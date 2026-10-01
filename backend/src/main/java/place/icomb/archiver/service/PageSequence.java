package place.icomb.archiver.service;

import org.springframework.jdbc.core.JdbcTemplate;

/** Renumbering of a record's pages — the one place it is done. */
final class PageSequence {

  private static final int PARKING_SPACE = 1_000_000;

  private PageSequence() {}

  /**
   * Moves every page from {@code fromSeq} upward by {@code delta}.
   *
   * <p>In two steps through a high offset: (record_id, seq) is unique and not deferrable, so
   * shifting in place collides with the row being moved into.
   */
  static void shift(JdbcTemplate jdbc, Long recordId, int fromSeq, int delta) {
    jdbc.update(
        "UPDATE page SET seq = seq + ? WHERE record_id = ? AND seq >= ?",
        PARKING_SPACE,
        recordId,
        fromSeq);
    jdbc.update(
        "UPDATE page SET seq = seq - ? + ? WHERE record_id = ? AND seq >= ?",
        PARKING_SPACE,
        delta,
        recordId,
        PARKING_SPACE);
  }

  /**
   * Gives a record's pages the order {@code pageIds}, which must be exactly its pages.
   *
   * <p>Through the same parking offset as {@link #shift}: no two pages ever hold the same number.
   */
  static void assign(JdbcTemplate jdbc, Long recordId, java.util.List<Long> pageIds) {
    jdbc.update("UPDATE page SET seq = seq + ? WHERE record_id = ?", PARKING_SPACE, recordId);
    for (int i = 0; i < pageIds.size(); i++) {
      jdbc.update(
          "UPDATE page SET seq = ? WHERE id = ? AND record_id = ?",
          i + 1,
          pageIds.get(i),
          recordId);
    }
  }
}
