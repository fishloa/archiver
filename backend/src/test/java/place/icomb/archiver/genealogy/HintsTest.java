package place.icomb.archiver.genealogy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static place.icomb.archiver.TestAuth.PROCESSOR_AUTH_HEADER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.TranslatorStub;
import place.icomb.archiver.mcp.ArchiverMcpTools;
import place.icomb.archiver.mcp.GenealogyMcpTools;
import place.icomb.archiver.service.FamilyTreeService;
import place.icomb.archiver.service.JobEventService;
import place.icomb.archiver.service.JobService;
import place.icomb.archiver.service.PersonMatchService;
import place.icomb.archiver.service.PersonMatchWorker;
import place.icomb.archiver.service.PipelineStateMachine;
import place.icomb.archiver.service.StorageService;

/**
 * Hints: suggestions made only when an administrator asks, never shown to anyone else, and turned
 * into links only by confirming them.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "archiver.person-match.enabled=false")
class HintsTest {

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

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StorageService storage;
  @Autowired private FamilyTreeService tree;
  @Autowired private JobService jobService;
  @Autowired private JobEventService jobEvents;
  @Autowired private PersonMatchService matching;
  @Autowired private PipelineStateMachine stateMachine;
  @Autowired private GenealogyMcpTools genealogyTools;
  @Autowired private ArchiverMcpTools readTools;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  private static final String ADMIN = "hints-admin@example.com";
  private static final String USER = "hints-reader@example.com";
  private static final int X = 322;
  private static final int Y = 323;
  private static final String FIGURE = "img-0.jpeg";

  private long record;
  private long p1;
  private long p2;

  @BeforeEach
  void fixtures() throws Exception {
    model.resetAll();
    TranslatorStub.register(jdbc, model);
    for (String[] u :
        new String[][] {{ADMIN, "admin", "HintAdmin"}, {USER, "user", "HintReader"}}) {
      jdbc.update("DELETE FROM app_user_email WHERE email = ?", u[0]);
      jdbc.update("DELETE FROM app_user WHERE display_name = ?", u[2]);
      Long id =
          jdbc.queryForObject(
              "INSERT INTO app_user (display_name, role) VALUES (?, ?) RETURNING id",
              Long.class,
              u[2],
              u[1]);
      jdbc.update("INSERT INTO app_user_email (user_id, email) VALUES (?, ?)", id, u[0]);
    }
    jdbc.update("DELETE FROM job WHERE kind = 'match_persons'");
    record = GenealogyFixtures.record(jdbc);
    byte[] png = GenealogyFixtures.png(200, 280);
    p1 = GenealogyFixtures.page(jdbc, storage, record, 1, png, "image/png");
    p2 = GenealogyFixtures.page(jdbc, storage, record, 2, png, "image/png");
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  private String nameOf(int person) {
    return tree.getPerson(person).matchName();
  }

  private void transcribe(long page, String markdown) {
    jdbc.update(
        "INSERT INTO page_text (page_id, engine, text_raw, content_type, raw_response)"
            + " VALUES (?, 'ocr_page_mistral', ?, 'text/markdown', ?::jsonb)",
        page,
        markdown,
        "{\"pages\":[{\"dimensions\":{\"width\":100,\"height\":140},\"images\":["
            + "{\"id\":\""
            + FIGURE
            + "\",\"top_left_x\":10,\"top_left_y\":20,"
            + "\"bottom_right_x\":30,\"bottom_right_y\":50}]}]}");
  }

  private long hint(long page, int person, String context, String figureId) {
    return jdbc.queryForObject(
        "INSERT INTO page_person_match (page_id, person_id, person_name, score, context,"
            + " figure_id) VALUES (?, ?, 'seeded', 0.5, ?, ?) RETURNING id",
        Long.class,
        page,
        person,
        context,
        figureId);
  }

  private List<Map<String, Object>> hintsOn(long page) {
    return jdbc.queryForList(
        "SELECT person_id, context, figure_id FROM page_person_match WHERE page_id = ?"
            + " ORDER BY figure_id NULLS FIRST",
        page);
  }

  private HttpResponse<String> send(String method, String path, Object body, String who)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    if (who != null) b.header("X-Auth-Email", who);
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode ok(HttpResponse<String> r, int status) throws Exception {
    assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
    return json.readTree(r.body());
  }

  private void runQueuedHintJobs() {
    new PersonMatchWorker("hints-test", jobService, jobEvents, matching).pollAndProcess();
  }

  private String jobStatus(long jobId) {
    return jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, jobId);
  }

  @Test
  void runningHintsOnAPageReplacesThatPagesHintsOnly() throws Exception {
    transcribe(
        p1,
        "Brief an "
            + nameOf(X)
            + " Czernin.\n\n!["
            + FIGURE
            + "]("
            + FIGURE
            + ")\n\nDer Graf"
            + " im Garten, 1938");
    transcribe(p2, "Seite zwei.");
    hint(p1, Y, "old guess", null);
    hint(p2, Y, "stays", null);
    TranslatorStub.reply(
        model,
        "{\"people\":[{\"personId\":"
            + X
            + ",\"score\":0.9,\"context\":\"Brief an\"}],"
            + "\"figures\":[{\"figureId\":\""
            + FIGURE
            + "\",\"personId\":"
            + X
            + ",\"score\":0.6,\"context\":\"Der Graf im Garten\"}]}");

    JsonNode run = ok(send("POST", "/admin/genealogy/hints/run", Map.of("pageId", p1), ADMIN), 200);
    assertThat(run.get("pages").asInt()).isEqualTo(1);
    long jobId = run.get("jobId").asLong();
    assertThat(jdbc.queryForMap("SELECT kind, record_id, page_id FROM job WHERE id = ?", jobId))
        .containsEntry("kind", "match_persons")
        .containsEntry("record_id", record)
        .containsEntry("page_id", p1);

    runQueuedHintJobs();

    assertThat(jobStatus(jobId)).isEqualTo("completed");
    assertThat(hintsOn(p1))
        .extracting("person_id", "context", "figure_id")
        .containsExactly(tuple(X, "Brief an", null), tuple(X, "Der Graf im Garten", FIGURE));
    assertThat(hintsOn(p2))
        .extracting("person_id", "context", "figure_id")
        .containsExactly(tuple(Y, "stays", null));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_person_record WHERE record_id = ?", Long.class, record))
        .isZero();

    String prompt =
        model.findAll(postRequestedFor(urlEqualTo(TranslatorStub.PATH))).get(0).getBodyAsString();
    assertThat(prompt).contains(FIGURE).contains("Der Graf im Garten");
    assertThat(prompt).contains("\"model\":\"" + TranslatorStub.MODEL + "\"");
  }

  @Test
  void aRecordRunCountsItsPagesAndRunsEveryPage() throws Exception {
    transcribe(p1, "Brief an " + nameOf(X) + " Czernin.");
    transcribe(p2, "Seite zwei.");
    hint(p2, Y, "gone after the run", null);
    TranslatorStub.reply(
        model, "{\"people\":[{\"personId\":" + X + ",\"score\":0.8,\"context\":\"an\"}]}");

    JsonNode run =
        ok(send("POST", "/admin/genealogy/hints/run", Map.of("recordId", record), ADMIN), 200);
    assertThat(run.get("pages").asInt()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT page_id FROM job WHERE id = ?", Long.class, run.get("jobId").asLong()))
        .isNull();

    runQueuedHintJobs();

    assertThat(jobStatus(run.get("jobId").asLong())).isEqualTo("completed");
    assertThat(hintsOn(p1)).extracting(h -> h.get("person_id")).containsExactly(X);
    assertThat(hintsOn(p2)).isEmpty();
  }

  @Test
  void aModelFailureFailsTheJobAndKeepsTheOldHints() throws Exception {
    transcribe(p1, "Brief an " + nameOf(X) + " Czernin.");
    hint(p1, Y, "old guess", null);
    model.stubFor(post(urlEqualTo(TranslatorStub.PATH)).willReturn(aResponse().withStatus(500)));

    long jobId =
        ok(send("POST", "/admin/genealogy/hints/run", Map.of("pageId", p1), ADMIN), 200)
            .get("jobId")
            .asLong();
    runQueuedHintJobs();

    assertThat(jobStatus(jobId)).isEqualTo("failed");
    assertThat(hintsOn(p1)).extracting(h -> h.get("context")).containsExactly("old guess");
  }

  @Test
  void noOpenAiAnywhere() throws Exception {
    try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
      List<String> offenders =
          files
              .filter(Files::isRegularFile)
              .filter(
                  f -> {
                    try {
                      String s = Files.readString(f);
                      return s.contains("api.openai.com") || s.contains("archiver.openai");
                    } catch (Exception e) {
                      return false;
                    }
                  })
              .map(Path::toString)
              .toList();
      assertThat(offenders).isEmpty();
    }
    String yml = Files.readString(Path.of("src/main/resources/application.yml"));
    assertThat(yml).doesNotContainPattern("(?m)^  openai:");
  }

  @Test
  void noRecordEnqueuesMatchingByItself() {
    jdbc.update("UPDATE record SET status = 'embedding' WHERE id = ?", record);
    jdbc.update(
        "INSERT INTO job (kind, record_id, status) VALUES ('embed_record', ?, 'completed')",
        record);

    stateMachine.autoAdvance(record);

    assertThat(jdbc.queryForObject("SELECT status FROM record WHERE id = ?", String.class, record))
        .isEqualTo("complete");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE record_id = ? AND kind = 'match_persons'",
                Long.class,
                record))
        .isZero();
  }

  @Test
  void hintsListEnrichedForAdmins() throws Exception {
    transcribe(p1, "x");
    long text = hint(p1, X, "named", null);
    long picture = hint(p1, Y, "pictured", FIGURE);

    JsonNode byPage = ok(send("GET", "/admin/genealogy/hints?pageId=" + p1, null, ADMIN), 200);
    JsonNode byRecord =
        ok(send("GET", "/admin/genealogy/hints?recordId=" + record, null, ADMIN), 200);
    assertThat(byRecord).isEqualTo(byPage);
    assertThat(byPage).hasSize(2);
    JsonNode first = byPage.get(0);
    assertThat(first.get("id").asLong()).isEqualTo(text);
    assertThat(first.get("personId").asInt()).isEqualTo(X);
    assertThat(first.get("name").asText()).isEqualTo(tree.getPerson(X).name);
    assertThat(first.has("birthYear")).isTrue();
    assertThat(first.get("pageSeq").asInt()).isEqualTo(1);
    assertThat(first.get("figureNumber").isNull()).isTrue();
    JsonNode second = byPage.get(1);
    assertThat(second.get("id").asLong()).isEqualTo(picture);
    assertThat(second.get("figureId").asText()).isEqualTo(FIGURE);
    assertThat(second.get("figureNumber").asInt()).isEqualTo(1);
  }

  @Test
  void confirmingATextHintMakesAPageLinkAndRemovesTheHint() throws Exception {
    long id = hint(p1, X, "Brief an den Grafen", null);
    long before = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM gen_change", Long.class);

    ok(send("POST", "/admin/genealogy/hints/" + id + "/confirm", Map.of(), ADMIN), 200);

    assertThat(
            jdbc.queryForMap(
                "SELECT person_id, page_id, role, note FROM gen_person_record WHERE record_id = ?",
                record))
        .containsEntry("person_id", X)
        .containsEntry("page_id", p1)
        .containsEntry("role", "mentioned")
        .containsEntry("note", "Brief an den Grafen");
    assertThat(hintsOn(p1)).isEmpty();
    List<Map<String, Object>> changes =
        jdbc.queryForList("SELECT txid, reason FROM gen_change WHERE id > ?", before);
    assertThat(changes).hasSize(1);
    assertThat(changes.get(0))
        .containsEntry("reason", "confirmed hint #" + id + " (page 1 of record " + record + ")");
  }

  @Test
  void confirmingATextHintTakesTheGivenRoleAndReason() throws Exception {
    long id = hint(p1, X, "signs", null);

    ok(
        send(
            "POST",
            "/admin/genealogy/hints/" + id + "/confirm",
            Map.of("role", "signatory", "reason", "signature at the foot"),
            ADMIN),
        200);

    assertThat(
            jdbc.queryForObject(
                "SELECT role FROM gen_person_record WHERE record_id = ?", String.class, record))
        .isEqualTo("signatory");
    assertThat(
            jdbc.queryForObject(
                "SELECT reason FROM gen_change ORDER BY id DESC LIMIT 1", String.class))
        .isEqualTo("signature at the foot");
  }

  @Test
  void confirmingAFigureHintCutsTheFigureAndTagsThePerson() throws Exception {
    transcribe(p1, "![" + FIGURE + "](" + FIGURE + ")");
    long plain = hint(p1, X, "pictured", FIGURE);
    long portrait = hint(p1, Y, "pictured", FIGURE);
    long before = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM gen_change", Long.class);

    ok(send("POST", "/admin/genealogy/hints/" + plain + "/confirm", Map.of(), ADMIN), 200);
    ok(
        send(
            "POST",
            "/admin/genealogy/hints/" + portrait + "/confirm",
            Map.of("portrait", true),
            ADMIN),
        200);

    List<Map<String, Object>> media =
        jdbc.queryForList(
            "SELECT m.crop_left, m.crop_top, m.crop_width, m.crop_height, mp.person_id,"
                + " mp.is_portrait FROM gen_media m JOIN gen_media_person mp ON mp.media_id = m.id"
                + " WHERE m.page_id = ? ORDER BY m.id",
            p1);
    assertThat(media)
        .containsExactly(
            Map.of(
                "crop_left",
                20,
                "crop_top",
                40,
                "crop_width",
                40,
                "crop_height",
                60,
                "person_id",
                X,
                "is_portrait",
                false),
            Map.of(
                "crop_left",
                20,
                "crop_top",
                40,
                "crop_width",
                40,
                "crop_height",
                60,
                "person_id",
                Y,
                "is_portrait",
                true));
    assertThat(hintsOn(p1)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(DISTINCT txid) FROM gen_change WHERE id > ?", Long.class, before))
        .isEqualTo(2);
  }

  @Test
  void confirmingTwiceIs404() throws Exception {
    long id = hint(p1, X, "once", null);

    ok(send("POST", "/admin/genealogy/hints/" + id + "/confirm", Map.of(), ADMIN), 200);
    assertThat(
            send("POST", "/admin/genealogy/hints/" + id + "/confirm", Map.of(), ADMIN).statusCode())
        .isEqualTo(404);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_person_record WHERE record_id = ?", Long.class, record))
        .isEqualTo(1);
  }

  @Test
  void reOcrDropsThePagesHints() throws Exception {
    transcribe(p1, "first reading");
    hint(p1, X, "old", null);
    hint(p2, X, "other page", null);

    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/processor/ocr/" + p1))
                .header("Content-Type", "application/json")
                .header("Authorization", PROCESSOR_AUTH_HEADER)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"engine\":\"ocr_page_mistral\",\"textRaw\":\"second reading\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(201);

    assertThat(hintsOn(p1)).isEmpty();
    assertThat(hintsOn(p2)).hasSize(1);
  }

  @Test
  void aWholeHandTranscriptionDropsThePagesHints() throws Exception {
    transcribe(p1, "first reading");
    hint(p1, X, "old", null);

    ok(
        send(
            "POST",
            "/admin/pages/" + p1 + "/corrections",
            Map.of(
                "field", "text_raw",
                "replaceAll", true,
                "replace", "typed by hand",
                "reason", "engine failed"),
            ADMIN),
        200);

    assertThat(hintsOn(p1)).isEmpty();
  }

  @Test
  void nonAdminGets403OnEveryHintRoute() throws Exception {
    long id = hint(p1, X, "secret", null);
    String[][] routes = {
      {"GET", "/admin/genealogy/hints?pageId=" + p1},
      {"GET", "/admin/genealogy/hints?recordId=" + record},
      {"POST", "/admin/genealogy/hints/run"},
      {"POST", "/admin/genealogy/hints/" + id + "/confirm"},
    };
    for (String[] route : routes) {
      assertThat(send(route[0], route[1], Map.of("pageId", p1), USER).statusCode())
          .as(route[0] + " " + route[1])
          .isEqualTo(403);
    }
    assertThat(hintsOn(p1)).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE kind = 'match_persons'", Long.class))
        .isZero();
  }

  @Test
  void findPeopleInDocumentShowsHintsOnlyToAdminsWhoAsk() {
    hint(p1, X, "secret", null);

    GenealogyFixtures.signInAsUser();
    assertThat(readTools.findPeopleInDocument(record, true)).doesNotContainKey("hints");

    GenealogyFixtures.signInAsAdmin();
    assertThat(readTools.findPeopleInDocument(record, false)).doesNotContainKey("hints");
    Map<String, Object> asked = readTools.findPeopleInDocument(record, true);
    assertThat(asked).containsKey("hints");
    assertThat((List<?>) asked.get("hints")).hasSize(1);
    assertThat((List<?>) asked.get("people")).isEmpty();
  }

  @Test
  void suggestPeopleEnqueuesForAdminsOnly() {
    GenealogyFixtures.signInAsUser();
    assertThatThrownBy(() -> genealogyTools.suggestPeople(record, null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE kind = 'match_persons'", Long.class))
        .isZero();

    GenealogyFixtures.signInAsAdmin();
    assertThat(genealogyTools.suggestPeople(record, null, null)).containsEntry("pages", 2);
    assertThat(genealogyTools.suggestPeople(record, 2, null)).containsEntry("pages", 1);
    assertThat(genealogyTools.suggestPeople(null, null, p1)).containsEntry("pages", 1);
    assertThat(
            jdbc.queryForList(
                "SELECT page_id FROM job WHERE kind = 'match_persons' ORDER BY id", Long.class))
        .containsExactly(null, p2, p1);
  }
}
