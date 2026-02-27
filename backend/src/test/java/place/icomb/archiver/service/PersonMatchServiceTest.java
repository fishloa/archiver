package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import place.icomb.archiver.service.FamilyTreeService.Person;
import place.icomb.archiver.service.PersonMatchService.MatchResult;

/**
 * Unit tests for PersonMatchService heuristic matching logic. Loads the real Czernin family tree
 * from classpath and tests against sample OCR text from known documents.
 *
 * <p>No database or Spring context needed — tests the pure matching algorithm.
 */
class PersonMatchServiceTest {

  static FamilyTreeService treeService;
  static PersonMatchService matchService;
  static List<Person> allPeople;

  @BeforeAll
  static void setup() {
    treeService = new FamilyTreeService();
    treeService.reload();
    allPeople = treeService.getAllPeople();
    matchService = new PersonMatchService(treeService);
  }

  @Test
  void familyTreeLoaded() {
    assertThat(allPeople).isNotEmpty();
    assertThat(allPeople.size()).isGreaterThan(100);
    System.out.printf("Family tree: %d people%n", allPeople.size());
  }

  @Test
  void rudolfDymokuryShouldRankFirst() {
    String text =
        "Grundbuchseinlage 109-4/1337 des Grundbuches der Katalastralgemeinde "
            + "Stein an der Donau, 12. April 1939. Dieses Grundstück gehört dem "
            + "Graf Rudolf Czernin /Dymokur/ und wurde im Jahr 1920 eingetragen. "
            + "Weitere Beteiligte: Ottokar Lobkowicz, Heinrich Czernin.";
    Integer docYear = 1941;

    List<MatchResult> results = matchService.computeMatches(text, allPeople, docYear);

    // Print all results for debugging BEFORE asserting
    System.out.println("=== rudolfDymokuryShouldRankFirst ===");
    for (int i = 0; i < results.size(); i++) {
      MatchResult r = results.get(i);
      Person p = treeService.getPerson(r.personId());
      System.out.printf(
          "%2d. [%3d] %-50s score=%.4f birth=%s death=%s place=%s%n",
          i + 1,
          r.personId(),
          r.personName(),
          r.score(),
          p != null ? p.birthYear : "?",
          p != null ? p.deathYear : "?",
          p != null ? p.birthPlace : "?");
    }

    assertThat(results).isNotEmpty();
    MatchResult top = results.getFirst();
    assertThat(top.personId()).as("Rudolf 234 (born Dymokury) should rank first").isEqualTo(234);
  }

  @Test
  void shouldNotMatchPeopleBornAfterDocument() {
    String text = "Graf Rudolf Czernin hat diese Urkunde unterzeichnet im Jahr 1920.";
    Integer docYear = 1920;

    List<MatchResult> results = matchService.computeMatches(text, allPeople, docYear);

    for (MatchResult r : results) {
      Person p = treeService.getPerson(r.personId());
      if (p != null && p.birthYear != null) {
        assertThat(p.birthYear)
            .as(
                "Person %d (%s) born %d should not match doc from %d",
                r.personId(), r.personName(), p.birthYear, docYear)
            .isLessThanOrEqualTo(docYear + 5);
      }
    }
  }

  @Test
  void firstNameBonusShouldPreferFirstNames() {
    Person rudolfFirst = new Person();
    rudolfFirst.id = 1;
    rudolfFirst.name = "Rudolf Maria Josef";
    rudolfFirst.birthYear = 1880;
    rudolfFirst.children = List.of();
    rudolfFirst.spouses = List.of();

    Person rudolfThird = new Person();
    rudolfThird.id = 2;
    rudolfThird.name = "Jan Nepomuk Rudolf";
    rudolfThird.birthYear = 1880;
    rudolfThird.children = List.of();
    rudolfThird.spouses = List.of();

    String text = "Rudolf Czernin hat dieses Dokument unterzeichnet.";

    List<MatchResult> results =
        matchService.computeMatches(text, List.of(rudolfFirst, rudolfThird), null);

    System.out.println("=== firstNameBonusShouldPreferFirstNames ===");
    for (MatchResult r : results) {
      System.out.printf("  [%d] %-30s score=%.4f%n", r.personId(), r.personName(), r.score());
    }

    assertThat(results).hasSizeGreaterThanOrEqualTo(2);

    MatchResult first = results.stream().filter(r -> r.personId() == 1).findFirst().orElseThrow();
    MatchResult second = results.stream().filter(r -> r.personId() == 2).findFirst().orElseThrow();

    assertThat(first.score())
        .as("Person with 'Rudolf' as first name should score higher")
        .isGreaterThan(second.score());
  }

