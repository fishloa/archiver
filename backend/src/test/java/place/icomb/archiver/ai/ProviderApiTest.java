package place.icomb.archiver.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * provider used to be free text that nothing read, so a translation row could name any provider and
 * still be submitted through Mistral's batch API.
 */
class ProviderApiTest {

  @Test
  void onlyProvidersWithAnAdapterAreResolvable() {
    assertThat(ProviderApi.byId("mistral-batch")).contains(ProviderApi.MISTRAL_BATCH);
    assertThat(ProviderApi.byId("openai")).contains(ProviderApi.OPENAI);
    // The old free-text values named brands, not protocols.
    assertThat(ProviderApi.byId("deepinfra")).isEmpty();
    assertThat(ProviderApi.byId("anthropic")).isEmpty();
    assertThat(ProviderApi.byId(null)).isEmpty();
  }

  @Test
  void aProviderOnlyOffersCapabilitiesItHasAnAdapterFor() {
    assertThat(ProviderApi.MISTRAL_BATCH.supports(AiCapability.TRANSLATION)).isTrue();
    assertThat(ProviderApi.MISTRAL_BATCH.supports(AiCapability.OCR)).isTrue();
    // No Mistral embedding adapter exists, so it must not be offered as one.
    assertThat(ProviderApi.MISTRAL_BATCH.supports(AiCapability.EMBEDDING)).isFalse();

    assertThat(ProviderApi.OPENAI.supports(AiCapability.EMBEDDING)).isTrue();
    assertThat(ProviderApi.OPENAI.supports(AiCapability.TRANSLATION)).isTrue();
    assertThat(ProviderApi.OPENAI.supports(AiCapability.OCR)).isFalse();
  }

  @Test
  void batchStyleSaysHowWorkIsSubmitted() {
    // Mistral runs work as an asynchronous job; everything else takes it inline.
    assertThat(ProviderApi.MISTRAL_BATCH.batchStyle()).isEqualTo(BatchStyle.ASYNC_JOB);
    assertThat(ProviderApi.OPENAI.batchStyle()).isEqualTo(BatchStyle.INLINE_ARRAY);
  }

  @Test
  void eachCapabilityCarriesItsOwnEndpointPath() {
    assertThat(ProviderApi.MISTRAL_BATCH.endpointPathFor(AiCapability.OCR)).isEqualTo("/v1/ocr");
    assertThat(ProviderApi.MISTRAL_BATCH.endpointPathFor(AiCapability.TRANSLATION))
        .isEqualTo("/v1/chat/completions");
    assertThat(ProviderApi.OPENAI.endpointPathFor(AiCapability.EMBEDDING)).isEqualTo("/embeddings");
  }

  @Test
  @SuppressWarnings("unchecked")
  void theDescriptionIsEnoughToBuildAFormWithoutKnowingAnyProvider() {
    List<Map<String, Object>> all = ProviderApi.describeAll();

    assertThat(all).isNotEmpty();
    Map<String, Object> mistral =
        all.stream().filter(p -> "mistral-batch".equals(p.get("id"))).findFirst().orElseThrow();

    assertThat(mistral).containsKeys("id", "label", "batchStyle", "defaultBaseUrl", "capabilities");
    Map<String, Object> capabilities = (Map<String, Object>) mistral.get("capabilities");
    assertThat(capabilities).containsKeys("TRANSLATION", "OCR");

    Map<String, Object> ocr = (Map<String, Object>) capabilities.get("OCR");
    assertThat(ocr.get("endpointPath")).isEqualTo("/v1/ocr");

    List<Map<String, Object>> settings = (List<Map<String, Object>>) ocr.get("settings");
    // Every field the UI renders must describe itself: key, label, type, default.
    assertThat(settings).isNotEmpty();
    assertThat(settings.get(0)).containsKeys("key", "label", "type", "help", "default");
    assertThat(settings.stream().map(x -> x.get("key")))
        .contains("pagesPerMinute", "maxBatchBytes");
  }

  @Test
  @SuppressWarnings("unchecked")
  void settingsAreScopedToTheCapabilityTheyBelongTo() {
    Map<String, Object> openai =
        ProviderApi.describeAll().stream()
            .filter(p -> "openai".equals(p.get("id")))
            .findFirst()
            .orElseThrow();
    Map<String, Object> capabilities = (Map<String, Object>) openai.get("capabilities");

    List<Map<String, Object>> embedding =
        (List<Map<String, Object>>)
            ((Map<String, Object>) capabilities.get("EMBEDDING")).get("settings");
    List<Map<String, Object>> translation =
        (List<Map<String, Object>>)
            ((Map<String, Object>) capabilities.get("TRANSLATION")).get("settings");

    // Dimensions and the query prefix are embedding concepts; offering them on a translation row
    // is how a form invites a meaningless configuration.
    assertThat(embedding.stream().map(x -> x.get("key"))).contains("dimensions", "queryPrefix");
    assertThat(translation.stream().map(x -> x.get("key"))).doesNotContain("dimensions");
  }

  @Test
  void aProviderThatCannotBatchIsCappedAtOne() {
    for (ProviderApi api : ProviderApi.values()) {
      if (api.batchStyle() == BatchStyle.SINGLE) {
        assertThat(api.maxBatchSize()).isEqualTo(1);
      }
      assertThat(api.minBatchSize()).isGreaterThanOrEqualTo(1);
      assertThat(api.maxBatchSize()).isGreaterThanOrEqualTo(api.minBatchSize());
    }
  }
}
