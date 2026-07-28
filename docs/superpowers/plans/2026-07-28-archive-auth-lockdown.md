# Archive Auth Lockdown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** No anonymous access to the archive from the WAN — every request is attributable to an email on the `app_user_email` allowlist — plus Apple ID sign-in on `archive.czernin.eu`.

**Architecture:** Two enforcement layers. nginx clears `X-Auth-Email` on every location that does not set it, so a client-supplied value never enters the network. The backend independently honours `X-Auth-Email` only when the request's TCP peer is in a trusted set (the resolved `web` container address, plus configured CIDRs). `SecurityConfig` flips from permit-by-default to authenticate-by-default. `/api/mcp/**` moves from `permitAll()` to a static bearer token.

**Tech Stack:** Java 25 / Spring Boot 4.0 (Spring Data JDBC, Spring Security), SvelteKit + Tailwind v4, nginx, oauth2-proxy, Docker Compose via Portainer.

**Spec:** `docs/superpowers/specs/2026-07-27-archive-auth-lockdown-design.md`

## Global Constraints

- `JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current` for every Gradle command.
- Backend formatting: `./gradlew spotlessApply` before every commit. Google Java Format.
- New backend tests use Java `HttpClient`, never REST Assured (Groovy 5 incompatibility with Spring Boot 4.0).
- Backend tests use Testcontainers PostgreSQL — Docker must be running.
- No `Co-Authored-By` lines in commit messages.
- Zero inline `style=""` attributes in frontend markup. Use design-system classes or a scoped `<style>` block.
- Do not touch unrelated code. No opportunistic reformatting or reorganising.
- Trusted CIDR default, exact value: `127.0.0.1/32,::1/128,10.0.9.0/24,192.168.16.0/22`
- Trusted proxy hostname default, exact value: `web`
- Nothing deploys until every task is complete. The site must not be left half-locked.

---

### Task 1: TrustedPeerResolver

Decides whether a TCP peer address is allowed to assert identity. Pure logic, no Spring wiring — that lands in Task 2.

**Files:**
- Create: `backend/src/main/java/place/icomb/archiver/config/TrustedPeerResolver.java`
- Test: `backend/src/test/java/place/icomb/archiver/config/TrustedPeerResolverTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `TrustedPeerResolver(List<String> cidrs, List<String> hostnames, Duration cacheTtl)` and `boolean isTrusted(String remoteAddr)`. Task 2 constructs it and calls `isTrusted`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/place/icomb/archiver/config/TrustedPeerResolverTest.java`:

```java
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
    var resolver =
        new TrustedPeerResolver(List.of(), List.of("no-such-host.invalid"), TTL);
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current \
  ./gradlew test --tests '*TrustedPeerResolverTest'
```

Expected: FAIL — compilation error, `TrustedPeerResolver` does not exist.

- [ ] **Step 3: Write minimal implementation**

`backend/src/main/java/place/icomb/archiver/config/TrustedPeerResolver.java`:

```java
package place.icomb.archiver.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * Decides whether a request's TCP peer is allowed to assert an identity via the {@code
 * X-Auth-Email} header.
 *
 * <p>Two kinds of entry are supported: CIDR ranges, and hostnames resolved through DNS. Hostnames
 * exist because container addresses are dynamic — the reverse proxy's address changes on every
 * redeploy, so a pinned IP would rot. Resolution results are cached briefly so this costs no DNS
 * lookup per request while still self-healing across redeploys.
 *
 * <p>Fails closed throughout: a malformed CIDR or an unresolvable hostname is dropped, never
 * widened into a permissive default.
 */
public class TrustedPeerResolver {

  private static final Logger log = LoggerFactory.getLogger(TrustedPeerResolver.class);

  private final List<IpAddressMatcher> matchers;
  private final List<String> hostnames;
  private final Duration cacheTtl;

  private volatile Set<String> cachedHostAddresses = Set.of();
  private volatile Instant cacheExpiresAt = Instant.EPOCH;

  public TrustedPeerResolver(List<String> cidrs, List<String> hostnames, Duration cacheTtl) {
    this.matchers = buildMatchers(cidrs);
    this.hostnames = List.copyOf(hostnames);
    this.cacheTtl = cacheTtl;
  }

  private static List<IpAddressMatcher> buildMatchers(List<String> cidrs) {
    var built = new ArrayList<IpAddressMatcher>();
    for (String cidr : cidrs) {
      if (cidr == null || cidr.isBlank()) {
        continue;
      }
      try {
        built.add(new IpAddressMatcher(cidr.trim()));
      } catch (IllegalArgumentException e) {
        log.warn("Ignoring malformed trusted CIDR '{}': {}", cidr, e.getMessage());
      }
    }
    return List.copyOf(built);
  }

  public boolean isTrusted(String remoteAddr) {
    if (remoteAddr == null || remoteAddr.isBlank()) {
      return false;
    }
    String peer = remoteAddr.trim();

    for (IpAddressMatcher matcher : matchers) {
      try {
        if (matcher.matches(peer)) {
          return true;
        }
      } catch (IllegalArgumentException e) {
        // Peer was not a parseable address for this matcher — treat as no match.
        log.debug("Peer '{}' not comparable against a configured CIDR: {}", peer, e.getMessage());
      }
    }

    return resolvedHostAddresses().contains(peer);
  }

  private Set<String> resolvedHostAddresses() {
    if (hostnames.isEmpty()) {
      return Set.of();
    }
    Instant now = Instant.now();
    if (now.isBefore(cacheExpiresAt)) {
      return cachedHostAddresses;
    }

    var resolved = new HashSet<String>();
    for (String hostname : hostnames) {
      if (hostname == null || hostname.isBlank()) {
        continue;
      }
      try {
        for (InetAddress address : InetAddress.getAllByName(hostname.trim())) {
          resolved.add(address.getHostAddress());
        }
      } catch (UnknownHostException e) {
        log.warn("Trusted proxy hostname '{}' did not resolve; ignoring it", hostname);
      }
    }

    cachedHostAddresses = Set.copyOf(resolved);
    cacheExpiresAt = now.plus(cacheTtl);
    return cachedHostAddresses;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current \
  ./gradlew test --tests '*TrustedPeerResolverTest'
```

