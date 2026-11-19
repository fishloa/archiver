package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class PipelineNoMatchingTest {

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

  @Autowired private PipelineStateMachine stateMachine;
  @Autowired private JdbcClient jdbc;

  private long seedRecord(String status) {
    Long archiveId =
        jdbc.sql("INSERT INTO archive (name, country) VALUES ('test archive', 'AT') RETURNING id")
            .query(Long.class)
            .single();
    return jdbc.sql(
            """
            INSERT INTO record (archive_id, source_system, source_record_id, title, status,
                                lang, metadata_lang, translation_quality)
            VALUES (?, 'test', ?, 'seeded', ?, 'de', 'de', 'bulk')
            RETURNING id
            """)
        .params(archiveId, "rec-" + System.nanoTime(), status)
        .query(Long.class)
        .single();
  }

  private String statusOf(long recordId) {
    return jdbc.sql("SELECT status FROM record WHERE id = ?")
        .params(recordId)
        .query(String.class)
        .single();
  }

  private long matchJobs(long recordId) {
    return jdbc.sql("SELECT count(*) FROM job WHERE record_id = ? AND kind = 'match_persons'")
        .params(recordId)
        .query(Long.class)
        .single();
  }

  @Test
  void embeddedRecordGoesStraightToComplete() {
    long recordId = seedRecord("embedding");
    jdbc.sql("INSERT INTO job (kind, record_id, status) VALUES ('embed_record', ?, 'completed')")
        .params(recordId)
        .update();

    stateMachine.autoAdvance(recordId);

    assertThat(statusOf(recordId)).isEqualTo("complete");
    assertThat(matchJobs(recordId)).isZero();
  }

  @Test
  void recordStuckInMatchingIsReleased() {
    long recordId = seedRecord("matching");

    stateMachine.autoAdvance(recordId);

    assertThat(statusOf(recordId)).isEqualTo("complete");
  }
}
