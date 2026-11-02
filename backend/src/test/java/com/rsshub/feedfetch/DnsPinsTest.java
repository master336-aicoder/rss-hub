package com.rsshub.feedfetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 03 §9.2 asks for the address the guard validated to be the address actually connected to. Two things can go
 * wrong, and both are checked here: the pin registry (reference-counted, so one finished fetch cannot erase a
 * live fetch's pin) and the JDK hook that makes a pin visible to {@code HttpClient} — which only exists if the
 * resolver provider was really installed, so one test asks {@code InetAddress} itself rather than the bean.
 */
class DnsPinsTest {

    private static final String HOST = "pinned-by-a-test.invalid";

    @Test
    void aPinIsVisibleWhileItsScopeIsOpenAndGoneAfterwards() throws Exception {
        assertThat(DnsPins.lookup(HOST)).isEmpty();

        try (DnsPins.Scope ignored = DnsPins.pin(HOST, List.of(ipv4("192.0.2.1")))) {
            assertThat(DnsPins.isPinned(HOST)).isTrue();
            assertThat(pinned(HOST)).containsExactly(ipv4("192.0.2.1"));
        }

        assertThat(DnsPins.isPinned(HOST)).isFalse();
        assertThat(DnsPins.lookup(HOST)).isEmpty();
    }

    @Test
    void overlappingPinsUnionAndEachScopeOnlyRemovesItsOwnAddress() throws Exception {
        List<InetAddress> first = List.of(ipv4("192.0.2.1"));
        List<InetAddress> second = List.of(ipv4("192.0.2.2"));

        try (DnsPins.Scope a = DnsPins.pin(HOST, first)) {
            try (DnsPins.Scope b = DnsPins.pin(HOST, second)) {
                assertThat(pinned(HOST)).containsExactlyInAnyOrder(ipv4("192.0.2.1"), ipv4("192.0.2.2"));
            }
            // Closing the second fetch must not blind the first one, which is why this is refcounted.
            assertThat(pinned(HOST)).containsExactlyElementsOf(first);
        }

        assertThat(DnsPins.isPinned(HOST)).isFalse();
    }

    /** A double close must not drop the count past the other holder, or the live fetch silently loses its pin. */
    @Test
    void closingOneScopeTwiceLeavesTheOtherHolderPinned() throws Exception {
        List<InetAddress> mine = List.of(ipv4("192.0.2.7"));
        List<InetAddress> theirs = List.of(ipv4("192.0.2.8"));
        DnsPins.Scope heldBySomeoneElse = DnsPins.pin(HOST, theirs);
        DnsPins.Scope ours = DnsPins.pin(HOST, mine);

        ours.close();
        ours.close();

        assertThat(pinned(HOST)).containsExactlyElementsOf(theirs);
        heldBySomeoneElse.close();
        assertThat(DnsPins.isPinned(HOST)).isFalse();
    }

    @Test
    void namesAreMatchedCaseInsensitivelyAndIpv6LiteralsLoseTheirBrackets() throws Exception {
        List<InetAddress> answer = List.of(ipv4("192.0.2.5"));

        try (DnsPins.Scope ignored = DnsPins.pin("MiXeD.Case.Host", answer)) {
            assertThat(DnsPins.isPinned("mixed.case.HOST")).isTrue();
            assertThat(DnsPins.isPinned("MIXED.case.host")).isTrue();
        }
        try (DnsPins.Scope ignored = DnsPins.pin("[::1]", answer)) {
            assertThat(DnsPins.isPinned("::1")).isTrue();
        }
    }

    @Test
    void pinningNothingIsRefusedBecauseItWouldSilentlyFallBackToAnUnvalidatedLookup() {
        assertThatThrownBy(() -> DnsPins.pin(HOST, List.of())).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(HOST);
    }

    /**
     * The assertion the whole defence rests on: a name with no DNS record resolves while a pin is open, which only
     * happens if {@link PinnedAddressResolverProvider} was installed as the system-wide provider. Without it the
     * guard validates one address and {@code HttpClient} connects to whatever a second, unvalidated lookup returns.
     * <p>
     * Nothing looks this name up before pinning it: the JDK caches a failed lookup for ~10 seconds and would
     * replay that answer instead of reaching the resolver, which would make the test fail for the wrong reason.
     * RFC 6761 reserves {@code .invalid}, so there is no record to find.
     */
    @Test
    void theInstalledProviderAnswersForAPinnedNameThatHasNoDnsRecord() throws Exception {
        String name = "pin-probe-" + System.nanoTime() + ".invalid";

        try (DnsPins.Scope ignored = DnsPins.pin(name, List.of(ipv4("192.0.2.9")))) {
            assertThat(InetAddress.getByName(name)).isEqualTo(ipv4("192.0.2.9"));
            assertThat(InetAddress.getAllByName(name)).containsExactly(ipv4("192.0.2.9"));
        }

        assertThat(DnsPins.lookup(name)).isEmpty();
    }

    @Test
    void anUnpinnedNameIsHandedToTheJdkResolverUntouched() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        List<InetAddress> answered = new PinnedAddressResolver(delegate(calls)).lookupByName("example.com",
            platformPolicy()).toList();

        assertThat(calls).hasValue(1);
        assertThat(answered).containsExactly(ipv4("192.0.2.99"));
    }

    /** The policy the JDK uses unless -Djava.net.preferIPv4Stack is set: both families, IPv4 first. */
    @Test
    void aDualFamilyPolicyKeepsEveryPinnedAddress() throws Exception {
        InetAddress v4 = ipv4("192.0.2.3");
        InetAddress v6 = InetAddress.getByName("2001:db8::1");

        try (DnsPins.Scope ignored = DnsPins.pin(HOST, List.of(v4, v6))) {
            assertThat(new PinnedAddressResolver(delegate(new AtomicInteger()))
                .lookupByName(HOST, LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6 | LookupPolicy.IPV4_FIRST))
                .toList()).containsExactlyInAnyOrder(v4, v6);
        }
    }

    @Test
    void aSingleFamilyPolicyIsHonouredForThePinnedAddresses() throws Exception {
        InetAddress v4 = ipv4("192.0.2.3");
        InetAddress v6 = InetAddress.getByName("2001:db8::1");

        try (DnsPins.Scope ignored = DnsPins.pin(HOST, List.of(v4, v6))) {
            List<InetAddress> answered = new PinnedAddressResolver(delegate(new AtomicInteger()))
                .lookupByName(HOST, LookupPolicy.of(LookupPolicy.IPV4)).toList();

            assertThat(answered).containsExactly(v4);
            assertThat(answered).allMatch(Inet4Address.class::isInstance);
        }
    }

    /** A family we were not told about must not read as "DNS failed", because the guard did resolve the host. */
    @Test
    void anIPv4RequestAgainstAnIpv6OnlyPinFallsBackToThePinnedSet() throws Exception {
        InetAddress v6 = InetAddress.getByName("2001:db8::2");

        try (DnsPins.Scope ignored = DnsPins.pin(HOST, List.of(v6))) {
            assertThat(new PinnedAddressResolver(delegate(new AtomicInteger()))
                .lookupByName(HOST, LookupPolicy.of(LookupPolicy.IPV4)).toList()).containsExactly(v6);
        }
    }

    // --- fixtures ------------------------------------------------------------------------------------------

    private static List<InetAddress> pinned(String host) {
        return DnsPins.lookup(host).orElseThrow(() -> new AssertionError("nothing pinned for " + host));
    }

    private static LookupPolicy platformPolicy() {
        return LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6 | LookupPolicy.IPV4_FIRST);
    }

    /** The delegate stands in for the JDK resolver: it answers a fixed address and counts how often it is asked. */
    private static InetAddressResolver delegate(AtomicInteger calls) {
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
                throws UnknownHostException {
                calls.incrementAndGet();
                return Stream.of(ipv4("192.0.2.99"));
            }

            @Override
            public String lookupByAddress(byte[] addr) {
                return "not-looked-up-by-address-in-this-test";
            }
        };
    }

    /** TEST-NET-1 (RFC 5737): a literal never touches DNS, and the JDK resolver is skipped for literals. */
    private static InetAddress ipv4(String dottedQuad) {
        try {
            return InetAddress.getByName(dottedQuad);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
