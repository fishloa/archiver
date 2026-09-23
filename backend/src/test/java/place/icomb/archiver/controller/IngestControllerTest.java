package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static place.icomb.archiver.TestAuth.PROCESSOR_AUTH_HEADER;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
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
 * Uses the JDK {@link HttpClient} rather than REST Assured, which pulled in a Groovy runtime that
 * does not sit well with Spring Boot 4 and was the only remaining user of that dependency.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestControllerTest {

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

  @Test
  void seedAndCreateRecord() throws Exception {
    Long archiveId =
        jdbc.sql("INSERT INTO archive (name, country) VALUES (:name, :country) RETURNING id")
            .param("name", "Test Archive")
            .param("country", "AT")
            .query(Long.class)
            .single();

    String recordBody =
        """
        {
          "archiveId": %d,
          "sourceSystem": "test-system",
          "sourceRecordId": "REC-001",
          "title": "Test Record",
          "description": "A test archival record",
          "referenceCode": "AT-OeStA/HHStA UR 1234"
        }
        """
            .formatted(archiveId);

    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/ingest/records"))
                .header("Content-Type", "application/json")
                .header("Authorization", PROCESSOR_AUTH_HEADER)
                .POST(HttpRequest.BodyPublishers.ofString(recordBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(201);

    @SuppressWarnings("unchecked")
    Map<String, Object> body = mapper.readValue(response.body(), Map.class);
    assertThat(body.get("id")).isNotNull();
    assertThat(body.get("sourceSystem")).isEqualTo("test-system");
    assertThat(body.get("sourceRecordId")).isEqualTo("REC-001");
  }

  @Test
  void ocrEngineHintIsStoredAndUsedWhenTheRecordIsCompleted() throws Exception {
    Long archiveId =
        jdbc.sql("INSERT INTO archive (name, country) VALUES (:name, :country) RETURNING id")
            .param("name", "Engine Hint Archive")
            .param("country", "AT")
            .query(Long.class)
            .single();

    Long recordId = createRecord(archiveId, "REC-HINT", "\"ocrEngine\": \"ocr_page_transkribus\",");

    assertThat(
            jdbc.sql("SELECT ocr_engine FROM record WHERE id = :id")
                .param("id", recordId)
                .query(String.class)
                .single())
        .isEqualTo("ocr_page_transkribus");

    // A page and a completion: the pipeline must queue the record's engine, not the default.
    postPage(recordId);
    http.send(
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    "http://localhost:" + port + "/api/ingest/records/" + recordId + "/complete"))
            .header("Authorization", PROCESSOR_AUTH_HEADER)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(
            jdbc.sql("SELECT kind FROM job WHERE record_id = :id AND kind LIKE 'ocr_page_%'")
                .param("id", recordId)
                .query(String.class)
                .list())
        .containsExactly("ocr_page_transkribus");
  }

  @Test
  void anEngineNothingClaimsIsRefused() throws Exception {
    Long archiveId =
        jdbc.sql("INSERT INTO archive (name, country) VALUES (:name, :country) RETURNING id")
            .param("name", "Bad Engine Archive")
            .param("country", "AT")
            .query(Long.class)
            .single();

    String recordBody =
        """
        {
          "archiveId": %d,
          "sourceSystem": "test-system",
          "sourceRecordId": "REC-BAD-ENGINE",
          "title": "Test Record",
          "ocrEngine": "ocr_page_nonesuch"
        }
        """
            .formatted(archiveId);

    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/ingest/records"))
                .header("Content-Type", "application/json")
                .header("Authorization", PROCESSOR_AUTH_HEADER)
                .POST(HttpRequest.BodyPublishers.ofString(recordBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("ocr_page_nonesuch");
    assertThat(
            jdbc.sql("SELECT count(*) FROM record WHERE source_record_id = 'REC-BAD-ENGINE'")
                .query(Long.class)
                .single())
        .isZero();
  }

  private Long createRecord(Long archiveId, String sourceRecordId, String extraJson)
      throws Exception {
    String recordBody =
        """
        {
          "archiveId": %d,
          "sourceSystem": "test-system",
          "sourceRecordId": "%s",
          %s
          "title": "Test Record"
        }
        """
            .formatted(archiveId, sourceRecordId, extraJson);

    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/ingest/records"))
                .header("Content-Type", "application/json")
                .header("Authorization", PROCESSOR_AUTH_HEADER)
                .POST(HttpRequest.BodyPublishers.ofString(recordBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(201);

    @SuppressWarnings("unchecked")
    Map<String, Object> body = mapper.readValue(response.body(), Map.class);
    return ((Number) body.get("id")).longValue();
  }

  /** A one-pixel JPEG is enough: this is about which job kind is queued, not about the image. */
  private void postPage(Long recordId) throws Exception {
    var image = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB),
        "jpg",
        image);

    String boundary = "pageboundary";
    var body = new java.io.ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"p1.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    body.write(image.toByteArray());
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

    http.send(
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/api/ingest/records/"
                        + recordId
                        + "/pages?seq=1"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("Authorization", PROCESSOR_AUTH_HEADER)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
