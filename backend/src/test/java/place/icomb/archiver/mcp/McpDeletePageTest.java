package place.icomb.archiver.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
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

/**
 * Deleting a page is the one thing on the MCP interface that cannot be undone, so what it promises
 * is checked against a real database: the right page goes, the gap closes, the count is right, and
 * nobody but an administrator, and nobody without a reason, can do it.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class McpDeletePageTest {

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
  @Autowired private JdbcTemplate jdbc;

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

  /** A record of three pages whose text is "p1", "p2", "p3", so the survivor can be told apart. */
  private long seedThreePageRecord() {
    Long archiveId =
        jdbc.queryForObject(
            "INSERT INTO archive (name) VALUES ('mcp delete page test') RETURNING id", Long.class);
    Long recordId =
        jdbc.queryForObject(
            """
            INSERT INTO record (archive_id, source_system, source_record_id, title, status,
                                lang, metadata_lang, page_count)
            VALUES (?, 'test', ?, 'three pages', 'complete', 'de', 'de', 3)
            RETURNING id
            """,
            Long.class,
            archiveId,
            "mcp-del-" + System.nanoTime());
    for (int seq = 1; seq <= 3; seq++) {
      Long attachmentId =
          jdbc.queryForObject(
              "INSERT INTO attachment (record_id, role, path, mime)"
                  + " VALUES (?, 'page', ?, 'image/jpeg') RETURNING id",
              Long.class,
              recordId,
              "test/del-" + recordId + "-p" + seq + ".jpg");
      jdbc.update(
          "INSERT INTO page (record_id, seq, attachment_id) VALUES (?, ?, ?)",
          recordId,
          seq,
          attachmentId);
    }
    return recordId;
  }

  private List<Integer> seqs(long recordId) {
    return jdbc.queryForList(
        "SELECT seq FROM page WHERE record_id = ? ORDER BY seq", Integer.class, recordId);
  }

  @Test
  void deletingTheMiddlePageClosesTheGapAndFixesTheCount() {
    long recordId = seedThreePageRecord();

    Map<String, Object> out = tools.deletePage(recordId, 2, "blank sheet");

    assertThat(out).containsEntry("removed", 2).containsEntry("pageCount", 2);
    assertThat(seqs(recordId)).containsExactly(1, 2);
    assertThat(
            jdbc.queryForObject(
                "SELECT page_count FROM record WHERE id = ?", Integer.class, recordId))
        .isEqualTo(2);
  }

  @Test
  void theRightPageGoes() {
    long recordId = seedThreePageRecord();

    tools.deletePage(recordId, 1, "duplicate");

    // The former page 3 is now page 2: what is left is p2 and p3, not p1 and p2.
    List<String> paths =
        jdbc.queryForList(
            "SELECT a.path FROM page p JOIN attachment a ON a.id = p.attachment_id"
                + " WHERE p.record_id = ? ORDER BY p.seq",
            String.class,
            recordId);
    assertThat(paths).hasSize(2);
    assertThat(paths.get(0)).endsWith("-p2.jpg");
    assertThat(paths.get(1)).endsWith("-p3.jpg");
  }

  @Test
  void aPageThatDoesNotExistIsReportedAndNothingChanges() {
    long recordId = seedThreePageRecord();

    Map<String, Object> out = tools.deletePage(recordId, 9, "typo");

    assertThat(out).containsKey("error");
    assertThat(seqs(recordId)).containsExactly(1, 2, 3);
  }

  @Test
  void aDeletionWithoutAReasonIsRefusedBecauseItCannotBeUndone() {
    long recordId = seedThreePageRecord();

    assertThat(tools.deletePage(recordId, 1, "  ")).containsKey("error");
    assertThat(tools.deletePage(recordId, 1, null)).containsKey("error");
    assertThat(seqs(recordId)).containsExactly(1, 2, 3);
  }

  @Test
  void anOrdinaryMcpUserCannotDeleteAPage() {
    long recordId = seedThreePageRecord();
    signInAsOrdinaryUser();

    assertThatThrownBy(() -> tools.deletePage(recordId, 1, "not mine to delete"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("administrators only");

    assertThat(seqs(recordId)).containsExactly(1, 2, 3);
  }

  @Test
  void withNoAuthenticationAtAllTheAnswerIsStillNo() {
    long recordId = seedThreePageRecord();
    SecurityContextHolder.clearContext();

    assertThatThrownBy(() -> tools.deletePage(recordId, 1, "anonymous"))
        .isInstanceOf(IllegalStateException.class);

    assertThat(seqs(recordId)).containsExactly(1, 2, 3);
  }
}
