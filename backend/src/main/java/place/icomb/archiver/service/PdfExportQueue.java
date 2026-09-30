package place.icomb.archiver.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import place.icomb.archiver.service.PdfExportService.Variant;

/**
 * Every statement that touches {@code pdf_export}: requesting, reusing, claiming, finishing,
 * expiring.
 *
 * <p>Page ids go to SQL as a comma-separated string and come back the same way, which avoids JDBC
 * array plumbing for no loss.
 */
@Service
public class PdfExportQueue {

  /** What a caller sees of an export. */
  public record View(
      String id,
      String state,
      int pageCount,
      String variant,
      Long bytes,
      Instant expiresAt,
      String error) {}

  /** A request's outcome: the export, and whether this call created it. */
  public record Requested(View view, boolean created) {}

  /** An export a worker has taken. */
  public record Claimed(
      String id, long recordId, Variant variant, List<Long> pageIds, String fingerprint) {}

  /** The columns the file endpoint needs. */
  public record Row(
      String id, long recordId, String state, String path, Variant variant, String error) {}

  private static final String VIEW_SQL =
      "SELECT id::text AS id, state, cardinality(page_ids) AS page_count, variant, bytes,"
          + " expires_at, error FROM pdf_export";

  private static final RowMapper<View> VIEW =
      (rs, n) -> {
        Timestamp expires = rs.getTimestamp("expires_at");
        return new View(
            rs.getString("id"),
            rs.getString("state"),
            rs.getInt("page_count"),
            apiName(fromDb(rs.getString("variant"))),
            (Long) rs.getObject("bytes"),
            expires == null ? null : expires.toInstant(),
            rs.getString("error"));
      };

  private final JdbcTemplate jdbc;
  private final PdfExportService pdfExportService;

  public PdfExportQueue(JdbcTemplate jdbc, PdfExportService pdfExportService) {
    this.jdbc = jdbc;
    this.pdfExportService = pdfExportService;
  }

  // --- variants ----------------------------------------------------------------------------

  static String dbName(Variant v) {
    return switch (v) {
      case ORIGINAL -> "original";
      case ENGLISH -> "english";
      case SIDE_BY_SIDE -> "side_by_side";
    };
  }

  static Variant fromDb(String s) {
    return switch (s) {
      case "english" -> Variant.ENGLISH;
      case "side_by_side" -> Variant.SIDE_BY_SIDE;
      default -> Variant.ORIGINAL;
    };
  }

  /** The API's spelling: original, english or side-by-side. */
  static String apiName(Variant v) {
    return v == Variant.SIDE_BY_SIDE ? "side-by-side" : dbName(v);
  }

  /** Reads the API's spelling; nothing given means the scans. */
  public static Variant parseVariant(String s) {
    if (s == null || s.isBlank()) {
      return Variant.ORIGINAL;
    }
    return switch (s.trim().toLowerCase(Locale.ROOT)) {
      case "original" -> Variant.ORIGINAL;
      case "english" -> Variant.ENGLISH;
      case "side-by-side", "sidebyside", "side_by_side" -> Variant.SIDE_BY_SIDE;
      default -> throw new IllegalArgumentException("Unknown variant: " + s);
    };
  }

  /** Named for what the file contains, so a folder of exports reads without opening them. */
  public static String fileSuffix(Variant v) {
    return switch (v) {
      case ORIGINAL -> "-original";
      case ENGLISH -> "-english";
      case SIDE_BY_SIDE -> "-original-and-english";
    };
  }

  // --- selecting pages and fingerprinting them ---------------------------------------------

  /**
   * The ids of the pages a selection names, in page order.
   *
   * @param pages a range like {@code 1,3,5-10}; blank means every page
   * @throws IllegalArgumentException if the range is malformed
   */
  public List<Long> pageIdsFor(long recordId, String pages) {
    if (pages == null || pages.isBlank()) {
      return jdbc.queryForList(
          "SELECT id FROM page WHERE record_id = ? ORDER BY seq", Long.class, recordId);
    }
    int maxSeq =
        jdbc.queryForObject(
            "SELECT coalesce(max(seq), 0) FROM page WHERE record_id = ?", Integer.class, recordId);
    List<Integer> seqs = pdfExportService.parsePageRange(pages, maxSeq);
    if (seqs.isEmpty()) {
      return List.of();
    }
    String marks = String.join(", ", Collections.nCopies(seqs.size(), "?"));
    List<Object> args = new ArrayList<>();
    args.add(recordId);
    args.addAll(seqs);
    return jdbc.queryForList(
        "SELECT id FROM page WHERE record_id = ? AND seq IN (" + marks + ") ORDER BY seq",
        Long.class,
        args.toArray());
  }

