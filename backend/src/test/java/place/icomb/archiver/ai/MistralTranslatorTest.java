package place.icomb.archiver.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * What Mistral actually returns, as opposed to what it was asked for.
 *
 * <p>It wrapped 13% of this archive's translations in a code fence and opened 5,454 pages by
 * announcing itself, despite being told to output only the translation. Stored that way a page
 * renders as literal pipes and hashes instead of a table, and every one of those pages opens with a
 * title the Reichsprotektor never wrote.
 */
class MistralTranslatorTest {

  @Test
  void removesAnEnclosingMarkdownFence() {
    String wrapped = "```markdown\n| Name | Date |\n| --- | --- |\n| Czernin | 1943 |\n```";
    assertThat(MistralTranslator.unwrapCodeFence(wrapped))
        .startsWith("| Name | Date |")
        .doesNotContain("```");
  }

  @Test
  void removesAPlainFence() {
    assertThat(MistralTranslator.unwrapCodeFence("```\nsome text\n```")).isEqualTo("some text");
  }

  @Test
  void leavesUnfencedTextAlone() {
    String plain = "# Heading\n\nSome prose about the Reichsprotektor.";
    assertThat(MistralTranslator.unwrapCodeFence(plain)).isEqualTo(plain);
  }

  @Test
  void keepsAFenceThatIsPartOfTheContent() {
    // A fence in the middle of a page is content, not a wrapper.
    String mixed = "Some prose\n\n```\nquoted block\n```\n\nmore prose";
    assertThat(MistralTranslator.unwrapCodeFence(mixed)).isEqualTo(mixed);
  }

  @Test
  void handlesEmptyAndNull() {
    assertThat(MistralTranslator.unwrapCodeFence(null)).isEmpty();
    assertThat(MistralTranslator.unwrapCodeFence("   ")).isEmpty();
  }

  // The same model also announced what it was about to do: 4,043 pages opened with a
  // "### Translation" heading and 1,363 with "Here is the translation:". Once headings render
  // large and ruled, each of those is a title on a document that never had one.

  @Test
  void removesAnAddedTranslationHeading() {
    assertThat(MistralTranslator.stripPreamble("### Translation\n\n3 August 1942\n\nDept. V"))
        .isEqualTo("3 August 1942\n\nDept. V");
  }

  @Test
  void removesASpokenPreamble() {
    assertThat(MistralTranslator.stripPreamble("Here is the translation:\n\nSecret Reich Matter"))
        .isEqualTo("Secret Reich Matter");
    assertThat(
            MistralTranslator.stripPreamble(
                "Here is the English translation of the document:\n\nSecret"))
        .isEqualTo("Secret");
  }

  @Test
  void removesABareLabel() {
    assertThat(MistralTranslator.stripPreamble("Translation:\n\nSecret Reich Matter"))
        .isEqualTo("Secret Reich Matter");
  }

  @Test
  void keepsAHeadingThatIsPartOfTheDocument() {
    String real = "# Secret Reich Matter\n\nLetter\n\nTo M-Standartenf\u00fchrer Dr. Brandt";
    assertThat(MistralTranslator.stripPreamble(real)).isEqualTo(real);
  }

  @Test
  void keepsProseThatMerelyMentionsTranslation() {
    // A page about translation work is content, not a preamble.
    String real = "The translation of the decree was ordered on 24.4.42.\n\nSigned, Frank";
    assertThat(MistralTranslator.stripPreamble(real)).isEqualTo(real);
  }

  @Test
  void removesALongAnnouncement() {
    assertThat(
            MistralTranslator.stripPreamble(
                "Here is the translation preserving all original formatting, proper names,"
                    + " place names, and dates:\n\n15a\n\nindispensable"))
        .isEqualTo("15a\n\nindispensable");
  }

  @Test
  void removesARepeatedHeading() {
    assertThat(MistralTranslator.stripPreamble("# Translation.\n\n# Translation.\n\nI. copy."))
        .isEqualTo("I. copy.");
  }

  @Test
  void keepsASentenceAboutTranslationWithNoColon() {
    // Without the colon this is prose, not the model introducing itself.
    String real = "Here is the translation of the decree that was ordered on 24.4.42\n\nSigned";
    assertThat(MistralTranslator.stripPreamble(real)).isEqualTo(real);
  }

  @Test
  void stripPreambleHandlesEmptyAndNull() {
    assertThat(MistralTranslator.stripPreamble(null)).isEmpty();
    assertThat(MistralTranslator.stripPreamble("   ")).isEmpty();
  }
}
