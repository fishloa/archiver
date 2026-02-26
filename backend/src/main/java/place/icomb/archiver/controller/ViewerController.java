package place.icomb.archiver.controller;

import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.dto.PageResponse;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.model.PageText;
import place.icomb.archiver.model.Record;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.repository.PageRepository;
import place.icomb.archiver.repository.PageTextRepository;
import place.icomb.archiver.repository.RecordRepository;
import place.icomb.archiver.service.JobService;
import place.icomb.archiver.service.PdfExportService;
import place.icomb.archiver.service.StorageService;

@RestController
@RequestMapping("/api")
public class ViewerController {

  private final PageRepository pageRepository;
  private final AttachmentRepository attachmentRepository;
  private final RecordRepository recordRepository;
  private final StorageService storageService;
  private final PageTextRepository pageTextRepository;
  private final JdbcTemplate jdbcTemplate;
  private final JobService jobService;
  private final PdfExportService pdfExportService;
  private final place.icomb.archiver.service.JobEventService jobEventService;

  public ViewerController(
      PageRepository pageRepository,
      AttachmentRepository attachmentRepository,
      RecordRepository recordRepository,
      StorageService storageService,
      PageTextRepository pageTextRepository,
      JdbcTemplate jdbcTemplate,
      JobService jobService,
      PdfExportService pdfExportService,
      place.icomb.archiver.service.JobEventService jobEventService) {
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.recordRepository = recordRepository;
    this.storageService = storageService;
    this.pageTextRepository = pageTextRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.jobService = jobService;
    this.pdfExportService = pdfExportService;
    this.jobEventService = jobEventService;
  }

