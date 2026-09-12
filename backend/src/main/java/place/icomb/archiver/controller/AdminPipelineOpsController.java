package place.icomb.archiver.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.service.JobService;
import place.icomb.archiver.service.PipelineGateService;
import place.icomb.archiver.service.PipelineStages;

/**
 * Operator actions on the pipeline: holding a stage, running the audit, resetting records, and the
 * counts the admin page reports.
 *
 * <p>Split out of ViewerController. These are the endpoints that change how the pipeline behaves,
 * as opposed to the ones that read the catalogue, and keeping them together makes the set of things
 * an operator can actually do to production legible in one place. The paths are unchanged, and they
 * remain admin-only through the same SecurityConfig rules that match /api/admin/**.
 */
@RestController
@RequestMapping("/api")
public class AdminPipelineOpsController {

  private final JdbcTemplate jdbcTemplate;
  private final JobService jobService;
  private final place.icomb.archiver.service.PipelineAuditService auditService;
  private final PipelineGateService gateService;

  public AdminPipelineOpsController(
      JdbcTemplate jdbcTemplate,
      JobService jobService,
      place.icomb.archiver.service.PipelineAuditService auditService,
      PipelineGateService gateService) {
    this.jdbcTemplate = jdbcTemplate;
    this.jobService = jobService;
    this.auditService = auditService;
    this.gateService = gateService;
  }

  @PostMapping("/admin/audit")
  public ResponseEntity<Map<String, Object>> runAudit() {
    int fixed = jobService.recoverStaleClaims() + auditService.auditPipeline();
    return ResponseEntity.ok(Map.of("fixed", fixed));
  }

  /**
   * Pipeline gates: pause a stage without losing its queue.
   *
   * <p>A paused kind is not claimed by any worker, so its jobs accumulate as {@code pending} and
   * are released untouched when the gate reopens. Scaling workers to zero would strand in-flight
   * claims and cancelling jobs would lose the queue; this does neither.
   */
  @GetMapping("/admin/gates")
  public ResponseEntity<Map<String, Object>> listGates() {
    return ResponseEntity.ok(
        Map.of(
            "gates", gateService.list(),
            "pausedKinds", gateService.pausedKinds(),
            "stages", PipelineStages.all()));
  }

  /**
   * Holds or releases an entire pipeline stage.
   *
   * <p>The unit an operator thinks in. Gating single job kinds is the mechanism, but on its own it
   * misleads: holding translate_record left page translation running, so the stage looked stopped
   * while it was busy and the control looked ignored.
   */
  @PostMapping("/admin/gates/stage/{stage}")
  public ResponseEntity<Map<String, Object>> setStageGate(
      @PathVariable String stage, @RequestBody Map<String, Object> body) {
    Object paused = body.get("paused");
    if (!(paused instanceof Boolean)) {
      return ResponseEntity.badRequest().body(Map.of("error", "paused must be true or false"));
    }
    List<String> kinds = PipelineStages.kindsOf(stage);
    if (kinds.isEmpty()) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "Unknown stage: " + stage, "known", PipelineStages.names()));
    }
    String reason = body.get("reason") instanceof String r ? r : null;
    var auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    String who = auth != null ? auth.getName() : "unknown";

    for (String kind : kinds) {
      gateService.set(kind, (Boolean) paused, reason, who);
    }
    return ResponseEntity.ok(
        Map.of(
            "stage",
            stage,
            "kinds",
            kinds,
            "paused",
            paused,
            "reason",
            reason == null ? "" : reason));
  }

  @PostMapping("/admin/gates/{kind}")
  public ResponseEntity<Map<String, Object>> setGate(
      @PathVariable String kind, @RequestBody Map<String, Object> body) {
    Object paused = body.get("paused");
    if (!(paused instanceof Boolean)) {
      return ResponseEntity.badRequest().body(Map.of("error", "paused must be true or false"));
    }
    String reason = body.get("reason") instanceof String r ? r : null;
    var auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    String who = auth != null ? auth.getName() : "unknown";
    return ResponseEntity.ok(gateService.set(kind, (Boolean) paused, reason, who));
  }

  @SuppressWarnings("unchecked")
  @PostMapping("/admin/records/reset-pipeline")
  public ResponseEntity<Map<String, Object>> resetPipeline(@RequestBody Map<String, Object> body) {
    // Validate targetStage
    String targetStage = (String) body.get("targetStage");
    if (targetStage == null
        || !Set.of("ocr_pending", "translating", "embedding").contains(targetStage)) {
      return ResponseEntity.badRequest()
          .body(
              Map.of("error", "Invalid targetStage. Must be: ocr_pending, translating, embedding"));
    }

    // Validate recordIds
    List<Number> rawIds = (List<Number>) body.get("recordIds");
    if (rawIds == null || rawIds.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("error", "recordIds must be non-empty"));
    }
    if (rawIds.size() > 100) {
      return ResponseEntity.badRequest().body(Map.of("error", "Maximum 100 records per request"));
    }

    List<Map<String, Object>> results = new ArrayList<>();
    for (Number rawId : rawIds) {
      long recordId = rawId.longValue();
      try {
        results.add(jobService.resetRecordToStage(recordId, targetStage));
      } catch (IllegalArgumentException e) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("recordId", recordId);
        err.put("error", e.getMessage());
        results.add(err);
      }
    }

    return ResponseEntity.ok(Map.of("results", results));
  }

  @GetMapping("/admin/stats")
  public ResponseEntity<Map<String, Object>> adminStats() {
    Map<String, Object> stats = new LinkedHashMap<>();

    // Record status counts
    stats.put(
        "recordsByStatus",
        jdbcTemplate.queryForList(
            "SELECT status, count(*) AS cnt FROM record GROUP BY status ORDER BY status"));

    // Job status counts
    stats.put(
        "jobsByKindAndStatus",
        jdbcTemplate.queryForList(
            "SELECT kind, status, count(*) AS cnt FROM job GROUP BY kind, status ORDER BY kind, status"));

    // Stale claimed jobs (> 1 hour)
    stats.put(
        "staleClaimedJobs",
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM job WHERE status = 'claimed' AND started_at < now() - interval '1 hour'",
            Long.class));

    // Failed jobs eligible for retry
    stats.put(
        "failedRetriableJobs",
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM job WHERE status = 'failed' AND attempts < 3", Long.class));

    // Stuck ingesting records
    stats.put(
        "stuckIngestingRecords",
        jdbcTemplate.queryForObject(
            """
        SELECT count(*) FROM record r
        WHERE r.status = 'ingesting' AND r.page_count > 0
          AND r.page_count = (SELECT count(*) FROM page p WHERE p.record_id = r.id)
          AND r.updated_at < now() - interval '10 minutes'
        """,
            Long.class));

    // ocr_done without post-OCR jobs
    stats.put(
        "ocrDoneNoPostOcrJobs",
        jdbcTemplate.queryForObject(
            """
        SELECT count(*) FROM record r
        WHERE r.status = 'ocr_done'
          AND NOT EXISTS (SELECT 1 FROM job j WHERE j.record_id = r.id AND j.kind = 'build_searchable_pdf')
        """,
            Long.class));

    // Recent pipeline events
    stats.put(
        "recentEvents",
        jdbcTemplate.queryForList(
            """
        SELECT pe.record_id, pe.stage, pe.event, pe.detail, pe.created_at,
               r.title AS record_title
        FROM pipeline_event pe
        LEFT JOIN record r ON r.id = pe.record_id
        ORDER BY pe.created_at DESC LIMIT 20
        """));

    return ResponseEntity.ok(stats);
  }
}
