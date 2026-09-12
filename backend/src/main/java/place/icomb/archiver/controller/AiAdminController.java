package place.icomb.archiver.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.ai.AiCapability;
import place.icomb.archiver.ai.AiRegistry;
import place.icomb.archiver.ai.ProviderApi;

/**
 * Managing which models do which job, and which is preferred.
 *
 * <p>Changing a provider, a model or the order of preference is a row update here rather than a
 * deploy. That is the point: the three things that decided this previously — environment variables
 * for the endpoint, more environment variables for the model, and a Java list for the preference —
 * lived in different places and disagreed, which is how the archive spent two days embedding
 * passages with one model while building queries for another.
 *
 * <p>Credentials are not editable here and are never returned. A row names an environment variable
 * and the value stays in the deployment, so this endpoint cannot leak a key and a database backup
 * does not carry one.
 */
@RestController
@RequestMapping("/api/admin/ai")
public class AiAdminController {

  private static final Logger log = LoggerFactory.getLogger(AiAdminController.class);

  /**
   * Rejects a configuration this build cannot actually run.
   *
   * <p>Provider was free text and nothing read it: every translation row became a chat request
   * submitted through Mistral's batch API whatever the row claimed, so a row pointing at an
   * OpenAI-compatible endpoint would fire POST /v1/files at it and fail every job. Returns the
   * reason, or null when the configuration is one the runtime can honour.
   */
  private String validateProvider(
      String providerId, AiCapability capability, Map<String, Object> body) {
    if (providerId == null || providerId.isBlank()) {
      return "provider is required; choose one of " + providerIds();
    }
    var provider = ProviderApi.byId(providerId);
    if (provider.isEmpty()) {
      return "unknown provider '" + providerId + "'; this build implements " + providerIds();
    }
    ProviderApi api = provider.get();
    if (!api.supports(capability)) {
      return "provider '" + providerId + "' has no " + capability + " adapter";
    }
    Object batch = body.get("maxBatchSize");
    if (batch != null) {
      int size = intOr(batch, 1);
      if (size < api.minBatchSize() || size > api.maxBatchSize()) {
        return "maxBatchSize for "
            + providerId
            + " must be between "
            + api.minBatchSize()
            + " and "
            + api.maxBatchSize()
            + " (batch style "
            + api.batchStyle()
            + ")";
      }
    }
    return null;
  }

  private static String providerIds() {
    return java.util.Arrays.stream(ProviderApi.values())
        .map(ProviderApi::id)
        .collect(java.util.stream.Collectors.joining(", "));
  }

  private final JdbcTemplate jdbc;
  private final AiRegistry registry;

  public AiAdminController(JdbcTemplate jdbc, AiRegistry registry) {
    this.jdbc = jdbc;
    this.registry = registry;
  }

  /** Everything registered, grouped by capability, best first. */
  @GetMapping("/implementations")
  public ResponseEntity<Map<String, Object>> list() {
    Map<String, Object> out = new LinkedHashMap<>();
    for (AiCapability capability : AiCapability.values()) {
      List<Map<String, Object>> rows =
          jdbc.queryForList(
              """
              SELECT id, capability, provider, model, base_url, endpoint_path, credential_env,
                     max_batch_size, rank, enabled, settings::text AS settings, updated_at
              FROM ai_implementation WHERE capability = ? ORDER BY rank
              """,
              capability.name());
      for (Map<String, Object> row : rows) {
        // Never the value, only whether the deployment actually has it. An admin needs to know a
        // row is unusable; nobody needs the key on a web page.
        row.put("credentialPresent", credentialPresent(row.get("credential_env")));
      }
      out.put(capability.name(), rows);
    }
    out.put("capabilities", AiCapability.values());
    return ResponseEntity.ok(out);
  }

  /**
   * The provider protocols this build implements, and what each one needs configured.
   *
   * <p>The admin UI renders its form from this rather than holding a list of its own: a provider is
   * a protocol with an adapter behind it, so the set of valid choices is a property of the backend.
   * Adding a provider is an entry in {@link ProviderApi} plus an adapter, and the UI picks it up
   * without being touched.
   */
  @GetMapping("/providers")
  public ResponseEntity<Map<String, Object>> providers() {
    return ResponseEntity.ok(Map.of("providers", ProviderApi.describeAll()));
  }

