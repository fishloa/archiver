package place.icomb.archiver.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import place.icomb.archiver.model.ProviderBatch;

/**
 * Batches belong to one job kind, and every query has to say so.
 *
 * <p>Four orchestrators run concurrently — OCR, bulk translation, upgrade translation and record
 * metadata — over one table. A query that forgets the filter lets one of them reconcile, release or
 * fail another's in-flight work, which with two providers means releasing jobs someone else is
 * being billed for.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class ProviderBatchRepositoryTest {

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

  @Autowired private ProviderBatchRepository batches;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute("DELETE FROM job");
    jdbc.execute("DELETE FROM provider_batch");
  }

  private Long batch(String kind, String status, String providerJobId, int pages) {
    return jdbc.queryForObject(
        """
        INSERT INTO provider_batch (job_kind, status, page_count, provider_job_id, submitted_at)
        VALUES (?, ?, ?, ?, now()) RETURNING id
        """,
        Long.class,
        kind,
        status,
        pages,
        providerJobId);
  }

  @Test
  void findByStatusSeesOnlyItsOwnKind() {
    Long mine = batch("ocr_page_mistral", "submitting", null, 0);
    batch("translate_page", "submitting", null, 0);

    var found = batches.findByStatus(ProviderBatch.SUBMITTING, "ocr_page_mistral");

    assertThat(found).extracting(ProviderBatch::getId).containsExactly(mine);
  }

  @Test
  void collectablePollableAndTimedOutAreAlsoScopedByKind() {
    Long translate = batch("translate_page", "submitted", "provider-1", 10);
    Long ocr = batch("ocr_page_mistral", "submitted", "provider-2", 10);
    jdbc.update("UPDATE provider_batch SET output_file_id = 'out'");

    assertThat(batches.findCollectable("translate_page"))
        .extracting(ProviderBatch::getId)
        .containsExactly(translate);
    assertThat(batches.findPollable("ocr_page_mistral"))
        .extracting(ProviderBatch::getId)
        .containsExactly(ocr);
    assertThat(batches.findTimedOut(Instant.now().plus(1, ChronoUnit.HOURS), "translate_page"))
        .extracting(ProviderBatch::getId)
        .containsExactly(translate);
  }

  @Test
  void theRateLimitCountsOnlyItsOwnKind() {
    batch("ocr_page_mistral", "submitted", "p1", 500);
    batch("translate_page", "submitted", "p2", 900);

    assertThat(batches.pagesSubmittedInLastMinute("ocr_page_mistral")).isEqualTo(500);
    assertThat(batches.pagesSubmittedInLastMinute("translate_page")).isEqualTo(900);
  }

  @Test
  void anOlderSubmissionDoesNotCountAgainstTheRateLimit() {
    Long old = batch("ocr_page_mistral", "submitted", "p1", 400);
    jdbc.update(
        "UPDATE provider_batch SET submitted_at = now() - interval '5 minutes' WHERE id = ?", old);

    assertThat(batches.pagesSubmittedInLastMinute("ocr_page_mistral")).isZero();
  }
}
