package place.icomb.archiver.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import place.icomb.archiver.model.AppUser;
import place.icomb.archiver.repository.AppUserRepository;

class McpJwtAuthenticationConverterTest {

  private final AppUserRepository users = mock(AppUserRepository.class);
  private final McpJwtAuthenticationConverter converter = new McpJwtAuthenticationConverter(users);

  private static Jwt jwt(String subject) {
    return Jwt.withTokenValue("t")
        .header("alg", "RS256")
        .subject(subject)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .build();
  }

  private static AppUser user(String role) {
    AppUser u = new AppUser();
    u.setRole(role);
    return u;
  }

  private java.util.List<String> roles(String subject) {
    return converter.convert(jwt(subject)).getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .toList();
  }

  @Test
  void anAdministratorsTokenCarriesTheAdminRole() {
    when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(user("admin")));
    assertThat(roles("Admin@Example.com ")).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
  }

  @Test
  void anOrdinaryUsersTokenDoesNot() {
    when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user("user")));
    assertThat(roles("user@example.com")).containsExactly("ROLE_USER");
  }

  @Test
  void aTokenForSomeoneNoLongerAllowlistedCarriesNoRole() {
    when(users.findByEmail("gone@example.com")).thenReturn(Optional.empty());
    assertThat(roles("gone@example.com")).isEmpty();
  }

  @Test
  void thePrincipalIsTheTokensSubject() {
    when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(user("admin")));
    assertThat(converter.convert(jwt("admin@example.com")).getName())
        .isEqualTo("admin@example.com");
  }
}