Expected: PASS, 9 tests.

- [ ] **Step 5: Format and commit**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current ./gradlew spotlessApply
cd .. && git add backend/src/main/java/place/icomb/archiver/config/TrustedPeerResolver.java \
        backend/src/test/java/place/icomb/archiver/config/TrustedPeerResolverTest.java
git commit -m "feat: add TrustedPeerResolver for X-Auth-Email peer validation"
```

---

### Task 2: ProxyAuthFilter honours X-Auth-Email only from a trusted peer

This is the fix for the live privilege-escalation defect. The regression test must fail against the current code.

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/config/ProxyAuthFilter.java`
- Modify: `backend/src/main/java/place/icomb/archiver/config/SecurityConfig.java:25-40`
- Modify: `backend/src/main/resources/application.yml:38-66` (add to the `archiver:` block)
- Test: `backend/src/test/java/place/icomb/archiver/controller/AuthSpoofingRegressionTest.java`

**Interfaces:**
- Consumes: `TrustedPeerResolver.isTrusted(String)` from Task 1.
- Produces: `ProxyAuthFilter(AppUserRepository, TrustedPeerResolver)` — a two-arg constructor replacing the one-arg form. Also sets request attribute `archiver.signedInAs` to the email string when the peer is trusted and the email authenticated with the identity provider but matched no `app_user_email` row. Task 5 reads that attribute.

- [ ] **Step 1: Write the failing regression test**

`backend/src/test/java/place/icomb/archiver/controller/AuthSpoofingRegressionTest.java`:

```java
package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression test for the X-Auth-Email spoofing defect: the backend granted ROLE_ADMIN purely on
 * the strength of a client-supplied header.
 *
 * <p>Trusted peers are overridden to nothing here. Without that override the test would pass
 * vacuously, because the test client connects over loopback and loopback is trusted by default.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthSpoofingRegressionTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  private static final String ADMIN_EMAIL = "spoof-admin@example.com";

  @LocalServerPort private int port;

  @Autowired private JdbcClient jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    // Untrust every peer, including loopback, so the reject path is actually exercised.
    registry.add("archiver.auth.trusted-cidrs", () -> "");
    registry.add("archiver.auth.trusted-proxy-hosts", () -> "no-such-host.invalid");
  }

  @BeforeEach
  void seedAdmin() {
    jdbc.sql("DELETE FROM app_user_email WHERE email = :e").param("e", ADMIN_EMAIL).update();
    jdbc.sql("DELETE FROM app_user WHERE display_name = 'SpoofTest Admin'").update();

    Long userId =
        jdbc.sql(
                "INSERT INTO app_user (display_name, role) "
                    + "VALUES ('SpoofTest Admin', 'admin') RETURNING id")
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:id, :e)")
        .param("id", userId)
        .param("e", ADMIN_EMAIL)
        .update();
  }

  @Test
  void spoofedAdminEmailFromUntrustedPeerIsRejected() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/users"))
            .header("X-Auth-Email", ADMIN_EMAIL)
            .GET()
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isNotEqualTo(200);
    assertThat(response.body()).doesNotContain(ADMIN_EMAIL);
  }

  @Test
  void spoofedEmailWithForwardedForSpoofAlsoRejected() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/users"))
            .header("X-Auth-Email", ADMIN_EMAIL)
            .header("X-Forwarded-For", "127.0.0.1")
            .header("X-Real-IP", "127.0.0.1")
            .GET()
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isNotEqualTo(200);
  }
}
```

**Test conventions in this codebase:** there is no shared integration-test base class. Each test class declares its own `@Container PostgreSQLContainer` and its own `@DynamicPropertySource` wiring the datasource, as `AdminControllerTest` does. DB access in tests uses `JdbcClient` (`.sql(...).param(...).update()`), not `JdbcTemplate`. Shared constants live in `place.icomb.archiver.TestAuth`.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current \
  ./gradlew test --tests '*AuthSpoofingRegressionTest'
```

Expected: FAIL — `spoofedAdminEmailFromUntrustedPeerIsRejected` gets 200 and the admin list in the body. That failure *is* the defect.

- [ ] **Step 3: Add configuration properties**

In `backend/src/main/resources/application.yml`, inside the existing `archiver:` block (after the `processor:` entry at line 41-42):

```yaml
  auth:
    trusted-cidrs: ${AUTH_TRUSTED_CIDRS:127.0.0.1/32,::1/128,10.0.9.0/24,192.168.16.0/22}
    trusted-proxy-hosts: ${AUTH_TRUSTED_PROXY_HOSTS:web}
    trusted-peer-cache-seconds: ${AUTH_TRUSTED_PEER_CACHE_SECONDS:30}
```

Do not add anything to `application-test.yml`. Loopback is in the default list, so the existing controller tests keep passing unchanged.

- [ ] **Step 4: Rewrite ProxyAuthFilter**

`backend/src/main/java/place/icomb/archiver/config/ProxyAuthFilter.java`:

```java
package place.icomb.archiver.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import place.icomb.archiver.model.AppUser;
import place.icomb.archiver.repository.AppUserRepository;

