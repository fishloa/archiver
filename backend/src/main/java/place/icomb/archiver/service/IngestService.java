package place.icomb.archiver.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import place.icomb.archiver.dto.IngestRecordRequest;
import place.icomb.archiver.dto.PageMetadata;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.model.Page;
import place.icomb.archiver.model.PageText;
import place.icomb.archiver.model.Record;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.repository.PageRepository;
import place.icomb.archiver.repository.PageTextRepository;
import place.icomb.archiver.repository.RecordRepository;

@Service
public class IngestService {

  private static final Logger log = LoggerFactory.getLogger(IngestService.class);

  private final RecordRepository recordRepository;
  private final AttachmentRepository attachmentRepository;
  private final PageRepository pageRepository;
  private final PageTextRepository pageTextRepository;
  private final StorageService storageService;
  private final PipelineStateMachine stateMachine;
  private final JdbcTemplate jdbcTemplate;
  private final RecordEventService recordEventService;
  private final PdfExportQueue pdfExportQueue;

  public IngestService(
      RecordRepository recordRepository,
      AttachmentRepository attachmentRepository,
      PageRepository pageRepository,
      PageTextRepository pageTextRepository,
      StorageService storageService,
      PipelineStateMachine stateMachine,
      JdbcTemplate jdbcTemplate,
      RecordEventService recordEventService,
      PdfExportQueue pdfExportQueue) {
    this.recordRepository = recordRepository;
    this.attachmentRepository = attachmentRepository;
    this.pageRepository = pageRepository;
    this.pageTextRepository = pageTextRepository;
    this.storageService = storageService;
    this.stateMachine = stateMachine;
    this.jdbcTemplate = jdbcTemplate;
    this.recordEventService = recordEventService;
    this.pdfExportQueue = pdfExportQueue;
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
    // Anything other than an explicit "best" is bulk: the default must never silently pick the
    // dearer model for 128,484 pages.
    if ("best".equals(request.translationQuality())) {
      record.setTranslationQuality("best");
    } else if (record.getTranslationQuality() == null) {
      record.setTranslationQuality("bulk");
    }
    // An engine named at ingest sticks; a request that omits it leaves an earlier choice alone
    // rather than silently reverting the record to the deployment default.
    if (request.ocrEngine() != null && !request.ocrEngine().isBlank()) {
      record.setOcrEngine(request.ocrEngine());
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

    String sha256 = sha256(imageBytes);
    String path = storageService.storePageImage(imageBytes);

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
   * Replaces an existing page's image in place: deletes the old attachment (cascading away the old
   * page row and everything derived from it -- OCR text, jobs, translations, embeddings) and
   * inserts a fresh page at the same seq via {@link #addPage}. If no page exists yet at that seq,
   * this is equivalent to a plain {@code addPage}. The record's own id is never touched.
   */
  @Transactional
  public Page replacePage(Long recordId, int seq, byte[] imageBytes, PageMetadata metadata) {
    // Lock the record first: a page move addresses pages by seq too.
    jdbcTemplate.queryForList(
        "SELECT id FROM record WHERE id = ? FOR UPDATE", Long.class, recordId);
    Optional<Page> existing = pageRepository.findByRecordIdAndSeq(recordId, seq);
    if (existing.isPresent()) {
      deleteAttachment(existing.get().getAttachmentId());
    }
    Page page = addPage(recordId, seq, imageBytes, metadata);

    // addPage() assumes it's always adding a new attachment on top of what's there; correct the
    // count here since a replace nets to the same attachment count, not +1.
    Record record = recordRepository.findById(recordId).orElseThrow();
    record.setAttachmentCount(attachmentRepository.findByRecordId(recordId).size());
    recordRepository.save(record);

    return page;
  }

  /**
   * Removes one page and closes the gap behind it.
   *
   * <p>Deleting the attachment cascades to the page and everything derived from it — its
   * transcription, translation, chunks and jobs. The pages after it move down, because a record
   * whose pages run 1, 2, 4 reads as one with a page missing rather than one with a page removed.
   */
  @Transactional
  public Record deletePage(Long recordId, int seq) {
    // Lock the record first: a page move addresses pages by seq too.
    jdbcTemplate.queryForList(
        "SELECT id FROM record WHERE id = ? FOR UPDATE", Long.class, recordId);
    Page page =
        pageRepository
            .findByRecordIdAndSeq(recordId, seq)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "No page %d in record %d".formatted(seq, recordId)));

    deleteAttachment(page.getAttachmentId());
    shiftSeq(recordId, seq + 1, -1);
    return refreshCounts(recordId);
  }

