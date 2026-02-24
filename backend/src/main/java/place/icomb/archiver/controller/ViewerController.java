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
      return ResponseEntity.ok(Map.of("pageId", pageId, "text", "", "confidence", 0.0, "engine", ""));
    }
    // Return the highest-confidence result
    PageText best = texts.stream()
        .max(Comparator.comparing(PageText::getConfidence, Comparator.nullsFirst(Comparator.naturalOrder())))
        .orElse(texts.get(0));
    return ResponseEntity.ok(Map.of(
        "pageId", pageId,
        "text", best.getTextRaw() != null ? best.getTextRaw() : "",
        "confidence", best.getConfidence() != null ? best.getConfidence() : 0.0f,
        "engine", best.getEngine() != null ? best.getEngine() : ""
    ));
  }

  @GetMapping("/search")
  public ResponseEntity<Map<String, Object>> searchPages(
      @RequestParam String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    if (q == null || q.isBlank()) {
      return ResponseEntity.ok(Map.of("results", List.of(), "total", 0));
    }

    long total = pageTextRepository.countByText(q);
    List<PageText> hits = pageTextRepository.searchByText(q, size, page * size);

    List<Map<String, Object>> results = hits.stream().map(pt -> {
      var pageEntity = pageRepository.findById(pt.getPageId()).orElse(null);
      var record = pageEntity != null ? recordRepository.findById(pageEntity.getRecordId()).orElse(null) : null;

      // Extract snippet around the match
      String text = pt.getTextRaw() != null ? pt.getTextRaw() : "";
      String snippet = extractSnippet(text, q, 200);

      Map<String, Object> result = new LinkedHashMap<>();
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
      return result;
    }).toList();

    return ResponseEntity.ok(Map.of("results", results, "total", total, "page", page, "size", size));
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
