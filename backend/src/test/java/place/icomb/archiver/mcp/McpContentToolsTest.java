package place.icomb.archiver.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
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
import place.icomb.archiver.PdfFixtures;
import place.icomb.archiver.service.PdfExportWorker;

/**
 * What a signed-in MCP user can do with a record beyond searching it: read a slice, read one page,
 * ask for a PDF of chosen pages, and fetch the PDF and the page image — all over the MCP OAuth
 * session, with no token and no browser.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class McpContentToolsTest {

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

  private long record;

  @BeforeEach
  void setUp() throws Exception {
    // An ordinary signed-in user: no admin role is needed for any of this.
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "reader@example.com", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    jdbc.execute("DELETE FROM pdf_export");
    record = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Magistrat file");
    for (int seq = 1; seq <= 4; seq++) {
      PdfFixtures.page(jdbc, storageRoot, record, seq, "English " + seq);
    }
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void aSliceOfARecordCarriesOnlyThoseNumberedPages() {
    Map<String, Object> out = tools.getDocument(record, 2, 3, null);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pages = (List<Map<String, Object>>) out.get("pages");
    assertThat(pages).extracting(p -> p.get("seq")).containsExactly(2, 3);
    assertThat(out.get("pageCount")).isEqualTo(4);
    assertThat(String.valueOf(out.get("fullTextEn")))
        .contains("English 2")
        .doesNotContain("English 4");
  }

  @Test
  void aWholeDocumentStillComesBackWhenNoSliceIsAsked() {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pages =
        (List<Map<String, Object>>) tools.getDocument(record, null, null, null).get("pages");
    assertThat(pages).hasSize(4);
  }

  @Test
  void textCanBeLeftOutWhenOnlyTheStructureIsWanted() {
    Map<String, Object> out = tools.getDocument(record, null, null, false);
    assertThat(out).doesNotContainKeys("fullText", "fullTextEn");
    assertThat(out.get("pages")).isNotNull();
  }

  @Test
  void onePageCarriesItsTextAndWhereToFetchItsImage() {
    Map<String, Object> page = tools.getPage(record, 3, null);

    assertThat(page.get("seq")).isEqualTo(3);
    assertThat(String.valueOf(page.get("textEn"))).contains("English 3");
    assertThat(page).containsKeys("pageId", "contentType", "imageUrl");
    assertThat(String.valueOf(page.get("imageUrl"))).contains("/api/files/");
  }

  @Test
  void aMissingPageIsAnErrorNotAnEmptyAnswer() {
    assertThatThrownBy(() -> tools.getPage(record, 99, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("99");
  }

  @Test
  void aPdfOfChosenPagesIsRequestedPolledAndFetched() {
    Map<String, Object> asked = tools.exportPdf(record, "original", "2");
    assertThat(asked.get("state")).isEqualTo("queued");
    assertThat(asked.get("pageCount")).isEqualTo(1);
    String id = String.valueOf(asked.get("id"));

    worker.runOnce();

    Map<String, Object> ready = tools.getPdfExport(id);
    assertThat(ready.get("state")).isEqualTo("ready");
    assertThat(String.valueOf(ready.get("downloadUrl")))
        .endsWith("/api/pdf-exports/" + id + "/file");
  }

  @Test
  void anExportOfNoSuchPagesOrRecordIsRefusedClearly() {
    assertThatThrownBy(() -> tools.exportPdf(record, "original", "50-60"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.exportPdf(-1L, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tools.getPdfExport("nope"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aReadyPdfComesBackAsAnEmbeddedFileSoAClientCanSaveIt() {
    String id = String.valueOf(tools.exportPdf(record, "original", "2").get("id"));
    worker.runOnce();

    io.modelcontextprotocol.spec.McpSchema.CallToolResult result = tools.downloadPdf(id);

    assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
    var embedded =
        result.content().stream()
            .filter(c -> c instanceof io.modelcontextprotocol.spec.McpSchema.EmbeddedResource)
            .map(c -> (io.modelcontextprotocol.spec.McpSchema.EmbeddedResource) c)
            .findFirst()
            .orElseThrow();
    var blob = (io.modelcontextprotocol.spec.McpSchema.BlobResourceContents) embedded.resource();
    assertThat(blob.mimeType()).isEqualTo("application/pdf");
    byte[] bytes = java.util.Base64.getDecoder().decode(blob.blob());
    assertThat(new String(bytes, 0, 4)).isEqualTo("%PDF");
  }

  @Test
  void aPdfThatIsNotReadyIsRefusedClearly() {
    String id = String.valueOf(tools.exportPdf(record, "original", "3").get("id"));

    assertThatThrownBy(() -> tools.downloadPdf(id))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prepared");
  }
}
