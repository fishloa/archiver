package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Which of a page's translations wins.
 *
 * <p>This decision used to be made in five places, and they disagreed: 53 pages displayed
 * mistral-small while the mistral-medium upgrade that had been paid for sat unused in
 * page_translation. The batch stage inferred the current model by comparing text bytes, which broke
 * the moment the cached text was edited.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class TranslationServiceTest {

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

  @Autowired private TranslationService translationService;
  @Autowired private JdbcTemplate jdbc;

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
    Long recordId =
        jdbc.queryForObject(
            """
            INSERT INTO record (archive_id, source_system, source_record_id, title, lang,
                                metadata_lang, status)
            VALUES (?, 'test', 'ts-1', 'T', 'de', 'de', 'complete') RETURNING id
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
        INSERT INTO page_text (page_id, engine, text_raw, content_type, created_at)
        VALUES (?, 'ocr_page_mistral', 'Original', 'text/plain', now())
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

  private String cached() {
    return jdbc.queryForObject(
        "SELECT text_en FROM page_text WHERE page_id = ?", String.class, pageId);
  }

  @Test
  void theBetterModelWins() {
    translation("mistral-small-latest", "cheap");
    translation("mistral-medium-latest", "careful");
    assertThat(translationService.bestEnglish(pageId)).isEqualTo("careful");
    assertThat(translationService.bestModel(pageId)).isEqualTo("mistral-medium-latest");
  }

  @Test
  void orderOfArrivalDoesNotMatter() {
    // The upgrade landing after the bulk run is the normal case; the reverse must behave too.
    translation("mistral-medium-latest", "careful");
    translation("mistral-small-latest", "cheap");
    assertThat(translationService.bestEnglish(pageId)).isEqualTo("careful");
  }

  @Test
  void anUnknownModelRanksLastButIsStillUsable() {
    translation("some-new-model", "experimental");
    assertThat(translationService.bestEnglish(pageId)).isEqualTo("experimental");
    translation("mistral-small-latest", "cheap");
    assertThat(translationService.bestEnglish(pageId)).isEqualTo("cheap");
  }

  @Test
  void blankTranslationsAreNotChosen() {
    translation("mistral-medium-latest", "");
    translation("mistral-small-latest", "cheap");
    assertThat(translationService.bestEnglish(pageId)).isEqualTo("cheap");
  }

  @Test
  void refreshRepointsTheCacheAtTheBestTranslation() {
    // Exactly the production fault: the cache holds the cheap translation while the upgrade
    // exists. Nothing about the cached bytes should be needed to work that out.
    translation("mistral-small-latest", "cheap");
    translation("mistral-medium-latest", "careful");
    jdbc.update("UPDATE page_text SET text_en = 'cheap' WHERE page_id = ?", pageId);

    translationService.refreshShown(pageId);
    assertThat(cached()).isEqualTo("careful");
  }

  @Test
  void refreshIsSelfHealingEvenWhenTheCacheMatchesNothing() {
    translation("mistral-small-latest", "cheap");
    // An edited cache matched no translation row, which is what broke the old text-equality
    // inference.
    jdbc.update("UPDATE page_text SET text_en = 'edited by hand' WHERE page_id = ?", pageId);
    translationService.refreshShown(pageId);
    assertThat(cached()).isEqualTo("cheap");
  }

  @Test
  void refreshLeavesAPageWithNoTranslationsAlone() {
    jdbc.update("UPDATE page_text SET text_en = 'kept' WHERE page_id = ?", pageId);
    translationService.refreshShown(pageId);
    assertThat(cached()).isEqualTo("kept");
  }

  @Test
  void recordingATranslationUpdatesTheCache() {
    translationService.record(pageId, "mistral-small-latest", "cheap");
    assertThat(cached()).isEqualTo("cheap");
    translationService.record(pageId, "mistral-medium-latest", "careful");
    assertThat(cached()).isEqualTo("careful");
    // And the cheaper one is kept, so an upgrade can be recognised rather than paid for twice.
    assertThat(translationService.bestEnglish(pageId)).isEqualTo("careful");
    Integer kept =
        jdbc.queryForObject(
            "SELECT count(*) FROM page_translation WHERE page_id = ?", Integer.class, pageId);
    assertThat(kept).isEqualTo(2);
  }

  @Test
  void aCheaperModelArrivingLateDoesNotDemoteTheUpgrade() {
    translationService.record(pageId, "mistral-medium-latest", "careful");
    translationService.record(pageId, "mistral-small-latest", "cheap");
    assertThat(cached()).isEqualTo("careful");
  }

  @Test
  void staleCachesAreRepairedInBulk() {
    translation("mistral-small-latest", "cheap");
    translation("mistral-medium-latest", "careful");
    jdbc.update("UPDATE page_text SET text_en = 'cheap' WHERE page_id = ?", pageId);

    assertThat(translationService.refreshAllStale()).isEqualTo(1);
    assertThat(cached()).isEqualTo("careful");
    // Idempotent: nothing left to fix.
    assertThat(translationService.refreshAllStale()).isZero();
  }

  @Test
  void theModelBreakdownNamesWhatIsActuallyShown() {
    translation("mistral-small-latest", "cheap");
    translation("mistral-medium-latest", "careful");
    var breakdown = translationService.modelBreakdown(recordIdOf(pageId));
    assertThat(breakdown).hasSize(1);
    assertThat(breakdown.get(0)[0]).isEqualTo("mistral-medium-latest");
    assertThat(breakdown.get(0)[1]).isEqualTo("1");
  }

  private Long recordIdOf(Long page) {
    return jdbc.queryForObject("SELECT record_id FROM page WHERE id = ?", Long.class, page);
  }
}
