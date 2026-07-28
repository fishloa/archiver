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
 * Recognises an existing, valid site session at the MCP OAuth Authorization Server's
 * /oauth2/authorize endpoint, so authorizing the MCP connector needs no second Google/Apple login
 * if the browser already has one. Structurally identical to ProxyAuthFilter -- same trust boundary
 * (X-Auth-Email only honoured from a trusted peer), same allowlist -- but scoped to this one
 * endpoint and registered only on the Authorization Server's security filter chain, not the main
 * application chain.
 *
 * <p>If no trusted, allowlisted identity is found, this filter does nothing and lets the request
 * fall through unauthenticated -- McpAuthorizationServerConfig's exceptionHandling then redirects
 * to /signin, exactly like every other unauthenticated browser route on this site.
 */
public class McpAuthorizeSessionFilter extends OncePerRequestFilter {

  private final AppUserRepository appUserRepository;
  private final TrustedPeerResolver trustedPeerResolver;

  public McpAuthorizeSessionFilter(
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
      appUserRepository
          .findByEmail(normalised.toLowerCase())
          .ifPresent(
              user -> {
                // Deliberately no auth.setDetails(user) here (unlike ProxyAuthFilter, which this
                // filter is otherwise modeled on): this Authentication gets persisted as JSON by
                // JdbcOAuth2AuthorizationService while the authorization request is in flight (e.g.
                // across the redirect to the consent page and back), using Spring Security's own
                // hardened Jackson module with a fixed PolymorphicTypeValidator allowlist. AppUser
                // isn't on that allowlist, so writing it succeeds but reading it back throws
                // InvalidTypeIdException -- surfacing as an opaque 403 on the consent-accept POST.
                // The allowlist check and role are already resolved by this point (baked into
                // buildAuthorities below), so nothing downstream needs the raw AppUser back.
                var auth =
                    new UsernamePasswordAuthenticationToken(
                        normalised, null, buildAuthorities(user));
                SecurityContextHolder.getContext().setAuthentication(auth);
              });
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
