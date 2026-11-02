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

  private FamilyTreeGolden() {}

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