/**
 * Establishes the caller's identity from the {@code X-Auth-Email} header set by the reverse proxy
 * after oauth2-proxy has authenticated them.
 *
 * <p>The header is honoured only when the request's TCP peer is trusted. Peer identity comes from
 * {@link HttpServletRequest#getRemoteAddr()} and never from {@code X-Forwarded-For} or {@code
 * X-Real-IP}, both of which the client controls.
 */
public class ProxyAuthFilter extends OncePerRequestFilter {

  /** Request attribute holding the email of a caller who signed in but is not on the allowlist. */
  public static final String SIGNED_IN_AS_ATTRIBUTE = "archiver.signedInAs";

  private final AppUserRepository appUserRepository;
  private final TrustedPeerResolver trustedPeerResolver;

  public ProxyAuthFilter(
      AppUserRepository appUserRepository, TrustedPeerResolver trustedPeerResolver) {
    this.appUserRepository = appUserRepository;
    this.trustedPeerResolver = trustedPeerResolver;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String email = request.getHeader("X-Auth-Email");

    if (email != null
        && !email.isBlank()
        && trustedPeerResolver.isTrusted(request.getRemoteAddr())) {
      String normalised = email.trim();
      var found = appUserRepository.findByEmail(normalised.toLowerCase());
      if (found.isPresent()) {
        AppUser user = found.get();
        var auth =
            new UsernamePasswordAuthenticationToken(normalised, null, buildAuthorities(user));
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
      } else {
        // Authenticated with the identity provider, but not on the allowlist. Record it so the
        // UI can say "signed in as X, no access" instead of bouncing back to sign-in forever.
        request.setAttribute(SIGNED_IN_AS_ATTRIBUTE, normalised);
      }
    }

    filterChain.doFilter(request, response);
  }

  private List<SimpleGrantedAuthority> buildAuthorities(AppUser user) {
    if ("admin".equals(user.getRole())) {
      return List.of(
          new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
    return List.of(new SimpleGrantedAuthority("ROLE_USER"));
  }
}
```

- [ ] **Step 5: Wire the resolver in SecurityConfig**

In `backend/src/main/java/place/icomb/archiver/config/SecurityConfig.java`, replace the constructor and field block at lines 22-30:

```java
  private final AppUserRepository appUserRepository;
  private final String processorToken;
  private final TrustedPeerResolver trustedPeerResolver;

  public SecurityConfig(
      AppUserRepository appUserRepository,
      @Value("${archiver.processor.token}") String processorToken,
      @Value("${archiver.auth.trusted-cidrs:}") String trustedCidrs,
      @Value("${archiver.auth.trusted-proxy-hosts:}") String trustedProxyHosts,
      @Value("${archiver.auth.trusted-peer-cache-seconds:30}") long trustedPeerCacheSeconds) {
    this.appUserRepository = appUserRepository;
    this.processorToken = processorToken;
    this.trustedPeerResolver =
        new TrustedPeerResolver(
            splitCsv(trustedCidrs),
            splitCsv(trustedProxyHosts),
            Duration.ofSeconds(trustedPeerCacheSeconds));
  }

  private static List<String> splitCsv(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }
```

Add imports: `java.time.Duration`, `java.util.Arrays`.

Then update the filter registration at line 37-38:

```java
        .addFilterBefore(
            new ProxyAuthFilter(appUserRepository, trustedPeerResolver),
            UsernamePasswordAuthenticationFilter.class)
```

- [ ] **Step 6: Run the regression test and the full existing suite**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current \
  ./gradlew test --tests '*AuthSpoofingRegressionTest'
```

Expected: PASS, 2 tests.

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current ./gradlew test
```

Expected: PASS. `AdminControllerTest`, `AuthControllerTest`, `ProfileControllerTest` and `AdminPipelineControllerTest` must all still pass without modification — loopback is trusted by default. If any of them fail, the default CIDR list is wrong; fix the default rather than the tests.

- [ ] **Step 7: Format and commit**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current ./gradlew spotlessApply
cd .. && git add backend/src/main/java/place/icomb/archiver/config/ProxyAuthFilter.java \
        backend/src/main/java/place/icomb/archiver/config/SecurityConfig.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/place/icomb/archiver/controller/AuthSpoofingRegressionTest.java
git commit -m "fix: only honour X-Auth-Email from a trusted TCP peer

The backend derived identity, including ROLE_ADMIN, from a client-supplied
X-Auth-Email header. Three nginx locations forwarded that header verbatim, so
any unauthenticated caller holding one allowlisted address could read and write
the admin API over the internet.

Identity is now established only when the request's TCP peer is in the trusted
set. Peer address comes from getRemoteAddr(), never from X-Forwarded-For."
```

---

### Task 3: nginx clears X-Auth-Email where it does not set it

The proxy-side half of the fix. Independent of Tasks 1-2 — a spoofed header stops entering the network at all.

**Files:**
- Modify: `web/nginx.conf:113-126` (`/api/mcp/`), `:128-141` (events), `:160-168` (`@api_anon`)

**Interfaces:**
- Consumes: nothing.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add the header clear to all three locations**

In `web/nginx.conf`, add this line to each of the three `location` blocks listed above, immediately after their existing `proxy_set_header X-Forwarded-Proto $scheme;` line:

```nginx
        proxy_set_header X-Auth-Email "";
```

The three blocks are `location /api/mcp/`, `location ~ ^/api/(records|processor)/events$`, and `location @api_anon`. Do not touch `location /api/` or `location /` — those already set the header from `$auth_email` and must keep doing so.

- [ ] **Step 2: Verify the config parses**

```bash
docker run --rm -v "$PWD/web/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:alpine nginx -t
```

Expected: `syntax is ok` / `test is successful`. The `resolver 127.0.0.11` line may warn outside Docker Compose; a warning is fine, an error is not.

- [ ] **Step 3: Commit**

```bash
git add web/nginx.conf
git commit -m "fix: clear X-Auth-Email in nginx locations that do not set it

@api_anon, /api/mcp/ and the events route declared their own proxy_set_header
directives, so nginx did not apply the server-level headers and forwarded a
client-supplied X-Auth-Email verbatim to the backend."
```

---

### Task 4: SecurityConfig denies by default

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/config/SecurityConfig.java:41-105`
- Test: `backend/src/test/java/place/icomb/archiver/controller/AnonymousAccessTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/place/icomb/archiver/controller/AnonymousAccessTest.java`:

```java
package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Every read endpoint must refuse an unauthenticated caller. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnonymousAccessTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @LocalServerPort private int port;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  private HttpResponse<String> getAnonymously(String path) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/records",
        "/api/records/1",
        "/api/viewer/pipeline-stats",
        "/api/v1/archives",
        "/api/v1/documents",
        "/api/admin/users"
      })
  void readEndpointsRefuseAnonymousCallers(String path) throws Exception {
    var response = getAnonymously(path);
    assertThat(response.statusCode()).isIn(401, 403);
  }

  @Test
  void authMeStillAnswersForSignedOutCallers() throws Exception {
    var response = getAnonymously("/api/auth/me");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"authenticated\":false");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current \
  ./gradlew test --tests '*AnonymousAccessTest'
