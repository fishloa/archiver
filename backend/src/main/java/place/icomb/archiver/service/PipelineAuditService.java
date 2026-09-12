package place.icomb.archiver.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pipeline's self-healing pass.
 *
 * <p>Split out of JobService, where it was 377 of 897 lines — the single largest thing in a class
 * otherwise concerned with the lifecycle of one job. The audit is a different job: it sweeps the
 * whole archive looking for records that stopped moving, and it runs on its own schedule.
 *
 * <p>Every transition it makes goes through {@link PipelineStateMachine#autoAdvance}. Passes that
 * wrote their own SQL instead had already drifted from the rules they were meant to enforce —
 * embedding audited records twice, and never enqueuing embedding at all on one path — so the rule
 * is that this class detects stuck records and the state machine decides what happens to them.
 */
@Service
public class PipelineAuditService {

  private static final Logger log = LoggerFactory.getLogger(PipelineAuditService.class);

  private final JdbcTemplate jdbcTemplate;
  private final JobService jobService;
  private final RecordEventService recordEventService;
  private final String defaultOcrEngine;
  private PipelineStateMachine stateMachine;

  public PipelineAuditService(
      JdbcTemplate jdbcTemplate,
      JobService jobService,
      RecordEventService recordEventService,
      @Value("${archiver.ocr.default-engine:ocr_page_mistral}") String defaultOcrEngine) {
    this.jdbcTemplate = jdbcTemplate;
    this.jobService = jobService;
    this.recordEventService = recordEventService;
    this.defaultOcrEngine = defaultOcrEngine;
  }

  /** Set after construction, because the state machine needs JobService and this needs it. */
  void setStateMachine(PipelineStateMachine stateMachine) {
    this.stateMachine = stateMachine;
  }

  private void enqueueJob(String kind, Long recordId, Long pageId, String payload) {
    jobService.enqueueJob(kind, recordId, pageId, payload);
  }

  private void logPipelineEvent(Long recordId, String stage, String event, String detail) {
    jdbcTemplate.update(
        "INSERT INTO pipeline_event (record_id, stage, event, detail, created_at)"
            + " VALUES (?, ?, ?, ?, now())",
        recordId,
        stage,
        event,
        detail);
  }

  @Transactional
  public int auditPipeline() {
    int total = 0;

    // Pass 1 (stale claim recovery) now runs in its own transaction — see recoverStaleClaims.
    int staleClaimed = 0;

    // --- Pass 2: Retry failed jobs with < 3 attempts (skip poison jobs) ---
    //
    // batch_id must be cleared with the rest. A batch claim looks for pending jobs with no
    // batch, so a retried job that kept the id of the batch it failed in became invisible to
    // every future claim — pending forever, counted as outstanding forever, and never run.
    int failedRetried =
        jdbcTemplate.update(
            """
            UPDATE job SET status = 'pending', error = NULL, finished_at = NULL, batch_id = NULL
            WHERE status = 'failed' AND attempts < 3
            """);
    if (failedRetried > 0) {
      log.info("Audit: retried {} failed jobs", failedRetried);
    }
    total += failedRetried;

    // --- Pass 3: Stuck ingesting records where all pages are present ---
    //     The scraper uploaded all pages but never called completeIngest.
    //     Transition to ocr_pending and enqueue OCR jobs.
    //     Also handles 0-page records (metadata-only) — skip OCR, go to ocr_done.
    List<Long> ingestingStuck =
        jdbcTemplate.queryForList(
            """
            SELECT r.id FROM record r
            WHERE r.status = 'ingesting'
              AND (r.page_count = 0 OR (
                r.page_count > 0
                AND r.page_count = (SELECT count(*) FROM page p WHERE p.record_id = r.id)
              ))
              AND r.updated_at < now() - interval '10 minutes'
            ORDER BY r.id
            """,
            Long.class);

    for (Long recordId : ingestingStuck) {
      log.info("Audit: completing stuck ingest for record {}", recordId);
      var langRow =
          jdbcTemplate.queryForMap("SELECT lang, page_count FROM record WHERE id = ?", recordId);
      String lang = (String) langRow.get("lang");
      int pc = ((Number) langRow.get("page_count")).intValue();

      if (pc == 0) {
        // Metadata-only record — skip OCR entirely, let state machine chain forward
        jdbcTemplate.update(
            "UPDATE record SET status = 'ocr_done', updated_at = now() WHERE id = ?", recordId);
        logPipelineEvent(recordId, "ingest", "completed", "from audit: 0 pages (metadata-only)");
        logPipelineEvent(recordId, "ocr", "completed", "skipped (no pages)");
        recordEventService.recordChanged(recordId, "status");
        stateMachine.autoAdvance(recordId);
      } else {
        String ocrPayload = lang != null ? "{\"lang\":\"" + lang + "\"}" : null;
        List<Long> pageIds =
            jdbcTemplate.queryForList(
                "SELECT id FROM page WHERE record_id = ? ORDER BY seq", Long.class, recordId);
        for (Long pageId : pageIds) {
          enqueueJob(defaultOcrEngine, recordId, pageId, ocrPayload);
        }
        jdbcTemplate.update(
            "UPDATE record SET status = 'ocr_pending', updated_at = now() WHERE id = ?", recordId);
        logPipelineEvent(
            recordId, "ingest", "completed", "from audit: " + pageIds.size() + " pages");
        logPipelineEvent(
            recordId, "ocr", "started", "from audit: " + pageIds.size() + " jobs enqueued");
        recordEventService.recordChanged(recordId, "status");
      }
    }
    total += ingestingStuck.size();

    // --- Pass 3b: ocr_pending records with 0 pages (should never be in this state) ---
    List<Long> ocrPendingNoPages =
        jdbcTemplate.queryForList(
            """
            SELECT r.id FROM record r
            WHERE r.status = 'ocr_pending'
              AND NOT EXISTS (SELECT 1 FROM page p WHERE p.record_id = r.id)
            ORDER BY r.id
            """,
            Long.class);

    for (Long recordId : ocrPendingNoPages) {
      jdbcTemplate.update(
          "UPDATE record SET status = 'ocr_done', updated_at = now() WHERE id = ?", recordId);
      log.info("Audit: record {} ocr_pending → ocr_done (0 pages)", recordId);
      logPipelineEvent(recordId, "ocr", "completed", "skipped (no pages)");
      recordEventService.recordChanged(recordId, "status");
      stateMachine.autoAdvance(recordId);
    }
    total += ocrPendingNoPages.size();

    // --- Pass 3c: ocr_pending records with pages but no pending/claimed OCR jobs
    //     (all page text was pre-populated, e.g. text-pdf ingest) ---
    List<Long> ocrPendingAllDone =
        jdbcTemplate.queryForList(
            """
            SELECT r.id FROM record r
            WHERE r.status = 'ocr_pending'
              AND EXISTS (SELECT 1 FROM page p WHERE p.record_id = r.id)
              AND NOT EXISTS (
                SELECT 1 FROM job j
                WHERE j.record_id = r.id
                  AND j.kind LIKE 'ocr\\_page\\_%'
                  AND j.status IN ('pending', 'claimed')
              )
            ORDER BY r.id
            """,
            Long.class);

    for (Long recordId : ocrPendingAllDone) {
      jdbcTemplate.update(
          "UPDATE record SET status = 'ocr_done', updated_at = now() WHERE id = ?", recordId);
      log.info("Audit: record {} ocr_pending → ocr_done (all text pre-populated)", recordId);
      logPipelineEvent(recordId, "ocr", "completed", "all text pre-populated");
      recordEventService.recordChanged(recordId, "status");
      stateMachine.autoAdvance(recordId);
    }
    total += ocrPendingAllDone.size();

    // --- Pass 4: ocr_done records with no build_searchable_pdf job ---
    List<Long> ocrDoneStuck =
        jdbcTemplate.queryForList(
            """
            SELECT r.id FROM record r
            WHERE r.status = 'ocr_done'
              AND NOT EXISTS (
                SELECT 1 FROM job j
                WHERE j.record_id = r.id
                  AND j.kind = 'build_searchable_pdf'
              )
            ORDER BY r.id
            """,
            Long.class);

    for (Long recordId : ocrDoneStuck) {
      log.info("Audit: re-queuing post-OCR pipeline for stuck record {}", recordId);
      stateMachine.autoAdvance(recordId);
    }
    total += ocrDoneStuck.size();

    // --- Pass 4b: pdf_pending records with 0 pages (skip PDF, go to translating) ---
    List<Long> noPageRecords =
        jdbcTemplate.queryForList(
            """
            SELECT r.id FROM record r
            WHERE r.status = 'pdf_pending'
              AND NOT EXISTS (SELECT 1 FROM page p WHERE p.record_id = r.id)
            ORDER BY r.id
            """,
            Long.class);

    for (Long recordId : noPageRecords) {
      // The state machine owns the skip (PDF_PENDING → PDF_DONE when a record has no pages),
      // including cancelling the build_searchable_pdf job that can never be satisfied.
      log.info("Audit: record {} pdf_pending with 0 pages, skipping PDF", recordId);
      stateMachine.autoAdvance(recordId);
    }
    total += noPageRecords.size();

    // --- Pass 5: pdf_pending records whose build_searchable_pdf job is completed
    //             but the record never transitioned to pdf_done ---
    List<Long> pdfPendingStuck =
        jdbcTemplate.queryForList(
            """
            SELECT r.id FROM record r
            WHERE r.status = 'pdf_pending'
              AND EXISTS (
                SELECT 1 FROM job j
                WHERE j.record_id = r.id
                  AND j.kind = 'build_searchable_pdf'
                  AND j.status = 'completed'
              )
              AND EXISTS (
                SELECT 1 FROM attachment a
                WHERE a.record_id = r.id
                  AND a.role = 'searchable_pdf'
              )
            ORDER BY r.id
            """,
            Long.class);

    for (Long recordId : pdfPendingStuck) {
      log.info("Audit: nudging pdf_pending for record {}", recordId);
      stateMachine.autoAdvance(recordId);
    }
    total += pdfPendingStuck.size();

    // --- Pass 6: Migrate pdf_done records to translating or complete ---
    //     Records stuck in pdf_done from before the translating status was added.
    List<Long> pdfDoneStuck =
        jdbcTemplate.queryForList(
            """
        SELECT r.id FROM record r
        WHERE r.status = 'pdf_done'
        ORDER BY r.id
        """,
            Long.class);

    for (Long recordId : pdfDoneStuck) {
      log.info("Audit: advancing stuck pdf_done record {}", recordId);
      stateMachine.autoAdvance(recordId);
    }
    total += pdfDoneStuck.size();

    // --- Pass 7: Stuck translating records where all translation jobs are actually done ---
    List<Long> translatingDone =
        jdbcTemplate.queryForList(
            """
        SELECT r.id FROM record r
        WHERE r.status = 'translating'
          AND NOT EXISTS (
            SELECT 1 FROM job j
            WHERE j.record_id = r.id
              AND j.kind IN ('translate_page', 'translate_page_upgrade', 'translate_record')
              AND j.status NOT IN ('completed', 'failed')
          )
        ORDER BY r.id
        """,
            Long.class);

    for (Long recordId : translatingDone) {
      // autoAdvance deliberately does NOT enqueue embed_record here: the embed job was already
      // enqueued on entry to TRANSLATING. This pass used to enqueue a second one, embedding
      // every audited record twice.
      log.info("Audit: record {} translating → embedding (all translation done)", recordId);
      stateMachine.autoAdvance(recordId);
    }
    total += translatingDone.size();

    // --- Pass 8: Backfill missing translation completed events ---
    List<Long> translationEventsMissing =
        jdbcTemplate.queryForList(
            """
        SELECT r.id FROM record r
        WHERE r.status = 'complete'
          AND NOT EXISTS (
            SELECT 1 FROM pipeline_event pe
            WHERE pe.record_id = r.id AND pe.stage = 'translation' AND pe.event = 'completed'
          )
          AND EXISTS (
            SELECT 1 FROM job j
            WHERE j.record_id = r.id
              AND j.kind IN ('translate_page', 'translate_page_upgrade', 'translate_record')
              AND j.status = 'completed'
          )
        ORDER BY r.id
        """,
            Long.class);

    for (Long recordId : translationEventsMissing) {
      log.info("Audit: logging missing translation completed event for record {}", recordId);
      logPipelineEvent(recordId, "translation", "completed", "retroactive from audit");
    }
    total += translationEventsMissing.size();

    // --- Pass 9: Stuck embedding records where embed job is done ---
    List<Long> embeddingDone =
        jdbcTemplate.queryForList(
            """
        SELECT r.id FROM record r
        WHERE r.status = 'embedding'
          AND EXISTS (
            SELECT 1 FROM job j
            WHERE j.record_id = r.id
              AND j.kind = 'embed_record'
              AND j.status = 'completed'
          )
        ORDER BY r.id
        """,
            Long.class);

    for (Long recordId : embeddingDone) {
      log.info("Audit: advancing stuck embedding record {}", recordId);
      stateMachine.autoAdvance(recordId);
    }
    total += embeddingDone.size();

    // --- Pass 9b: Stuck matching records where match job is done ---
    List<Long> matchingDone =
        jdbcTemplate.queryForList(
            """
        SELECT r.id FROM record r
        WHERE r.status = 'matching'
          AND EXISTS (
            SELECT 1 FROM job j
            WHERE j.record_id = r.id
              AND j.kind = 'match_persons'
              AND j.status = 'completed'
          )
        ORDER BY r.id
        """,
            Long.class);

    for (Long recordId : matchingDone) {
      log.info("Audit: advancing stuck matching record {}", recordId);
      stateMachine.autoAdvance(recordId);
    }
    total += matchingDone.size();

    // --- Pass 9c: embedding records with no embed_record job at all ---
    //     Pass 9 only advances records whose embed job finished. A record that reached
    //     'embedding' without one (legacy rows, or a transition that predates the state
    //     machine) has nothing to wait for and would sit there forever.
    List<Long> embeddingNoJob =
        jdbcTemplate.queryForList(
            """
        SELECT r.id FROM record r
        WHERE r.status = 'embedding'
          AND NOT EXISTS (
            SELECT 1 FROM job j
            WHERE j.record_id = r.id AND j.kind = 'embed_record'
          )
        ORDER BY r.id
        """,
            Long.class);

    for (Long recordId : embeddingNoJob) {
      log.info("Audit: record {} embedding with no embed job, enqueuing", recordId);
      enqueueJob("embed_record", recordId, null, null);
      logPipelineEvent(recordId, "embedding", "started", "backfill from audit");
    }
    total += embeddingNoJob.size();

    // --- Pass 10: Backfill embedding for complete records that were never embedded ---
    List<Long> completeUnembedded =
        jdbcTemplate.queryForList(
            """
        SELECT r.id FROM record r
        WHERE r.status = 'complete'
          AND NOT EXISTS (
            SELECT 1 FROM job j
            WHERE j.record_id = r.id
              AND j.kind = 'embed_record'
          )
        ORDER BY r.id
        """,
            Long.class);

    for (Long recordId : completeUnembedded) {
      jdbcTemplate.update(
          "UPDATE record SET status = 'embedding', updated_at = now() WHERE id = ?", recordId);
      enqueueJob("embed_record", recordId, null, null);
      logPipelineEvent(recordId, "embedding", "started", "backfill from audit");
      log.info("Audit: record {} complete → embedding (backfill)", recordId);
      recordEventService.recordChanged(recordId, "status");
    }
    total += completeUnembedded.size();

    log.info(
        "Pipeline audit complete: {} stale jobs reset, {} failed retried, {} ingesting fixed, "
            + "{} ocr_pending text-done, {} ocr_done re-queued, {} pdf_pending nudged, "
            + "{} pdf_done advanced, {} translating→embedding, "
            + "{} translation events backfilled, "
            + "{} embedding advanced, {} embedding re-queued, {} matching advanced, "
            + "{} complete→embedding backfill ({} total)",
        staleClaimed,
        failedRetried,
        ingestingStuck.size(),
        ocrPendingAllDone.size(),
        ocrDoneStuck.size(),
        pdfPendingStuck.size(),
        pdfDoneStuck.size(),
        translatingDone.size(),
        translationEventsMissing.size(),
        embeddingDone.size(),
        embeddingNoJob.size(),
        matchingDone.size(),
        completeUnembedded.size(),
        total);
    return total;
  }
}
