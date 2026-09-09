package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

/**
 * The export has to survive this archive's actual content: Czech diacritics that PDFBox's built-in
 * fonts cannot encode, and markdown emitted by the OCR engine.
 */
class MarkdownPdfRendererTest {

  private String renderAndExtract(String markdown) throws Exception {
    byte[] bytes;
    try (PDDocument doc = new PDDocument()) {
      new MarkdownPdfRenderer(doc).renderPage(doc, markdown, "Page 1", null);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      bytes = out.toByteArray();
    }
    try (PDDocument read = Loader.loadPDF(bytes)) {
      return new PDFTextStripper().getText(read);
    }
  }

  @Test
  void rendersCzechAndGermanDiacritics() throws Exception {
    // 16,711 translations contain Czech diacritics and 63,927 contain German ones. WinAnsi
    // cannot encode ě š č ř ž ů at all, so a built-in font would throw on real content.
    String text = renderAndExtract("Černín z Chudenic, Přemysl, Žofie — Größe, Straße, Fürst");
    assertThat(text).contains("Černín");
    assertThat(text).contains("Přemysl");
    assertThat(text).contains("Größe");
  }

  @Test
  void keepsHeadingsListsAndTableColumns() throws Exception {
    String text =
        renderAndExtract(
            """
            # Geheime Staatspolizei

            Some introductory prose that should survive the rendering.

            - first item
            - second item

            | Name | Date |
            | --- | --- |
            | Czernin | 1943 |
            """);
    assertThat(text).contains("Geheime Staatspolizei");
    assertThat(text).contains("first item");
    assertThat(text).contains("Czernin");
    assertThat(text).contains("1943");
  }

  @Test
  void tablesAreDrawnAsColumnsNotPipedText() throws Exception {
    // Drawn as text with the pipes left in, a wage card's rows wrapped into a wall of
    // punctuation and every label/value pairing — the whole content of a form — was lost.
    String text =
        renderAndExtract(
            """
            | Beruf | Lohn | Bemerkung |
            | --- | --- | --- |
            | Landarbeiter | 24,50 | wöchentlich ausgezahlt |
            """);
    assertThat(text).contains("Landarbeiter");
    assertThat(text).contains("24,50");
    assertThat(text).contains("wöchentlich");
    assertThat(text).doesNotContain("|");
    assertThat(text).doesNotContain("---");
  }

