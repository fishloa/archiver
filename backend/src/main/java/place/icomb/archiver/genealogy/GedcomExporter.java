package place.icomb.archiver.genealogy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The genealogy as a GEDCOM 7.0 file (FamilySearch GEDCOM 7.0 specification).
 *
 * <p>People are {@code @I<id>@}, couples {@code @F<id>@}, sources {@code @S<id>@}, images
 * {@code @O<id>@}. A child whose parents form no couple gets a family of its one parent,
 * {@code @F_P<id>@}. Images point at the archive's stored original and carry their crop.
 */
@Service
public class GedcomExporter {

  static final String FILE_BASE = "https://archive.czernin.eu/api/files/";

  private static final Set<String> ATTRIBUTES = Set.of("OCCU", "TITL", "EDUC", "RESI");
  private static final Map<String, String> NAME_TYPES =
      Map.of(
          "birth", "BIRTH",
          "married", "MARRIED",
          "alias", "AKA",
          "religious", "OTHER",
          "title", "OTHER",
          "other", "OTHER");
  private static final Map<String, String> PEDIGREE =
      Map.of("birth", "BIRTH", "adopted", "ADOPTED", "foster", "FOSTER");

  private final JdbcTemplate jdbc;

  public GedcomExporter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private record Family(String xref, Integer husband, Integer wife, Long coupleId) {}

