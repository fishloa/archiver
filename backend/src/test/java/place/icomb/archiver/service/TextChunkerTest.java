package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The chunker decides what gets embedded, so its boundaries are part of the index.
 *
 * <p>Replaces the Python embed-worker's chunker. Parity with it was established first — both run
 * over 300 real archive pages produced an identical digest of all 448 chunks — and the two known
 * defects were then fixed deliberately, so the difference from the old output is intended rather
 * than accidental.
 */
class TextChunkerTest {

  @Test
  void shortTextIsOneChunk() {
    assertThat(TextChunker.chunkText("a short page")).containsExactly("a short page");
  }

  @Test
  void blankTextProducesNothing() {
    assertThat(TextChunker.chunkText("   ")).isEmpty();
    assertThat(TextChunker.chunkText(null)).isEmpty();
  }

  @Test
  void aSeparatorMustFitInsideTheWindow() {
    // A separator has to lie wholly inside the search window. Letting "\n\n" straddle the end
    // moved a real boundary from the sentence end at 1847 to 1999, splitting mid-sentence.
    String text = "x".repeat(1846) + ". " + "y".repeat(151) + "\n\n" + "z".repeat(400);
    List<String> chunks = TextChunker.chunkText(text);
    assertThat(chunks.get(0)).endsWith(".");
    assertThat(chunks.get(0)).hasSize(1847);
  }

  @Test
  void noChunkBeginsMidWord() {
    // The defect this replaces: the overlap was a raw character offset, so the next chunk began
    // wherever end-200 landed. On a real page that produced a chunk starting "ührung als
    // deutsch...", cut through the middle of "Einführung".
    String text =
        ("Einführung als deutsch anzusprechenden Revision der Angelegenheit. ").repeat(120);
    List<String> chunks = TextChunker.chunkText(text);
    assertThat(chunks).hasSizeGreaterThan(2);
    assertThat(chunks)
        .allSatisfy(c -> assertThat(c).matches("(?s)^\\S.*"))
        .allSatisfy(
            c ->
                assertThat(text.contains(" " + c.substring(0, 12)) || text.startsWith(c))
                    .as("chunk %s starts at a word boundary", c.substring(0, 12))
                    .isTrue());
  }

  @Test
  void alwaysMakesForwardProgress() {
    // The original could compute a next start at or behind the current one and spin for ever.
    // Text with no spaces at all is the pathological case.
    String noBreaks = "h".repeat(9000);
    List<String> chunks = TextChunker.chunkText(noBreaks);
    assertThat(chunks).isNotEmpty();
    assertThat(String.join("", chunks).length()).isGreaterThanOrEqualTo(noBreaks.length());
  }

