package place.icomb.archiver.config;

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

  public SecurityConfig(
      AppUserRepository appUserRepository,
      @Value("${archiver.processor.token}") String processorToken) {
    this.appUserRepository = appUserRepository;
    this.processorToken = processorToken;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new ProxyAuthFilter(appUserRepository), UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(
            new ProcessorTokenFilter(processorToken), UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth
                    // MCP server endpoints — read-only tools, no auth needed
                    .requestMatchers("/api/mcp/**")
                    .permitAll()
                    // GET requests are read-only — allow anonymous
                    .requestMatchers(HttpMethod.GET, "/api/**")
                    .permitAll()
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
                    // Semantic search is a read-only POST
                    .requestMatchers(HttpMethod.POST, "/api/search/semantic")
                    .permitAll()
                    // Claude translation — requires login
                    .requestMatchers(HttpMethod.POST, "/api/translate/claude")
                    .hasAnyRole("USER", "ADMIN")
                    // On-demand worker translation
                    .requestMatchers(HttpMethod.POST, "/api/translate")
                    .permitAll()
                    // Family tree maintenance — idempotent
                    .requestMatchers(HttpMethod.POST, "/api/family-tree/reload")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/family-tree/invalidate-matches")
                    .permitAll()
                    // Auth endpoint
                    .requestMatchers("/api/auth/**")
                    .permitAll()
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
                    // Everything else (actuator, swagger, etc.)
                    .anyRequest()
                    .permitAll());

    return http.build();
  }
}
