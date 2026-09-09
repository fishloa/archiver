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
}
