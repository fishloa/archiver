package place.icomb.archiver.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Renders a page's markdown onto PDF pages.
 *
 * <p>Fonts are embedded rather than PDFBox's built-ins: those use WinAnsi encoding, which cannot
 * represent the Czech diacritics (ě š č ř ž ů) present in 16,711 of this archive's translations,
 * and throw rather than degrade. DejaVu covers Latin-1 and Latin-2 and is public domain.
 *
 * <p>Deliberately a subset of markdown — headings, paragraphs, lists, tables, bold runs and the
 * figures the OCR engine cut out — because that is what the engine emits. 43,704 pages carry
 * headings, 14,328 bold, 11,861 tables and 21,996 figures, so all four earn their code. Anything
 * unrecognised is drawn as plain text rather than dropped: losing content silently is the failure
 * that matters here.
 *
 * <p>Layout is flat: every element knows its own height, so a caller can fill a box, stop, and
 * resume on the next page without understanding what the elements are. Tables paginate a row at a
 * time for the same reason.
 */
public class MarkdownPdfRenderer {

  private static final PDRectangle PAGE_SIZE = PDRectangle.A4;
  private static final float MARGIN = 56f;
  private static final float BODY_SIZE = 9.5f;
  private static final float LEADING = 12.5f;
  private static final float CELL_PAD = 3f;

