package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
import place.icomb.archiver.PdfFixtures;
import place.icomb.archiver.service.PdfExportService.Variant;

/**
 * Requests, reuse, claiming and expiry — everything the queue decides, without building a PDF.
 *
 * <p>The fingerprint is what stops an old export being served for changed pages, so most of this is
 * about what changes it and what must not.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class PdfExportQueueTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private PdfExportQueue queue;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Path storageRoot;

  private long record;
  private List<Long> pages;

  @BeforeEach
  void seed() throws Exception {
    jdbc.execute("DELETE FROM pdf_export");
    jdbc.execute("DELETE FROM record");
    long archive = PdfFixtures.archive(jdbc);
    record = PdfFixtures.record(jdbc, archive, "Lagebericht");
    pages =
        List.of(
            PdfFixtures.page(jdbc, storageRoot, record, 1, "One"),
            PdfFixtures.page(jdbc, storageRoot, record, 2, "Two"),
            PdfFixtures.page(jdbc, storageRoot, record, 3, "Three"));
  }

  private PdfExportQueue.Requested request() {
    return queue.request(record, Variant.ORIGINAL, pages);
  }

  private void state(String id, String state) {
    jdbc.update("UPDATE pdf_export SET state = ? WHERE id = ?::uuid", state, id);
  }

  // --- selecting pages ---------------------------------------------------------------------

  @Test
  void noSelectionMeansEveryPageInOrder() {
    assertThat(queue.pageIdsFor(record, null)).containsExactlyElementsOf(pages);
    assertThat(queue.pageIdsFor(record, "  ")).containsExactlyElementsOf(pages);
  }

  @Test
  void aRangeSelectsThosePagesByIdInOrder() {
    assertThat(queue.pageIdsFor(record, "1,3")).containsExactly(pages.get(0), pages.get(2));
    assertThat(queue.pageIdsFor(record, "2-3")).containsExactly(pages.get(1), pages.get(2));
  }

  @Test
  void aRangeNamingNoPageSelectsNothing() {
    assertThat(queue.pageIdsFor(record, "99")).isEmpty();
  }

  @Test
  void aHugeRangeIsClampedToTheRecordsPagesAndIsQuick() {
    List<Long> got =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5), () -> queue.pageIdsFor(record, "1-99999999"));
    assertThat(got).containsExactlyElementsOf(pages);
  }

  @Test
  void aRangeEndingAtIntegerMaxTerminates() {
    List<Long> got =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5), () -> queue.pageIdsFor(record, "2-2147483647"));
    assertThat(got).containsExactly(pages.get(1), pages.get(2));
  }

  @Test
  void aRangeStartingPastTheLastPageSelectsNothing() {
    assertThat(queue.pageIdsFor(record, "5-99999999")).isEmpty();
  }

  @Test
  void aNumberAndAHugeRangeMix() {
    assertThat(queue.pageIdsFor(record, "1,3-999999")).containsExactly(pages.get(0), pages.get(2));
  }

  @Test
  void aMalformedRangeIsRefused() {
    assertThatThrownBy(() -> queue.pageIdsFor(record, "a-b"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theVariantSpellingsTheApiAcceptsAreParsed() {
    assertThat(PdfExportQueue.parseVariant(null)).isEqualTo(Variant.ORIGINAL);
    assertThat(PdfExportQueue.parseVariant("english")).isEqualTo(Variant.ENGLISH);
    assertThat(PdfExportQueue.parseVariant("side-by-side")).isEqualTo(Variant.SIDE_BY_SIDE);
    assertThat(PdfExportQueue.parseVariant("SideBySide")).isEqualTo(Variant.SIDE_BY_SIDE);
    assertThatThrownBy(() -> PdfExportQueue.parseVariant("pdf"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- requesting and reuse ----------------------------------------------------------------

  @Test
  void aNewRequestIsQueued() {
    PdfExportQueue.Requested r = request();

    assertThat(r.created()).isTrue();
    assertThat(r.view().state()).isEqualTo("queued");
    assertThat(r.view().pageCount()).isEqualTo(3);
    assertThat(r.view().variant()).isEqualTo("original");
    assertThat(r.view().bytes()).isNull();
  }

  @Test
  void anIdenticalRequestJoinsOneThatIsQueuedOrBuilding() {
    PdfExportQueue.Requested first = request();
    assertThat(request().view().id()).isEqualTo(first.view().id());

    state(first.view().id(), "building");
    PdfExportQueue.Requested joined = request();

    assertThat(joined.created()).isFalse();
    assertThat(joined.view().id()).isEqualTo(first.view().id());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pdf_export", Long.class)).isEqualTo(1L);
  }

  @Test
  void aFinishedExportIsReusedUntilItExpires() {
    String id = request().view().id();
    queue.claimNext();
    queue.markReady(id, "exports/aa/x.pdf", 10, Duration.ofHours(24));

    PdfExportQueue.Requested again = request();
    assertThat(again.created()).isFalse();
    assertThat(again.view().id()).isEqualTo(id);
    assertThat(again.view().state()).isEqualTo("ready");
    assertThat(again.view().bytes()).isEqualTo(10L);
    assertThat(again.view().expiresAt()).isNotNull();

    jdbc.update("UPDATE pdf_export SET expires_at = now() - interval '1 minute'");
    assertThat(request().created()).isTrue();
  }

  @Test
  void aDifferentVariantOrSelectionIsADifferentExport() {
    String original = request().view().id();

    assertThat(queue.request(record, Variant.ENGLISH, pages).view().id()).isNotEqualTo(original);
    assertThat(queue.request(record, Variant.ORIGINAL, pages.subList(0, 2)).view().id())
        .isNotEqualTo(original);
  }

  @Test
  void aFailedExportIsNotReused() {
    String id = request().view().id();
    queue.markFailed(id, "boom");

    assertThat(request().created()).isTrue();
  }

  // --- what makes an export stale ----------------------------------------------------------

  @Test
  void theFingerprintIsStableWhenNothingChanges() {
    assertThat(queue.fingerprint(record, pages)).isEqualTo(queue.fingerprint(record, pages));
  }

  @Test
  void changingTheEnglishTextChangesTheFingerprint() {
    String before = queue.fingerprint(record, pages);
    jdbc.update("UPDATE page_text SET text_en = 'Changed' WHERE page_id = ?", pages.get(1));
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);
  }

  @Test
  void aNewTranslationChangesTheFingerprint() {
    String before = queue.fingerprint(record, pages);
    jdbc.update(
        "INSERT INTO page_translation (page_id, model, text_en) VALUES (?, 'better', 'x')",
        pages.get(0));
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);
  }

  @Test
  void rewritingATranslationInPlaceChangesTheFingerprint() {
    // PageTranslationRepository.upsert overwrites a model's row with the same id.
    String before = queue.fingerprint(record, pages);
    jdbc.update(
        "UPDATE page_translation SET text_en = 'Rewritten' WHERE page_id = ?", pages.get(1));
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);
  }

  @Test
  void aReOcrdPageChangesTheFingerprint() {
    String before = queue.fingerprint(record, pages);
    jdbc.update("DELETE FROM page_text WHERE page_id = ?", pages.get(2));
    jdbc.update(
        "INSERT INTO page_text (page_id, engine, text_raw, text_en) VALUES (?, 'other', 'r', 'Three')",
        pages.get(2));
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);
  }

  @Test
  void aReplacedScanChangesTheFingerprint() {
    String before = queue.fingerprint(record, pages);
    long other =
        jdbc.queryForObject(
            "INSERT INTO attachment (record_id, role, path) VALUES (?, 'page_image', 'attachments/zz/new.jpg') RETURNING id",
            Long.class,
            record);
    jdbc.update("UPDATE page SET attachment_id = ? WHERE id = ?", other, pages.get(0));
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);
  }

  @Test
  void aMovedOrRenumberedPageChangesTheFingerprint() {
    String before = queue.fingerprint(record, pages);
    jdbc.update("UPDATE page SET seq = 9 WHERE id = ?", pages.get(2));
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);

    jdbc.update("UPDATE page SET seq = 3 WHERE id = ?", pages.get(2));
    long other = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Other");
    jdbc.update("UPDATE page SET record_id = ? WHERE id = ?", other, pages.get(2));
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);
  }

  @Test
  void theCoverSheetsRecordFieldsAreInTheFingerprint() {
    String before = queue.fingerprint(record, pages);
    jdbc.update("UPDATE record SET title_en = 'A title' WHERE id = ?", record);
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);
  }

  @Test
  void theRecordsWholeRowIsInTheFingerprint() {
    String before = queue.fingerprint(record, pages);
    jdbc.update("UPDATE record SET date_range_text = '1938-1945' WHERE id = ?", record);
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);
  }

  @Test
  void theArchivesNameIsInTheFingerprint() {
    String before = queue.fingerprint(record, pages);
    jdbc.update(
        "UPDATE archive SET name = 'Renamed' WHERE id = (SELECT archive_id FROM record WHERE id = ?)",
        record);
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);
  }

  @Test
  void aTranslationOfAPageOutsideTheSelectionChangesTheFingerprint() {
    List<Long> selected = pages.subList(0, 1);
    String before = queue.fingerprint(record, selected);
    jdbc.update(
        "INSERT INTO page_translation (page_id, model, text_en) VALUES (?, 'better', 'x')",
        pages.get(2));
    assertThat(queue.fingerprint(record, selected)).isNotEqualTo(before);
  }

  @Test
  void anEngineOfAPageOutsideTheSelectionChangesTheFingerprint() {
    List<Long> selected = pages.subList(0, 1);
    String before = queue.fingerprint(record, selected);
    jdbc.update("UPDATE page_text SET engine = 'other' WHERE page_id = ?", pages.get(2));
    assertThat(queue.fingerprint(record, selected)).isNotEqualTo(before);
  }

  @Test
  void anotherRecordsDataLeavesTheFingerprintAlone() throws Exception {
    String before = queue.fingerprint(record, pages);
    long archive = PdfFixtures.archive(jdbc);
    long other = PdfFixtures.record(jdbc, archive, "Unrelated");
    long otherPage = PdfFixtures.page(jdbc, storageRoot, other, 1, "Elsewhere");
    jdbc.update("UPDATE record SET title_en = 'Changed' WHERE id = ?", other);
    jdbc.update("UPDATE page_text SET engine = 'x' WHERE page_id = ?", otherPage);
    assertThat(queue.fingerprint(record, pages)).isEqualTo(before);
  }

  @Test
  void theRecordsUpdatedAtAloneDoesNotChangeTheFingerprint() {
    String before = queue.fingerprint(record, pages);
    jdbc.update("UPDATE record SET updated_at = now() + interval '1 hour' WHERE id = ?", record);
    assertThat(queue.fingerprint(record, pages)).isEqualTo(before);
  }

  // --- claiming and finishing --------------------------------------------------------------

  @Test
  void claimingTakesTheOldestQueuedExportAndMarksItBuilding() {
    String first = request().view().id();
    queue.request(record, Variant.ENGLISH, pages);

    PdfExportQueue.Claimed claimed = queue.claimNext().orElseThrow();

    assertThat(claimed.id()).isEqualTo(first);
    assertThat(claimed.recordId()).isEqualTo(record);
    assertThat(claimed.variant()).isEqualTo(Variant.ORIGINAL);
    assertThat(claimed.pageIds()).containsExactlyElementsOf(pages);
    assertThat(queue.find(first).orElseThrow().state()).isEqualTo("building");
    assertThat(queue.claimNext().orElseThrow().id()).isNotEqualTo(first);
    assertThat(queue.claimNext()).isEmpty();
  }

  @Test
  void markReadyAcceptsABuildingExport() {
    String id = request().view().id();
    queue.claimNext();

    assertThat(queue.markReady(id, "exports/aa/x.pdf", 10, Duration.ofHours(24))).isTrue();

    assertThat(queue.find(id).orElseThrow().state()).isEqualTo("ready");
  }

  @Test
  void markReadyRefusesAnExportThatIsNotBuilding() {
    for (String state : List.of("queued", "failed", "expired")) {
      jdbc.execute("DELETE FROM pdf_export");
      String id = request().view().id();
      state(id, state);

      assertThat(queue.markReady(id, "exports/aa/x.pdf", 10, Duration.ofHours(24)))
          .as(state)
          .isFalse();

      PdfExportQueue.Row row = queue.row(id).orElseThrow();
      assertThat(row.state()).as(state).isEqualTo(state);
      assertThat(row.path()).as(state).isNull();
    }
  }

  @Test
  void markReadyOnAnExportWhoseRecordWasDeletedIsRefused() throws Exception {
    String id = request().view().id();
    queue.claimNext();
    jdbc.update("DELETE FROM record WHERE id = ?", record);

    assertThat(queue.markReady(id, "exports/aa/x.pdf", 10, Duration.ofHours(24))).isFalse();
  }

  @Test
  void readyRecordsTheFileSizeAndExpiry() {
    String id = request().view().id();
    queue.claimNext();
    queue.markReady(id, "exports/aa/x.pdf", 1234, Duration.ofHours(24));

    PdfExportQueue.Row row = queue.row(id).orElseThrow();
    assertThat(row.state()).isEqualTo("ready");
    assertThat(row.path()).isEqualTo("exports/aa/x.pdf");
    assertThat(queue.find(id).orElseThrow().bytes()).isEqualTo(1234L);
    long hours =
        jdbc.queryForObject(
            "SELECT round(extract(epoch FROM expires_at - now()) / 3600) FROM pdf_export",
            Long.class);
    assertThat(hours).isEqualTo(24L);
  }

  @Test
  void failedRecordsTheErrorAndAnUnknownOrMalformedIdIsSimplyAbsent() {
    String id = request().view().id();
    queue.markFailed(id, "no scan");

    assertThat(queue.find(id).orElseThrow().error()).isEqualTo("no scan");
    assertThat(queue.find("not-a-uuid")).isEmpty();
    assertThat(queue.row("00000000-0000-0000-0000-000000000000")).isEmpty();
  }

  @Test
  void seqsAreResolvedFromPageIdsInOrder() {
    assertThat(queue.seqsOf(record, List.of(pages.get(2), pages.get(0)))).containsExactly(1, 3);
  }

  // --- expiry and recovery -----------------------------------------------------------------

  @Test
  void dueExportsAreExpiredAndTheirFilesReturned() {
    String id = request().view().id();
    queue.claimNext();
    queue.markReady(id, "exports/aa/x.pdf", 10, Duration.ofHours(24));
    jdbc.update("UPDATE pdf_export SET expires_at = now() - interval '1 second'");

    assertThat(queue.expireDue()).containsExactly("exports/aa/x.pdf");
    assertThat(queue.find(id).orElseThrow().state()).isEqualTo("expired");
    assertThat(queue.expireDue()).isEmpty();
  }

  @Test
  void anExportStillInDateIsNotExpired() {
    String id = request().view().id();
    queue.claimNext();
    queue.markReady(id, "exports/aa/x.pdf", 10, Duration.ofHours(24));
    assertThat(queue.expireDue()).isEmpty();
  }

  @Test
  void aBuildThatHasRunTooLongIsFailedAsInterrupted() {
    String id = request().view().id();
    queue.claimNext();
    jdbc.update("UPDATE pdf_export SET started_at = now() - interval '3 hours'");

    assertThat(queue.failInterrupted(Duration.ofHours(2))).isEqualTo(1);

    assertThat(queue.find(id).orElseThrow().state()).isEqualTo("failed");
    assertThat(queue.find(id).orElseThrow().error()).isEqualTo("interrupted");
  }

  @Test
  void aRecentBuildIsLeftAlone() {
    request();
    queue.claimNext();
    assertThat(queue.failInterrupted(Duration.ofHours(2))).isZero();
  }

  @Test
  void aRestartFailsEveryBuildingExportAtOnce() {
    String id = request().view().id();
    queue.claimNext();

    assertThat(queue.failAllBuilding("interrupted by a restart")).isEqualTo(1);

    assertThat(queue.find(id).orElseThrow().error()).isEqualTo("interrupted by a restart");
  }

  @Test
  void oldExpiredAndFailedRowsArePurgedButRecentOnesAreKept() {
    String expired = request().view().id();
    queue.claimNext();
    queue.markReady(expired, "exports/aa/x.pdf", 1, Duration.ofHours(24));
    jdbc.update("UPDATE pdf_export SET expires_at = now() - interval '1 second'");
    queue.expireDue();
    String failed = queue.request(record, Variant.ENGLISH, pages).view().id();
    queue.markFailed(failed, "x");
    jdbc.update(
        "UPDATE pdf_export SET created_at = now() - interval '8 days' WHERE id = ?::uuid", expired);

    assertThat(queue.purgeOld()).isEqualTo(1);

    assertThat(queue.find(expired)).isEmpty();
    assertThat(queue.find(failed)).isPresent();
  }

  @Test
  void aRecordsExportFilesAreListedForDeletion() {
    String id = request().view().id();
    queue.claimNext();
    queue.markReady(id, "exports/aa/x.pdf", 1, Duration.ofHours(24));
    queue.request(record, Variant.ENGLISH, pages);

    assertThat(queue.pathsForRecord(record)).containsExactly("exports/aa/x.pdf");
  }

  @Test
  void aPathOutsideExportsIsNeverListedForDeletion() {
    String id = request().view().id();
    queue.claimNext();
    queue.markReady(id, "attachments/zz/keep.jpg", 1, Duration.ofHours(24));

    assertThat(queue.pathsForRecord(record)).isEmpty();
  }

  // --- concurrency ---------------------------------------------------------------------------

  @Test
  void manyCallersAskingForTheSameExportAtOnceGetOneRow() throws Exception {
    int callers = 12;
    var pool = java.util.concurrent.Executors.newFixedThreadPool(callers);
    var start = new java.util.concurrent.CountDownLatch(1);
    List<java.util.concurrent.Future<PdfExportQueue.Requested>> results =
        new java.util.ArrayList<>();
    for (int i = 0; i < callers; i++) {
      results.add(
          pool.submit(
              () -> {
                start.await();
                return request();
              }));
    }
    start.countDown();
    java.util.Set<String> ids = new java.util.HashSet<>();
    int created = 0;
    for (var f : results) {
      PdfExportQueue.Requested r = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
      ids.add(r.view().id());
      if (r.created()) {
        created++;
      }
    }
    pool.shutdownNow();

    assertThat(ids).hasSize(1);
    assertThat(created).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pdf_export", Long.class)).isEqualTo(1L);
  }

  @Test
  void manyWorkersClaimEachQueuedExportExactlyOnce() throws Exception {
    // nine distinct fingerprints: three variants over three single pages
    int queued = 0;
    for (Variant v : Variant.values()) {
      for (long page : pages) {
        queue.request(record, v, List.of(page));
        queued++;
      }
    }
    int workers = 16;
    var pool = java.util.concurrent.Executors.newFixedThreadPool(workers);
    var start = new java.util.concurrent.CountDownLatch(1);
    List<java.util.concurrent.Future<List<String>>> results = new java.util.ArrayList<>();
    for (int i = 0; i < workers; i++) {
      results.add(
          pool.submit(
              () -> {
                start.await();
                List<String> mine = new java.util.ArrayList<>();
                for (var c = queue.claimNext(); c.isPresent(); c = queue.claimNext()) {
                  mine.add(c.get().id());
                }
                return mine;
              }));
    }
    start.countDown();
    List<String> all = new java.util.ArrayList<>();
    for (var f : results) {
      all.addAll(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
    }
    pool.shutdownNow();

    assertThat(all).hasSize(queued).doesNotHaveDuplicates();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM pdf_export WHERE state = 'building'", Long.class))
        .isEqualTo((long) queued);
  }
}
