package place.icomb.archiver.genealogy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import place.icomb.archiver.service.FamilyTreeService;
import place.icomb.archiver.service.JobService;
import place.icomb.archiver.service.OcrFigures;

/**
 * Hints ({@code page_person_match}): suggestions made on request, shown to administrators only, and
 * turned into a record link or a media tag only when one is confirmed.
 */
@Service
public class HintService {

  private static final String JOB_KIND = "match_persons";

  private final JdbcTemplate jdbc;
  private final JobService jobs;
  private final GenealogyService genealogy;
  private final FamilyTreeService tree;
  private final OcrFigures figures;

  public HintService(
      JdbcTemplate jdbc,
      JobService jobs,
      GenealogyService genealogy,
      FamilyTreeService tree,
      OcrFigures figures) {
    this.jdbc = jdbc;
    this.jobs = jobs;
    this.genealogy = genealogy;
    this.tree = tree;
    this.figures = figures;
  }

  /** Queues a hint run for one page, or for every page of a record: {@code {jobId, pages}}. */
  public Map<String, Object> run(Long recordId, Long pageId) {
    if (pageId != null) {
      List<Long> record =
          jdbc.queryForList("SELECT record_id FROM page WHERE id = ?", Long.class, pageId);
      if (record.isEmpty()) {
        throw GenealogyException.notFound("no page " + pageId);
      }
      return Map.of(
          "jobId", jobs.enqueueJob(JOB_KIND, record.get(0), pageId, null).getId(), "pages", 1);
    }
    if (recordId == null) {
      throw GenealogyException.badRequest("give recordId or pageId");
    }
    List<Integer> pages =
        jdbc.queryForList(
            "SELECT (SELECT count(*) FROM page WHERE record_id = r.id)::int FROM record r"
                + " WHERE r.id = ?",
            Integer.class,
            recordId);
    if (pages.isEmpty()) {
      throw GenealogyException.notFound("no record " + recordId);
    }
    return Map.of(
        "jobId", jobs.enqueueJob(JOB_KIND, recordId, null, null).getId(), "pages", pages.get(0));
  }

  /** A page's or a record's hints, with each person's name and years and each figure's number. */
  public List<Map<String, Object>> list(Long pageId, Long recordId) {
    if ((pageId == null) == (recordId == null)) {
      throw GenealogyException.badRequest("give pageId or recordId");
    }
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            SELECT h.id, h.page_id, p.record_id, p.seq, h.person_id, h.person_name, h.score,
                   h.context, h.figure_id
            FROM page_person_match h JOIN page p ON p.id = h.page_id
            WHERE %s = ?
            ORDER BY p.seq, h.figure_id NULLS FIRST, h.score DESC, h.id
            """
                .formatted(pageId != null ? "h.page_id" : "p.record_id"),
            pageId != null ? pageId : recordId);
    Map<Long, Map<String, Integer>> numbers = new HashMap<>();
    return rows.stream()
        .map(
            r -> {
              long page = ((Number) r.get("page_id")).longValue();
              int personId = ((Number) r.get("person_id")).intValue();
              String figureId = (String) r.get("figure_id");
              FamilyTreeService.Person person = tree.getPerson(personId);
              Map<String, Object> out = new LinkedHashMap<>();
              out.put("id", r.get("id"));
              out.put("pageId", page);
              out.put("recordId", r.get("record_id"));
              out.put("pageSeq", r.get("seq"));
              out.put("personId", personId);
              out.put("name", person != null ? person.name : r.get("person_name"));
              out.put("birthYear", person != null ? person.birthYear : null);
              out.put("deathYear", person != null ? person.deathYear : null);
              out.put("score", r.get("score"));
              out.put("context", r.get("context"));
              out.put("figureId", figureId);
              out.put(
                  "figureNumber",
                  figureId == null
                      ? null
                      : numbers.computeIfAbsent(page, this::figureNumbers).get(figureId));
              return out;
            })
        .toList();
  }

  private Map<String, Integer> figureNumbers(long pageId) {
    Map<String, Integer> out = new HashMap<>();
    for (OcrFigures.Figure f : figures.figures(pageId)) out.put(f.id(), f.number());
    return out;
  }

  /**
   * Confirms a hint in one write: a text hint becomes a record link on its page, a figure hint a
   * picture cut to the figure and tagged with the person. The hint is deleted in the same
   * transaction, so confirming it again finds nothing.
   */
  @Transactional
  public Map<String, Object> confirm(long id, String reason, String role, Boolean portrait) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            SELECT h.page_id, h.person_id, h.context, h.figure_id, p.record_id, p.seq
            FROM page_person_match h JOIN page p ON p.id = h.page_id
            WHERE h.id = ? FOR UPDATE OF h
            """,
            id);
    if (rows.isEmpty()) {
      throw GenealogyException.notFound("no hint " + id);
    }
    Map<String, Object> h = rows.get(0);
    long pageId = ((Number) h.get("page_id")).longValue();
    long recordId = ((Number) h.get("record_id")).longValue();
    int personId = ((Number) h.get("person_id")).intValue();
    String figureId = (String) h.get("figure_id");
    String why =
        reason == null || reason.isBlank()
            ? "confirmed hint #%d (page %s of record %d)".formatted(id, h.get("seq"), recordId)
            : reason;

    Map<String, Object> out;
    if (figureId == null) {
      Map<String, Object> link = new LinkedHashMap<>();
      link.put("personId", personId);
      link.put("recordId", recordId);
      link.put("pageId", pageId);
      link.put("role", role == null || role.isBlank() ? "mentioned" : role);
      link.put("note", h.get("context"));
      out = genealogy.create(GenEntity.RECORD_LINK, link, why);
    } else {
      OcrFigures.Figure f =
          figures.figures(pageId).stream()
              .filter(x -> x.id().equals(figureId))
              .findFirst()
              .orElseThrow(
                  () ->
                      GenealogyException.conflict(
                          "page %d no longer has figure %s".formatted(pageId, figureId)));
      Map<String, Object> media = new LinkedHashMap<>();
      media.put("pageId", pageId);
      media.put("cropLeft", f.left());
      media.put("cropTop", f.top());
      media.put("cropWidth", f.width());
      media.put("cropHeight", f.height());
      out = genealogy.createMedia(media, personId, Boolean.TRUE.equals(portrait), why);
    }
    jdbc.update("DELETE FROM page_person_match WHERE id = ?", id);
    return out;
  }
}
