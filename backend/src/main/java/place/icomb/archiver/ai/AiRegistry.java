package place.icomb.archiver.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * What models exist, what they can do, and which is preferred.
 *
 * <p>Reads {@code ai_implementation}, so the choice of provider and the order of preference are
 * data rather than code and can be changed without a deploy. Three things that had to agree and did
 * not — which provider a stage calls, which model it asks for, and which of several stored
 * translations is the better one — are now answered from one table.
 *
 * <p>Credentials are never stored here. A row names an environment variable; the value is read from
 * the deployment, so a database backup carries no keys.
 */
@Service
public class AiRegistry {

  private static final Logger log = LoggerFactory.getLogger(AiRegistry.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final JdbcTemplate jdbc;
  private final org.springframework.core.env.Environment environment;

  public AiRegistry(JdbcTemplate jdbc, org.springframework.core.env.Environment environment) {
    this.jdbc = jdbc;
    this.environment = environment;
  }

  /**
   * A configured model, as the database describes it.
   *
   * @param rank preference within its capability; lowest wins
   */
  public record Registration(
      String id,
      AiCapability capability,
      String provider,
      String model,
      String baseUrl,
      String endpointPath,
      String credentialEnv,
      int maxBatchSize,
      int rank,
      boolean enabled,
      JsonNode settings) {

    /** A setting, or the given fallback when absent. */
    public String setting(String key, String fallback) {
      JsonNode v = settings.path(key);
      return v.isMissingNode() || v.isNull() ? fallback : v.asText();
    }

    public int setting(String key, int fallback) {
      JsonNode v = settings.path(key);
      return v.isMissingNode() || v.isNull() ? fallback : v.asInt(fallback);
    }

    public long setting(String key, long fallback) {
      JsonNode v = settings.path(key);
      return v.isMissingNode() || v.isNull() ? fallback : v.asLong(fallback);
    }
  }

  /** Everything registered for a capability, best first. Disabled rows are excluded. */
  public List<Registration> forCapability(AiCapability capability) {
    return jdbc.query(
        """
        SELECT * FROM ai_implementation
        WHERE capability = ? AND enabled
        ORDER BY rank
        """,
        (rs, i) -> map(rs),
        capability.name());
  }

  /**
   * The preferred implementation for a capability, if one is registered and configured.
   *
   * <p>Skips a row whose credential is absent, so a local endpoint can sit below a hosted one as a
   * standby and be used only when the hosted one is not configured — or above it, to prefer the
   * machine in the room and fall back to the hosted endpoint when it is not running.
   */
  public Optional<Registration> best(AiCapability capability) {
    return forCapability(capability).stream().filter(this::hasCredential).findFirst();
  }

  /**
   * Every configured implementation for a capability, best first.
   *
   * <p>For callers that want to fail over rather than give up: the same model on a second provider
   * is a legitimate retry, whereas a different model is a different answer.
   */
  public List<Registration> configured(AiCapability capability) {
    return forCapability(capability).stream().filter(this::hasCredential).toList();
  }

  /** Implementations of one model, across providers, best first. */
  public List<Registration> forModel(AiCapability capability, String model) {
    return jdbc.query(
        """
        SELECT * FROM ai_implementation
        WHERE capability = ? AND model = ? AND enabled
        ORDER BY rank
        """,
        (rs, i) -> map(rs),
        capability.name(),
        model);
  }

  /** One implementation by id, enabled or not — history refers to retired models. */
  public Optional<Registration> byId(String id) {
    return jdbc
        .query("SELECT * FROM ai_implementation WHERE id = ?", (rs, i) -> map(rs), id)
        .stream()
        .findFirst();
  }

  /**
   * Preference order for a capability by model name, best first, each model once.
   *
   * <p>Distinct because the same model can be registered against more than one provider — Qwen3 on
   * a hosted endpoint and on a local server, say, with different URLs and different keys. Those are
   * different implementations to route work to, but not different qualities of answer: a
   * translation is as good as the model that produced it, wherever it ran. Stored output records
   * only the model, so ranking is by model, and a model inherits the best rank any of its providers
   * holds.
   *
   * <p>Includes disabled rows: a retired model's output is still in the archive and still has to
   * rank correctly against whatever replaced it.
   */
  public List<String> rankedModels(AiCapability capability) {
    return jdbc.queryForList(
        """
        SELECT model FROM ai_implementation
        WHERE capability = ?
        GROUP BY model
        ORDER BY MIN(rank)
        """,
        String.class,
        capability.name());
  }

  /** The same order as a PostgreSQL array literal, for binding to {@code ?::text[]}. */
  public String rankedModelsLiteral(AiCapability capability) {
    return "{" + String.join(",", rankedModels(capability)) + "}";
  }

  /** The credential for an implementation, read from the environment it names. */
  public String credential(Registration registration) {
    if (registration.credentialEnv() == null || registration.credentialEnv().isBlank()) {
      return "";
    }
    String value = environment.getProperty(registration.credentialEnv());
    return value == null ? "" : value;
  }

  private boolean hasCredential(Registration registration) {
    if (registration.credentialEnv() == null || registration.credentialEnv().isBlank()) {
      return true;
    }
    boolean present = !credential(registration).isBlank();
    if (!present) {
      // Skipped rather than tried and failed: a half-filled configuration must not quietly send
      // work to a provider that will reject it.
      log.warn(
          "{} is enabled but {} is not set; skipping it",
          registration.id(),
          registration.credentialEnv());
    }
    return present;
  }

  private Registration map(java.sql.ResultSet rs) throws java.sql.SQLException {
    JsonNode settings;
    try {
      String raw = rs.getString("settings");
      settings = raw == null ? MAPPER.createObjectNode() : MAPPER.readTree(raw);
    } catch (Exception e) {
      log.warn("Unreadable settings for {}", rs.getString("id"), e);
      settings = MAPPER.createObjectNode();
    }
    return new Registration(
        rs.getString("id"),
        AiCapability.valueOf(rs.getString("capability")),
        rs.getString("provider"),
        rs.getString("model"),
        rs.getString("base_url"),
        rs.getString("endpoint_path"),
        rs.getString("credential_env"),
        rs.getInt("max_batch_size"),
        rs.getInt("rank"),
        rs.getBoolean("enabled"),
        settings);
  }

  /** Everything registered, for the admin screen. */
  public Map<AiCapability, List<Registration>> all() {
    Map<AiCapability, List<Registration>> out = new LinkedHashMap<>();
    for (AiCapability capability : AiCapability.values()) {
      out.put(capability, new ArrayList<>(forCapability(capability)));
    }
    return out;
  }
}