  /** Registers a model. */
  @PostMapping("/implementations")
  public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
    String id = str(body.get("id"));
    String capability = str(body.get("capability"));
    if (id.isBlank() || capability.isBlank() || str(body.get("model")).isBlank()) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "id, capability and model are required"));
    }
    if (!isCapability(capability)) {
      return ResponseEntity.badRequest().body(Map.of("error", "unknown capability: " + capability));
    }
    String provider = str(body.getOrDefault("provider", ""));
    var problem = validateProvider(provider, AiCapability.valueOf(capability), body);
    if (problem != null) {
      return ResponseEntity.badRequest().body(Map.of("error", problem));
    }
    try {
      jdbc.update(
          """
          INSERT INTO ai_implementation
              (id, capability, provider, model, base_url, endpoint_path, credential_env,
               max_batch_size, rank, enabled, settings)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
          """,
          id,
          capability,
          provider,
          str(body.get("model")),
          str(body.getOrDefault("baseUrl", "")),
          emptyToNull(str(body.get("endpointPath"))),
          emptyToNull(str(body.get("credentialEnv"))),
          intOr(body.get("maxBatchSize"), 1),
          intOr(body.get("rank"), nextRank(capability)),
          !Boolean.FALSE.equals(body.get("enabled")),
          str(body.getOrDefault("settings", "{}")));
    } catch (DuplicateKeyException e) {
      return ResponseEntity.status(409)
          .body(Map.of("error", "an implementation with that id or rank already exists"));
    }
    log.info("Registered AI implementation {} for {}", id, capability);
    return ResponseEntity.status(201).body(Map.of("id", id));
  }

  /**
   * Updates a model's configuration. Its id and capability are fixed once history refers to them.
   */
  @PutMapping("/implementations/{id}")
  public ResponseEntity<?> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
    if (registry.byId(id).isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    var existing = registry.byId(id).orElseThrow();
    if (body.get("provider") != null) {
      var problem = validateProvider(str(body.get("provider")), existing.capability(), body);
      if (problem != null) {
        return ResponseEntity.badRequest().body(Map.of("error", problem));
      }
    }
    int updated =
        jdbc.update(
            """
            UPDATE ai_implementation SET
                provider = COALESCE(?, provider),
                model = COALESCE(?, model),
                base_url = COALESCE(?, base_url),
                endpoint_path = COALESCE(?, endpoint_path),
                credential_env = COALESCE(?, credential_env),
                max_batch_size = COALESCE(?, max_batch_size),
                enabled = COALESCE(?, enabled),
                settings = COALESCE(?::jsonb, settings),
                updated_at = now()
            WHERE id = ?
            """,
            nullable(body, "provider"),
            nullable(body, "model"),
            nullable(body, "baseUrl"),
            nullable(body, "endpointPath"),
            nullable(body, "credentialEnv"),
            body.get("maxBatchSize") == null ? null : intOr(body.get("maxBatchSize"), 1),
            body.get("enabled") == null ? null : Boolean.TRUE.equals(body.get("enabled")),
            nullable(body, "settings"),
            id);
    return updated == 0 ? ResponseEntity.notFound().build() : ResponseEntity.ok(Map.of("id", id));
  }

  /**
   * Sets the preference order for a capability.
   *
   * <p>Takes the whole order rather than one row's rank: (capability, rank) is unique, so moving
   * one implementation past another collides unless both move together. Applied in one transaction
   * through a temporary offset, so a failure leaves the previous order intact rather than a
   * half-applied one.
   */
  @PutMapping("/capabilities/{capability}/order")
  @Transactional
  public ResponseEntity<?> reorder(
      @PathVariable String capability, @RequestBody Map<String, Object> body) {
    if (!isCapability(capability)) {
      return ResponseEntity.badRequest().body(Map.of("error", "unknown capability"));
    }
    @SuppressWarnings("unchecked")
    List<String> order = (List<String>) body.get("order");
    if (order == null || order.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("error", "order is required"));
    }

    List<String> known =
        jdbc.queryForList(
            "SELECT id FROM ai_implementation WHERE capability = ?", String.class, capability);
    if (!order.containsAll(known) || order.size() != known.size()) {
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "error", "order must list every implementation of this capability exactly once"));
    }

    jdbc.update("UPDATE ai_implementation SET rank = rank + 1000 WHERE capability = ?", capability);
    for (int i = 0; i < order.size(); i++) {
      jdbc.update(
          "UPDATE ai_implementation SET rank = ?, updated_at = now() WHERE id = ?",
          i + 1,
          order.get(i));
    }
    log.info("Reordered {} preference: {}", capability, order);
    return ResponseEntity.ok(Map.of("capability", capability, "order", order));
  }

  /**
   * Removes an implementation.
   *
   * <p>Refused while output attributed to its model is still stored: the ranking needs the row to
   * decide whether that output is better or worse than what replaced it. Disable it instead — a
   * disabled row still ranks, it just stops receiving work.
   */
  @DeleteMapping("/implementations/{id}")
  public ResponseEntity<?> delete(@PathVariable String id) {
    var registration = registry.byId(id);
    if (registration.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    String model = registration.get().model();
    Integer used =
        jdbc.queryForObject(
            """
            SELECT (SELECT count(*) FROM page_translation WHERE model = ?)
                 + (SELECT count(*) FROM record_translation WHERE model = ?)
            """,
            Integer.class,
            model,
            model);
    if (used != null && used > 0) {
      return ResponseEntity.status(409)
          .body(
              Map.of(
                  "error",
                  "in use",
                  "message",
                  used
                      + " stored translations were produced by "
                      + model
                      + ". Disable it instead; a disabled implementation still ranks but takes no"
                      + " new work.",
                  "storedOutputs",
                  used));
    }
    jdbc.update("DELETE FROM ai_implementation WHERE id = ?", id);
    log.info("Removed AI implementation {}", id);
    return ResponseEntity.noContent().build();
  }

  /** Whether the deployment holds the credential a row names — never the value itself. */
  private boolean credentialPresent(Object credentialEnv) {
    return registry.credentialPresent(credentialEnv == null ? null : str(credentialEnv));
  }

  private int nextRank(String capability) {
    Integer max =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(rank), 0) FROM ai_implementation WHERE capability = ?",
            Integer.class,
            capability);
    return (max == null ? 0 : max) + 1;
  }

  private static boolean isCapability(String value) {
    for (AiCapability c : AiCapability.values()) {
      if (c.name().equals(value)) {
        return true;
      }
    }
    return false;
  }

  private static String nullable(Map<String, Object> body, String key) {
    Object value = body.get(key);
    return value == null ? null : value.toString();
  }

  private static String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static int intOr(Object value, int fallback) {
    if (value instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(str(value));
    } catch (Exception e) {
      return fallback;
    }
  }

  private static String str(Object o) {
    return o == null ? "" : o.toString();
  }
}
