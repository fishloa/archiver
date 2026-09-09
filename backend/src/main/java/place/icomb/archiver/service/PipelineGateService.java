package place.icomb.archiver.service;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Operator gates: pause a stage without losing its queue.
 *
 * <p>A paused kind is simply not claimed. Its jobs stay {@code pending} and accumulate, so a bad
 * translation model or a misbehaving provider can be stopped in one call and released later with
 * nothing cancelled and nothing re-queued by hand. Scaling workers to zero would strand in-flight
 * claims; cancelling jobs would lose the queue. This does neither.
 */
@Service
public class PipelineGateService {

  private static final Logger log = LoggerFactory.getLogger(PipelineGateService.class);

  private final JdbcTemplate jdbc;

  public PipelineGateService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** True when work of this kind must not be claimed. Absence of a row means open. */
  public boolean isPaused(String kind) {
    if (kind == null) return false;
    Boolean paused =
        jdbc.query(
            "SELECT paused FROM pipeline_gate WHERE kind = ?",
            rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE,
            kind);
    return Boolean.TRUE.equals(paused);
  }

  /** Every gate that has ever been set, open or closed. */
  public List<Map<String, Object>> list() {
    return jdbc.queryForList(
        "SELECT kind, paused, reason, updated_at, updated_by FROM pipeline_gate ORDER BY kind");
  }

  /** Closes or opens a gate. Recorded with a reason so the next person knows why work stopped. */
  public Map<String, Object> set(String kind, boolean paused, String reason, String who) {
    jdbc.update(
        """
        INSERT INTO pipeline_gate (kind, paused, reason, updated_at, updated_by)
        VALUES (?, ?, ?, now(), ?)
        ON CONFLICT (kind) DO UPDATE
          SET paused = EXCLUDED.paused, reason = EXCLUDED.reason,
              updated_at = now(), updated_by = EXCLUDED.updated_by
        """,
        kind,
        paused,
        reason,
        who);
    log.warn("Pipeline gate {} {} by {} ({})", kind, paused ? "CLOSED" : "opened", who, reason);
    return Map.of("kind", kind, "paused", paused, "reason", reason == null ? "" : reason);
  }

  /** Kinds currently paused, for the dashboard. */
  public List<String> pausedKinds() {
    return jdbc.queryForList("SELECT kind FROM pipeline_gate WHERE paused", String.class);
  }
}
