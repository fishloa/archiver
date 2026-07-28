package place.icomb.archiver.config;

import org.springaicommunity.mcp.security.authorizationserver.config.McpAuthorizationServerConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

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

  @Bean
  @Order(1)
  public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http)
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
    http.securityMatcher(endpointsMatcher)
        .csrf(csrf -> csrf.ignoringRequestMatchers(endpointsMatcher))
        .with(authorizationServerConfigurer, Customizer.withDefaults())
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

  @Bean
  public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
    return new JdbcRegisteredClientRepository(jdbcTemplate);
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
    return AuthorizationServerSettings.builder().build();
  }
}
