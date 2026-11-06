package place.icomb.archiver.config;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import place.icomb.archiver.repository.AppUserRepository;

/**
 * Gives an MCP call the roles its user holds now. The JWT carries no role, so the user is looked up
 * on every call: making someone an administrator, or removing them, takes effect without a new
 * token.
 */
public class McpJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  private final AppUserRepository appUserRepository;

  public McpJwtAuthenticationConverter(AppUserRepository appUserRepository) {
    this.appUserRepository = appUserRepository;
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    return new JwtAuthenticationToken(jwt, authorities(jwt.getSubject()), jwt.getSubject());
  }

  private List<GrantedAuthority> authorities(String subject) {
    if (subject == null || subject.isBlank()) {
      return List.of();
    }
    return appUserRepository
        .findByEmail(subject.trim().toLowerCase())
        .<List<GrantedAuthority>>map(
            user ->
                "admin".equals(user.getRole())
                    ? List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))
                    : List.of(new SimpleGrantedAuthority("ROLE_USER")))
        .orElse(List.of());
  }
}
