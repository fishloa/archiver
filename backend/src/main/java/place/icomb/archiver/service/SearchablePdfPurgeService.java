package place.icomb.archiver.service;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes the stored per-record searchable PDFs the retired pipeline stage left behind.
 *
 * <p>Every PDF is built on demand now, so these files are unreferenced. Only the role {@code
 * searchable_pdf} is touched: a born-digital upload's {@code original_pdf} and every page image
 * stay. One row at a time, so it can be stopped and restarted: clear the record's pointer and drop
 * the row in one transaction, then the file, and the file only when no other row names the same
 * path.
 */
@Service
public class SearchablePdfPurgeService {

  private static final Logger log = LoggerFactory.getLogger(SearchablePdfPurgeService.class);

  private final JdbcTemplate jdbc;
  private final StorageService storage;
  private final TransactionTemplate tx;

  public SearchablePdfPurgeService(
      JdbcTemplate jdbc, StorageService storage, PlatformTransactionManager txManager) {
    this.jdbc = jdbc;
    this.storage = storage;
    this.tx = new TransactionTemplate(txManager);
  }

  /** What is still stored: attachment rows of the retired role, their bytes, records pointing. */
  public record Status(long files, long bytes, long recordsPointing) {}

  /** What one batch did, and what is left. */
  public record Batch(
      int purged, int filesRemoved, int sharedPathKept, long bytes, Status status) {}

  public Status status() {
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT count(*) AS n, coalesce(sum(bytes), 0) AS b FROM attachment"
                + " WHERE role = 'searchable_pdf'");
    Long pointing =
        jdbc.queryForObject(
            "SELECT count(*) FROM record WHERE pdf_attachment_id IN"
                + " (SELECT id FROM attachment WHERE role = 'searchable_pdf')",
            Long.class);
    return new Status(
        ((Number) row.get("n")).longValue(),
        ((Number) row.get("b")).longValue(),
        pointing == null ? 0 : pointing);
  }

  public synchronized Batch purge(int limit) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT id, record_id, path, coalesce(bytes, 0) AS bytes FROM attachment"
                + " WHERE role = 'searchable_pdf' ORDER BY id LIMIT ?",
            limit);
    int purged = 0;
    int removed = 0;
    int shared = 0;
    long bytes = 0;
    for (Map<String, Object> r : rows) {
      long id = ((Number) r.get("id")).longValue();
      long recordId = ((Number) r.get("record_id")).longValue();
      String path = (String) r.get("path");
      Boolean dropped =
          tx.execute(
              s -> {
                jdbc.update(
                    "UPDATE record SET pdf_attachment_id = NULL WHERE pdf_attachment_id = ?", id);
                int n =
                    jdbc.update(
                        "DELETE FROM attachment WHERE id = ? AND role = 'searchable_pdf'", id);
                jdbc.update(
                    "UPDATE record SET attachment_count ="
                        + " (SELECT count(*) FROM attachment WHERE record_id = record.id)"
                        + " WHERE id = ?",
                    recordId);
                return n == 1;
              });
      if (!Boolean.TRUE.equals(dropped)) {
        continue;
      }
      purged++;
      bytes += ((Number) r.get("bytes")).longValue();
      Long others =
          jdbc.queryForObject("SELECT count(*) FROM attachment WHERE path = ?", Long.class, path);
      if (path != null && others != null && others == 0) {
        storage.deleteStoredFile(path);
        removed++;
      } else {
        shared++;
        log.info("Kept {}: another attachment row names the same file", path);
      }
    }
    return new Batch(purged, removed, shared, bytes, status());
  }
}
