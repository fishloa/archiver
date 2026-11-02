package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import place.icomb.archiver.service.FamilyTreeService.Person;
import place.icomb.archiver.service.FamilyTreeService.RelationshipResult;

/**
 * The family tree's observable behaviour, written out as text so it can be compared before and
 * after the tree moved from the text file into the database.
 *
 * <p>Captured once from the in-memory tree (2026-11-02) and checked into {@code
 * src/test/resources/genealogy/golden}. Rewrite them only with {@code GOLDEN_WRITE=true}, and only
 * from the tree they are meant to describe.
 */
public final class FamilyTreeGolden {

  static final Path DIR = Path.of("src/test/resources/genealogy/golden");

  static final List<Integer> REFERENCE_PEOPLE = List.of(1, 76, 128, 324);

  static final int ALEXANDER = 322;

  static final List<String> QUERIES =
      List.of(
          "alexander",
          "czernin",
          "rudolf",
          "jaromir",
          "theobald",
          "wolfgang maria",
          "eugen",
          "diana",
          "humprecht",
          "dymokury",
          "Děpolt",
          "lobkowicz");

  /**
   * People whose legacy birth place was an artefact of the old parser rather than a place: 91's
   * "1651/after 1660/1666" left "/after", and 200's "*and +Klagenfurt" left "and +". The database
   * gives 91 no place and 200 Klagenfurt.
   */
  static final Map<Integer, String> BIRTH_PLACE_EXCEPTIONS =
      Map.of(91, "legacy '/after' artefact", 200, "legacy 'and +' artefact");

  /**
   * Descendants through a daughter whose legacy name already ends in their father's line's surname
   * (Phillips, Beckett): the database keeps that surname instead of adding Czernin.
   */
  static final Set<Integer> INHERITED_SURNAME_IDS = Set.of(277, 278, 289, 290, 291, 292, 293, 294);

  private FamilyTreeGolden() {}

  /**
   * The name the database shows for a legacy person: the legacy name plus the family surname
   * Czernin. A name already holding a Czernin form is shown as it was, less a trailing remark in
   * parentheses; so is a name already ending in an inherited surname. This is the only intended
   * difference between the in-memory tree and the database tree.
   */
  public static String expectedDisplay(int id, String legacyName) {
    if (legacyName.contains("Czernin") || INHERITED_SURNAME_IDS.contains(id)) {
      return legacyName.replaceAll("(\\s*\\([^)]*\\))+\\s*$", "").trim();
    }
    return legacyName + " Czernin";
  }

  /** {@link #assertPeopleMatch}, with the name column compared as {@link #expectedDisplay}. */
  static void assertPeopleMatchAfterMove(List<String> actual, List<String> golden) {
    List<String> expected = new ArrayList<>();
    for (String line : golden) {
      String[] c = line.split("\t", -1);
      c[8] = expectedDisplay(Integer.parseInt(c[0]), c[8]);
      expected.add(String.join("\t", c));
    }
    assertPeopleMatch(actual, expected);
  }

