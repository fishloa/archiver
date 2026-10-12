package place.icomb.archiver.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;
import place.icomb.archiver.repository.AppUserRepository;

/**
 * Lets a signed-in MCP user call the same API the UI calls, with the access token the MCP connector
 * already holds, instead of a browser session.
 *
 * <p>The token is the one {@code /api/mcp/**} accepts, validated by the same decoder. The caller is
 * then looked up on the allowlist on every request, so removing a user stops their token working at
 * once rather than when it expires.
 *
 * <p>It is read access, plus asking for a PDF. A token that lives in a chat connector does not get
 * to change the archive through the UI's routes, so any other write is refused with 403. It also
 * never carries the administrator role, however the user is set up: the admin routes keep their own
 * bearer token.
 */
public class McpBearerFilter extends OncePerRequestFilter {

  private static final Pattern PDF_REQUEST = Pattern.compile("^/api/records/\\d+/pdf-exports$");

  private final JwtDecoder decoder;
  private final AppUserRepository appUserRepository;

  public McpBearerFilter(JwtDecoder decoder, AppUserRepository appUserRepository) {
    this.decoder = decoder;
    this.appUserRepository = appUserRepository;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String header = request.getHeader("Authorization");
    if (SecurityContextHolder.getContext().getAuthentication() == null
        && header != null
        && header.startsWith("Bearer ")
        && looksLikeJwt(header.substring(7))) {
      Jwt jwt = decode(header.substring(7));
      var user =
          jwt == null || jwt.getSubject() == null
              ? null
              : appUserRepository.findByEmail(jwt.getSubject().trim().toLowerCase()).orElse(null);
      if (user != null) {
        if (!permitted(request)) {
          response.setStatus(HttpServletResponse.SC_FORBIDDEN);
          response.setContentType(MediaType.APPLICATION_JSON_VALUE);
          response.getWriter().write("{\"error\":\"an MCP token may read, and request PDFs\"}");
          return;
        }
        var auth =
            new UsernamePasswordAuthenticationToken(
                jwt.getSubject().trim(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
      }
    }
    filterChain.doFilter(request, response);
  }

  private Jwt decode(String token) {
    try {
      return decoder.decode(token);
    } catch (JwtException e) {
      return null;
    }
  }

  /** A JWT has three dot-separated parts; anything else is another kind of token. */
  private static boolean looksLikeJwt(String token) {
    return token.chars().filter(c -> c == '.').count() == 2;
  }

  private static boolean permitted(HttpServletRequest request) {
    String method = request.getMethod();
    if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
      return true;
    }
    return "POST".equals(method) && PDF_REQUEST.matcher(request.getRequestURI()).matches();
  }
}
