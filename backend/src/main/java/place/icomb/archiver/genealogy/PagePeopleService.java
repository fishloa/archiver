package place.icomb.archiver.genealogy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import place.icomb.archiver.service.FamilyTreeService;
import place.icomb.archiver.service.FamilyTreeService.Person;

/**
 * Who is confirmed on a page or a record: people a curator linked to it, or tagged on a picture cut
 * from it. Person-matching hints are not read here.
 */
@Service
public class PagePeopleService {

  private static final List<String> PRECEDENCE = List.of("page", "picture", "record");

  private final JdbcTemplate jdbc;
  private final FamilyTreeService tree;

  public PagePeopleService(JdbcTemplate jdbc, FamilyTreeService tree) {
    this.jdbc = jdbc;
    this.tree = tree;
  }

  private record Hit(
      int personId, Integer pageSeq, String via, String role, String note, Long mediaId) {}

  /** One entry per person; a page link beats a picture, which beats a link to the whole record. */
  public List<Map<String, Object>> forPage(long pageId) {
    List<Map<String, Object>> page =
        jdbc.queryForList("SELECT record_id, seq FROM page WHERE id = ?", pageId);
    if (page.isEmpty()) {
      return List.of();
    }
    long recordId = ((Number) page.get(0).get("record_id")).longValue();
    return entries(hits(recordId, pageId), false);
  }

  /** One entry per person per page they are confirmed on; record-level links have no pageSeq. */
  public List<Map<String, Object>> forRecord(long recordId) {
    return entries(hits(recordId, null), true);
  }

  /**
   * The portrait URL of each person who has one; merged people are asked about as their survivor.
   */
  public Map<Integer, String> portraits(Collection<Integer> ids) {
    Map<Integer, String> out = new LinkedHashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    Map<Integer, Integer> canonical = new LinkedHashMap<>();
    Set<Integer> lookup = new LinkedHashSet<>();
    for (int id : ids) {
      Person p = tree.getPerson(id);
      int c = p == null ? id : p.id;
      canonical.put(id, c);
      lookup.add(c);
      lookup.add(id);
    }
    String marks = String.join(",", lookup.stream().map(i -> "?").toList());
    Map<Integer, Long> media = new LinkedHashMap<>();
    jdbc.query(
        "SELECT person_id, media_id FROM gen_media_person WHERE is_portrait AND person_id IN ("
            + marks
            + ")",
        rs -> {
          media.put(rs.getInt("person_id"), rs.getLong("media_id"));
        },
        lookup.toArray());
    for (var e : canonical.entrySet()) {
      Long m = media.containsKey(e.getValue()) ? media.get(e.getValue()) : media.get(e.getKey());
      if (m != null) {
        out.put(e.getKey(), "/api/family-tree/media/" + m + "/image");
      }
    }
    return out;
  }

  private List<Hit> hits(long recordId, Long pageId) {
    List<Hit> out = new ArrayList<>();
    String pageFilter = pageId == null ? "" : " AND (l.page_id IS NULL OR l.page_id = ?)";
    Object[] linkArgs = pageId == null ? new Object[] {recordId} : new Object[] {recordId, pageId};
    jdbc.query(
        "SELECT l.person_id, l.page_id, pg.seq, l.role, l.note FROM gen_person_record l"
            + " LEFT JOIN page pg ON pg.id = l.page_id WHERE l.record_id = ?"
            + pageFilter
            + " ORDER BY l.id",
        rs -> {
          boolean onPage = rs.getObject("page_id") != null;
          out.add(
              new Hit(
                  rs.getInt("person_id"),
                  onPage ? rs.getInt("seq") : null,
                  onPage ? "page" : "record",
                  rs.getString("role"),
                  rs.getString("note"),
                  null));
        },
        linkArgs);
    String mediaFilter = pageId == null ? "" : " AND m.page_id = ?";
    jdbc.query(
        "SELECT mp.person_id, m.id AS media_id, pg.seq FROM gen_media_person mp"
            + " JOIN gen_media m ON m.id = mp.media_id JOIN page pg ON pg.id = m.page_id"
            + " WHERE pg.record_id = ?"
            + mediaFilter
            + " ORDER BY m.id, mp.sort_order",
        rs -> {
          out.add(
              new Hit(
                  rs.getInt("person_id"),
                  rs.getInt("seq"),
                  "picture",
                  "pictured",
                  null,
                  rs.getLong("media_id")));
        },
        linkArgs);
    return out;
  }

  private List<Map<String, Object>> entries(List<Hit> hits, boolean bySeq) {
    Map<String, List<Hit>> groups = new LinkedHashMap<>();
    Map<Integer, Person> people = new LinkedHashMap<>();
    for (Hit h : hits) {
      Person p = people.computeIfAbsent(h.personId(), tree::getPerson);
      if (p == null) {
        continue;
      }
      String key = p.id + "|" + (bySeq ? h.pageSeq() : "");
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(h);
    }
    Map<Integer, String> portraits =
        portraits(groups.values().stream().map(g -> g.get(0).personId()).distinct().toList());
    List<Map<String, Object>> out = new ArrayList<>();
    for (List<Hit> group : groups.values()) {
      Hit best = group.get(0);
      for (Hit h : group) {
        if (PRECEDENCE.indexOf(h.via()) < PRECEDENCE.indexOf(best.via())) {
          best = h;
        }
      }
      Person p = people.get(best.personId());
      Set<String> roles = new LinkedHashSet<>();
      roles.add(best.role());
      group.forEach(h -> roles.add(h.role()));
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("personId", p.id);
      e.put("name", p.name);
      e.put("birthYear", p.birthYear);
      e.put("deathYear", p.deathYear);
      e.put("portraitUrl", portraits.get(best.personId()));
      e.put("via", best.via());
      e.put("role", best.role());
      e.put("roles", List.copyOf(roles));
      e.put("note", best.note());
      if (best.mediaId() != null) {
        e.put("mediaId", best.mediaId());
      }
      if (bySeq) {
        e.put("pageSeq", best.pageSeq());
      }
      out.add(e);
    }
    return out;
  }
}
