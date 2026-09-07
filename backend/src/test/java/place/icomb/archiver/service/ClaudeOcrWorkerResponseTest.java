package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Response parsing for the Anthropic Messages API. Models with adaptive thinking (Opus 5 and later)
 * put a {@code thinking} block first in {@code content}, so the OCR text is not always block 0.
 */
class ClaudeOcrWorkerResponseTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private String extract(String json) throws Exception {
    return ClaudeOcrWorker.extractText(mapper.readTree(json));
  }

  @Test
  void readsTextFromASingleTextBlock() throws Exception {
    assertThat(extract("{\"content\":[{\"type\":\"text\",\"text\":\"Der Reichsprotektor\"}]}"))
        .isEqualTo("Der Reichsprotektor");
  }

  @Test
  void skipsLeadingThinkingBlock() throws Exception {
    String json =
        """
        {"content":[
          {"type":"thinking","thinking":"The page is German typescript."},
          {"type":"text","text":"Der Reichsprotektor"}
        ]}
        """;
    assertThat(extract(json)).isEqualTo("Der Reichsprotektor");
  }

  @Test
  void joinsMultipleTextBlocks() throws Exception {
    String json =
        """
        {"content":[
          {"type":"thinking","thinking":""},
          {"type":"text","text":"first page half"},
          {"type":"text","text":"second page half"}
        ]}
        """;
    assertThat(extract(json)).isEqualTo("first page half\nsecond page half");
  }

  @Test
  void failsWhenResponseCarriesNoTextBlock() {
    assertThatThrownBy(() -> extract("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"x\"}]}"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("no text block");
  }
}
