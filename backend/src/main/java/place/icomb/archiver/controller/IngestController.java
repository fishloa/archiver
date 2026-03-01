package place.icomb.archiver.controller;

import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import place.icomb.archiver.dto.IngestRecordRequest;
import place.icomb.archiver.dto.IngestRecordResponse;
import place.icomb.archiver.dto.PageMetadata;
import place.icomb.archiver.dto.PageResponse;
import place.icomb.archiver.model.Archive;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.model.Page;
import place.icomb.archiver.model.Record;
import place.icomb.archiver.repository.ArchiveRepository;
import place.icomb.archiver.repository.PageRepository;
import place.icomb.archiver.repository.RecordRepository;
import place.icomb.archiver.service.IngestService;

@RestController
@RequestMapping("/api/ingest")
public class IngestController {

  private final IngestService ingestService;
  private final RecordRepository recordRepository;
  private final ArchiveRepository archiveRepository;
  private final PageRepository pageRepository;

  public IngestController(
      IngestService ingestService,
      RecordRepository recordRepository,
      ArchiveRepository archiveRepository,
      PageRepository pageRepository) {
    this.ingestService = ingestService;
    this.recordRepository = recordRepository;
    this.archiveRepository = archiveRepository;
    this.pageRepository = pageRepository;
  }

  // ── Archive CRUD ──────────────────────────────────────────────────────────

  @GetMapping("/archives")
  public Iterable<Archive> listArchives() {
    return archiveRepository.findAll();
  }

  @GetMapping("/archives/{id}")
  public ResponseEntity<Archive> getArchive(@PathVariable Long id) {
    return archiveRepository
        .findById(id)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @PostMapping("/archives")
  public ResponseEntity<Archive> createArchive(@RequestBody Map<String, String> body) {
    Archive archive = new Archive();
    archive.setName(body.get("name"));
    archive.setCountry(body.get("country"));
    archive.setCitationTemplate(body.get("citationTemplate"));
    archive.setCreatedAt(Instant.now());
    archive = archiveRepository.save(archive);
    return ResponseEntity.status(HttpStatus.CREATED).body(archive);
  }

  @PutMapping("/archives/{id}")
  public ResponseEntity<Archive> updateArchive(
      @PathVariable Long id, @RequestBody Map<String, String> body) {
    return archiveRepository
        .findById(id)
        .map(
            archive -> {
              if (body.containsKey("name")) archive.setName(body.get("name"));
              if (body.containsKey("country")) archive.setCountry(body.get("country"));
              if (body.containsKey("citationTemplate"))
                archive.setCitationTemplate(body.get("citationTemplate"));
              return ResponseEntity.ok(archiveRepository.save(archive));
            })
        .orElse(ResponseEntity.notFound().build());
  }

  @DeleteMapping("/archives/{id}")
  public ResponseEntity<Void> deleteArchive(@PathVariable Long id) {
    if (archiveRepository.existsById(id)) {
      archiveRepository.deleteById(id);
      return ResponseEntity.noContent().build();
    }
    return ResponseEntity.notFound().build();
  }

  // ── Record ingest ───────────────────────────────────────────────────────

  @PostMapping("/records")
  public ResponseEntity<IngestRecordResponse> createOrUpdateRecord(
      @Valid @RequestBody IngestRecordRequest request) {
    Record record = ingestService.createOrUpdateRecord(request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new IngestRecordResponse(
                record.getId(),
                record.getSourceSystem(),
                record.getSourceRecordId(),
                record.getStatus()));
  }

  @PostMapping(value = "/records/{recordId}/pages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<PageResponse> addPage(
      @PathVariable Long recordId,
      @RequestParam int seq,
      @RequestPart("image") MultipartFile image,
      @RequestPart(value = "metadata", required = false) PageMetadata metadata)
      throws IOException {
    Page page = ingestService.addPage(recordId, seq, image.getBytes(), metadata);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new PageResponse(
                page.getId(),
                page.getRecordId(),
                page.getSeq(),
                page.getAttachmentId(),
                page.getPageLabel(),
                page.getWidth(),
                page.getHeight(),
                page.getSourceUrl()));
  }

  @PostMapping(value = "/records/{recordId}/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Map<String, Object>> addPdf(
      @PathVariable Long recordId, @RequestPart("pdf") MultipartFile pdf) throws IOException {
    Attachment attachment = ingestService.addPdf(recordId, pdf.getBytes());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Map.of(
                "attachmentId", attachment.getId(),
                "path", attachment.getPath(),
                "sha256", attachment.getSha256()));
  }

  @PostMapping(
      value = "/records/{recordId}/text-pdf",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Map<String, Object>> addTextPdf(
      @PathVariable Long recordId, @RequestPart("pdf") MultipartFile pdf) throws IOException {
    int pageCount = ingestService.addTextPdf(recordId, pdf.getBytes());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("recordId", recordId, "pages", pageCount, "ocrSkipped", true));
  }

  @PostMapping("/records/{recordId}/repair")
  public ResponseEntity<Map<String, Object>> repairRecord(@PathVariable Long recordId) {
    Record record = ingestService.repairRecord(recordId);
    List<Integer> existingSeqs =
        pageRepository.findByRecordId(recordId).stream()
            .map(Page::getSeq)
            .sorted()
            .collect(Collectors.toList());
    return ResponseEntity.ok(
        Map.of(
            "id", record.getId(),
            "status", record.getStatus(),
            "existingPages", existingSeqs));
  }

  @PostMapping("/records/{recordId}/complete")
  public ResponseEntity<IngestRecordResponse> completeIngest(@PathVariable Long recordId) {
    Record record = ingestService.completeIngest(recordId);
    return ResponseEntity.ok(
        new IngestRecordResponse(
            record.getId(),
            record.getSourceSystem(),
            record.getSourceRecordId(),
            record.getStatus()));
  }

  @DeleteMapping("/records/{recordId}")
  public ResponseEntity<Void> deleteRecord(@PathVariable Long recordId) {
    ingestService.deleteRecord(recordId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/records/by-source/{sourceSystem}/{sourceRecordId}")
  public ResponseEntity<Void> deleteRecordBySource(
      @PathVariable String sourceSystem, @PathVariable String sourceRecordId) {
    return recordRepository
        .findBySourceSystemAndSourceRecordId(sourceSystem, sourceRecordId)
        .map(
            r -> {
              ingestService.deleteRecord(r.getId());
              return ResponseEntity.noContent().<Void>build();
            })
        .orElse(ResponseEntity.notFound().build());
  }

  @GetMapping("/status/{sourceSystem}/{sourceRecordId}")
  public ResponseEntity<IngestRecordResponse> getStatus(
      @PathVariable String sourceSystem, @PathVariable String sourceRecordId) {
    return recordRepository
        .findBySourceSystemAndSourceRecordId(sourceSystem, sourceRecordId)
        .map(
            r ->
                ResponseEntity.ok(
                    new IngestRecordResponse(
                        r.getId(), r.getSourceSystem(), r.getSourceRecordId(), r.getStatus())))
        .orElse(ResponseEntity.notFound().build());
  }

  @GetMapping("/status/{sourceSystem}")
  public ResponseEntity<Map<String, String>> getAllStatuses(@PathVariable String sourceSystem) {
    List<Record> records = recordRepository.findBySourceSystem(sourceSystem);
    Map<String, String> statuses =
        records.stream().collect(Collectors.toMap(Record::getSourceRecordId, Record::getStatus));
    return ResponseEntity.ok(statuses);
  }
}
