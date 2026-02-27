package place.icomb.archiver.config;

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

  public SecurityConfig(AppUserRepository appUserRepository) {
    this.appUserRepository = appUserRepository;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new ProxyAuthFilter(appUserRepository), UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth
                    // GET requests are read-only — allow anonymous
                    .requestMatchers(HttpMethod.GET, "/api/**")
                    .permitAll()
                    // Worker/scraper endpoints — own bearer token auth
                    .requestMatchers("/api/processor/**")
                    .permitAll()
                    .requestMatchers("/api/ingest/**")
                    .permitAll()
                    // Semantic search is a read-only POST
                    .requestMatchers(HttpMethod.POST, "/api/search/semantic")
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