  /**
   * What these pages are right now: an md5 over the whole record and archive rows (minus the
   * volatile {@code updated_at}), the ids and engines of every transcription and the ids and models
   * of every translation in the record (the cover sheet prints record-wide provenance), and, for
   * each selected page in order, its id, record, position, scan, text, a hash of its English text,
   * and each of its translations (id and a hash of its text, since a re-run overwrites a row in
   * place). Re-OCR, a new translation, a replaced scan or a moved page all change it.
   */
  public String fingerprint(long recordId, List<Long> pageIds) {
    return jdbc.queryForObject(
        """
        SELECT md5(
          coalesce((SELECT md5((to_jsonb(r) - 'updated_at')::text || to_jsonb(a)::text)
                    FROM record r JOIN archive a ON a.id = r.archive_id
                    WHERE r.id = ?), '')
          || coalesce((SELECT md5(string_agg(pt2.id::text || ':' || pt2.engine, ','
                                             ORDER BY pt2.id))
                       FROM page_text pt2 JOIN page p2 ON p2.id = pt2.page_id
                       WHERE p2.record_id = ?), '')
          || coalesce((SELECT md5(string_agg(t2.id::text || ':' || t2.model, ','
                                             ORDER BY t2.id))
                       FROM page_translation t2 JOIN page p3 ON p3.id = t2.page_id
                       WHERE p3.record_id = ?), '')
          || coalesce(string_agg(
               concat_ws(':', p.id, p.record_id, p.seq, p.attachment_id,
                         coalesce(pt.id::text, '-'),
                         md5(coalesce(pt.text_en, '')),
                         coalesce((SELECT string_agg(t.id::text || '=' || md5(t.text_en), ',' ORDER BY t.id)
                                   FROM page_translation t WHERE t.page_id = p.id), '-')),
               '|' ORDER BY sel.ord), ''))
        FROM unnest(string_to_array(?, ',')::bigint[]) WITH ORDINALITY AS sel(page_id, ord)
        JOIN page p ON p.id = sel.page_id
        LEFT JOIN page_text pt ON pt.page_id = p.id
        """,
        String.class,
        recordId,
        recordId,
        recordId,
        csv(pageIds));
  }

  private static String csv(List<Long> ids) {
    return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
  }

  private static List<Long> ids(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    return java.util.Arrays.stream(csv.split(",")).map(Long::valueOf).toList();
  }

  /** The positions of the given pages within a record, in order. */
  public List<Integer> seqsOf(long recordId, List<Long> pageIds) {
    return jdbc.queryForList(
        "SELECT seq FROM page WHERE record_id = ?"
            + " AND id IN (SELECT unnest(string_to_array(?, ',')::bigint[])) ORDER BY seq",
        Integer.class,
        recordId,
        csv(pageIds));
  }

  // --- requesting --------------------------------------------------------------------------

  /**
   * Asks for an export, reusing an identical one when there is one.
   *
   * <p>Identical means the same record, variant and fingerprint, and either still queued or
   * building, or finished and not yet expired. Serialised on the fingerprint, so two identical
   * requests arriving together cannot both insert.
   */
  @Transactional
  public Requested request(long recordId, Variant variant, List<Long> pageIds) {
    String fingerprint = fingerprint(recordId, pageIds);
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", fingerprint);

    List<View> reusable =
        jdbc.query(
            VIEW_SQL
                + " WHERE record_id = ? AND variant = ? AND fingerprint = ?"
                + " AND (state IN ('queued', 'building')"
                + "      OR (state = 'ready' AND expires_at > now()))"
                + " ORDER BY created_at DESC LIMIT 1",
            VIEW,
            recordId,
            dbName(variant),
            fingerprint);
    if (!reusable.isEmpty()) {
      return new Requested(reusable.get(0), false);
    }

    String id =
        jdbc.queryForObject(
            "INSERT INTO pdf_export (record_id, variant, page_ids, fingerprint)"
                + " VALUES (?, ?, string_to_array(?, ',')::bigint[], ?) RETURNING id::text",
            String.class,
            recordId,
            dbName(variant),
            csv(pageIds),
            fingerprint);
    return new Requested(find(id).orElseThrow(), true);
  }