  public String export(boolean includeLiving) {
    Map<Integer, Map<String, Object>> people = new LinkedHashMap<>();
    for (Map<String, Object> p :
        jdbc.queryForList(
            "SELECT id, uid::text AS uid, sex, living, notes FROM gen_person"
                + " WHERE merged_into IS NULL ORDER BY id")) {
      if (includeLiving || !Boolean.TRUE.equals(p.get("living"))) {
        people.put((Integer) p.get("id"), p);
      }
    }
    Map<Long, String> places = placeNames();
    Map<String, List<Map<String, Object>>> citations = citations();

    List<Family> families = new ArrayList<>();
    Map<Long, Family> byCouple = new HashMap<>();
    for (Map<String, Object> c :
        jdbc.queryForList("SELECT id, person1_id, person2_id FROM gen_couple ORDER BY id")) {
      Integer a = keep(people, (Integer) c.get("person1_id"));
      Integer b = keep(people, (Integer) c.get("person2_id"));
      boolean aFemale = a != null && "F".equals(people.get(a).get("sex"));
      Integer husband = aFemale ? b : a;
      Integer wife = aFemale ? a : b;
      Family f = new Family("F" + c.get("id"), husband, wife, ((Number) c.get("id")).longValue());
      byCouple.put(f.coupleId(), f);
      families.add(f);
    }
    Map<Family, List<Object[]>> children = new LinkedHashMap<>();
    Map<Integer, Family> singleParent = new HashMap<>();
    for (Map<String, Object> l :
        jdbc.queryForList(
            "SELECT parent_id, child_id, type, couple_id FROM gen_parent_child ORDER BY child_id, id")) {
      Integer child = keep(people, (Integer) l.get("child_id"));
      if (child == null) continue;
      Family f;
      if (l.get("couple_id") != null) {
        f = byCouple.get(((Number) l.get("couple_id")).longValue());
      } else {
        Integer parent = keep(people, (Integer) l.get("parent_id"));
        if (parent == null) continue;
        f =
            singleParent.computeIfAbsent(
                parent,
                p -> {
                  boolean female = "F".equals(people.get(p).get("sex"));
                  Family made = new Family("F_P" + p, female ? null : p, female ? p : null, null);
                  families.add(made);
                  return made;
                });
      }
      List<Object[]> kids = children.computeIfAbsent(f, k -> new ArrayList<>());
      if (kids.stream().noneMatch(k -> k[0].equals(child))) {
        kids.add(new Object[] {child, l.get("type")});
      }
    }
    families.removeIf(
        f ->
            f.husband() == null
                && f.wife() == null
                && children.getOrDefault(f, List.of()).isEmpty());

    Map<Integer, List<String>> famc = new HashMap<>();
    Map<Integer, List<String>> pedigree = new HashMap<>();
    Map<Integer, List<String>> fams = new HashMap<>();
    for (Family f : families) {
      for (Integer partner : new Integer[] {f.husband(), f.wife()}) {
        if (partner != null) fams.computeIfAbsent(partner, k -> new ArrayList<>()).add(f.xref());
      }
      for (Object[] kid : children.getOrDefault(f, List.of())) {
        famc.computeIfAbsent((Integer) kid[0], k -> new ArrayList<>()).add(f.xref());
        pedigree.computeIfAbsent((Integer) kid[0], k -> new ArrayList<>()).add((String) kid[1]);
      }
    }

    Gedcom g = new Gedcom();
    g.tag(0, "HEAD");
    g.tag(1, "GEDC");
    g.value(2, "VERS", "7.0");
    g.value(1, "SOUR", "CZERNIN_ARCHIVE");
    g.value(2, "NAME", "Czernin family archive");

    Map<Integer, List<Map<String, Object>>> names =
        byPerson("SELECT * FROM gen_name ORDER BY person_id, is_primary DESC, sort_order, id");
    Map<Integer, List<Map<String, Object>>> events =
        byPerson(
            "SELECT * FROM gen_event WHERE person_id IS NOT NULL ORDER BY person_id, sort_order, id");
    Map<Integer, List<Map<String, Object>>> media =
        byPerson(
            "SELECT mp.person_id, m.* FROM gen_media_person mp JOIN gen_media m ON m.id = mp.media_id"
                + " ORDER BY mp.person_id, mp.is_portrait DESC, mp.sort_order, m.id");
    Set<Long> usedSources = new HashSet<>();
    Set<Long> usedMedia = new HashSet<>();

    for (Map<String, Object> p : people.values()) {
      int id = (Integer) p.get("id");
      g.record("I" + id, "INDI");
      for (Map<String, Object> n : names.getOrDefault(id, List.of())) {
        String given = (String) n.get("given");
        String surname = (String) n.get("surname");
        String full =
            ((given == null ? "" : given) + " /" + (surname == null ? "" : surname) + "/").trim();
        g.value(1, "NAME", full);
        g.value(2, "TYPE", NAME_TYPES.get((String) n.get("type")));
        if (List.of("religious", "title").contains(n.get("type"))) {
          g.value(3, "PHRASE", (String) n.get("type"));
        }
        g.value(2, "NPFX", (String) n.get("prefix"));
        g.value(2, "GIVN", given);
        g.value(2, "NICK", (String) n.get("nickname"));
        g.value(2, "SURN", surname);
        g.value(2, "NSFX", (String) n.get("suffix"));
        cite(g, 2, citations.get("name_id:" + n.get("id")), usedSources);
      }
      g.value(1, "SEX", (String) p.get("sex"));
      for (Map<String, Object> e : events.getOrDefault(id, List.of())) {
        event(g, e, places, citations, usedSources);
      }
      List<String> parents = famc.getOrDefault(id, List.of());
      for (int i = 0; i < parents.size(); i++) {
        g.pointer(1, "FAMC", parents.get(i));
        String type = pedigree.get(id).get(i);
        g.value(2, "PEDI", PEDIGREE.getOrDefault(type, "OTHER"));
        if (!PEDIGREE.containsKey(type)) g.value(3, "PHRASE", type);
      }
      for (String f : fams.getOrDefault(id, List.of())) g.pointer(1, "FAMS", f);
      for (Map<String, Object> m : media.getOrDefault(id, List.of())) {
        long mediaId = ((Number) m.get("id")).longValue();
        usedMedia.add(mediaId);
        g.pointer(1, "OBJE", "O" + mediaId);
        if (m.get("crop_left") != null) {
          g.tag(2, "CROP");
          g.value(3, "TOP", String.valueOf(m.get("crop_top")));
          g.value(3, "LEFT", String.valueOf(m.get("crop_left")));
          g.value(3, "HEIGHT", String.valueOf(m.get("crop_height")));
          g.value(3, "WIDTH", String.valueOf(m.get("crop_width")));
        }
        g.value(2, "TITL", (String) m.get("caption"));
      }
      cite(g, 1, citations.get("person_id:" + id), usedSources);
      g.value(1, "NOTE", (String) p.get("notes"));
      g.value(1, "_UID", (String) p.get("uid"));
    }

    Map<Long, List<Map<String, Object>>> coupleEvents = new HashMap<>();
    for (Map<String, Object> e :
        jdbc.queryForList(
            "SELECT * FROM gen_event WHERE couple_id IS NOT NULL ORDER BY couple_id, sort_order, id")) {
      coupleEvents
          .computeIfAbsent(((Number) e.get("couple_id")).longValue(), k -> new ArrayList<>())
          .add(e);
    }
    for (Family f : families) {
      List<Object[]> kids = children.getOrDefault(f, List.of());
      boolean anyone = f.husband() != null || f.wife() != null || !kids.isEmpty();
      if (!anyone) continue;
      g.record(f.xref(), "FAM");
      if (f.husband() != null) g.pointer(1, "HUSB", "I" + f.husband());
      if (f.wife() != null) g.pointer(1, "WIFE", "I" + f.wife());
      for (Object[] kid : kids) g.pointer(1, "CHIL", "I" + kid[0]);
      if (f.coupleId() != null) {
        for (Map<String, Object> e : coupleEvents.getOrDefault(f.coupleId(), List.of())) {
          event(g, e, places, citations, usedSources);
        }
        cite(g, 1, citations.get("couple_id:" + f.coupleId()), usedSources);
      }
    }

    for (Map<String, Object> s : jdbc.queryForList("SELECT * FROM gen_source ORDER BY id")) {
      long id = ((Number) s.get("id")).longValue();
      if (!usedSources.contains(id)) continue;
      g.record("S" + id, "SOUR");
      g.value(1, "TITL", (String) s.get("title"));
      g.value(1, "AUTH", (String) s.get("author"));
      String note = String.join("\n", nonNull((String) s.get("url"), (String) s.get("notes")));
      g.value(1, "NOTE", note.isEmpty() ? null : note);
    }
    for (Map<String, Object> m :
        jdbc.queryForList(
            "SELECT m.id, a.id AS attachment_id, a.mime FROM gen_media m"
                + " JOIN page p ON p.id = m.page_id JOIN attachment a ON a.id = p.attachment_id"
                + " ORDER BY m.id")) {
      long id = ((Number) m.get("id")).longValue();
      if (!usedMedia.contains(id)) continue;
      g.record("O" + id, "OBJE");
      g.value(1, "FILE", FILE_BASE + m.get("attachment_id"));
      g.value(2, "FORM", m.get("mime") == null ? "image/jpeg" : (String) m.get("mime"));
    }
    g.tag(0, "TRLR");
    return g.toString();
  }

