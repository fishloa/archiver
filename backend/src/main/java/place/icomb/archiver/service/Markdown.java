package place.icomb.archiver.service;

import java.util.List;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/**
 * One markdown parser for the whole backend.
 *
 * <p>Mistral OCR returns markdown for 128,118 pages. That markdown was previously interpreted by
 * three separate hand-written implementations — the PDF renderer, the chunker, and the Python
 * worker's — which disagreed with each other about tables, headings and inline markup. A page could
 * therefore be chunked one way, drawn another, and searched a third.
 *
 * <p>Configuration lives here so the parse is identical everywhere: GFM tables, because the
 * archive's forms and wage cards are tables, and block source spans, because chunks and PDF text
 * must come from the bytes the OCR engine produced rather than from a re-rendering of them.
 */
public final class Markdown {

  public static final String MEDIA_TYPE = "text/markdown";

  private static final Parser PARSER =
      Parser.builder()
          .extensions(List.of(TablesExtension.create()))
          .includeSourceSpans(IncludeSourceSpans.BLOCKS)
          .build();

  private Markdown() {}

  public static Node parse(String text) {
    return PARSER.parse(text == null ? "" : text);
  }

  public static boolean isMarkdown(String contentType) {
    return MEDIA_TYPE.equals(contentType);
  }

  /**
   * A node's words, without its markup.
   *
   * <p>Collected from the tree rather than rendered: commonmark's text renderer quotes inline code,
   * so "# **Bold** and `code`" comes back as {@code Bold and "code"}.
   */
  public static String plainText(Node node) {
    StringBuilder sb = new StringBuilder();
    node.accept(
        new AbstractVisitor() {
          @Override
          public void visit(Text text) {
            sb.append(text.getLiteral());
          }

          @Override
          public void visit(Code code) {
            sb.append(code.getLiteral());
          }
        });
    return sb.toString().strip();
  }

  /**
   * Widens any table whose delimiter row is narrower than its body.
   *
   * <p>A GFM table's width is fixed by the delimiter row, and the renderer drops every cell beyond
   * it without complaining. So a model that translates
   *
   * <pre>|  Věznice gestapa …  |   |
   * | --- | --- |</pre>
   *
   * into a one-column header takes the values out of the document while leaving the labels in
   * place, and the page reads as though the archive holds a list of empty fields. That happened to
   * the Terezín prisoner cards: name, dates and destination all vanished from the English text.
   *
   * <p>Padding the delimiter row is the conservative repair — no cell is moved or rewritten, and a
   * table that is already consistent is returned unchanged.
   */
  public static String repairTables(String markdown) {
    if (markdown == null || markdown.isBlank() || !markdown.contains("|")) {
      return markdown;
    }
    String[] lines = markdown.split("\n", -1);
    boolean changed = false;
    for (int i = 1; i < lines.length; i++) {
      if (!isDelimiterRow(lines[i]) || !lines[i - 1].contains("|")) {
        continue;
      }
      int width = cellCount(lines[i - 1]);
      for (int j = i + 1; j < lines.length && lines[j].contains("|"); j++) {
        width = Math.max(width, cellCount(lines[j]));
      }
      if (cellCount(lines[i]) >= width) {
        continue;
      }
      lines[i] = pad(splitCells(lines[i]), width, "---");
      lines[i - 1] = pad(splitCells(lines[i - 1]), width, "");
      changed = true;
    }
    return changed ? String.join("\n", lines) : markdown;
  }

  /** A row of only dashes and alignment colons — the line that sets a table's width. */
  private static boolean isDelimiterRow(String line) {
    String trimmed = line.strip();
    if (!trimmed.contains("-") || !trimmed.contains("|")) {
      return false;
    }
    for (String cell : splitCells(line)) {
      if (!cell.strip().matches(":?-+:?")) {
        return false;
      }
    }
    return true;
  }

  /** Cells of a row, split on unescaped pipes, ignoring the optional leading and trailing ones. */
  private static String[] splitCells(String line) {
    String body = line.strip();
    if (body.startsWith("|")) {
      body = body.substring(1);
    }
    if (body.endsWith("|") && !body.endsWith("\\|")) {
      body = body.substring(0, body.length() - 1);
    }
    return body.split("(?<!\\\\)\\|", -1);
  }

  private static int cellCount(String line) {
    return splitCells(line).length;
  }

  private static String pad(String[] cells, int width, String filler) {
    StringBuilder sb = new StringBuilder("|");
    for (int i = 0; i < width; i++) {
      String cell = i < cells.length ? cells[i].strip() : filler;
      sb.append(' ').append(cell.isEmpty() && !filler.isEmpty() ? filler : cell).append(" |");
    }
    return sb.toString();
  }

  /** A block's own source text, verbatim, from the lines it was parsed from. */
  public static String sourceOf(Node node, String[] lines) {
    List<SourceSpan> spans = node.getSourceSpans();
    if (spans.isEmpty()) {
      return "";
    }
    int first = spans.get(0).getLineIndex();
    int last = spans.get(spans.size() - 1).getLineIndex();
    StringBuilder sb = new StringBuilder();
    for (int i = first; i <= last && i < lines.length; i++) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(lines[i]);
    }
    return sb.toString().stripTrailing();
  }
}
