package place.icomb.archiver.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.model.Job;
import place.icomb.archiver.repository.AttachmentRepository;

/**
 * Internal worker that builds a record's stored searchable PDF.
 *
 * <p>Replaces the Python {@code pdf-worker}, which drew its invisible text layer by spreading every
 * line evenly down the page at the left margin — the layer existed but pointed nowhere, so
 * selecting text in a stored PDF returned the wrong words, and its Helvetica base font could not
 * encode the Czech diacritics on 16,711 pages at all. The correct positioning already lived in
 * {@link PdfExportService} for the on-the-fly exports; porting it to Python would have meant
 * maintaining the coordinate mapping and an embedded font twice. One implementation now serves
 * both, and the stored PDF is byte-for-byte what the "original" export produces.
 *
 * <p>Instances are created by {@link place.icomb.archiver.config.WorkerSchedulingConfig}.
 */
public class SearchablePdfWorker extends GenericWorker {

  private static final Logger log = LoggerFactory.getLogger(SearchablePdfWorker.class);
  private static final String JOB_KIND = "build_searchable_pdf";

  private final PdfExportService pdfExportService;
  private final StorageService storageService;
  private final AttachmentRepository attachmentRepository;

  public SearchablePdfWorker(
      String workerId,
      JobService jobService,
      JobEventService jobEventService,
      PdfExportService pdfExportService,
      StorageService storageService,
      AttachmentRepository attachmentRepository) {
    super(jobService, jobEventService, JOB_KIND, workerId);
    this.pdfExportService = pdfExportService;
    this.storageService = storageService;
    this.attachmentRepository = attachmentRepository;
  }

  @Override
  protected Logger log() {
    return log;
  }

  @Override
  protected void processJob(Job job) throws Exception {
    Long recordId = job.getRecordId();
    if (recordId == null) {
      throw new IllegalStateException("build_searchable_pdf job " + job.getId() + " has no record");
    }

    // Built to a temporary file rather than the heap: a 942-page record of full-resolution scans
    // is far too large to assemble in memory, and several workers run at once.
    Path tmp = Files.createTempFile("searchable-", ".pdf");
    try {
      int pages = pdfExportService.buildRecordPdfToFile(recordId, tmp);
      long bytes = Files.size(tmp);

      String path;
      try (var in = Files.newInputStream(tmp)) {
        path = storageService.storeDerivStream(recordId, "pdf", "searchable.pdf", in);
      }

      // Rebuilds overwrite the same storage path, so reuse the row rather than adding one per
      // rebuild: a new row each time would orphan record.pdf_attachment_id and leave 3,127
      // records pointing at whichever insert happened to be last.
      Attachment attachment =
          attachmentRepository
              .findFirstByRecordIdAndRoleOrderByIdDesc(recordId, "searchable_pdf")
              .orElseGet(Attachment::new);
      attachment.setRecordId(recordId);
      attachment.setRole("searchable_pdf");
      attachment.setPath(path);
      attachment.setMime("application/pdf");
      attachment.setBytes(bytes);
      if (attachment.getCreatedAt() == null) {
        attachment.setCreatedAt(Instant.now());
      }
      attachmentRepository.save(attachment);

      log.info("Built searchable PDF for record {}: {} pages, {} bytes", recordId, pages, bytes);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
