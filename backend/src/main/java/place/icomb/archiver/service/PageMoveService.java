package place.icomb.archiver.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import place.icomb.archiver.model.Record;
import place.icomb.archiver.repository.RecordRepository;
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
  private final RecordRepository recordRepository;

  public PageMoveService(
      JdbcTemplate jdbc, RecordEventService recordEventService, RecordRepository recordRepository) {
    this.jdbc = jdbc;
    this.recordEventService = recordEventService;
    this.recordRepository = recordRepository;
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
    Map<Long, Locked> locked = lockRecords(sourceRecordId, targetRecordId);
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
    requireMovable(locked, sourceRecordId, targetRecordId, allowCrossArchive, seq, seq);
    int targetPages = pageCount(targetRecordId);
    int at = atSeq == null ? targetPages + 1 : atSeq;
    if (at < 1 || at > targetPages + 1) {
      throw new PageMoveException(
          Kind.BAD_REQUEST,
          "seq %d is outside 1..%d for record %d".formatted(at, targetPages + 1, targetRecordId));
    }
    return moveRun(sourceRecordId, seq, seq, targetRecordId, at);
  }

  /** A split: the record it made, and the move that filled it. */
  public record SplitResult(long newRecordId, Moved moved) {}

  /**
   * Splits a record in two: pages {@code splitAtSeq..end} move to a new record.
   *
   * <p>The new record inherits what a split of one document should — archive, languages, OCR
   * engine, translation quality, reference code — because it is one document recognised as two, not
   * a new acquisition. It is created {@code complete}: its pages already went through the pipeline.
   * Its English title is supplied, never generated, because generating it is an AI call.
   */
  @Transactional
  public SplitResult split(
      long recordId,
      int splitAtSeq,
      String title,
      String description,
      String titleEn,
      String descriptionEn) {
    if (title == null || title.isBlank()) {
      throw new PageMoveException(Kind.BAD_REQUEST, "title is required for the new record");
    }
    // Lock the source before reading anything that decides the range, so a concurrent move or
    // split cannot make the page count or the first moved page stale.
    List<Long> lockedSource =
        jdbc.queryForList("SELECT id FROM record WHERE id = ? FOR UPDATE", Long.class, recordId);
    if (lockedSource.isEmpty()) {
      throw new PageMoveException(Kind.NOT_FOUND, "Record %d not found".formatted(recordId));
    }
    Record source =
        recordRepository
            .findById(recordId)
            .orElseThrow(
                () ->
                    new PageMoveException(
                        Kind.NOT_FOUND, "Record %d not found".formatted(recordId)));
    int pages = pageCount(recordId);
    if (splitAtSeq < 2 || splitAtSeq > pages) {
      throw new PageMoveException(
          Kind.BAD_REQUEST,
          "splitAtSeq must be from 2 to %d, so that both records keep at least one page"
              .formatted(pages));
    }
    long firstMoved =
        jdbc.queryForObject(
            "SELECT id FROM page WHERE record_id = ? AND seq = ?",
            Long.class,
            recordId,
            splitAtSeq);

    Record made = new Record();
    made.setArchiveId(source.getArchiveId());
    made.setSourceSystem(source.getSourceSystem());
    // A page can move back and the source be split at the same seq again, so the id from the
    // first page moved can already be taken; count up until it is free (the source is locked).
    String baseId = source.getSourceRecordId() + "#split-" + firstMoved;
    String sourceRecordId = baseId;
    for (int n = 2;
        jdbc.queryForObject(
                "SELECT count(*) FROM record WHERE source_system = ? AND source_record_id = ?",
                Long.class,
                source.getSourceSystem(),
                sourceRecordId)
            > 0;
        n++) {
      sourceRecordId = baseId + "-" + n;
    }
    made.setSourceRecordId(sourceRecordId);
    made.setTitle(title);
    made.setDescription(description);
    made.setTitleEn(titleEn);
    made.setDescriptionEn(descriptionEn);
    made.setLang(source.getLang());
    made.setMetadataLang(source.getMetadataLang());
    made.setOcrEngine(source.getOcrEngine());
    made.setTranslationQuality(source.getTranslationQuality());
    made.setReferenceCode(source.getReferenceCode());
    made.setStatus("complete");
    made.setCreatedAt(Instant.now());
    made.setUpdatedAt(Instant.now());
    made = recordRepository.save(made);

    Map<Long, Locked> locked = lockRecords(recordId, made.getId());
    requireMovable(locked, recordId, made.getId(), false, splitAtSeq, pages);
    Moved moved = moveRun(recordId, splitAtSeq, pages, made.getId(), 1);
    return new SplitResult(made.getId(), moved);
  }

  /**
   * Refuses a move that would be wrong, before anything is touched.
   *
   * <p>Both records must be complete: a record still in the pipeline re-evaluates its guards
   * whenever one of its jobs finishes, so pages it had gained could be run through paid stages.
   * From {@code complete} there is nowhere to advance to.
   */
  private void requireMovable(
      Map<Long, Locked> locked, long src, long tgt, boolean allowCross, int fromSeq, int toSeq) {
    if (src == tgt) {
      throw new PageMoveException(Kind.BAD_REQUEST, "Source and target are the same record");
    }
    Locked s = locked.get(src);
    Locked t = locked.get(tgt);
    for (Locked r : List.of(s, t)) {
      if (!"complete".equals(r.status())) {
        throw new PageMoveException(
            Kind.CONFLICT,
            "Record %d is %s, not complete; let its pipeline settle before moving pages"
                .formatted(r.id(), r.status()));
      }
      if (r.held()) {
        throw new PageMoveException(Kind.CONFLICT, "Record %d is on AI hold".formatted(r.id()));
      }
    }
    if (s.archiveId() != t.archiveId() && !allowCross) {
      throw new PageMoveException(
          Kind.CONFLICT,
          "Records %d and %d are in different archives; pass allowCrossArchive to move between them"
              .formatted(src, tgt));
    }
    Long busy =
        jdbc.queryForObject(
            "SELECT count(*) FROM job WHERE record_id IN (?, ?) AND status IN ('pending', 'claimed')",
            Long.class,
            src,
            tgt);
    if (busy != null && busy > 0) {
      throw new PageMoveException(
          Kind.CONFLICT,
          "%d job(s) are still pending or running against these records; a page moved now would"
                  .formatted(busy)
              + " have its output written into the record it left");
    }
    Long exporting =
        jdbc.queryForObject(
            "SELECT count(*) FROM pdf_export WHERE record_id IN (?, ?)"
                + " AND state IN ('queued', 'building')",
            Long.class,
            src,
            tgt);
    if (exporting != null && exporting > 0) {
      throw new PageMoveException(
          Kind.CONFLICT,
          "%d PDF export(s) of these records are still being prepared; moving pages now would"
                  .formatted(exporting)
              + " change them underneath the export");
    }
    Long legacy =
        jdbc.queryForObject(
            "SELECT count(*) FROM page p JOIN attachment a ON a.id = p.attachment_id"
                + " WHERE p.record_id = ? AND p.seq BETWEEN ? AND ?"
                + " AND a.path NOT LIKE 'attachments/%'",
            Long.class, src, fromSeq, toSeq);
    if (legacy != null && legacy > 0) {
      throw new PageMoveException(
          Kind.CONFLICT,
          "%d of these pages still have their image in the old records/{id}/ layout; run the storage"
                  .formatted(legacy)
              + " migration first, or deleting the record they leave would delete the scan");
    }
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
