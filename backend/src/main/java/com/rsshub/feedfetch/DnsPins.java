package com.rsshub.feedfetch;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pins the addresses the SSRF guard just validated so that the connection which follows uses <em>those</em>
 * addresses and not a fresh lookup. 03 §9.2 calls this out explicitly: validating and then resolving again is a
 * TOCTOU window, and a host can answer the second query with {@code 169.254.169.254}.
 * <p>
 * The registry is process-wide static state rather than a Spring bean, because the consumer is
 * {@link PinnedAddressResolverProvider}, which the JDK instantiates through {@code ServiceLoader} long before an
 * application context exists.
 * <p>
 * Pins are reference-counted and unioned: while any fetch holds a pin for a host, only addresses that some live
 * fetch validated are ever returned for it, and a pin disappears exactly when its fetch chain ends. Two
 * concurrent fetches of the same host therefore widen the set instead of fighting over it — every member of the
 * union passed the guard.
 * <p>
 * One lock guards the whole map. It is reachable from the JDK's resolver path, so it has to be simple enough to
 * reason about; at six concurrent fetches there is nothing to scale.
 */
public final class DnsPins {

    private static final Object LOCK = new Object();
    private static final Map<String, Entry> ENTRIES = new HashMap<>();

    private DnsPins() {
    }

    /**
     * Installs {@code addresses} for {@code host} until the returned scope is closed.
     *
     * @throws IllegalArgumentException if {@code addresses} is empty — pinning nothing would mean falling back to
     *         an unvalidated lookup while believing the opposite
     */
    public static Scope pin(String host, List<InetAddress> addresses) {
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("no validated addresses to pin for " + host);
        }
        String key = normalize(host);
        synchronized (LOCK) {
            Entry entry = ENTRIES.computeIfAbsent(key, k -> new Entry());
            entry.references++;
            entry.addresses.addAll(addresses);
        }
        // One release per pin, even if the caller closes twice: a second decrement would take the count below the
        // other holders and drop a pin that a live fetch is still relying on, which is exactly the unvalidated
        // fallback this class exists to prevent.
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            synchronized (LOCK) {
                Entry entry = ENTRIES.get(key);
                if (entry == null) {
                    return;
                }
                entry.references--;
                addresses.forEach(entry.addresses::remove);
                if (entry.references <= 0) {
                    ENTRIES.remove(key);
                }
            }
        };
    }

    /** The addresses currently pinned for a host, or empty when nothing holds a pin. */
    public static Optional<List<InetAddress>> lookup(String host) {
        synchronized (LOCK) {
            Entry entry = ENTRIES.get(normalize(host));
            if (entry == null || entry.references <= 0 || entry.addresses.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(Collections.unmodifiableList(new ArrayList<>(entry.addresses)));
        }
    }

    /** Test seam: true when at least one fetch currently holds a pin for this host. */
    public static boolean isPinned(String host) {
        return lookup(host).isPresent();
    }

    private static String normalize(String host) {
        String lowered = host.toLowerCase(Locale.ROOT);
        // URI.getHost() keeps the brackets around an IPv6 literal; InetAddress does not.
        return lowered.startsWith("[") && lowered.endsWith("]")
            ? lowered.substring(1, lowered.length() - 1)
            : lowered;
    }

    /** Closes the pin; use with try-with-resources around the whole redirect chain for one host. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final class Entry {
        int references;
        final Set<InetAddress> addresses = new LinkedHashSet<>();
    }
}
