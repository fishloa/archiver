package place.icomb.archiver.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.service.SearchablePdfPurgeService;
import place.icomb.archiver.service.StorageMigrationService;

/**
 * Storage housekeeping, a batch at a time: moving page images to attachment addresses, and removing
 * the retired stored PDFs. Admin only, via /api/admin.
 */
@RestController
@RequestMapping("/api/admin/storage")
public class AdminStorageController {

  private static final int DEFAULT_LIMIT = 500;
  private static final int MAX_LIMIT = 5_000;

  private final StorageMigrationService migration;
  private final SearchablePdfPurgeService pdfPurge;

  public AdminStorageController(
      StorageMigrationService migration, SearchablePdfPurgeService pdfPurge) {
    this.migration = migration;
    this.pdfPurge = pdfPurge;
  }

  @GetMapping("/searchable-pdfs")
  public SearchablePdfPurgeService.Status searchablePdfStatus() {
    return pdfPurge.status();
  }

  /** Deletes up to {@code limit} stored searchable PDFs of the retired pipeline stage. */
  @PostMapping("/purge-searchable-pdfs")
  public ResponseEntity<?> purgeSearchablePdfs(
      @RequestBody(required = false) Map<String, Object> body) {
    return limited(body)
        .map(l -> ResponseEntity.ok((Object) pdfPurge.purge(l)))
        .orElseGet(
            () ->
                ResponseEntity.badRequest()
                    .body(Map.of("error", "limit must be a number from 1 to " + MAX_LIMIT)));
  }

  @GetMapping("/migrate")
  public StorageMigrationService.Status status() {
    return migration.status();
  }

  @PostMapping("/migrate")
  public ResponseEntity<?> migrate(@RequestBody(required = false) Map<String, Object> body) {
    return limited(body)
        .map(l -> ResponseEntity.ok((Object) migration.migrate(l)))
        .orElseGet(
            () ->
                ResponseEntity.badRequest()
                    .body(Map.of("error", "limit must be a number from 1 to " + MAX_LIMIT)));
  }

  /** The batch size: default when absent, empty when out of range or not a number. */
  private static java.util.Optional<Integer> limited(Map<String, Object> body) {
    if (body == null || body.get("limit") == null) {
      return java.util.Optional.of(DEFAULT_LIMIT);
    }
    if (!(body.get("limit") instanceof Number n) || n.intValue() < 1 || n.intValue() > MAX_LIMIT) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(n.intValue());
  }
}