  @Test
  void aLongTableKeepsItsHeaderOnEveryChunk() {
    // 11,861 pages carry tables. Split by character count, the header lands in one chunk and the
    // values in the next, leaving a column of bare numbers that is neither retrievable nor
    // readable when shown as a search result.
    StringBuilder md = new StringBuilder("| Beruf | Lohn | Bemerkung |\n| --- | --- | --- |\n");
    for (int i = 0; i < 80; i++) {
      md.append("| Landarbeiter ").append(i).append(" | 24,50 | wöchentlich ausgezahlt |\n");
    }
    List<TextChunker.Chunk> chunks = TextChunker.chunkDocument(md.toString(), "text/markdown");

    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks)
        .allSatisfy(c -> assertThat(c.content()).contains("| Beruf | Lohn | Bemerkung |"))
        .allSatisfy(c -> assertThat(c.content()).contains("| --- | --- | --- |"));
    // Every row survives exactly once across the chunks.
    long rows =
        chunks.stream()
            .flatMap(c -> c.content().lines())
            .filter(l -> l.startsWith("| Landarbeiter"))
            .count();
    assertThat(rows).isEqualTo(80);
  }

  @Test
  void aTableIsNotMergedIntoSurroundingProse() {
    String md = "Some introductory prose.\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\nClosing prose.";
    List<TextChunker.Chunk> chunks = TextChunker.chunkDocument(md, "text/markdown");
    assertThat(chunks).anySatisfy(c -> assertThat(c.content()).contains("| 1 | 2 |"));
    assertThat(String.join("\n", chunks.stream().map(TextChunker.Chunk::content).toList()))
        .contains("Some introductory prose.")
        .contains("Closing prose.");
  }

  @Test
  void plainTextTablesAreLeftAlone() {
    String text = "| not | really | a table |\n".repeat(3);
    List<TextChunker.Chunk> chunks = TextChunker.chunkDocument(text, "text/plain");
    assertThat(chunks).hasSize(1);
  }

  @Test
  void theSourceIsPreservedVerbatim() {
    // text_raw is stored exactly as the OCR engine produced it; chunks are cut from those bytes,
    // not re-rendered from the parse tree.
    String md = "Some   oddly    spaced prose.\n\n| A |  B |\n| --- | --- |\n|  1 | 2  |\n";
    var chunks = TextChunker.chunkDocument(md, "text/markdown");
    String all = String.join("\n", chunks.stream().map(TextChunker.Chunk::content).toList());
    assertThat(all).contains("Some   oddly    spaced prose.");
    assertThat(all).contains("|  1 | 2  |");
  }

  @Test
  void prefersAParagraphBreak() {
    String text = "a".repeat(1200) + "\n\n" + "b".repeat(1500);
    assertThat(TextChunker.chunkText(text).get(0)).hasSize(1200);
  }

  @Test
  void fallsBackToASentenceThenAWord() {
    String sentence = "c".repeat(1500) + ". " + "d".repeat(900);
    assertThat(TextChunker.chunkText(sentence).get(0)).endsWith(".");

    String wordy = "e".repeat(1500) + " " + "f".repeat(900);
    assertThat(TextChunker.chunkText(wordy).get(0)).hasSize(1500);
  }

  @Test
  void chunksOverlap() {
    String text = "g".repeat(5000);
    List<String> chunks = TextChunker.chunkText(text);
    assertThat(chunks.size()).isGreaterThan(2);
    // Each chunk after the first restarts 200 characters back.
    assertThat(chunks.get(0)).hasSize(2000);
  }

  @Test
  void plainTextIsNotParsedAsMarkdown() {
    // content_type is read, never sniffed: a typescript's "- 5 -" is byte-identical to a bullet.
    var chunks = TextChunker.chunkDocument("# not a heading here\nbody", "text/plain");
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).heading()).isEmpty();
    assertThat(chunks.get(0).content()).contains("# not a heading here");
  }

  @Test
  void markdownHeadingsBecomeAPath() {
    String md =
        """
        # Reichsprotektor

        intro text

        ## Enteignung

        the body of the section
        """;
    var chunks = TextChunker.chunkDocument(md, "text/markdown");
    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(0).heading()).isEqualTo("Reichsprotektor");
    assertThat(chunks.get(1).heading()).isEqualTo("Reichsprotektor > Enteignung");
    assertThat(chunks.get(1).content()).startsWith("Reichsprotektor > Enteignung");
  }

  @Test
  void aDeeperHeadingPopsBackToItsParent() {
    String md = "# A\n\nx\n\n## B\n\ny\n\n## C\n\nz\n";
    var chunks = TextChunker.chunkDocument(md, "text/markdown");
    assertThat(chunks.get(2).heading()).isEqualTo("A > C");
  }

  @Test
  void theHeadingPathIsPrefixedToEveryChunk() {
    // A chunk from the middle of a section still has to carry that section's subject.
    String md = "## Enteignung des Adels\n\n" + "w".repeat(3000);
    List<TextChunker.Chunk> chunks = TextChunker.chunkDocument(md, "text/markdown");
    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allSatisfy(c -> assertThat(c.content()).startsWith("Enteignung des Adels"));
    assertThat(chunks).allSatisfy(c -> assertThat(c.heading()).isEqualTo("Enteignung des Adels"));
  }

  @Test
  void inlineMarkupIsStrippedFromHeadings() {
    var chunks = TextChunker.chunkDocument("# **Bold** and `code`\n\nbody", "text/markdown");
    assertThat(chunks.get(0).heading()).isEqualTo("Bold and code");
  }
}
