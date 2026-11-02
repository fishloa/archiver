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
            null, record, 1, null, "full name", 3, null, name, null, null, null, "certificate");
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
            tools.cite(null, null, null, null, null, null, null, null, null, null, ALEXANDER, "x"))
        .containsKey("error");
    assertThat(tools.addMedia(99999999L, 1, null, null, null, null, null, null, null, null, "x"))
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
            t -> t.cite(1L, null, null, null, null, null, null, null, null, null, ALEXANDER, "r"),
            t -> t.linkRecord(ALEXANDER, 1L, null, null, null, null, "r"),
            t -> t.addMedia(1L, 1, null, null, null, null, null, null, null, null, "r"),
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
}
