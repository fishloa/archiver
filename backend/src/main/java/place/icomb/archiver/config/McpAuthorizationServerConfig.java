package place.icomb.archiver.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Duration;
import org.springaicommunity.mcp.security.authorizationserver.config.McpAuthorizationServerConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.McpDefaultJwtCustomizer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import place.icomb.archiver.repository.AppUserRepository;

/**
 * The Authorization Server role: issues per-user JWTs for the MCP resource server (see
 * McpResourceServerConfig) after the login-reuse filter (McpAuthorizeSessionFilter, added to this
 * chain) confirms the caller against the existing site session and allowlist.
 *
 * <p>Persistence is Spring Authorization Server's own standard JDBC schema (V20 migration) --
 * simplified JDBC implementations, adequate for a single-operator archive's request volume.
 */
@Configuration
public class McpAuthorizationServerConfig {

  private final String issuer;

  public McpAuthorizationServerConfig(
      @Value("${archiver.mcp.oauth.issuer:https://archive.czernin.eu}") String issuer) {
    this.issuer = issuer;
  }

  @Bean
  @Order(1)
  public SecurityFilterChain authorizationServerSecurityFilterChain(
      HttpSecurity http,
      AppUserRepository appUserRepository,
      TrustedPeerResolver trustedPeerResolver)
      throws Exception {
    OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
        new OAuth2AuthorizationServerConfigurer();

    // NOTE: authorizationServerConfigurer.getEndpointsMatcher() never includes /oauth2/register.
    // McpAuthorizationServerConfigurer wires up the dynamic-client-registration endpoint by calling
    // HttpSecurity#oauth2AuthorizationServer(...) from its own init() (confirmed by decompiling
    // McpAuthorizationServerConfigurer.class), which runs *after* authorizationServerConfigurer's
    // own
    // init() has already computed and frozen its endpointsMatcher field -- so no ordering of these
    // `.with(...)` calls makes getEndpointsMatcher() pick up the registration endpoint. Without
    // this
    // explicit addition, POST /oauth2/register silently falls through to the main application's
    // catch-all SecurityConfig chain and is denied there instead of reaching this chain's DCR
    // filter.
    RequestMatcher endpointsMatcher =
        new OrRequestMatcher(
            authorizationServerConfigurer.getEndpointsMatcher(),
            PathPatternRequestMatcher.withDefaults().matcher("/oauth2/register"));

    // Use ignoringRequestMatchers(...), not disable(), to exempt this chain from CSRF. disable()
    // fully deregisters the CsrfConfigurer, and OAuth2AuthorizationServerConfigurer.init() (invoked
    // by the .with(authorizationServerConfigurer, ...) call below) itself calls http.csrf(...)
    // again later, with its own narrower matcher. If the CsrfConfigurer isn't already registered at
    // that point, that second call creates a brand-new CsrfConfigurer scoped only to the library's
    // own endpoints -- silently excluding /oauth2/register again and reintroducing the CSRF 403
    // this
    // fix addresses. ignoringRequestMatchers(...) works because it leaves the configurer
    // registered,
    // so that later call additively customizes this SAME instance instead of replacing it.
    // Positioned before LogoutFilter -- NOT the more usual UsernamePasswordAuthenticationFilter
    // anchor -- because the authorization server's own internal OAuth2AuthorizationCodeRequest-
    // ValidatingFilter is registered immediately after LogoutFilter, well before
    // UsernamePasswordAuthenticationFilter's normal slot. That validating filter converts the
    // request into an OAuth2AuthorizationCodeRequestAuthenticationToken, embeds whatever
    // principal SecurityContextHolder holds AT THAT MOMENT, and caches the token as a request
    // attribute for the rest of the chain (including the actual OAuth2AuthorizationEndpointFilter
    // further down) to reuse rather than re-deriving -- so if this filter authenticated any
    // later, its identity would never reach the token the authorization endpoint actually
    // validates against (confirmed by decompiling both filters; the cached-principal-is-stale
    // failure mode surfaces as "invalid_request: OAuth 2.0 Parameter: principal"). LogoutFilter
    // is used as the anchor, rather than an authorization-server-specific filter class, because
    // those classes' relative order is only registered once OAuth2AuthorizationServerConfigurer
    // itself initialises later in this same build -- addFilterBefore needs the anchor's order to
    // already be known, which only standard Spring Security filter classes guarantee up front.
    http.securityMatcher(endpointsMatcher)
        .csrf(csrf -> csrf.ignoringRequestMatchers(endpointsMatcher))
        .addFilterBefore(
            new McpAuthorizeSessionFilter(appUserRepository, trustedPeerResolver),
            LogoutFilter.class)
        // NOTE: explicitly enabling clientRegistrationEndpoint(...) here -- rather than leaving it
        // to McpAuthorizationServerConfigurer.mcpAuthorizationServer() below, which also enables it
        // by default -- is required, not redundant. OAuth2AuthorizationServerConfigurer.init(...)
        // (invoked once, during http.build()'s init phase, in this `.with(...)` call's insertion
        // order) iterates its OWN internal `configurers` map and calls `.init(http)` on each entry
        // present AT THAT MOMENT -- this is what invokes
        // OAuth2ClientRegistrationEndpointConfigurer.init(...), the method that actually registers
        // the DCR AuthenticationProviders via http.authenticationProvider(...) (confirmed by
        // decompiling OAuth2AuthorizationServerConfigurer.init()/lambda$init$2 and
        // OAuth2ClientRegistrationEndpointConfigurer.init()). McpAuthorizationServerConfigurer's
        // own
        // init() (which runs SECOND, since it's added via `.with(...)` after this one) calls
        // `.clientRegistrationEndpoint(...)` too, but only as part of a customizer passed to
        // `HttpSecurity#oauth2AuthorizationServer(...)` -- which reaches the SAME configurer
        // instance and adds OAuth2ClientRegistrationEndpointConfigurer to its map for the FIRST
        // time only if it isn't already there. Added this late, its `.init(http)` never runs
        // (authorizationServerConfigurer.init() already iterated the map before this addition) --
        // only its `.configure(http)` runs later (that pass DOES pick up late additions), which
        // registers the endpoint's filter/matcher but no AuthenticationProvider. That mismatch is
        // exactly the previously-observed bug: POST /oauth2/register reached a real filter and
        // produced an OAuth2ClientRegistrationAuthenticationToken, but ProviderManager had no
        // AuthenticationProvider registered for it ("No AuthenticationProvider found for
        // OAuth2ClientRegistrationAuthenticationToken"). Calling `.clientRegistrationEndpoint(...)`
        // here -- synchronously, as part of THIS `.with(...)` call, before http.build() begins its
        // init phase -- adds the sub-configurer to the map early enough that
        // authorizationServerConfigurer.init() finds and initialises it correctly.
        //
        // openRegistrationAllowed(true) must ALSO be set here, not left to
        // McpAuthorizationServerConfigurer's later customization of the same flag: the value is
        // read and baked into the registered OAuth2ClientRegistrationAuthenticationProvider inside
        // OAuth2ClientRegistrationEndpointConfigurer.init() (via
        // createDefaultAuthenticationProviders,
        // confirmed by decompiling both), which -- per the ordering explained above -- runs as part
        // of THIS `.with(...)` call's authorizationServerConfigurer.init(), before
        // McpAuthorizationServerConfigurer.init() ever executes. Leaving it unset here defaults to
        // false (registration requires an already-authenticated caller / initial access token),
        // which surfaced as DCR returning 401 even after the AuthenticationProvider itself was
        // correctly registered.
        .with(
            authorizationServerConfigurer,
            (asConfigurer) ->
                asConfigurer.clientRegistrationEndpoint(dcr -> dcr.openRegistrationAllowed(true)))
        .with(McpAuthorizationServerConfigurer.mcpAuthorizationServer(), Customizer.withDefaults())
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .exceptionHandling(
            exceptions ->
                exceptions.defaultAuthenticationEntryPointFor(
                    new org.springframework.security.web.authentication
                        .LoginUrlAuthenticationEntryPoint("/signin"),
                    new org.springframework.security.web.util.matcher.MediaTypeRequestMatcher(
                        org.springframework.http.MediaType.TEXT_HTML)));

    return http.build();
  }

