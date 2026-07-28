package place.icomb.archiver.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Single source of truth for the /api/mcp/** CORS policy (Claude.ai origin only). Extracted out of
 * McpResourceServerConfig so that class doesn't duplicate what SecurityConfig originally defined
 * inline -- a second copy would have silently drifted the moment one was edited without the other.
 */
@Configuration
public class McpCorsConfig {

  @Bean
  public CorsConfigurationSource mcpCorsSource() {
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
