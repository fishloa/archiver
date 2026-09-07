package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Response parsing for Mistral's OCR endpoint, which returns {@code pages[].markdown} rather than
 * the chat endpoint's {@code choices[].message.content}.
 */
class MistralOcrWorkerResponseTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private String extract(String json) throws Exception {
    return MistralOcrWorker.extractText(mapper.readTree(json));
  }

  @Test
  void readsMarkdownFromASinglePage() throws Exception {
    assertThat(extract("{\"pages\":[{\"index\":0,\"markdown\":\"Der Reichsprotektor\"}]}"))
        .isEqualTo("Der Reichsprotektor");
  }

  @Test
  void joinsMultiplePagesInOrder() throws Exception {
    String json =
        """
        {"pages":[
          {"index":0,"markdown":"first page"},
          {"index":1,"markdown":"second page"}
        ]}
        """;
    assertThat(extract(json)).isEqualTo("first page\n\nsecond page");
  }

  @Test
  void skipsBlankPagesRatherThanEmittingSeparators() throws Exception {
    String json =
        """
        {"pages":[
          {"index":0,"markdown":"only real content"},
          {"index":1,"markdown":"   "}
        ]}
        """;
    assertThat(extract(json)).isEqualTo("only real content");
  }

  @Test
  void failsWhenResponseCarriesNoPageMarkdown() {
    assertThatThrownBy(() -> extract("{\"pages\":[]}"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("no page markdown");
  }

  @Test
  void failsRatherThanReturningEmptyWhenEveryPageIsBlank() {
    assertThatThrownBy(() -> extract("{\"pages\":[{\"index\":0,\"markdown\":\"\"}]}"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("no page markdown");
  }
}
