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

/**
 * Establishes the caller's identity from the {@code X-Auth-Email} header set by the reverse proxy
 * after oauth2-proxy has authenticated them.
 *
 * <p>The header is honoured only when the request's TCP peer is trusted. Peer identity comes from
 * {@link HttpServletRequest#getRemoteAddr()} and never from {@code X-Forwarded-For} or {@code
 * X-Real-IP}, both of which the client controls.
 */
public class ProxyAuthFilter extends OncePerRequestFilter {

  /** Request attribute holding the email of a caller who signed in but is not on the allowlist. */
  public static final String SIGNED_IN_AS_ATTRIBUTE = "archiver.signedInAs";

  private final AppUserRepository appUserRepository;
  private final TrustedPeerResolver trustedPeerResolver;

  public ProxyAuthFilter(
      AppUserRepository appUserRepository, TrustedPeerResolver trustedPeerResolver) {
    this.appUserRepository = appUserRepository;
    this.trustedPeerResolver = trustedPeerResolver;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String email = request.getHeader("X-Auth-Email");

    if (email != null
        && !email.isBlank()
        && trustedPeerResolver.isTrusted(request.getRemoteAddr())) {
      String normalised = email.trim();
      var found = appUserRepository.findByEmail(normalised.toLowerCase());
      if (found.isPresent()) {
        AppUser user = found.get();
        var auth =
            new UsernamePasswordAuthenticationToken(normalised, null, buildAuthorities(user));
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
      } else {
        // Authenticated with the identity provider, but not on the allowlist. Record it so the
        // UI can say "signed in as X, no access" instead of bouncing back to sign-in forever.
        request.setAttribute(SIGNED_IN_AS_ATTRIBUTE, normalised);
      }
    }

    filterChain.doFilter(request, response);
  }

  private List<SimpleGrantedAuthority> buildAuthorities(AppUser user) {
    if ("admin".equals(user.getRole())) {
      return List.of(
          new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
    return List.of(new SimpleGrantedAuthority("ROLE_USER"));
  }
}
