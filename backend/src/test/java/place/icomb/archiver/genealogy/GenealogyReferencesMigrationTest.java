package place.icomb.archiver.genealogy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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
import place.icomb.archiver.genealogy.legacy.V22__Import_legacy_genealogy;

/**
 * V23 against the rows production may hold: matches and users naming a folded section root, or
 * nobody at all. The application starts at V22; the rows are planted; then V23 runs.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.flyway.target=22")
class GenealogyReferencesMigrationTest {

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
  @Autowired private DataSource dataSource;

  private long page(long record, int seq) {
    long attachment =
        jdbc.queryForObject(
            "INSERT INTO attachment (record_id, role, path, mime) VALUES (?, 'page_image', ?,"
                + " 'image/jpeg') RETURNING id",
            Long.class,
            record,
            "v23-" + seq + ".jpg");
    return jdbc.queryForObject(
        "INSERT INTO page (record_id, seq, attachment_id) VALUES (?, ?, ?) RETURNING id",
        Long.class,
        record,
        seq,
        attachment);
  }

  private long user(String name, Integer personId) {
    return jdbc.queryForObject(
        "INSERT INTO app_user (display_name, role, family_tree_person_id) VALUES (?, 'user', ?)"
            + " RETURNING id",
        Long.class,
        name,
        personId);
  }

  @Test
  void referencesToTheFoldedRootsMoveAndReferencesToNobodyGo() {
    long record = GenealogyFixtures.record(jdbc);
    long first = page(record, 1);
    long second = page(record, 2);
    jdbc.update(
        "INSERT INTO page_person_match (page_id, person_id, person_name, score) VALUES"
            + " (?, 122, 'root', 0.5), (?, 76, 'twin', 0.6), (?, 227, 'root', 0.4),"
            + " (?, 999999, 'nobody', 0.1)",
        first,
        first,
        second,
        second);
    long toRoot = user("v23 root", 122);
    long toNobody = user("v23 nobody", 999999);
    long toAlexander = user("v23 alexander", 322);

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .javaMigrations(new V22__Import_legacy_genealogy())
        .load()
        .migrate();

    assertThat(
            jdbc.queryForList(
                "SELECT person_id, person_name FROM page_person_match WHERE page_id = ?", first))
        .containsExactly(Map.of("person_id", 76, "person_name", "twin"));
    assertThat(
            jdbc.queryForList(
                "SELECT person_id FROM page_person_match WHERE page_id = ?", Integer.class, second))
        .containsExactly(80);
    assertThat(familyTreePerson(toRoot)).isEqualTo(76);
    assertThat(familyTreePerson(toNobody)).isNull();
    assertThat(familyTreePerson(toAlexander)).isEqualTo(322);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO page_person_match (page_id, person_id, person_name, score)"
                        + " VALUES (?, 999999, 'nobody', 0.1)",
                    second))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private Integer familyTreePerson(long userId) {
    return jdbc.queryForObject(
        "SELECT family_tree_person_id FROM app_user WHERE id = ?", Integer.class, userId);
  }
}
