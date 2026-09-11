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
  @Autowired private place.icomb.archiver.ai.AiRegistry registry;

  private Long pageId;
  private Long recordId;

  @BeforeEach
  void seed() {
    // Restore the seeded preference order: a test that reorders it must not leak that into the
    // next one. Moved aside first because (capability, rank) is unique, so a direct swap collides.
    jdbc.update("UPDATE ai_implementation SET rank = rank + 100 WHERE capability = 'TRANSLATION'");
    jdbc.update("UPDATE ai_implementation SET rank = 1 WHERE id = 'mistral:mistral-medium-latest'");
    jdbc.update("UPDATE ai_implementation SET rank = 2 WHERE id = 'mistral:mistral-small-latest'");
    jdbc.update(
        "UPDATE ai_implementation SET rank = 3 WHERE id = 'deepinfra:google/gemma-4-31B-it'");
    jdbc.update("UPDATE ai_implementation SET rank = 99 WHERE id = 'legacy'");

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

  // -------------------------------------------------------------------------
  // Record metadata
  // -------------------------------------------------------------------------

  private String cachedTitle() {
    return jdbc.queryForObject("SELECT title_en FROM record WHERE id = ?", String.class, recordId);
  }

  @Test
  void recordMetadataFollowsTheSameRanking() {
    translationService.recordMetadata(recordId, "google/gemma-4-31B-it", "An obscene letter", "d1");
    assertThat(cachedTitle()).isEqualTo("An obscene letter");

    translationService.recordMetadata(
        recordId, "mistral-medium-latest", "A letter of condolence", "d2");
    assertThat(cachedTitle()).isEqualTo("A letter of condolence");
    assertThat(translationService.bestMetadataModel(recordId)).isEqualTo("mistral-medium-latest");
  }

  @Test
  void aWorseMetadataModelArrivingLaterDoesNotWin() {
    // Exactly what the HTTP worker did: it wrote record.title_en unconditionally, so a weaker
    // model could overwrite a better translation with no trace that the better one existed.
    translationService.recordMetadata(
        recordId, "mistral-medium-latest", "A letter of condolence", "d");
    translationService.recordMetadata(recordId, "google/gemma-4-31B-it", "An obscene letter", "d");
    assertThat(cachedTitle()).isEqualTo("A letter of condolence");
  }

  @Test
  void bothMetadataTranslationsAreKept() {
    translationService.recordMetadata(recordId, "google/gemma-4-31B-it", "old", "d");
    translationService.recordMetadata(recordId, "mistral-medium-latest", "new", "d");
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM record_translation WHERE record_id = ?", Integer.class, recordId);
    assertThat(rows).isEqualTo(2);
  }

  @Test
  void staleRecordMetadataIsRepairedInBulk() {
    translationService.recordMetadata(recordId, "google/gemma-4-31B-it", "old", "d");
    translationService.recordMetadata(recordId, "mistral-medium-latest", "new", "d");
    jdbc.update("UPDATE record SET title_en = 'old' WHERE id = ?", recordId);

    assertThat(translationService.refreshAllStaleMetadata()).isEqualTo(1);
    assertThat(cachedTitle()).isEqualTo("new");
    assertThat(translationService.refreshAllStaleMetadata()).isZero();
  }

  @Test
  void aRecordWithNoMetadataTranslationIsLeftAlone() {
    jdbc.update("UPDATE record SET title_en = 'untouched' WHERE id = ?", recordId);
    translationService.refreshShownMetadata(recordId);
    assertThat(cachedTitle()).isEqualTo("untouched");
  }

  // -------------------------------------------------------------------------
  // Preference order, from ai_implementation
  // -------------------------------------------------------------------------

  @Test
  void preferenceComesFromTheDatabase() {
    // "Prefer model XX over YY" has to be a row update, not a release. Swapping the ranks must
    // change which stored translation is served, with no code change and no re-translation.
    translation("mistral-small-latest", "cheap");
    translation("mistral-medium-latest", "careful");
    assertThat(translationService.bestEnglish(pageId)).isEqualTo("careful");

    jdbc.update("UPDATE ai_implementation SET rank = 10 WHERE model = 'mistral-medium-latest'");
    jdbc.update("UPDATE ai_implementation SET rank = 1 WHERE model = 'mistral-small-latest'");

    assertThat(translationService.bestEnglish(pageId)).isEqualTo("cheap");
    assertThat(translationService.bestModel(pageId)).isEqualTo("mistral-small-latest");
  }

  @Test
  void aRetiredModelStillRanksAgainstItsReplacement() {
    // gemma is disabled but its output is still in the archive, so it has to keep losing to the
    // models that replaced it rather than becoming unordered.
    translation("google/gemma-4-31B-it", "an obscene letter");
    translation("mistral-small-latest", "a letter of condolence");
    assertThat(translationService.bestEnglish(pageId)).isEqualTo("a letter of condolence");
  }

  @Test
  void anUnrankedModelLosesToEveryRankedOne() {
    translation("some-model-nobody-registered", "unknown provenance");
    translation("mistral-small-latest", "cheap");
    assertThat(translationService.bestEnglish(pageId)).isEqualTo("cheap");
  }

  @Test
  void theSameModelOnTwoProvidersIsOneQuality() {
    // A local endpoint and a hosted one can serve the same weights with different URLs and
    // different keys. They are two implementations to route work to, but not two qualities of
    // answer, and stored output records only the model — so the ranking must not list it twice
    // or a translation would be compared against itself.
    jdbc.update(
        """
        INSERT INTO ai_implementation
            (id, capability, provider, model, base_url, endpoint_path, credential_env,
             max_batch_size, rank, enabled, settings)
        VALUES ('local:mistral-small-latest', 'TRANSLATION', 'local', 'mistral-small-latest',
                'http://localhost:11434/v1', '/chat/completions', NULL, 1, 5, true, '{}')
        """);

    var ranked = registry.rankedModels(place.icomb.archiver.ai.AiCapability.TRANSLATION);
    assertThat(ranked).doesNotHaveDuplicates();
    // It keeps the better of its two ranks: 2 from the hosted row, not 5 from the local one.
    assertThat(ranked.indexOf("mistral-small-latest"))
        .isLessThan(ranked.indexOf("google/gemma-4-31B-it"));

    translation("mistral-small-latest", "cheap");
    translation("mistral-medium-latest", "careful");
    assertThat(translationService.bestEnglish(pageId)).isEqualTo("careful");
  }

  @Test
  void aLocalImplementationNeedsNoCredential() {
    // A machine in the room has no API key. That must not read as "unconfigured".
    jdbc.update(
        """
        INSERT INTO ai_implementation
            (id, capability, provider, model, base_url, endpoint_path, credential_env,
             max_batch_size, rank, enabled, settings)
        VALUES ('local:qwen3', 'EMBEDDING', 'local', 'Qwen/Qwen3-Embedding-8B',
                'http://localhost:11434/v1', '/embeddings', NULL, 1, 0, true,
                '{"dimensions": 1024}')
        """);

    var best = registry.best(place.icomb.archiver.ai.AiCapability.EMBEDDING);
    assertThat(best).isPresent();
    assertThat(best.get().id()).isEqualTo("local:qwen3");
    assertThat(best.get().maxBatchSize()).isEqualTo(1);
  }

  @Test
  void anImplementationWhoseKeyIsUnsetIsSkippedNotFailed() {
    jdbc.update(
        """
        INSERT INTO ai_implementation
            (id, capability, provider, model, base_url, credential_env, max_batch_size, rank,
             enabled, settings)
        VALUES ('nowhere:model-x', 'OCR', 'nowhere', 'model-x', 'https://example.invalid',
                'A_KEY_THAT_IS_NOT_SET', 1, 0, true, '{}')
        """);

    var best = registry.best(place.icomb.archiver.ai.AiCapability.OCR);
    assertThat(best).isPresent();
    assertThat(best.get().provider()).isEqualTo("mistral");
  }
}
