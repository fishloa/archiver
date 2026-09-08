package place.icomb.archiver.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The admin token is the only scriptable route to ROLE_ADMIN, so its failure modes matter: a blank
 * configured token must never match, and the processor token must never escalate.
 */
class ProcessorTokenFilterTest {

  private static final String PROCESSOR = "processor-secret";
  private static final String ADMIN = "admin-secret";

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private List<String> rolesFor(String bearer, String processorToken, String adminToken)
      throws Exception {
    HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
    Mockito.when(request.getHeader("Authorization")).thenReturn(bearer);
    HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
    FilterChain chain = Mockito.mock(FilterChain.class);

    new ProcessorTokenFilter(processorToken, adminToken).doFilter(request, response, chain);

    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth == null
        ? List.of()
        : auth.getAuthorities().stream().map(Object::toString).collect(Collectors.toList());
  }

  @Test
  void adminTokenGrantsAdmin() throws Exception {
    assertThat(rolesFor("Bearer " + ADMIN, PROCESSOR, ADMIN)).containsExactly("ROLE_ADMIN");
  }

  @Test
  void processorTokenGrantsProcessorOnly() throws Exception {
    assertThat(rolesFor("Bearer " + PROCESSOR, PROCESSOR, ADMIN)).containsExactly("ROLE_PROCESSOR");
  }

  @Test
  void blankAdminTokenNeverMatches() throws Exception {
    // The property defaults to empty. An empty bearer must not authenticate as admin.
    assertThat(rolesFor("Bearer ", PROCESSOR, "")).isEmpty();
    assertThat(rolesFor("Bearer  ", PROCESSOR, " ")).isEmpty();
  }

  @Test
  void nullAdminTokenNeverMatches() throws Exception {
    assertThat(rolesFor("Bearer anything", PROCESSOR, null)).isEmpty();
  }

  @Test
  void wrongTokenGrantsNothing() throws Exception {
    assertThat(rolesFor("Bearer nope", PROCESSOR, ADMIN)).isEmpty();
  }

  @Test
  void missingHeaderGrantsNothing() throws Exception {
    assertThat(rolesFor(null, PROCESSOR, ADMIN)).isEmpty();
  }
}
