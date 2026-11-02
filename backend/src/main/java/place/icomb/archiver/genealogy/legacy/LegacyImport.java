package place.icomb.archiver.genealogy.legacy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import place.icomb.archiver.genealogy.GedcomDate;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Adoption;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.AdoptionKind;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Descriptor;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Event;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Marriage;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Spouse;
import place.icomb.archiver.genealogy.legacy.LegacyTreeParser.LegacyPerson;
import place.icomb.archiver.genealogy.legacy.LegacyTreeParser.LegacyTree;

/**
 * Writes the legacy genealogy file into the {@code gen_*} tables, once, as migration V22.
 *
 * <p>Ids 1-348 are the parse-order ids of {@link LegacyTreeParser}; spouses, who were text inside a
 * line, become people numbered from 349. Every name and event cites the legacy file at quality 1,
 * and whatever the notation leaves unsettled is written to {@code needs_review}.
 */
public final class LegacyImport {

  public static final String SOURCE_TITLE = "genealogy.euweb.cz Czernin pages (legacy text file)";
  public static final String SOURCE_URL = "https://genealogy.euweb.cz/bohemia/czernin1.html";

  /** Nobody born before 1926 is taken to be living; the file records no deaths after 2006. */
  static final int FIRST_LIVING_BIRTH_YEAR = 1926;

  public static final String FAMILY_SURNAME = "Czernin";
  private static final Set<String> FAMILY_TITLES =
      Set.of("Graf", "Gf", "Freiherr", "Frhr", "Fürst", "Fst", "Prinz", "Pr");

  private final JdbcTemplate jdbc;
  private final Map<String, Long> places = new HashMap<>();
  private final Map<Integer, List<String>> review = new LinkedHashMap<>();
  private final Map<Integer, Map<Integer, Long>> couplesByOrder = new HashMap<>();
  private final Map<Long, Integer> spouseOfCouple = new HashMap<>();
  private final Map<Integer, Map<Integer, Integer>> spouseByOrder = new HashMap<>();
  private final Map<String, Integer> eventOrder = new HashMap<>();
  private final Map<Integer, String> surnames = new HashMap<>();
  private final Map<Integer, List<String>> otherSurnames = new HashMap<>();
  private final Map<Integer, List<String>> spouseSurnames = new HashMap<>();
  private long sourceId;

