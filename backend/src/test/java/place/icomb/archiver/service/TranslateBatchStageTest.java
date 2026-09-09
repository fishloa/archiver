package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The model wrapped 13% of this archive's translations in a code fence despite being told to output
 * only the translation. Stored that way, a page renders as literal pipes and hashes instead of a
 * table.
 */
class TranslateBatchStageTest {

  @Test
  void removesAnEnclosingMarkdownFence() {
    String wrapped = "```markdown\n| Name | Date |\n| --- | --- |\n| Czernin | 1943 |\n```";
    assertThat(TranslateBatchStage.unwrapCodeFence(wrapped))
        .startsWith("| Name | Date |")
        .doesNotContain("```");
  }

  @Test
  void removesAPlainFence() {
    assertThat(TranslateBatchStage.unwrapCodeFence("```\nsome text\n```")).isEqualTo("some text");
  }

  @Test
  void leavesUnfencedTextAlone() {
    String plain = "# Heading\n\nSome prose about the Reichsprotektor.";
    assertThat(TranslateBatchStage.unwrapCodeFence(plain)).isEqualTo(plain);
  }

  @Test
  void keepsAFenceThatIsPartOfTheContent() {
    // A fence in the middle of a page is content, not a wrapper.
    String mixed = "Some prose\n\n```\nquoted block\n```\n\nmore prose";
    assertThat(TranslateBatchStage.unwrapCodeFence(mixed)).isEqualTo(mixed);
  }

  @Test
  void handlesEmptyAndNull() {
    assertThat(TranslateBatchStage.unwrapCodeFence(null)).isEmpty();
    assertThat(TranslateBatchStage.unwrapCodeFence("   ")).isEmpty();
  }

  // The same model also announced what it was about to do: 4,043 pages opened with a
  // "### Translation" heading and 1,363 with "Here is the translation:". Once headings render
  // large and ruled, each of those is a title on a document that never had one.

  @Test
  void removesAnAddedTranslationHeading() {
    assertThat(TranslateBatchStage.stripPreamble("### Translation\n\n3 August 1942\n\nDept. V"))
        .isEqualTo("3 August 1942\n\nDept. V");
  }

  @Test
  void removesASpokenPreamble() {
    assertThat(TranslateBatchStage.stripPreamble("Here is the translation:\n\nSecret Reich Matter"))
        .isEqualTo("Secret Reich Matter");
    assertThat(
            TranslateBatchStage.stripPreamble(
                "Here is the English translation of the document:\n\nSecret"))
        .isEqualTo("Secret");
  }

  @Test
  void removesABareLabel() {
    assertThat(TranslateBatchStage.stripPreamble("Translation:\n\nSecret Reich Matter"))
        .isEqualTo("Secret Reich Matter");
  }

  @Test
  void keepsAHeadingThatIsPartOfTheDocument() {
    String real = "# Secret Reich Matter\n\nLetter\n\nTo M-Standartenf\u00fchrer Dr. Brandt";
    assertThat(TranslateBatchStage.stripPreamble(real)).isEqualTo(real);
  }

  @Test
  void keepsProseThatMerelyMentionsTranslation() {
    // A page about translation work is content, not a preamble.
    String real = "The translation of the decree was ordered on 24.4.42.\n\nSigned, Frank";
    assertThat(TranslateBatchStage.stripPreamble(real)).isEqualTo(real);
  }

  @Test
  void removesALongAnnouncement() {
    assertThat(
            TranslateBatchStage.stripPreamble(
                "Here is the translation preserving all original formatting, proper names,"
                    + " place names, and dates:\n\n15a\n\nindispensable"))
        .isEqualTo("15a\n\nindispensable");
  }

  @Test
  void removesARepeatedHeading() {
    assertThat(TranslateBatchStage.stripPreamble("# Translation.\n\n# Translation.\n\nI. copy."))
        .isEqualTo("I. copy.");
  }

  @Test
  void keepsASentenceAboutTranslationWithNoColon() {
    // Without the colon this is prose, not the model introducing itself.
    String real = "Here is the translation of the decree that was ordered on 24.4.42\n\nSigned";
    assertThat(TranslateBatchStage.stripPreamble(real)).isEqualTo(real);
  }

  @Test
  void stripPreambleHandlesEmptyAndNull() {
    assertThat(TranslateBatchStage.stripPreamble(null)).isEmpty();
    assertThat(TranslateBatchStage.stripPreamble("   ")).isEmpty();
  }
}