  private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
  private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\(([^)]*)\\)");
  private static final Pattern TABLE_DIVIDER = Pattern.compile("^\\|[\\s:|-]+\\|$");

  /**
   * A typescript's centred page number, which is byte-identical to a markdown bullet.
   *
   * <p>"- 5 -" is how nearly every page in this archive numbers itself, and rendering it as a
   * bullet put a bullet at the top of thousands of pages.
   */
  private static final Pattern PAGE_NUMBER = Pattern.compile("^[-–—]\\s*\\d{1,4}\\s*[-–—]$");

  private final PDDocument doc;
  private final PDFont regular;
  private final PDFont bold;

  public MarkdownPdfRenderer(PDDocument doc) throws IOException {
    this.doc = doc;
    this.regular =
        PDType0Font.load(doc, getClass().getResourceAsStream("/fonts/DejaVuSans.ttf"), true);
    this.bold =
        PDType0Font.load(doc, getClass().getResourceAsStream("/fonts/DejaVuSans-Bold.ttf"), true);
  }

  // -------------------------------------------------------------------------
  // Element model
  // -------------------------------------------------------------------------

  /** A drawable piece of the document. Height is what pagination reserves for it. */
  public sealed interface El {
    float height();
  }

  /** A run of text sharing one font and size. */
  public record Span(String text, boolean bold, float size) {}

  /** One line of text, possibly mixing regular and bold runs. */
  public record TextEl(List<Span> spans, float indent, float height) implements El {}

  /** A horizontal rule, used under top-level headings and around table headers. */
  public record RuleEl(float width, float thickness, float height) implements El {}

  /** A figure the OCR engine cut out of the scan. */
  public record ImageEl(PDImageXObject image, float width, float drawHeight, float height)
      implements El {}

  /** One table row; cells are pre-wrapped and pre-positioned. */
  public record CellEl(List<String> lines, float x, float width) {}

  /** A table row, drawn as a unit so a row never splits across pages. */
  public record RowEl(List<CellEl> cells, boolean header, float height) implements El {}

  // -------------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------------

  /**
   * Draws one source page's markdown, starting a new PDF page and continuing onto further pages if
   * it overflows.
   *
   * @return PDF pages used — always at least one, so an empty source page still produces a page and
   *     the export stays aligned with the original
   */
  public int renderPage(PDDocument doc, String markdown, String header, Long pageId)
      throws IOException {
    float width = PAGE_SIZE.getWidth() - 2 * MARGIN;
    float top = PAGE_SIZE.getHeight() - MARGIN;
    float bottom = MARGIN;
    List<El> els = layoutTo(markdown, width, top - bottom, pageId);

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

        i = drawElements(cs, els, i, MARGIN, top, bottom);
      }
    } while (i < els.size());

    return pagesUsed;
  }

  /** Lays markdown out to a given width, for callers drawing into their own box. */
  public List<El> layoutTo(String markdown, float width, float maxImageHeight, Long pageId)
      throws IOException {
    Map<String, OcrImageService.Crop> crops =
        pageId == null || ocrImageService == null ? Map.of() : ocrImageService.cropAll(pageId);
    return layout(markdown == null ? "" : markdown, width, maxImageHeight, crops);
  }

  /**
   * Draws laid-out elements into a box, stopping when the next one will not fit.
   *
   * @return index of the first element that did not fit, so a caller can continue on another page
   */
  public int drawElements(
      PDPageContentStream cs, List<El> els, int from, float x, float yTop, float yBottom)
      throws IOException {
    float y = yTop;
    int i = from;
    while (i < els.size()) {
      El el = els.get(i);
      // Always draw at least one element, or an element taller than the box loops forever.
      if (y - el.height() < yBottom && i > from) {
        break;
      }
      draw(cs, el, x, y);
      y -= el.height();
      i++;
    }
    return i;
  }

  public PDFont regularFont() {
    return regular;
  }

  /** Width of a string at a size, for fitting invisible text to a scanned block. */
  public float widthOf(String s, float size) throws IOException {
    return stringWidth(regular, size, s);
  }

  public String forDrawing(String s) {
    return sanitise(s);
  }

  /**
   * Figure source, set by the export service.
   *
   * <p>A field rather than a constructor argument because the renderer is also constructed in
   * contexts that have no page to crop from — a null service simply renders text.
   */
  private OcrImageService ocrImageService;

  public void setOcrImageService(OcrImageService service) {
    this.ocrImageService = service;
  }

  // -------------------------------------------------------------------------
  // Drawing
  // -------------------------------------------------------------------------

  private void draw(PDPageContentStream cs, El el, float x, float y) throws IOException {
    switch (el) {
      case TextEl t -> drawSpans(cs, t.spans(), x + t.indent(), y - t.height() + descent(t));
      case RuleEl r -> {
        cs.setLineWidth(r.thickness());
        float ry = y - r.height() / 2f;
        cs.moveTo(x, ry);
        cs.lineTo(x + r.width(), ry);
        cs.stroke();
      }
      case ImageEl im ->
          cs.drawImage(im.image(), x, y - im.drawHeight() - 2f, im.width(), im.drawHeight());
      case RowEl row -> {
        for (CellEl cell : row.cells()) {
          float cy = y - CELL_PAD;
          for (String line : cell.lines()) {
            cy -= BODY_SIZE;
            drawSpans(
                cs,
                List.of(new Span(line, row.header(), BODY_SIZE - 0.5f)),
                x + cell.x() + CELL_PAD,
                cy);
            cy -= (LEADING - BODY_SIZE) * 0.5f;
          }
        }
      }
    }
  }

  /** Baseline offset within a text element's reserved height. */
  private float descent(TextEl t) {
    float size = t.spans().isEmpty() ? BODY_SIZE : t.spans().get(0).size();
    return Math.max(0, (t.height() - size) * 0.35f);
  }

  private void drawSpans(PDPageContentStream cs, List<Span> spans, float x, float y)
      throws IOException {
    float cursor = x;
    for (Span span : spans) {
      String text = sanitise(span.text());
      if (text.isEmpty()) continue;
      PDFont font = span.bold() ? bold : regular;
      cs.beginText();
      cs.setFont(font, span.size());
      cs.newLineAtOffset(cursor, y);
      cs.showText(text);
      cs.endText();
      cursor += font.getStringWidth(text) / 1000f * span.size();
    }
  }

  // -------------------------------------------------------------------------
  // Layout
  // -------------------------------------------------------------------------

  private List<El> layout(
      String markdown, float width, float maxImageHeight, Map<String, OcrImageService.Crop> crops)
      throws IOException {
    List<El> out = new ArrayList<>();
    String[] raw = markdown.split("\n", -1);

    for (int i = 0; i < raw.length; i++) {
      String line = raw[i].stripTrailing();

      if (line.isBlank()) {
        out.add(new TextEl(List.of(), 0, LEADING * 0.55f));
        continue;
      }

      // A figure on its own line, drawn where the engine found it.
      Matcher img = IMAGE.matcher(line.trim());
      if (img.matches()) {
        ImageEl el = imageElement(img.group(1), width, maxImageHeight, crops);
        if (el != null) {
          out.add(el);
          continue;
        }
        // No crop available — fall through so a caption or alt text is not lost.
      }

      int hashes = 0;
      while (hashes < line.length() && line.charAt(hashes) == '#') hashes++;
      if (hashes > 0 && hashes <= 6 && hashes < line.length() && line.charAt(hashes) == ' ') {
        addHeading(out, line.substring(hashes + 1).trim(), hashes, width);
        continue;
      }

      String t = line.trim();

      // A table: gather every consecutive pipe row, then lay the block out as columns.
      if (isTableRow(t)) {
        int end = i;
        List<String> rows = new ArrayList<>();
        while (end < raw.length && isTableRow(raw[end].trim())) {
          rows.add(raw[end].trim());
          end++;
        }
        addTable(out, rows, width);
        i = end - 1;
        continue;
      }

      String bulletBody = null;
      float indent = 0;
      if (PAGE_NUMBER.matcher(t).matches()) {
        out.add(new TextEl(parseSpans(t, BODY_SIZE), 0, LEADING));
        continue;
      }
      if (t.startsWith("- ") || t.startsWith("* ")) {
        bulletBody = "• " + t.substring(2);
        indent = 10f;
      } else if (t.matches("^\\d+[.)]\\s+.*")) {
        bulletBody = t;
        indent = 10f;
      }
      if (bulletBody != null) {
        List<List<Span>> wrapped = wrapSpans(parseSpans(bulletBody, BODY_SIZE), width - indent);
        for (int k = 0; k < wrapped.size(); k++) {
          out.add(new TextEl(wrapped.get(k), k == 0 ? indent : indent + 10f, LEADING));
        }
        continue;
      }

      for (List<Span> w : wrapSpans(parseSpans(line, BODY_SIZE), width)) {
        out.add(new TextEl(w, 0, LEADING));
      }
    }
    return out;
  }

  /**
   * A heading, sized by level and set off by space.
   *
   * <p>Bold alone did not read as a title at body size in a half-page column; the size step, the
   * space above and the rule under the top two levels are what make a report's section headings
   * scannable rather than just slightly darker text.
   */
  private void addHeading(List<El> out, String text, int level, float width) throws IOException {
    float size =
        switch (level) {
          case 1 -> 16f;
          case 2 -> 13f;
          case 3 -> 11.5f;
          default -> 10.5f;
        };
    // Space above, but not a gap at the very top of a page.
    if (!out.isEmpty()) {
      out.add(new TextEl(List.of(), 0, size * 0.7f));
    }
    for (List<Span> w : wrapSpans(parseSpans(text, size, true), width)) {
      out.add(new TextEl(w, 0, size * 1.35f));
    }
    if (level <= 2) {
      out.add(new RuleEl(width, level == 1 ? 0.8f : 0.4f, 5f));
    }
    out.add(new TextEl(List.of(), 0, size * 0.35f));
  }

  private boolean isTableRow(String t) {
    return t.startsWith("|") && t.endsWith("|") && t.length() > 2;
  }

  /**
   * Lays a pipe table out as real columns.
   *
   * <p>Drawn as text with the pipes left in, a wage card's columns wrapped into a wall of
   * punctuation and the label/value pairing — the entire content of a form — became unreadable.
   * Columns are sized by their widest cell, then scaled to fit the available width.
   */
  private void addTable(List<El> out, List<String> rows, float width) throws IOException {
    List<List<String>> cells = new ArrayList<>();
    boolean sawDivider = false;
    for (String row : rows) {
      if (TABLE_DIVIDER.matcher(row).matches()) {
        sawDivider = true;
        continue;
      }
      List<String> cols = new ArrayList<>();
      for (String c : row.substring(1, row.length() - 1).split("\\|", -1)) {
        cols.add(stripInline(c.trim()));
      }
      cells.add(cols);
    }
    if (cells.isEmpty()) {
      return;
    }

    int columns = cells.stream().mapToInt(List::size).max().orElse(1);
    float[] natural = new float[columns];
    for (List<String> row : cells) {
      for (int c = 0; c < row.size(); c++) {
        natural[c] = Math.max(natural[c], stringWidth(regular, BODY_SIZE - 0.5f, row.get(c)));
      }
    }
    float total = 0;
    for (float n : natural) total += n + 2 * CELL_PAD;
    float[] widths = new float[columns];
    for (int c = 0; c < columns; c++) {
      // Proportional to content, so a narrow date column does not get the same room as a remark.
      widths[c] = total <= 0 ? width / columns : (natural[c] + 2 * CELL_PAD) / total * width;
      widths[c] = Math.max(widths[c], 24f);
    }
    // Renormalise after the minimum-width floor.
    float sum = 0;
    for (float w : widths) sum += w;
    if (sum > width) {
      for (int c = 0; c < columns; c++) widths[c] = widths[c] / sum * width;
    }

    out.add(new TextEl(List.of(), 0, 3f));
    boolean headerRow = sawDivider;
    for (List<String> row : cells) {
      List<CellEl> built = new ArrayList<>();
      int maxLines = 1;
      float x = 0;
      for (int c = 0; c < columns; c++) {
        String content = c < row.size() ? row.get(c) : "";
        List<String> wrapped = wrap(content, regular, BODY_SIZE - 0.5f, widths[c] - 2 * CELL_PAD);
        maxLines = Math.max(maxLines, wrapped.size());
        built.add(new CellEl(wrapped, x, widths[c]));
        x += widths[c];
      }
      out.add(new RowEl(built, headerRow, maxLines * LEADING * 0.92f + 2 * CELL_PAD));
      if (headerRow) {
        out.add(new RuleEl(width, 0.5f, 3f));
        headerRow = false;
      }
    }
    out.add(new TextEl(List.of(), 0, 5f));
  }

  /** Builds a drawable figure, scaled to fit the column and the remaining page. */
  private ImageEl imageElement(
      String ref, float width, float maxHeight, Map<String, OcrImageService.Crop> crops)
      throws IOException {
    OcrImageService.Crop crop = crops.get(ref);
    if (crop == null) {
      return null;
    }
    PDImageXObject image = JPEGFactory.createFromStream(doc, new ByteArrayInputStream(crop.jpeg()));
    // Never upscale: a signature cut at 180px wide looks like a signature, not a banner.
    float scale = Math.min(1f, width / crop.width());
    float cap = Math.max(40f, maxHeight * 0.6f);
    scale = Math.min(scale, cap / crop.height());
    float w = crop.width() * scale;
    float h = crop.height() * scale;
    return new ImageEl(image, w, h, h + 8f);
  }

  // -------------------------------------------------------------------------
  // Inline parsing
  // -------------------------------------------------------------------------

  private List<Span> parseSpans(String s, float size) {
    return parseSpans(s, size, false);
  }

  /** Splits a line into regular and bold runs, dropping the markers themselves. */
  private List<Span> parseSpans(String s, float size, boolean allBold) {
    String cleaned = stripLinks(s);
    List<Span> out = new ArrayList<>();
    Matcher m = BOLD.matcher(cleaned);
    int last = 0;
    while (m.find()) {
      if (m.start() > last) {
        out.add(new Span(cleaned.substring(last, m.start()), allBold, size));
      }
      String inner = m.group(1) != null ? m.group(1) : m.group(2);
      out.add(new Span(inner, true, size));
      last = m.end();
    }
    if (last < cleaned.length()) {
      out.add(new Span(cleaned.substring(last), allBold, size));
    }
    if (out.isEmpty()) {
      out.add(new Span(cleaned, allBold, size));
    }
    return out;
  }

  /** Wraps styled runs to a width, preserving each word's style. */
  private List<List<Span>> wrapSpans(List<Span> spans, float maxWidth) throws IOException {
    List<List<Span>> lines = new ArrayList<>();
    List<Span> current = new ArrayList<>();
    float used = 0;

    for (Span span : spans) {
      PDFont font = span.bold() ? bold : regular;
      String[] words = span.text().split(" ", -1);
      StringBuilder run = new StringBuilder();
      for (String word : words) {
        if (word.isEmpty()) continue;
        float wordWidth = stringWidth(font, span.size(), word + " ");
        if (used + wordWidth > maxWidth && (used > 0 || run.length() > 0)) {
          if (run.length() > 0) {
            current.add(new Span(run.toString().stripTrailing(), span.bold(), span.size()));
            run.setLength(0);
          }
          lines.add(current);
          current = new ArrayList<>();
          used = 0;
        }
        run.append(word).append(' ');
        used += wordWidth;
      }
      if (run.length() > 0) {
        current.add(new Span(run.toString(), span.bold(), span.size()));
      }
    }
    if (!current.isEmpty()) {
      lines.add(current);
    }
    if (lines.isEmpty()) {
      lines.add(List.of());
    }
    return lines;
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

  /** Removes links and code ticks but leaves bold markers for the span parser. */
  private static String stripLinks(String s) {
    return s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
        .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
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
