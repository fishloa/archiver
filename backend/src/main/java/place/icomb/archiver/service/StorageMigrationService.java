package place.icomb.archiver.service;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Moves page images from the old records/{id}/attachments/pages/p{seq}.jpg layout to their own
 * attachment address.
 *
 * <p>One row at a time and without a transaction around the file work: give the file its new name
 * (a hard link where it can, a verified copy where it cannot), point the row at it, then drop the
 * old name. Every step leaves a row that points at a file that exists, so it can be stopped at any
 * moment and started again. Moving pages between records waits until {@link Status#legacy()} is
 * zero — against a half-migrated store a move would still have to move files.
 */
@Service
public class StorageMigrationService {

  private static final Logger log = LoggerFactory.getLogger(StorageMigrationService.class);

  /** A page image not yet at an attachment address, whose file has not been found missing. */
  private static final String LEGACY =
      "role = 'page_image' AND path NOT LIKE 'attachments/%' AND missing_since IS NULL";

  private final JdbcTemplate jdbc;
  private final StorageService storage;

  public StorageMigrationService(JdbcTemplate jdbc, StorageService storage) {
    this.jdbc = jdbc;
    this.storage = storage;
  }

  /** How far the migration has got. {@code legacy} is the gate on moving pages between records. */
  public record Status(long legacy, long migrated, long missing) {}

  /** What one batch did, and where that leaves the whole store. */
  public record Batch(
      int migrated, int missing, int failed, int changedUnderneath, Status status) {}

  public Status status() {
    return new Status(
        count(LEGACY),
        count("role = 'page_image' AND path LIKE 'attachments/%'"),
        count("missing_since IS NOT NULL"));
  }

  /**
   * Migrates up to {@code limit} rows.
   *
   * <p>Synchronized so two admin calls cannot race for one row; the conditional updates below would
   * survive that race anyway, at the cost of a wasted copy.
   */
  public synchronized Batch migrate(int limit) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT id, path FROM attachment WHERE " + LEGACY + " ORDER BY id LIMIT ?", limit);

    int migrated = 0;
    int missing = 0;
    int failed = 0;
    int changed = 0;
    for (Map<String, Object> row : rows) {
      long id = ((Number) row.get("id")).longValue();
      String oldPath = (String) row.get("path");

      String newPath;
      try {
        newPath = storage.placeAtNewAddress(oldPath);
      } catch (UncheckedIOException e) {
        // Left as it was, so the next batch tries it again; reported so a person can look.
        log.warn(
            "Storage migration could not copy attachment {} at {}: {}", id, oldPath, e.toString());
        failed++;
        continue;
      }

      if (newPath == null) {
        jdbc.update(
            "UPDATE attachment SET missing_since = now() WHERE id = ? AND path = ?", id, oldPath);
        log.warn("Storage migration found no file for attachment {} at {}", id, oldPath);
        missing++;
        continue;
      }

      // Conditional on the path still being the one copied: a page replaced or deleted since the
      // SELECT has a row that is not this one any more, and the copy is simply discarded.
      int updated =
          jdbc.update(
              "UPDATE attachment SET path = ? WHERE id = ? AND path = ?", newPath, id, oldPath);
      if (updated == 0) {
        storage.deleteStoredFile(newPath);
        changed++;
        continue;
      }
      migrated++;

      // Two old rows could name one file — the collision that cost record 4006 its images. The
      // original goes only once nothing points at it.
      Long stillUsed =
          jdbc.queryForObject(
              "SELECT count(*) FROM attachment WHERE path = ?", Long.class, oldPath);
      if (stillUsed == null || stillUsed == 0) {
        storage.deleteStoredFile(oldPath);
      }
    }

    Status status = status();
    log.info(
        "Storage migration batch: migrated={} missing={} failed={} changed={} — {} legacy remain",
        migrated,
        missing,
        failed,
        changed,
        status.legacy());
    return new Batch(migrated, missing, failed, changed, status);
  }

  private long count(String where) {
    Long n = jdbc.queryForObject("SELECT count(*) FROM attachment WHERE " + where, Long.class);
    return n == null ? 0 : n;
  }
}
