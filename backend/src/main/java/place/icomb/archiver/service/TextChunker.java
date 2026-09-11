package place.icomb.archiver.service;

import java.util.ArrayList;
import java.util.List;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.node.Document;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;

/**
 * Splits a page into the chunks that get embedded.
 *
 * <p>Markdown is parsed, not pattern-matched. 128,118 of this archive's pages are markdown from
 * Mistral OCR and the structure carries meaning: a table's header row is what makes the numbers
 * under it mean anything, so a chunk boundary dropped in the middle of a table produces a column of
 * bare values that is neither retrievable nor readable when shown as a search result.
 *
 * <p>Chunks are cut from the original source text rather than re-rendered from the tree. {@code
 * text_raw} is stored exactly as the OCR engine produced it — this is an archive backing a
 * citizenship application — so what gets embedded and shown must be those bytes, not a normalised
 * version of them.
 */
public final class TextChunker {

  /** ~2000 characters is roughly 500 tokens of English. */
  public static final int MAX_CHARS = 2000;

  public static final int OVERLAP = 200;

  private TextChunker() {}

  /** One chunk: the text to embed, and the heading path it came from. */
  public record Chunk(String content, String heading) {}

  /**
   * Chunks a page, keeping every chunk tied to its section heading.
   *
   * <p>The heading path is prefixed to the embedded content so a chunk taken from the middle of a
   * section still carries that section's subject.
   */
  public static List<Chunk> chunkDocument(String text, String contentType) {
    List<Chunk> out = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return out;
    }
    if (!Markdown.isMarkdown(contentType)) {
      // Content type is read, never sniffed: a typescript's centred page number "- 5 -" is
      // byte-identical to a markdown bullet, so the two cannot be told apart by inspection.
      for (String piece : chunkText(text)) {
        out.add(new Chunk(piece, ""));
      }
      return out;
    }

    String[] lines = text.split("\n", -1);
    Document doc = (Document) Markdown.parse(text);

    List<String> headings = new ArrayList<>();
    List<Integer> levels = new ArrayList<>();
    StringBuilder pending = new StringBuilder();
    String pendingHeading = "";

