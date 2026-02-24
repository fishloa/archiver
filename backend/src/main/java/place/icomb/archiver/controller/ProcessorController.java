package place.icomb.archiver.controller;

import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import place.icomb.archiver.dto.EntityHitRequest;
import place.icomb.archiver.dto.JobClaimRequest;
import place.icomb.archiver.dto.JobCompleteRequest;
import place.icomb.archiver.dto.JobFailRequest;
import place.icomb.archiver.dto.JobResponse;
import place.icomb.archiver.dto.OcrResultRequest;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.model.Job;
import place.icomb.archiver.model.Page;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.repository.JobRepository;
import place.icomb.archiver.repository.PageRepository;
import place.icomb.archiver.service.JobEventService;
import place.icomb.archiver.service.JobService;
import place.icomb.archiver.service.StorageService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/processor")
public class ProcessorController {

  private final JobService jobService;
  private final JobEventService jobEventService;
  private final JobRepository jobRepository;
  private final PageRepository pageRepository;
  private final AttachmentRepository attachmentRepository;
  private final StorageService storageService;
  private final JdbcTemplate jdbcTemplate;
  private final String processorToken;

  public ProcessorController(
      JobService jobService,
      JobEventService jobEventService,
      JobRepository jobRepository,
      PageRepository pageRepository,
      AttachmentRepository attachmentRepository,
      StorageService storageService,
      JdbcTemplate jdbcTemplate,
      @Value("${archiver.processor.token}") String processorToken) {
    this.jobService = jobService;
    this.jobEventService = jobEventService;
    this.jobRepository = jobRepository;
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.storageService = storageService;
    this.jdbcTemplate = jdbcTemplate;
    this.processorToken = processorToken;
  }

  // -------------------------------------------------------------------------
  // SSE job events
  // -------------------------------------------------------------------------

  @GetMapping(value = "/jobs/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamJobEvents(@RequestHeader("Authorization") String authHeader) {
    validateToken(authHeader);
    return jobEventService.subscribe();
  }

  // -------------------------------------------------------------------------
  // Job lifecycle
  // -------------------------------------------------------------------------

  @PostMapping("/jobs/claim")
  public ResponseEntity<JobResponse> claimJob(
      @RequestHeader("Authorization") String authHeader,
      @Valid @RequestBody JobClaimRequest request) {
    validateToken(authHeader);
    return jobService
        .claimJob(request.kind())
        .map(j -> ResponseEntity.ok(toJobResponse(j)))
        .orElse(ResponseEntity.noContent().build());
  }

  @GetMapping("/jobs/{jobId}")
  public ResponseEntity<JobResponse> getJob(
      @RequestHeader("Authorization") String authHeader, @PathVariable Long jobId) {
    validateToken(authHeader);
    return jobRepository
        .findById(jobId)
        .map(j -> ResponseEntity.ok(toJobResponse(j)))
        .orElse(ResponseEntity.notFound().build());
  }

  @PostMapping("/jobs/{jobId}/complete")
  public ResponseEntity<JobResponse> completeJob(
      @RequestHeader("Authorization") String authHeader,
      @PathVariable Long jobId,
      @RequestBody(required = false) JobCompleteRequest request) {
    validateToken(authHeader);
    String result = request != null ? request.result() : null;
    Job job = jobService.completeJob(jobId, result);
    return ResponseEntity.ok(toJobResponse(job));
  }

  @PostMapping("/jobs/{jobId}/fail")
  public ResponseEntity<JobResponse> failJob(
      @RequestHeader("Authorization") String authHeader,
      @PathVariable Long jobId,
      @Valid @RequestBody JobFailRequest request) {
    validateToken(authHeader);
    Job job = jobService.failJob(jobId, request.error());
    return ResponseEntity.ok(toJobResponse(job));
  }

  // -------------------------------------------------------------------------
  // Page image streaming
  // -------------------------------------------------------------------------

