package place.icomb.archiver.controller;

import java.util.List;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import place.icomb.archiver.dto.PageResponse;
import place.icomb.archiver.dto.RecordResponse;
import place.icomb.archiver.model.Page;
import place.icomb.archiver.model.Record;
import place.icomb.archiver.repository.ArchiveRepository;
import place.icomb.archiver.repository.PageRepository;
import place.icomb.archiver.repository.RecordRepository;
import place.icomb.archiver.service.RecordEventService;

@RestController
@RequestMapping("/api/records")
public class CatalogueController {

  private final RecordRepository recordRepository;
  private final ArchiveRepository archiveRepository;
  private final PageRepository pageRepository;
  private final JdbcTemplate jdbcTemplate;
  private final RecordEventService recordEventService;

  public CatalogueController(
      RecordRepository recordRepository,
      ArchiveRepository archiveRepository,
      PageRepository pageRepository,
      JdbcTemplate jdbcTemplate,
      RecordEventService recordEventService) {
    this.recordRepository = recordRepository;
    this.archiveRepository = archiveRepository;
    this.pageRepository = pageRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.recordEventService = recordEventService;
  }

  private static final java.util.Set<String> SORTABLE_COLUMNS =
      java.util.Set.of(
          "id", "title", "dateRangeText", "referenceCode", "status", "createdAt", "pageCount");

  @GetMapping
  public ResponseEntity<org.springframework.data.domain.Page<RecordResponse>> listRecords(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt") String sortBy,
      @RequestParam(defaultValue = "desc") String sortDir,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Long archiveId) {

    boolean hasStatus = status != null && !status.isBlank();
    boolean hasArchive = archiveId != null;

    if (!hasStatus && !hasArchive) {
      Sort sort =
          sortDir.equalsIgnoreCase("asc")
              ? Sort.by(sortBy).ascending()
              : Sort.by(sortBy).descending();
      Pageable pageable = PageRequest.of(page, size, sort);
      org.springframework.data.domain.Page<Record> records = recordRepository.findAll(pageable);
      return ResponseEntity.ok(records.map(this::toResponse));
    }

    // Build filtered query using JdbcTemplate
    String col = toSnakeCase(sortBy);
    if (!SORTABLE_COLUMNS.contains(sortBy)) col = "created_at";
    String dir = sortDir.equalsIgnoreCase("asc") ? "ASC" : "DESC";
    int offset = page * size;

    StringBuilder where = new StringBuilder("WHERE 1=1");
    java.util.List<Object> params = new java.util.ArrayList<>();
    if (hasStatus) {
      where.append(" AND status = ?");
      params.add(status);
    }
    if (hasArchive) {
      where.append(" AND archive_id = ?");
      params.add(archiveId);
    }

    Long total =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM record " + where, Long.class, params.toArray());
    List<Record> rows =
        jdbcTemplate.query(
            "SELECT * FROM record " + where + " ORDER BY " + col + " " + dir + " LIMIT ? OFFSET ?",
            new BeanPropertyRowMapper<>(Record.class),
            concatParams(params, size, offset));
    Pageable pageable = PageRequest.of(page, size);
    org.springframework.data.domain.Page<RecordResponse> result =
        new PageImpl<>(
            rows.stream().map(this::toResponse).toList(), pageable, total != null ? total : 0);
    return ResponseEntity.ok(result);
  }

  private static Object[] concatParams(java.util.List<Object> base, Object... extra) {
    java.util.List<Object> all = new java.util.ArrayList<>(base);
    java.util.Collections.addAll(all, extra);
    return all.toArray();
  }

  private static String toSnakeCase(String camel) {
    return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
  }

  @GetMapping("/{id}")
  public ResponseEntity<RecordResponse> getRecord(@PathVariable Long id) {
    return recordRepository
        .findById(id)
        .map(r -> ResponseEntity.ok(toResponse(r)))
        .orElse(ResponseEntity.notFound().build());
  }

  @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamEvents() {
    return recordEventService.subscribe();
  }

  @GetMapping("/{id}/pages")
  public ResponseEntity<List<PageResponse>> getRecordPages(@PathVariable Long id) {
    if (recordRepository.findById(id).isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    List<Page> pages = pageRepository.findByRecordId(id);
    List<PageResponse> response =
        pages.stream()
            .map(
                p ->
                    new PageResponse(
                        p.getId(),
                        p.getRecordId(),
                        p.getSeq(),
                        p.getAttachmentId(),
                        p.getPageLabel(),
                        p.getWidth(),
                        p.getHeight(),
                        p.getSourceUrl()))
            .toList();
    return ResponseEntity.ok(response);
  }

  @GetMapping("/archives")
  public ResponseEntity<List<ArchiveResponse>> listArchives() {
    List<ArchiveResponse> archives = new java.util.ArrayList<>();
    archiveRepository
        .findAll()
        .forEach(a -> archives.add(new ArchiveResponse(a.getId(), a.getName(), a.getCountry())));
    archives.sort(java.util.Comparator.comparing(ArchiveResponse::id));
    return ResponseEntity.ok(archives);
  }

  public record ArchiveResponse(Long id, String name, String country) {}

  private RecordResponse toResponse(Record r) {
    return new RecordResponse(
        r.getId(),
        r.getArchiveId(),
        r.getCollectionId(),
        r.getSourceSystem(),
        r.getSourceRecordId(),
        r.getTitle(),
        r.getDescription(),
        r.getDateRangeText(),
        r.getDateStartYear(),
        r.getDateEndYear(),
        r.getReferenceCode(),
        r.getInventoryNumber(),
        r.getCallNumber(),
        r.getContainerType(),
        r.getContainerNumber(),
        r.getFindingAidNumber(),
        r.getIndexTerms(),
        r.getRawSourceMetadata(),
        r.getPdfAttachmentId(),
        r.getTitleEn(),
        r.getDescriptionEn(),
        r.getAttachmentCount(),
        r.getPageCount(),
        r.getStatus(),
        r.getCreatedAt(),
        r.getUpdatedAt(),
        r.getSourceUrl());
  }
}
