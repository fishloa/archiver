package place.icomb.archiver.service;

import static place.icomb.archiver.service.PipelineState.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Formal state machine for the record processing pipeline. All valid transitions, guards, and
 * actions are defined here — single source of truth.
 */
@Service
public class PipelineStateMachine {

  private static final Logger log = LoggerFactory.getLogger(PipelineStateMachine.class);

  private final JdbcTemplate jdbcTemplate;
  private final JobService jobService;
  private final RecordEventService recordEventService;

  private final Map<PipelineState, List<Transition>> transitions =
      new EnumMap<>(PipelineState.class);

  record Transition(
      PipelineState target, Predicate<RecordContext> guard, Consumer<RecordContext> action) {}

  /** Context object passed to guards and actions. Lazily populated. */
  static class RecordContext {
    final Long recordId;
    final JdbcTemplate jdbc;

    // Lazily loaded
    private Boolean hasPages;
    private Boolean allOcrJobsComplete;
    private Boolean pdfJobComplete;
    private Boolean allTranslationJobsComplete;
    private Boolean hasTranslationJobs;
    private Boolean embedJobComplete;
    private Boolean matchJobComplete;
    private Boolean hasPrePopulatedText;
    private String contentLang;
    private String metadataLang;

    RecordContext(Long recordId, JdbcTemplate jdbc) {
      this.recordId = recordId;
      this.jdbc = jdbc;
    }

    boolean hasPages() {
      if (hasPages == null) {
        Long count =
            jdbc.queryForObject(
                "SELECT count(*) FROM page WHERE record_id = ?", Long.class, recordId);
        hasPages = count != null && count > 0;
      }
      return hasPages;
    }

    boolean allOcrJobsComplete() {
      if (allOcrJobsComplete == null) {
        Long pending =
            jdbc.queryForObject(
                """
                SELECT count(*) FROM job
                WHERE record_id = ?
                  AND kind IN ('ocr_page_paddle', 'ocr_page_abbyy', 'ocr_page_qwen3vl')
                  AND status IN ('pending', 'claimed')
                """,
                Long.class,
                recordId);
        allOcrJobsComplete = pending != null && pending == 0;
      }
      return allOcrJobsComplete;
    }

    boolean pdfJobComplete() {
      if (pdfJobComplete == null) {
        Long completed =
            jdbc.queryForObject(
                """
                SELECT count(*) FROM job
                WHERE record_id = ? AND kind = 'build_searchable_pdf' AND status = 'completed'
                """,
                Long.class,
                recordId);
        pdfJobComplete = completed != null && completed > 0;
      }
      return pdfJobComplete;
    }

    boolean allTranslationJobsComplete() {
      if (allTranslationJobsComplete == null) {
        Long pending =
            jdbc.queryForObject(
                """
                SELECT count(*) FROM job
                WHERE record_id = ?
                  AND kind IN ('translate_page', 'translate_record')
                  AND status NOT IN ('completed', 'failed')
                """,
                Long.class,
                recordId);
        allTranslationJobsComplete = pending != null && pending == 0;
      }
      return allTranslationJobsComplete;
    }

    boolean hasTranslationJobs() {
      if (hasTranslationJobs == null) {
        Long count =
            jdbc.queryForObject(
                """
                SELECT count(*) FROM job
                WHERE record_id = ?
                  AND kind IN ('translate_page', 'translate_record')
                  AND status != 'completed'
                """,
                Long.class,
                recordId);
        hasTranslationJobs = count != null && count > 0;
      }
      return hasTranslationJobs;
    }

    boolean embedJobComplete() {
      if (embedJobComplete == null) {
        Long completed =
            jdbc.queryForObject(
                """
                SELECT count(*) FROM job
                WHERE record_id = ? AND kind = 'embed_record' AND status = 'completed'
                """,
                Long.class,
                recordId);
        embedJobComplete = completed != null && completed > 0;
      }
      return embedJobComplete;
    }

