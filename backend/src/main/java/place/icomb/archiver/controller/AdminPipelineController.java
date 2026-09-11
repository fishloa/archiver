package place.icomb.archiver.controller;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.service.JobService;

@RestController
@RequestMapping("/api/admin")
public class AdminPipelineController {

  private static final Logger log = LoggerFactory.getLogger(AdminPipelineController.class);

  private final JdbcTemplate jdbcTemplate;
  private final JobService jobService;
  private final place.icomb.archiver.ai.AiRegistry aiRegistry;
  private final String defaultOcrEngine;

  public AdminPipelineController(
      JdbcTemplate jdbcTemplate,
      JobService jobService,
      place.icomb.archiver.ai.AiRegistry aiRegistry,
      @org.springframework.beans.factory.annotation.Value(
              "${archiver.ocr.default-engine:ocr_page_mistral}")
          String defaultOcrEngine) {
    this.jdbcTemplate = jdbcTemplate;
    this.jobService = jobService;
    this.aiRegistry = aiRegistry;
    this.defaultOcrEngine = defaultOcrEngine;
  }

  /**
   * Discards every embedding and queues the archive to be embedded again.
   *
   * <p>Moved here from /api/processor, where it answered to the worker token. That token is held by
   * every scraper and is passed around as deployment configuration, so anything holding it could
   * delete all 204,787 vectors in one unauthenticated-looking call. It is an administrator's
   * decision, not a worker's.
   *
   * <p>The count must be sent back to confirm it. Re-embedding the archive is chargeable and takes
   * hours, and an operator who has miscounted by an order of magnitude should find out before the
   * delete rather than after.
   */
  @PostMapping("/reset-embeddings")
  public ResponseEntity<Map<String, Object>> resetEmbeddings(
      @RequestParam(defaultValue = "0") int confirmChunks) {
    Integer chunks = jdbcTemplate.queryForObject("SELECT count(*) FROM text_chunk", Integer.class);
    int held = chunks == null ? 0 : chunks;

    if (confirmChunks != held) {
      return ResponseEntity.status(409)
          .body(
              Map.of(
                  "error",
                  "confirmation required",
                  "message",
                  "This deletes every embedding and re-embeds the archive, which is chargeable"
                      + " and takes hours. Send confirmChunks="
                      + held
                      + " to proceed.",
                  "chunksHeld",
                  held));
    }

    var embedder = aiRegistry.best(place.icomb.archiver.ai.AiCapability.EMBEDDING);
    if (embedder.isEmpty()) {
      return ResponseEntity.status(409)
          .body(
              Map.of(
                  "error",
                  "no embedding model is enabled and configured",
                  "message",
                  "The index would be deleted and never rebuilt. Enable an embedding model"
                      + " first."));
    }

    int deleted = jdbcTemplate.update("DELETE FROM text_chunk");
    List<Long> recordIds =
        jdbcTemplate.queryForList("SELECT id FROM record WHERE status = 'complete'", Long.class);
    for (Long recordId : recordIds) {
      jdbcTemplate.update(
          "UPDATE record SET status = 'embedding', updated_at = now() WHERE id = ?", recordId);
      jobService.enqueueJob("embed_record", recordId, null, null);
    }

    log.warn(
        "Embedding index reset: {} chunks deleted, {} records queued against {}",
        deleted,
        recordIds.size(),
        embedder.get().id());
    return ResponseEntity.ok(
        Map.of(
            "chunksDeleted", deleted,
            "recordsQueued", recordIds.size(),
            "model", embedder.get().model()));
  }

  /** Returns 200 for admin users. Used by nginx auth_request to gate admin-only proxies. */
  @GetMapping("/check")
  public ResponseEntity<Void> checkAdmin() {
    return ResponseEntity.ok().build();
  }

  /**
   * Re-transcribes records with the engine that is actually running.
   *
   * <p>This hardcoded {@code ocr_page_qwen3vl}, which has been disabled in the deployment since
   * Mistral became the only OCR engine. It deleted each record's text, its searchable PDF and its
   * translations first, then queued jobs for an engine with no worker — so the destruction happened
   * and the repair never did, leaving the records blank.
   *
   * <p>The engine now comes from the registry, and if none is usable nothing is destroyed.
   */
  @PostMapping("/enqueue-reocr")
  public ResponseEntity<Map<String, Object>> enqueueReocr(
      @RequestParam(defaultValue = "0") long recordId,
      @RequestParam(defaultValue = "1000") int limit) {

    var engine = aiRegistry.best(place.icomb.archiver.ai.AiCapability.OCR);
    if (engine.isEmpty()) {
      return ResponseEntity.status(409)
          .body(
              Map.of(
                  "error",
                  "no OCR engine is enabled and configured",
                  "message",
                  "Re-transcription would clear each record's text and PDF and then queue work"
                      + " nothing can claim. Enable an OCR model first."));
    }
    // The job kind is the engine's own, not a name derived from the provider: job_kind is a
    // CHECK-constrained column and a guessed value fails the insert after the deletion has
    // already happened.
    String jobKind = engine.get().setting("jobKind", defaultOcrEngine);

    String sql =
        """
        SELECT DISTINCT r.id AS record_id, r.lang
        FROM record r
        JOIN page p ON p.record_id = r.id
        WHERE r.status IN ('ocr_done', 'pdf_pending', 'pdf_done',
                           'translating', 'embedding', 'complete')
          AND NOT EXISTS (
            SELECT 1 FROM job j
            WHERE j.record_id = r.id AND j.kind = ?
              AND j.status IN ('pending', 'claimed')
          )
        """;
    List<Object> params = new java.util.ArrayList<>();
    params.add(jobKind);
    if (recordId > 0) {
      sql += " AND r.id = ?";
      params.add(recordId);
    }
    sql += " ORDER BY r.id LIMIT ?";
    params.add(Math.max(1, limit));

    List<Map<String, Object>> records = jdbcTemplate.queryForList(sql, params.toArray());

    int totalJobs = 0;
    int totalRecords = 0;
    for (Map<String, Object> row : records) {
      Long recId = ((Number) row.get("record_id")).longValue();
      String lang = (String) row.get("lang");

      // Clean up downstream data for this record
      jobService.resetForOcr(recId);

      String payload = lang != null ? "{\"lang\":\"" + lang + "\"}" : null;
      List<Long> pageIds =
          jdbcTemplate.queryForList(
              "SELECT id FROM page WHERE record_id = ? ORDER BY seq", Long.class, recId);
      for (Long pageId : pageIds) {
        jobService.enqueueJob(jobKind, recId, pageId, payload);
        totalJobs++;
      }
      totalRecords++;
    }

    log.info("Enqueued {} {} jobs across {} records", totalJobs, jobKind, totalRecords);
    return ResponseEntity.ok(
        Map.of(
            "jobsEnqueued", totalJobs,
            "recordsReset", totalRecords,
            "engine", jobKind));
  }
}
