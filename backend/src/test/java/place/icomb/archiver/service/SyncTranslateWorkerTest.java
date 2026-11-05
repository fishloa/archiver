package place.icomb.archiver.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import place.icomb.archiver.ai.AiCapability;
import place.icomb.archiver.ai.AiRegistry;
import place.icomb.archiver.ai.ChatTranslator;

class SyncTranslateWorkerTest {

  private static WireMockServer server;

  private final ResilientHttpClient http = ResilientHttpClient.builder().maxRetries(1).build();

  @BeforeAll
  static void start() {
    server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    server.start();
  }

  @AfterAll
  static void stop() {
    server.stop();
  }

  @BeforeEach
  void reset() {
    server.resetAll();
  }

  private static ChatTranslator translator() {
    return new ChatTranslator(
        new AiRegistry.Registration(
            "t",
            AiCapability.TRANSLATION,
            "openai",
            "m-1",
            "http://localhost:" + server.port(),
            "/v1/chat/completions",
            null,
            1,
            1,
            true,
            JsonNodeFactory.instance.objectNode()),
        "k");
  }

  @Test
  void oneCallSendsTheBodyWithItsModelAndReturnsTheCleanedAnswer() throws Exception {
    server.stubFor(
        post(urlEqualTo("/v1/chat/completions"))
            .willReturn(
                okJson(
                    "{\"choices\":[{\"message\":{\"content\":"
                        + "\"Here is the Czech translation:\\n\\nTajné\"}}]}")));
    ChatTranslator t = translator();

    String out = SyncTranslateWorker.translate(http, t, "k", t.request("Geheim", "de", "cs"));

    assertThat(out).isEqualTo("Tajné");
    server.verify(
        postRequestedFor(urlEqualTo("/v1/chat/completions"))
            .withHeader("Authorization", equalTo("Bearer k"))
            .withRequestBody(matchingJsonPath("$.model", equalTo("m-1")))
            .withRequestBody(containing("into Czech")));
  }

  @Test
  void aRefusalIsAnErrorCarryingTheStatusAndTheProvidersWords() {
    server.stubFor(
        post(urlEqualTo("/v1/chat/completions"))
            .willReturn(aResponse().withStatus(400).withBody("{\"message\":\"quota exceeded\"}")));
    ChatTranslator t = translator();

    assertThatThrownBy(
            () -> SyncTranslateWorker.translate(http, t, "k", t.request("Geheim", null, "cs")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP 400")
        .hasMessageContaining("quota exceeded");
  }
}
