package place.icomb.archiver.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.repository.RecordRepository;
import place.icomb.archiver.service.PdfExportQueue;
import place.icomb.archiver.service.PdfExportService.Variant;
import place.icomb.archiver.service.StorageService;

/**
 * Every PDF the archive makes: ask for it, wait, download it.
 *
 * <p>There is one path whatever the size. A whole 942-page record and a three-page extract are both
 * requested here, built by a worker, and fetched when ready, so nothing holds a request open or a
 * document in memory while it is assembled.
 */
@RestController
@RequestMapping("/api")
public class PdfExportController {

  private final PdfExportQueue queue;
  private final RecordRepository recordRepository;
  private final StorageService storageService;

  public PdfExportController(
      PdfExportQueue queue, RecordRepository recordRepository, StorageService storageService) {
    this.queue = queue;
    this.recordRepository = recordRepository;
    this.storageService = storageService;
  }

  @PostMapping("/records/{recordId}/pdf-exports")
  public ResponseEntity<Map<String, Object>> create(
      @PathVariable long recordId, @RequestBody(required = false) Map<String, Object> body) {
    if (!recordRepository.existsById(recordId)) {
      return error(HttpStatus.NOT_FOUND, "Record %d not found".formatted(recordId));
    }
    Map<String, Object> in = body == null ? Map.of() : body;

    Variant variant;
    String pages;
    try {
      variant = PdfExportQueue.parseVariant(text(in, "variant"));
      pages = text(in, "pages");
    } catch (IllegalArgumentException e) {
      return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    List<Long> pageIds;
    try {
      pageIds = queue.pageIdsFor(recordId, pages);
    } catch (IllegalArgumentException e) {
      return error(HttpStatus.BAD_REQUEST, "Malformed page range: " + pages);
    }
    if (pageIds.isEmpty()) {
      return error(HttpStatus.BAD_REQUEST, "No page matches that selection");
    }

    PdfExportQueue.Requested requested = queue.request(recordId, variant, pageIds);
    boolean reusedFinished = !requested.created() && "ready".equals(requested.view().state());
    return ResponseEntity.status(reusedFinished ? HttpStatus.OK : HttpStatus.ACCEPTED)
        .body(describe(requested.view()));
  }

  @GetMapping("/pdf-exports/{id}")
  public ResponseEntity<Map<String, Object>> status(@PathVariable String id) {
    return queue
        .find(id)
        .map(view -> ResponseEntity.ok(describe(view)))
        .orElseGet(() -> error(HttpStatus.NOT_FOUND, "No such export"));
  }

  @GetMapping("/pdf-exports/{id}/file")
  public ResponseEntity<?> file(@PathVariable String id) {
    Optional<PdfExportQueue.Row> found = queue.row(id);
    if (found.isEmpty()) {
      return error(HttpStatus.NOT_FOUND, "No such export");
    }
    PdfExportQueue.Row row = found.get();
    return switch (row.state()) {
      case "ready" -> {
        Path file = storageService.exportFile(row.path());
        if (!Files.exists(file)) {
          yield error(HttpStatus.GONE, "The file is no longer available; request the PDF again");
        }
        String name =
            "record-" + row.recordId() + PdfExportQueue.fileSuffix(row.variant()) + ".pdf";
        yield ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
            .contentLength(file.toFile().length())
            .body(new FileSystemResource(file));
      }
      case "expired" -> error(HttpStatus.GONE, "This PDF has expired; request it again");
      case "failed" ->
          error(
              HttpStatus.CONFLICT,
              row.error() == null ? "The PDF could not be built" : row.error());
      default -> error(HttpStatus.CONFLICT, "The PDF is still being prepared");
    };
  }

  private static Map<String, Object> describe(PdfExportQueue.View v) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", v.id());
    out.put("state", v.state());
    out.put("pageCount", v.pageCount());
    out.put("variant", v.variant());
    out.put("bytes", v.bytes());
    out.put("expiresAt", v.expiresAt() == null ? null : v.expiresAt().toString());
    out.put("error", v.error());
    return out;
  }

  private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", String.valueOf(message)));
  }

  private static String text(Map<String, Object> body, String key) {
    Object v = body.get(key);
    if (v == null) {
      return null;
    }
    if (!(v instanceof String s)) {
      throw new IllegalArgumentException(key + " must be text");
    }
    return s;
  }
}
