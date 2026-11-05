package place.icomb.archiver.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.nio.file.Path;
import java.util.List;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.PdfFixtures;
import place.icomb.archiver.TranslatorStub;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class TranslateMissingTest {

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

  @Autowired private TranslationService translations;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Path storageRoot;

  private long record;
  private List<Long> pages;

  @BeforeEach
  void seed() throws Exception {
    model.resetAll();
    TranslatorStub.register(jdbc, model);
    TranslatorStub.reply(model, "Překlad:\n\nČeský text");
    jdbc.execute("DELETE FROM record");
    record = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Lagebericht");
    pages =
        List.of(
            PdfFixtures.page(jdbc, storageRoot, record, 1, "One"),
            PdfFixtures.page(jdbc, storageRoot, record, 2, "Two"),
            PdfFixtures.page(jdbc, storageRoot, record, 3, "Three"));
  }

  private String czech(long pageId) {
    return jdbc
        .queryForList(
            "SELECT text FROM page_translation WHERE page_id = ? AND lang = 'cs'",
            String.class,
            pageId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Test
  void eachMissingPageIsTranslatedOnceAndThenNeverAgain() throws Exception {
    assertThat(translations.translateMissing(record, pages, "cs")).isEqualTo(3);
    assertThat(TranslatorStub.calls(model)).isEqualTo(3);
    assertThat(czech(pages.get(0))).isEqualTo("Český text");

    assertThat(translations.translateMissing(record, pages, "cs")).isZero();
    assertThat(TranslatorStub.calls(model)).isEqualTo(3);
  }

  @Test
  void onlyTheSelectedPagesAreTranslated() throws Exception {
    assertThat(translations.translateMissing(record, pages.subList(1, 2), "cs")).isEqualTo(1);
    assertThat(czech(pages.get(0))).isNull();
    assertThat(czech(pages.get(1))).isEqualTo("Český text");
    assertThat(czech(pages.get(2))).isNull();
  }

  @Test
  void theRecordsOwnLanguageAndEnglishAreNeverTranslated() throws Exception {
    assertThat(translations.translateMissing(record, pages, "de")).isZero();
    assertThat(translations.translateMissing(record, pages, "en")).isZero();
    assertThat(TranslatorStub.calls(model)).isZero();
  }

  @Test
  void aBlankPageIsNotSentToTheModel() throws Exception {
    jdbc.update("UPDATE page_text SET text_raw = '   ' WHERE page_id = ?", pages.get(1));
    assertThat(translations.translateMissing(record, pages, "cs")).isEqualTo(2);
    assertThat(TranslatorStub.calls(model)).isEqualTo(2);
    assertThat(czech(pages.get(1))).isNull();
  }

  @Test
  void theRequestAsksForCzechWithTheConfiguredKey() throws Exception {
    translations.translateMissing(record, pages.subList(0, 1), "cs");
    model.verify(
        postRequestedFor(urlEqualTo(TranslatorStub.PATH))
            .withHeader("Authorization", equalTo("Bearer stub-key"))
            .withRequestBody(containing("into Czech"))
            .withRequestBody(containing("Text 1")));
    assertThat(
            jdbc.queryForObject(
                "SELECT model FROM page_translation WHERE page_id = ? AND lang = 'cs'",
                String.class,
                pages.get(0)))
        .isEqualTo(TranslatorStub.MODEL);
  }

  @Test
  void theEnglishCacheAndRankingAreUntouched() throws Exception {
    String cached =
        jdbc.queryForObject(
            "SELECT text_en FROM page_text WHERE page_id = ?", String.class, pages.get(0));
    translations.translateMissing(record, pages, "cs");
    assertThat(
            jdbc.queryForObject(
                "SELECT text_en FROM page_text WHERE page_id = ?", String.class, pages.get(0)))
        .isEqualTo(cached);
    assertThat(translations.bestEnglish(pages.get(0))).isEqualTo("One");
  }

  @Test
  void aFailedCallStopsWithAReadableErrorAndKeepsWhatWasStored() {
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

    assertThatThrownBy(() -> translations.translateMissing(record, pages, "cs"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("page 2")
        .hasMessageContaining("Czech")
        .hasMessageContaining("HTTP 400");
    assertThat(czech(pages.get(0))).isEqualTo("Český text");
    assertThat(czech(pages.get(1))).isNull();
  }

  @Test
  void withNoTranslatorConfiguredItSaysSo() {
    TranslatorStub.unregister(jdbc);
    assertThatThrownBy(() -> translations.translateMissing(record, pages, "cs"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No translation model is configured")
        .hasMessageContaining("Czech");
    assertThat(TranslatorStub.calls(model)).isZero();
  }
}