  /**
   * Labels, ids and steps exactly as captured; every name, including those inside the path
   * description, as {@link #expectedDisplay}.
   */
  static void assertKinshipMatchAfterMove(
      List<String> actual, List<String> golden, List<String> people) {
    Map<Integer, String> names = new java.util.HashMap<>();
    Map<Integer, Integer> parents = new java.util.HashMap<>();
    for (String line : people) {
      String[] c = line.split("\t", -1);
      names.put(Integer.parseInt(c[0]), c[8]);
      if (!c[1].equals("-")) parents.put(Integer.parseInt(c[0]), Integer.parseInt(c[1]));
    }
    assertThat(actual).hasSameSizeAs(golden);
    for (int i = 0; i < golden.size(); i++) {
      String[] want = golden.get(i).split("\t", -1);
      if (want.length == 3) {
        assertThat(actual.get(i)).isEqualTo(golden.get(i));
        continue;
      }
      String[] got = actual.get(i).split("\t", -1);
      int person = Integer.parseInt(want[0]);
      int ancestor = Integer.parseInt(want[5]);
      int ref = want[1].equals("default") ? ALEXANDER : Integer.parseInt(want[1]);
      String row = "kinship row " + golden.get(i);
      for (int col : new int[] {0, 1, 5, 6, 7}) {
        assertThat(got[col]).as(row).isEqualTo(want[col]);
      }
      String label = person == ref ? "This is " + expectedDisplay(ref, names.get(ref)) : want[2];
      assertThat(got[2]).as(row).isEqualTo(label);
      assertThat(got[8]).as(row).isEqualTo(expectedDisplay(person, names.get(person)));
      assertThat(got[9]).as(row).isEqualTo(expectedDisplay(ref, names.get(ref)));
      assertThat(got[4]).as(row).isEqualTo(expectedDisplay(ancestor, names.get(ancestor)));
      String path;
      if (person == ref) {
        path = ref == ALEXANDER ? want[3] : label;
      } else {
        List<Integer> involved = new ArrayList<>(List.of(person, ref, ancestor));
        for (int from : new int[] {person, ref}) {
          if (from == ancestor) continue;
          Integer cur = from;
          while (cur != null && parents.get(cur) != null && parents.get(cur) != ancestor) {
            cur = parents.get(cur);
          }
          if (cur != null && cur != ancestor) involved.add(cur);
        }
        path = renamed(want[3], involved, names);
      }
      assertThat(got[3]).as(row).isEqualTo(path);
    }
  }

  /** Each involved person's legacy name in the text, longest first, as its display name. */
  private static String renamed(String text, List<Integer> ids, Map<Integer, String> names) {
    List<Integer> order =
        ids.stream()
            .distinct()
            .sorted(Comparator.comparingInt((Integer id) -> names.get(id).length()).reversed())
            .toList();
    String out = text;
    for (int k = 0; k < order.size(); k++) {
      out = out.replace(names.get(order.get(k)), "\u0000" + k + "\u0001");
    }
    for (int k = 0; k < order.size(); k++) {
      int id = order.get(k);
      out = out.replace("\u0000" + k + "\u0001", expectedDisplay(id, names.get(id)));
    }
    return out;
  }

  /**
   * Search results as captured, names as {@link #expectedDisplay}. Queries containing "czernin" are
   * left out: every family member now carries the surname, so they rank differently by design.
   */
  static void assertSearchMatchAfterMove(List<String> actual, List<String> golden) {
    List<String> want = new ArrayList<>();
    for (String line : golden) {
      String[] c = line.split("\t", -1);
      if (c[0].contains("czernin")) continue;
      c[3] = expectedDisplay(Integer.parseInt(c[2]), c[3]);
      want.add(String.join("\t", c));
    }
    List<String> got =
        actual.stream().filter(l -> !l.split("\t", -1)[0].contains("czernin")).toList();
    assertThat(got).isEqualTo(want);
  }

  static List<String> people(FamilyTreeService tree) {
    List<String> out = new ArrayList<>();
    for (Person p : sorted(tree.getAllPeople())) {
      List<String> childIds = new ArrayList<>();
      for (Person c : p.children) childIds.add(String.valueOf(c.id));
      out.add(
          String.join(
              "\t",
              String.valueOf(p.id),
              p.parent == null ? "-" : String.valueOf(p.parent.id),
              String.valueOf(p.section),
              p.fullCode(),
              String.valueOf(p.depth),
              String.valueOf(p.birthYear),
              String.valueOf(p.deathYear),
              String.valueOf(p.birthPlace),
              p.name,
              p.fullEntry,
              String.join(",", childIds)));
    }
    return out;
  }

  static List<String> kinship(FamilyTreeService tree) {
    List<String> out = new ArrayList<>();
    for (Person p : sorted(tree.getAllPeople())) {
      out.add(row(p.id, "default", tree.relate(p.id)));
      for (int ref : REFERENCE_PEOPLE) {
        out.add(row(p.id, String.valueOf(ref), tree.relate(p.id, ref)));
      }
    }
    return out;
  }

