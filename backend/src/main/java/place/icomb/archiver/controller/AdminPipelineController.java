package place.icomb.archiver.controller;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
  private final org.springframework.core.env.Environment environment;
  private final place.icomb.archiver.service.TranskribusImportService transkribusImport;

  public AdminPipelineController(
      JdbcTemplate jdbcTemplate,
      JobService jobService,
      place.icomb.archiver.ai.AiRegistry aiRegistry,
      @org.springframework.beans.factory.annotation.Value(
              "${archiver.ocr.default-engine:ocr_page_mistral}")
          String defaultOcrEngine,
      org.springframework.core.env.Environment environment,
      place.icomb.archiver.service.TranskribusImportService transkribusImport) {
    this.jdbcTemplate = jdbcTemplate;
    this.jobService = jobService;
    this.aiRegistry = aiRegistry;
    this.defaultOcrEngine = defaultOcrEngine;
    this.environment = environment;
    this.transkribusImport = transkribusImport;
  }

  /** The Transkribus row and its credentials, or empty when none is configured. */
  private java.util.Optional<place.icomb.archiver.ai.TranskribusConfig> transkribusConfig() {
    return aiRegistry.forCapability(place.icomb.archiver.ai.AiCapability.OCR).stream()
        .filter(r -> "transkribus".equals(r.provider()))
        .map(r -> new place.icomb.archiver.ai.TranskribusConfig(r, environment))
        .filter(place.icomb.archiver.ai.TranskribusConfig::isConfigured)
        .findFirst();
  }

  /**
   * Imports transcriptions made in the Transkribus web app.
   *
   * <p>The super models cannot be started through the API — it answers "You are not allowed for
   * TrHtr Recognition!" even on a paid plan — but what they produce can be read. So a handwritten
   * page is uploaded by machine, transcribed by a person pressing Run, and collected here.
   *
   * <p>Give a {@code docId} to import one document, or nothing to sweep every document in the
   * collection. Pages are matched to the archive by the file name they were uploaded under, {@code
   * rec<recordId>_seq<pageSeq>.jpg}, which Transkribus preserves in the PAGE XML.
   *
   * <p>Each imported page is then carried through translation, the record's searchable PDF and
   * re-embedding on its own, unless {@code advance=false}.
   */
  @PostMapping("/import-transkribus")
  public ResponseEntity<?> importTranskribus(
      @RequestParam(required = false) Integer collId,
      @RequestParam(required = false) Long docId,
      @RequestParam(defaultValue = "true") boolean advance,
      @RequestParam(defaultValue = "false") boolean all,
      @RequestParam(defaultValue = "false") boolean overwrite) {

    var config = transkribusConfig();
    if (config.isEmpty()) {
      return ResponseEntity.status(409)
          .body(
              Map.of(
                  "error",
                  "no configured Transkribus row",
                  "hint",
                  "TRANSKRIBUS_USERNAME and TRANSKRIBUS_PASSWORD must be set in the deployment"));
    }

    var client = new place.icomb.archiver.service.TranskribusTrpClient(config.get());
    try {
      int collection = collId != null ? collId : client.collectionId();
      var docIds = new java.util.ArrayList<Long>();
      if (docId != null) {
        docIds.add(docId);
      } else if (all) {
        for (var doc : client.listDocuments(collection)) {
          docIds.add(doc.path("docId").asLong());
        }
      } else {
        // The collection accumulates every document ever uploaded, so importing it whole puts
        // each one back over whatever the archive holds now. A sweep has to be asked for.
        return ResponseEntity.badRequest()
            .body(
                Map.of(
                    "error",
                    "give docId, or pass all=true to import the whole collection",
                    "hint",
                    "the collection holds every document ever uploaded, including models run on"
                        + " page types they suit badly"));
      }

      var results = new java.util.LinkedHashMap<String, Object>();
      int imported = 0;
      for (Long id : docIds) {
        var outcomes = transkribusImport.importDocument(client, collection, id, advance, overwrite);
        results.put(String.valueOf(id), outcomes);
        imported += (int) outcomes.stream().filter(o -> "imported".equals(o.outcome())).count();
      }

      log.info(
          "Transkribus import: collection={} documents={} pages imported={}",
          collection,
          docIds.size(),
          imported);
      return ResponseEntity.ok(
          Map.of(
              "collection",
              collection,
              "documents",
              docIds,
              "imported",
              imported,
              "detail",
              results));
    } catch (Exception e) {
      log.error("Transkribus import failed: {}", e.getMessage(), e);
      return ResponseEntity.status(502).body(Map.of("error", String.valueOf(e.getMessage())));
    }
  }

  /**
   * Re-transcribes one page on a chosen engine, and carries that page alone onward.
   *
   * <p>The case this exists for: a reader sees a page whose transcription is wrong — handwriting
   * that the default engine answered with fluent, plausible nonsense — and asks for that page to be
   * read again by a model that can read it. Re-running the record is the wrong instrument, because
   * it re-transcribes every other page on the default engine and re-translates all of them.
   *
   * <p>Addressed by {@code recordId} plus {@code seq}, which is what a reader can see, or by {@code
   * pageId} directly. {@code engine} takes {@code transkribus} (the default here, since the default
   * engine is what produced the bad page) or any other registered OCR job kind. {@code htrId}
   * overrides the model for this one page, so any model in Transkribus's catalogue can be tried
   * without touching configuration; {@code pageClass=print} sends a typescript to the typewriter
   * model instead of the handwriting one.
   *
   * <p>The existing transcription is deleted, which the {@code page_ocr_history} trigger preserves,
   * so the engine that got it wrong stays on the record.
   */
  /**
   * Freezes a record's AI processing, or lifts the freeze.
   *
   * <p>Its queued jobs are left pending and are not claimed until the hold is lifted, so nothing is
   * lost and nothing is charged for while a broken record is put right. {@code reason} is stored
   * and shown, because a hold with no reason is indistinguishable from a stuck record.
   */
  @PostMapping("/records/{recordId}/ai-hold")
  public ResponseEntity<Map<String, Object>> holdRecord(
      @PathVariable Long recordId,
      @RequestParam(required = false) String reason,
      @RequestParam(defaultValue = "true") boolean hold) {

    int updated =
        hold
            ? jdbcTemplate.update(
                "UPDATE record SET ai_held_at = now(), ai_hold_reason = ? WHERE id = ?",
                reason,
                recordId)
            : jdbcTemplate.update(
                "UPDATE record SET ai_held_at = NULL, ai_hold_reason = NULL WHERE id = ?",
                recordId);

    if (updated == 0) {
      return ResponseEntity.status(404).body(Map.of("error", "no record " + recordId));
    }

    Long queued =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM job WHERE record_id = ? AND status IN ('pending', 'claimed')",
            Long.class,
            recordId);

    log.info("Record {} AI hold {}: {}", recordId, hold ? "set" : "lifted", reason);
    return ResponseEntity.ok(
        Map.of(
            "recordId",
            recordId,
            "held",
            hold,
            "reason",
            String.valueOf(reason),
            "jobsWaiting",
            queued == null ? 0 : queued));
  }

  /**
   * Stops queued work: one job, or everything still queued for one record.
   *
   * <p>Addressed by identity, never by pattern. A kind-and-time filter cancels whatever happens to
   * match at the moment it runs, including work queued by something else that is going along fine.
   *
   * <p>Only {@code pending} and {@code claimed} jobs are touched; a finished job is left alone.
   */
  @PostMapping("/cancel-jobs")
  public ResponseEntity<Map<String, Object>> cancelJobs(
      @RequestParam(required = false) Long jobId,
      @RequestParam(required = false) Long recordId,
      @RequestParam(required = false) String reason) {

    if ((jobId == null) == (recordId == null)) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "give exactly one of jobId or recordId"));
    }

    String note =
        "cancelled via /api/admin/cancel-jobs"
            + (reason == null || reason.isBlank() ? "" : ": " + reason);

    List<Map<String, Object>> stopped;
    if (jobId != null) {
      stopped =
          jdbcTemplate.queryForList(
              """
              UPDATE job
                 SET status = 'failed', error = ?, finished_at = now()
               WHERE id = ? AND status IN ('pending', 'claimed')
              RETURNING id, kind, record_id, page_id
              """,
              note,
              jobId);
    } else {
      stopped =
          jdbcTemplate.queryForList(
              """
              UPDATE job
                 SET status = 'failed', error = ?, finished_at = now()
               WHERE record_id = ? AND status IN ('pending', 'claimed')
              RETURNING id, kind, record_id, page_id
              """,
              note,
              recordId);
    }

    log.info("Cancelled {} job(s): jobId={} record={}", stopped.size(), jobId, recordId);
    return ResponseEntity.ok(Map.of("cancelled", stopped.size(), "jobs", stopped));
  }

  @PostMapping("/reocr-page")
  public ResponseEntity<Map<String, Object>> reocrPage(
      @RequestParam(required = false) Long recordId,
      @RequestParam(required = false) Integer seq,
      @RequestParam(required = false) Long pageId,
      @RequestParam(defaultValue = "transkribus") String engine,
      @RequestParam(required = false) Integer htrId,
      @RequestParam(required = false) String pageClass,
      @RequestParam(defaultValue = "full") String andThen) {

    Long resolvedPageId = pageId;
    if (resolvedPageId == null) {
      if (recordId == null || seq == null) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "give either pageId, or recordId and seq"));
      }
      resolvedPageId =
          jdbcTemplate
              .query(
                  "SELECT id FROM page WHERE record_id = ? AND seq = ?",
                  (rs, i) -> rs.getLong(1),
                  recordId,
                  seq)
              .stream()
              .findFirst()
              .orElse(null);
      if (resolvedPageId == null) {
        return ResponseEntity.status(404)
            .body(Map.of("error", "no page %d in record %d".formatted(seq, recordId)));
      }
    }

    Long resolvedRecordId =
        jdbcTemplate.queryForObject(
            "SELECT record_id FROM page WHERE id = ?", Long.class, resolvedPageId);
    if (resolvedRecordId == null) {
      return ResponseEntity.status(404).body(Map.of("error", "no page " + resolvedPageId));
    }

    String jobKind =
        "transkribus".equalsIgnoreCase(engine)
            ? place.icomb.archiver.ai.TranskribusConfig.JOB_KIND
            : (engine.startsWith("ocr_page_") ? engine : "ocr_page_" + engine);

    // Refuse an engine nothing can claim, rather than parking a job forever. The Transkribus rows
    // carry their job kind in settings; the batch engines are named by their own job kind.
    boolean claimable =
        aiRegistry.configured(place.icomb.archiver.ai.AiCapability.OCR).stream()
            .anyMatch(r -> jobKind.equals(r.setting("jobKind", defaultOcrEngine)));
    if (!claimable) {
      return ResponseEntity.status(409)
          .body(
              Map.of(
                  "error",
                  "no configured OCR engine claims " + jobKind,
                  "hint",
                  "check the ai_implementation rows and the engine's credential"));
    }

    String lang =
        jdbcTemplate.queryForObject(
            "SELECT lang FROM record WHERE id = ?", String.class, resolvedRecordId);

    var payload = new java.util.LinkedHashMap<String, Object>();
    if (lang != null) {
      payload.put("lang", lang);
    }
    if (htrId != null) {
      payload.put("htrId", htrId);
    }
    if (pageClass != null) {
      payload.put("pageClass", pageClass);
    }
    payload.put("andThen", andThen);

    String payloadJson;
    try {
      payloadJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
    } catch (Exception e) {
      return ResponseEntity.status(500).body(Map.of("error", "could not build payload"));
    }

    // The existing transcription stays until a new one arrives. Both writers delete the page's row
    // immediately before inserting their own, so deleting here buys nothing and costs the page its
    // text whenever the job then fails.
    jobService.enqueueJob(jobKind, resolvedRecordId, resolvedPageId, payloadJson);
    jdbcTemplate.execute("NOTIFY ocr_jobs");

    log.info(
        "Re-OCR queued: record={} page={} kind={} htrId={} pageClass={}",
        resolvedRecordId,
        resolvedPageId,
        jobKind,
        htrId,
        pageClass);

    return ResponseEntity.ok(
        Map.of(
            "recordId", resolvedRecordId,
            "pageId", resolvedPageId,
            "jobKind", jobKind,
            "payload", payload));
  }

  /**
   * Transkribus's public model catalogue: every model's id, name, languages and error rate.
   *
   * <p>Needs no Transkribus credential, so a model can be chosen and checked before one exists.
   * {@code lang} filters by ISO 639-3 code as Transkribus uses them ({@code deu}, {@code ces},
   * {@code eng}) and {@code docType} by {@code handwritten} or {@code print}.
   */
  @GetMapping("/transkribus/models")
  public ResponseEntity<?> transkribusModels(
      @RequestParam(required = false) String lang,
      @RequestParam(required = false) String docType,
      @RequestParam(defaultValue = "25") int limit) {
    try {
      var models =
          place.icomb.archiver.service.TranskribusClient.publicModels(
              java.net.http.HttpClient.newHttpClient());
      var out = new java.util.ArrayList<Map<String, Object>>();
      for (var m : models) {
        if (lang != null) {
          boolean match = false;
          for (var l : m.path("isoLanguages")) {
            match |= lang.equalsIgnoreCase(l.asText());
          }
          if (!match) {
            continue;
          }
        }
        if (docType != null && !docType.equalsIgnoreCase(m.path("docType").asText())) {
          continue;
        }
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("htrId", m.path("modelId").asInt());
        row.put("name", m.path("name").asText());
        row.put("docType", m.path("docType").asText());
        row.put("cer", m.path("finalCer").isMissingNode() ? null : m.path("finalCer").asDouble());
        row.put("trainWords", m.path("nrOfWords").asLong());
        row.put("featured", m.path("featured").asBoolean(false));
        out.add(row);
        if (out.size() >= limit) {
          break;
        }
      }
      return ResponseEntity.ok(out);
    } catch (Exception e) {
      return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
    }
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