  /**
   * Inserts a page at {@code seq}, moving that page and everything after it up by one.
   *
   * <p>With {@link #replacePage} this is enough to correct a scan without re-ingesting the record:
   * a sheet holding two documents becomes the left half in place and the right half inserted after
   * it, and a sideways page is simply replaced by a rotated one.
   */
  @Transactional
  public Page insertPage(Long recordId, int seq, byte[] imageBytes, PageMetadata metadata) {
    // Lock the record first: a page move addresses pages by seq too.
    jdbcTemplate.queryForList(
        "SELECT id FROM record WHERE id = ? FOR UPDATE", Long.class, recordId);
    int pageCount = pageRepository.countByRecordId(recordId);
    if (seq < 1 || seq > pageCount + 1) {
      throw new IllegalArgumentException(
          "seq %d is outside 1..%d for record %d".formatted(seq, pageCount + 1, recordId));
    }
    shiftSeq(recordId, seq, +1);
    Page page = addPage(recordId, seq, imageBytes, metadata);
    refreshCounts(recordId);
    return page;
  }

  /**
   * Deletes an attachment row, and its file once the delete has committed.
   *
   * <p>Only files at an attachment address are removed here. A file in the old
   * records/{id}/attachments/pages layout stays where it has always stayed until then — under its
   * record, removed with it — because two old rows can name the same file.
   */
  private void deleteAttachment(Long attachmentId) {
    String path = attachmentRepository.findById(attachmentId).map(Attachment::getPath).orElse(null);
    attachmentRepository.deleteById(attachmentId);
    if (isAttachmentAddressed(path)) {
      deleteFilesAfterCommit(java.util.List.of(path));
    }
  }

  private static boolean isAttachmentAddressed(String path) {
    return path != null && path.startsWith("attachments/");
  }