  static List<String> search(FamilyTreeService tree) {
    List<String> out = new ArrayList<>();
    for (String q : QUERIES) {
      int rank = 1;
      for (var r : tree.search(q, 10)) {
        out.add(
            String.join(
                "\t",
                q,
                String.valueOf(rank++),
                String.valueOf(r.personId()),
                r.name(),
                String.format(Locale.ROOT, "%.6f", r.score()),
                String.valueOf(r.birthYear()),
                String.valueOf(r.deathYear()),
                r.section(),
                r.code()));
      }
    }
    return out;
  }

  public static List<String> read(String name) throws IOException {
    try (InputStream in = FamilyTreeGolden.class.getResourceAsStream("/genealogy/golden/" + name)) {
      assertThat(in).as("golden file " + name).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
  }

  static void write(String name, List<String> lines) throws IOException {
    Files.createDirectories(DIR);
    Files.writeString(DIR.resolve(name), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
  }

  /**
   * Every column must match exactly except the birth place, which must match as {@code
   * PersonMatchService} reads it: the text before the first comma or plus, normalised, and only
   * when four or more characters long.
   */
  static void assertPeopleMatch(List<String> actual, List<String> golden) {
    assertThat(actual).hasSameSizeAs(golden);
    for (int i = 0; i < golden.size(); i++) {
      String[] want = golden.get(i).split("\t", -1);
      String[] got = actual.get(i).split("\t", -1);
      int id = Integer.parseInt(want[0]);
      for (int col = 0; col < want.length; col++) {
        if (col == 7) {
          if (!BIRTH_PLACE_EXCEPTIONS.containsKey(id)) {
            assertThat(placeKey(got[7])).as("birth place of " + id).isEqualTo(placeKey(want[7]));
          }
        } else {
          assertThat(got[col]).as("column " + col + " of person " + id).isEqualTo(want[col]);
        }
      }
    }
  }

  /**
   * The in-memory tree's people exactly as it held them, birth places included, rebuilt from {@code
   * people.tsv}: the input person matching was tuned on.
   */
  static List<Person> legacyPeople() throws IOException {
    Map<Integer, Person> byId = new java.util.LinkedHashMap<>();
    Map<Integer, Integer> parentOf = new java.util.HashMap<>();
    for (String line : read("people.tsv")) {
      String[] c = line.split("\t", -1);
      Person p = new Person();
      p.id = Integer.parseInt(c[0]);
      p.section = c[2];
      p.code = c[3].isEmpty() ? null : c[3];
      p.depth = Integer.parseInt(c[4]);
      p.birthYear = c[5].equals("null") ? null : Integer.valueOf(c[5]);
      p.deathYear = c[6].equals("null") ? null : Integer.valueOf(c[6]);
      p.birthPlace = c[7].equals("null") ? null : c[7];
      p.name = c[8];
      p.fullEntry = c[9];
      p.children = new ArrayList<>();
      p.spouses = new ArrayList<>();
      byId.put(p.id, p);
      if (!c[1].equals("-")) parentOf.put(p.id, Integer.parseInt(c[1]));
    }
    for (Person p : byId.values()) {
      Integer parent = parentOf.get(p.id);
      if (parent != null) {
        p.parent = byId.get(parent);
        p.parent.children.add(p);
      }
    }
    return List.copyOf(byId.values());
  }

  static String placeKey(String place) {
    if (place == null || place.equals("null") || place.isBlank()) return null;
    String first = place.split("[,+]")[0].trim();
    String norm =
        Normalizer.normalize(first, Normalizer.Form.NFD).replaceAll("\\p{Mn}", "").toLowerCase();
    return norm.length() >= 4 ? norm : null;
  }

  private static String row(int personId, String ref, RelationshipResult r) {
    if (r == null) return personId + "\t" + ref + "\tnull";
    return String.join(
        "\t",
        String.valueOf(personId),
        ref,
        r.kinshipLabel(),
        r.pathDescription(),
        String.valueOf(r.commonAncestorName()),
        String.valueOf(r.commonAncestorId()),
        String.valueOf(r.stepsFromPerson()),
        String.valueOf(r.stepsFromRef()),
        r.personName(),
        r.refPersonName());
  }

  private static List<Person> sorted(List<Person> people) {
    return people.stream().sorted(Comparator.comparingInt(p -> p.id)).toList();
  }
}