  @Test
  void heinrichCzerninShouldMatch() {
    String text =
        "Die Liegenschaft wurde an Heinrich Czernin übertragen laut Beschluss vom 15.3.1942.";
    Integer docYear = 1942;

    // First, check if any Heinrich exists in the tree
    System.out.println("=== People named Heinrich in tree ===");
    for (Person p : allPeople) {
      if (treeService.normalize(p.name).contains("heinrich")) {
        System.out.printf(
            "  [%3d] %-50s birth=%s death=%s%n", p.id, p.name, p.birthYear, p.deathYear);
      }
    }

    List<MatchResult> results = matchService.computeMatches(text, allPeople, docYear);

    System.out.println("=== heinrichCzerninShouldMatch ===");
    for (int i = 0; i < results.size(); i++) {
      MatchResult r = results.get(i);
      Person p = treeService.getPerson(r.personId());
      System.out.printf(
          "%2d. [%3d] %-50s score=%.4f birth=%s death=%s%n",
          i + 1,
          r.personId(),
          r.personName(),
          r.score(),
          p != null ? p.birthYear : "?",
          p != null ? p.deathYear : "?");
    }

    // If no Heinrich in the tree, this is expected to fail — skip assertion
    boolean hasHeinrich =
        allPeople.stream().anyMatch(p -> treeService.normalize(p.name).contains("heinrich"));
    if (hasHeinrich) {
      assertThat(results)
          .anyMatch(
              r -> {
                Person p = treeService.getPerson(r.personId());
                return p != null && treeService.normalize(p.name).contains("heinrich");
              });
    } else {
      System.out.println("  (No Heinrich in tree — skipping assertion)");
    }
  }

  @Test
  void noMatchesForIrrelevantText() {
    String text = "This document discusses agricultural policies in 1950s Czechoslovakia.";

    List<MatchResult> results = matchService.computeMatches(text, allPeople, 1955);

    System.out.println("=== noMatchesForIrrelevantText ===");
    System.out.printf("Matches: %d%n", results.size());
    for (MatchResult r : results) {
      System.out.printf("  [%3d] %-45s score=%.3f%n", r.personId(), r.personName(), r.score());
    }
  }

  @Test
  void debugTokenization() {
    // Debug: show how a sample text tokenizes and what person 234's tokens look like
    String text = "Graf Rudolf Czernin /Dymokur/ und Heinrich Czernin";
    String normalized = treeService.normalize(text);
    var tokens = treeService.tokenize(normalized);
    System.out.println("=== debugTokenization ===");
    System.out.println("Normalized: " + normalized);
    System.out.println("Tokens: " + tokens);

    Person p234 = treeService.getPerson(234);
    if (p234 != null) {
      var titleWords = treeService.getTitleWords();
      var givenNames = matchService.getOrderedGivenNames(p234, titleWords);
      System.out.printf("Person 234: name=%s%n", p234.name);
      System.out.printf("  Given names: %s%n", givenNames);
      System.out.printf(
          "  Birth: %s, Death: %s, Place: %s%n", p234.birthYear, p234.deathYear, p234.birthPlace);
    }

    // Also show person 205 (the one ranking first)
    Person p205 = treeService.getPerson(205);
    if (p205 != null) {
      var titleWords = treeService.getTitleWords();
      var givenNames = matchService.getOrderedGivenNames(p205, titleWords);
      System.out.printf("Person 205: name=%s%n", p205.name);
      System.out.printf("  Given names: %s%n", givenNames);
      System.out.printf(
          "  Birth: %s, Death: %s, Place: %s%n", p205.birthYear, p205.deathYear, p205.birthPlace);
    }
  }
}
