package place.icomb.archiver.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Which handwriting model runs is data, not code, and the choice has to survive a row that omits
 * settings: a page sent to the wrong model costs a credit from a monthly allowance of fifty.
 */
class TranskribusConfigTest {

  private AiRegistry.Registration registration(ObjectNode settings) {
    return new AiRegistry.Registration(
        "transkribus:free-supermodels",
        AiCapability.OCR,
        "transkribus",
        "Transkribus super models (per language)",
        "https://transkribus.eu/processing/v1",
        "/processes",
        "TRANSKRIBUS_PASSWORD",
        1,
        2,
        true,
        settings);
  }

  private ObjectNode fullSettings() {
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    ObjectNode byLang = s.putObject("htrByLang");
    byLang.put("de", 265149);
    byLang.put("cs", 263129);
    byLang.put("en", 265029);
    s.put("htrId", 51170);
    s.putObject("printHtrByLang").put("cs", 42352);
    s.put("printHtrId", 37545);
    s.put("monthlyCredits", 50);
    s.put("usernameEnv", "TRANSKRIBUS_USERNAME");
    return s;
  }

  private MockEnvironment env() {
    return new MockEnvironment()
        .withProperty("TRANSKRIBUS_USERNAME", "researcher@example.com")
        .withProperty("TRANSKRIBUS_PASSWORD", "s3cret");
  }

  @Test
  void picksTheModelTrainedForTheContentLanguage() {
    var config = new TranskribusConfig(registration(fullSettings()), env());

    assertThat(config.htrId("de")).isEqualTo(265149);
    assertThat(config.htrId("cs")).isEqualTo(263129);
    assertThat(config.htrId("en")).isEqualTo(265029);
  }

  @Test
  void fallsBackToTheMultilingualModelForAnUnmappedLanguage() {
    // French, Italian and Latin all appear in this archive; none has its own row.
    var config = new TranskribusConfig(registration(fullSettings()), env());

    assertThat(config.htrId("fr")).isEqualTo(51170);
    assertThat(config.htrId(null)).isEqualTo(51170);
    assertThat(config.htrId("")).isEqualTo(51170);
  }

  @Test
  void languageCodeCaseDoesNotChangeTheModel() {
    var config = new TranskribusConfig(registration(fullSettings()), env());

    assertThat(config.htrId("DE")).isEqualTo(265149);
  }

  @Test
  void typescriptGoesToThePrintModelNotTheHandwritingOne() {
    var config = new TranskribusConfig(registration(fullSettings()), env());

    assertThat(config.printHtrId("cs")).isEqualTo(42352);
    assertThat(config.printHtrId("de")).isEqualTo(37545);
  }

  @Test
  void aRowWithNoSettingsStillNamesAModel() {
    // A row inserted by hand, or a settings key renamed upstream, must not send htrId 0.
    var config = new TranskribusConfig(registration(JsonNodeFactory.instance.objectNode()), env());

    assertThat(config.htrId("de")).isEqualTo(51170);
    assertThat(config.printHtrId("de")).isEqualTo(37545);
    assertThat(config.monthlyCredits()).isEqualTo(50);
    assertThat(config.clientId()).isEqualTo("processing-api-client");
    assertThat(config.tokenUrl()).contains("account.readcoop.eu");
  }

  @Test
  void anEnabledRowWithNoPasswordIsNotConfigured() {
    // Otherwise the worker claims pages and fails each one, and every failure is a spent credit.
    var config =
        new TranskribusConfig(
            registration(fullSettings()),
            new MockEnvironment().withProperty("TRANSKRIBUS_USERNAME", "researcher@example.com"));

    assertThat(config.isConfigured()).isFalse();
  }

  @Test
  void anEnabledRowWithNoUsernameIsNotConfigured() {
    // The password grant needs both halves; a password alone cannot get a token.
    var config =
        new TranskribusConfig(
            registration(fullSettings()),
            new MockEnvironment().withProperty("TRANSKRIBUS_PASSWORD", "s3cret"));

    assertThat(config.isConfigured()).isFalse();
  }

  @Test
  void aFullyCredentialledRowIsConfigured() {
    var config = new TranskribusConfig(registration(fullSettings()), env());

    assertThat(config.isConfigured()).isTrue();
    assertThat(config.model()).isEqualTo("Transkribus super models (per language)");
  }
}
