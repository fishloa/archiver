package place.icomb.archiver.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
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

  @Test
  void cacheReuseWithinTtl() {
    var resolver = new CountingResolver(List.of(), List.of("localhost"), Duration.ofMillis(100));

    // First call should trigger a lookup
    assertThat(resolver.isTrusted("127.0.0.1")).isTrue();
    assertThat(resolver.lookupCallCount).isEqualTo(1);

    // Second call within TTL should reuse cached result
    assertThat(resolver.isTrusted("127.0.0.1")).isTrue();
    assertThat(resolver.lookupCallCount).isEqualTo(1);
  }

  @Test
  void cacheExpiresAfterTtl() throws InterruptedException {
    var resolver = new CountingResolver(List.of(), List.of("localhost"), Duration.ofMillis(50));

    // First call should trigger a lookup
    assertThat(resolver.isTrusted("127.0.0.1")).isTrue();
    assertThat(resolver.lookupCallCount).isEqualTo(1);

    // Wait for cache to expire
    Thread.sleep(60);

    // Second call after TTL expiry should trigger another lookup
    assertThat(resolver.isTrusted("127.0.0.1")).isTrue();
    assertThat(resolver.lookupCallCount).isEqualTo(2);
  }

  static class CountingResolver extends TrustedPeerResolver {
    int lookupCallCount = 0;

    CountingResolver(List<String> cidrs, List<String> hostnames, Duration cacheTtl) {
      super(cidrs, hostnames, cacheTtl);
    }

    @Override
    protected Set<String> lookup(String hostname) {
      lookupCallCount++;
      return super.lookup(hostname);
    }
  }
}