  private void event(
      Gedcom g,
      Map<String, Object> e,
      Map<Long, String> places,
      Map<String, List<Map<String, Object>>> citations,
      Set<Long> usedSources) {
    String tag = (String) e.get("type");
    String description = (String) e.get("description");
    String date = (String) e.get("date_text");
    Long place = e.get("place_id") == null ? null : ((Number) e.get("place_id")).longValue();
    if (ATTRIBUTES.contains(tag)) {
      if (description == null) {
        g.tag(1, tag);
      } else {
        g.value(1, tag, description);
      }
    } else if (tag.equals("EVEN")) {
      g.tag(1, "EVEN");
      g.value(2, "TYPE", description == null ? "event" : description);
    } else if (date == null && place == null && description == null) {
      g.value(1, tag, "Y");
    } else {
      g.tag(1, tag);
    }
    g.value(2, "DATE", date);
    g.value(2, "PLAC", place == null ? null : places.get(place));
    if (!ATTRIBUTES.contains(tag) && !tag.equals("EVEN")) g.value(2, "NOTE", description);
    cite(g, 2, citations.get("event_id:" + e.get("id")), usedSources);
  }

  private static void cite(
      Gedcom g, int level, List<Map<String, Object>> list, Set<Long> usedSources) {
    if (list == null) return;
    for (Map<String, Object> c : list) {
      long source = ((Number) c.get("source_id")).longValue();
      usedSources.add(source);
      g.pointer(level, "SOUR", "S" + source);
      String page = (String) c.get("detail");
      if (c.get("page_id") != null) {
        page = (page == null ? "" : page + "; ") + "archive page " + c.get("page_id");
      }
      g.value(level + 1, "PAGE", page);
      g.value(
          level + 1, "QUAY", c.get("quality") == null ? null : String.valueOf(c.get("quality")));
    }
  }