```

Expected: FAIL — the `/api/records`, `/api/viewer`, `/api/v1` cases return 200 because `GET /api/**` is `permitAll()`.

- [ ] **Step 3: Invert the matchers**

In `SecurityConfig.filterChain`, make exactly these changes to the `authorizeHttpRequests` block. Leave every other matcher as it is.

Delete these matchers entirely:

```java
                    // GET requests are read-only — allow anonymous
                    .requestMatchers(HttpMethod.GET, "/api/**")
                    .permitAll()
```

```java
                    // Semantic search is a read-only POST
                    .requestMatchers(HttpMethod.POST, "/api/search/semantic")
                    .permitAll()
```

```java
                    // On-demand worker translation
                    .requestMatchers(HttpMethod.POST, "/api/translate")
                    .permitAll()
```

```java
                    // Family tree maintenance — idempotent
                    .requestMatchers(HttpMethod.POST, "/api/family-tree/reload")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/family-tree/invalidate-matches")
                    .permitAll()
```

Add, immediately after the `/api/admin/**` GET matcher (which must stay first so it is not shadowed):

```java
                    // All reads require an allowlisted user
                    .requestMatchers(HttpMethod.GET, "/api/**")
                    .hasAnyRole("USER", "ADMIN", "PROCESSOR")
```

`ROLE_PROCESSOR` is included because workers issue GETs against `/api/processor/**` and `/api/ingest/**` with the Bearer token.

Replace the final matcher at lines 103-105:

```java
                    // Everything else — actuator health, swagger, static resources
                    .requestMatchers("/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated());
```

Keep `/api/auth/**` `permitAll()` — `/api/auth/me` must answer for a signed-out caller so the UI can tell "not signed in" from "signed in, no access".

- [ ] **Step 4: Run the test**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current \
  ./gradlew test --tests '*AnonymousAccessTest'
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Run the full suite and check the container health probe**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current ./gradlew test
```

Expected: PASS. If a previously-passing test now fails with 401/403, that test was relying on anonymous read access — give it an authenticated caller rather than re-opening the endpoint.

Confirm the compose health check still works. Check what `deploy/docker-compose.yml` uses for the backend `healthcheck` — if it probes an `/actuator/**` path other than `health`, add that exact path to the `permitAll()` list in Step 3.

- [ ] **Step 6: Format and commit**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current ./gradlew spotlessApply
cd .. && git add backend/src/main/java/place/icomb/archiver/config/SecurityConfig.java \
        backend/src/test/java/place/icomb/archiver/controller/AnonymousAccessTest.java
git commit -m "feat: deny by default, drop the blanket anonymous read permit

GET /api/** now requires an allowlisted user, anyRequest() is authenticated,
and the unauthenticated write endpoints on /api/family-tree and /api/translate
are closed."
```

---

### Task 5: Signed-in-but-not-allowlisted is distinguishable

Without this, anyone outside the allowlist gets a redirect loop: the frontend sends them to `/signin`, oauth2-proxy sees a valid session and sends them straight back.

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/controller/AuthController.java`
- Test: `backend/src/test/java/place/icomb/archiver/controller/AuthControllerTest.java`

**Interfaces:**
- Consumes: `ProxyAuthFilter.SIGNED_IN_AS_ATTRIBUTE` from Task 2.
- Produces: `GET /api/auth/me` returns `{"authenticated": false, "signedInAs": "<email>"}` when the caller authenticated with the identity provider but is not on the allowlist. Task 6 consumes `signedInAs` from the frontend.

- [ ] **Step 1: Write the failing test**

Append to `backend/src/test/java/place/icomb/archiver/controller/AuthControllerTest.java`. It already has its own `@Container`, `@DynamicPropertySource` and `port` field — reuse them, add nothing new at class level.

```java
  @Test
  void reportsSignedInAsForAnEmailThatIsNotOnTheAllowlist() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))
            .header("X-Auth-Email", "stranger@example.com")
            .GET()
            .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"authenticated\":false");
    assertThat(response.body()).contains("\"signedInAs\":\"stranger@example.com\"");
  }

  @Test
  void omitsSignedInAsWhenNoEmailWasPresented() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))
            .GET()
            .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"authenticated\":false");
    assertThat(response.body()).doesNotContain("signedInAs");
  }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current \
  ./gradlew test --tests '*AuthControllerTest'
```

Expected: FAIL on `reportsSignedInAsForAnEmailThatIsNotOnTheAllowlist` — body has no `signedInAs`.

- [ ] **Step 3: Implement**

Replace the body of `AuthController.me()`:

```java
  @GetMapping("/me")
  public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();

    if (auth == null || !(auth.getDetails() instanceof AppUser user)) {
      var anonymous = new java.util.LinkedHashMap<String, Object>();
      anonymous.put("authenticated", false);
      Object signedInAs = request.getAttribute(ProxyAuthFilter.SIGNED_IN_AS_ATTRIBUTE);
      if (signedInAs instanceof String email && !email.isBlank()) {
        anonymous.put("signedInAs", email);
      }
      return ResponseEntity.ok(anonymous);
    }

    var result = new java.util.LinkedHashMap<String, Object>();
    result.put("authenticated", true);
    result.put("email", auth.getName());
    result.put("displayName", user.getDisplayName() != null ? user.getDisplayName() : "");
    result.put("role", user.getRole());
    result.put("lang", user.getLang() != null ? user.getLang() : "en");
    result.put("familyTreePersonId", user.getFamilyTreePersonId());
    return ResponseEntity.ok(result);
  }
```

Add imports: `jakarta.servlet.http.HttpServletRequest`, `place.icomb.archiver.config.ProxyAuthFilter`.

- [ ] **Step 4: Run the test**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current \
  ./gradlew test --tests '*AuthControllerTest'
```

Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current ./gradlew spotlessApply
cd .. && git add backend/src/main/java/place/icomb/archiver/controller/AuthController.java \
        backend/src/test/java/place/icomb/archiver/controller/AuthControllerTest.java
git commit -m "feat: report signedInAs for authenticated users not on the allowlist"
```

---

### Task 6: Frontend shows a no-access page instead of looping

**Files:**
- Create: `frontend/src/routes/no-access/+page.svelte`
- Create: `frontend/src/routes/no-access/+page.server.ts`
- Modify: `frontend/src/hooks.server.ts`
- Modify: `frontend/src/lib/server/api.ts:15-29` (`AuthUser` interface)

**Interfaces:**
- Consumes: `signedInAs` from `GET /api/auth/me` (Task 5).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add `signedInAs` to the AuthUser type**

In `frontend/src/lib/server/api.ts`, extend the interface at line 15:

```typescript
export interface AuthUser {
  authenticated: boolean;
  email?: string;
  displayName?: string;
  role?: string;
  familyTreePersonId?: number;
  signedInAs?: string;
}
```

- [ ] **Step 2: Redirect not-allowlisted users in hooks.server.ts**

Read `frontend/src/hooks.server.ts` first — it currently reads `X-Auth-Email` into `event.locals.userEmail`. Add, after that assignment and before `resolve(event)`:

```typescript
  const path = event.url.pathname;
  const exempt =
    path.startsWith("/no-access") ||
    path.startsWith("/oauth2-") ||
    path.startsWith("/signin") ||
    path.startsWith("/api/");

  if (event.locals.userEmail && !exempt) {
    const user = await fetchCurrentUser(event.locals.userEmail);
    if (!user.authenticated) {
      throw redirect(307, "/no-access");
    }
  }
```

Import `redirect` from `@sveltejs/kit` and `fetchCurrentUser` from `$lib/server/api`.

The `exempt` list is what prevents the loop: `/no-access` must never redirect to itself.

- [ ] **Step 3: Create the no-access page loader**

`frontend/src/routes/no-access/+page.server.ts`:

```typescript
import type { PageServerLoad } from "./$types";
import { fetchCurrentUser } from "$lib/server/api";
import { redirect } from "@sveltejs/kit";

export const load: PageServerLoad = async ({ locals }) => {
  if (!locals.userEmail) throw redirect(307, "/signin");

  const user = await fetchCurrentUser(locals.userEmail);
  if (user.authenticated) throw redirect(307, "/");

  return { signedInAs: user.signedInAs ?? locals.userEmail };
};
```

- [ ] **Step 4: Create the no-access page**

`frontend/src/routes/no-access/+page.svelte`. Use the Verdant design system (`--vui-*` CSS custom properties) as the rest of the app does — open an existing page such as `frontend/src/routes/+page.svelte` to copy the class conventions in use. No inline `style=""` attributes.

```svelte
<script lang="ts">
  import type { PageData } from "./$types";
  let { data }: { data: PageData } = $props();
</script>

<svelte:head><title>No access — Czernin Archive</title></svelte:head>

<div class="no-access">
  <h1>No access</h1>
  <p>
    You are signed in as <strong>{data.signedInAs}</strong>, but that address is not
    on the access list for this archive.
  </p>
  <p>If you believe it should be, contact the archive owner.</p>
  <a class="signout" href="/oauth2-google/sign_out?rd=/signin">Sign out</a>
</div>

<style>
  .no-access {
    max-width: 32rem;
    margin: 6rem auto;
    padding: 0 1.5rem;
    text-align: center;
    color: var(--vui-color-text);
  }

  h1 {
    font-size: 1.75rem;
    margin-bottom: 1rem;
  }

  p {
    margin-bottom: 0.75rem;
    line-height: 1.6;
  }

  .signout {
    display: inline-block;
    margin-top: 1.5rem;
    padding: 0.6rem 1.25rem;
    border-radius: var(--vui-radius-md, 0.375rem);
    background: var(--vui-color-surface-raised, #f3f4f6);
    color: var(--vui-color-text);
    text-decoration: none;
  }

  .signout:hover {
    background: var(--vui-color-surface-hover, #e5e7eb);
  }
</style>
```

- [ ] **Step 5: Type check**

```bash
cd frontend && npm run check
```

Expected: 0 errors. Resolve any `@const` placement or typing complaints now — CI's svelte-check is stricter than the dev server.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/routes/no-access frontend/src/hooks.server.ts frontend/src/lib/server/api.ts
git commit -m "feat: show a no-access page for users outside the allowlist

Signing in with an address that is not on the allowlist previously bounced
between /signin and the app forever, because oauth2-proxy sees a valid session
and sends the user straight back."
```

---

### Task 7: nginx denies by default

**Files:**
- Modify: `web/nginx.conf` — delete `:24-36` (`error_page 401 = @anon` and `location @anon`), delete `:160-168` (`location @api_anon`), modify `:128-141` (events), `:144-158` (`/api/`), `:171-185` (`/`), add a `/.well-known/` location
- Modify: `web/Dockerfile` (only if a new file must be copied in for `/.well-known/`)

**Interfaces:**
- Consumes: nothing.
- Produces: an unauthenticated `/.well-known/` route that Task 8 relies on for Apple's domain verification.

- [ ] **Step 1: Delete the anonymous fallthrough**

Remove these blocks from `web/nginx.conf` entirely:

```nginx
    # ── Optional auth: try Google cookie, fall through to anonymous ──
    error_page 401 = @anon;
    location @anon {
        ...
    }
```

and

```nginx
    location @api_anon {
        ...
    }
```

- [ ] **Step 2: Split the events routes**

Replace the single `location ~ ^/api/(records|processor)/events$` block with two. `/api/records/events` gains `auth_request`; `/api/processor/events` stays open at nginx because `ProcessorTokenFilter` guards it and the workers depend on it.

```nginx
    location = /api/records/events {
        auth_request /oauth2-google/auth;
        auth_request_set $auth_email $upstream_http_x_auth_request_email;
        error_page 401 = @api_401;

        set $backend http://backend:8080;
        proxy_pass $backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Auth-Email $auth_email;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 86400;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        chunked_transfer_encoding off;
    }

    location = /api/processor/events {
        set $backend http://backend:8080;
        proxy_pass $backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Auth-Email "";
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 86400;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        chunked_transfer_encoding off;
    }
```

- [ ] **Step 3: Make API 401s return JSON, not a redirect**

Add this named location. The SvelteKit client must not receive HTML where it expects JSON.

```nginx
    location @api_401 {
        default_type application/json;
        return 401 '{"error":"authentication required"}';
    }
```

Change `location /api/`'s `error_page 401 = @api_anon;` to:

```nginx
        error_page 401 = @api_401;
```

- [ ] **Step 4: Send browser routes to the sign-in page**

In `location /`, add an `error_page` so an unauthenticated visitor lands on the sign-in page instead of the deleted `@anon`:

```nginx
        error_page 401 = /signin;
```

- [ ] **Step 5: Add the unauthenticated `/.well-known/` route**

Apple's domain-verification fetcher must not receive a sign-in redirect. Place this block *above* `location /`:

```nginx
    # ── Domain verification files — must stay unauthenticated ──
    location /.well-known/ {
        root /usr/share/nginx/html;
        default_type text/plain;
        try_files $uri =404;
    }
```

Create the directory the file will live in so the route exists before Task 8 needs it:

```bash
mkdir -p web/well-known
```

Check `web/Dockerfile` — if it copies individual files rather than a directory, add a line copying `well-known/` to `/usr/share/nginx/html/.well-known/`.

- [ ] **Step 6: Verify the config parses**

```bash
docker run --rm -v "$PWD/web/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:alpine nginx -t
```

Expected: `syntax is ok` / `test is successful`.

- [ ] **Step 7: Commit**

```bash
git add web/nginx.conf web/Dockerfile
git commit -m "feat: nginx denies by default

Anonymous fallthrough deleted. Browser routes redirect to /signin, API routes
return 401 JSON, /api/records/events now requires auth. /api/processor/events
stays open at the proxy — ProcessorTokenFilter guards it and the workers need
it. Adds an unauthenticated /.well-known/ route for domain verification."
```

---

### Task 8: MCP behind a static bearer token

**Files:**
- Create: `backend/src/main/java/place/icomb/archiver/config/McpTokenFilter.java`
- Modify: `backend/src/main/java/place/icomb/archiver/config/SecurityConfig.java` (matcher at `:44-46`, CORS at `:110-120`, filter chain)
- Modify: `backend/src/main/resources/application.yml` (`archiver.mcp.token`)
- Test: `backend/src/test/java/place/icomb/archiver/config/McpTokenFilterTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `McpTokenFilter(String mcpToken)` granting `ROLE_MCP`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/place/icomb/archiver/config/McpTokenFilterTest.java`:

```java
package place.icomb.archiver.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpTokenFilterTest {

  private static final String TOKEN = "test-mcp-token";

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @LocalServerPort private int port;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("archiver.mcp.token", () -> TOKEN);
  }

  private HttpResponse<String> postMcp(String bearer) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/mcp/sse"))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream, application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}"));
    if (bearer != null) {
      builder = builder.header("Authorization", "Bearer " + bearer);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rejectsMcpRequestWithNoToken() throws Exception {
    assertThat(postMcp(null).statusCode()).isIn(401, 403);
  }

  @Test
  void rejectsMcpRequestWithWrongToken() throws Exception {
    assertThat(postMcp("not-the-token").statusCode()).isIn(401, 403);
  }

  @Test
  void acceptsMcpRequestWithCorrectToken() throws Exception {
    assertThat(postMcp(TOKEN).statusCode()).isNotIn(401, 403);
  }
}
```

**Note:** `application-test.yml` sets `spring.ai.mcp.server.enabled=false`, so the MCP endpoint itself may not be mapped in tests. If `acceptsMcpRequestWithCorrectToken` returns 404, that is a pass for our purposes — the assertion is `isNotIn(401, 403)`, which 404 satisfies. Do not enable the MCP server in the test profile; it breaks the test context.

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current \
  ./gradlew test --tests '*McpTokenFilterTest'
```

Expected: FAIL on the two reject cases — `/api/mcp/**` is `permitAll()`.

- [ ] **Step 3: Add the token property**

In `application.yml`, inside the `archiver:` block:

```yaml
  mcp:
    token: ${MCP_TOKEN:}
```

Empty default. Step 5 makes an empty token deny everything.

- [ ] **Step 4: Write the filter**

`backend/src/main/java/place/icomb/archiver/config/McpTokenFilter.java`:

```java
package place.icomb.archiver.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates MCP callers bearing the shared MCP token and grants ROLE_MCP.
 *
 * <p>Interim measure. The intent is to replace this with per-user OAuth federating to the existing
 * Google and Apple proxies — see the Phase 3b spec.
 */
