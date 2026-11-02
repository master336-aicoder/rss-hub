package com.rsshub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.rsshub.common.SsrfBlockedException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The gate list from doc/design/04-roadmap.md M0: loopback, link-local (cloud metadata), private, IPv6 and
 * non-HTTP targets must all be refused before a socket opens.
 */
class SsrfGuardTest {

    private final SsrfGuard guard = new SsrfGuard(new SsrfProperties(List.of()));

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1/feed",
        "http://127.0.0.1:8080/feed",
        "http://localhost:8080/actuator/env",
        "http://0.0.0.0/",
        "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
        "http://10.0.0.5/rss",
        "http://192.168.1.1/admin",
        "http://172.16.9.9/feed.xml",
        "http://[::1]/feed",
        "http://[fe80::1]/feed",
        "http://[fc00::1234]/feed",
        "http://100.64.0.1/feed",
        "http://192.0.0.1/feed",
        "http://198.18.0.1/feed",
        "http://224.0.0.1/feed"
    })
    @DisplayName("private and reserved targets are refused")
    void refusesPrivateTargets(String url) {
        assertThatExceptionOfType(SsrfBlockedException.class).isThrownBy(() -> guard.validate(url));
    }

    @Test
    void localhostHostnameIsRefused() {
        // "localhost" only reaches the guard through DNS, so assert on whatever the resolver answers.
        Assumptions.assumeTrue(resolves("localhost"));
        assertThatExceptionOfType(SsrfBlockedException.class)
            .isThrownBy(() -> guard.validate("http://localhost/feed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ftp://example.com/feed.xml",
        "file:///etc/passwd",
        "gopher://127.0.0.1:11211/",
        "http://",
        "https:///feed",
        "not a url at all"
    })
    void refusesNonHttpTargets(String url) {
        assertThatExceptionOfType(SsrfBlockedException.class).isThrownBy(() -> guard.validate(url));
    }

    @Test
    void publicHostPasses() {
        Assumptions.assumeTrue(resolves("example.com"), "no DNS on this machine, cannot exercise the allow path");
        assertThatCode(() -> guard.validate("https://example.com/feed.xml")).doesNotThrowAnyException();
    }

    @Test
    void allowListIsTheOnlyWayBackIn() {
        SsrfGuard guarded = new SsrfGuard(new SsrfProperties(List.of("127.")));
        assertThatCode(() -> guarded.validate("http://127.0.0.1/internal.rss")).doesNotThrowAnyException();
    }

    @Test
    void resolvedAddressesAreHandedBackForTheFetcherToPin() {
        // The caller must connect to these instead of resolving again; that gap is the DNS-rebinding window.
        SsrfGuard guarded = new SsrfGuard(new SsrfProperties(List.of("127.")));
        var target = guarded.validate("http://127.0.0.1/feed");
        assertThat(target.uri().getHost()).isEqualTo("127.0.0.1");
        assertThat(target.resolvedAddresses()).containsExactly(InetAddress.getLoopbackAddress());
    }

    private static boolean resolves(String host) {
        try {
            InetAddress.getByName(host);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