  // --- reading -----------------------------------------------------------------------------

  private static Optional<UUID> uuid(String id) {
    try {
      return Optional.of(UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  public Optional<View> find(String id) {
    return uuid(id)
        .flatMap(
            u ->
                jdbc.query(VIEW_SQL + " WHERE id = ?::uuid", VIEW, u.toString()).stream()
                    .findFirst());
  }

  public Optional<Row> row(String id) {
    return uuid(id)
        .flatMap(
            u ->
                jdbc
                    .query(
                        "SELECT id::text AS id, record_id, state, path, variant, error"
                            + " FROM pdf_export WHERE id = ?::uuid",
                        (rs, n) ->
                            new Row(
                                rs.getString("id"),
                                rs.getLong("record_id"),
                                rs.getString("state"),
                                rs.getString("path"),
                                fromDb(rs.getString("variant")),
                                rs.getString("error")),
                        u.toString())
                    .stream()
                    .findFirst());
  }

  /** The files of a record's exports, for deleting with the record. */
  public List<String> pathsForRecord(long recordId) {
    return jdbc.queryForList(
        "SELECT path FROM pdf_export WHERE record_id = ? AND path IS NOT NULL",
        String.class,
        recordId);
  }

  // --- working -----------------------------------------------------------------------------

  /**
   * Takes the oldest queued export and marks it building. {@code SKIP LOCKED}, so workers never
   * wait on each other and never take the same row.
   */
  @Transactional
  public Optional<Claimed> claimNext() {
    return jdbc
        .query(
            """
            UPDATE pdf_export SET state = 'building', started_at = now()
            WHERE id = (SELECT id FROM pdf_export WHERE state = 'queued'
                        ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1)
            RETURNING id::text AS id, record_id, variant,
                      array_to_string(page_ids, ',') AS page_ids, fingerprint
            """,
            (rs, n) ->
                new Claimed(
                    rs.getString("id"),
                    rs.getLong("record_id"),
                    fromDb(rs.getString("variant")),
                    ids(rs.getString("page_ids")),
                    rs.getString("fingerprint")))
        .stream()
        .findFirst();
  }

  /**
   * Records a finished build, but only for an export that is still building.
   *
   * @return false when it no longer was (failed as hung, or its record was deleted), in which case
   *     nothing changed and the caller must discard the file it built
   */
  public boolean markReady(String id, String path, long bytes, Duration ttl) {
    return jdbc.update(
            "UPDATE pdf_export SET state = 'ready', path = ?, bytes = ?, finished_at = now(),"
                + " expires_at = now() + make_interval(secs => ?)"
                + " WHERE id = ?::uuid AND state = 'building'",
            path,
            bytes,
            (double) ttl.toSeconds(),
            id)
        == 1;
  }

  public void markFailed(String id, String error) {
    jdbc.update(
        "UPDATE pdf_export SET state = 'failed', error = ?, finished_at = now()"
            + " WHERE id = ?::uuid",
        error,
        id);
  }

  // --- recovering and expiring -------------------------------------------------------------

  /** Fails every building export: at startup no build can be running in a fresh JVM. */
  public int failAllBuilding(String reason) {
    return jdbc.update(
        "UPDATE pdf_export SET state = 'failed', error = ?, finished_at = now()"
            + " WHERE state = 'building'",
        reason);
  }

  /** Fails a build that has run longer than it should: a hang in a live JVM. */
  public int failInterrupted(Duration olderThan) {
    return jdbc.update(
        "UPDATE pdf_export SET state = 'failed', error = 'interrupted', finished_at = now()"
            + " WHERE state = 'building' AND started_at < now() - make_interval(secs => ?)",
        (double) olderThan.toSeconds());
  }

  /** Marks finished exports past their expiry {@code expired}, returning their files to delete. */
  public List<String> expireDue() {
    return jdbc.queryForList(
        "UPDATE pdf_export SET state = 'expired' WHERE state = 'ready' AND expires_at <= now()"
            + " RETURNING path",
        String.class);
  }

  /** Deletes expired and failed rows a week old, so a stale link says 410 rather than 404. */
  public int purgeOld() {
    return jdbc.update(
        "DELETE FROM pdf_export WHERE state IN ('expired', 'failed')"
            + " AND created_at < now() - interval '7 days'");
  }
}
