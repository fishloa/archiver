package place.icomb.archiver.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import place.icomb.archiver.repository.AppUserRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

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

  @Bean
  public TrustedPeerResolver trustedPeerResolver() {
    return trustedPeerResolver;
  }

  private static List<String> splitCsv(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new ProxyAuthFilter(appUserRepository, trustedPeerResolver),
            UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(
            new ProcessorTokenFilter(processorToken), UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth
                    // Admin GET endpoints — admin only (must precede general GET permit)
                    .requestMatchers(HttpMethod.GET, "/api/admin/**")
                    .hasRole("ADMIN")
                    // Auth endpoint — must precede the general GET permit below so it isn't
                    // shadowed; /api/auth/me must answer for a signed-out caller.
                    .requestMatchers("/api/auth/**")
                    .permitAll()
                    // All reads require an allowlisted user
                    .requestMatchers(HttpMethod.GET, "/api/**")
                    .hasAnyRole("USER", "ADMIN", "PROCESSOR")
                    // Worker/scraper ingest — bearer token or admin only
                    .requestMatchers(HttpMethod.POST, "/api/ingest/**")
                    .hasAnyRole("PROCESSOR", "ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/ingest/**")
                    .hasAnyRole("PROCESSOR", "ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/api/ingest/**")
                    .hasAnyRole("PROCESSOR", "ADMIN")
                    // Worker processor endpoints — bearer token or admin only
                    .requestMatchers(HttpMethod.POST, "/api/processor/**")
                    .hasAnyRole("PROCESSOR", "ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/processor/**")
                    .hasAnyRole("PROCESSOR", "ADMIN")
                    // Claude translation — requires login
                    .requestMatchers(HttpMethod.POST, "/api/translate/claude")
                    .hasAnyRole("USER", "ADMIN")
                    // Self-service profile — requires login
                    .requestMatchers(HttpMethod.PUT, "/api/profile")
                    .hasAnyRole("USER", "ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/profile/**")
                    .hasAnyRole("USER", "ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/api/profile/**")
                    .hasAnyRole("USER", "ADMIN")
                    // Admin endpoints — admin only
                    .requestMatchers(HttpMethod.POST, "/api/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/api/admin/**")
                    .hasRole("ADMIN")
                    // All other mutating requests — authenticated users
                    .requestMatchers(HttpMethod.POST, "/api/**")
                    .hasAnyRole("USER", "ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/**")
                    .hasAnyRole("USER", "ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/api/**")
                    .hasAnyRole("USER", "ADMIN")
                    // Only actuator health is public — swagger and static resources now
                    // require authentication, matched by the anyRequest() rule below
                    .requestMatchers("/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated());

    return http.build();
  }
}
