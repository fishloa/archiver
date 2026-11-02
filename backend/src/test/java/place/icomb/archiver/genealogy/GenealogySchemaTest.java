package place.icomb.archiver.genealogy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** What migration V21 holds the genealogy to, whatever writes it. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class GenealogySchemaTest {

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

  private int a;
  private int b;

  @BeforeEach
  void twoPeople() {
    a = jdbc.queryForObject("INSERT INTO gen_person DEFAULT VALUES RETURNING id", Integer.class);
    b = jdbc.queryForObject("INSERT INTO gen_person DEFAULT VALUES RETURNING id", Integer.class);
  }

  private void refused(String sql, Object... args) {
    assertThatThrownBy(() -> jdbc.update(sql, args))
        .as(sql)
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void newPeopleAreNumberedAboveTheLegacyIds() {
    assertThat(a).isGreaterThan(348);
    assertThat(jdbc.queryForObject("SELECT sex FROM gen_person WHERE id = ?", String.class, a))
        .isEqualTo("U");
    assertThat(jdbc.queryForObject("SELECT uid FROM gen_person WHERE id = ?", String.class, a))
        .hasSize(36);
  }

  @Test
  void personRules() {
    refused("UPDATE gen_person SET sex = 'X' WHERE id = ?", a);
    refused("UPDATE gen_person SET merged_into = id WHERE id = ?", a);
  }

  @Test
  void aPersonHasAtMostOnePrimaryName() {
    jdbc.update("INSERT INTO gen_name (person_id, given, is_primary) VALUES (?, 'A', true)", a);
    refused("INSERT INTO gen_name (person_id, given, is_primary) VALUES (?, 'B', true)", a);
    refused("INSERT INTO gen_name (person_id, is_primary) VALUES (?, false)", a);
    refused("INSERT INTO gen_name (person_id, given, type) VALUES (?, 'C', 'pet')", a);
  }

  @Test
  void couplesAndParentLinks() {
    refused("INSERT INTO gen_couple (person1_id, person2_id) VALUES (?, ?)", a, a);
    refused("INSERT INTO gen_couple (person1_id, person2_id, type) VALUES (?, ?, 'x')", a, b);
    refused("INSERT INTO gen_parent_child (parent_id, child_id) VALUES (?, ?)", a, a);
    jdbc.update("INSERT INTO gen_parent_child (parent_id, child_id) VALUES (?, ?)", a, b);
    refused("INSERT INTO gen_parent_child (parent_id, child_id, type) VALUES (?, ?, 'step')", a, b);
    refused("INSERT INTO gen_parent_child (parent_id, child_id, type) VALUES (?, ?, 'x')", b, a);
  }

  @Test
  void anEventBelongsToExactlyOnePersonOrCouple() {
    long couple =
        jdbc.queryForObject(
            "INSERT INTO gen_couple (person1_id, person2_id) VALUES (?, ?) RETURNING id",
            Long.class,
            a,
            b);
    refused("INSERT INTO gen_event (type) VALUES ('BIRT')");
    refused("INSERT INTO gen_event (person_id, couple_id, type) VALUES (?, ?, 'MARR')", a, couple);
    refused("INSERT INTO gen_event (person_id, type) VALUES (?, 'BIRTH')", a);
    jdbc.update("INSERT INTO gen_event (couple_id, type) VALUES (?, 'MARR')", couple);
  }

  @Test
  void aCitationHasExactlyOneTargetAndAQuay() {
    long source =
        jdbc.queryForObject("INSERT INTO gen_source (title) VALUES ('s') RETURNING id", Long.class);
    refused("INSERT INTO gen_citation (source_id) VALUES (?)", source);
    refused(
        "INSERT INTO gen_citation (source_id, person_id, couple_id) VALUES (?, ?, 1)", source, a);
    refused("INSERT INTO gen_citation (source_id, person_id, quality) VALUES (?, ?, 4)", source, a);
    refused("INSERT INTO gen_source (title) VALUES (NULL)");
  }

  @Test
  void aCropIsAllOrNothing() {
    long record = GenealogyFixtures.record(jdbc);
    long attachment =
        jdbc.queryForObject(
            "INSERT INTO attachment (record_id, role, path, mime) VALUES (?, 'page_image', 'p.jpg',"
                + " 'image/jpeg') RETURNING id",
            Long.class,
            record);
    long page =
        jdbc.queryForObject(
            "INSERT INTO page (record_id, seq, attachment_id) VALUES (?, 1, ?) RETURNING id",
            Long.class,
            record,
            attachment);
    refused("INSERT INTO gen_media (page_id, crop_left) VALUES (?, 1)", page);
    refused(
        "INSERT INTO gen_media (page_id, crop_left, crop_top, crop_width, crop_height)"
            + " VALUES (?, 0, 0, 0, 5)",
        page);
    long media =
        jdbc.queryForObject(
            "INSERT INTO gen_media (page_id) VALUES (?) RETURNING id", Long.class, page);
    jdbc.update(
        "INSERT INTO gen_media_person (media_id, person_id, is_portrait) VALUES (?, ?, true)",
        media,
        a);
    long other =
        jdbc.queryForObject(
            "INSERT INTO gen_media (page_id) VALUES (?) RETURNING id", Long.class, page);
    refused(
        "INSERT INTO gen_media_person (media_id, person_id, is_portrait) VALUES (?, ?, true)",
        other,
        a);
  }

  @Test
  void deletingAPageOrRecordStaysPossible() {
    long record = GenealogyFixtures.record(jdbc);
    long attachment =
        jdbc.queryForObject(
            "INSERT INTO attachment (record_id, role, path, mime) VALUES (?, 'page_image', 'q.jpg',"
                + " 'image/jpeg') RETURNING id",
            Long.class,
            record);
    long page =
        jdbc.queryForObject(
            "INSERT INTO page (record_id, seq, attachment_id) VALUES (?, 1, ?) RETURNING id",
            Long.class,
            record,
            attachment);
    long source =
        jdbc.queryForObject(
            "INSERT INTO gen_source (title, record_id) VALUES ('s', ?) RETURNING id",
            Long.class,
            record);
    long citation =
        jdbc.queryForObject(
            "INSERT INTO gen_citation (source_id, page_id, person_id) VALUES (?, ?, ?) RETURNING id",
            Long.class,
            source,
            page,
            a);
    long media =
        jdbc.queryForObject(
            "INSERT INTO gen_media (page_id) VALUES (?) RETURNING id", Long.class, page);
    jdbc.update("INSERT INTO gen_media_person (media_id, person_id) VALUES (?, ?)", media, a);
    jdbc.update(
        "INSERT INTO gen_person_record (person_id, record_id, page_id) VALUES (?, ?, ?)",
        a,
        record,
        page);

    jdbc.update("DELETE FROM page WHERE id = ?", page);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_media WHERE id = ?", Integer.class, media))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT page_id FROM gen_citation WHERE id = ?", Long.class, citation))
        .isNull();

    jdbc.update("DELETE FROM attachment WHERE record_id = ?", record);
    jdbc.update("DELETE FROM record WHERE id = ?", record);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_person_record WHERE person_id = ?", Integer.class, a))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT record_id FROM gen_source WHERE id = ?", Long.class, source))
        .isNull();
  }

  @Test
  void anAuditRowNeedsAReason() {
    refused(
        "INSERT INTO gen_change (entity, entity_id, op, reason, changed_by)"
            + " VALUES ('person', 1, 'update', '  ', 'x')");
    refused(
        "INSERT INTO gen_change (entity, entity_id, op, reason, changed_by)"
            + " VALUES ('person', 1, 'rename', 'r', 'x')");
  }
}
