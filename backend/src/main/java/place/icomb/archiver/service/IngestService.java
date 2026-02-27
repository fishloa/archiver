package place.icomb.archiver.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import place.icomb.archiver.dto.IngestRecordRequest;
import place.icomb.archiver.dto.PageMetadata;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.model.Page;
import place.icomb.archiver.model.Record;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.repository.PageRepository;
import place.icomb.archiver.repository.RecordRepository;

@Service
public class IngestService {

  private final RecordRepository recordRepository;
  private final AttachmentRepository attachmentRepository;
  private final PageRepository pageRepository;
  private final StorageService storageService;
  private final JobService jobService;
  private final JdbcTemplate jdbcTemplate;
  private final RecordEventService recordEventService;

  public IngestService(
      RecordRepository recordRepository,
      AttachmentRepository attachmentRepository,
      PageRepository pageRepository,
      StorageService storageService,
      JobService jobService,
      JdbcTemplate jdbcTemplate,
      RecordEventService recordEventService) {
    this.recordRepository = recordRepository;
    this.attachmentRepository = attachmentRepository;
    this.pageRepository = pageRepository;
    this.storageService = storageService;
    this.jobService = jobService;
    this.jdbcTemplate = jdbcTemplate;
    this.recordEventService = recordEventService;
  }

  /**
   * Creates a new record or updates an existing one matched by sourceSystem + sourceRecordId.
   * Returns the record (created or updated).
   */
  @Transactional
  public Record createOrUpdateRecord(IngestRecordRequest request) {
    Optional<Record> existing =
        recordRepository.findBySourceSystemAndSourceRecordId(
            request.sourceSystem(), request.sourceRecordId());

    Record record;
    if (existing.isPresent()) {
      record = existing.get();
    } else {
      record = new Record();
      record.setSourceSystem(request.sourceSystem());
      record.setSourceRecordId(request.sourceRecordId());
      record.setStatus("ingesting");
      record.setCreatedAt(Instant.now());
    }

    record.setArchiveId(request.archiveId());
    record.setCollectionId(request.collectionId());
    record.setTitle(request.title());
    record.setDescription(request.description());
    record.setDateRangeText(request.dateRangeText());
    record.setDateStartYear(request.dateStartYear());
    record.setDateEndYear(request.dateEndYear());
    record.setReferenceCode(request.referenceCode());
    record.setInventoryNumber(request.inventoryNumber());
    record.setCallNumber(request.callNumber());
    record.setContainerType(request.containerType());
    record.setContainerNumber(request.containerNumber());
    record.setFindingAidNumber(request.findingAidNumber());
    record.setIndexTerms(request.indexTerms());
    record.setRawSourceMetadata(request.rawSourceMetadata());
    if (request.lang() != null) {
      record.setLang(request.lang());
    }
    if (request.metadataLang() != null) {
      record.setMetadataLang(request.metadataLang());
    }
    if (request.sourceUrl() != null) {
      record.setSourceUrl(request.sourceUrl());
    }
    record.setUpdatedAt(Instant.now());

    record = recordRepository.save(record);
    if (!existing.isPresent()) {
      jdbcTemplate.update(
          "INSERT INTO pipeline_event (record_id, stage, event, created_at) VALUES (?, 'ingest', 'started', now())",
          record.getId());
    }
    recordEventService.recordChanged(record.getId(), existing.isPresent() ? "updated" : "created");
    return record;
  }

  /** Stores a page image, creates the attachment and page records. */
  @Transactional
  public Page addPage(Long recordId, int seq, byte[] imageBytes, PageMetadata metadata) {
    Record record =
        recordRepository
            .findById(recordId)
            .orElseThrow(() -> new IllegalArgumentException("Record not found: " + recordId));

    String path = storageService.storePageImage(recordId, seq, imageBytes);
    String sha256 = sha256(imageBytes);

    Attachment attachment = new Attachment();
    attachment.setRecordId(recordId);
    attachment.setRole("page_image");
    attachment.setPath(path);
    attachment.setSha256(sha256);
    attachment.setMime("image/jpeg");
    attachment.setBytes((long) imageBytes.length);
    attachment.setCreatedAt(Instant.now());
    attachment = attachmentRepository.save(attachment);

    Page page = new Page();
    page.setRecordId(recordId);
    page.setSeq(seq);
    page.setAttachmentId(attachment.getId());
    if (metadata != null) {
      page.setPageLabel(metadata.pageLabel());
      page.setWidth(metadata.width());
      page.setHeight(metadata.height());
      if (metadata.sourceUrl() != null) {
        page.setSourceUrl(metadata.sourceUrl());
      }
    }
    page = pageRepository.save(page);

    // Update counters
    record.setPageCount(pageRepository.countByRecordId(recordId));
    record.setAttachmentCount(record.getAttachmentCount() + 1);
    record.setUpdatedAt(Instant.now());
    recordRepository.save(record);

    recordEventService.recordChanged(recordId, "updated");
    return page;
  }