  private Map<Long, String> placeNames() {
    Map<Long, String> name = new HashMap<>();
    Map<Long, Long> parent = new HashMap<>();
    for (Map<String, Object> p : jdbc.queryForList("SELECT id, name, parent_id FROM gen_place")) {
      long id = ((Number) p.get("id")).longValue();
      name.put(id, (String) p.get("name"));
      if (p.get("parent_id") != null) parent.put(id, ((Number) p.get("parent_id")).longValue());
    }
    Map<Long, String> out = new HashMap<>();
    for (long id : name.keySet()) {
      List<String> parts = new ArrayList<>();
      Set<Long> seen = new HashSet<>();
      for (Long cur = id; cur != null && seen.add(cur); cur = parent.get(cur)) {
        parts.add(name.get(cur));
      }
      out.put(id, String.join(", ", parts));
    }
    return out;
  }

  private Map<String, List<Map<String, Object>>> citations() {
    Map<String, List<Map<String, Object>>> out = new HashMap<>();
    for (Map<String, Object> c : jdbc.queryForList("SELECT * FROM gen_citation ORDER BY id")) {
      for (String target : List.of("event_id", "name_id", "couple_id", "person_id")) {
        if (c.get(target) != null) {
          out.computeIfAbsent(target + ":" + c.get(target), k -> new ArrayList<>()).add(c);
        }
      }
    }
    return out;
  }

  private Map<Integer, List<Map<String, Object>>> byPerson(String sql) {
    Map<Integer, List<Map<String, Object>>> out = new HashMap<>();
    for (Map<String, Object> row : jdbc.queryForList(sql)) {
      out.computeIfAbsent((Integer) row.get("person_id"), k -> new ArrayList<>()).add(row);
    }
    return out;
  }

  private static Integer keep(Map<Integer, Map<String, Object>> people, Integer id) {
    return id != null && people.containsKey(id) ? id : null;
  }

  private static List<String> nonNull(String... values) {
    List<String> out = new ArrayList<>();
    for (String v : values) if (v != null && !v.isBlank()) out.add(v);
    return out;
  }

  /** GEDCOM 7 lines. A value line with no payload is left out; a newline continues with CONT. */
  private static final class Gedcom {
    private final StringBuilder out = new StringBuilder();

    void record(String xref, String tag) {
      out.append("0 @").append(xref).append("@ ").append(tag).append('\n');
    }

    void tag(int level, String tag) {
      emit(level, tag, null);
    }

    void pointer(int level, String tag, String xref) {
      out.append(level).append(' ').append(tag).append(" @").append(xref).append("@\n");
    }

    void value(int level, String tag, String payload) {
      if (payload == null || payload.isEmpty()) return;
      String[] parts = payload.split("\n", -1);
      emit(level, tag, parts[0]);
      for (int i = 1; i < parts.length; i++) emit(level + 1, "CONT", parts[i]);
    }

    private void emit(int level, String tag, String payload) {
      out.append(level).append(' ').append(tag);
      if (payload != null && !payload.isEmpty()) {
        out.append(' ').append(payload.startsWith("@") ? "@" + payload : payload);
      }
      out.append('\n');
    }

    @Override
    public String toString() {
      return out.toString();
    }
  }
}