public class McpTokenFilter extends OncePerRequestFilter {

  private final String mcpToken;

  public McpTokenFilter(String mcpToken) {
    this.mcpToken = mcpToken;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    if (SecurityContextHolder.getContext().getAuthentication() == null
        && mcpToken != null
        && !mcpToken.isBlank()) {
      String header = request.getHeader("Authorization");
      if (header != null && header.startsWith("Bearer ") && matches(header.substring(7))) {
        var auth =
            new UsernamePasswordAuthenticationToken(
                "mcp", null, List.of(new SimpleGrantedAuthority("ROLE_MCP")));
        SecurityContextHolder.getContext().setAuthentication(auth);
      }
    }

    filterChain.doFilter(request, response);
  }

  private boolean matches(String presented) {
    return MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8), mcpToken.getBytes(StandardCharsets.UTF_8));
  }
}
```

- [ ] **Step 5: Wire it up and tighten CORS**

In `SecurityConfig`, add the constructor parameter `@Value("${archiver.mcp.token:}") String mcpToken`, store it in a field, and register the filter alongside the others:

```java
        .addFilterBefore(
            new McpTokenFilter(mcpToken), UsernamePasswordAuthenticationFilter.class)
```

Replace the MCP matcher at lines 44-46:

```java
                    // MCP server endpoints — shared token until per-user OAuth lands
                    .requestMatchers("/api/mcp/**")
                    .hasRole("MCP")
