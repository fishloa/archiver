package place.icomb.archiver.genealogy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
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
import place.icomb.archiver.service.FamilyTreeGolden;

/** What migration V22 made of the legacy genealogy file, read back from the database. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class LegacyImportTest {

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

  @Autowired private JdbcTemplate jdbc;

  private static final int ALEXANDER = 322;
  private static final int JAROMIR_EGON = 141;

  private String primaryName(int personId) {
    return jdbc.queryForObject(
        "SELECT concat_ws(' ', given, surname) FROM gen_name WHERE person_id = ? AND is_primary",
        String.class,
        personId);
  }

  private int personNamed(String given, String surname) {
    return jdbc.queryForObject(
        "SELECT person_id FROM gen_name WHERE given = ? AND surname = ? AND is_primary",
        Integer.class,
        given,
        surname);
  }

  @Test
  void everyLegacyIdHasItsOldNameWithTheFamilySurname() throws Exception {
    for (String line : FamilyTreeGolden.read("people.tsv")) {
      String[] c = line.split("\t", -1);
      int id = Integer.parseInt(c[0]);
      assertThat(primaryName(id))
          .as("person " + id)
          .isEqualTo(FamilyTreeGolden.expectedDisplay(id, c[8]));
    }
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM gen_person WHERE id <= 348", Integer.class))
        .isEqualTo(348);
  }

  @Test
  void aCzerninFormAlreadyInTheNameBecomesTheSurnameOnce() {
    assertThat(nameParts(76)).containsExactly(null, "Johann Rudolf", "Czernin");
    assertThat(nameParts(1)).containsExactly(null, "Humprecht", "Czernin z Chudenic");
    assertThat(nameParts(172))
        .containsExactly(null, "Josef Maria Rudolf Ferdinand", "Czernin-Kinsky");
    assertThat(nameParts(324)).containsExactly(null, "Alexander Hannibal", "Czernin Fishlock");
    assertThat(nameParts(277)).containsExactly(null, "Charlotte Sonia", "Phillips");
    assertThat(nameParts(138)).containsExactly(null, "Rudolf Heřman Maria Josef", "Czernin");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_name WHERE type = 'married'", Integer.class))
        .isZero();
  }

  private java.util.List<Object> nameParts(int personId) {
    Map<String, Object> n =
        jdbc.queryForMap(
            "SELECT prefix, given, surname FROM gen_name WHERE person_id = ? AND is_primary",
            personId);
    return java.util.Arrays.asList(n.get("prefix"), n.get("given"), n.get("surname"));
  }

  @Test
  void spousesAreNumberedFrom349() {
    assertThat(jdbc.queryForObject("SELECT min(id) FROM gen_person WHERE id > 348", Integer.class))
        .isEqualTo(349);
    int next = jdbc.queryForObject("SELECT nextval('gen_person_id_seq')", Integer.class);
    assertThat(next)
        .isGreaterThan(jdbc.queryForObject("SELECT max(id) FROM gen_person", Integer.class) - 1);
  }

  @Test
  void alexanderHasBirthDeathMarriageToDianaAndBothParents() {
    assertThat(
            jdbc.queryForList(
                "SELECT e.type, e.date_text, p.name AS place FROM gen_event e"
                    + " LEFT JOIN gen_place p ON p.id = e.place_id"
                    + " WHERE e.person_id = ? ORDER BY e.sort_order",
                ALEXANDER))
        .containsExactly(
            Map.of("type", "BIRT", "date_text", "30 APR 1913", "place", "Wien"),
            Map.of("type", "DEAT", "date_text", "5 APR 2002", "place", "Oxford"));
    int diana = personNamed("Diana Zannick", "Hutton/Hulton");
    Map<String, Object> marriage =
        jdbc.queryForMap(
            "SELECT e.date_text, p.name AS place FROM gen_couple c"
                + " JOIN gen_event e ON e.couple_id = c.id AND e.type = 'MARR'"
                + " LEFT JOIN gen_place p ON p.id = e.place_id"
                + " WHERE c.person1_id = ? AND c.person2_id = ?",
            ALEXANDER,
            diana);
    assertThat(marriage)
        .containsEntry("date_text", "9 JUL 1949")
        .containsEntry("place", "Haywards Heath");
    assertThat(
            jdbc.queryForList(
                "SELECT parent_id FROM gen_parent_child WHERE child_id = ? AND type = 'birth'"
                    + " ORDER BY parent_id",
                Integer.class,
                ALEXANDER))
        .containsExactly(296, personNamed("Maria Gabriele", "von Orsini-Rosenberg"));
    assertThat(
            jdbc.queryForList(
                "SELECT e.type, e.date_text FROM gen_event e WHERE e.person_id = ?"
                    + " ORDER BY e.sort_order",
                diana))
        .containsExactly(
            Map.of("type", "BIRT", "date_text", "12 DEC 1922"),
            Map.of("type", "DEAT", "date_text", "30 AUG 1982"));
  }

  @Test
  void jaromirHasFourCouplesThreeEndingInDivorceAndOneSpouseMarriedTwice() {
    List<Map<String, Object>> couples =
        jdbc.queryForList(
            """
            SELECT c.id, c.sort_order,
                   (SELECT count(*) FROM gen_event e WHERE e.couple_id = c.id AND e.type = 'MARR') AS marr,
                   (SELECT count(*) FROM gen_event e WHERE e.couple_id = c.id AND e.type = 'DIV') AS div
            FROM gen_couple c WHERE c.person1_id = ? ORDER BY c.sort_order
            """,
            JAROMIR_EGON);
    assertThat(couples).hasSize(4);
    assertThat(couples.stream().filter(c -> ((Number) c.get("div")).intValue() > 0)).hasSize(3);
    Map<String, Object> alixMay = couples.get(1);
    assertThat(((Number) alixMay.get("marr")).intValue()).isEqualTo(2);
    assertThat(((Number) alixMay.get("div")).intValue()).isEqualTo(2);
  }

  @Test
  void theAdoptionsBecomeAdoptedLinksOrAdoptionEvents() {
    assertThat(
            jdbc.queryForList(
                "SELECT parent_id, child_id FROM gen_parent_child WHERE type = 'adopted'"))
        .containsExactly(Map.of("parent_id", 134, "child_id", 202));
    assertThat(
            jdbc.queryForList(
                "SELECT person_id, date_text FROM gen_event WHERE type = 'ADOP' ORDER BY person_id"))
        .containsExactly(
            Map.of("person_id", 172, "date_text", "1954"),
            Map.of("person_id", 175, "date_text", "1964"),
            Map.of("person_id", 202, "date_text", "1927"));
  }

  @Test
  void legacyDatesBecomeGedcomDates() {
    assertThat(dateOf(70, "DEAT")).isEqualTo("BET 1731 AND 1733");
    assertThat(dateOf(1, "DEAT")).isEqualTo("AFT 1501");
    assertThat(dateOf(8, "DEAT")).isEqualTo("ABT 1599");
    assertThat(dateOf(58, "BIRT")).isEqualTo("APR 1570");
    assertThat(dateOf(54, "DEAT")).isEqualTo("BEF 1564");
    assertThat(
            jdbc.queryForMap(
                "SELECT date_text, description FROM gen_event WHERE person_id = 13 AND type = 'DEAT'"))
        .containsEntry("date_text", null)
        .containsEntry("description", "young");
  }

  private String dateOf(int personId, String type) {
    return jdbc.queryForObject(
        "SELECT date_text FROM gen_event WHERE person_id = ? AND type = ?",
        String.class,
        personId,
        type);
  }

  @Test
  void theReviewListNamesTheKnownConflicts() {
    List<String> review =
        jdbc.queryForList(
            "SELECT needs_review FROM gen_person WHERE needs_review IS NOT NULL", String.class);
    assertThat(review).isNotEmpty();
    int mariaAntonia = personNamed("Maria Antonia", "zu Salm-Neuburg");
    assertThat(
            jdbc.queryForObject(
                "SELECT needs_review FROM gen_person WHERE id = ?", String.class, mariaAntonia))
        .contains("conflicting birth dates: 16 APR 1766 vs 16 APR 1776");
    assertThat(review).anyMatch(r -> r.contains("uncertain name: z Gutštejna?"));
    assertThat(review).anyMatch(r -> r.contains("date not understood: 31.7./12.11.1787"));
  }

  @Test
  void everyImportedNameAndEventCitesTheLegacyFile() {
    long source =
        jdbc.queryForObject(
            "SELECT id FROM gen_source WHERE title = ?",
            Long.class,
            "genealogy.euweb.cz Czernin pages (legacy text file)");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_event e WHERE NOT EXISTS (SELECT 1 FROM gen_citation c"
                    + " WHERE c.event_id = e.id AND c.source_id = ? AND c.quality = 1)",
                Integer.class,
                source))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_name n WHERE NOT EXISTS (SELECT 1 FROM gen_citation c"
                    + " WHERE c.name_id = n.id AND c.source_id = ? AND c.quality = 1)",
                Integer.class,
                source))
        .isZero();
  }

  @Test
  void theLegacyLineIsKept() {
    assertThat(
            jdbc.queryForObject(
                "SELECT legacy_entry FROM gen_person WHERE id = ?", String.class, ALEXANDER))
        .startsWith("D6. Alexander Friedrich Josef Paul Maria, *Wien 30.4.1913");
  }

  @Test
  void nobodyBornBefore1926IsLivingAndTheLivingHaveNoDeath() {
    assertThat(jdbc.queryForObject("SELECT living FROM gen_person WHERE id = 324", Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT living FROM gen_person WHERE id = ?", Boolean.class, ALEXANDER))
        .isFalse();
  }

  @Test
  void personReferencesAreForeignKeys() {
    assertThat(
            jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conname IN"
                    + " ('page_person_match_person_id_fkey', 'app_user_family_tree_person_id_fkey')"
                    + " ORDER BY conname",
                String.class))
        .containsExactly("app_user_family_tree_person_id_fkey", "page_person_match_person_id_fkey");
  }
}
