package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Response parsing for Mistral's OCR endpoint, which returns {@code pages[].markdown} rather than
 * the chat endpoint's {@code choices[].message.content}.
 */
class MistralOcrWorkerResponseTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private String extract(String json) throws Exception {
    return MistralBatchOcrWorker.extractText(mapper.readTree(json));
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
  void returnsEmptyWhenResponseCarriesNoPages() throws Exception {
    assertThat(extract("{\"pages\":[]}")).isEmpty();
  }

  @Test
  void returnsEmptyWhenEveryPageIsBlank() throws Exception {
    // A blank verso is a correct OCR result, not an error. Throwing here failed the job, the
    // audit retried it twice more, and it landed terminally failed — three billed calls for a
    // blank page that archival scans are full of.
    assertThat(extract("{\"pages\":[{\"index\":0,\"markdown\":\"\"}]}")).isEmpty();
  }
}
