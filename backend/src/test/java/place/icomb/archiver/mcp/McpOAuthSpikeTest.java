package place.icomb.archiver.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;

/**
 * Spike, not a permanent test: proves the community MCP OAuth libraries actually resolve and their
 * key classes are on the classpath before any real code depends on them. Delete once Task 2 is
 * confirmed complete -- superseded by the real integration test in Task 9.
 *
 * <p>Note: both package paths differ from the task brief's guesses, verified via {@code unzip -l}
 * against the resolved jars:
 *
 * <ul>
 *   <li>{@code McpServerOAuth2Configurer} is at {@code
 *       org.springaicommunity.mcp.security.server.config} (jar: {@code
 *       mcp-server-security-0.1.13.jar}) -- one segment deeper than the guessed {@code
 *       org.springaicommunity.mcp.security.server}.
 *   <li>{@code OAuth2AuthorizationServerConfigurer} is at {@code
 *       org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization}
 *       (jar: {@code spring-security-config-7.1.0.jar}) -- a different package tree entirely, and a
 *       different jar, from the guessed {@code
 *       org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers}
 *       (that package/jar does not contain this class at all in 7.1.0).
 * </ul>
 */
class McpOAuthSpikeTest {

  @Test
  void keyLibraryClassesAreOnTheClasspath() {
    assertThat(McpServerOAuth2Configurer.class).isNotNull();
    assertThat(OAuth2AuthorizationServerConfigurer.class).isNotNull();
  }
}