  /**
   * Removes stored files after the surrounding transaction commits, so a rollback never leaves a
   * row pointing at a file that has gone.
   */
  private void deleteFilesAfterCommit(java.util.List<String> paths) {
    if (paths.isEmpty()) {
      return;
    }
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive()) {
      paths.forEach(storageService::deleteStoredFile);
      return;
    }
    org.springframework.transaction.support.TransactionSynchronizationManager
        .registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override
              public void afterCommit() {
                paths.forEach(storageService::deleteStoredFile);
              }
            });
  }

  /**
   * Moves every page from {@code fromSeq} upward by {@code delta}.
   *
   * <p>In two steps through a high offset: (record_id, seq) is unique and not deferrable, so
   * shifting in place collides with the row being moved into.
   */
  private void shiftSeq(Long recordId, int fromSeq, int delta) {
    PageSequence.shift(jdbcTemplate, recordId, fromSeq, delta);
  }

  private Record refreshCounts(Long recordId) {
    Record record = recordRepository.findById(recordId).orElseThrow();
    record.setPageCount(pageRepository.countByRecordId(recordId));
    record.setAttachmentCount(attachmentRepository.findByRecordId(recordId).size());
    record.setUpdatedAt(Instant.now());
    return recordRepository.save(record);
  }

  /**
   * Wipes every existing page (and their derived OCR/translation/embedding data) plus the built PDF
   * for a record, and resets its status to "ingesting" -- while keeping the record's own id, source
   * identity, and catalogue metadata untouched. Intended for a full re-scrape of a record whose
   * scan images need to be regenerated (e.g. after fixing a scraper bug), driven by the caller
   * re-running addPage/addPdf/completeIngest for every page exactly as it would for a fresh ingest.
   */
  @Transactional
  public Record replaceAllPages(Long recordId) {
    Record record =
        recordRepository
            .findById(recordId)
            .orElseThrow(() -> new IllegalArgumentException("Record not found: " + recordId));

    // Break circular FK: record -> attachment (pdf), same precaution as deleteRecord().
    if (record.getPdfAttachmentId() != null) {
      record.setPdfAttachmentId(null);
      recordRepository.save(record);
    }

    // Deleting attachments cascades: attachment -> page -> page_text/job/entity_hit/evidence/
    // text_chunk. This removes every page and everything derived from it, plus the old PDF.
    for (Attachment attachment : attachmentRepository.findByRecordId(recordId)) {
      deleteAttachment(attachment.getId());
    }

    record = recordRepository.findById(recordId).orElseThrow();
    record.setStatus("ingesting");
    record.setPageCount(0);
    record.setAttachmentCount(0);
    record.setUpdatedAt(Instant.now());
    record = recordRepository.save(record);

    jdbcTemplate.update(
        "INSERT INTO pipeline_event (record_id, stage, event, detail, created_at) VALUES (?, 'ingest', 'replace_started', 'all pages wiped for full re-scrape', now())",
        recordId);
    recordEventService.recordChanged(recordId, "status");
    return record;
  }

  /**
   * Reopens a completed/processed record for page repair. Resets status to ingesting, removes stale
   * PDF attachment, and returns existing page sequence numbers so the caller knows which pages are
   * already present.
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
   * Marks the record as ingested and delegates to the state machine for next steps (OCR enqueuing,
   * text-pdf skip, metadata-only skip, etc).
   */
  @Transactional
  public Record completeIngest(Long recordId) {
    Record record =
        recordRepository
            .findById(recordId)
            .orElseThrow(() -> new IllegalArgumentException("Record not found: " + recordId));

    jdbcTemplate.update(
        "INSERT INTO pipeline_event (record_id, stage, event, created_at) VALUES (?, 'ingest', 'completed', now())",
        recordId);

    recordEventService.recordChanged(recordId, "completed");
    stateMachine.autoAdvance(recordId);

    return recordRepository.findById(recordId).orElse(record);
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

    // Delete files on disk. Page images no longer live under records/{id}/, so the record's own
    // rows name the rest; they are removed once the delete has committed.
    deleteFilesAfterCommit(
        attachmentRepository.findByRecordId(recordId).stream()
            .map(Attachment::getPath)
            .filter(IngestService::isAttachmentAddressed)
            .toList());
    // Export files hang off the record by a cascading foreign key, so the rows go with it; their
    // files live under exports/, which deleting the record's own directory does not reach.
    deleteFilesAfterCommit(pdfExportQueue.pathsForRecord(recordId));
    storageService.deleteRecordFiles(recordId);

    // Delete record — pages, attachments, jobs etc. cascade via ON DELETE CASCADE
    recordRepository.delete(record);

    recordEventService.recordChanged(recordId, "deleted");
  }

  private static final int MAX_TEXT_PDF_PAGES = 500;
  private static final long MAX_TEXT_PDF_BYTES = 100 * 1024 * 1024; // 100 MB
  private static final float RENDER_DPI = 300;

  /**
   * Ingests a text-based PDF: renders each page to an image, extracts embedded text via PDFBox, and
   * stores both. Because page_text rows are pre-populated, OCR will be skipped at complete time.
   */
  public int addTextPdf(Long recordId, byte[] pdfBytes) {
    if (pdfBytes.length > MAX_TEXT_PDF_BYTES) {
      throw new IllegalArgumentException(
          "PDF too large: " + (pdfBytes.length / 1024 / 1024) + " MB (max 100 MB)");
    }

    Record record =
        recordRepository
            .findById(recordId)
            .orElseThrow(() -> new IllegalArgumentException("Record not found: " + recordId));

    int pageCount;
    try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
      pageCount = doc.getNumberOfPages();
      if (pageCount > MAX_TEXT_PDF_PAGES) {
        throw new IllegalArgumentException(
            "PDF has " + pageCount + " pages (max " + MAX_TEXT_PDF_PAGES + ")");
      }

      PDFRenderer renderer = new PDFRenderer(doc);
      PDFTextStripper stripper = new PDFTextStripper();

      for (int i = 0; i < pageCount; i++) {
        int seq = i + 1;

        // Render page to JPEG — free the BufferedImage promptly to limit memory
        byte[] imageBytes;
        int imgW, imgH;
        {
          BufferedImage image = renderer.renderImageWithDPI(i, RENDER_DPI);
          imgW = image.getWidth();
          imgH = image.getHeight();
          ByteArrayOutputStream baos = new ByteArrayOutputStream();
          ImageIO.write(image, "JPEG", baos);
          imageBytes = baos.toByteArray();
          image.flush();
        }

        // Store image
        String sha = sha256(imageBytes);
        String path = storageService.storePageImage(imageBytes);

        Attachment attachment = new Attachment();
        attachment.setRecordId(recordId);
        attachment.setRole("page_image");
        attachment.setPath(path);
        attachment.setSha256(sha);
        attachment.setMime("image/jpeg");
        attachment.setBytes((long) imageBytes.length);
        attachment.setCreatedAt(Instant.now());
        attachment = attachmentRepository.save(attachment);

        Page page = new Page();
        page.setRecordId(recordId);
        page.setSeq(seq);
        page.setAttachmentId(attachment.getId());
        page.setWidth(imgW);
        page.setHeight(imgH);
        page = pageRepository.save(page);

        // Extract text from this page
        stripper.setStartPage(seq);
        stripper.setEndPage(seq);
        String text = stripper.getText(doc).strip();

        if (!text.isEmpty()) {
          // Replace rather than append. page_text is UNIQUE on page_id, and the V26 trigger
          // copies the outgoing transcription to page_ocr_history, so the record that a
          // previous engine ran this page survives even though its text does not.
          pageTextRepository.deleteByPageId(page.getId());

          PageText pt = new PageText();
          pt.setPageId(page.getId());
          pt.setEngine("pdfbox");
          pt.setConfidence(1.0f);
          pt.setTextRaw(text);
          pt.setCreatedAt(Instant.now());
          pageTextRepository.save(pt);
        }

        log.info(
            "Text-PDF page {}/{} for record {} — {} chars",
            seq,
            pageCount,
            recordId,
            text.length());
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to process text PDF for record " + recordId, e);
    }

    // Store original PDF as attachment (outside PDDocument try-with-resources to free it first)
    String pdfPath = storageService.storePdf(recordId, pdfBytes);
    String pdfSha = sha256(pdfBytes);
    Attachment pdfAttachment = new Attachment();
    pdfAttachment.setRecordId(recordId);
    pdfAttachment.setRole("original_pdf");
    pdfAttachment.setPath(pdfPath);
    pdfAttachment.setSha256(pdfSha);
    pdfAttachment.setMime("application/pdf");
    pdfAttachment.setBytes((long) pdfBytes.length);
    pdfAttachment.setCreatedAt(Instant.now());
    pdfAttachment = attachmentRepository.save(pdfAttachment);

    record.setPdfAttachmentId(pdfAttachment.getId());
    record.setPageCount(pageCount);
    record.setAttachmentCount(pageCount + 1);
    record.setUpdatedAt(Instant.now());
    recordRepository.save(record);

    recordEventService.recordChanged(recordId, "updated");
    return pageCount;
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
