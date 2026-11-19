package place.icomb.archiver.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.genealogy.GenealogyFixtures;
import place.icomb.archiver.service.OcrFigures;
import place.icomb.archiver.service.StorageService;

/** The tree-editing MCP tools: administrators only, a reason every time, and undoable. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class McpGenealogyToolsTest {

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

  @Autowired private GenealogyMcpTools tools;
  @Autowired private ArchiverMcpTools readTools;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StorageService storage;
  @Autowired private OcrFigures figures;

  private static final int ALEXANDER = 322;

  private void signIn(String... roles) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "mcp-genealogy@example.com",
                "n/a",
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
  }

  @BeforeEach
  void admin() {
    signIn("ROLE_USER", "ROLE_ADMIN");
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  private long lastChange() {
    return jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM gen_change", Long.class);
  }

  @Test
  void anAdministratorAddsAPersonWithABirthPlaceAndCanUndoIt() {
    Map<String, Object> person =
        tools.addPerson("Anna", "Novák", null, "F", null, "letter of 1938");
    int id = ((Number) person.get("id")).intValue();
    Map<String, Object> birth =
        tools.addEvent(id, null, "BIRT", "12 MAR 1890", "Dymokury-Nové", null, "parish register");
    assertThat(birth).doesNotContainKey("error");
    assertThat(
            jdbc.queryForObject(
                "SELECT p.name FROM gen_event e JOIN gen_place p ON p.id = e.place_id WHERE e.id = ?",
                String.class,
                birth.get("id")))
        .isEqualTo("Dymokury-Nové");

    Map<String, Object> undone = tools.undoChange(lastChange(), "wrong person");
    assertThat(undone).doesNotContainKey("error");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_event WHERE id = ?", Integer.class, birth.get("id")))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_place WHERE name = 'Dymokury-Nové'", Integer.class))
        .isZero();
  }

  @Test
  void aCitationOfARecordPageMakesTheRecordsSourceOnce() {
    long archive =
        jdbc.queryForObject(
            "INSERT INTO archive (name) VALUES ('mcp cite') RETURNING id", Long.class);
    long record =
        jdbc.queryForObject(
            "INSERT INTO record (archive_id, source_system, source_record_id, title, status, lang,"
                + " metadata_lang) VALUES (?, 'test', ?, 'Heiratsurkunde', 'complete', 'de', 'de')"
                + " RETURNING id",
            Long.class,
            archive,
            "cite-" + System.nanoTime());
    long attachment =
        jdbc.queryForObject(
            "INSERT INTO attachment (record_id, role, path, mime) VALUES (?, 'page_image', 'x.jpg',"
                + " 'image/jpeg') RETURNING id",
            Long.class,
            record);
    long page =
        jdbc.queryForObject(
            "INSERT INTO page (record_id, seq, attachment_id) VALUES (?, 1, ?) RETURNING id",
            Long.class,
            record,
            attachment);
    long name =
        jdbc.queryForObject(
            "SELECT id FROM gen_name WHERE person_id = ? AND is_primary", Long.class, ALEXANDER);

    Map<String, Object> first =
        tools.cite(
            null,
            record,
            1,
            null,
            "full name",
            3,
            null,
            name,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "certificate");
    Map<String, Object> second =
        tools.cite(
            null,
            record,
            1,
            null,
            "same page",
            3,
            null,
            null,
            null,
            null,
            ALEXANDER,
            null,
            null,
            null,
            null,
            "certificate");

    assertThat(first.get("sourceId")).isEqualTo(second.get("sourceId"));
    assertThat(((Number) first.get("pageId")).longValue()).isEqualTo(page);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_source WHERE record_id = ?", Integer.class, record))
        .isEqualTo(1);
  }

  @Test
  void refusalsComeBackAsErrorsAndChangeNothing() {
    long before = lastChange();
    assertThat(tools.addPerson("X", null, null, null, null, " ")).containsKey("error");
    assertThat(tools.addEvent(ALEXANDER, null, "BIRT", "30.4.1913", null, null, "x"))
        .containsKey("error");
    assertThat(tools.linkParent(324, 1, "birth", null, "x")).containsKey("error");
    assertThat(
            tools.cite(
                null, null, null, null, null, null, null, null, null, null, ALEXANDER, null, null,
                null, null, "x"))
        .containsKey("error");
    assertThat(
            tools.addMedia(
                99999999L, 1, null, null, null, null, null, null, null, null, null, null, "x"))
        .containsKey("error");
    assertThat(lastChange()).isEqualTo(before);
  }

  @Test
  void getPersonCarriesTheStoredFacts() {
    Map<String, Object> p = readTools.getPerson(ALEXANDER);
    assertThat(p)
        .containsKeys(
            "names", "couples", "parentLinks", "recordLinks", "media", "portrait", "sources");
    assertThat((List<?>) p.get("couples")).hasSize(1);
    assertThat(((List<?>) p.get("sources")).toString()).contains("legacy text file");
    assertThat(((List<?>) p.get("lifeEvents")).toString()).contains("30 APR 1913");
  }

  @Test
  void everyWriteToolRefusesAnyoneButAnAdministrator() {
    List<Consumer<GenealogyMcpTools>> calls =
        List.of(
            t -> t.addPerson("X", null, null, null, null, "r"),
            t -> t.updatePerson(ALEXANDER, null, null, "x", null, "r"),
            t -> t.addName(ALEXANDER, "alias", "X", null, null, null, null, null, "r"),
            t -> t.addEvent(ALEXANDER, null, "RESI", null, null, null, "r"),
            t -> t.updateEvent(1L, null, null, null, "x", "r"),
            t -> t.linkParent(1, 2, null, null, "r"),
            t -> t.addCouple(1, 2, null, null, "r"),
            t ->
                t.cite(
                    1L, null, null, null, null, null, null, null, null, null, ALEXANDER, null, null,
                    null, null, "r"),
            t -> t.linkRecord(ALEXANDER, 1L, null, null, null, null, "r"),
            t -> t.addMedia(1L, 1, null, null, null, null, null, null, null, null, null, null, "r"),
            t -> t.updateFact("event", 1L, Map.of("description", "x"), "r"),
            t -> t.deleteFact("event", 1L, "r"),
            t -> t.tagMedia(1L, ALEXANDER, null, "r"),
            t -> t.mergePersons(1, 2, "r"),
            t -> t.undoChange(1L, "r"),
            t -> t.treeChanges(ALEXANDER, null, null, null));
    long before = lastChange();
    for (String[] who : new String[][] {{"ROLE_USER"}, {"ROLE_PROCESSOR"}, {}}) {
      signIn(who);
      for (Consumer<GenealogyMcpTools> call : calls) {
        assertThatThrownBy(() -> call.accept(tools))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("administrators only");
      }
    }
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> tools.addPerson("X", null, null, null, null, "r"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(lastChange()).isEqualTo(before);
  }

  @Test
  void getPersonHidesThePrivateFactsFromReadersAndShowsThemToAnAdministrator() {
    jdbc.update(
        "UPDATE gen_person SET notes = 'private', needs_review = 'check me' WHERE id = ?",
        ALEXANDER);
    try {
      signIn("ROLE_USER");
      assertThat(readTools.getPerson(ALEXANDER)).doesNotContainKeys("notes", "needsReview");

      signIn("ROLE_USER", "ROLE_ADMIN");
      assertThat(readTools.getPerson(ALEXANDER))
          .containsEntry("notes", "private")
          .containsEntry("needsReview", "check me");
    } finally {
      jdbc.update(
          "UPDATE gen_person SET notes = NULL, needs_review = NULL WHERE id = ?", ALEXANDER);
    }
  }

  private long pageWithFigures() throws Exception {
    long record = GenealogyFixtures.record(jdbc);
    long page =
        GenealogyFixtures.page(
            jdbc, storage, record, 1, GenealogyFixtures.png(200, 280), "image/png");
    jdbc.update(
        "INSERT INTO page_text (page_id, engine, text_raw, content_type, raw_response)"
            + " VALUES (?, 'ocr_page_mistral', 'x', 'text/markdown', ?::jsonb)",
        page,
        "{\"pages\":[{\"dimensions\":{\"width\":100,\"height\":140},\"images\":["
            + "{\"id\":\"img-0.jpeg\",\"top_left_x\":10,\"top_left_y\":20,"
            + "\"bottom_right_x\":30,\"bottom_right_y\":50}]}]}");
    return page;
  }

  private long newPerson() {
    return ((Number) tools.addPerson("Test", "Person", null, "U", null, "fixture").get("id"))
        .longValue();
  }

  private long newEvent(long person) {
    return ((Number)
            tools
                .addEvent((int) person, null, "RESI", "1 JAN 1930", null, null, "fixture")
                .get("id"))
        .longValue();
  }

  private Map<String, Object> citeUrl(long event, String url, String title) {
    return tools.cite(
        null, null, null, null, "d", 2, event, null, null, null, null, url, title, null, null,
        "web");
  }

  @Test
  void citingAUrlTwiceMakesOneSource() {
    long event = newEvent(newPerson());
    Map<String, Object> a = citeUrl(event, "https://example.org/a", "Parish site");
    Map<String, Object> b = citeUrl(event, "https://example.org/a", "Parish site");
    assertThat(a.get("sourceId")).isEqualTo(b.get("sourceId"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_source WHERE url = 'https://example.org/a'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_citation WHERE event_id = ?", Integer.class, event))
        .isEqualTo(2);
  }

  @Test
  void aFreeTextSourceIsRepointedAndItsCitationsFollow() {
    GenealogyFixtures.record(jdbc);
    long record = jdbc.queryForObject("SELECT max(id) FROM record", Long.class);
    long event = newEvent(newPerson());
    Map<String, Object> cited = citeUrl(event, null, "Letter from X " + System.nanoTime());
    long source = ((Number) cited.get("sourceId")).longValue();
    String title =
        jdbc.queryForObject("SELECT title FROM gen_source WHERE id = ?", String.class, source);

    Map<String, Object> r =
        tools.updateFact("source", source, Map.of("recordId", record), "it is archive record");
    assertThat(r).doesNotContainKey("error");
    assertThat(
            jdbc.queryForObject(
                "SELECT s.record_id FROM gen_citation c JOIN gen_source s ON s.id = c.source_id"
                    + " WHERE c.event_id = ?",
                Long.class,
                event))
        .isEqualTo(record);
    assertThat(
            jdbc.queryForObject("SELECT title FROM gen_source WHERE id = ?", String.class, source))
        .isEqualTo(title);
  }

  @Test
  void updateAndDeleteAFact() {
    long event = newEvent(newPerson());
    assertThat(tools.updateFact("event", event, Map.of("dateText", "5 APR 1934"), "fix"))
        .doesNotContainKey("error");
    assertThat(
            jdbc.queryForObject(
                "SELECT date_text FROM gen_event WHERE id = ?", String.class, event))
        .isEqualTo("5 APR 1934");
    assertThat(tools.deleteFact("event", event, "wrong")).doesNotContainKey("error");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_event WHERE id = ?", Integer.class, event))
        .isZero();
    assertThat(tools.undoChange(lastChange(), "restore")).doesNotContainKey("error");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_event WHERE id = ?", Integer.class, event))
        .isEqualTo(1);
    assertThat(tools.updateFact("person", 1L, Map.of("notes", "x"), "r").get("error").toString())
        .contains("name, event, couple");
  }

  @Test
  void deletingACitedSourceIsRefused() {
    long event = newEvent(newPerson());
    long source =
        ((Number) citeUrl(event, "https://example.org/b", "B").get("sourceId")).longValue();
    assertThat(tools.deleteFact("source", source, "tidy").get("error").toString())
        .contains("still referenced by");
  }

  @Test
  void addMediaByFigure() throws Exception {
    long page = pageWithFigures();
    long person = newPerson();
    Map<String, Object> m =
        tools.addMedia(
            null, null, page, 1, null, null, null, null, null, null, null, (int) person, "figure");
    assertThat(m).doesNotContainKey("error");
    OcrFigures.Figure f = figures.figure(page, 1);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT crop_left, crop_top, crop_width, crop_height FROM gen_media WHERE id = ?",
            m.get("id"));
    assertThat(row.values()).containsExactly(f.left(), f.top(), f.width(), f.height());
    assertThat(
            jdbc.queryForObject(
                "SELECT is_portrait FROM gen_media_person WHERE media_id = ?",
                Boolean.class,
                m.get("id")))
        .isTrue();
  }

  @Test
  void figureAndCropTogetherIsRefused() throws Exception {
    long page = pageWithFigures();
    assertThat(
            tools
                .addMedia(null, null, page, 1, 0, null, null, null, null, null, null, null, "x")
                .get("error"))
        .isEqualTo("give figure or a pixel crop, not both");
  }

  @Test
  void updatingMediaWithFigureAndCropTogetherIsRefused() throws Exception {
    long page = pageWithFigures();
    long person = newPerson();
    Map<String, Object> m =
        tools.addMedia(
            null, null, page, 1, null, null, null, null, null, null, null, (int) person, "figure");
    long before = lastChange();
    assertThat(
            tools
                .updateFact(
                    "media",
                    ((Number) m.get("id")).longValue(),
                    Map.of("figure", 1, "cropLeft", 0),
                    "x")
                .get("error"))
        .isEqualTo("give figure or a pixel crop, not both");
    assertThat(lastChange()).isEqualTo(before);
  }

  @Test
  void figureOutOfRangeIsRefused() throws Exception {
    long page = pageWithFigures();
    long before = lastChange();
    assertThat(
            tools
                .addMedia(null, null, page, 2, null, null, null, null, null, null, null, null, "x")
                .get("error")
                .toString())
        .contains("there is no figure 2");
    assertThat(lastChange()).isEqualTo(before);
  }

  @Test
  void aSecondPortraitReplacesTheFirstAndUndoRestoresIt() throws Exception {
    long page = pageWithFigures();
    long person = newPerson();
    long m1 =
        ((Number)
                tools
                    .addMedia(
                        null, null, page, null, null, null, null, null, null, null, null, null, "a")
                    .get("id"))
            .longValue();
    long m2 =
        ((Number)
                tools
                    .addMedia(
                        null, null, page, null, null, null, null, null, null, null, null, null, "b")
                    .get("id"))
            .longValue();
    tools.tagMedia(m1, (int) person, true, "first");
    Map<String, Object> second = tools.tagMedia(m2, (int) person, true, "second");
    assertThat(second).doesNotContainKey("error");
    String portraits = "SELECT media_id FROM gen_media_person WHERE person_id = ? AND is_portrait";
    assertThat(jdbc.queryForList(portraits, Long.class, person)).containsExactly(m2);

    assertThat(tools.undoChange(lastChange(), "undo")).doesNotContainKey("error");
    assertThat(jdbc.queryForList(portraits, Long.class, person)).containsExactly(m1);
  }

  @Test
  void nonAdminIsRefusedUpdateFact() {
    signIn("ROLE_USER");
    assertThatThrownBy(() -> tools.updateFact("event", 1L, Map.of("description", "x"), "r"))
        .hasMessageContaining("administrators only");
  }
}
