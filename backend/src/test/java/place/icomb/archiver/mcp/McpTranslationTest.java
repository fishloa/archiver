package place.icomb.archiver.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.controller.ApiController;

/**
 * What a machine reader gets back.
 *
 * <p>One translation, the best one held, and no way to ask for a worse one. A person looking at a
 * page in the browser can be shown which models translated it and compare them; a model answering a
 * research question cannot weigh that and should not be handed the choice. Everything downstream of
 * this — MCP tools, the machine API — reads the same single field.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class McpTranslationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private ApiController apiController;
  @Autowired private JdbcTemplate jdbc;

  private Long recordId;
  private Long pageId;

  @BeforeEach
  void seed() {
    jdbc.execute("DELETE FROM page_translation");
    jdbc.execute("DELETE FROM page_text");
    jdbc.execute("DELETE FROM page");
    jdbc.execute("DELETE FROM attachment");
    jdbc.execute("DELETE FROM record");

    Long archiveId =
        jdbc.queryForObject("INSERT INTO archive (name) VALUES ('T') RETURNING id", Long.class);
    recordId =
        jdbc.queryForObject(
            """
            INSERT INTO record (archive_id, source_system, source_record_id, title, lang,
                                metadata_lang, status)
            VALUES (?, 'test', 'mcp-1', 'Lagebericht', 'de', 'de', 'complete') RETURNING id
            """,
            Long.class,
            archiveId);
    Long attachmentId =
        jdbc.queryForObject(
            """
            INSERT INTO attachment (record_id, role, path, mime, bytes, created_at)
            VALUES (?, 'page_image', 'x.jpg', 'image/jpeg', 1, now()) RETURNING id
            """,
            Long.class,
            recordId);
    pageId =
        jdbc.queryForObject(
            "INSERT INTO page (record_id, seq, attachment_id) VALUES (?, 1, ?) RETURNING id",
            Long.class,
            recordId,
            attachmentId);
    jdbc.update(
        """
        INSERT INTO page_text (page_id, engine, text_raw, text_en, content_type, created_at)
        VALUES (?, 'ocr_page_mistral', 'Original', 'the cheap one', 'text/plain', now())
        """,
        pageId);
  }

  private void translation(String model, String text) {
    jdbc.update(
        """
        INSERT INTO page_translation (page_id, model, text_en, created_at)
        VALUES (?, ?, ?, now())
        ON CONFLICT (page_id, model) DO UPDATE SET text_en = EXCLUDED.text_en
        """,
        pageId,
        model,
        text);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> firstPage() {
    Map<String, Object> doc = apiController.getDocument(recordId, "https://example.test/api");
    List<Map<String, Object>> pages = (List<Map<String, Object>>) doc.get("pages");
    return pages.get(0);
  }

  @Test
  void servesTheBestTranslationEvenWhenTheCacheIsBehind() {
    translation("mistral-small-latest", "the cheap one");
    translation("mistral-medium-latest", "the careful one");
    // page_text.text_en still holds the cheap one, as it did in production for 53 pages.
    jdbc.update("UPDATE page_text SET text_en = 'the cheap one' WHERE page_id = ?", pageId);

    assertThat(firstPage().get("textEn")).isEqualTo("the careful one");
  }

  @Test
  void offersNoLesserAlternative() {
    translation("mistral-small-latest", "the cheap one");
    translation("mistral-medium-latest", "the careful one");

    Map<String, Object> page = firstPage();

    // A machine reader gets one answer. It cannot weigh a choice of models, so it is not given one.
    assertThat(page).doesNotContainKeys("translations", "translationOptions", "models");
    assertThat(page.toString()).doesNotContain("the cheap one");
  }

  @Test
  void anUntranslatedPageSaysSoRatherThanGuessing() {
    jdbc.update("UPDATE page_text SET text_en = NULL WHERE page_id = ?", pageId);
    assertThat(firstPage().get("textEn")).isEqualTo("");
  }
}
