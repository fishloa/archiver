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
