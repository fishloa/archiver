package place.icomb.archiver.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.service.StorageMigrationService;

/** Moves page images to attachment addresses, a batch at a time. Admin only, via /api/admin. */
@RestController
@RequestMapping("/api/admin/storage")
public class AdminStorageController {

  private static final int DEFAULT_LIMIT = 500;
  private static final int MAX_LIMIT = 5_000;

  private final StorageMigrationService migration;

  public AdminStorageController(StorageMigrationService migration) {
    this.migration = migration;
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
