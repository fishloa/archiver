package place.icomb.archiver.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests bearing a valid processor token (Authorization: Bearer &lt;token&gt;) and
 * grants ROLE_PROCESSOR. This covers workers and scrapers hitting /api/processor/** and
 * /api/ingest/**.
 */
public class ProcessorTokenFilter extends OncePerRequestFilter {

  private final String processorToken;

  public ProcessorTokenFilter(String processorToken) {
    this.processorToken = processorToken;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    // Only attempt if no auth already set (ProxyAuthFilter may have run first)
    if (SecurityContextHolder.getContext().getAuthentication() == null) {
      String authHeader = request.getHeader("Authorization");
      if (authHeader != null
          && authHeader.startsWith("Bearer ")
          && authHeader.substring(7).equals(processorToken)) {
        var auth =
            new UsernamePasswordAuthenticationToken(
                "processor", null, List.of(new SimpleGrantedAuthority("ROLE_PROCESSOR")));
        SecurityContextHolder.getContext().setAuthentication(auth);
      }
    }

    filterChain.doFilter(request, response);
  }
}
