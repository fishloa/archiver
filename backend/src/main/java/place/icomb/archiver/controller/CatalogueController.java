package place.icomb.archiver.controller;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.dto.PageResponse;
import place.icomb.archiver.dto.RecordResponse;
import place.icomb.archiver.model.Page;
import place.icomb.archiver.model.Record;
import place.icomb.archiver.repository.PageRepository;
import place.icomb.archiver.repository.RecordRepository;

@RestController
@RequestMapping("/api/records")
public class CatalogueController {

  private final RecordRepository recordRepository;
  private final PageRepository pageRepository;

  public CatalogueController(RecordRepository recordRepository, PageRepository pageRepository) {
    this.recordRepository = recordRepository;
    this.pageRepository = pageRepository;
  }

  @GetMapping
  public ResponseEntity<org.springframework.data.domain.Page<RecordResponse>> listRecords(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt") String sortBy,
      @RequestParam(defaultValue = "desc") String sortDir) {
    Sort sort =
        sortDir.equalsIgnoreCase("asc")
            ? Sort.by(sortBy).ascending()
            : Sort.by(sortBy).descending();
    Pageable pageable = PageRequest.of(page, size, sort);
    org.springframework.data.domain.Page<Record> records = recordRepository.findAll(pageable);
    org.springframework.data.domain.Page<RecordResponse> response = records.map(this::toResponse);
    return ResponseEntity.ok(response);
  }

  @GetMapping("/{id}")
  public ResponseEntity<RecordResponse> getRecord(@PathVariable Long id) {
    return recordRepository
        .findById(id)
        .map(r -> ResponseEntity.ok(toResponse(r)))
        .orElse(ResponseEntity.notFound().build());
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
                        p.getHeight()))
            .toList();
    return ResponseEntity.ok(response);
  }

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
        r.getPdfAttachmentId(),
        r.getAttachmentCount(),
        r.getPageCount(),
        r.getStatus(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
