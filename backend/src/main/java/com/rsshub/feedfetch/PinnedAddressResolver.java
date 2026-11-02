package com.rsshub.feedfetch;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Delegates to the JDK resolver except when {@link DnsPins} holds a validated answer for the name, in which case
 * only those addresses are used. This is what makes the pin real: without it, {@code HttpClient} resolves the host
 * again at connect time, after the guard has already been consulted.
 */
public final class PinnedAddressResolver implements InetAddressResolver {

    private final InetAddressResolver delegate;

    PinnedAddressResolver(InetAddressResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
        Optional<List<InetAddress>> pinned = DnsPins.lookup(host);
        if (pinned.isEmpty()) {
            return delegate.lookupByName(host, lookupPolicy);
        }
        List<InetAddress> addresses = pinned.get();
        List<InetAddress> preferred = preferFamily(addresses, lookupPolicy.characteristics());
        // Never answer "no addresses" from a pin: the caller asked for a family we do not hold, and an empty
        // result would look like a DNS failure for a host we just resolved successfully.
        return (preferred.isEmpty() ? addresses : preferred).stream();
    }

    /**
     * Narrows the pinned set only when the policy asks for exactly one family. The platform default carries both
     * bits ({@code IPV4 | IPV6 | IPV4_FIRST}), and treating that as "IPv4 only" would quietly drop every IPv6
     * address a dual-stack feed publishes.
     */
    private static List<InetAddress> preferFamily(List<InetAddress> addresses, int characteristics) {
        boolean v4 = (characteristics & LookupPolicy.IPV4) != 0;
        boolean v6 = (characteristics & LookupPolicy.IPV6) != 0;
        if (v4 == v6) {
            return addresses;
        }
        int bytes = v4 ? 4 : 16;
        return addresses.stream().filter(a -> a.getAddress().length == bytes).toList();
    }

    @Override
    public String lookupByAddress(byte[] addr) throws UnknownHostException {
        return delegate.lookupByAddress(addr);
    }
}
