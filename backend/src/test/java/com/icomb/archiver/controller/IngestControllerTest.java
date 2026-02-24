package com.icomb.archiver.controller;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestControllerTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:18-alpine")
          .withDatabaseName("archiver_test")
          .withUsername("archiver")
          .withPassword("archiver")
          .withCommand("postgres", "-c", "max_connections=50");

  @LocalServerPort private int port;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    RestAssured.basePath = "/api";
  }

  @Test
  void fullIngestWorkflow_createRecord_addPage_complete_verifyStatus() {
    // Step 1: Create an archive first (we need a valid archive_id).
    // Use JDBC directly since there's no archive ingest endpoint.
    // Instead, we'll insert via the ingest endpoint — but we need an archive.
    // For this test, insert the archive via a direct HTTP call to a helper,
    // or we insert it via the DB. Let's use a simpler approach: pre-seed.
    //
    // Actually, the IngestController does not create archives.
    // We'll insert one directly via RestAssured calling a JDBC-backed seed.
    // Simplest: use the Spring JdbcTemplate through a test utility.
    //
    // For a self-contained REST Assured test, we'll seed via raw SQL
    // executed through a small helper endpoint. But we don't have one.
    //
    // Best approach: insert archive via Testcontainers JDBC connection.
    Long archiveId = seedArchive();

    // Step 2: Create a record via POST /api/ingest/records
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

    int recordId =
        given()
            .contentType(ContentType.JSON)
            .body(recordBody)
            .when()
            .post("/ingest/records")
            .then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("sourceSystem", equalTo("test-system"))
            .body("sourceRecordId", equalTo("REC-001"))
            .body("status", equalTo("ingesting"))
            .extract()
            .path("id");

    // Step 3: Add a page image via POST /api/ingest/records/{recordId}/pages
    byte[] fakeImage = createMinimalJpeg();

    given()
        .multiPart("image", "page.jpg", fakeImage, "image/jpeg")
        .param("seq", 1)
        .when()
        .post("/ingest/records/{recordId}/pages", recordId)
        .then()
        .statusCode(201)
        .body("id", notNullValue())
        .body("recordId", equalTo(recordId))
        .body("seq", equalTo(1))
        .body("attachmentId", greaterThan(0));

    // Step 4: Complete the ingest via POST /api/ingest/records/{recordId}/complete
    given()
        .when()
        .post("/ingest/records/{recordId}/complete", recordId)
        .then()
        .statusCode(200)
        .body("id", equalTo(recordId))
        .body("status", equalTo("ocr_pending"));

    // Step 5: Verify status via GET /api/ingest/status/{sourceSystem}/{sourceRecordId}
    given()
        .when()
        .get("/ingest/status/{sourceSystem}/{sourceRecordId}", "test-system", "REC-001")
        .then()
        .statusCode(200)
        .body("id", equalTo(recordId))
        .body("status", equalTo("ocr_pending"))
        .body("sourceSystem", equalTo("test-system"))
        .body("sourceRecordId", equalTo("REC-001"));
  }

  /**
   * Seeds a test archive directly into the database and returns its ID. Uses the Testcontainers
   * JDBC connection.
   */
  private Long seedArchive() {
    try (var conn = java.sql.DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var stmt =
            conn.prepareStatement(
                "INSERT INTO archive (name, country, created_at) VALUES (?, ?, now()) RETURNING id")) {
      stmt.setString(1, "Test Archive");
      stmt.setString(2, "AT");
      var rs = stmt.executeQuery();
      rs.next();
      return rs.getLong(1);
    } catch (Exception e) {
      throw new RuntimeException("Failed to seed archive", e);
    }
  }

  /**
   * Creates a minimal valid JPEG byte array. This is the smallest valid JPEG: SOI + EOI markers.
   */
  private byte[] createMinimalJpeg() {
    return new byte[] {
      (byte) 0xFF, (byte) 0xD8, // SOI
      (byte) 0xFF, (byte) 0xE0, // APP0 marker
      0x00, 0x10, // APP0 length
      0x4A, 0x46, 0x49, 0x46, 0x00, // "JFIF\0"
      0x01, 0x01, // version
      0x00, // units
      0x00, 0x01, // X density
      0x00, 0x01, // Y density
      0x00, 0x00, // thumbnail
      (byte) 0xFF, (byte) 0xD9 // EOI
    };
  }
}
