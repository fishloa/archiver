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
