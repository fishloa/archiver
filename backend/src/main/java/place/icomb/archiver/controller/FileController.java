package place.icomb.archiver.controller;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.service.StorageService;
import place.icomb.archiver.service.ThumbnailService;

/**
 * Serving bytes: page scans and thumbnails. PDFs are requested and fetched through {@link
 * PdfExportController}.
 *
 * <p>Split out of ViewerController, which had grown to nineteen endpoints across five unrelated
 * jobs. The two endpoints that remain, file and thumbnail, share every dependency they have —
 * storage, attachments and thumbnails — and touch none of the catalogue queries, pipeline
 * statistics or admin actions that made up the rest of that class. The paths are unchanged.
 */
@RestController
@RequestMapping("/api")
public class FileController {

  private final AttachmentRepository attachmentRepository;
  private final StorageService storageService;
  private final ThumbnailService thumbnailService;

  public FileController(
      AttachmentRepository attachmentRepository,
      StorageService storageService,
      ThumbnailService thumbnailService) {
    this.attachmentRepository = attachmentRepository;
    this.storageService = storageService;
    this.thumbnailService = thumbnailService;
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
}
