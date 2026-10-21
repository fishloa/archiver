package place.icomb.archiver.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import place.icomb.archiver.PdfFixtures;
import place.icomb.archiver.service.PdfExportWorker;

/**
 * A large PDF cannot be carried in one MCP reply: anything past about 12 MB died in transit with a
 * "session expired" that said nothing about size. So the tool returns it in numbered parts that
 * join into the exact file, and says so. The part size is set tiny here so an ordinary fixture
 * spans several parts.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "archiver.mcp.download-part-bytes=4000")
class McpDownloadPartsTest {

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
  @Autowired private PdfExportWorker worker;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Path storageRoot;

  private String exportId;

  @BeforeEach
  void setUp() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "reader@example.com", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    jdbc.execute("DELETE FROM pdf_export");
    long record = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Parts record");
    for (int seq = 1; seq <= 4; seq++) {
      PdfFixtures.page(jdbc, storageRoot, record, seq, "English " + seq);
    }
    exportId = String.valueOf(tools.exportPdf(record, "original", null).get("id"));
    worker.runOnce();
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  private static String textOf(McpSchema.CallToolResult result) {
    return result.content().stream()
        .filter(c -> c instanceof McpSchema.TextContent)
        .map(c -> ((McpSchema.TextContent) c).text())
        .findFirst()
        .orElseThrow();
  }

  private static byte[] bytesOf(McpSchema.CallToolResult result) {
    var embedded =
        result.content().stream()
            .filter(c -> c instanceof McpSchema.EmbeddedResource)
            .map(c -> (McpSchema.EmbeddedResource) c)
            .findFirst()
            .orElseThrow();
    return java.util.Base64.getDecoder()
        .decode(((McpSchema.BlobResourceContents) embedded.resource()).blob());
  }

  @Test
  void aPdfTooLargeForOneReplyIsRefusedWithItsNumberOfParts() {
    assertThatThrownBy(() -> tools.downloadPdf(exportId, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("part=1")
        .hasMessageContaining("SHA-256");
  }

  @Test
  void thePartsJoinIntoTheExactFileAndItsChecksumMatches() throws Exception {
    McpSchema.CallToolResult first = tools.downloadPdf(exportId, 1);
    Matcher m =
        Pattern.compile("part 1 of (\\d+); whole PDF (\\d+) bytes, sha256 ([0-9a-f]{64})")
            .matcher(textOf(first));
    assertThat(m.find()).as(textOf(first)).isTrue();
    int parts = Integer.parseInt(m.group(1));
    long total = Long.parseLong(m.group(2));
    String sha = m.group(3);
    assertThat(parts).isGreaterThan(1);

    ByteArrayOutputStream joined = new ByteArrayOutputStream();
    for (int part = 1; part <= parts; part++) {
      McpSchema.CallToolResult r = part == 1 ? first : tools.downloadPdf(exportId, part);
      byte[] chunk = bytesOf(r);
      assertThat(chunk.length).isLessThanOrEqualTo(4000);
      joined.write(chunk);
    }

    byte[] whole = joined.toByteArray();
    assertThat((long) whole.length).isEqualTo(total);
    assertThat(new String(whole, 0, 4)).isEqualTo("%PDF");
    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(whole)))
        .isEqualTo(sha);
  }

  @Test
  void aPartThatDoesNotExistIsRefusedClearly() {
    assertThatThrownBy(() -> tools.downloadPdf(exportId, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no part 0");
    assertThatThrownBy(() -> tools.downloadPdf(exportId, 9999))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no part 9999");
  }
}
