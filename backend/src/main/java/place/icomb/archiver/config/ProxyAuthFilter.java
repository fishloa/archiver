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
import place.icomb.archiver.model.AppUser;
import place.icomb.archiver.repository.AppUserRepository;

public class ProxyAuthFilter extends OncePerRequestFilter {

  private final AppUserRepository appUserRepository;

  public ProxyAuthFilter(AppUserRepository appUserRepository) {
    this.appUserRepository = appUserRepository;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String email = request.getHeader("X-Auth-Email");

    if (email != null && !email.isBlank()) {
      appUserRepository
          .findByEmail(email.trim().toLowerCase())
          .ifPresent(
              user -> {
                var authorities = buildAuthorities(user);
                var auth = new UsernamePasswordAuthenticationToken(email.trim(), null, authorities);
                auth.setDetails(user);
                SecurityContextHolder.getContext().setAuthentication(auth);
              });
    }

    filterChain.doFilter(request, response);
  }

  private List<SimpleGrantedAuthority> buildAuthorities(AppUser user) {
    if ("admin".equals(user.getRole())) {
      return List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
    return List.of(new SimpleGrantedAuthority("ROLE_USER"));
  }
}
