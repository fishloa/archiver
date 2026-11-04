package place.icomb.archiver.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import place.icomb.archiver.model.AppUser;
import place.icomb.archiver.service.ActivityService;

/**
 * Records what allowlisted users do. Only callers authenticated through the {@code X-Auth-Email}
 * header are recorded; bearer-token, MCP, anonymous and not-allowlisted callers never are.
 * Recording is handed to a background queue and can never fail or delay the request.
 */
public class ActivityFilter extends OncePerRequestFilter {

  private final ActivityService activity;

  public ActivityFilter(ActivityService activity) {
    this.activity = activity;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      chain.doFilter(request, response);
    } finally {
      try {
        if (request.getAttribute(ProxyAuthFilter.PROXY_USER_ATTRIBUTE) instanceof AppUser user
            && ("user".equals(user.getRole()) || "admin".equals(user.getRole()))) {
          String contentType = response.getContentType();
          if (contentType == null || !contentType.startsWith("text/event-stream")) {
            activity.record(
                user,
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                request.getHeader("Accept"),
                response.getStatus());
          }
        }
      } catch (RuntimeException e) {
        logger.warn("activity recording failed", e);
      }
    }
  }
}
