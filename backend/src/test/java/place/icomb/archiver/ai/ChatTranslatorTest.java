package place.icomb.archiver.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What Mistral actually returns, as opposed to what it was asked for.
 *
 * <p>It wrapped 13% of this archive's translations in a code fence and opened 5,454 pages by
 * announcing itself, despite being told to output only the translation. Stored that way a page
 * renders as literal pipes and hashes instead of a table, and every one of those pages opens with a
 * title the Reichsprotektor never wrote.
 */
class ChatTranslatorTest {

  @Test
  void removesAnEnclosingMarkdownFence() {
    String wrapped = "```markdown\n| Name | Date |\n| --- | --- |\n| Czernin | 1943 |\n```";
    assertThat(ChatTranslator.unwrapCodeFence(wrapped))
        .startsWith("| Name | Date |")
        .doesNotContain("```");
  }

  @Test
  void removesAPlainFence() {
    assertThat(ChatTranslator.unwrapCodeFence("```\nsome text\n```")).isEqualTo("some text");
  }

  @Test
  void leavesUnfencedTextAlone() {
    String plain = "# Heading\n\nSome prose about the Reichsprotektor.";
    assertThat(ChatTranslator.unwrapCodeFence(plain)).isEqualTo(plain);
  }

  @Test
  void keepsAFenceThatIsPartOfTheContent() {
    // A fence in the middle of a page is content, not a wrapper.
    String mixed = "Some prose\n\n```\nquoted block\n```\n\nmore prose";
    assertThat(ChatTranslator.unwrapCodeFence(mixed)).isEqualTo(mixed);
  }

  @Test
  void handlesEmptyAndNull() {
    assertThat(ChatTranslator.unwrapCodeFence(null)).isEmpty();
    assertThat(ChatTranslator.unwrapCodeFence("   ")).isEmpty();
  }

  // The same model also announced what it was about to do: 4,043 pages opened with a
  // "### Translation" heading and 1,363 with "Here is the translation:". Once headings render
  // large and ruled, each of those is a title on a document that never had one.

  @Test
  void removesAnAddedTranslationHeading() {
    assertThat(ChatTranslator.stripPreamble("### Translation\n\n3 August 1942\n\nDept. V"))
        .isEqualTo("3 August 1942\n\nDept. V");
  }

  @Test
  void removesASpokenPreamble() {
    assertThat(ChatTranslator.stripPreamble("Here is the translation:\n\nSecret Reich Matter"))
        .isEqualTo("Secret Reich Matter");
    assertThat(
            ChatTranslator.stripPreamble(
                "Here is the English translation of the document:\n\nSecret"))
        .isEqualTo("Secret");
  }

  @Test
  void removesABareLabel() {
    assertThat(ChatTranslator.stripPreamble("Translation:\n\nSecret Reich Matter"))
        .isEqualTo("Secret Reich Matter");
  }

  @Test
  void keepsAHeadingThatIsPartOfTheDocument() {
    String real = "# Secret Reich Matter\n\nLetter\n\nTo M-Standartenf\u00fchrer Dr. Brandt";
    assertThat(ChatTranslator.stripPreamble(real)).isEqualTo(real);
  }

  @Test
  void keepsProseThatMerelyMentionsTranslation() {
    // A page about translation work is content, not a preamble.
    String real = "The translation of the decree was ordered on 24.4.42.\n\nSigned, Frank";
    assertThat(ChatTranslator.stripPreamble(real)).isEqualTo(real);
  }

  @Test
  void removesALongAnnouncement() {
    assertThat(
            ChatTranslator.stripPreamble(
                "Here is the translation preserving all original formatting, proper names,"
                    + " place names, and dates:\n\n15a\n\nindispensable"))
        .isEqualTo("15a\n\nindispensable");
  }

  @Test
  void removesARepeatedHeading() {
    assertThat(ChatTranslator.stripPreamble("# Translation.\n\n# Translation.\n\nI. copy."))
        .isEqualTo("I. copy.");
  }

  @Test
  void keepsASentenceAboutTranslationWithNoColon() {
    // Without the colon this is prose, not the model introducing itself.
    String real = "Here is the translation of the decree that was ordered on 24.4.42\n\nSigned";
    assertThat(ChatTranslator.stripPreamble(real)).isEqualTo(real);
  }

  @Test
  void stripPreambleHandlesEmptyAndNull() {
    assertThat(ChatTranslator.stripPreamble(null)).isEmpty();
    assertThat(ChatTranslator.stripPreamble("   ")).isEmpty();
  }

  private static ChatTranslator translator() {
    return new ChatTranslator(
        new AiRegistry.Registration(
            "t",
            AiCapability.TRANSLATION,
            "openai",
            "m",
            "http://localhost",
            "/v1/chat/completions",
            null,
            1,
            1,
            true,
            JsonNodeFactory.instance.objectNode()),
        "k");
  }

  private static String content(Map<String, Object> request) {
    return String.valueOf(((Map<?, ?>) ((List<?>) request.get("messages")).get(0)).get("content"));
  }