    boolean matchJobComplete() {
      if (matchJobComplete == null) {
        Long completed =
            jdbc.queryForObject(
                """
                SELECT count(*) FROM job
                WHERE record_id = ? AND kind = 'match_persons' AND status = 'completed'
                """,
                Long.class,
                recordId);
        matchJobComplete = completed != null && completed > 0;
      }
      return matchJobComplete;
    }

    boolean hasPrePopulatedText() {
      if (hasPrePopulatedText == null) {
        // All pages have text already (text-pdf ingest scenario)
        Long pagesWithoutText =
            jdbc.queryForObject(
                """
                SELECT count(*) FROM page p
                WHERE p.record_id = ?
                  AND NOT EXISTS (SELECT 1 FROM page_text pt WHERE pt.page_id = p.id)
                """,
                Long.class,
                recordId);
        hasPrePopulatedText = hasPages() && pagesWithoutText != null && pagesWithoutText == 0;
      }
      return hasPrePopulatedText;
    }

    String contentLang() {
      if (contentLang == null) {
        contentLang =
            jdbc.queryForObject("SELECT lang FROM record WHERE id = ?", String.class, recordId);
      }
      return contentLang;
    }

    String metadataLang() {
      if (metadataLang == null) {
        metadataLang =
            jdbc.queryForObject(
                "SELECT metadata_lang FROM record WHERE id = ?", String.class, recordId);
      }
      return metadataLang;
    }
  }

  public PipelineStateMachine(
      JdbcTemplate jdbcTemplate, JobService jobService, RecordEventService recordEventService) {
    this.jdbcTemplate = jdbcTemplate;
    this.jobService = jobService;
    this.recordEventService = recordEventService;
    // Break circular dependency: JobService ← PipelineStateMachine → JobService
    jobService.setStateMachine(this);
    defineTransitions();
  }

  private void defineTransitions() {
    // INGESTING → OCR_PENDING: has pages that need OCR
    addTransition(
        INGESTING, OCR_PENDING, ctx -> ctx.hasPages() && !ctx.hasPrePopulatedText(), ctx -> {});

    // INGESTING → OCR_DONE: no pages OR all text pre-populated
    addTransition(
        INGESTING, OCR_DONE, ctx -> !ctx.hasPages() || ctx.hasPrePopulatedText(), ctx -> {});

    // OCR_PENDING → OCR_DONE: all OCR jobs complete
    addTransition(
        OCR_PENDING,
        OCR_DONE,
        RecordContext::allOcrJobsComplete,
        ctx -> logPipelineEvent(ctx.recordId, "ocr", "completed", null));

    // OCR_DONE → PDF_PENDING: has pages (enqueue PDF + translations)
    addTransition(
        OCR_DONE, PDF_PENDING, RecordContext::hasPages, ctx -> enqueuePostOcrJobs(ctx, true));

    // OCR_DONE → TRANSLATING: no pages but has translations to do
    addTransition(
        OCR_DONE,
        TRANSLATING,
        ctx -> !ctx.hasPages() && needsTranslation(ctx),
        ctx -> enqueuePostOcrJobs(ctx, false));

    // OCR_DONE → EMBEDDING: no pages and no translation needed
    addTransition(
        OCR_DONE,
        EMBEDDING,
        ctx -> !ctx.hasPages() && !needsTranslation(ctx),
        ctx -> enqueueEmbedJob(ctx.recordId));

    // PDF_PENDING → PDF_DONE: PDF job complete
    addTransition(
        PDF_PENDING, PDF_DONE, RecordContext::pdfJobComplete, ctx -> setPdfAttachmentId(ctx));

    // PDF_DONE → TRANSLATING: still has pending translation jobs
    addTransition(PDF_DONE, TRANSLATING, RecordContext::hasTranslationJobs, ctx -> {});

    // PDF_DONE → EMBEDDING: no pending translation jobs
    addTransition(
        PDF_DONE,
        EMBEDDING,
        ctx -> !ctx.hasTranslationJobs(),
        ctx -> {
          logTranslationCompleteIfNeeded(ctx);
          enqueueEmbedJob(ctx.recordId);
        });

    // TRANSLATING → EMBEDDING: all translation jobs complete
    addTransition(
        TRANSLATING,
        EMBEDDING,
        RecordContext::allTranslationJobsComplete,
        ctx -> {
          logPipelineEvent(ctx.recordId, "translation", "completed", null);
          enqueueEmbedJob(ctx.recordId);
        });

    // EMBEDDING → MATCHING: embed job complete
    addTransition(
        EMBEDDING,
        MATCHING,
        RecordContext::embedJobComplete,
        ctx -> {
          logPipelineEvent(ctx.recordId, "embedding", "completed", null);
          jobService.enqueueJob("match_persons", ctx.recordId, null, null);
          logPipelineEvent(ctx.recordId, "matching", "started", null);
        });

    // MATCHING → COMPLETE: match job complete
    addTransition(
        MATCHING,
        COMPLETE,
        RecordContext::matchJobComplete,
        ctx -> logPipelineEvent(ctx.recordId, "matching", "completed", null));
  }

