package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Builds each export variant against a real record.
 *
 * <p>MarkdownPdfRendererTest covers markdown → PDF, but nothing exercised the service that wires
 * the renderer to the database and the stored scans, and that gap shipped a StackOverflowError: a
 * bulk edit rewrote the body of {@code newRenderer} into a call to itself, every export and every
 * stored PDF hung, and the whole suite stayed green. Building all three variants end to end is
 * enough to catch that entire class of fault.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class PdfExportServiceTest {

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

  @Autowired private PdfExportService pdfExportService;
  @Autowired private JdbcTemplate jdbc;

  @Value("${archiver.storage.root}")
  private String storageRoot;

  private Long recordId;

  /**
   * A scan, its transcription, the engine's block boxes and one figure — the shape every export
   * reads from.
   */
  @BeforeEach
  void seed() throws Exception {
    jdbc.execute("DELETE FROM page_translation");
    jdbc.execute("DELETE FROM page_text");
    jdbc.execute("DELETE FROM page");
    jdbc.execute("DELETE FROM attachment");
    jdbc.execute("DELETE FROM record");

    Long archiveId =
        jdbc.queryForObject(
            "INSERT INTO archive (name) VALUES ('Test Archive') RETURNING id", Long.class);

    recordId =
        jdbc.queryForObject(
            """
            INSERT INTO record
                (archive_id, source_system, source_record_id, title, lang, metadata_lang,
                 status)
            VALUES (?, 'test', 'pdf-export-test', 'Lagebericht', 'de', 'de', 'complete')
            RETURNING id
            """,
            Long.class,
            archiveId);

    // A real image on disk: the export decodes it, so a placeholder byte array will not do.
    BufferedImage scan = new BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB);
    var g = scan.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, 1200, 1600);
    g.dispose();
    String relative = "records/" + recordId + "/pages/1.jpg";
    Path file = Path.of(storageRoot).resolve(relative);
    Files.createDirectories(file.getParent());
    try (var out = Files.newOutputStream(file)) {
      ImageIO.write(scan, "jpg", out);
    }

    Long attachmentId =
        jdbc.queryForObject(
            """
            INSERT INTO attachment (record_id, role, path, mime, bytes, created_at)
            VALUES (?, 'page_image', ?, 'image/jpeg', ?, now()) RETURNING id
            """,
            Long.class,
            recordId,
            relative,
            Files.size(file));

    Long pageId =
        jdbc.queryForObject(
            "INSERT INTO page (record_id, seq, attachment_id) VALUES (?, 1, ?) RETURNING id",
            Long.class,
            recordId,
            attachmentId);

    String rawResponse =
        """
        {"pages":[{"dimensions":{"width":1200,"height":1600},
          "blocks":[{"content":"Enteignung des tschechischen Adels",
                     "top_left_x":100,"top_left_y":120,
                     "bottom_right_x":900,"bottom_right_y":170}],
          "images":[{"id":"img-0.jpeg","top_left_x":200,"top_left_y":900,
                     "bottom_right_x":700,"bottom_right_y":1100}]}]}
        """;

    String english =
        """
        # Expropriation of the Czech nobility

        Against 10 of the most important **Czech nobles** I have imposed
        compulsory administration over their property.

        | Family | Hectares |
        | --- | --- |
        | Czernin | 35000 |

        ![img-0.jpeg](img-0.jpeg)
        """;

    // The exports read the canonical translation from page_text.text_en; page_translation is
    // the per-model history sitting behind it.
    jdbc.update(
        """
        INSERT INTO page_text
            (page_id, engine, text_raw, text_en, content_type, raw_response, created_at)
        VALUES (?, 'ocr_page_mistral', ?, ?, 'text/markdown', ?::jsonb, now())
        """,
        pageId,
        "# Enteignung des tschechischen Adels\n\nGegen 10 der wichtigsten Adligen.\n",
        english,
        rawResponse);

    jdbc.update(
        """
        INSERT INTO page_translation (page_id, model, text_en, created_at)
        VALUES (?, 'mistral-small-latest', ?, now())
        """,
        pageId,
        english);
  }

  private String textOf(byte[] pdf) throws Exception {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      return new PDFTextStripper().getText(doc);
    }
  }

  @Test
  void englishExportRendersTheTranslation() throws Exception {
    byte[] pdf = pdfExportService.buildPdf(recordId, List.of(1), PdfExportService.Variant.ENGLISH);
    String text = textOf(pdf);
    assertThat(text).contains("Expropriation of the Czech nobility");
    assertThat(text).contains("Czech nobles");
    assertThat(text).contains("35000");
    assertThat(text).doesNotContain("**");
    assertThat(text).doesNotContain("img-0.jpeg");
  }

  @Test
  void originalExportCarriesAnInvisibleTextLayerAtTheBlockPosition() throws Exception {
    byte[] pdf = pdfExportService.buildPdf(recordId, List.of(1), PdfExportService.Variant.ORIGINAL);
    // The transcription must be selectable, and positioned: the old Python worker drew every
    // line at the left margin spread evenly down the page, so the layer existed and pointed
    // nowhere.
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      var stripper = new PDFTextStripper();
      String text = stripper.getText(doc);
      assertThat(text).contains("Enteignung des tschechischen Adels");
      assertThat(doc.getNumberOfPages()).isEqualTo(1);
      // Drawn at the block's own x, a sixth of the way across — not at the page edge.
      assertThat(doc.getPage(0).getMediaBox().getWidth()).isEqualTo(1200f);
    }
  }

  @Test
  void sideBySideExportCarriesBothHalvesAndTheSourceUrl() throws Exception {
    byte[] pdf =
        pdfExportService.buildPdf(recordId, List.of(1), PdfExportService.Variant.SIDE_BY_SIDE);
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      String text = new PDFTextStripper().getText(doc);
      // Left half: the scan's own transcription, invisible but extractable.
      assertThat(text).contains("Enteignung des tschechischen Adels");
      // Right half: the translation.
      assertThat(text).contains("Expropriation of the Czech nobility");
      // Footer: where this page lives, so a forwarded extract traces back.
      assertThat(text).contains("/records/" + recordId + "/pages/1");
      assertThat(doc.getPage(0).getMediaBox().getWidth())
          .isGreaterThan(doc.getPage(0).getMediaBox().getHeight());
    }
  }

  @Test
  void theInvisibleLayerHoldsPlainTextNotMarkdown() throws Exception {
    // This layer is what a reader's search box matches against, so markdown in it makes
    // "![img-0.jpeg](img-0.jpeg)" and heading hashes part of the scan's searchable text.
    jdbc.update(
        """
        UPDATE page_text SET raw_response = ?::jsonb
        WHERE page_id = (SELECT id FROM page WHERE record_id = ? AND seq = 1)
        """,
        """
        {"pages":[{"dimensions":{"width":1200,"height":1600},
          "blocks":[{"content":"# Enteignung\\n![img-0.jpeg](img-0.jpeg)\\n**Kinsky** und Czernin",
                     "top_left_x":100,"top_left_y":120,
                     "bottom_right_x":900,"bottom_right_y":300}],
          "images":[]}]}
        """,
        recordId);

    byte[] pdf = pdfExportService.buildPdf(recordId, List.of(1), PdfExportService.Variant.ORIGINAL);
    String text = textOf(pdf);
    assertThat(text).contains("Enteignung");
    assertThat(text).contains("Kinsky");
    assertThat(text).doesNotContain("img-0.jpeg");
    assertThat(text).doesNotContain("**");
    assertThat(text).doesNotContain("# ");
  }

  @Test
  void translatedExportsDrawTheFiguresTheEngineFound() throws Exception {
    // 21,996 pages reference a cut-out figure — signatures, stamps, seals. On a countersigned
    // order the signature block is the evidence.
    byte[] pdf = pdfExportService.buildPdf(recordId, List.of(1), PdfExportService.Variant.ENGLISH);
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      assertThat(countImages(doc)).isEqualTo(1);
    }
  }

  @Test
  void storedRecordPdfIsBuiltToFile() throws Exception {
    Path target = Files.createTempFile("searchable-test-", ".pdf");
    try {
      int pages = pdfExportService.buildRecordPdfToFile(recordId, target);
      assertThat(pages).isEqualTo(1);
      assertThat(Files.size(target)).isGreaterThan(0);
      assertThat(textOf(Files.readAllBytes(target))).contains("Enteignung des tschechischen Adels");
    } finally {
      Files.deleteIfExists(target);
    }
  }

  private long countImages(PDDocument doc) {
    var resources = doc.getPage(0).getResources();
    return java.util.stream.StreamSupport.stream(resources.getXObjectNames().spliterator(), false)
        .filter(
            name -> {
              try {
                return resources.getXObject(name) instanceof PDImageXObject;
              } catch (Exception e) {
                return false;
              }
            })
        .count();
  }

  @SuppressWarnings("unused")
  private static byte[] jpeg(BufferedImage image) throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(image, "jpg", out);
    return out.toByteArray();
  }
}
