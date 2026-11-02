package place.icomb.archiver.genealogy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The structured facts about one person that the person page and get_person show. */
@Service
public class GenealogyReadService {

  private final JdbcTemplate jdbc;

  public GenealogyReadService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Map<String, Object> personDetail(int personId) {
    return personDetail(personId, false);
  }

  public Map<String, Object> personDetail(int personId, boolean includePrivate) {
    List<Map<String, Object>> person =
        jdbc.queryForList(
            "SELECT uid, sex, living, notes, needs_review FROM gen_person WHERE id = ?", personId);
    if (person.isEmpty()) return null;
    Map<String, Object> out = new LinkedHashMap<>(Rows.camel(person.get(0)));
    if (!includePrivate) {
      out.remove("notes");
      out.remove("needsReview");
    }
    out.put(
        "names",
        Rows.camel(
            jdbc.queryForList(
                """
                SELECT id, type, given, surname, prefix, suffix, nickname, is_primary, sort_order
                FROM gen_name WHERE person_id = ? ORDER BY is_primary DESC, sort_order, id
                """,
                personId)));
    out.put(
        "couples",
        Rows.camel(
            jdbc.queryForList(
                """
                SELECT c.id, c.type, c.sort_order, c.notes, s.id AS spouse_id,
                       concat_ws(' ', n.given, n.surname) AS spouse_name
                FROM gen_couple c
                JOIN gen_person s ON s.id = CASE WHEN c.person1_id = ? THEN c.person2_id
                                                 ELSE c.person1_id END
                LEFT JOIN gen_name n ON n.person_id = s.id AND n.is_primary
                WHERE ? IN (c.person1_id, c.person2_id)
                ORDER BY c.sort_order, c.id
                """,
                personId,
                personId)));
    out.put(
        "parentLinks",
        Rows.camel(
            jdbc.queryForList(
                """
                SELECT l.id, l.type, l.couple_id, l.parent_id, l.child_id,
                       CASE WHEN l.child_id = ? THEN 'parent' ELSE 'child' END AS relation,
                       concat_ws(' ', n.given, n.surname) AS other_name
                FROM gen_parent_child l
                LEFT JOIN gen_name n ON n.is_primary
                     AND n.person_id = CASE WHEN l.child_id = ? THEN l.parent_id ELSE l.child_id END
                WHERE ? IN (l.parent_id, l.child_id)
                ORDER BY relation DESC, l.id
                """,
                personId,
                personId,
                personId)));
    out.put(
        "recordLinks",
        Rows.camel(
            jdbc.queryForList(
                """
                SELECT pr.id, pr.record_id, pr.page_id, pg.seq AS page_seq, pr.role, pr.note,
                       pr.quality, r.title, r.title_en, r.reference_code
                FROM gen_person_record pr
                JOIN record r ON r.id = pr.record_id
                LEFT JOIN page pg ON pg.id = pr.page_id
                WHERE pr.person_id = ?
                ORDER BY pr.record_id, pg.seq NULLS FIRST, pr.id
                """,
                personId)));
    List<Map<String, Object>> media =
        Rows.camel(
            jdbc.queryForList(
                """
                SELECT m.id, m.page_id, pg.record_id, pg.seq AS page_seq, m.crop_left, m.crop_top,
                       m.crop_width, m.crop_height, m.caption, m.date_text, mp.is_portrait,
                       '/api/family-tree/media/' || m.id || '/image' AS image_url
                FROM gen_media_person mp
                JOIN gen_media m ON m.id = mp.media_id
                JOIN page pg ON pg.id = m.page_id
                WHERE mp.person_id = ?
                ORDER BY mp.is_portrait DESC, mp.sort_order, m.id
                """,
                personId));
    out.put("media", media);
    out.put(
        "portrait",
        media.stream()
            .filter(m -> Boolean.TRUE.equals(m.get("isPortrait")))
            .findFirst()
            .orElse(null));
    out.put(
        "sources",
        Rows.camel(
            jdbc.queryForList(
                """
                SELECT DISTINCT s.id, s.title, s.author, s.record_id, s.url
                FROM gen_source s
                JOIN gen_citation c ON c.source_id = s.id
                WHERE c.person_id = ?
                   OR c.name_id IN (SELECT id FROM gen_name WHERE person_id = ?)
                   OR c.event_id IN (SELECT e.id FROM gen_event e
                                     LEFT JOIN gen_couple k ON k.id = e.couple_id
                                     WHERE e.person_id = ? OR ? IN (k.person1_id, k.person2_id))
                   OR c.couple_id IN (SELECT id FROM gen_couple
                                      WHERE ? IN (person1_id, person2_id))
                   OR c.parent_child_id IN (SELECT id FROM gen_parent_child WHERE child_id = ?)
                ORDER BY s.id
                """,
                personId,
                personId,
                personId,
                personId,
                personId,
                personId)));
    return out;
  }
}
