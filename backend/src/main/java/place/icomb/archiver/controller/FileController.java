package place.icomb.archiver.controller;

import java.io.IOException;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.model.Record;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.repository.RecordRepository;
import place.icomb.archiver.service.PdfExportService;
import place.icomb.archiver.service.StorageService;
import place.icomb.archiver.service.ThumbnailService;

/**
 * Serving bytes: page scans, thumbnails, and the PDF exports built on demand.
 *
 * <p>Split out of ViewerController, which had grown to nineteen endpoints across five unrelated
 * jobs. These four share every dependency they have — storage, attachments and the PDF builder —
 * and none of them touch the catalogue queries, pipeline statistics or admin actions that made up
 * the rest of that class. The paths are unchanged.
 */
@RestController
@RequestMapping("/api")
public class FileController {

  private final AttachmentRepository attachmentRepository;
  private final RecordRepository recordRepository;
  private final StorageService storageService;
  private final ThumbnailService thumbnailService;
  private final PdfExportService pdfExportService;

  public FileController(
      AttachmentRepository attachmentRepository,
      RecordRepository recordRepository,
      StorageService storageService,
      ThumbnailService thumbnailService,
      PdfExportService pdfExportService) {
    this.attachmentRepository = attachmentRepository;
    this.recordRepository = recordRepository;
    this.storageService = storageService;
    this.thumbnailService = thumbnailService;
    this.pdfExportService = pdfExportService;
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
    Attachment attachment = attachmentRepository.findById(attachmentId).orElse(null);
    if (attachment == null) {
      return ResponseEntity.notFound().build();
    }

    java.nio.file.Path thumb = thumbnailService.thumbnailFor(attachment);
    if (thumb == null) {
      // Not an image, or one ImageIO cannot decode. Serving the original is what every
      // request got before thumbnails existed, so the page still draws.
      return streamFile(attachmentId);
    }

    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_JPEG)
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
        // Immutable: the cache is keyed by attachment id, and a replaced scan gets a new one.
        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
        .body(new org.springframework.core.io.FileSystemResource(thumb));
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
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"record-" + recordId + ".pdf\"")
        .body(resource);
  }

  @GetMapping("/records/{recordId}/export-pdf")
  public ResponseEntity<Resource> exportPdf(
      @PathVariable Long recordId,
      @RequestParam String pages,
      @RequestParam(defaultValue = "original") String variant) {
    Record record = recordRepository.findById(recordId).orElse(null);
    if (record == null) {
      return ResponseEntity.notFound().build();
    }

    List<Integer> seqNumbers;
    try {
      seqNumbers = pdfExportService.parsePageRange(pages);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().build();
    }

    if (seqNumbers.isEmpty()) {
      return ResponseEntity.badRequest().build();
    }

    try {
      PdfExportService.Variant v =
          switch (variant == null ? "original" : variant.toLowerCase()) {
            case "english" -> PdfExportService.Variant.ENGLISH;
            case "side-by-side", "sidebyside" -> PdfExportService.Variant.SIDE_BY_SIDE;
            default -> PdfExportService.Variant.ORIGINAL;
          };
      byte[] pdfBytes = pdfExportService.buildPdf(recordId, seqNumbers, v);
      ByteArrayResource resource = new ByteArrayResource(pdfBytes);
      // Named for what the file contains, so a folder of exports is readable without opening them.
      String suffix =
          switch (v) {
            case ENGLISH -> "-english";
            case SIDE_BY_SIDE -> "-original-and-english";
            case ORIGINAL -> "-original";
          };
      String filename = "record-" + recordId + suffix + ".pdf";
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_PDF)
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
          .contentLength(pdfBytes.length)
          .body(resource);
    } catch (IOException e) {
      if (e.getMessage() != null && e.getMessage().contains("No valid pages")) {
        return ResponseEntity.notFound().build();
      }
      return ResponseEntity.internalServerError().build();
    }
  }
}