  @GetMapping("/pipeline/stats")
  public ResponseEntity<Map<String, Object>> pipelineStats() {
    // Record counts per status
    List<Map<String, Object>> recordCounts =
        jdbcTemplate.queryForList(
            "SELECT status, COUNT(*) AS cnt, COALESCE(SUM(page_count),0) AS pages"
                + " FROM record GROUP BY status");

    Map<String, Long> recordsByStatus = new LinkedHashMap<>();
    Map<String, Long> pagesByStatus = new LinkedHashMap<>();
    long totalRecords = 0;
    long totalPages = 0;
    for (var row : recordCounts) {
      String status = (String) row.get("status");
      long cnt = ((Number) row.get("cnt")).longValue();
      long pages = ((Number) row.get("pages")).longValue();
      recordsByStatus.put(status, cnt);
      pagesByStatus.put(status, pages);
      totalRecords += cnt;
      totalPages += pages;
    }

    // Job counts per kind+status
    List<Map<String, Object>> jobCounts =
        jdbcTemplate.queryForList(
            "SELECT kind, status, COUNT(*) AS cnt FROM job GROUP BY kind, status");

    Map<String, Map<String, Long>> jobsByKind = new LinkedHashMap<>();
    for (var row : jobCounts) {
      String kind = (String) row.get("kind");
      String status = (String) row.get("status");
      long cnt = ((Number) row.get("cnt")).longValue();
      jobsByKind.computeIfAbsent(kind, k -> new LinkedHashMap<>()).put(status, cnt);
    }

    // Connected worker counts per job kind
    Map<String, Integer> workerCounts = jobEventService.getWorkerCounts();

    // Per-stage page progress: how many pages have completed within each active stage
    // OCR: pages with page_text vs total pages for records in ocr_pending
    Map<String, Object> ocrProgress = jdbcTemplate.queryForMap("""
        SELECT COALESCE(SUM(CASE WHEN EXISTS (SELECT 1 FROM page_text pt WHERE pt.page_id = p.id)
               THEN 1 ELSE 0 END), 0) AS done,
               COUNT(*) AS total
        FROM page p JOIN record r ON r.id = p.record_id
        WHERE r.status = 'ocr_pending'
        """);
    long ocrPagesDone = ((Number) ocrProgress.get("done")).longValue();
    long ocrPagesTotal = ((Number) ocrProgress.get("total")).longValue();

    // Translation: pages with text_en vs total pages for records in translating/pdf_pending/pdf_done
    Map<String, Object> transProgress = jdbcTemplate.queryForMap("""
        SELECT COALESCE(SUM(CASE WHEN pt.text_en IS NOT NULL AND pt.text_en != ''
               THEN 1 ELSE 0 END), 0) AS done,
               COUNT(*) AS total
        FROM page p
        JOIN record r ON r.id = p.record_id
        LEFT JOIN page_text pt ON pt.page_id = p.id
        WHERE r.status IN ('pdf_pending', 'pdf_done', 'translating')
        """);
    long transPagesDone = ((Number) transProgress.get("done")).longValue();
    long transPagesTotal = ((Number) transProgress.get("total")).longValue();

    // Scraping: pages already downloaded vs expected page_count for ingesting records
    Map<String, Object> scrapingProgress = jdbcTemplate.queryForMap("""
        SELECT COALESCE(SUM((SELECT count(*) FROM page p WHERE p.record_id = r.id)), 0) AS done,
               COALESCE(SUM(r.page_count), 0) AS total
        FROM record r WHERE r.status = 'ingesting'
        """);
    long scrapingPagesDone = ((Number) scrapingProgress.get("done")).longValue();
    long scrapingPagesTotal = ((Number) scrapingProgress.get("total")).longValue();

    // Build stage objects
    List<Map<String, Object>> stages = new java.util.ArrayList<>();

    var scrapingStage = buildStage(
        "Scraping", "ingesting", recordsByStatus, pagesByStatus, null, jobsByKind, workerCounts);
    scrapingStage.put("pagesDone", scrapingPagesDone);
    scrapingStage.put("pagesTotal", scrapingPagesTotal);
    stages.add(scrapingStage);

    stages.add(
        buildStage(
            "Ingested", "ingested", recordsByStatus, pagesByStatus, null, jobsByKind,
            workerCounts));

    var ocrStage = buildStage(
        "OCR", "ocr_pending", recordsByStatus, pagesByStatus,
        new String[] {"ocr_page_paddle"}, jobsByKind, workerCounts);
    ocrStage.put("pagesDone", ocrPagesDone);
    ocrStage.put("pagesTotal", ocrPagesTotal);
    stages.add(ocrStage);

    stages.add(
        buildStage(
            "PDF Build",
            "pdf_pending",
            recordsByStatus,
            pagesByStatus,
            new String[] {"build_searchable_pdf"},
            jobsByKind,
            workerCounts));

    var transStage = buildStage(
        "Translation", "translating", recordsByStatus, pagesByStatus,
        new String[] {"translate_page", "translate_record"}, jobsByKind, workerCounts);
    transStage.put("pagesDone", transPagesDone);
    transStage.put("pagesTotal", transPagesTotal);
    stages.add(transStage);

    stages.add(
        buildStage(
            "Embedding",
            "embedding",
            recordsByStatus,
            pagesByStatus,
            new String[] {"embed_record"},
            jobsByKind,
            workerCounts));

    // "Complete" aggregates terminal statuses
    long doneRecords =
        recordsByStatus.getOrDefault("ocr_done", 0L)
            + recordsByStatus.getOrDefault("pdf_done", 0L)
            + recordsByStatus.getOrDefault("entities_pending", 0L)
            + recordsByStatus.getOrDefault("entities_done", 0L)
            + recordsByStatus.getOrDefault("complete", 0L);
    long donePages =
        pagesByStatus.getOrDefault("ocr_done", 0L)
            + pagesByStatus.getOrDefault("pdf_done", 0L)
            + pagesByStatus.getOrDefault("entities_pending", 0L)
            + pagesByStatus.getOrDefault("entities_done", 0L)
            + pagesByStatus.getOrDefault("complete", 0L);
    Map<String, Object> completeStage = new LinkedHashMap<>();
    completeStage.put("name", "Complete");
    completeStage.put("records", doneRecords);
    completeStage.put("pages", donePages);
    stages.add(completeStage);

    return ResponseEntity.ok(
        Map.of("stages", stages, "totals", Map.of("records", totalRecords, "pages", totalPages)));
  }

  private Map<String, Object> buildStage(
      String name,
      String recordStatus,
      Map<String, Long> recordsByStatus,
      Map<String, Long> pagesByStatus,
      String[] jobKinds,
      Map<String, Map<String, Long>> jobsByKind,
      Map<String, Integer> workerCounts) {
    Map<String, Object> stage = new LinkedHashMap<>();
    stage.put("name", name);
    stage.put("records", recordsByStatus.getOrDefault(recordStatus, 0L));
    stage.put("pages", pagesByStatus.getOrDefault(recordStatus, 0L));
    if (jobKinds != null) {
      long pending = 0, running = 0, completed = 0, failed = 0;
      int workers = 0;
      for (String kind : jobKinds) {
        Map<String, Long> jobs = jobsByKind.getOrDefault(kind, Map.of());
        pending += jobs.getOrDefault("pending", 0L);
        running += jobs.getOrDefault("claimed", 0L);
        completed += jobs.getOrDefault("completed", 0L);
        failed += jobs.getOrDefault("failed", 0L);
        workers = Math.max(workers, workerCounts.getOrDefault(kind, 0));
      }
      stage.put("jobsPending", pending);
      stage.put("jobsRunning", running);
      stage.put("jobsCompleted", completed);
      stage.put("jobsFailed", failed);
      stage.put("workersConnected", workers);
    }
    return stage;
  }

