package place.icomb.archiver.genealogy;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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
import place.icomb.archiver.genealogy.legacy.V22__Import_legacy_genealogy;

/**
 * V28 against the queue the old pipeline left: its person-matching jobs must not run as paid hint
 * jobs once hints run only on request. The application starts at V27; the jobs are planted; then
 * V28 runs.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {"spring.flyway.target=27", "archiver.person-match.enabled=false"})
class HintsMigrationTest {

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

  private long job(long record, String kind, String status, int attempts) {
    return jdbc.queryForObject(
        "INSERT INTO job (kind, record_id, status, attempts) VALUES (?, ?, ?, ?) RETURNING id",
        Long.class,
        kind,
        record,
        status,
        attempts);
  }

  @Test
  void queuedMatchingJobsAreCancelledAndNothingElseIs() {
    long record = GenealogyFixtures.record(jdbc);
    long pending = job(record, "match_persons", "pending", 0);
    long claimed = job(record, "match_persons", "claimed", 1);
    long retryable = job(record, "match_persons", "failed", 1);
    long spent = job(record, "match_persons", "failed", 3);
    long done = job(record, "match_persons", "completed", 1);
    long embed = job(record, "embed_record", "pending", 0);

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .javaMigrations(new V22__Import_legacy_genealogy())
        .load()
        .migrate();

    for (long id : new long[] {pending, claimed, retryable}) {
      assertThat(jdbc.queryForMap("SELECT status, error FROM job WHERE id = ?", id))
          .containsEntry("status", "completed")
          .containsEntry("error", "cancelled: hints run only on request");
    }
    assertThat(jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, spent))
        .isEqualTo("failed");
    assertThat(jdbc.queryForObject("SELECT error FROM job WHERE id = ?", String.class, done))
        .isNull();
    assertThat(jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, embed))
        .isEqualTo("pending");
  }
}
