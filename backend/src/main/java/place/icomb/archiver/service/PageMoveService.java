package place.icomb.archiver.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import place.icomb.archiver.service.PageMoveException.Kind;

/**
 * Moves pages between records without running any of the pipeline again.
 *
 * <p>A page's scan lives at an address that says nothing about the record it is in, so a move is a
 * database statement: the page, its attachment, its chunks and its finished jobs change record;
 * everything keyed on the page id — text, translation, search row, OCR history, person matches —
 * follows because the page keeps its id.
 */
@Service
public class PageMoveService {

  private final JdbcTemplate jdbc;
  private final RecordEventService recordEventService;

  public PageMoveService(JdbcTemplate jdbc, RecordEventService recordEventService) {
    this.jdbc = jdbc;
    this.recordEventService = recordEventService;
  }

  /** What a move did. */
  public record Moved(
      List<Long> pageIds,
      long sourceRecordId,
      int sourcePageCount,
      long targetRecordId,
      int targetPageCount) {}

  /** The columns of a record that decide whether pages may move in or out of it. */
  private record Locked(long id, long archiveId, String status, boolean held) {}

  /**
   * Moves one page to another record.
   *
   * @param atSeq where it lands in the target (1..pages+1); null appends it
   */
  @Transactional
  public Moved movePage(
      long sourceRecordId,
      long pageId,
      long targetRecordId,
      Integer atSeq,
      boolean allowCrossArchive) {
    lockRecords(sourceRecordId, targetRecordId);
    Integer seq =
        jdbc
            .queryForList(
                "SELECT seq FROM page WHERE id = ? AND record_id = ?",
                Integer.class,
                pageId,
                sourceRecordId)
            .stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new PageMoveException(
                        Kind.NOT_FOUND,
                        "Page %d is not in record %d".formatted(pageId, sourceRecordId)));
    int targetPages = pageCount(targetRecordId);
    int at = atSeq == null ? targetPages + 1 : atSeq;
    if (at < 1 || at > targetPages + 1) {
      throw new PageMoveException(
          Kind.BAD_REQUEST,
          "seq %d is outside 1..%d for record %d".formatted(at, targetPages + 1, targetRecordId));
    }
    return moveRun(sourceRecordId, seq, seq, targetRecordId, at);
  }

  /**
   * Moves the run of pages {@code fromSeq..toSeq} of {@code src} into {@code tgt} starting at
   * {@code atSeq}, closing the gap it leaves. Callers have already locked both records.
   */
  private Moved moveRun(long src, int fromSeq, int toSeq, long tgt, int atSeq) {
    int n = toSeq - fromSeq + 1;
    int lastAt = atSeq + n - 1;
    List<Long> ids =
        jdbc.queryForList(
            "SELECT id FROM page WHERE record_id = ? AND seq BETWEEN ? AND ? ORDER BY seq",
            Long.class,
            src,
            fromSeq,
            toSeq);

    // Open a gap in the target, drop the run into it, then close the gap it left in the source.
    PageSequence.shift(jdbc, tgt, atSeq, n);
    jdbc.update(
        "UPDATE page SET record_id = ?, seq = seq - ? + ? WHERE record_id = ? AND seq BETWEEN ? AND ?",
        tgt,
        fromSeq,
        atSeq,
        src,
        fromSeq,
        toSeq);

    // The pages now sit at atSeq..lastAt in the target. Their attachments must follow, or deleting
    // the source record would cascade away the scans and, through them, the pages.
    jdbc.update(
        "UPDATE attachment SET record_id = ? WHERE id IN"
            + " (SELECT attachment_id FROM page WHERE record_id = ? AND seq BETWEEN ? AND ?)",
        tgt,
        tgt,
        atSeq,
        lastAt);
    String moved = "(SELECT id FROM page WHERE record_id = ? AND seq BETWEEN ? AND ?)";
    jdbc.update(
        "UPDATE text_chunk SET record_id = ? WHERE page_id IN " + moved, tgt, tgt, atSeq, lastAt);
    jdbc.update("UPDATE job SET record_id = ? WHERE page_id IN " + moved, tgt, tgt, atSeq, lastAt);

    PageSequence.shift(jdbc, src, toSeq + 1, -n);

    refreshCounts(src);
    refreshCounts(tgt);
    logMove(src, "moved out %s to record %d".formatted(ids, tgt));
    logMove(tgt, "received %s from record %d at seq %d".formatted(ids, src, atSeq));
    recordEventService.recordChanged(src, "updated");
    recordEventService.recordChanged(tgt, "updated");

    return new Moved(ids, src, pageCount(src), tgt, pageCount(tgt));
  }

  /**
   * Locks both records in id order, so two moves crossing in opposite directions cannot deadlock.
   */
  private Map<Long, Locked> lockRecords(long a, long b) {
    Map<Long, Locked> found = new HashMap<>();
    jdbc.query(
        "SELECT id, archive_id, status, ai_held_at IS NOT NULL AS held FROM record"
            + " WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
        rs -> {
          found.put(
              rs.getLong("id"),
              new Locked(
                  rs.getLong("id"),
                  rs.getLong("archive_id"),
                  rs.getString("status"),
                  rs.getBoolean("held")));
        },
        a,
        b);
    for (long id : new long[] {a, b}) {
      if (!found.containsKey(id)) {
        throw new PageMoveException(Kind.NOT_FOUND, "Record %d not found".formatted(id));
      }
    }
    return found;
  }

  private int pageCount(long recordId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM page WHERE record_id = ?", Integer.class, recordId);
    return n == null ? 0 : n;
  }

  private void refreshCounts(long recordId) {
    jdbc.update(
        """
        UPDATE record SET
          page_count = (SELECT count(*) FROM page WHERE record_id = record.id),
          attachment_count = (SELECT count(*) FROM attachment WHERE record_id = record.id),
          updated_at = now()
        WHERE id = ?
        """,
        recordId);
  }

  private void logMove(long recordId, String detail) {
    jdbc.update(
        "INSERT INTO pipeline_event (record_id, stage, event, detail, created_at)"
            + " VALUES (?, 'page_move', 'pages_moved', ?, now())",
        recordId,
        detail);
  }
}
