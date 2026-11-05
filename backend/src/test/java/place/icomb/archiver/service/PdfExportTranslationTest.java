package place.icomb.archiver.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import place.icomb.archiver.TranslatorStub;
import place.icomb.archiver.service.PdfExportService.Variant;

/** Exports in a language the archive does not hold yet, against a stubbed model. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class PdfExportTranslationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  static WireMockServer model =
      new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

  @BeforeAll
  static void start() {
    model.start();
  }

  @AfterAll
  static void stop() {
    model.stop();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add(TranslatorStub.KEY_ENV, () -> "stub-key");
  }

  @Autowired private PdfExportWorker worker;
  @Autowired private ManualCorrectionService corrections;
  @Autowired private PdfExportQueue queue;
  @Autowired private StorageService storage;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Path storageRoot;

  private long record;
  private List<Long> pages;

  @BeforeEach
  void seed() throws Exception {
    ReflectionTestUtils.setField(worker, "minFreeBytes", 0L);
    model.resetAll();
    TranslatorStub.register(jdbc, model);
    TranslatorStub.reply(model, "Překlad:\n\nČeský text");
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

  private PdfExportQueue.View build(Variant variant, List<Long> selection, String lang) {
    String id = queue.request(record, variant, selection, lang).view().id();
    worker.runOnce();
    return queue.find(id).orElseThrow();
  }

  private String textOf(PdfExportQueue.View view) throws Exception {
    Path file = storage.exportFile(queue.row(view.id()).orElseThrow().path());
    try (PDDocument doc = Loader.loadPDF(file.toFile())) {
      return new PDFTextStripper().getText(doc);
    }
  }

  private int czechRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM page_translation t JOIN page p ON p.id = t.page_id"
            + " WHERE p.record_id = ? AND t.lang = 'cs'",
        Integer.class,
        record);
  }

  @Test
  void aCzechExportAsksTheModelOncePerPageAndTheNextExportNotAtAll() throws Exception {
    PdfExportQueue.View first = build(Variant.TRANSLATION, pages, "cs");
    assertThat(first.state()).as("error: %s", first.error()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isEqualTo(3);
    assertThat(czechRows()).isEqualTo(3);
    assertThat(textOf(first)).contains("Český text").doesNotContain("Překlad:");

    PdfExportQueue.View other = build(Variant.SIDE_BY_SIDE, pages.subList(0, 2), "cs");
    assertThat(other.state()).as("error: %s", other.error()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isEqualTo(3);

    PdfExportQueue.Requested again = queue.request(record, Variant.TRANSLATION, pages, "cs");
    assertThat(again.created()).isFalse();
    assertThat(again.view().id()).isEqualTo(first.id());
  }

  @Test
  void aGermanExportOfAGermanRecordAsksNothingAndShowsTheTranscription() throws Exception {
    PdfExportQueue.View view = build(Variant.TRANSLATION, pages, "de");
    assertThat(view.state()).as("error: %s", view.error()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isZero();
    assertThat(textOf(view))
        .contains("Text 1")
        .contains("German transcription")
        .doesNotContain("One");
  }

  @Test
  void aBlankPageIsNeverSentToTheModel() {
    jdbc.update("UPDATE page_text SET text_raw = '' WHERE page_id = ?", pages.get(1));
    PdfExportQueue.View view = build(Variant.TRANSLATION, pages, "cs");
    assertThat(view.state()).as("error: %s", view.error()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isEqualTo(2);
  }

  @Test
  void aFailedCallFailsTheExportReadablyAndKeepsWhatWasAlreadyPaidFor() {
    model.resetAll();
    model.stubFor(
        post(urlEqualTo(TranslatorStub.PATH))
            .inScenario("quota")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson(TranslatorStub.body("Český text")))
            .willSetStateTo("exhausted"));
    model.stubFor(
        post(urlEqualTo(TranslatorStub.PATH))
            .inScenario("quota")
            .whenScenarioStateIs("exhausted")
            .willReturn(aResponse().withStatus(400).withBody("{\"message\":\"quota exceeded\"}")));

    PdfExportQueue.View failed = build(Variant.TRANSLATION, pages, "cs");
    assertThat(failed.state()).isEqualTo("failed");
    assertThat(failed.error())
        .contains("page 2")
        .contains("Czech")
        .contains("HTTP 400")
        .contains("quota exceeded");
    assertThat(czechRows()).isEqualTo(1);

    model.resetAll();
    TranslatorStub.reply(model, "Český text");
    PdfExportQueue.View retried = build(Variant.TRANSLATION, pages, "cs");
    assertThat(retried.state()).as("error: %s", retried.error()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isEqualTo(2);
    assertThat(
            model.findAll(
                postRequestedFor(urlEqualTo(TranslatorStub.PATH))
                    .withRequestBody(containing("Text 1"))))
        .isEmpty();
  }

  @Test
  void withNoTranslatorConfiguredTheExportFailsSayingSo() {
    TranslatorStub.unregister(jdbc);
    PdfExportQueue.View view = build(Variant.SIDE_BY_SIDE, pages, "cs");
    assertThat(view.state()).isEqualTo("failed");
    assertThat(view.error()).contains("No translation model is configured");
    assertThat(TranslatorStub.calls(model)).isZero();
  }

  @Test
  void anExportQueuedBehindOneThatTranslatesStillBuilds() {
    String a = queue.request(record, Variant.TRANSLATION, pages, "cs").view().id();
    String b = queue.request(record, Variant.SIDE_BY_SIDE, pages, "cs").view().id();

    worker.runOnce();
    worker.runOnce();

    PdfExportQueue.View second = queue.find(b).orElseThrow();
    assertThat(queue.find(a).orElseThrow().state()).isEqualTo("ready");
    assertThat(second.state()).as("error: %s", second.error()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isEqualTo(3);
  }

  @Test
  void twoExportsBuildingAtOnceTranslateEachPageOnce() throws Exception {
    model.resetAll();
    model.stubFor(
        post(urlEqualTo(TranslatorStub.PATH))
            .willReturn(okJson(TranslatorStub.body("Český text")).withFixedDelay(300)));
    String a = queue.request(record, Variant.TRANSLATION, pages, "cs").view().id();
    String b = queue.request(record, Variant.SIDE_BY_SIDE, pages, "cs").view().id();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Boolean>> runs = List.of(worker::runOnce, worker::runOnce);
      for (Future<Boolean> run : pool.invokeAll(runs)) {
        run.get();
      }
    } finally {
      pool.shutdown();
    }

    assertThat(queue.find(a).orElseThrow().state()).isEqualTo("ready");
    assertThat(queue.find(b).orElseThrow().state()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isEqualTo(3);
  }

  @Test
  void aCorrectedTranscriptionIsTranslatedAgainAndTheNewTextRendered() throws Exception {
    assertThat(build(Variant.TRANSLATION, pages, "cs").state()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isEqualTo(3);

    corrections.correctPage(pages.get(0), "text_raw", "Text 1", "Text eins", "typo", false);
    model.resetAll();
    TranslatorStub.reply(model, "Nový překlad");

    PdfExportQueue.View again = build(Variant.TRANSLATION, pages, "cs");
    assertThat(again.state()).as("error: %s", again.error()).isEqualTo("ready");
    assertThat(TranslatorStub.calls(model)).isEqualTo(1);
    assertThat(textOf(again)).contains("Nový překlad");
  }
}