  @Test
  void tableCellsStartInsideTheTextMargin() throws Exception {
    // The row draw omitted the box's x origin, so the first column landed at the page edge —
    // outside the margin and out of line with every other element on the page.
    var xs = new java.util.HashMap<String, Float>();
    try (PDDocument doc = new PDDocument()) {
      new MarkdownPdfRenderer(doc)
          .renderPage(
              doc,
              """
              Ordinary prose for comparison.

              | Unit | Litres |
              | --- | --- |
              | Wachbataillon | 925 |
              """,
              "Page 1",
              null);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      try (PDDocument read = Loader.loadPDF(out.toByteArray())) {
        new PDFTextStripper() {
          @Override
          protected void writeString(String text, java.util.List<TextPosition> positions) {
            if (!positions.isEmpty()) {
              xs.putIfAbsent(text.strip(), positions.get(0).getXDirAdj());
            }
          }
        }.getText(read);
      }
    }
    float prose =
        xs.entrySet().stream()
            .filter(e -> e.getKey().startsWith("Ordinary"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow();
    float firstCell =
        xs.entrySet().stream()
            .filter(e -> e.getKey().startsWith("Wachbataillon"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow();
    // Inside the margin, and within a cell's padding of where the prose starts.
    assertThat(firstCell).isGreaterThanOrEqualTo(prose);
    assertThat(firstCell).isLessThan(prose + 12f);
  }

  @Test
  void aCentredPageNumberIsNotABullet() throws Exception {
    // "- 5 -" is how nearly every typescript in this archive numbers itself, and it is
    // byte-identical to a markdown bullet.
    String text = renderAndExtract("- 5 -\n\n66\n\nsought to strengthen the Czech element.");
    assertThat(text).contains("- 5 -");
    assertThat(text).doesNotContain("\u2022");
  }

  @Test
  void anActualBulletStillGetsOne() throws Exception {
    String text = renderAndExtract("- first item\n- second item");
    assertThat(text).contains("\u2022");
  }

  @Test
  void headingsAreLargerThanBodyText() throws Exception {
    // Bold alone at body size did not read as a title in a half-page column.
    var sizes = new java.util.HashMap<String, Float>();
    try (PDDocument doc = new PDDocument()) {
      new MarkdownPdfRenderer(doc)
          .renderPage(doc, "# Lagebericht\n\nordinary prose follows here", "Page 1", null);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      try (PDDocument read = Loader.loadPDF(out.toByteArray())) {
        new PDFTextStripper() {
          @Override
          protected void writeString(String text, java.util.List<TextPosition> positions) {
            for (TextPosition p : positions) {
              sizes.merge(text.strip(), p.getFontSizeInPt(), Math::max);
            }
          }
        }.getText(read);
      }
    }
    float heading =
        sizes.entrySet().stream()
            .filter(e -> e.getKey().contains("Lagebericht"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow();
    float body =
        sizes.entrySet().stream()
            .filter(e -> e.getKey().contains("ordinary prose"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow();
    assertThat(heading).isGreaterThan(body);
  }

  @Test
  void boldRunsAreDrawnInTheBoldFace() throws Exception {
    // Fonts are collected per character, not per extracted line: the stripper hands back a
    // whole line at once, so taking the first position's font would report the regular face
    // for a line that mixes the two and the assertion would pass for the wrong reason.
    var chars = new StringBuilder();
    var fonts = new java.util.ArrayList<String>();
    try (PDDocument doc = new PDDocument()) {
      new MarkdownPdfRenderer(doc)
          .renderPage(doc, "plain words **emphasised words** plain again", "Page 1", null);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      try (PDDocument read = Loader.loadPDF(out.toByteArray())) {
        new PDFTextStripper() {
          @Override
          protected void writeString(String text, java.util.List<TextPosition> positions) {
            for (TextPosition p : positions) {
              chars.append(p.getUnicode());
              for (int i = 0; i < p.getUnicode().length(); i++) {
                fonts.add(p.getFont().getName());
              }
            }
          }
        }.getText(read);
      }
    }

    String rendered = chars.toString();
    int bolded = rendered.indexOf("emphasised");
    int plain = rendered.indexOf("plain");
    assertThat(bolded).isGreaterThan(-1);
    assertThat(fonts.get(bolded)).containsIgnoringCase("Bold");
    assertThat(fonts.get(plain)).doesNotContainIgnoringCase("Bold");
  }

  @Test
  void ocrFiguresAreDrawnIntoThePage() throws Exception {
    // 21,996 pages reference a figure the engine cut out — signatures, stamps, seals. On a
    // countersigned order the signature block is the evidence, so an export that silently
    // drops it loses the part that matters most.
    BufferedImage signature = new BufferedImage(240, 80, BufferedImage.TYPE_INT_RGB);
    var g = signature.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, 240, 80);
    g.dispose();
    var jpeg = new ByteArrayOutputStream();
    ImageIO.write(signature, "jpg", jpeg);

    OcrImageService images = mock(OcrImageService.class);
    when(images.cropAll(any()))
        .thenReturn(Map.of("img-0.jpeg", new OcrImageService.Crop(jpeg.toByteArray(), 240, 80)));

    try (PDDocument doc = new PDDocument()) {
      MarkdownPdfRenderer renderer = new MarkdownPdfRenderer(doc);
      renderer.setOcrImageService(images);
      renderer.renderPage(doc, "Signed:\n\n![img-0.jpeg](img-0.jpeg)\n", "Page 1", 42L);

      var resources = doc.getPage(0).getResources();
      long drawn =
          java.util.stream.StreamSupport.stream(resources.getXObjectNames().spliterator(), false)
              .filter(
                  name -> {
                    try {
                      return resources.getXObject(name) instanceof PDImageXObject;
                    } catch (Exception e) {
                      return false;
                    }
                  })
              .count();
      assertThat(drawn).isEqualTo(1);
    }
  }

  @Test
  void aFigureWithNoCropDoesNotBreakThePage() throws Exception {
    String text = renderAndExtract("before\n\n![img-9.jpeg](img-9.jpeg)\n\nafter");
    assertThat(text).contains("before");
    assertThat(text).contains("after");
    assertThat(text).doesNotContain("img-9.jpeg");
  }

  @Test
  void stripsMarkdownMarkersRatherThanPrintingThem() throws Exception {
    String text = renderAndExtract("**bold text** and an ![img-0.jpeg](img-0.jpeg) image");
    assertThat(text).contains("bold text");
    assertThat(text).doesNotContain("**");
    assertThat(text).doesNotContain("img-0.jpeg");
  }

  @Test
  void anEmptyPageStillProducesAPage() throws Exception {
    // One PDF page per source page, or the export stops lining up with the original and the
    // two cannot be read side by side.
    try (PDDocument doc = new PDDocument()) {
      int used = new MarkdownPdfRenderer(doc).renderPage(doc, "", "Page 7 [no text]", null);
      assertThat(used).isEqualTo(1);
      assertThat(doc.getNumberOfPages()).isEqualTo(1);
    }
  }

  @Test
  void longPageOverflowsOntoFurtherPages() throws Exception {
    String longText = ("A reasonably long line of translated archival prose. ").repeat(300);
    try (PDDocument doc = new PDDocument()) {
      int used = new MarkdownPdfRenderer(doc).renderPage(doc, longText, "Page 1", null);
      assertThat(used).isGreaterThan(1);
    }
  }
}