```

Tighten `mcpCorsSource()` — `allowedOrigins: ["*"]` was written for a `permitAll()` endpoint and should not outlive it:

```java
  private CorsConfigurationSource mcpCorsSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("https://claude.ai"));
    config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/mcp/**", config);
    return source;
  }
```

- [ ] **Step 6: Run the tests**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current \
  ./gradlew test --tests '*McpTokenFilterTest'
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current ./gradlew test
```

Expected: both PASS.

- [ ] **Step 7: Format and commit**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current ./gradlew spotlessApply
cd .. && git add backend/src/main/java/place/icomb/archiver/config/McpTokenFilter.java \
        backend/src/main/java/place/icomb/archiver/config/SecurityConfig.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/place/icomb/archiver/config/McpTokenFilterTest.java
git commit -m "feat: require a bearer token on /api/mcp/

Closes the last anonymous route. CORS narrowed from allow-all, which was
written for the permitAll() endpoint this replaces."
```

---

### Task 9: Smoke-test scripts assert no anonymous access

**Files:**
- Modify: `web/test-endpoints.sh`
- Modify: `web/validate-deploy.sh`

**Interfaces:**
- Consumes: the deployed behaviour from Tasks 3, 4, 7, 8.
- Produces: nothing.

- [ ] **Step 1: Read both scripts**

Open `web/test-endpoints.sh` and `web/validate-deploy.sh` and match their existing assertion helpers and output style. Do not restructure them.

- [ ] **Step 2: Add the anonymous-access assertions**

Add checks asserting each of these returns 401 or 403 with no cookie and no headers, against whichever base URL the script already uses:

- `GET /api/records`
- `GET /api/v1/archives`
- `GET /api/admin/users`
- `GET /api/records/events`
- `POST /api/mcp/sse`

And one spoofing check:

```bash
# X-Auth-Email must not be honoured from outside the trusted set.
code=$(curl -s -o /dev/null -w '%{http_code}' \
  -H 'X-Auth-Email: timothy.corbettclark@gmail.com' \
  "$BASE_URL/api/admin/users")
if [ "$code" = "200" ]; then
  echo "FAIL: spoofed X-Auth-Email accepted through the proxy (got $code)"
  exit 1
fi
echo "PASS: spoofed X-Auth-Email rejected through the proxy ($code)"
```

Do **not** add an equivalent probe against the backend's LAN address (`192.168.19.0:8080`). Under the current threat model the LAN is trusted, so that spoof succeeds by design and such a check would fail. It gets added when `trusted-cidrs` is narrowed — see the follow-up section of the spec.

- [ ] **Step 3: Commit**

```bash
git add web/test-endpoints.sh web/validate-deploy.sh
git commit -m "test: assert no anonymous access and no header spoofing via the proxy"
```

---

### Task 10: Deploy

Everything ships together. Nothing in this task is reversible by a `git revert`, so work through it in order.

**Files:**
- Modify: `deploy/docker-compose.yml` (add `MCP_TOKEN`)
- Manual: Apple Developer portal, Google Cloud Console, Portainer stack #183

- [ ] **Step 1: Full local verification**

```bash
cd backend && JAVA_HOME=/Users/fishloa/.sdkman/candidates/java/current ./gradlew test
cd ../frontend && npm run check
cd .. && make lint
```

Expected: all pass, svelte-check at 0 errors. Do not proceed past a failure.

- [ ] **Step 2: Generate the MCP token and add it to compose**

```bash
openssl rand -hex 32
```

Add to `deploy/docker-compose.yml` under the backend service's `environment:`:

```yaml
      MCP_TOKEN: ${MCP_TOKEN}
```

Commit the compose change. Do **not** commit the token value.

- [ ] **Step 3: Apple Developer portal**

1. developer.apple.com → Certificates, IDs & Profiles → Identifiers → filter **Services IDs** → `com.icomb.place.archiver.signin` (Team `5DJJ367BH4`).
2. Configure → Sign in with Apple → Web Authentication Configuration.
3. **Domains and Subdomains**: add `archive.czernin.eu`. Keep `archiver.icomb.place`.
4. **Return URLs**: add `https://archive.czernin.eu/oauth2-apple/callback`. Keep the existing entry.
5. Download `apple-developer-domain-association.txt`, save it to `web/well-known/apple-developer-domain-association.txt`, commit it.
6. Do not click Verify yet — the file has to be deployed first. Save and come back at Step 7.

- [ ] **Step 4: Google Cloud Console**

Add `https://archive.czernin.eu/oauth2-google/callback` to the authorised redirect URIs for OAuth client `840118678161-rgs82brehfjmj6ktrofmcpap5e7...`. Keep the existing URI.

- [ ] **Step 5: Push and wait for CI**

```bash
git push
```

Jenkins builds the changed images, pushes to Nexus, and triggers the Portainer webhook on success. Watch it:

```bash
curl -s --user "admin:5c5dff7da0954fbfa035e85dbfe40681" \
  "https://ci.icomb.place/job/archiver/lastBuild/api/json" | python3 -m json.tool | head -20
```

Do not proceed until the build is green.

- [ ] **Step 6: Update the Portainer stack**

The live stack is running older compose than the repo — single-domain cookie settings and hardcoded redirect URLs. It must be brought up to the repo version or sign-in on the second host cannot work at all.

`GET /api/stacks/183/file`, replace the content with `deploy/docker-compose.yml`, then `PUT /api/stacks/183?endpointId=2` with the existing `Env` array plus `MCP_TOKEN`, and:

```json
"Webhook": "b7e3a1d2-5f4c-4e8a-9b1d-3c6f8a2e4d71"
```

Omitting `Webhook` clears it. Confirm the env array still carries every existing key — `APPLE_*`, `OAUTH2_PROXY_*`, `PROCESSOR_TOKEN`, `TEI_API_KEY`, the DB settings and the scraper credentials.

- [ ] **Step 7: Verify Apple's domain check**

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  https://archive.czernin.eu/.well-known/apple-developer-domain-association.txt
```

Expected: `200`. A `302` means the `/.well-known/` location is not bypassing auth — fix that before continuing.

Then return to the Apple portal and click **Verify**, then **Save**.

- [ ] **Step 8: Verify the deployment**

```bash
make validate-deploy
```

Then by hand, in a clean browser profile, all four combinations:

| Host | Provider | Expected |
|---|---|---|
| archiver.icomb.place | Google | signs in, archive loads |
| archiver.icomb.place | Apple | signs in, archive loads |
| archive.czernin.eu | Google | signs in, archive loads |
| archive.czernin.eu | Apple | signs in, archive loads |

Then confirm the lockdown itself:

- Logged out, `https://archive.czernin.eu/` redirects to `/signin` and shows no archive content.
- `curl -s -o /dev/null -w '%{http_code}' https://archive.czernin.eu/api/records` returns 401.
- `curl -H 'X-Auth-Email: timothy.corbettclark@gmail.com' https://archive.czernin.eu/api/admin/users` does **not** return the user list.
- Signing in with a Google account that is not in `app_user_email` lands on `/no-access` and does not loop.
- The Claude.ai MCP connector still works once its bearer token is configured.

- [ ] **Step 9: Confirm the pipeline still runs**

The workers authenticate with `PROCESSOR_TOKEN`, not `X-Auth-Email`, so they should be unaffected — but verify rather than assume:

```bash
ssh zelkova "docker logs archiver-backend-1 --since 5m 2>&1 | grep -i 'error\|denied\|403' | head -20"
```

Check the `/pipeline` page shows workers as live, and that no job is stuck claiming.

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| Phase 0 layer 1 — nginx clears header | 3 |
| Phase 0 layer 2 — trusted peer check | 1, 2 |
| Phase 0 tests, existing tests unchanged | 1, 2 |
| Phase 1 — SecurityConfig deny by default | 4 |
| Phase 1 — signed-in-but-unknown, no loop | 5, 6 |
| Phase 1 — nginx deny by default, `/.well-known/` | 7 |
| Phase 1 — smoke tests | 9 |
| Phase 2 — Apple + Google portal, stack deploy | 10 |
| Phase 3a — MCP static token, CORS | 8 |
| Phase 3b — MCP OAuth | deferred, own spec |

**Deferred deliberately:** the `validate-deploy.sh` assertion against the backend's LAN address, and narrowing `trusted-cidrs`. Both belong to the follow-up ship recorded in the spec.

**Test conventions confirmed against the codebase:** no shared integration-test base class exists. Every test class declares its own `@Testcontainers` / `@Container PostgreSQLContainer` / `@DynamicPropertySource`, uses `@ActiveProfiles("test")`, and accesses the DB through `JdbcClient`. All three new test classes in this plan follow that pattern.

**Type consistency check:** `TrustedPeerResolver(List<String>, List<String>, Duration)` and `isTrusted(String)` are used identically in Tasks 1 and 2. `ProxyAuthFilter(AppUserRepository, TrustedPeerResolver)` matches between Task 2's implementation and its `SecurityConfig` registration. `ProxyAuthFilter.SIGNED_IN_AS_ATTRIBUTE` is defined in Task 2 and read in Task 5. `signedInAs` is produced by Task 5 and consumed in Task 6.
