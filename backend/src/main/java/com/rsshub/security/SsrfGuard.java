package com.rsshub.security;

import com.rsshub.common.SsrfBlockedException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Every outbound URL the service is asked to fetch must pass through here, including each redirect hop.
 * {@link ValidatedTarget} returns the addresses seen at validation time so the fetcher can connect to those
 * instead of resolving again — resolving twice is the DNS-rebinding window.
 */
@Component
public class SsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);

    private final List<String> allowList;

    public SsrfGuard(SsrfProperties properties) {
        this.allowList = properties.allowPrivatePrefixes();
    }

    public ValidatedTarget validate(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            throw new SsrfBlockedException("malformed_url");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new SsrfBlockedException("scheme_not_allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfBlockedException("missing_host");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SsrfBlockedException("dns_unresolved");
        }
        if (addresses.length == 0) {
            throw new SsrfBlockedException("dns_unresolved");
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                if (!explicitlyAllowed(address)) {
                    throw new SsrfBlockedException("private_target:" + address.getHostAddress());
                }
                log.info("resolved [{}] to blocked address [{}], allowed by rsshub.ssrf.allow-private-prefixes",
                    host, address.getHostAddress());
            }
        }
        return new ValidatedTarget(uri, Arrays.asList(addresses));
    }

    private boolean isBlocked(InetAddress address) {
        return address.isLoopbackAddress()
            || address.isAnyLocalAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()
            || isCarrierGradeNat(address)
            || isReservedOrTransitionAddress(address);
    }

    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] b = address.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xff) >= 64 && (b[1] & 0xff) <= 127;
    }

    private static boolean isReservedOrTransitionAddress(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length == 4) {
            // 192.0.0.0/24 IETF reserved, 198.18.0.0/15 benchmarking, 192.88.99.0/24 6to4 relay anycast.
            return (b[0] & 0xff) == 192 && (b[1] & 0xff) == 0
                || (b[0] & 0xff) == 198 && (b[1] & 0xff) >= 18 && (b[1] & 0xff) <= 19
                || (b[0] & 0xff) == 192 && (b[1] & 0xff) == 88 && (b[2] & 0xff) == 99;
        }
        if (b.length == 16) {
            boolean uniqueLocal = (b[0] & 0xfe) == 0xfc;
            // 2001::/32 Teredo can tunnel a private IPv4 through a public relay.
            boolean teredo = (b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01
                && (b[2] & 0xff) == 0x00 && (b[3] & 0xff) == 0x00;
            return uniqueLocal || teredo;
        }
        return false;
    }

    private boolean explicitlyAllowed(InetAddress address) {
        String ip = address.getHostAddress();
        return allowList.stream().anyMatch(prefix -> ip.equals(prefix) || ip.startsWith(prefix));
    }

    public record ValidatedTarget(URI uri, List<InetAddress> resolvedAddresses) {}
}
