package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.PdfFixtures;
import place.icomb.archiver.service.PdfExportService.Variant;

/**
 * Building an export, and everything around it that can go wrong: a scan that has gone, pages that
 * changed, a restart mid-build, a file past its expiry.
 *
 * <p>The worker's scheduled tasks are off in the test profile, so each test drives it directly.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class PdfExportWorkerTest {

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

  @Autowired private PdfExportWorker worker;
  @Autowired private PdfExportQueue queue;
  @Autowired private StorageService storage;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Path storageRoot;

  private long record;
  private List<Long> pages;

  @BeforeEach
  void seed() throws Exception {
    ReflectionTestUtils.setField(worker, "minFreeBytes", 0L);
    jdbc.execute("DELETE FROM pdf_export");
    jdbc.execute("DELETE FROM record");
    storage.clearExportTemp();
    record = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Lagebericht");
    pages =
        List.of(
            PdfFixtures.page(jdbc, storageRoot, record, 1, "One"),
            PdfFixtures.page(jdbc, storageRoot, record, 2, "Two"),
            PdfFixtures.page(jdbc, storageRoot, record, 3, "Three"));
  }

  private String request(Variant variant) {
    return queue.request(record, variant, pages).view().id();
  }

  private boolean tempIsEmpty() throws Exception {
    try (var entries = Files.list(storage.exportTempDir())) {
      return entries.findAny().isEmpty();
    }
  }

  // --- building ----------------------------------------------------------------------------

  @Test
  void anExportIsBuiltAndPlacedAndMarkedReady() throws Exception {
    String id = request(Variant.ORIGINAL);

    assertThat(worker.runOnce()).isTrue();

    PdfExportQueue.View view = queue.find(id).orElseThrow();
    assertThat(view.state()).isEqualTo("ready");
    PdfExportQueue.Row row = queue.row(id).orElseThrow();
    Path file = storage.exportFile(row.path());
    assertThat(row.path()).matches("exports/[0-9a-f]{2}/[0-9a-f-]{36}\\.pdf");
    assertThat(file).exists();
    assertThat(view.bytes()).isEqualTo(Files.size(file));
    try (PDDocument doc = Loader.loadPDF(file.toFile())) {
      assertThat(doc.getNumberOfPages()).isEqualTo(3);
    }
    long hours =
        jdbc.queryForObject(
            "SELECT round(extract(epoch FROM expires_at - now()) / 3600) FROM pdf_export",
            Long.class);
    assertThat(hours).isEqualTo(24L);
    assertThat(tempIsEmpty()).as("nothing left in the temp area").isTrue();
  }

  @Test
  void theOtherVariantsBuildToo() throws Exception {
    for (Variant variant : List.of(Variant.ENGLISH, Variant.SIDE_BY_SIDE)) {
      String id = request(variant);
      worker.runOnce();
      assertThat(queue.find(id).orElseThrow().state()).as(variant.toString()).isEqualTo("ready");
      try (PDDocument doc =
          Loader.loadPDF(storage.exportFile(queue.row(id).orElseThrow().path()).toFile())) {
        assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(3);
      }
    }
  }

  @Test
  void runOnceReportsWhenThereWasNothingToDo() {
    assertThat(worker.runOnce()).isFalse();
  }

  @Test
  void drainBuildsEverythingQueued() {
    String a = request(Variant.ORIGINAL);
    String b = request(Variant.ENGLISH);

    worker.drain();

    assertThat(queue.find(a).orElseThrow().state()).isEqualTo("ready");
    assertThat(queue.find(b).orElseThrow().state()).isEqualTo("ready");
  }

  // --- failing cleanly ---------------------------------------------------------------------

  @Test
  void aMissingScanFailsTheExportAndLeavesNothingBehind() throws Exception {
    String id = request(Variant.ORIGINAL);
    String scan =
        jdbc.queryForObject(
            "SELECT a.path FROM page p JOIN attachment a ON a.id = p.attachment_id WHERE p.id = ?",
            String.class,
            pages.get(1));
    Files.delete(storageRoot.resolve(scan));

    worker.runOnce();

    PdfExportQueue.View view = queue.find(id).orElseThrow();
    assertThat(view.state()).isEqualTo("failed");
    assertThat(view.error()).isNotBlank();
    assertThat(tempIsEmpty()).as("scratch and partial files are removed").isTrue();
  }

  @Test
  void pagesChangedAfterTheRequestFailTheExportRatherThanMixOldAndNew() {
    String id = request(Variant.ENGLISH);
    jdbc.update("UPDATE page_text SET text_en = 'Edited' WHERE page_id = ?", pages.get(0));

    worker.runOnce();

    PdfExportQueue.View view = queue.find(id).orElseThrow();
    assertThat(view.state()).isEqualTo("failed");
    assertThat(view.error()).contains("changed");
  }

  @Test
  void aBuildRefusesToStartWithoutFreeSpace() {
    ReflectionTestUtils.setField(worker, "minFreeBytes", Long.MAX_VALUE);
    String id = request(Variant.ORIGINAL);

    worker.runOnce();

    PdfExportQueue.View view = queue.find(id).orElseThrow();
    assertThat(view.state()).isEqualTo("failed");
    assertThat(view.error()).containsIgnoringCase("free space");
  }

  @Test
  void aLongErrorIsCutToFiveHundredCharactersAndAnEmptyOneStillSaysSomething() {
    assertThat(PdfExportWorker.abbreviate("y".repeat(2000))).hasSize(500);
    assertThat(PdfExportWorker.abbreviate("short")).isEqualTo("short");
    assertThat(PdfExportWorker.abbreviate(null)).isNotBlank();
    assertThat(PdfExportWorker.abbreviate("  ")).isNotBlank();
  }

  // --- reaping -----------------------------------------------------------------------------

  @Test
  void anExpiredExportsFileIsDeletedAndItsRowMarked() throws Exception {
    String id = request(Variant.ORIGINAL);
    worker.runOnce();
    Path file = storage.exportFile(queue.row(id).orElseThrow().path());
    assertThat(file).exists();
    jdbc.update("UPDATE pdf_export SET expires_at = now() - interval '1 second'");

    worker.reap();

    assertThat(file).doesNotExist();
    assertThat(queue.find(id).orElseThrow().state()).isEqualTo("expired");
  }

  @Test
  void anExportStillInDateIsLeftAlone() throws Exception {
    String id = request(Variant.ORIGINAL);
    worker.runOnce();
    Path file = storage.exportFile(queue.row(id).orElseThrow().path());

    worker.reap();

    assertThat(file).exists();
    assertThat(queue.find(id).orElseThrow().state()).isEqualTo("ready");
  }

  @Test
  void aBuildThatHasHungForHoursIsFailedByTheReaper() {
    String id = request(Variant.ORIGINAL);
    queue.claimNext();
    jdbc.update("UPDATE pdf_export SET started_at = now() - interval '3 hours'");

    worker.reap();

    assertThat(queue.find(id).orElseThrow().error()).isEqualTo("interrupted");
  }

  // --- restarting --------------------------------------------------------------------------

  @Test
  void aRestartFailsWhatWasBuildingAndEmptiesTheTempArea() throws Exception {
    String id = request(Variant.ORIGINAL);
    queue.claimNext();
    Files.writeString(storage.exportTempDir().resolve("half.pdf.partial"), "half");
    Files.createDirectories(storage.exportTempDir().resolve("PDFBox-scratch"));

    worker.recoverAfterRestart();

    PdfExportQueue.View view = queue.find(id).orElseThrow();
    assertThat(view.state()).isEqualTo("failed");
    assertThat(view.error()).isEqualTo("interrupted by a restart");
    assertThat(tempIsEmpty()).isTrue();
  }

  @Test
  void aRestartLeavesFinishedAndQueuedExportsAlone() {
    String ready = request(Variant.ORIGINAL);
    worker.runOnce();
    String queued = request(Variant.ENGLISH);

    worker.recoverAfterRestart();

    assertThat(queue.find(ready).orElseThrow().state()).isEqualTo("ready");
    assertThat(queue.find(queued).orElseThrow().state()).isEqualTo("queued");
  }
}
