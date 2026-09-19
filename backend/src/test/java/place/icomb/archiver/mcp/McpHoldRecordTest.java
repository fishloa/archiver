package place.icomb.archiver.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
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
import place.icomb.archiver.service.JobService;

/**
 * The one writing tool on the MCP interface.
 *
 * <p>Reading tools can be wrong and waste a question. This one changes the system, so what it
 * promises is checked against a real database: that a held record's jobs survive, that no worker
 * will claim them, and that releasing the hold lets them through.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class McpHoldRecordTest {

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

  @Autowired private ArchiverMcpTools tools;

  /** Signs the caller in as an administrator, as the MCP session filter does for one. */
  /** Other tests share this database; this suite reasons about one job kind, so clear it. */
  @BeforeEach
  void clearTranslateRecordJobs() {
    jdbc.update("DELETE FROM job WHERE kind = 'translate_record'");
  }

  @BeforeEach
  void signInAsAdmin() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "admin@example.com",
                "n/a",
                List.of(
                    new SimpleGrantedAuthority("ROLE_USER"),
                    new SimpleGrantedAuthority("ROLE_ADMIN"))));
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  private void signInAsOrdinaryUser() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "reader@example.com", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
  }

  @Autowired private JobService jobService;
  @Autowired private JdbcTemplate jdbc;

  private long seedRecordWithAJob() {
    Long archiveId =
        jdbc.queryForObject(
            "INSERT INTO archive (name) VALUES ('mcp hold test') RETURNING id", Long.class);
    Long recordId =
        jdbc.queryForObject(
            """
            INSERT INTO record (archive_id, source_system, source_record_id, title, status,
                                lang, metadata_lang)
            VALUES (?, 'test', ?, 'held record', 'translating', 'de', 'de')
            RETURNING id
            """,
            Long.class,
            archiveId,
            "mcp-" + System.nanoTime());
    Long attachmentId =
        jdbc.queryForObject(
            "INSERT INTO attachment (record_id, role, path, mime)"
                + " VALUES (?, 'page', 'test/p1.jpg', 'image/jpeg') RETURNING id",
            Long.class,
            recordId);
    jdbc.update(
        "INSERT INTO page (record_id, seq, attachment_id) VALUES (?, 1, ?)",
        recordId,
        attachmentId);
    jdbc.update(
        "INSERT INTO job (kind, record_id, status) VALUES ('translate_record', ?, 'pending')",
        recordId);
    return recordId;
  }

  @Test
  void holdingARecordKeepsItsWorkAndStopsItBeingClaimed() {
    long recordId = seedRecordWithAJob();

    Map<String, Object> out = tools.holdRecord(recordId, "overwritten transcription", null);

    assertThat(out).containsEntry("held", true).containsEntry("jobsWaiting", 1L);
    // Nothing is thrown away — the point of a hold over a cancel.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM job WHERE record_id = ? AND status = 'pending'",
                Long.class,
                recordId))
        .isEqualTo(1);
    assertThat(jobService.claimJob("translate_record")).isEmpty();
  }

  @Test
  void releasingTheHoldLetsTheWorkRun() {
    long recordId = seedRecordWithAJob();
    tools.holdRecord(recordId, "temporary", null);

    Map<String, Object> out = tools.holdRecord(recordId, "fixed", false);

    assertThat(out).containsEntry("held", false);
    assertThat(jobService.claimJob("translate_record"))
        .get()
        .extracting(place.icomb.archiver.model.Job::getRecordId)
        .isEqualTo(recordId);
  }

  @Test
  void theReasonIsStoredBecauseAHoldWithoutOneLooksLikeAStuckRecord() {
    long recordId = seedRecordWithAJob();

    tools.holdRecord(recordId, "Text Titan II run over a typescript", null);

    assertThat(
            jdbc.queryForObject(
                "SELECT ai_hold_reason FROM record WHERE id = ?", String.class, recordId))
        .isEqualTo("Text Titan II run over a typescript");
  }

  @Test
  void aRecordThatDoesNotExistIsReportedRatherThanSilentlyIgnored() {
    assertThat(tools.holdRecord(-1L, "nothing", null)).containsKey("error");
  }

  @Test
  void jobsCanBeListedByRecord() {
    long recordId = seedRecordWithAJob();

    var jobs = tools.listJobs(recordId, null, null, null, null);

    assertThat(jobs)
        .singleElement()
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .containsEntry("kind", "translate_record");
    // The id is the point of the tool: a cancel needs one.
    assertThat(jobs.get(0)).containsKeys("id", "status", "recordId");
  }

  // --- who may do this ---------------------------------------------------------------------

  @Test
  void anOrdinaryMcpUserCannotHoldARecord() {
    long recordId = seedRecordWithAJob();
    signInAsOrdinaryUser();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> tools.holdRecord(recordId, "not mine to hold", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("administrators only");

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM record WHERE id = ? AND ai_held_at IS NULL",
                Long.class,
                recordId))
        .isEqualTo(1);
  }

  @Test
  void anOrdinaryMcpUserCannotCancelOrReocr() {
    long recordId = seedRecordWithAJob();
    signInAsOrdinaryUser();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> tools.cancelJob(1L, "no"))
        .isInstanceOf(IllegalStateException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> tools.reocrPage(recordId, 1, null, "mistral"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void withNoAuthenticationAtAllTheAnswerIsStillNo() {
    // Fails closed. If the security context does not reach a tool invocation, the tool refuses
    // rather than running unguarded.
    long recordId = seedRecordWithAJob();
    SecurityContextHolder.clearContext();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> tools.holdRecord(recordId, "anonymous", null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void readingToolsStayOpenToAnOrdinaryUser() {
    long recordId = seedRecordWithAJob();
    signInAsOrdinaryUser();

    assertThat(tools.listJobs(recordId, null, null, null, null)).hasSize(1);
  }

  @Test
  void aHeldRecordRefusesNewOcrWorkRatherThanQueueingIt() {
    long recordId = seedRecordWithAJob();
    tools.holdRecord(recordId, "being repaired", null);

    Map<String, Object> out = tools.reocrPage(recordId, 1, null, "mistral");

    assertThat(out).containsKey("error");
    assertThat(String.valueOf(out.get("error"))).contains("AI hold");
  }
}
