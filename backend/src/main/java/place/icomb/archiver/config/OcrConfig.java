package place.icomb.archiver.config;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import place.icomb.archiver.ai.AiCapability;
import place.icomb.archiver.ai.AiRegistry;
import place.icomb.archiver.ai.RegistryOcr;

/**
 * The OCR model this deployment uses.
 *
 * <p>Resolved from the registry, so disabling OCR or moving it to another provider is a row an
 * admin can edit rather than a compose file and a redeploy. Falls back to archiver.ocr.mistral.*
 * when no row is enabled, so a deployment that has not been migrated behaves exactly as before.
 */
@Configuration
public class OcrConfig {

  private static final Logger log = LoggerFactory.getLogger(OcrConfig.class);

  @Bean
  public RegistryOcr registryOcr(
      AiRegistry registry,
      @Value("${archiver.ocr.mistral.enabled:false}") boolean envEnabled,
      @Value("${archiver.ocr.mistral.api-key:}") String envApiKey,
      @Value("${archiver.ocr.mistral.model:mistral-ocr-latest}") String envModel,
      @Value("${archiver.ocr.mistral.base-url:https://api.mistral.ai}") String envBaseUrl,
      @Value("${archiver.ocr.mistral.tick-interval:15000}") long envTickInterval,
      @Value("${archiver.ocr.mistral.max-batch-pages:1000}") int envMaxBatchPages,
      @Value("${archiver.ocr.mistral.max-batch-bytes:209715200}") long envMaxBatchBytes,
      @Value("${archiver.ocr.mistral.pages-per-minute:1250}") int envPagesPerMinute) {

    var registered = registry.best(AiCapability.OCR);
    if (registered.isPresent()) {
      var r = registered.get();
      var ocr = new RegistryOcr(r, registry.credential(r));
      log.info(
          "OCR from registry: {} ({}, {} pages/batch, {} pages/min)",
          ocr.model(),
          ocr.endpoint(),
          ocr.maxBatchPages(),
          ocr.pagesPerMinute());
      return ocr;
    }

    ObjectNode settings = JsonNodeFactory.instance.objectNode();
    settings.put("maxBatchBytes", envMaxBatchBytes);
    settings.put("pagesPerMinute", envPagesPerMinute);
    settings.put("tickIntervalMs", envTickInterval);
    var fallback =
        new AiRegistry.Registration(
            "env:" + envModel,
            AiCapability.OCR,
            "mistral",
            envModel,
            envBaseUrl,
            "/v1/ocr",
            null,
            envMaxBatchPages,
            1,
            envEnabled,
            settings);
    // An env-configured deployment only runs OCR when the flag says so; a registry-configured one
    // expresses the same thing by enabling or disabling the row.
    log.info("No enabled OCR row; falling back to archiver.ocr.mistral.* (enabled={})", envEnabled);
    return new RegistryOcr(fallback, envEnabled ? envApiKey : "");
  }
}
