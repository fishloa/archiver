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
 * Authenticates requests bearing a static bearer token and grants the matching role.
 *
 * <p>Two tokens are recognised:
 *
 * <ul>
 *   <li>the <b>processor</b> token — ROLE_PROCESSOR, used by workers and scrapers against {@code
 *       /api/processor/**} and {@code /api/ingest/**}
 *   <li>the <b>admin</b> token — ROLE_ADMIN, for operating the archive from a script or the command
 *       line. The admin endpoints ({@code /api/viewer/admin/**}, pipeline resets) sit behind {@code
 *       POST /api/**} which requires USER or ADMIN, and the OAuth2 login that normally supplies
 *       those roles cannot be scripted, so without this every operational task had to be done as
 *       hand-written SQL against production.
 * </ul>
 *
 * <p>Both are optional and fail closed: a null or blank configured token never matches, so leaving
 * {@code archiver.admin.token} unset simply means no token grants admin.
 */
public class ProcessorTokenFilter extends OncePerRequestFilter {

  private final String processorToken;
  private final String adminToken;

  public ProcessorTokenFilter(String processorToken, String adminToken) {
    this.processorToken = processorToken;
    this.adminToken = adminToken;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    // Only attempt if no auth already set (ProxyAuthFilter may have run first)
    if (SecurityContextHolder.getContext().getAuthentication() == null) {
      String authHeader = request.getHeader("Authorization");
      if (authHeader != null && authHeader.startsWith("Bearer ")) {
        String presented = authHeader.substring(7);
        if (matches(presented, adminToken)) {
          authenticate("admin", "ROLE_ADMIN");
        } else if (matches(presented, processorToken)) {
          authenticate("processor", "ROLE_PROCESSOR");
        }
      }
    }

    filterChain.doFilter(request, response);
  }

  private static void authenticate(String principal, String role) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(role))));
  }

  /**
   * Constant-time comparison. A token is a shared secret compared on every request, so {@code
   * equals} would leak its prefix through response timing.
   */
  private static boolean matches(String presented, String configured) {
    if (configured == null || configured.isBlank() || presented == null) {
      return false;
    }
    return MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8), configured.getBytes(StandardCharsets.UTF_8));
  }
}
