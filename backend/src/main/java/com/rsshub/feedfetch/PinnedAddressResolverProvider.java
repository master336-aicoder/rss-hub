package com.rsshub.feedfetch;

import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;

/**
 * Registers {@link PinnedAddressResolver} with the JDK. The JVM installs the first provider that
 * {@code ServiceLoader} finds, so this class plus {@code META-INF/services/java.net.spi.InetAddressResolverProvider}
 * is the whole mechanism — if either is missing, fetches silently go back to resolving twice and the SSRF guard
 * loses its pin. {@link PinnedAddressResolverTest} asserts the pin is honoured, which is the only way to notice.
 */
public final class PinnedAddressResolverProvider extends InetAddressResolverProvider {

    /** ServiceLoader requires a public no-arg constructor; the resolver is built per-lookup-request in {@link #get}. */
    public PinnedAddressResolverProvider() {
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        return new PinnedAddressResolver(configuration.builtinResolver());
    }

    @Override
    public String name() {
        return "rss-hub pinned-address resolver";
    }
}
