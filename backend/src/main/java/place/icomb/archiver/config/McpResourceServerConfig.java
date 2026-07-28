package place.icomb.archiver.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.List;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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
 * <p>Also sets {@code resourcePath("/api/mcp")} -- the library's default resource path is {@code
 * /mcp} (baked into {@code McpServerOAuth2Configurer}'s constructor), which would compute the wrong
 * expected audience for requests actually mounted at {@code /api/mcp/**}.
 *
 * <p>Also explicitly disables CSRF, forces a stateless session policy, and re-registers the
 * Claude.ai CORS configuration on this chain. Spring Security resolves each incoming request to
 * exactly one {@code SecurityFilterChain} by matching {@code securityMatcher}s in {@code @Order};
 * once this chain's own matcher claims {@code /api/mcp/**}, SecurityConfig's default chain (and its
 * {@code cors()}/{@code csrf().disable()} configuration) never sees these requests again, so each
 * must be repeated here or Bearer-token MCP calls get 403'd by the default CSRF policy instead of
 * authenticated normally.
 */
@Configuration
public class McpResourceServerConfig {

  private final String issuer;
  private final JWKSource<SecurityContext> jwkSource;

  public McpResourceServerConfig(
      @Value("${archiver.mcp.oauth.issuer:https://archive.czernin.eu}") String issuer,
      JWKSource<SecurityContext> jwkSource) {
    this.issuer = issuer;
    this.jwkSource = jwkSource;
  }

  @Bean
  @Order(2)
  public SecurityFilterChain mcpResourceServerSecurityFilterChain(HttpSecurity http)
      throws Exception {
    NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
    jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));

    http.securityMatcher("/api/mcp/**")
        .cors(cors -> cors.configurationSource(mcpCorsSource()))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .with(
            McpServerOAuth2Configurer.mcpServerOAuth2(),
            (mcpAuthorization) -> {
              mcpAuthorization.authorizationServer(issuer);
              mcpAuthorization.resourcePath("/api/mcp");
              mcpAuthorization.jwtDecoder(jwtDecoder);
              mcpAuthorization.validateAudienceClaim(true);
            })
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());

    return http.build();
  }

  private CorsConfigurationSource mcpCorsSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("https://claude.ai"));
    config.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Mcp-Session-Id"));
    config.setExposedHeaders(List.of("Mcp-Session-Id"));

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/mcp/**", config);
    return source;
  }
}