    for (Node node = doc.getFirstChild(); node != null; node = node.getNext()) {
      if (node instanceof Heading heading) {
        flush(out, pending, pendingHeading);
        int level = heading.getLevel();
        while (!levels.isEmpty() && levels.get(levels.size() - 1) >= level) {
          levels.remove(levels.size() - 1);
          headings.remove(headings.size() - 1);
        }
        levels.add(level);
        headings.add(Markdown.plainText(heading));
        pendingHeading = String.join(" > ", headings);
        continue;
      }

      String block = Markdown.sourceOf(node, lines);
      if (block.isBlank()) {
        continue;
      }

      // A table that does not fit is split on row boundaries with its header repeated, never
      // mid-table. Anything else that does not fit is split on sentence and word boundaries.
      List<String> pieces = node instanceof TableBlock ? splitTable(block) : List.of(block);

      for (String piece : pieces) {
        if (pending.length() > 0 && pending.length() + piece.length() + 2 > MAX_CHARS) {
          flush(out, pending, pendingHeading);
        }
        if (piece.length() > MAX_CHARS && !(node instanceof TableBlock)) {
          flush(out, pending, pendingHeading);
          for (String part : chunkText(piece)) {
            out.add(chunk(part, pendingHeading));
          }
          continue;
        }
        if (pending.length() > 0) {
          pending.append("\n\n");
        }
        pending.append(piece);
      }
    }
    flush(out, pending, pendingHeading);
    return out;
  }

  private static void flush(List<Chunk> out, StringBuilder pending, String heading) {
    String body = pending.toString().strip();
    if (!body.isEmpty()) {
      out.add(chunk(body, heading));
    }
    pending.setLength(0);
  }

  private static Chunk chunk(String body, String heading) {
    return new Chunk(heading.isEmpty() ? body : heading + "\n\n" + body, heading);
  }

  /**
   * Splits an oversized table on row boundaries, repeating the header on every piece.
   *
   * <p>Without the repeat, the second half of a wage card is a column of numbers whose labels are
   * in a different chunk.
   */
  private static List<String> splitTable(String table) {
    List<String> out = new ArrayList<>();
    String[] rows = table.split("\n");
    if (table.length() <= MAX_CHARS || rows.length < 3) {
      out.add(table);
      return out;
    }
    String header = rows[0] + "\n" + rows[1];
    StringBuilder current = new StringBuilder(header);
    for (int i = 2; i < rows.length; i++) {
      if (current.length() + rows[i].length() + 1 > MAX_CHARS
          && current.length() > header.length()) {
        out.add(current.toString());
        current = new StringBuilder(header);
      }
      current.append('\n').append(rows[i]);
    }
    if (current.length() > header.length()) {
      out.add(current.toString());
    }
    return out;
  }

  /** Splits plain text into overlapping chunks on natural boundaries. */
  public static List<String> chunkText(String text) {
    return chunkText(text, MAX_CHARS, OVERLAP);
  }

  /**
   * Splits text into overlapping chunks, breaking at a paragraph, then a sentence, then a word.
   *
   * <p>The overlap rewinds to a word boundary. The Python implementation this replaces took it as a
   * raw character offset, so on a real page a chunk began "ührung als deutsch...", cut through the
   * middle of "Einführung" — noise in the embedding, and damage on screen.
   */
  public static List<String> chunkText(String text, int maxChars, int overlap) {
    List<String> chunks = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return chunks;
    }
    String t = text.strip();
    if (t.length() <= maxChars) {
      chunks.add(t);
      return chunks;
    }

    int start = 0;
    while (start < t.length()) {
      int end = Math.min(start + maxChars, t.length());
      if (end < t.length()) {
        end = breakBefore(t, start, end, maxChars);
      }
      String chunk = t.substring(start, end).strip();
      if (!chunk.isEmpty()) {
        chunks.add(chunk);
      }
      if (end >= t.length()) {
        break;
      }
      int next = wordStartAtOrAfter(t, Math.max(start + 1, end - overlap), end);
      start = next <= start ? end : next;
    }
    return chunks;
  }

  /** A paragraph break, else a sentence end, else a line, else a word, else the hard limit. */
  private static int breakBefore(String t, int start, int end, int maxChars) {
    int half = start + maxChars / 2;

    int paragraph = lastIndexIn(t, "\n\n", half, end);
    if (paragraph > start) {
      return paragraph;
    }
    for (String sep : new String[] {". ", ".\n", "! ", "? "}) {
      int at = lastIndexIn(t, sep, half, end);
      if (at > start) {
        return at + 1;
      }
    }
    int line = lastIndexIn(t, "\n", half, end);
    if (line > start) {
      return line;
    }
    int space = lastIndexIn(t, " ", half, end);
    return space > start ? space : end;
  }

  /**
   * Moves forward to the start of the next whole word, so a chunk never opens mid-word.
   *
   * <p>Never searches past {@code limit}. Scanning to the end of the text instead made a long run
   * with no whitespace — which real OCR produces — skip everything up to the next space, dropping
   * 7,000 characters of a 9,000-character page. With no boundary inside the limit the caller takes
   * no overlap, losing context but never content.
   */
  private static int wordStartAtOrAfter(String t, int index, int limit) {
    int cap = Math.min(limit, t.length());
    int i = Math.max(0, Math.min(index, cap));
    if (i == 0 || i >= cap || Character.isWhitespace(t.charAt(i - 1))) {
      return i;
    }
    while (i < cap && !Character.isWhitespace(t.charAt(i))) {
      i++;
    }
    while (i < cap && Character.isWhitespace(t.charAt(i))) {
      i++;
    }
    return i;
  }

  /** The last occurrence of {@code sep} lying wholly within [from, to). */
  private static int lastIndexIn(String text, String sep, int from, int to) {
    if (from < 0) from = 0;
    int limit = Math.min(to, text.length());
    if (from >= limit) return -1;
    int found = -1;
    int at = text.indexOf(sep, from);
    while (at >= 0 && at + sep.length() <= limit) {
      found = at;
      at = text.indexOf(sep, at + 1);
    }
    return found;
  }
}
