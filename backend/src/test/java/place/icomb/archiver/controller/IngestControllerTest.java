package place.icomb.archiver.controller;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
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

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @BeforeEach
  void setUp() {
    RestAssured.port = port;
    RestAssured.basePath = "/api";
  }

  @Test
  void seedAndCreateRecord() {
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

    given()
        .contentType(ContentType.JSON)
        .body(recordBody)
        .when()
        .post("/ingest/records")
        .then()
        .statusCode(201)
        .body("id", notNullValue())
        .body("sourceSystem", equalTo("test-system"))
        .body("sourceRecordId", equalTo("REC-001"));
  }
}
