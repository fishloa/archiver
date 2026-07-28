package place.icomb.archiver.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedPeerResolverTest {

  private static final Duration TTL = Duration.ofSeconds(30);

  @Test
  void trustsAddressInsideConfiguredCidr() {
    var resolver = new TrustedPeerResolver(List.of("10.0.9.0/24"), List.of(), TTL);
    assertThat(resolver.isTrusted("10.0.9.9")).isTrue();
  }

  @Test
  void rejectsAddressOutsideConfiguredCidr() {
    var resolver = new TrustedPeerResolver(List.of("10.0.9.0/24"), List.of(), TTL);
    assertThat(resolver.isTrusted("203.0.113.7")).isFalse();
  }

  @Test
  void trustsLoopbackWhenLoopbackCidrConfigured() {
    var resolver = new TrustedPeerResolver(List.of("127.0.0.1/32"), List.of(), TTL);
    assertThat(resolver.isTrusted("127.0.0.1")).isTrue();
  }

  @Test
  void trustsAddressResolvedFromHostname() {
    // localhost resolves to 127.0.0.1 on every platform we build on.
    var resolver = new TrustedPeerResolver(List.of(), List.of("localhost"), TTL);
    assertThat(resolver.isTrusted("127.0.0.1")).isTrue();
  }

  @Test
  void unresolvableHostnameDoesNotWidenTheSet() {
    var resolver = new TrustedPeerResolver(List.of(), List.of("no-such-host.invalid"), TTL);
    assertThat(resolver.isTrusted("127.0.0.1")).isFalse();
    assertThat(resolver.isTrusted("10.0.9.9")).isFalse();
  }

  @Test
  void malformedCidrIsDroppedRatherThanWideningTheSet() {
    var resolver = new TrustedPeerResolver(List.of("not-a-cidr"), List.of(), TTL);
    assertThat(resolver.isTrusted("10.0.9.9")).isFalse();
  }

  @Test
  void emptyConfigurationTrustsNothing() {
    var resolver = new TrustedPeerResolver(List.of(), List.of(), TTL);
    assertThat(resolver.isTrusted("127.0.0.1")).isFalse();
    assertThat(resolver.isTrusted("10.0.9.9")).isFalse();
  }

  @Test
  void nullOrBlankPeerIsNotTrusted() {
    var resolver = new TrustedPeerResolver(List.of("0.0.0.0/0"), List.of(), TTL);
    assertThat(resolver.isTrusted(null)).isFalse();
    assertThat(resolver.isTrusted("  ")).isFalse();
  }

  @Test
  void eitherEntryKindAloneIsSufficient() {
    var resolver = new TrustedPeerResolver(List.of("10.0.9.0/24"), List.of("localhost"), TTL);
    assertThat(resolver.isTrusted("10.0.9.9")).isTrue();
    assertThat(resolver.isTrusted("127.0.0.1")).isTrue();
  }
}