  @GetMapping("/pages/{pageId}")
  public ResponseEntity<PageResponse> getPage(@PathVariable Long pageId) {
    return pageRepository
        .findById(pageId)
        .map(
            p ->
                ResponseEntity.ok(
                    new PageResponse(
                        p.getId(),
                        p.getRecordId(),
                        p.getSeq(),
                        p.getAttachmentId(),
                        p.getPageLabel(),
                        p.getWidth(),
                        p.getHeight(),
                        p.getSourceUrl())))
        .orElse(ResponseEntity.notFound().build());
  }

  @GetMapping("/pages/{pageId}/text")
  public ResponseEntity<Map<String, Object>> getPageText(@PathVariable Long pageId) {
    List<PageText> texts = pageTextRepository.findByPageId(pageId);
    if (texts.isEmpty()) {
      return ResponseEntity.ok(
          Map.of("pageId", pageId, "text", "", "confidence", 0.0, "engine", ""));
    }
    // Return the highest-confidence result
    PageText best =
        texts.stream()
            .max(
                Comparator.comparing(
                    PageText::getConfidence, Comparator.nullsFirst(Comparator.naturalOrder())))
            .orElse(texts.get(0));
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("pageId", pageId);
    result.put("text", best.getTextRaw() != null ? best.getTextRaw() : "");
    result.put("confidence", best.getConfidence() != null ? best.getConfidence() : 0.0f);
    result.put("engine", best.getEngine() != null ? best.getEngine() : "");
    result.put("textEn", best.getTextEn() != null ? best.getTextEn() : "");
    return ResponseEntity.ok(result);
  }