  // McpAuthorizationServerConfigurer.getTokenGenerator() only builds its own OAuth2TokenGenerator
  // (wired with ResourceIdentifierAudienceTokenCustomizer, which sets the `aud` claim from the
  // token request's `resource` parameter, RFC 8707) if NEITHER an existing shared HttpSecurity
  // object NOR a user-defined bean of that type is found -- confirmed via decompilation. In this
  // chain, something (most likely OAuth2AuthorizationServerConfigurer's own `configure()` phase,
  // building its own default generator via OAuth2TokenEndpointConfigurer) wins that race before
  // the MCP configurer's init() ever gets to apply its audience-aware one: every issued JWT's
  // `aud` claim came back set to the client_id (Spring's plain default), not the resource
  // identifier, causing McpResourceServerConfig's audience validation to reject every real token
  // with "aud claim is not valid" -- discovered via the full round-trip test, the first test in
  // this whole plan to ever exercise a real issued token against the resource server chain.
  // Defining this AS OUR OWN BEAN sidesteps the race entirely: Spring resolves a user bean before
  // any configurer-internal shared-object logic runs, so this wins deterministically regardless
  // of configurer init() ordering. Composition mirrors what
  // McpAuthorizationServerConfigurer.getTokenGenerator() builds internally (confirmed via
  // decompilation): JwtGenerator + OAuth2RefreshTokenGenerator, delegated via
  // DelegatingOAuth2TokenGenerator.
  //
  // Does NOT use ResourceIdentifierAudienceTokenCustomizer, though: that customizer only sets
  // `aud` when the token request carries an RFC 8707 `resource` parameter (confirmed via
  // decompilation) -- and real production traffic proved Claude.ai's own token requests never
  // include one (every issued token's `aud` still came back as the client_id even after
  // McpResourceServerConfig's resourcePath was corrected to match Claude's exact endpoint, since
  // there was no `resource` param for that customizer to read at all). This system has exactly
  // one resource server and one MCP endpoint, so there is nothing to ask the client to
  // disambiguate -- every token is unconditionally audience-restricted to that one endpoint,
  // matching exactly what McpResourceServerConfig's ResourceIdentifier computes at validation
  // time (issuer + the same "/api/mcp/sse" resourcePath), rather than depending on optional,
  // inconsistently-implemented client behavior.
  @Bean
  public OAuth2TokenGenerator<?> tokenGenerator(JWKSource<SecurityContext> jwkSource) {
    String resource = issuer + "/api/mcp/sse";
    JwtGenerator jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
    jwtGenerator.setJwtCustomizer(
        context -> {
          McpDefaultJwtCustomizer.DEFAULT_JWT_CUSTOMIZER.customize(context);
          context.getClaims().claim("aud", resource);
        });
    return new DelegatingOAuth2TokenGenerator(jwtGenerator, new OAuth2RefreshTokenGenerator());
  }

