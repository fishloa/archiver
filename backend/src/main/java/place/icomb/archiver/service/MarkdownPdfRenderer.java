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
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;

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
  public int renderPage(PDDocument doc, String markdown, String note, Long pageId)
      throws IOException {
    return renderPage(doc, markdown, note, pageId, null, null);
  }

  /**
   * Draws one source page's markdown, starting a new PDF page and continuing onto further pages if
   * it overflows.
   *
   * @param note an annotation such as "[not translated - original text]", drawn small at the top so
   *     a reader is never left guessing why a page reads in German
   * @param footerLeft archive URL for the footer, or null for no footer
   * @param seq the page's number in the original document, used for the footer's right side
   * @return PDF pages used — always at least one, so an empty source page still produces a page and
   *     the export stays aligned with the original
   */
  public int renderPage(
      PDDocument doc, String markdown, String note, Long pageId, String footerLeft, Integer seq)
      throws IOException {
    boolean footer = footerLeft != null || seq != null;
    float width = PAGE_SIZE.getWidth() - 2 * MARGIN;
    float top = PAGE_SIZE.getHeight() - MARGIN - (note == null || note.isBlank() ? 0 : 14f);
    float bottom = MARGIN + (footer ? footerBandHeight(PAGE_SIZE.getWidth()) : 0);
    List<El> els = layoutTo(markdown, width, top - bottom, pageId);

    int pagesUsed = 0;
    int i = 0;
    do {
      PDPage page = new PDPage(PAGE_SIZE);
      doc.addPage(page);
      pagesUsed++;
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        if (note != null && !note.isBlank()) {
          cs.beginText();
          cs.setFont(regular, 7.5f);
          cs.newLineAtOffset(MARGIN, PAGE_SIZE.getHeight() - MARGIN + 4f);
          cs.showText(sanitise(note));
          cs.endText();
        }

        i = drawElements(cs, els, i, MARGIN, top, bottom);

        if (footer) {
          drawFooter(
              page,
              cs,
              PAGE_SIZE.getWidth(),
              MARGIN,
              MARGIN,
              footerLeft,
              seq == null ? null : pageLabel(seq, pagesUsed > 1));
        }
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

  /**
   * Draws the footer every export carries: where the page came from, and which page it is.
   *
   * <p>Left is the archive URL, so a printed or forwarded extract can be traced back to the record
   * it came from. Right names the page in the original document — not the PDF's own page number,
   * which drifts from it as soon as one source page needs two sheets. That drift is the reason for
   * "cont.": without it a reader holding sheet two has no way to tell whether they are looking at a
   * continuation or at the next document page.
   *
   * <p>The size is scaled to the page: the scan exports are sized in image pixels, where a 7pt
   * footer is invisible.
   */
  public void drawFooter(
      PDPage page,
      PDPageContentStream cs,
      float pageWidth,
      float margin,
      float baseline,
      String left,
      String right)
      throws IOException {
    float size = footerSize(pageWidth);
    if (left != null && !left.isBlank()) {
      String text = sanitise(left);
      cs.beginText();
      cs.setFont(regular, size);
      cs.newLineAtOffset(margin, baseline);
      cs.showText(text);
      cs.endText();
      float linkWidth = regular.getStringWidth(text) / 1000f * size;
      // Underlined and clickable. An extract is read on screen at least as often as on paper, and
      // retyping a record and page number by hand to get back to the source is exactly the
      // friction the footer exists to remove — but the link is invisible until the rule under it
      // says there is one.
      drawRule(cs, margin, baseline - size * 0.22f, linkWidth, 0.4f);
      linkTo(page, left, margin, baseline, linkWidth, size);
    }
    if (right != null && !right.isBlank()) {
      String text = sanitise(right);
      float width = regular.getStringWidth(text) / 1000f * size;
      cs.beginText();
      cs.setFont(regular, size);
      cs.newLineAtOffset(pageWidth - margin - width, baseline);
      cs.showText(text);
      cs.endText();
    }
  }

  /** Puts an invisible clickable region over drawn text. */
  private void linkTo(PDPage page, String uri, float x, float baseline, float width, float size)
      throws IOException {
    PDActionURI action = new PDActionURI();
    action.setURI(uri);

    PDAnnotationLink link = new PDAnnotationLink();
    link.setAction(action);
    link.setRectangle(new PDRectangle(x, baseline - size * 0.25f, width, size * 1.25f));
    // No visible border: the footer already looks like a link, and a box round it does not.
    PDBorderStyleDictionary border = new PDBorderStyleDictionary();
    border.setWidth(0);
    link.setBorderStyle(border);

    page.getAnnotations().add(link);
  }

  /** Footer type size for a page of the given width, and the band it needs. */
  public float footerSize(float pageWidth) {
    return Math.max(7f, Math.min(pageWidth / 100f, 26f));
  }

  public float footerBandHeight(float pageWidth) {
    return footerSize(pageWidth) * 2.6f;
  }

  /** "Archive Page 5", or "Archive Page 5 cont." on a source page's second and later sheets. */
  public static String pageLabel(int seq, boolean continuation) {
    return "Archive Page " + seq + (continuation ? " cont." : "");
  }

  public PDFont boldFont() {
    return bold;
  }

  /** Wraps plain (non-markdown) text to a width — the cover sheet's fields are not markdown. */
  public List<String> wrapPlain(String text, boolean useBold, float size, float maxWidth)
      throws IOException {
    return wrap(text == null ? "" : text, useBold ? bold : regular, size, maxWidth);
  }

  /** Draws a single run of plain text at a baseline. */
  public void drawText(
      PDPageContentStream cs, String text, float x, float y, float size, boolean useBold)
      throws IOException {
    if (text == null || text.isBlank()) return;
    cs.beginText();
    cs.setFont(useBold ? bold : regular, size);
    cs.newLineAtOffset(x, y);
    cs.showText(sanitise(text));
    cs.endText();
  }

  /** Width of plain text as it would be drawn. */
  public float textWidth(String text, float size, boolean useBold) throws IOException {
    return stringWidth(useBold ? bold : regular, size, text == null ? "" : text);
  }

  /** A horizontal rule, for the cover sheet's section dividers. */
  public void drawRule(PDPageContentStream cs, float x, float y, float width, float thickness)
      throws IOException {
    cs.setLineWidth(thickness);
    cs.moveTo(x, y);
    cs.lineTo(x + width, y);
    cs.stroke();
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
    if (markdown == null || markdown.isBlank()) {
      return out;
    }
    Node doc = Markdown.parse(markdown);
    String[] lines = markdown.split("\n", -1);

    for (Node node = doc.getFirstChild(); node != null; node = node.getNext()) {
      layoutBlock(out, node, lines, width, maxImageHeight, crops);
      out.add(new TextEl(List.of(), 0, LEADING * 0.55f));
    }
    return out;
  }

  /** Lays out one parsed block. */
  private void layoutBlock(
      List<El> out,
      Node node,
      String[] lines,
      float width,
      float maxImageHeight,
      Map<String, OcrImageService.Crop> crops)
      throws IOException {

    if (node instanceof Heading heading) {
      addHeading(
          out, spansOf(heading, headingSize(heading.getLevel()), true), heading.getLevel(), width);
      return;
    }
    if (node instanceof TableBlock table) {
      addTable(out, tableRows(table, lines), width);
      return;
    }
    if (node instanceof ThematicBreak) {
      out.add(new RuleEl(width, 0.4f, 6f));
      return;
    }
    if (node instanceof BulletList || node instanceof OrderedList) {
      addList(out, node, lines, width, maxImageHeight, crops);
      return;
    }
    if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
      String literal =
          node instanceof FencedCodeBlock fenced
              ? fenced.getLiteral()
              : ((IndentedCodeBlock) node).getLiteral();
      for (String line : literal.split("\n")) {
        out.add(new TextEl(List.of(new Span(line, false, BODY_SIZE)), 6f, LEADING));
      }
      return;
    }
    if (node instanceof BlockQuote) {
      for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
        layoutBlock(out, child, lines, width - 14f, maxImageHeight, crops);
      }
      return;
    }
    if (node instanceof Paragraph) {
      addParagraph(out, node, width, maxImageHeight, crops, 0f);
      return;
    }
    // Anything unrecognised is drawn as its own source text rather than dropped: losing content
    // silently is the failure that matters here.
    String source = Markdown.sourceOf(node, lines);
    if (!source.isBlank()) {
      for (List<Span> w : wrapSpans(List.of(new Span(source, false, BODY_SIZE)), width)) {
        out.add(new TextEl(w, 0, LEADING));
      }
    }
  }

  /**
   * A paragraph, with any figure the OCR engine cut out drawn where it appears.
   *
   * <p>A figure on its own is emitted as an image; one among words keeps its place in the flow.
   */
  private void addParagraph(
      List<El> out,
      Node paragraph,
      float width,
      float maxImageHeight,
      Map<String, OcrImageService.Crop> crops,
      float indent)
      throws IOException {

    List<Span> spans = new ArrayList<>();
    for (Node child = paragraph.getFirstChild(); child != null; child = child.getNext()) {
      if (child instanceof Image image) {
        ImageEl el = imageElement(image.getDestination(), width - indent, maxImageHeight, crops);
        if (el != null) {
          flushSpans(out, spans, width - indent, indent, 0f);
          out.add(el);
          continue;
        }
        // No crop for it. Mistral names a figure by its file — "![img-0.jpeg](img-0.jpeg)" —
        // so the alt text is an internal id, not a caption, and printing it puts a filename in
        // front of the reader. Only a genuine caption is kept.
        String alt = Markdown.plainText(image);
        if (!alt.isBlank() && !alt.equals(image.getDestination())) {
          spans.add(new Span(alt, false, BODY_SIZE));
        }
        continue;
      }
      if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
        // A line break in the source ends the line here. CommonMark would fold it into a space,
        // which is right for prose and wrong for these documents: they are typescripts and forms
        // where one field sits per line, and joining them loses every label/value pairing — the
        // whole content of a wage card.
        flushSpans(out, spans, width - indent, indent, 0f);
        continue;
      }
      spans.addAll(inlineSpans(child, BODY_SIZE, false));
    }
    flushSpans(out, spans, width - indent, indent, 0f);
  }

  /**
   * Emits wrapped lines.
   *
   * <p>{@code hanging} indents every line after the first, which is what a list item wants so its
   * text aligns under itself rather than under its bullet. A paragraph wants none: applying it
   * everywhere indented the second and later lines of ordinary prose.
   */
  private void flushSpans(List<El> out, List<Span> spans, float width, float indent, float hanging)
      throws IOException {
    if (spans.isEmpty()) {
      return;
    }
    List<List<Span>> wrapped = wrapSpans(List.copyOf(spans), width);
    for (int i = 0; i < wrapped.size(); i++) {
      out.add(new TextEl(wrapped.get(i), i == 0 ? indent : indent + hanging, LEADING));
    }
    spans.clear();
  }

  /**
   * A list, with one guard: a centred page number is not a bullet.
   *
   * <p>"- 5 -" is how nearly every typescript in this archive numbers itself, and it is valid
   * markdown for a list item containing "5 -". A parser is right to read it as a list; a reader
   * looking at a scanned page is not helped by a bullet at the top of it.
   */
  private void addList(
      List<El> out,
      Node list,
      String[] lines,
      float width,
      float maxImageHeight,
      Map<String, OcrImageService.Crop> crops)
      throws IOException {

    String source = Markdown.sourceOf(list, lines).strip();
    if (PAGE_NUMBER.matcher(source).matches()) {
      out.add(new TextEl(List.of(new Span(source, false, BODY_SIZE)), 0, LEADING));
      return;
    }

    boolean ordered = list instanceof OrderedList;
    int number = ordered ? ((OrderedList) list).getMarkerStartNumber() : 0;
    for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
      String marker = ordered ? (number++) + ". " : "\u2022 ";
      boolean first = true;
      for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
        if (first && child instanceof Paragraph) {
          List<Span> spans = new ArrayList<>();
          spans.add(new Span(marker, false, BODY_SIZE));
          for (Node inline = child.getFirstChild(); inline != null; inline = inline.getNext()) {
            spans.addAll(inlineSpans(inline, BODY_SIZE, false));
          }
          flushSpans(out, spans, width - 10f, 10f, 10f);
          first = false;
          continue;
        }
        layoutBlock(out, child, lines, width - 10f, maxImageHeight, crops);
      }
    }
  }

  private static float headingSize(int level) {
    return switch (level) {
      case 1 -> 16f;
      case 2 -> 13f;
      case 3 -> 11.5f;
      default -> 10.5f;
    };
  }

  /** The rows of a table, as source text, so cell contents stay exactly as transcribed. */
  private List<String> tableRows(TableBlock table, String[] lines) {
    List<String> rows = new ArrayList<>();
    for (String line : Markdown.sourceOf(table, lines).split("\n")) {
      if (!line.isBlank()) {
        rows.add(line.strip());
      }
    }
    return rows;
  }

  /** Inline nodes as styled runs. */
  private List<Span> inlineSpans(Node node, float size, boolean bold) {
    List<Span> out = new ArrayList<>();
    if (node instanceof Text text) {
      out.add(new Span(text.getLiteral(), bold, size));
      return out;
    }
    if (node instanceof Code code) {
      out.add(new Span(code.getLiteral(), bold, size));
      return out;
    }
    if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
      // Reached only inside a list item or heading, where the line is kept flowing.
      out.add(new Span(" ", bold, size));
      return out;
    }
    boolean strong = bold || node instanceof StrongEmphasis;
    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
      out.addAll(inlineSpans(child, size, strong));
    }
    return out;
  }

  private List<Span> spansOf(Node node, float size, boolean bold) {
    List<Span> out = new ArrayList<>();
    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
      out.addAll(inlineSpans(child, size, bold));
    }
    return out.isEmpty() ? List.of(new Span(Markdown.plainText(node), bold, size)) : out;
  }

  /**
   * A heading, sized by level and set off by space.
   *
   * <p>Bold alone did not read as a title at body size in a half-page column; the size step, the
   * space above and the rule under the top two levels are what make a report's section headings
   * scannable rather than just slightly darker text.
   */
  private void addHeading(List<El> out, List<Span> spans, int level, float width)
      throws IOException {
    float size = headingSize(level);
    // Space above, but not a gap at the very top of a page.
    if (!out.isEmpty()) {
      out.add(new TextEl(List.of(), 0, size * 0.7f));
    }
    for (List<Span> w : wrapSpans(spans, width)) {
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
