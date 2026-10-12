package place.icomb.archiver.controller;

import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.model.Page;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.repository.PageRepository;
import place.icomb.archiver.service.StorageService;

/**
 * The bytes behind the MCP tools: a finished PDF and a page's original scan.
 *
 * <p>An MCP tool result is text, so {@code export_pdf} and {@code get_page} hand back an address.
 * These routes sit under {@code /api/mcp/**}, which the MCP resource-server chain guards with the
 * caller's per-user OAuth token, so whoever can use the tools can fetch what they name and nobody
 * else can. They only read; nothing here widens a bearer token's reach.
 */
@RestController
@RequestMapping("/api/mcp")
public class McpFileController {

  private final PdfExportController exports;
  private final PageRepository pageRepository;
  private final AttachmentRepository attachmentRepository;
  private final StorageService storageService;

  public McpFileController(
      PdfExportController exports,
      PageRepository pageRepository,
      AttachmentRepository attachmentRepository,
      StorageService storageService) {
    this.exports = exports;
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.storageService = storageService;
  }

  @GetMapping("/pdf-exports/{id}/file")
  public ResponseEntity<?> pdf(@PathVariable String id) {
    return exports.file(id);
  }

  /** The scan exactly as stored: no resize, no re-encode. */
  @GetMapping("/pages/{pageId}/image")
  public ResponseEntity<?> pageImage(@PathVariable Long pageId) {
    Page page = pageRepository.findById(pageId).orElse(null);
    Attachment attachment =
        page == null || page.getAttachmentId() == null
            ? null
            : attachmentRepository.findById(page.getAttachmentId()).orElse(null);
    if (attachment == null) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", "No image for page " + pageId));
    }
    Resource resource = storageService.streamFile(attachment);
    return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(resource);
  }
}