  public LegacyImport(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void run(List<String> lines) {
    LegacyTree tree = LegacyTreeParser.parse(lines);
    sourceId =
        jdbc.queryForObject(
            "INSERT INTO gen_source (title, url, notes) VALUES (?, ?, ?) RETURNING id",
            Long.class,
            SOURCE_TITLE,
            SOURCE_URL,
            "Imported by migration V22 from czernin-genealogy.txt; each person's line is kept"
                + " in gen_person.legacy_entry.");

    Map<Integer, LegacyFacts> facts = new LinkedHashMap<>();
    Set<String> forenames = forenames(tree);
    for (LegacyPerson p : tree.people()) {
      LegacyFacts f = LegacyLineParser.parse(p.fullEntry);
      facts.put(p.id, f);
      String[] name = familyName(p, forenames);
      surnames.put(p.id, name[2]);
      otherSurnames.put(p.id, f.otherSurnames());
      jdbc.update(
          "INSERT INTO gen_person (id, legacy_entry, legacy_section, legacy_code, legacy_depth,"
              + " notes) VALUES (?, ?, ?, ?, ?, ?)",
          p.id,
          p.fullEntry,
          p.section,
          p.fullCode(),
          p.depth,
          f.notes().isEmpty() ? null : String.join("\n", f.notes()));
      long nameId =
          jdbc.queryForObject(
              "INSERT INTO gen_name (person_id, type, prefix, given, surname, nickname,"
                  + " is_primary) VALUES (?, 'birth', ?, ?, ?, ?, true) RETURNING id",
              Long.class,
              p.id,
              name[0],
              name[1],
              name[2],
              f.nickname());
      cite("name_id", nameId);
      if (tree.mergedInto().containsKey(p.id)) {
        continue;
      }
      review.computeIfAbsent(p.id, k -> new ArrayList<>()).addAll(f.review());
      for (String surname : f.otherSurnames()) {
        long other =
            jdbc.queryForObject(
                "INSERT INTO gen_name (person_id, type, given, surname, sort_order)"
                    + " VALUES (?, 'other', ?, ?, 1) RETURNING id",
                Long.class,
                p.id,
                p.name,
                surname);
        cite("name_id", other);
      }
      personEvents(p.id, f.birth(), f.death(), f.burial(), f.descriptors());
      for (Adoption a : f.adoptions()) {
        if (a.kind() == AdoptionKind.ADOPTED) {
          event(p.id, null, "ADOP", new Event(a.date(), null, a.text()));
        }
      }
      marriages(p.id, f.marriages());
    }

    for (LegacyPerson child : tree.people()) {
      if (tree.mergedInto().containsKey(child.id) || child.parent == null) continue;
      LegacyPerson parent = child.parent;
      Integer order =
          child.marriageHint != null ? child.marriageHint : facts.get(parent.id).allChildrenBy();
      Map<Integer, Long> couples = couplesByOrder.getOrDefault(parent.id, Map.of());
      Set<Long> distinct = new LinkedHashSet<>(couples.values());
      Long couple = null;
      if (order != null) {
        couple = couples.get(order);
        if (couple == null) {
          flag(child.id, "child of marriage " + order + ", which has no recorded spouse");
        }
      } else if (distinct.size() == 1) {
        couple = distinct.iterator().next();
      } else if (distinct.size() > 1) {
        flag(parent.id, "which marriage each child is from is not recorded");
      }
      link(parent.id, child.id, "birth", couple);
      if (couple != null) {
        link(spouseOfCouple.get(couple), child.id, "birth", couple);
      }
    }

    adoptions(tree, facts);
    conflicts(tree, facts);

    for (var e : review.entrySet()) {
      if (!e.getValue().isEmpty()) {
        jdbc.update(
            "UPDATE gen_person SET needs_review = ? WHERE id = ?",
            String.join("; ", new LinkedHashSet<>(e.getValue())),
            e.getKey());
      }
    }
    for (var e : tree.mergedInto().entrySet()) {
      jdbc.update("UPDATE gen_person SET merged_into = ? WHERE id = ?", e.getValue(), e.getKey());
    }
    jdbc.update(
        """
        UPDATE gen_person p SET living = true
        WHERE p.merged_into IS NULL
          AND NOT EXISTS (SELECT 1 FROM gen_event d WHERE d.person_id = p.id AND d.type = 'DEAT')
          AND EXISTS (SELECT 1 FROM gen_event b WHERE b.person_id = p.id AND b.type = 'BIRT'
                      AND b.date_from >= make_date(?, 1, 1))
        """,
        FIRST_LIVING_BIRTH_YEAR);
    jdbc.execute("SELECT setval('gen_person_id_seq', (SELECT max(id) FROM gen_person))");
  }

  /**
   * Prefix, given names and surname of a family member's primary name. The family surname is
   * Czernin: a legacy name that already holds a Czernin form ("Humprecht Czernin z Chudenic", "…
   * Czernin-Kinsky (adopted 1954)") is split at it, titles before it going to the prefix and a
   * trailing remark in parentheses dropped. A descendant through a daughter whose legacy name
   * already ends in the father's line's surname ("Charlotte Sonia Phillips") keeps that surname.
   * Every other legacy name is the given names, and the surname is Czernin.
   */
  private String[] familyName(LegacyPerson p, Set<String> forenames) {
    String name = p.name;
    int czernin = name.indexOf("Czernin");
    if (czernin >= 0) {
      String surname = name.substring(czernin).replaceAll("(\\s*\\([^)]*\\))+\\s*$", "").trim();
      List<String> prefix = new ArrayList<>();
      List<String> given = new ArrayList<>();
      for (String word : name.substring(0, czernin).trim().split("\\s+")) {
        if (word.isEmpty()) continue;
        (FAMILY_TITLES.contains(word) ? prefix : given).add(word);
      }
      return new String[] {
        prefix.isEmpty() ? null : String.join(" ", prefix),
        given.isEmpty() ? null : String.join(" ", given),
        surname
      };
    }
    String[] words = name.split("\\s+");
    String last = words[words.length - 1];
    if (words.length > 1 && !forenames.contains(last) && p.parent != null && inherits(p, last)) {
      return new String[] {null, name.substring(0, name.lastIndexOf(last)).trim(), last};
    }
    return new String[] {null, name, FAMILY_SURNAME};
  }

  private boolean inherits(LegacyPerson p, String surname) {
    int parent = p.parent.id;
    return (surname.equals(surnames.get(parent)) && !FAMILY_SURNAME.equals(surname))
        || spouseSurnames.getOrDefault(parent, List.of()).contains(surname)
        || otherSurnames.getOrDefault(parent, List.of()).contains(surname);
  }

  /** Every word used as a forename: all but the last of each legacy name, and one-word names. */
  private static Set<String> forenames(LegacyTree tree) {
    Set<String> out = new HashSet<>();
    for (LegacyPerson p : tree.people()) {
      String[] words = p.name.split("\\s+");
      out.addAll(Arrays.asList(words).subList(0, Math.max(1, words.length - 1)));
    }
    return out;
  }

  private void personEvents(
      int personId, Event birth, Event death, Event burial, List<Descriptor> descriptors) {
    event(personId, null, "BIRT", birth);
    event(personId, null, "DEAT", death);
    event(personId, null, "BURI", burial);
    for (Descriptor d : descriptors) {
      event(personId, null, d.tag(), new Event(d.date(), null, d.text()));
    }
  }

  private void marriages(int personId, List<Marriage> marriages) {
    Map<Integer, Long> couples = couplesByOrder.computeIfAbsent(personId, k -> new HashMap<>());
    Map<Integer, Integer> spouses = spouseByOrder.computeIfAbsent(personId, k -> new HashMap<>());
    for (Marriage m : marriages) {
      Long couple;
      if (m.sameAsOrder() != null && couples.containsKey(m.sameAsOrder())) {
        couple = couples.get(m.sameAsOrder());
        spouses.put(m.order(), spouses.get(m.sameAsOrder()));
      } else if (m.spouse() == null) {
        Event w = m.wedding();
        String date = w == null || w.date() == null ? "-" : w.date();
        String place = w == null || w.place() == null ? "-" : w.place();
        flag(
            personId,
            "marriage "
                + m.order()
                + " has no spouse name (date: "
                + date
                + ", place: "
                + place
                + ")");
        continue;
      } else {
        int spouseId = spouse(m.spouse());
        spouseSurnames
            .computeIfAbsent(personId, k -> new ArrayList<>())
            .add(m.spouse().name().surname());
        couple =
            jdbc.queryForObject(
                "INSERT INTO gen_couple (person1_id, person2_id, type, sort_order)"
                    + " VALUES (?, ?, 'marriage', ?) RETURNING id",
                Long.class,
                personId,
                spouseId,
                m.order());
        spouseOfCouple.put(couple, spouseId);
        spouses.put(m.order(), spouseId);
      }
      couples.put(m.order(), couple);
      event(null, couple, "MARR", m.wedding());
      if (m.endTag() != null) {
        event(null, couple, m.endTag(), m.end() == null ? new Event(null, null, null) : m.end());
      }
    }
  }

  private int spouse(Spouse s) {
    int id =
        jdbc.queryForObject("INSERT INTO gen_person DEFAULT VALUES RETURNING id", Integer.class);
    long nameId =
        jdbc.queryForObject(
            "INSERT INTO gen_name (person_id, type, prefix, given, surname, nickname, is_primary)"
                + " VALUES (?, 'birth', ?, ?, ?, ?, true) RETURNING id",
            Long.class,
            id,
            s.name().prefix(),
            s.name().given(),
            s.name().surname(),
            s.name().nickname());
    cite("name_id", nameId);
    personEvents(id, s.birth(), s.death(), null, s.descriptors());
    review.computeIfAbsent(id, k -> new ArrayList<>()).addAll(s.review());
    return id;
  }

  private void adoptions(LegacyTree tree, Map<Integer, LegacyFacts> facts) {
    Map<Integer, String> names = new HashMap<>();
    for (LegacyPerson p : tree.people()) names.put(p.id, p.name);
    List<Map.Entry<Integer, Adoption>> adopters = new ArrayList<>();
    List<Map.Entry<Integer, Adoption>> adoptees = new ArrayList<>();
    for (var e : facts.entrySet()) {
      if (tree.mergedInto().containsKey(e.getKey())) continue;
      for (Adoption a : e.getValue().adoptions()) {
        if (a.kind() == AdoptionKind.ADOPTED_CHILD) adopters.add(Map.entry(e.getKey(), a));
        if (a.kind() == AdoptionKind.ADOPTED_BY) adoptees.add(Map.entry(e.getKey(), a));
      }
    }
    for (var adopter : adopters) {
      Map.Entry<Integer, Adoption> match = null;
      for (var adoptee : adoptees) {
        boolean sameYear = Objects.equals(adopter.getValue().date(), adoptee.getValue().date());
        boolean namesAgree =
            firstWord(adopter.getValue().otherName()).equals(firstWord(names.get(adoptee.getKey())))
                && firstWord(adoptee.getValue().otherName())
                    .equals(firstWord(names.get(adopter.getKey())));
        if (sameYear && namesAgree) {
          match = adoptee;
          break;
        }
      }
      if (match != null) {
        adoptees.remove(match);
        link(adopter.getKey(), match.getKey(), "adopted", null);
        event(
            match.getKey(),
            null,
            "ADOP",
            new Event(match.getValue().date(), null, match.getValue().text()));
      } else {
        event(
            adopter.getKey(),
            null,
            "EVEN",
            new Event(adopter.getValue().date(), null, adopter.getValue().text()));
        flag(adopter.getKey(), "adopted child not found in the tree");
      }
    }
    for (var adoptee : adoptees) {
      event(
          adoptee.getKey(),
          null,
          "ADOP",
          new Event(adoptee.getValue().date(), null, adoptee.getValue().text()));
    }
  }

  /** The folded section roots repeat their twin's line; where the two disagree, say so. */
  private void conflicts(LegacyTree tree, Map<Integer, LegacyFacts> facts) {
    for (var e : tree.mergedInto().entrySet()) {
      LegacyFacts root = facts.get(e.getKey());
      LegacyFacts twin = facts.get(e.getValue());
      String where = "CZERNIN header line vs person " + e.getValue();
      compare(e.getValue(), "birth", root.birth(), twin.birth(), where);
      compare(e.getValue(), "death", root.death(), twin.death(), where);
      Map<Integer, Integer> spouses = spouseByOrder.getOrDefault(e.getValue(), Map.of());
      for (Marriage rm : root.marriages()) {
        for (Marriage tm : twin.marriages()) {
          if (rm.order() != tm.order() || rm.spouse() == null || tm.spouse() == null) continue;
          Integer spouseId = spouses.get(tm.order());
          compare(spouseId, "birth", rm.spouse().birth(), tm.spouse().birth(), where);
          compare(spouseId, "death", rm.spouse().death(), tm.spouse().death(), where);
        }
      }
    }
  }

  private void compare(Integer personId, String what, Event header, Event line, String where) {
    String a = header == null ? null : header.date();
    String b = line == null ? null : line.date();
    if (personId != null && !Objects.equals(a, b)) {
      flag(personId, "conflicting %s dates: %s vs %s (%s)".formatted(what, a, b, where));
    }
  }

  private void event(Integer personId, Long coupleId, String tag, Event e) {
    if (e == null) return;
    GedcomDate date = GedcomDate.parse(e.date());
    String key = personId != null ? "p" + personId : "c" + coupleId;
    int order = eventOrder.merge(key, 1, Integer::sum);
    long id =
        jdbc.queryForObject(
            "INSERT INTO gen_event (person_id, couple_id, type, date_text, date_from, date_to,"
                + " place_id, description, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " RETURNING id",
            Long.class,
            personId,
            coupleId,
            tag,
            date == null ? null : date.text(),
            date == null ? null : date.from(),
            date == null ? null : date.to(),
            place(e.place()),
            e.description(),
            order);
    cite("event_id", id);
  }

  private Long place(String name) {
    if (name == null) return null;
    return places.computeIfAbsent(
        name,
        n ->
            jdbc.queryForObject(
                "INSERT INTO gen_place (name) VALUES (?) RETURNING id", Long.class, n));
  }

  private void link(int parentId, int childId, String type, Long coupleId) {
    jdbc.update(
        "INSERT INTO gen_parent_child (parent_id, child_id, type, couple_id) VALUES (?, ?, ?, ?)"
            + " ON CONFLICT (parent_id, child_id) DO NOTHING",
        parentId,
        childId,
        type,
        coupleId);
  }

  private void cite(String column, long id) {
    jdbc.update(
        "INSERT INTO gen_citation (source_id, quality, " + column + ") VALUES (?, 1, ?)",
        sourceId,
        id);
  }

  private void flag(int personId, String reason) {
    review.computeIfAbsent(personId, k -> new ArrayList<>()).add(reason);
  }

  private static String firstWord(String s) {
    return s == null ? "" : s.trim().split("\\s+")[0];
  }
}
