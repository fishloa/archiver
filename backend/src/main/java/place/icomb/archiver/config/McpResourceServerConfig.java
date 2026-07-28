package place.icomb.archiver.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * The Resource Server role: validates the per-user JWTs issued by McpAuthorizationServerConfig on
 * /api/mcp/**, replacing the shared-secret McpTokenFilter. Self-issued, self-validated -- the
 * issuer is this same application, so no external IdP token ever reaches this endpoint directly.
 *
 * <p><b>Deviation from the task brief's sample:</b> the brief's sample left {@code jwtDecoder(...)}
 * unset, which forces {@code McpServerOAuth2Configurer} to build its own decoder via {@code
 * NimbusJwtDecoder.withIssuerLocation(issuer)}. Decompiling that builder
 * (JwkSetUriJwtDecoderBuilder.jwkSource()) shows it invokes an HTTP call to resolve the issuer's
 * JWK set *eagerly*, inside {@code build()} -- and {@code build()} runs from this bean method,
 * during {@code ApplicationContext} refresh, before the embedded servlet container is listening.
 * Since the Authorization Server role lives in this SAME application, that eager call is this app
 * trying to HTTP-call its own not-yet-open port -- guaranteed to fail (or hang) in every
 * environment, not just tests. Supplying {@code jwtDecoder(...)} built directly from the {@link
 * JWKSource} (Task 4's in-memory signing key) sidesteps the self-referential network round trip
 * entirely; audience validation (`validateAudienceClaim(true)`) still wraps whichever decoder is
 * supplied, confirmed by decompiling {@code McpServerOAuth2Configurer.getJwtDecoder(String)}.
 *
 * <p>Also sets {@code resourcePath("/api/mcp/sse")} -- the library's default resource path is
 * {@code /mcp} (baked into {@code McpServerOAuth2Configurer}'s constructor), which would compute
 * the wrong expected audience for requests actually mounted at {@code /api/mcp/**}. This must be
 * the exact endpoint path, not just its prefix ({@code /api/mcp}): {@code
 * ResourceIdentifier.getResource()} builds the expected audience string from this configured path
 * verbatim, and real MCP clients (Claude.ai confirmed, via its own RFC 9728
 * protected-resource-metadata probe at {@code /.well-known/oauth-protected-resource/api/mcp/sse})
 * send the FULL canonical endpoint URL as the RFC 8707 {@code resource} parameter when requesting a
 * token -- not just its path prefix. A mismatch here (this was originally {@code "/api/mcp"}) means
 * the issued token's {@code aud} claim (set verbatim from whatever the client requested, confirmed
 * via decompiling {@code ResourceIdentifierAudienceTokenCustomizer}) never matches what this
 * validator expects, and every real call is rejected with "aud claim is not valid" -- discovered
 * live: Claude.ai could complete DCR, consent, and token exchange, then had every single MCP call
 * rejected in a token-refresh retry loop.
 *
 * <p>Also explicitly disables CSRF, forces a stateless session policy, and applies the shared
 * Claude.ai CORS configuration ({@link McpCorsConfig}) on this chain. Spring Security resolves each
 * incoming request to exactly one {@code SecurityFilterChain} by matching {@code securityMatcher}s
 * in {@code @Order}; once this chain's own matcher claims {@code /api/mcp/**}, SecurityConfig's
 * default chain (and its {@code cors()}/{@code csrf().disable()} configuration) never sees these
 * requests again, so each must be repeated here or Bearer-token MCP calls get 403'd by the default
 * CSRF policy instead of authenticated normally.
 */
@Configuration
public class McpResourceServerConfig {

  private final String issuer;
  private final JWKSource<SecurityContext> jwkSource;
  private final CorsConfigurationSource mcpCorsSource;

  public McpResourceServerConfig(
      @Value("${archiver.mcp.oauth.issuer:https://archive.czernin.eu}") String issuer,
      JWKSource<SecurityContext> jwkSource,
      CorsConfigurationSource mcpCorsSource) {
    this.issuer = issuer;
    this.jwkSource = jwkSource;
    this.mcpCorsSource = mcpCorsSource;
  }

  @Bean
  @Order(2)
  public SecurityFilterChain mcpResourceServerSecurityFilterChain(HttpSecurity http)
      throws Exception {
    NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
    jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));

    // McpServerOAuth2Configurer registers Spring's own OAuth2ProtectedResourceMetadataFilter
    // (RFC 9728) as part of THIS chain's filters -- but a chain's filters only ever run for
    // requests that first match ITS OWN securityMatcher. "/api/mcp/**" alone never matches
    // /.well-known/oauth-protected-resource (bare, or with the resource path appended, which
    // MCP clients including Claude.ai probe per RFC 9728's path-appended convention), so those
    // requests fell through to SecurityConfig's default deny-by-default chain and got a bare
    // 403 instead of ever reaching the metadata filter -- the client never even learned this
    // resource's authorization server from that document. Same class of gap as
    // McpAuthorizationServerConfig's /oauth2/register matcher union.
    RequestMatcher securityMatcher =
        new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher("/api/mcp/**"),
            PathPatternRequestMatcher.withDefaults()
                .matcher("/.well-known/oauth-protected-resource"),
            PathPatternRequestMatcher.withDefaults()
                .matcher("/.well-known/oauth-protected-resource/**"));

    http.securityMatcher(securityMatcher)
        .cors(cors -> cors.configurationSource(mcpCorsSource))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .with(
            McpServerOAuth2Configurer.mcpServerOAuth2(),
            (mcpAuthorization) -> {
              mcpAuthorization.authorizationServer(issuer);
              mcpAuthorization.resourcePath("/api/mcp/sse");
              mcpAuthorization.jwtDecoder(jwtDecoder);
              mcpAuthorization.validateAudienceClaim(true);
            })
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        PathPatternRequestMatcher.withDefaults()
                            .matcher("/.well-known/oauth-protected-resource"),
                        PathPatternRequestMatcher.withDefaults()
                            .matcher("/.well-known/oauth-protected-resource/**"))
                    .permitAll()
                    .anyRequest()
                    .authenticated());

    return http.build();
  }
}
