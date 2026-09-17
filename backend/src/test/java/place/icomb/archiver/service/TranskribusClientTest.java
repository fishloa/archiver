package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import place.icomb.archiver.ai.AiCapability;
import place.icomb.archiver.ai.AiRegistry;
import place.icomb.archiver.ai.TranskribusConfig;

/**
 * The Transkribus call sequence, against a stub of its own API.
 *
 * <p>Submit, poll, take the text. The shapes asserted here were read from the live OpenAPI document
 * (Metagrapho API 1.13.1); if any of them drift, every page this engine reads is a wasted credit,
 * so they are pinned.
 */
class TranskribusClientTest {

  private HttpServer server;
  private String baseUrl;
  private final AtomicInteger tokenRequests = new AtomicInteger();
  private final AtomicInteger statusRequests = new AtomicInteger();
  private volatile String lastSubmitBody;
  private volatile String lastAuthHeader;

  /** Status values the stub returns in order, so a test can make the page wait. */
  private volatile List<String> statusSequence = List.of("FINISHED");

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);

    server.createContext(
        "/token",
        exchange -> {
          tokenRequests.incrementAndGet();
          respond(
              exchange,
              200,
              "{\"access_token\":\"tok-" + tokenRequests.get() + "\",\"expires_in\":300}");
        });

    server.createContext(
        "/processing/v1/processes",
        exchange -> {
          lastAuthHeader = exchange.getRequestHeaders().getFirst("Authorization");
          String path = exchange.getRequestURI().getPath();

          if ("POST".equals(exchange.getRequestMethod())) {
            lastSubmitBody =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, "{\"processId\":3866314,\"status\":\"CREATED\"}");
            return;
          }
          if (path.endsWith("/page")) {
            respond(exchange, 200, "<PcGts><Page/></PcGts>");
            return;
          }
          int call = statusRequests.getAndIncrement();
          String status = statusSequence.get(Math.min(call, statusSequence.size() - 1));
          String body =
              "FINISHED".equals(status)
                  ? """
                    {"processId":3866314,"status":"FINISHED",
                     "content":{"text":"Wien III., Metternichgasse 10",
                     "regions":[{"id":"region_1","lines":[{"id":"line_1","text":"Wien III."}]}]}}
                    """
                  : "{\"processId\":3866314,\"status\":\"" + status + "\"}";
          respond(exchange, 200, body);
        });

    server.start();
    baseUrl = "http://localhost:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  private static void respond(HttpExchange exchange, int code, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(code, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private TranskribusConfig config() {
    ObjectNode settings = JsonNodeFactory.instance.objectNode();
    settings.put("tokenUrl", baseUrl + "/token");
    settings.put("clientId", "processing-api-client");
    settings.put("pollIntervalMs", 5);
    settings.put("maxPollMs", 2000);
    settings.put("htrId", 51170);
    var registration =
        new AiRegistry.Registration(
            "transkribus:test",
            AiCapability.OCR,
            "transkribus",
            "test model",
            baseUrl + "/processing/v1",
            "/processes",
            "TRANSKRIBUS_PASSWORD",
            1,
            2,
            true,
            settings);
    return new TranskribusConfig(
        registration,
        new MockEnvironment()
            .withProperty("TRANSKRIBUS_USERNAME", "researcher@example.com")
            .withProperty("TRANSKRIBUS_PASSWORD", "s3cret"));
  }

  private TranskribusClient client() {
    return new TranskribusClient(config(), HttpClient.newHttpClient());
  }

  @Test
  void submitsTheModelIdAndImageAndReturnsTheTranscription() throws Exception {
    var result = client().transcribe("page-bytes".getBytes(StandardCharsets.UTF_8), 265149);

    assertThat(result.text()).isEqualTo("Wien III., Metternichgasse 10");
    assertThat(result.processId()).isEqualTo(3866314L);
    assertThat(result.htrId()).isEqualTo(265149);

    // The request shape the API actually requires: htrId and languageModel nested under
    // config.textRecognition, and the image under image.base64.
    assertThat(lastSubmitBody).contains("\"htrId\":265149");
    assertThat(lastSubmitBody).contains("\"languageModel\":\"built-in\"");
    assertThat(lastSubmitBody).contains("\"base64\":");
    assertThat(lastAuthHeader).isEqualTo("Bearer tok-1");
  }

  @Test
  void pollsUntilTheProcessFinishes() throws Exception {
    statusSequence = List.of("CREATED", "WAITING", "RUNNING", "FINISHED");

    var result = client().transcribe("page".getBytes(StandardCharsets.UTF_8), 51170);

    assertThat(result.text()).isNotBlank();
    assertThat(statusRequests.get()).isEqualTo(4);
  }

  @Test
  void aFailedProcessIsAnError() {
    statusSequence = List.of("FAILED");

    assertThatThrownBy(() -> client().transcribe("page".getBytes(StandardCharsets.UTF_8), 51170))
        .isInstanceOf(TranskribusClient.TranskribusException.class)
        .hasMessageContaining("failed");
  }

  @Test
  void aProcessThatNeverFinishesGivesUpRatherThanPollingForever() {
    statusSequence = List.of("RUNNING");

    assertThatThrownBy(() -> client().transcribe("page".getBytes(StandardCharsets.UTF_8), 51170))
        .isInstanceOf(TranskribusClient.TranskribusException.class)
        .hasMessageContaining("still RUNNING");
  }

  @Test
  void theTokenIsFetchedOnceAndReused() throws Exception {
    var client = client();
    client.transcribe("one".getBytes(StandardCharsets.UTF_8), 51170);
    client.transcribe("two".getBytes(StandardCharsets.UTF_8), 51170);

    // A token request per page would double the round trips for every credit spent.
    assertThat(tokenRequests.get()).isEqualTo(1);
  }

  @Test
  void anOversizeImageIsRejectedBeforeItIsSent() {
    // The API caps base64 length; finding out from a 413 would cost the round trip and possibly
    // the credit.
    byte[] huge = new byte[TranskribusClient.MAX_BASE64_LENGTH];

    assertThatThrownBy(() -> client().transcribe(huge, 51170))
        .isInstanceOf(TranskribusClient.TranskribusException.class)
        .hasMessageContaining("over the API's");
  }

  @Test
  void pageXmlIsFetchableForLineGeometry() throws Exception {
    var client = client();
    client.transcribe("page".getBytes(StandardCharsets.UTF_8), 51170);

    assertThat(client.pageXml(3866314L)).contains("PcGts");
  }

  @Test
  void linesCanBeReadOutOfTheStatusBody() throws Exception {
    var client = client();
    client.transcribe("page".getBytes(StandardCharsets.UTF_8), 51170);

    assertThat(TranskribusClient.textLines(client.status(3866314L))).containsExactly("Wien III.");
  }
}
