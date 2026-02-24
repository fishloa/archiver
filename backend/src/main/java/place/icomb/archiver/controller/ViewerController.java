package place.icomb.archiver.controller;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

  public ViewerController(
      PageRepository pageRepository,
      AttachmentRepository attachmentRepository,
      RecordRepository recordRepository,
      StorageService storageService,
      PageTextRepository pageTextRepository,
      JdbcTemplate jdbcTemplate) {
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.recordRepository = recordRepository;
    this.storageService = storageService;
    this.pageTextRepository = pageTextRepository;
    this.jdbcTemplate = jdbcTemplate;
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

    // Build stage objects
    List<Map<String, Object>> stages = new java.util.ArrayList<>();

    stages.add(buildStage("Scraping", "ingesting",
        recordsByStatus, pagesByStatus, null, jobsByKind));
    stages.add(buildStage("Ingested", "ingested",
        recordsByStatus, pagesByStatus, null, jobsByKind));
    stages.add(buildStage("OCR", "ocr_pending",
        recordsByStatus, pagesByStatus, "ocr_page_paddle", jobsByKind));
    stages.add(buildStage("PDF Build", "pdf_pending",
        recordsByStatus, pagesByStatus, "build_searchable_pdf", jobsByKind));
    stages.add(buildStage("Entities", "entities_pending",
        recordsByStatus, pagesByStatus, "extract_entities", jobsByKind));

    // "Complete" aggregates terminal statuses
    long doneRecords = recordsByStatus.getOrDefault("ocr_done", 0L)
        + recordsByStatus.getOrDefault("pdf_done", 0L)
        + recordsByStatus.getOrDefault("entities_done", 0L)
        + recordsByStatus.getOrDefault("complete", 0L);
    long donePages = pagesByStatus.getOrDefault("ocr_done", 0L)
        + pagesByStatus.getOrDefault("pdf_done", 0L)
        + pagesByStatus.getOrDefault("entities_done", 0L)
        + pagesByStatus.getOrDefault("complete", 0L);
    Map<String, Object> completeStage = new LinkedHashMap<>();
    completeStage.put("name", "Complete");
    completeStage.put("records", doneRecords);
    completeStage.put("pages", donePages);
    stages.add(completeStage);

    return ResponseEntity.ok(Map.of(
        "stages", stages,
        "totals", Map.of("records", totalRecords, "pages", totalPages)));
  }

  private Map<String, Object> buildStage(
      String name, String recordStatus,
      Map<String, Long> recordsByStatus, Map<String, Long> pagesByStatus,
      String jobKind, Map<String, Map<String, Long>> jobsByKind) {
    Map<String, Object> stage = new LinkedHashMap<>();
    stage.put("name", name);
    stage.put("records", recordsByStatus.getOrDefault(recordStatus, 0L));
    stage.put("pages", pagesByStatus.getOrDefault(recordStatus, 0L));
    if (jobKind != null) {
      Map<String, Long> jobs = jobsByKind.getOrDefault(jobKind, Map.of());
      stage.put("jobsPending", jobs.getOrDefault("pending", 0L));
      stage.put("jobsRunning", jobs.getOrDefault("claimed", 0L));
      stage.put("jobsCompleted", jobs.getOrDefault("completed", 0L));
      stage.put("jobsFailed", jobs.getOrDefault("failed", 0L));
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
                        p.getHeight())))
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
    return ResponseEntity.ok(
        Map.of(
            "pageId", pageId,
            "text", best.getTextRaw() != null ? best.getTextRaw() : "",
            "confidence", best.getConfidence() != null ? best.getConfidence() : 0.0f,
            "engine", best.getEngine() != null ? best.getEngine() : ""));
  }

  @GetMapping("/search")
  public ResponseEntity<Map<String, Object>> searchPages(
      @RequestParam String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    if (q == null || q.isBlank()) {
      return ResponseEntity.ok(Map.of("results", List.of(), "total", 0, "page", 0, "size", size));
    }

    String termPattern = "%" + q.toLowerCase().replace("%", "\\%") + "%";
    int offset = page * size;

    // Combined search: OCR text + record metadata (title, description, referenceCode)
    long total =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM (
              SELECT pt.id FROM page_text pt
              WHERE pt.text_norm ILIKE '%' || immutable_unaccent(lower(?)) || '%'
              UNION
              SELECT -r.id FROM record r
              WHERE lower(r.title) LIKE ?
                 OR lower(r.description) LIKE ?
                 OR lower(r.reference_code) LIKE ?
            ) sub
            """,
            Long.class,
            q,
            termPattern,
            termPattern,
            termPattern);

    // OCR text hits
    List<PageText> ocrHits = pageTextRepository.searchByText(q, size, offset);

    List<Map<String, Object>> results = new java.util.ArrayList<>();
    for (PageText pt : ocrHits) {
      var pageEntity = pageRepository.findById(pt.getPageId()).orElse(null);
      var record =
          pageEntity != null
              ? recordRepository.findById(pageEntity.getRecordId()).orElse(null)
              : null;

      String text = pt.getTextRaw() != null ? pt.getTextRaw() : "";
      String snippet = extractSnippet(text, q, 200);

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("type", "ocr");
      result.put("pageTextId", pt.getId());
      result.put("pageId", pt.getPageId());
      result.put("confidence", pt.getConfidence());
      result.put("engine", pt.getEngine());
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
      // Exclude records already shown via OCR hits
      List<Map<String, Object>> metaHits =
          jdbcTemplate.queryForList(
              """
              SELECT r.id, r.title, r.description, r.reference_code, r.status, r.page_count
              FROM record r
              WHERE (lower(r.title) LIKE ?
                  OR lower(r.description) LIKE ?
                  OR lower(r.reference_code) LIKE ?)
              ORDER BY r.id DESC
              LIMIT ?
              """,
              termPattern,
              termPattern,
              termPattern,
              metaLimit);

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
        if (title.toLowerCase().contains(q.toLowerCase())) {
          result.put("snippet", title);
        } else if (desc.toLowerCase().contains(q.toLowerCase())) {
          result.put("snippet", extractSnippet(desc, q, 200));
        } else {
          result.put("snippet", row.get("reference_code"));
        }
        results.add(result);
      }
    }

    return ResponseEntity.ok(
        Map.of("results", results, "total", total != null ? total : 0, "page", page, "size", size));
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