  @GetMapping("/search")
  public ResponseEntity<Map<String, Object>> searchPages(
      @RequestParam String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    if (q == null || q.isBlank()) {
      return ResponseEntity.ok(Map.of("results", List.of(), "total", 0, "page", 0, "size", size));
    }

    // Parse query into positive and negative terms
    // e.g. "czernin -palace -palais" -> include ["czernin"], exclude ["palace", "palais"]
    List<String> includeTerms = new java.util.ArrayList<>();
    List<String> excludeTerms = new java.util.ArrayList<>();
    parseSearchTerms(q, includeTerms, excludeTerms);

    if (includeTerms.isEmpty()) {
      return ResponseEntity.ok(
          Map.of("results", List.of(), "total", 0, "page", page, "size", size));
    }

    int offset = page * size;

    // Build dynamic OCR search SQL
    StringBuilder ocrWhere = new StringBuilder();
    List<Object> ocrParams = new java.util.ArrayList<>();
    for (int i = 0; i < includeTerms.size(); i++) {
      if (i > 0) ocrWhere.append(" AND ");
      ocrWhere.append("pt.text_norm ILIKE '%' || immutable_unaccent(lower(?)) || '%'");
      ocrParams.add(includeTerms.get(i));
    }
    for (String exc : excludeTerms) {
      ocrWhere.append(" AND pt.text_norm NOT ILIKE '%' || immutable_unaccent(lower(?)) || '%'");
      ocrParams.add(exc);
    }

    // Build dynamic metadata search SQL
    StringBuilder metaWhere = new StringBuilder();
    List<Object> metaParams = new java.util.ArrayList<>();
    // Positive terms: each must match at least one metadata field
    for (int i = 0; i < includeTerms.size(); i++) {
      if (i > 0) metaWhere.append(" AND ");
      String pat = "%" + includeTerms.get(i).toLowerCase().replace("%", "\\%") + "%";
      metaWhere.append(
          "(lower(r.title) LIKE ? OR lower(r.description) LIKE ? OR lower(r.reference_code) LIKE ?)");
      metaParams.add(pat);
      metaParams.add(pat);
      metaParams.add(pat);
    }
    // Negative terms: must not match any metadata field
    for (String exc : excludeTerms) {
      String pat = "%" + exc.toLowerCase().replace("%", "\\%") + "%";
      metaWhere.append(" AND lower(COALESCE(r.title,'')) NOT LIKE ?");
      metaWhere.append(" AND lower(COALESCE(r.description,'')) NOT LIKE ?");
      metaWhere.append(" AND lower(COALESCE(r.reference_code,'')) NOT LIKE ?");
      metaParams.add(pat);
      metaParams.add(pat);
      metaParams.add(pat);
    }

    // Count: combined OCR + metadata
    List<Object> countParams = new java.util.ArrayList<>();
    countParams.addAll(ocrParams);
    countParams.addAll(metaParams);
    Long total =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ("
                + " SELECT pt.id FROM page_text pt WHERE "
                + ocrWhere
                + " UNION"
                + " SELECT -r.id FROM record r WHERE "
                + metaWhere
                + ") sub",
            Long.class,
            countParams.toArray());

    // OCR text hits
    List<Object> ocrQueryParams = new java.util.ArrayList<>(ocrParams);
    ocrQueryParams.add(size);
    ocrQueryParams.add(offset);
    List<Map<String, Object>> ocrRows =
        jdbcTemplate.queryForList(
            "SELECT pt.* FROM page_text pt"
                + " WHERE "
                + ocrWhere
                + " ORDER BY pt.confidence DESC NULLS LAST"
                + " LIMIT ? OFFSET ?",
            ocrQueryParams.toArray());

    // Use the first include term for snippet extraction
    String snippetTerm = includeTerms.get(0);

    List<Map<String, Object>> results = new java.util.ArrayList<>();
    for (var row : ocrRows) {
      Long ptId = ((Number) row.get("id")).longValue();
      Long ptPageId = ((Number) row.get("page_id")).longValue();
      Float confidence =
          row.get("confidence") != null ? ((Number) row.get("confidence")).floatValue() : null;
      String engine = row.get("engine") != null ? row.get("engine").toString() : "";
      String textRaw = row.get("text_raw") != null ? row.get("text_raw").toString() : "";

      var pageEntity = pageRepository.findById(ptPageId).orElse(null);
      var record =
          pageEntity != null
              ? recordRepository.findById(pageEntity.getRecordId()).orElse(null)
              : null;

      String snippet = extractSnippet(textRaw, snippetTerm, 200);

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("type", "ocr");
      result.put("pageTextId", ptId);
      result.put("pageId", ptPageId);
      result.put("confidence", confidence);
      result.put("engine", engine);
      result.put("snippet", snippet);
      if (pageEntity != null) {
        result.put("seq", pageEntity.getSeq());
        result.put("recordId", pageEntity.getRecordId());
      }
      if (record != null) {
        result.put("recordTitle", record.getTitle());
        result.put("referenceCode", record.getReferenceCode());
      }
      results.add(result);
    }

    // If we have room, add metadata matches (records whose title/description/ref match)
    if (results.size() < size) {
      int metaLimit = size - results.size();
      List<Object> metaQueryParams = new java.util.ArrayList<>(metaParams);
      metaQueryParams.add(metaLimit);
      List<Map<String, Object>> metaHits =
          jdbcTemplate.queryForList(
              "SELECT r.id, r.title, r.description, r.reference_code, r.status, r.page_count"
                  + " FROM record r"
                  + " WHERE "
                  + metaWhere
                  + " ORDER BY r.id DESC"
                  + " LIMIT ?",
              metaQueryParams.toArray());

      for (var row : metaHits) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "record");
        result.put("recordId", row.get("id"));
        result.put("recordTitle", row.get("title"));
        result.put("referenceCode", row.get("reference_code"));
        result.put("pageCount", row.get("page_count"));
        result.put("status", row.get("status"));
        // Build snippet from whichever field matched
        String desc = row.get("description") != null ? row.get("description").toString() : "";
        String title = row.get("title") != null ? row.get("title").toString() : "";
        if (title.toLowerCase().contains(snippetTerm.toLowerCase())) {
          result.put("snippet", title);
        } else if (desc.toLowerCase().contains(snippetTerm.toLowerCase())) {
          result.put("snippet", extractSnippet(desc, snippetTerm, 200));
        } else {
          result.put("snippet", row.get("reference_code"));
        }
        results.add(result);
      }
    }

    return ResponseEntity.ok(
        Map.of(
            "results", results, "total", total != null ? total : 0L, "page", page, "size", size));
  }

  /**
   * Parses a search query string into positive (include) and negative (exclude) terms. Terms
   * prefixed with '-' are exclusions. For example: "czernin -palace -palais" ->
   * include=["czernin"], exclude=["palace", "palais"]
   */
  private void parseSearchTerms(String query, List<String> include, List<String> exclude) {
    String[] tokens = query.trim().split("\\s+");
    for (String token : tokens) {
      if (token.startsWith("-") && token.length() > 1) {
        exclude.add(token.substring(1));
      } else if (!token.isEmpty()) {
        include.add(token);
      }
    }
  }

  @GetMapping("/files/{attachmentId}")
  public ResponseEntity<Resource> streamFile(@PathVariable Long attachmentId) {
    Attachment attachment = attachmentRepository.findById(attachmentId).orElse(null);
    if (attachment == null) {
      return ResponseEntity.notFound().build();
    }

    Resource resource = storageService.streamFile(attachment);
    MediaType mediaType =
        attachment.getMime() != null
            ? MediaType.parseMediaType(attachment.getMime())
            : MediaType.APPLICATION_OCTET_STREAM;

    return ResponseEntity.ok()
        .contentType(mediaType)
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
        .body(resource);
  }

  @GetMapping("/files/{attachmentId}/thumbnail")
  public ResponseEntity<Resource> streamThumbnail(@PathVariable Long attachmentId) {
    // Stub: In a full implementation, this would serve a pre-generated thumbnail.
    // For now, serve the original file (useful for page images that are already small).
    return streamFile(attachmentId);
  }

  @GetMapping("/records/{recordId}/pdf")
  public ResponseEntity<Resource> streamRecordPdf(@PathVariable Long recordId) {
    Record record = recordRepository.findById(recordId).orElse(null);
    if (record == null || record.getPdfAttachmentId() == null) {
      return ResponseEntity.notFound().build();
    }

    Attachment attachment = attachmentRepository.findById(record.getPdfAttachmentId()).orElse(null);
    if (attachment == null) {
      return ResponseEntity.notFound().build();
    }

    Resource resource = storageService.streamFile(attachment);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"record-" + recordId + ".pdf\"")
        .body(resource);
  }

  @GetMapping("/records/{recordId}/export-pdf")
  public ResponseEntity<Resource> exportPdf(
      @PathVariable Long recordId, @RequestParam String pages) {
    Record record = recordRepository.findById(recordId).orElse(null);
    if (record == null) {
      return ResponseEntity.notFound().build();
    }

    List<Integer> seqNumbers;
    try {
      seqNumbers = pdfExportService.parsePageRange(pages);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().build();
    }

    if (seqNumbers.isEmpty()) {
      return ResponseEntity.badRequest().build();
    }

    try {
      byte[] pdfBytes = pdfExportService.buildPdf(recordId, seqNumbers);
      ByteArrayResource resource = new ByteArrayResource(pdfBytes);
      String filename = "record-" + recordId + "-pages.pdf";
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_PDF)
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
          .contentLength(pdfBytes.length)
          .body(resource);
    } catch (IOException e) {
      return ResponseEntity.internalServerError().build();
    }
  }

  @GetMapping("/records/{recordId}/timeline")
  public ResponseEntity<List<Map<String, Object>>> getRecordTimeline(@PathVariable Long recordId) {
    // Pipeline events
    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT stage, event, detail, created_at FROM pipeline_event WHERE record_id = ? ORDER BY created_at ASC",
            recordId);

    // Also include job-level timing as supplementary data
    List<Map<String, Object>> jobStats =
        jdbcTemplate.queryForList(
            """
        SELECT kind, status, count(*) AS cnt,
               min(created_at) AS first_created,
               min(started_at) AS first_started,
               max(finished_at) AS last_finished
        FROM job WHERE record_id = ?
        GROUP BY kind, status
        ORDER BY kind, status
        """,
            recordId);

    return ResponseEntity.ok(
        List.of(
            Map.of("type", "events", "data", events), Map.of("type", "jobs", "data", jobStats)));
  }

  // -------------------------------------------------------------------------
  // Admin endpoints
  // -------------------------------------------------------------------------

  @PostMapping("/admin/audit")
  public ResponseEntity<Map<String, Object>> runAudit() {
    int fixed = jobService.auditPipeline();
    return ResponseEntity.ok(Map.of("fixed", fixed));
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
            "SELECT record_id, stage, event, detail, created_at FROM pipeline_event ORDER BY created_at DESC LIMIT 20"));

    return ResponseEntity.ok(stats);
  }

  private String extractSnippet(String text, String query, int maxLen) {
    String lower = text.toLowerCase();
    String queryLower = query.toLowerCase();
    int idx = lower.indexOf(queryLower);
    if (idx < 0) {
      return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
    int start = Math.max(0, idx - maxLen / 4);
    int end = Math.min(text.length(), idx + queryLower.length() + maxLen * 3 / 4);
    String snippet = text.substring(start, end);
    if (start > 0) snippet = "..." + snippet;
    if (end < text.length()) snippet = snippet + "...";
    return snippet;
  }
}
