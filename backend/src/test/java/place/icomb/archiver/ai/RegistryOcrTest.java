package place.icomb.archiver.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** OCR configuration came from the environment while the admin page edited rows nothing read. */
class RegistryOcrTest {

  private AiRegistry.Registration registration(ObjectNode settings, int maxBatchSize) {
    return new AiRegistry.Registration(
        "mistral:mistral-ocr-latest",
        AiCapability.OCR,
        "mistral",
        "mistral-ocr-latest",
        "https://api.mistral.ai",
        "/v1/ocr",
        "MISTRAL_API_KEY",
        maxBatchSize,
        1,
        true,
        settings);
  }

  private ObjectNode settings() {
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("maxBatchBytes", 209715200L);
    s.put("pagesPerMinute", 1250);
    return s;
  }

  @Test
  void takesModelEndpointAndBatchLimitsFromTheRow() {
    var ocr = new RegistryOcr(registration(settings(), 1000), "key");

    assertThat(ocr.model()).isEqualTo("mistral-ocr-latest");
    assertThat(ocr.endpoint()).isEqualTo("https://api.mistral.ai/v1/ocr");
    assertThat(ocr.maxBatchPages()).isEqualTo(1000);
    assertThat(ocr.maxBatchBytes()).isEqualTo(209715200L);
    assertThat(ocr.pagesPerMinute()).isEqualTo(1250);
  }

  @Test
  void anEnabledRowWithNoCredentialIsNotConsideredConfigured() {
    // Otherwise OCR would claim jobs and fail every one against a provider that rejects them.
    var ocr = new RegistryOcr(registration(settings(), 1000), "");

    assertThat(ocr.isConfigured()).isFalse();
  }

  @Test
  void fallsBackToTheByteCeilingThatActuallyBinds() {
    // Page images run from 32 kB to 7.7 MB, so a batch fills by size long before it fills by
    // count. A row that omits the setting must not default to something unbounded.
    var ocr = new RegistryOcr(registration(JsonNodeFactory.instance.objectNode(), 1000), "key");

    assertThat(ocr.maxBatchBytes()).isEqualTo(209715200L);
    assertThat(ocr.pagesPerMinute()).isEqualTo(1250);
    assertThat(ocr.tickInterval()).isEqualTo(15000L);
  }

  @Test
  void tickIntervalCanBeOverriddenPerRow() {
    ObjectNode s = settings();
    s.put("tickIntervalMs", 30000L);

    var ocr = new RegistryOcr(registration(s, 1000), "key");

    assertThat(ocr.tickInterval()).isEqualTo(30000L);
  }

  @Test
  void isConfiguredWithACredentialAndAnEndpoint() {
    var ocr = new RegistryOcr(registration(settings(), 1000), "key");

    assertThat(ocr.isConfigured()).isTrue();
  }
}
