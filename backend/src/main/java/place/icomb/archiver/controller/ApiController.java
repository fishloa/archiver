package place.icomb.archiver.controller;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.model.Page;
import place.icomb.archiver.model.PageText;
import place.icomb.archiver.model.Record;
import place.icomb.archiver.repository.ArchiveRepository;
import place.icomb.archiver.repository.PageRepository;
import place.icomb.archiver.repository.PageTextRepository;
import place.icomb.archiver.repository.RecordRepository;

/**
 * Machine-readable API endpoints designed for use by LLM tools (e.g. Claude Code). Returns
 * self-contained JSON documents with full text content and links.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

  private final RecordRepository recordRepository;
  private final ArchiveRepository archiveRepository;
  private final PageRepository pageRepository;
  private final PageTextRepository pageTextRepository;
  private final JdbcTemplate jdbcTemplate;

  public ApiController(
      RecordRepository recordRepository,
      ArchiveRepository archiveRepository,
      PageRepository pageRepository,
      PageTextRepository pageTextRepository,
      JdbcTemplate jdbcTemplate) {
    this.recordRepository = recordRepository;
    this.archiveRepository = archiveRepository;
    this.pageRepository = pageRepository;
    this.pageTextRepository = pageTextRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  /** List all archives. */
  @GetMapping("/archives")
  public List<Map<String, Object>> listArchives() {
    List<Map<String, Object>> archives = new java.util.ArrayList<>();
    archiveRepository
        .findAll()
        .forEach(
            a -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("id", a.getId());
              m.put("name", a.getName());
              m.put("country", a.getCountry());
              archives.add(m);
            });
    archives.sort(Comparator.comparing(m -> ((Number) m.get("id")).longValue()));
    return archives;
  }

  /**
   * Full document endpoint — returns record metadata, all pages with OCR text (original + English
   * translation), and links to images and PDF.
   */
  @GetMapping("/documents/{recordId}")
  public Map<String, Object> getDocument(
      @PathVariable Long recordId,
      @RequestParam(required = false, defaultValue = "/api") String baseUrl) {

    Record record = recordRepository.findById(recordId).orElse(null);
    if (record == null) {
      return Map.of("error", "Record not found", "recordId", recordId);
    }

    Map<String, Object> doc = new LinkedHashMap<>();

    // Record metadata
    doc.put("id", record.getId());
    doc.put("archiveId", record.getArchiveId());
    doc.put("sourceSystem", record.getSourceSystem());
    doc.put("sourceRecordId", record.getSourceRecordId());
    doc.put("title", record.getTitle());
    doc.put("titleEn", record.getTitleEn());
    doc.put("description", record.getDescription());
    doc.put("descriptionEn", record.getDescriptionEn());
    doc.put("dateRange", record.getDateRangeText());
    doc.put("referenceCode", record.getReferenceCode());
    doc.put("status", record.getStatus());
    doc.put("pageCount", record.getPageCount());
    doc.put("sourceUrl", record.getSourceUrl());
    doc.put("createdAt", record.getCreatedAt());

    // Links
    Map<String, String> links = new LinkedHashMap<>();
    if (record.getPdfAttachmentId() != null) {
      links.put("pdf", baseUrl + "/records/" + recordId + "/pdf");
    }
    links.put("pages", baseUrl + "/records/" + recordId + "/pages");
    links.put("self", baseUrl + "/v1/documents/" + recordId);
    doc.put("links", links);

    // Pages with OCR text
    List<Page> pages = pageRepository.findByRecordId(recordId);
    pages.sort(Comparator.comparingInt(Page::getSeq));

    List<Map<String, Object>> pageList = new java.util.ArrayList<>();
    for (Page p : pages) {
      Map<String, Object> pm = new LinkedHashMap<>();
      pm.put("seq", p.getSeq());
      pm.put("pageLabel", p.getPageLabel());

      // Image link
      if (p.getAttachmentId() != null) {
        pm.put("imageUrl", baseUrl + "/files/" + p.getAttachmentId());
      }

      // OCR text (best confidence)
      List<PageText> texts = pageTextRepository.findByPageId(p.getId());
      if (!texts.isEmpty()) {
        PageText best =
            texts.stream()
                .max(
                    Comparator.comparing(
                        PageText::getConfidence, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(texts.get(0));
        pm.put("text", best.getTextRaw() != null ? best.getTextRaw() : "");
        pm.put("textEn", best.getTextEn() != null ? best.getTextEn() : "");
        pm.put("ocrConfidence", best.getConfidence() != null ? best.getConfidence() : 0.0f);
        pm.put("ocrEngine", best.getEngine() != null ? best.getEngine() : "");
      } else {
        pm.put("text", "");
        pm.put("textEn", "");
      }

      pageList.add(pm);
    }

    doc.put("pages", pageList);
    return doc;
  }

  /**
   * Search endpoint — searches records by text query (OCR content + metadata). Returns a list of
   * matching documents with links to the full document endpoint.
   */
  @GetMapping("/search")
  public Map<String, Object> search(
      @RequestParam String q,
      @RequestParam(required = false) Long archiveId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false, defaultValue = "/api") String baseUrl) {

    if (q == null || q.isBlank()) {
      return Map.of("results", List.of(), "total", 0, "page", 0, "size", size);
    }

    List<String> includeTerms = new java.util.ArrayList<>();
    List<String> excludeTerms = new java.util.ArrayList<>();
    parseSearchTerms(q, includeTerms, excludeTerms);

    if (includeTerms.isEmpty()) {
      return Map.of("results", List.of(), "total", 0, "page", page, "size", size);
    }

    int offset = page * size;

    // Search records matching by OCR text or metadata
    StringBuilder where = new StringBuilder("WHERE 1=1");
    List<Object> params = new java.util.ArrayList<>();

    if (archiveId != null) {
      where.append(" AND r.archive_id = ?");
      params.add(archiveId);
    }

    // Each include term must match title, description, reference_code, or OCR text
    for (String term : includeTerms) {
      String pat = "%" + term.toLowerCase().replace("%", "\\%") + "%";
      where.append(
          " AND (lower(COALESCE(r.title,'')) LIKE ?"
              + " OR lower(COALESCE(r.description,'')) LIKE ?"
              + " OR lower(COALESCE(r.reference_code,'')) LIKE ?"
              + " OR EXISTS (SELECT 1 FROM page p2"
              + " JOIN page_text pt2 ON pt2.page_id = p2.id"
              + " WHERE p2.record_id = r.id"
              + " AND pt2.text_norm ILIKE '%' || immutable_unaccent(lower(?)) || '%'))");
      params.add(pat);
      params.add(pat);
      params.add(pat);
      params.add(term);
    }

    for (String exc : excludeTerms) {
      String pat = "%" + exc.toLowerCase().replace("%", "\\%") + "%";
      where.append(
          " AND lower(COALESCE(r.title,'')) NOT LIKE ?"
              + " AND lower(COALESCE(r.description,'')) NOT LIKE ?");
      params.add(pat);
      params.add(pat);
    }

    // Count
    Long total =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM record r " + where, Long.class, params.toArray());

    // Fetch records
    List<Object> queryParams = new java.util.ArrayList<>(params);
    queryParams.add(size);
    queryParams.add(offset);
    List<Record> rows =
        jdbcTemplate.query(
            "SELECT * FROM record r "
                + where
                + " ORDER BY r.created_at DESC LIMIT ? OFFSET ?",
            new BeanPropertyRowMapper<>(Record.class),
            queryParams.toArray());

    List<Map<String, Object>> results = new java.util.ArrayList<>();
    for (Record r : rows) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("id", r.getId());
      result.put("title", r.getTitle());
      result.put("titleEn", r.getTitleEn());
      result.put("dateRange", r.getDateRangeText());
      result.put("referenceCode", r.getReferenceCode());
      result.put("status", r.getStatus());
      result.put("pageCount", r.getPageCount());
      result.put("sourceUrl", r.getSourceUrl());
      result.put("documentUrl", baseUrl + "/v1/documents/" + r.getId());
      if (r.getPdfAttachmentId() != null) {
        result.put("pdfUrl", baseUrl + "/records/" + r.getId() + "/pdf");
      }
      results.add(result);
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("query", q);
    if (archiveId != null) response.put("archiveId", archiveId);
    response.put("total", total != null ? total : 0L);
    response.put("page", page);
    response.put("size", size);
    response.put("results", results);
    return response;
  }

  /**
   * List records for an archive (browse without search). Returns same format as search but lists
   * all records in the archive.
   */
  @GetMapping("/documents")
  public Map<String, Object> listDocuments(
      @RequestParam(required = false) Long archiveId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false, defaultValue = "/api") String baseUrl) {

    int offset = page * size;
    StringBuilder where = new StringBuilder("WHERE 1=1");
    List<Object> params = new java.util.ArrayList<>();

    if (archiveId != null) {
      where.append(" AND archive_id = ?");
      params.add(archiveId);
    }

    Long total =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM record " + where, Long.class, params.toArray());

    List<Object> queryParams = new java.util.ArrayList<>(params);
    queryParams.add(size);
    queryParams.add(offset);
    List<Record> rows =
        jdbcTemplate.query(
            "SELECT * FROM record " + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
            new BeanPropertyRowMapper<>(Record.class),
            queryParams.toArray());

    List<Map<String, Object>> results = new java.util.ArrayList<>();
    for (Record r : rows) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("id", r.getId());
      result.put("title", r.getTitle());
      result.put("titleEn", r.getTitleEn());
      result.put("dateRange", r.getDateRangeText());
      result.put("referenceCode", r.getReferenceCode());
      result.put("status", r.getStatus());
      result.put("pageCount", r.getPageCount());
      result.put("sourceUrl", r.getSourceUrl());
      result.put("documentUrl", baseUrl + "/v1/documents/" + r.getId());
      if (r.getPdfAttachmentId() != null) {
        result.put("pdfUrl", baseUrl + "/records/" + r.getId() + "/pdf");
      }
      results.add(result);
    }

    Map<String, Object> response = new LinkedHashMap<>();
    if (archiveId != null) response.put("archiveId", archiveId);
    response.put("total", total != null ? total : 0L);
    response.put("page", page);
    response.put("size", size);
    response.put("results", results);
    return response;
  }

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
}
