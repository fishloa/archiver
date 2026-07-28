package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression test for the X-Auth-Email spoofing defect: the backend granted ROLE_ADMIN purely on
 * the strength of a client-supplied header.
 *
 * <p>Trusted peers are overridden to nothing here. Without that override the test would pass
 * vacuously, because the test client connects over loopback and loopback is trusted by default.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthSpoofingRegressionTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  private static final String ADMIN_EMAIL = "spoof-admin@example.com";

  @LocalServerPort private int port;

  @Autowired private JdbcClient jdbc;

  private final HttpClient http = HttpClient.newHttpClient();

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    // Untrust every peer, including loopback, so the reject path is actually exercised.
    registry.add("archiver.auth.trusted-cidrs", () -> "");
    registry.add("archiver.auth.trusted-proxy-hosts", () -> "no-such-host.invalid");
  }

  @BeforeEach
  void seedAdmin() {
    jdbc.sql("DELETE FROM app_user_email WHERE email = :e").param("e", ADMIN_EMAIL).update();
    jdbc.sql("DELETE FROM app_user WHERE display_name = 'SpoofTest Admin'").update();

    Long userId =
        jdbc.sql(
                "INSERT INTO app_user (display_name, role) "
                    + "VALUES ('SpoofTest Admin', 'admin') RETURNING id")
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:id, :e)")
        .param("id", userId)
        .param("e", ADMIN_EMAIL)
        .update();
  }

  @Test
  void spoofedAdminEmailFromUntrustedPeerIsRejected() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/users"))
            .header("X-Auth-Email", ADMIN_EMAIL)
            .GET()
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isNotEqualTo(200);
    assertThat(response.body()).doesNotContain(ADMIN_EMAIL);
  }

  @Test
  void spoofedEmailWithForwardedForSpoofAlsoRejected() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/users"))
            .header("X-Auth-Email", ADMIN_EMAIL)
            .header("X-Forwarded-For", "127.0.0.1")
            .header("X-Real-IP", "127.0.0.1")
            .GET()
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isNotEqualTo(200);
  }
}
