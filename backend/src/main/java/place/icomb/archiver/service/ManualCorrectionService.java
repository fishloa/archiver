package place.icomb.archiver.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrects a stored transcription, translation or title by hand, and records that it was done.
 *
 * <p>A correction names the exact text to change ({@code find}) and what to put there ({@code
 * replace}); {@code find} must occur exactly once in the field, so a correction can never touch
 * more than the caller meant to. The old and new text, the reason and the administrator are kept in
 * {@code manual_correction}. Nothing here re-runs the pipeline.
 */
@Service
public class ManualCorrectionService {

  public enum Kind {
    NOT_FOUND,
    BAD_REQUEST,
    CONFLICT
  }

  public static class CorrectionException extends RuntimeException {
    private final Kind kind;

    public CorrectionException(Kind kind, String message) {
      super(message);
      this.kind = kind;
    }

    public Kind kind() {
      return kind;
    }
  }

  private static final Set<String> PAGE_FIELDS = Set.of("text_raw", "text_en");
  private static final Set<String> RECORD_FIELDS =
      Set.of("title", "description", "title_en", "description_en");

  private final JdbcTemplate jdbc;
  private final RecordEventService events;

  public ManualCorrectionService(JdbcTemplate jdbc, RecordEventService events) {
    this.jdbc = jdbc;
    this.events = events;
  }

  /**
   * Corrects {@code text_raw} or {@code text_en} of one page: one exact passage, or with {@code
   * whole} the entire field (a transcription made by hand where the engines failed). Whole is
   * explicit, never inferred from a missing {@code find}: a request that forgot its target must not
   * overwrite a page.
   */
  @Transactional
  public Map<String, Object> correctPage(
      long pageId, String field, String find, String replace, String reason, boolean whole) {
    require(field != null && PAGE_FIELDS.contains(field), "field must be one of " + PAGE_FIELDS);
    if (whole) {
      require(find == null, "give either find or replaceAll, not both");
    } else {
      require(find != null, "find is required");
    }
    checkEdit(find, replace, reason);

    List<Map<String, Object>> page =
        jdbc.queryForList("SELECT id, record_id FROM page WHERE id = ? FOR UPDATE", pageId);
    if (page.isEmpty()) {
      throw new CorrectionException(Kind.NOT_FOUND, "Page %d not found".formatted(pageId));
    }
    long recordId = ((Number) page.get(0).get("record_id")).longValue();

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT id, text_raw, text_en FROM page_text WHERE page_id = ? ORDER BY id DESC",
            pageId);
    if (rows.isEmpty()) {
      throw new CorrectionException(
          Kind.NOT_FOUND, "Page %d has no transcription to correct".formatted(pageId));
    }
    // page_text holds the current transcription: one row per page, newest if ever more.
    Map<String, Object> row = rows.get(0);
    long textId = ((Number) row.get("id")).longValue();
    String current = (String) row.get(field);
    if (whole && current != null && current.equals(replace)) {
      throw new CorrectionException(Kind.BAD_REQUEST, "the text is already exactly that");
    }
    String updated = whole ? replace : replaceOnce(current, find, replace, field);

    jdbc.update("UPDATE page_text SET " + field + " = ? WHERE id = ?", updated, textId);

    if (field.equals("text_raw")) {
      if (whole) {
        // Nothing of the old chunks can be patched; they are rebuilt by re-embedding the record.
        jdbc.update("DELETE FROM text_chunk WHERE page_id = ?", pageId);
      } else {
        // The chunks are made from the original text; keep their copy of the passage in step.
        jdbc.update(
            "UPDATE text_chunk SET content = replace(content, ?, ?)"
                + " WHERE page_id = ? AND position(? in content) > 0",
            find,
            replace,
            pageId,
            find);
      }
    } else if (whole) {
      jdbc.update("UPDATE page_translation SET text_en = ? WHERE page_id = ?", replace, pageId);
    } else {
      // Every model's translation of the page, so the cache cannot be refilled from a stale one.
      jdbc.update(
          "UPDATE page_translation SET text_en = replace(text_en, ?, ?)"
              + " WHERE page_id = ? AND position(? in text_en) > 0",
          find,
          replace,
          pageId,
          find);
    }

    long id = audit(recordId, pageId, field, whole ? current : find, replace, reason);
    events.recordChanged(recordId, "text");
    return Map.of(
        "correctionId", id,
        "recordId", recordId,
        "pageId", pageId,
        "field", field,
        "applied", true,
        "whole", whole);
  }

  /** Corrects {@code title}, {@code description}, {@code title_en} or {@code description_en}. */
  @Transactional
  public Map<String, Object> correctRecord(
      long recordId, String field, String find, String replace, String reason) {
    require(
        field != null && RECORD_FIELDS.contains(field), "field must be one of " + RECORD_FIELDS);
    checkEdit(find, replace, reason);

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT " + field + " AS v FROM record WHERE id = ? FOR UPDATE", recordId);
    if (rows.isEmpty()) {
      throw new CorrectionException(Kind.NOT_FOUND, "Record %d not found".formatted(recordId));
    }
    String current = (String) rows.get(0).get("v");
    String updated = replaceOnce(current, find, replace, field);

    jdbc.update(
        "UPDATE record SET " + field + " = ?, updated_at = now() WHERE id = ?", updated, recordId);
    long id = audit(recordId, null, field, find, replace, reason);
    events.recordChanged(recordId, "metadata");
    return Map.of("correctionId", id, "recordId", recordId, "field", field, "applied", true);
  }

  /** What has been corrected by hand on a record, newest first. */
  public List<Map<String, Object>> list(long recordId) {
    return jdbc.queryForList(
        "SELECT id, record_id AS \"recordId\", page_id AS \"pageId\", field,"
            + " old_text AS \"oldText\", new_text AS \"newText\", reason,"
            + " corrected_by AS \"correctedBy\", corrected_at AS \"correctedAt\""
            + " FROM manual_correction WHERE record_id = ? ORDER BY id DESC",
        recordId);
  }

  private long audit(
      long recordId, Long pageId, String field, String find, String replace, String reason) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    String who = auth != null ? auth.getName() : "unknown";
    return jdbc.queryForObject(
        "INSERT INTO manual_correction"
            + " (record_id, page_id, field, old_text, new_text, reason, corrected_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
        Long.class,
        recordId,
        pageId,
        field,
        find,
        replace,
        reason,
        who);
  }

  private static void checkEdit(String find, String replace, String reason) {
    require(find == null || !find.isEmpty(), "find must not be empty");
    require(replace != null && !replace.isBlank(), "replace is required");
    require(find == null || !find.equals(replace), "find and replace are identical");
    require(reason != null && !reason.isBlank(), "reason is required");
  }

  private static String replaceOnce(String current, String find, String replace, String field) {
    if (current == null || current.isEmpty()) {
      throw new CorrectionException(Kind.CONFLICT, "%s is empty".formatted(field));
    }
    int first = current.indexOf(find);
    if (first < 0) {
      throw new CorrectionException(Kind.CONFLICT, "find text not present in " + field);
    }
    if (current.indexOf(find, first + find.length()) >= 0) {
      throw new CorrectionException(
          Kind.CONFLICT, "find text occurs more than once in %s; make it longer".formatted(field));
    }
    return current.substring(0, first) + replace + current.substring(first + find.length());
  }

  private static void require(boolean ok, String message) {
    if (!ok) {
      throw new CorrectionException(Kind.BAD_REQUEST, message);
    }
  }
}