  @GetMapping("/pages/{pageId}/image")
  public ResponseEntity<Resource> getPageImage(
      @RequestHeader("Authorization") String authHeader, @PathVariable Long pageId) {
    validateToken(authHeader);
    Page page =
        pageRepository
            .findById(pageId)
            .orElseThrow(() -> new IllegalArgumentException("Page not found: " + pageId));
    Attachment attachment =
        attachmentRepository
            .findById(page.getAttachmentId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Attachment not found: " + page.getAttachmentId()));

    Resource resource = storageService.streamFile(attachment);
    return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(resource);
  }

  // -------------------------------------------------------------------------
  // OCR results
  // -------------------------------------------------------------------------

  @PostMapping("/ocr/{pageId}")
  public ResponseEntity<Map<String, Object>> submitOcrResult(
      @RequestHeader("Authorization") String authHeader,
      @PathVariable Long pageId,
      @RequestBody OcrResultRequest request) {
    validateToken(authHeader);
    pageRepository
        .findById(pageId)
        .orElseThrow(() -> new IllegalArgumentException("Page not found: " + pageId));

    jdbcTemplate.update(
        "INSERT INTO page_text (page_id, engine, confidence, text_raw, hocr, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        pageId,
        request.engine(),
        request.confidence(),
        request.textRaw(),
        request.hocr(),
        Instant.now());

    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("pageId", pageId, "status", "ok"));
  }

  @PostMapping(value = "/ocr/{pageId}/artifact", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Map<String, Object>> uploadOcrArtifact(
      @RequestHeader("Authorization") String authHeader,
      @PathVariable Long pageId,
      @RequestPart("file") MultipartFile file)
      throws IOException {
    validateToken(authHeader);
    Page page =
        pageRepository
            .findById(pageId)
            .orElseThrow(() -> new IllegalArgumentException("Page not found: " + pageId));

    String originalFilename =
        file.getOriginalFilename() != null ? file.getOriginalFilename() : "artifact";
    String path =
        storageService.storeDeriv(page.getRecordId(), "ocr", originalFilename, file.getBytes());

    Attachment attachment = new Attachment();
    attachment.setRecordId(page.getRecordId());
    attachment.setRole("ocr_artifact");
    attachment.setPath(path);
    attachment.setMime(file.getContentType());
    attachment.setBytes(file.getSize());
    attachment.setCreatedAt(Instant.now());
    attachmentRepository.save(attachment);

    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("pageId", pageId, "path", path));
  }

  // -------------------------------------------------------------------------
  // Searchable PDF upload
  // -------------------------------------------------------------------------

  @PostMapping(
      value = "/records/{recordId}/searchable-pdf",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Map<String, Object>> uploadSearchablePdf(
      @RequestHeader("Authorization") String authHeader,
      @PathVariable Long recordId,
      @RequestPart("pdf") MultipartFile pdf)
      throws IOException {
    validateToken(authHeader);

    String path = storageService.storeDeriv(recordId, "pdf", "searchable.pdf", pdf.getBytes());

    Attachment attachment = new Attachment();
    attachment.setRecordId(recordId);
    attachment.setRole("searchable_pdf");
    attachment.setPath(path);
    attachment.setMime("application/pdf");
    attachment.setBytes(pdf.getSize());
    attachment.setCreatedAt(Instant.now());
    attachmentRepository.save(attachment);

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("recordId", recordId, "path", path));
  }

  // -------------------------------------------------------------------------
  // Entity extraction results
  // -------------------------------------------------------------------------

  @PostMapping("/entities/{pageId}")
  public ResponseEntity<Map<String, Object>> submitEntities(
      @RequestHeader("Authorization") String authHeader,
      @PathVariable Long pageId,
      @RequestBody EntityHitRequest request) {
    validateToken(authHeader);
    pageRepository
        .findById(pageId)
        .orElseThrow(() -> new IllegalArgumentException("Page not found: " + pageId));

    for (EntityHitRequest.EntityHitItem item : request.entities()) {
      jdbcTemplate.update(
          "INSERT INTO entity_hit (page_id, entity_type, value, confidence, start_offset,"
              + " end_offset, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
          pageId,
          item.entityType(),
          item.value(),
          item.confidence(),
          item.startOffset(),
          item.endOffset(),
          Instant.now());
    }

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("pageId", pageId, "count", request.entities().size()));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private void validateToken(String authHeader) {
    if (authHeader == null
        || !authHeader.startsWith("Bearer ")
        || !authHeader.substring(7).equals(processorToken)) {
      throw new SecurityException("Invalid processor token");
    }
  }

  private JobResponse toJobResponse(Job job) {
    return new JobResponse(
        job.getId(),
        job.getKind(),
        job.getRecordId(),
        job.getPageId(),
        job.getPayload(),
        job.getStatus(),
        job.getAttempts(),
        job.getError(),
        job.getCreatedAt(),
        job.getStartedAt(),
        job.getFinishedAt());
  }
}