  private void addTransition(
      PipelineState from,
      PipelineState to,
      Predicate<RecordContext> guard,
      Consumer<RecordContext> action) {
    transitions
        .computeIfAbsent(from, k -> new ArrayList<>())
        .add(new Transition(to, guard, action));
  }

  /**
   * Evaluates guards from the current state and chains through transitions until no more apply.
   * E.g. OCR_PENDING → OCR_DONE → PDF_PENDING all in one call. Returns true if any transition
   * occurred.
   */
  @Transactional
  public boolean autoAdvance(Long recordId) {
    boolean advanced = false;
    for (int i = 0; i < 10; i++) { // safety limit to prevent infinite loops
      if (!tryAdvanceOnce(recordId)) break;
      advanced = true;
    }
    return advanced;
  }

  private boolean tryAdvanceOnce(Long recordId) {
    String currentStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM record WHERE id = ?", String.class, recordId);
    if (currentStatus == null) return false;

    PipelineState current;
    try {
      current = PipelineState.fromDb(currentStatus);
    } catch (IllegalArgumentException e) {
      log.warn(
          "Record {} has unrecognized status '{}', cannot auto-advance", recordId, currentStatus);
      return false;
    }

    List<Transition> available = transitions.get(current);
    if (available == null) return false;

    // Fresh context for each step (cached values from previous state may be stale)
    RecordContext ctx = new RecordContext(recordId, jdbcTemplate);
    for (Transition t : available) {
      if (t.guard().test(ctx)) {
        return executeTransition(recordId, current, t.target(), t.action(), ctx);
      }
    }
    return false;
  }

  /**
   * Forces a transition to the specified target state. Validates the transition is defined. Used
   * for explicit transitions (not guard-based).
   */
  @Transactional
  public void transition(Long recordId, PipelineState target) {
    String currentStatus =
        jdbcTemplate.queryForObject(
            "SELECT status FROM record WHERE id = ?", String.class, recordId);
    if (currentStatus == null) {
      throw new IllegalStateException("Record not found: " + recordId);
    }

    PipelineState current = PipelineState.fromDb(currentStatus);
    List<Transition> available = transitions.get(current);
    if (available == null) {
      throw new IllegalStateException(
          "No transitions defined from " + current + " for record " + recordId);
    }

    RecordContext ctx = new RecordContext(recordId, jdbcTemplate);
    for (Transition t : available) {
      if (t.target() == target && t.guard().test(ctx)) {
        executeTransition(recordId, current, target, t.action(), ctx);
        return;
      }
    }

    throw new IllegalStateException(
        "Invalid or guarded transition " + current + " → " + target + " for record " + recordId);
  }

