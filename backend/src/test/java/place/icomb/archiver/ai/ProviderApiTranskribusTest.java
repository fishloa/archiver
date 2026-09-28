package place.icomb.archiver.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The admin form is built from what a provider declares about itself. Transkribus declared nothing,
 * so the page rendered no fields for it and the only way to set a model was to edit JSON by hand —
 * which the form could not express and, on save, would have destroyed.
 */
class ProviderApiTranskribusTest {

  private ProviderApi transkribus() {
    return ProviderApi.byId("transkribus").orElseThrow();
  }

  @Test
  void transkribusIsAProviderTheFormCanRender() {
    var provider = transkribus();

    assertThat(provider.supports(AiCapability.OCR)).isTrue();
    assertThat(provider.endpointPathFor(AiCapability.OCR)).isEqualTo("/processes");
    assertThat(provider.settingsFor(AiCapability.OCR)).isNotEmpty();
  }

  @Test
  void theModelSettingsCarryACatalogueToChooseFrom() {
    // A model id typed by hand is unverifiable: 579509 and 263129 look alike and only one reads
    // Czech. The form is told where to fetch the real names.
    var byKey =
        transkribus().settingsFor(AiCapability.OCR).stream()
            .collect(java.util.stream.Collectors.toMap(ProviderApi.Setting::key, s -> s));

    assertThat(byKey.get("htrId").type()).isEqualTo("model");
    assertThat(byKey.get("htrId").optionsUrl()).isEqualTo("/api/admin/transkribus/models");
    assertThat(byKey.get("htrByLang").type()).isEqualTo("modelByLang");
    assertThat(byKey.get("htrByLang").optionsUrl()).isEqualTo("/api/admin/transkribus/models");
  }

  @Test
  void everySettingIsDescribedWithItsOptionsUrl() {
    @SuppressWarnings("unchecked")
    Map<String, Object> described =
        ProviderApi.describeAll().stream()
            .filter(p -> "transkribus".equals(p.get("id")))
            .findFirst()
            .orElseThrow();

    @SuppressWarnings("unchecked")
    Map<String, Object> ocr =
        (Map<String, Object>) ((Map<String, Object>) described.get("capabilities")).get("OCR");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> settings = (List<Map<String, Object>>) ocr.get("settings");

    assertThat(settings).extracting(s -> s.get("key")).contains("htrId", "htrByLang");
    assertThat(settings)
        .filteredOn(s -> "htrByLang".equals(s.get("key")))
        .allSatisfy(
            s -> assertThat(s.get("optionsUrl")).isEqualTo("/api/admin/transkribus/models"));
  }
}