  /** Stores a PDF, creates the attachment record, and links it to the record. */
  @Transactional
  public Attachment addPdf(Long recordId, byte[] pdfBytes) {
    Record record =
        recordRepository
            .findById(recordId)
            .orElseThrow(() -> new IllegalArgumentException("Record not found: " + recordId));

    String path = storageService.storePdf(recordId, pdfBytes);
    String sha256 = sha256(pdfBytes);

    Attachment attachment = new Attachment();
    attachment.setRecordId(recordId);
    attachment.setRole("original_pdf");
    attachment.setPath(path);
    attachment.setSha256(sha256);
    attachment.setMime("application/pdf");
    attachment.setBytes((long) pdfBytes.length);
    attachment.setCreatedAt(Instant.now());
    attachment = attachmentRepository.save(attachment);

    record.setPdfAttachmentId(attachment.getId());
    record.setAttachmentCount(record.getAttachmentCount() + 1);
    record.setUpdatedAt(Instant.now());
    recordRepository.save(record);

    recordEventService.recordChanged(recordId, "updated");
    return attachment;
  }

  /**
   * Reopens a completed/processed record for page repair. Resets status to ingesting, removes
   * stale PDF attachment, and returns existing page sequence numbers so the caller knows which
   * pages are already present.
   */
  @Transactional
  public Record repairRecord(Long recordId) {
    Record record =
        recordRepository
            .findById(recordId)
            .orElseThrow(() -> new IllegalArgumentException("Record not found: " + recordId));

    // Remove old PDF attachment — it will be rebuilt after repair
    if (record.getPdfAttachmentId() != null) {
      record.setPdfAttachmentId(null);
    }

    record.setStatus("ingesting");
    record.setUpdatedAt(Instant.now());
    record = recordRepository.save(record);

    jdbcTemplate.update(
        "INSERT INTO pipeline_event (record_id, stage, event, detail, created_at) VALUES (?, 'ingest', 'repair_started', 'reopened for page repair', now())",
        recordId);
    recordEventService.recordChanged(recordId, "status");
    return record;
  }

  /**
   * Marks the record as ingested, transitions to ocr_pending, and enqueues OCR jobs for all pages.
   * Pages that already have OCR text (from a previous run / repair) are skipped.
   * Fires a NOTIFY on the ocr_jobs channel.
   */
  @Transactional
  public Record completeIngest(Long recordId) {
    Record record =
        recordRepository
            .findById(recordId)
            .orElseThrow(() -> new IllegalArgumentException("Record not found: " + recordId));

    // Enqueue OCR jobs for all pages, passing language in payload
    String ocrPayload = record.getLang() != null ? "{\"lang\":\"" + record.getLang() + "\"}" : null;
    List<Page> pages = pageRepository.findByRecordId(recordId);

    if (pages.isEmpty()) {
      // No pages — skip OCR entirely, go straight to ocr_done and start post-OCR pipeline
      record.setStatus("ocr_done");
      record.setUpdatedAt(Instant.now());
      record = recordRepository.save(record);

      jdbcTemplate.update(
          "INSERT INTO pipeline_event (record_id, stage, event, detail, created_at) VALUES (?, 'ingest', 'completed', '0 pages (metadata-only)', now())",
          recordId);
      jdbcTemplate.update(
          "INSERT INTO pipeline_event (record_id, stage, event, detail, created_at) VALUES (?, 'ocr', 'completed', 'skipped (no pages)', now())",
          recordId);

      recordEventService.recordChanged(recordId, "completed");
      return record;
    }

    record.setStatus("ocr_pending");
    record.setUpdatedAt(Instant.now());
    record = recordRepository.save(record);

    int enqueued = 0;
    for (Page page : pages) {
      // Skip pages that already have OCR text (repair scenario)
      Boolean hasText =
          jdbcTemplate.queryForObject(
              "SELECT EXISTS(SELECT 1 FROM page_text WHERE page_id = ?)",
              Boolean.class,
              page.getId());
      if (hasText != null && hasText) {
        continue;
      }
      jobService.enqueueJob("ocr_page_paddle", recordId, page.getId(), ocrPayload);
      enqueued++;
    }

    int skipped = pages.size() - enqueued;
    String detail = enqueued + " pages" + (skipped > 0 ? " (" + skipped + " already OCR'd)" : "");

    jdbcTemplate.update(
        "INSERT INTO pipeline_event (record_id, stage, event, detail, created_at) VALUES (?, 'ingest', 'completed', ?, now())",
        recordId,
        detail);
    jdbcTemplate.update(
        "INSERT INTO pipeline_event (record_id, stage, event, detail, created_at) VALUES (?, 'ocr', 'started', ?, now())",
        recordId,
        enqueued + " jobs enqueued" + (skipped > 0 ? " (" + skipped + " skipped)" : ""));

    if (enqueued > 0) {
      // Fire NOTIFY so listeners can pick up work immediately
      jdbcTemplate.execute("NOTIFY ocr_jobs");
    }

    recordEventService.recordChanged(recordId, "completed");
    return record;
  }

  /**
   * Deletes a record and all associated data (pages, attachments, jobs, files on disk). Nulls
   * pdf_attachment_id first to avoid circular FK constraint.
   */
  @Transactional
  public void deleteRecord(Long recordId) {
    Record record =
        recordRepository
            .findById(recordId)
            .orElseThrow(() -> new IllegalArgumentException("Record not found: " + recordId));

    // Break circular FK: record → attachment
    if (record.getPdfAttachmentId() != null) {
      record.setPdfAttachmentId(null);
      recordRepository.save(record);
    }

    // Delete files on disk
    storageService.deleteRecordFiles(recordId);

    // Delete record — pages, attachments, jobs etc. cascade via ON DELETE CASCADE
    recordRepository.delete(record);

    recordEventService.recordChanged(recordId, "deleted");
  }

  private static String sha256(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(data);
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }
}