  private boolean executeTransition(
      Long recordId,
      PipelineState from,
      PipelineState to,
      Consumer<RecordContext> action,
      RecordContext ctx) {
    // Atomic status update — only succeeds if still in the expected state
    int updated =
        jdbcTemplate.update(
            "UPDATE record SET status = ?, updated_at = now() WHERE id = ? AND status = ?",
            to.toDb(),
            recordId,
            from.toDb());

    if (updated == 0) {
      log.debug(
          "Record {} transition {} → {} lost race (status already changed)", recordId, from, to);
      return false;
    }

    log.info("Record {} {} → {}", recordId, from, to);

    // Execute side-effects
    action.accept(ctx);

    recordEventService.recordChanged(recordId, "status");
    return true;
  }

  // --- Side-effect helpers ---

  private void enqueuePostOcrJobs(RecordContext ctx, boolean includesPdf) {
    Long recordId = ctx.recordId;

    if (includesPdf) {
      jobService.enqueueJob("build_searchable_pdf", recordId, null, null);
      logPipelineEvent(recordId, "pdf_build", "started", null);
    } else {
      logPipelineEvent(recordId, "pdf_build", "completed", "skipped (no pages)");
    }

    String metadataLang = ctx.metadataLang();
    String contentLang = ctx.contentLang();

    // Metadata translation
    if (metadataLang == null || !"en".equals(metadataLang)) {
      String metaPayload = metadataLang != null ? "{\"lang\":\"" + metadataLang + "\"}" : null;
      jobService.enqueueJob("translate_record", recordId, null, metaPayload);
    }

    // Page translations
    int translateCount = 0;
    if (ctx.hasPages() && (contentLang == null || !"en".equals(contentLang))) {
      List<Long> pageIds =
          jdbcTemplate.queryForList(
              "SELECT p.id FROM page p WHERE p.record_id = ? ORDER BY p.seq", Long.class, recordId);
      for (Long pageId : pageIds) {
        jobService.enqueueJob("translate_page", recordId, pageId, null);
      }
      translateCount = pageIds.size();
    } else if (ctx.hasPages() && "en".equals(contentLang)) {
      // English content: copy OCR text directly to text_en
      jdbcTemplate.update(
          "UPDATE page_text SET text_en = text_raw WHERE page_id IN (SELECT id FROM page WHERE record_id = ?) AND (text_en IS NULL OR text_en = '')",
          recordId);
    }

    logPipelineEvent(recordId, "translation", "started", translateCount + " page jobs enqueued");
  }

  private void enqueueEmbedJob(Long recordId) {
    jobService.enqueueJob("embed_record", recordId, null, null);
    logPipelineEvent(recordId, "embedding", "started", null);
  }

  private boolean needsTranslation(RecordContext ctx) {
    String metadataLang = ctx.metadataLang();
    return metadataLang == null || !"en".equals(metadataLang);
  }

  private void setPdfAttachmentId(RecordContext ctx) {
    Long pdfAttId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM attachment WHERE record_id = ? AND role = 'searchable_pdf' ORDER BY id DESC LIMIT 1",
            Long.class,
            ctx.recordId);
    if (pdfAttId != null) {
      jdbcTemplate.update(
          "UPDATE record SET pdf_attachment_id = ? WHERE id = ?", pdfAttId, ctx.recordId);
      logPipelineEvent(ctx.recordId, "pdf_build", "completed", "attachment_id=" + pdfAttId);
    }
  }

  private void logTranslationCompleteIfNeeded(RecordContext ctx) {
    Long totalTranslation =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM job WHERE record_id = ? AND kind IN ('translate_page', 'translate_record')",
            Long.class,
            ctx.recordId);
    if (totalTranslation != null && totalTranslation > 0) {
      logPipelineEvent(ctx.recordId, "translation", "completed", "finished before pdf");
    }
  }

  void logPipelineEvent(Long recordId, String stage, String event, String detail) {
    jdbcTemplate.update(
        "INSERT INTO pipeline_event (record_id, stage, event, detail, created_at) VALUES (?, ?, ?, ?, now())",
        recordId,
        stage,
        event,
        detail);
  }
}
