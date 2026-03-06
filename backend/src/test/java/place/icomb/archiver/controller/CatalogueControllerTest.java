package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogueControllerTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void setUp() {
    jdbc.sql("DELETE FROM record WHERE title LIKE 'CatTest%'").update();
  }

  @Test
  void listRecordsReturnsIndexTermsAsArray() throws Exception {
    Long archiveId =
        jdbc.sql(
                "INSERT INTO archive (name, country) VALUES ('CatTestArchive', 'AT')"
                    + " ON CONFLICT DO NOTHING RETURNING id")
            .query(Long.class)
            .optional()
            .orElseGet(
                () ->
                    jdbc.sql("SELECT id FROM archive WHERE name = 'CatTestArchive'")
                        .query(Long.class)
                        .single());

    jdbc.sql(
            "INSERT INTO record (archive_id, source_system, source_record_id, title, status,"
                + " index_terms) VALUES (:aid, 'test', 'cat-test-1', :title, 'ingested', :terms)")
        .param("aid", archiveId)
        .param("title", "CatTest IndexTerms Record")
        .param("terms", "[\"term1\", \"term2\", \"term3\"]")
        .update();

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/api/records?archiveId="
                        + archiveId
                        + "&sortBy=createdAt&sortDir=desc"))
            .GET()
            .build();

    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);

    JsonNode body = mapper.readTree(response.body());
    JsonNode content = body.get("content");
    assertThat(content).isNotNull();
    assertThat(content.size()).isGreaterThanOrEqualTo(1);

    JsonNode record = content.get(0);
    JsonNode indexTerms = record.get("indexTerms");
    assertThat(indexTerms).isNotNull();
    assertThat(indexTerms.isArray()).isTrue();
    assertThat(indexTerms.size()).isEqualTo(3);
    assertThat(indexTerms.get(0).asText()).isEqualTo("term1");
    assertThat(indexTerms.get(1).asText()).isEqualTo("term2");
    assertThat(indexTerms.get(2).asText()).isEqualTo("term3");
  }
}
