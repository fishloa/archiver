package place.icomb.archiver;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** The one TRANSLATION model a test may call: a WireMock chat endpoint, ranked first. */
public final class TranslatorStub {

  public static final String MODEL = "stub-translator";
  public static final String KEY_ENV = "STUB_TRANSLATE_KEY";
  public static final String PATH = "/v1/chat/completions";

  private TranslatorStub() {}

  public static void register(JdbcTemplate jdbc, WireMockServer server) {
    unregister(jdbc);
    jdbc.update("UPDATE ai_implementation SET enabled = false WHERE capability = 'TRANSLATION'");
    jdbc.update(
        """
        INSERT INTO ai_implementation
            (id, capability, provider, model, base_url, endpoint_path, credential_env,
             max_batch_size, rank, enabled, settings)
        VALUES ('stub:translator', 'TRANSLATION', 'openai', ?, ?, ?, ?, 1, 0, true, '{}')
        """,
        MODEL,
        "http://localhost:" + server.port(),
        PATH,
        KEY_ENV);
  }

  public static void unregister(JdbcTemplate jdbc) {
    jdbc.update("DELETE FROM ai_implementation WHERE id = 'stub:translator'");
  }

  public static void reply(WireMockServer server, String content) {
    server.stubFor(post(urlEqualTo(PATH)).willReturn(okJson(body(content))));
  }

  public static String body(String content) {
    try {
      return new ObjectMapper()
          .writeValueAsString(
              Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  public static int calls(WireMockServer server) {
    return server.findAll(postRequestedFor(urlEqualTo(PATH))).size();
  }
}