  @Bean
  public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
    RegisteredClientRepository delegate = new JdbcRegisteredClientRepository(jdbcTemplate);
    return new RegisteredClientRepository() {
      @Override
      public void save(RegisteredClient registeredClient) {
        delegate.save(useLongLivedTokens(skipConsentForScopelessClients(registeredClient)));
      }

      @Override
      public RegisteredClient findById(String id) {
        return delegate.findById(id);
      }

      @Override
      public RegisteredClient findByClientId(String clientId) {
        return delegate.findByClientId(clientId);
      }
    };
  }

  // The default OAuth2ClientRegistrationAuthenticationValidator unconditionally rejects any
  // `scope` field in a Dynamic Client Registration request body (RFC 7591 section 3.2.2), so
  // every DCR-registered client -- including Claude.ai's -- ends up with zero scopes. Spring's
  // default consent flow requires at least one approved scope to succeed:
  // OAuth2AuthorizationConsentAuthenticationProvider throws access_denied when the set of
  // consented scopes is empty and no prior consent exists, which is unconditionally true for a
  // scopeless client -- there's nothing to check on the consent form, so consent can never
  // complete. This isn't a UX nicety we're skipping -- it's structurally impossible for these
  // clients, discovered when the real Claude.ai flow reproducibly failed at the consent-accept
  // step with "OAuth 2.0 Parameter: client_id" (Spring's generic, misleadingly-labeled message
  // for this exact empty-scope rejection). This system's actual authorization boundary is
  // identity + allowlist (McpAuthorizeSessionFilter, Tasks 5-6), enforced before the consent page
  // ever renders -- not OAuth scope, which McpResourceServerConfig doesn't check at all -- so
  // skipping a consent screen that has nothing meaningful to show is the correct fix, not a
  // workaround.
  // Spring's default access token lifetime is 5 minutes (refresh token 60 minutes) --
  // reasonable for a multi-tenant service where a leaked token's blast radius matters, but
  // this is a single-operator archive whose actual authorization boundary is identity +
  // allowlist (McpAuthorizeSessionFilter), not token expiry. In practice, Claude.ai's MCP
  // client doesn't proactively use its refresh_token on a 401 -- it starts a whole new
  // DCR + authorize + token cycle, which the session-reuse filter completes without a visible
  // Google/Apple login, but STILL requires the human to manually re-trigger it (confirmed
  // live: a 5-minute gap between tool calls forced a manual "reauthorize" click, not a silent
  // background recovery). Three months trades token lifetime for that not happening during a
  // normal working session, which is the right tradeoff here since expiry isn't the real
  // security boundary anyway.
  private static RegisteredClient useLongLivedTokens(RegisteredClient registeredClient) {
    TokenSettings settings =
        TokenSettings.withSettings(registeredClient.getTokenSettings().getSettings())
            .accessTokenTimeToLive(Duration.ofDays(90))
            .refreshTokenTimeToLive(Duration.ofDays(90))
            .build();
    return RegisteredClient.from(registeredClient).tokenSettings(settings).build();
  }

  private static RegisteredClient skipConsentForScopelessClients(
      RegisteredClient registeredClient) {
    if (!registeredClient.getScopes().isEmpty()) {
      return registeredClient;
    }
    ClientSettings settings =
        ClientSettings.withSettings(registeredClient.getClientSettings().getSettings())
            .requireAuthorizationConsent(false)
            .build();
    return RegisteredClient.from(registeredClient).clientSettings(settings).build();
  }

  @Bean
  public OAuth2AuthorizationService authorizationService(
      JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
    return new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
  }

  @Bean
  public OAuth2AuthorizationConsentService authorizationConsentService(
      JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
    return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
  }

  @Bean
  public AuthorizationServerSettings authorizationServerSettings() {
    // Explicit issuer, rather than leaving it unset (which makes Spring Security compute `iss`
    // dynamically per-request from scheme/host/port), so it always agrees with the issuer
    // McpResourceServerConfig validates incoming JWTs against -- both read the SAME
    // archiver.mcp.oauth.issuer property. Without this, any environment where the request's
    // observed scheme/host doesn't exactly match the resource server's configured issuer (e.g.
    // this application's own test suite, which talks to http://localhost:<random-port>, or
    // production if a proxy ever fails to forward the right Host/X-Forwarded-* headers) would
    // issue tokens whose `iss` claim fails validation on every single request.
    return AuthorizationServerSettings.builder().issuer(issuer).build();
  }
}
