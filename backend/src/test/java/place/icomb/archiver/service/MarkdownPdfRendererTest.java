package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * The export has to survive this archive's actual content: Czech diacritics that PDFBox's built-in
 * fonts cannot encode, and markdown emitted by the OCR engine.
 */
class MarkdownPdfRendererTest {

  private String renderAndExtract(String markdown) throws Exception {
    byte[] bytes;
    try (PDDocument doc = new PDDocument()) {
      new MarkdownPdfRenderer(doc).renderPage(doc, markdown, "Page 1");
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
    // Table pipes are kept: on a form or register the columns are the information.
    assertThat(text).contains("Czernin");
    assertThat(text).contains("1943");
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
      int used = new MarkdownPdfRenderer(doc).renderPage(doc, "", "Page 7 [no text]");
      assertThat(used).isEqualTo(1);
      assertThat(doc.getNumberOfPages()).isEqualTo(1);
    }
  }

  @Test
  void longPageOverflowsOntoFurtherPages() throws Exception {
    String longText = ("A reasonably long line of translated archival prose. ").repeat(300);
    try (PDDocument doc = new PDDocument()) {
      int used = new MarkdownPdfRenderer(doc).renderPage(doc, longText, "Page 1");
      assertThat(used).isGreaterThan(1);
    }
  }
}
