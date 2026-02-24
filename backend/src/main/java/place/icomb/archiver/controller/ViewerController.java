package place.icomb.archiver.controller;

import place.icomb.archiver.dto.PageResponse;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.model.Page;
import place.icomb.archiver.model.Record;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.repository.PageRepository;
import place.icomb.archiver.repository.RecordRepository;
import place.icomb.archiver.service.StorageService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ViewerController {

  private final PageRepository pageRepository;
  private final AttachmentRepository attachmentRepository;
  private final RecordRepository recordRepository;
  private final StorageService storageService;

  public ViewerController(
      PageRepository pageRepository,
      AttachmentRepository attachmentRepository,
      RecordRepository recordRepository,
      StorageService storageService) {
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.recordRepository = recordRepository;
    this.storageService = storageService;
  }

  @GetMapping("/pages/{pageId}")
  public ResponseEntity<PageResponse> getPage(@PathVariable Long pageId) {
    return pageRepository
        .findById(pageId)
        .map(
            p ->
                ResponseEntity.ok(
                    new PageResponse(
                        p.getId(),
                        p.getRecordId(),
                        p.getSeq(),
                        p.getAttachmentId(),
                        p.getPageLabel(),
                        p.getWidth(),
                        p.getHeight())))
        .orElse(ResponseEntity.notFound().build());
  }

  @GetMapping("/files/{attachmentId}")
  public ResponseEntity<Resource> streamFile(@PathVariable Long attachmentId) {
    Attachment attachment =
        attachmentRepository
            .findById(attachmentId)
            .orElse(null);
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
    // Stub: In a full implementation, this would serve a pre-generated thumbnail.
    // For now, serve the original file (useful for page images that are already small).
    return streamFile(attachmentId);
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
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"record-" + recordId + ".pdf\"")
        .body(resource);
  }
}
