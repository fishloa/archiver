package place.icomb.archiver.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * Renders a page's markdown onto PDF pages.
 *
 * <p>Fonts are embedded rather than PDFBox's built-ins: those use WinAnsi encoding, which cannot
 * represent the Czech diacritics (ě š č ř ž ů) present in 16,711 of this archive's translations,
 * and throw rather than degrade. DejaVu covers Latin-1 and Latin-2 and is public domain.
 *
 * <p>Deliberately a small subset of markdown — headings, paragraphs, lists and tables — because
 * that is what the OCR engine emits. Anything unrecognised is drawn as plain text rather than
 * dropped: losing content silently is the failure that matters here.
 */
public class MarkdownPdfRenderer {

  private static final PDRectangle PAGE_SIZE = PDRectangle.A4;
  private static final float MARGIN = 56f;
  private static final float BODY_SIZE = 9.5f;
  private static final float LEADING = 12.5f;

  private final PDFont regular;
  private final PDFont bold;

  public MarkdownPdfRenderer(PDDocument doc) throws IOException {
    this.regular =
        PDType0Font.load(doc, getClass().getResourceAsStream("/fonts/DejaVuSans.ttf"), true);
    this.bold =
        PDType0Font.load(doc, getClass().getResourceAsStream("/fonts/DejaVuSans-Bold.ttf"), true);
  }

  /**
   * Draws one source page's markdown, starting a new PDF page and continuing onto further pages if
   * it overflows.
   *
   * @return PDF pages used — always at least one, so an empty source page still produces a page and
   *     the export stays aligned with the original
   */
  public int renderPage(PDDocument doc, String markdown, String header) throws IOException {
    List<Line> lines = layout(markdown == null ? "" : markdown);
    int pagesUsed = 0;
    int i = 0;

    do {
      PDPage page = new PDPage(PAGE_SIZE);
      doc.addPage(page);
      pagesUsed++;
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        // Identifies which original page this is, so the two can be held side by side.
        cs.beginText();
        cs.setFont(regular, 7.5f);
        cs.newLineAtOffset(MARGIN, PAGE_SIZE.getHeight() - MARGIN + 16f);
        cs.showText(sanitise(header + (pagesUsed > 1 ? "  (cont.)" : "")));
        cs.endText();

        float y = PAGE_SIZE.getHeight() - MARGIN;
        while (i < lines.size() && y > MARGIN) {
          Line line = lines.get(i);
          if (!line.text().isEmpty()) {
            cs.beginText();
            cs.setFont(line.bold() ? bold : regular, line.size());
            cs.newLineAtOffset(MARGIN + line.indent(), y);
            cs.showText(sanitise(line.text()));
            cs.endText();
          }
          y -= line.leading();
          i++;
        }
      }
    } while (i < lines.size());

    return pagesUsed;
  }

  private record Line(String text, float size, boolean bold, float indent, float leading) {}

  private List<Line> layout(String markdown) throws IOException {
    List<Line> out = new ArrayList<>();
    float width = PAGE_SIZE.getWidth() - 2 * MARGIN;

    for (String raw : markdown.split("\n", -1)) {
      String line = raw.stripTrailing();

      if (line.isBlank()) {
        out.add(new Line("", BODY_SIZE, false, 0, LEADING * 0.6f));
        continue;
      }

      int hashes = 0;
      while (hashes < line.length() && line.charAt(hashes) == '#') hashes++;
      if (hashes > 0 && hashes <= 6 && hashes < line.length() && line.charAt(hashes) == ' ') {
        float size = Math.max(BODY_SIZE, 15f - (hashes - 1) * 1.5f);
        for (String w : wrap(stripInline(line.substring(hashes + 1).trim()), bold, size, width)) {
          out.add(new Line(w, size, true, 0, size * 1.45f));
        }
        out.add(new Line("", size, false, 0, size * 0.5f));
        continue;
      }

      String t = line.trim();
      // Table rows keep their pipes: the columns are the information on a form or register page.
      if (t.startsWith("|") && t.endsWith("|")) {
        for (String w : wrap(stripInline(t), regular, BODY_SIZE - 1f, width)) {
          out.add(new Line(w, BODY_SIZE - 1f, false, 0, LEADING * 0.92f));
        }
        continue;
      }

      String bulletBody = null;
      float indent = 0;
      if (t.startsWith("- ") || t.startsWith("* ")) {
        bulletBody = "• " + t.substring(2);
        indent = 10f;
      } else if (t.matches("^\\d+[.)]\\s+.*")) {
        bulletBody = t;
        indent = 10f;
      }
      if (bulletBody != null) {
        List<String> wrapped = wrap(stripInline(bulletBody), regular, BODY_SIZE, width - indent);
        for (int k = 0; k < wrapped.size(); k++) {
          out.add(
              new Line(wrapped.get(k), BODY_SIZE, false, k == 0 ? indent : indent + 10f, LEADING));
        }
        continue;
      }

      for (String w : wrap(stripInline(line), regular, BODY_SIZE, width)) {
        out.add(new Line(w, BODY_SIZE, false, 0, LEADING));
      }
    }
    return out;
  }

  /** Removes inline markers that would otherwise be drawn as literal text. */
  static String stripInline(String s) {
    return s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
        .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .strip();
  }

  private List<String> wrap(String text, PDFont font, float size, float maxWidth)
      throws IOException {
    List<String> out = new ArrayList<>();
    if (text.isEmpty()) {
      out.add("");
      return out;
    }
    StringBuilder current = new StringBuilder();
    for (String word : text.split("\\s+")) {
      String candidate = current.isEmpty() ? word : current + " " + word;
      if (current.isEmpty() || stringWidth(font, size, candidate) <= maxWidth) {
        current.setLength(0);
        current.append(candidate);
      } else {
        out.add(current.toString());
        current.setLength(0);
        current.append(word);
      }
    }
    if (current.length() > 0) {
      out.add(current.toString());
    }
    return out;
  }

  private float stringWidth(PDFont font, float size, String s) throws IOException {
    return font.getStringWidth(sanitise(s)) / 1000f * size;
  }

  /**
   * Replaces characters the font cannot draw.
   *
   * <p>PDFBox throws on an unmappable glyph, which would fail an entire export for one stray
   * character in one page. A visible placeholder is better than no document.
   */
  private String sanitise(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); ) {
      int cp = s.codePointAt(i);
      String ch = new String(Character.toChars(cp));
      i += Character.charCount(cp);
      if (cp == '\t') {
        sb.append("    ");
        continue;
      }
      if (cp < 0x20) continue;
      try {
        regular.getStringWidth(ch);
        sb.append(ch);
      } catch (Exception e) {
        sb.append('?');
      }
    }
    return sb.toString();
  }
}