  @Test
  void theEnglishRequestIsExactlyWhatItWas() {
    String old =
        "Translate the following German or Czech archival document text into English. "
            + "Preserve all proper names, place names, dates, reference numbers and titles exactly "
            + "as written. Keep the markdown structure. Do not add commentary or explanation. "
            + "Output only the translation.\n\n";
    Map<String, Object> expected =
        Map.of(
            "max_tokens",
            4000,
            "temperature",
            0.1,
            "messages",
            List.of(Map.of("role", "user", "content", old + "Seite 1")));

    assertThat(translator().request("Seite 1", null)).isEqualTo(expected);
    assertThat(translator().request("Seite 1", "de", "en")).isEqualTo(expected);
  }

  @Test
  void aCzechOrGermanRequestNamesItsTargetAndKeepsTheEvidenceWording() {
    String czech = content(translator().request("Seite 1", "de", "cs"));
    assertThat(czech)
        .startsWith("Translate the following German or Czech archival document text into Czech. ")
        .contains("Preserve all proper names, place names, dates, reference numbers and titles")
        .endsWith("Output only the translation.\n\nSeite 1");
    assertThat(content(translator().request("Strana 1", "cs", "de")))
        .contains("text into German. ");
  }

  @Test
  void removesCzechAndGermanPreambles() {
    assertThat(ChatTranslator.stripPreamble("Here is the Czech translation:\n\nTajné"))
        .isEqualTo("Tajné");
    assertThat(ChatTranslator.stripPreamble("### Czech translation\n\nTajné")).isEqualTo("Tajné");
    assertThat(ChatTranslator.stripPreamble("Překlad:\n\nTajné")).isEqualTo("Tajné");
    assertThat(ChatTranslator.stripPreamble("Übersetzung:\n\nGeheim")).isEqualTo("Geheim");
    assertThat(
            ChatTranslator.stripPreamble(
                "Here is the German translation of the document:\n\nGeheim"))
        .isEqualTo("Geheim");
  }

  @Test
  void keepsACzechHeadingThatIsPartOfTheDocument() {
    String real = "# Překlad smlouvy\n\nText";
    assertThat(ChatTranslator.stripPreamble(real)).isEqualTo(real);
  }

  @Test
  void aPreambleBeforeACodeFenceIsRemovedWithTheFence() {
    var body =
        JsonNodeFactory.instance
            .objectNode()
            .set(
                "choices",
                JsonNodeFactory.instance
                    .arrayNode()
                    .add(
                        JsonNodeFactory.instance
                            .objectNode()
                            .set(
                                "message",
                                JsonNodeFactory.instance
                                    .objectNode()
                                    .put(
                                        "content",
                                        "Here is the translated text:\n\n```markdown\n"
                                            + "# Title\n\nBody\n```"))));

    assertThat(translator().parse(body)).isEqualTo("# Title\n\nBody");
  }

  @Test
  void recognisesTranslatedTextAndCzechAndGermanIntroductions() {
    assertThat(ChatTranslator.stripPreamble("Here is the translated version:\n\nX")).isEqualTo("X");
    assertThat(ChatTranslator.stripPreamble("Zde je český překlad:\n\nX")).isEqualTo("X");
    assertThat(ChatTranslator.stripPreamble("Hier ist die deutsche Übersetzung:\n\nX"))
        .isEqualTo("X");
  }

  private static com.fasterxml.jackson.databind.JsonNode answer(String content) {
    var f = JsonNodeFactory.instance;
    return f.objectNode()
        .set(
            "choices",
            f.arrayNode()
                .add(f.objectNode().set("message", f.objectNode().put("content", content))));
  }

  @Test
  void aBareLabelIsKeptWhenTheSourceOpensWithOne() {
    String kept = "### Translation\n\nCertified copy of the birth record";
    assertThat(translator().parse(answer(kept), "Übersetzung\n\nBeglaubigte Abschrift"))
        .isEqualTo(kept);
    assertThat(translator().parse(answer("Překlad:\n\nText"), "# Übersetzung\n\nText"))
        .isEqualTo("Překlad:\n\nText");
    assertThat(translator().parse(answer("Translation:\n\nText"), "Translation:\n\nText"))
        .isEqualTo("Translation:\n\nText");
  }

  @Test
  void aBareLabelTheSourceDoesNotHaveIsStillStripped() {
    assertThat(
            translator()
                .parse(answer("### Translation\n\nCertified copy"), "Beglaubigte Abschrift"))
        .isEqualTo("Certified copy");
    assertThat(translator().parse(answer("### Translation\n\nCertified copy"), null))
        .isEqualTo("Certified copy");
  }

  @Test
  void aSpokenIntroductionIsStrippedWhateverTheSourceSays() {
    assertThat(
            translator()
                .parse(
                    answer("Here is the translation:\n\nCertified copy"),
                    "Übersetzung\n\nBeglaubigte Abschrift"))
        .isEqualTo("Certified copy");
  }
}
