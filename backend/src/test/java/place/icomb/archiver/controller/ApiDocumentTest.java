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

/**
 * The machine-readable document payload, which is what the MCP tools serve to an LLM.
 *
 * <p>It returned page text with no statement of its media type, so a consumer had to guess whether
 * it held markdown or plain text — the one thing the archive's own conventions forbid.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiDocumentTest {

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

  private static final String TEST_EMAIL = "api-doc-test@example.com";

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  private Long archiveId;

  @BeforeEach
  void setUp() {
    jdbc.sql("DELETE FROM app_user_email WHERE email = :e").param("e", TEST_EMAIL).update();
    jdbc.sql("DELETE FROM app_user WHERE display_name = 'ApiDoc User'").update();
    Long userId =
        jdbc.sql(
                "INSERT INTO app_user (display_name, role) VALUES ('ApiDoc User', 'user')"
                    + " RETURNING id")
            .query(Long.class)
            .single();
    jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:u, :e)")
        .param("u", userId)
        .param("e", TEST_EMAIL)
        .update();

    archiveId =
        jdbc.sql("INSERT INTO archive (name, country) VALUES ('ApiDocArchive', 'CZ') RETURNING id")
            .query(Long.class)
            .single();
  }

  /** Creates a record with one page per supplied text, in order. */
  private Long recordWithPages(String contentType, String... texts) {
    Long recordId =
        jdbc.sql(
                "INSERT INTO record (archive_id, source_system, source_record_id, title, status)"
                    + " VALUES (:a, 'test', :s, 'ApiDoc Record', 'complete') RETURNING id")
            .param("a", archiveId)
            .param("s", "apidoc-" + System.nanoTime())
            .query(Long.class)
            .single();

    int seq = 1;
    for (String text : texts) {
      Long attachmentId =
          jdbc.sql(
                  "INSERT INTO attachment (record_id, role, path, created_at)"
                      + " VALUES (:r, 'page_image', :p, now()) RETURNING id")
              .param("r", recordId)
              .param("p", "/test/" + recordId + "/" + seq)
              .query(Long.class)
              .single();
      Long pageId =
          jdbc.sql(
                  "INSERT INTO page (record_id, seq, attachment_id) VALUES (:r, :s, :a) RETURNING"
                      + " id")
              .param("r", recordId)
              .param("s", seq)
              .param("a", attachmentId)
              .query(Long.class)
              .single();
      jdbc.sql(
              "INSERT INTO page_text (page_id, text_raw, content_type, engine)"
                  + " VALUES (:p, :t, :c, 'test')")
          .param("p", pageId)
          .param("t", text)
          .param("c", contentType)
          .update();
      seq++;
    }
    return recordId;
  }

  private JsonNode getDocument(Long recordId) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/documents/" + recordId))
            .header("X-Auth-Email", TEST_EMAIL)
            .GET()
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return mapper.readTree(response.body());
  }

  @Test
  void eachPageStatesTheMediaTypeOfItsText() throws Exception {
    Long recordId = recordWithPages("text/markdown", "# Heading\n\nSome text");

    JsonNode doc = getDocument(recordId);

    assertThat(doc.get("pages").get(0).get("contentType").asText()).isEqualTo("text/markdown");
  }

  @Test
  void plainTextPagesSayPlainText() throws Exception {
    // The case that matters: "- 5 -" is a page number here, and a bullet in markdown.
    Long recordId = recordWithPages("text/plain", "- 5 -");

    JsonNode doc = getDocument(recordId);

    assertThat(doc.get("pages").get(0).get("contentType").asText()).isEqualTo("text/plain");
    assertThat(doc.get("pages").get(0).get("text").asText()).isEqualTo("- 5 -");
  }

  @Test
  void fullTextJoinsEveryPageInSequenceOrder() throws Exception {
    Long recordId = recordWithPages("text/plain", "first page", "second page", "third page");

    JsonNode doc = getDocument(recordId);

    String full = doc.get("fullText").asText();
    assertThat(full).isEqualTo("first page\n\f\nsecond page\n\f\nthird page");
  }

  @Test
  void fullTextSkipsBlankPagesRatherThanEmittingRunsOfSeparators() throws Exception {
    Long recordId = recordWithPages("text/plain", "first page", "   ", "third page");

    JsonNode doc = getDocument(recordId);

    assertThat(doc.get("fullText").asText()).isEqualTo("first page\n\f\nthird page");
  }

  @Test
  void fullTextIsEmptyWhenNoPageCarriesText() throws Exception {
    Long recordId = recordWithPages("text/plain", "", "");

    JsonNode doc = getDocument(recordId);

    assertThat(doc.get("fullText").asText()).isEmpty();
  }

  @Test
  void aRecordWithNoPagesStillAnswersWithTheFullTextFields() throws Exception {
    Long recordId =
        jdbc.sql(
                "INSERT INTO record (archive_id, source_system, source_record_id, title, status)"
                    + " VALUES (:a, 'test', :s, 'ApiDoc Empty', 'complete') RETURNING id")
            .param("a", archiveId)
            .param("s", "apidoc-empty-" + System.nanoTime())
            .query(Long.class)
            .single();

    JsonNode doc = getDocument(recordId);

    assertThat(doc.get("pages")).isEmpty();
    assertThat(doc.get("fullText").asText()).isEmpty();
    assertThat(doc.get("fullTextEn").asText()).isEmpty();
  }
}
