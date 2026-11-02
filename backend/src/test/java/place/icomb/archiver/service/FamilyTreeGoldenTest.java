package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The in-memory tree's behaviour, pinned before the tree moves into the database. Task 6 replaces
 * this with {@code FamilyTreeReadModelTest}, which checks the same golden files against the
 * database-backed tree.
 */
class FamilyTreeGoldenTest {

  static FamilyTreeService tree;

  @BeforeAll
  static void load() {
    tree = new FamilyTreeService();
    tree.reload();
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOLDEN_WRITE", matches = "true")
  void writeGoldenFiles() throws Exception {
    FamilyTreeGolden.write("people.tsv", FamilyTreeGolden.people(tree));
    FamilyTreeGolden.write("kinship.tsv", FamilyTreeGolden.kinship(tree));
    FamilyTreeGolden.write("search.tsv", FamilyTreeGolden.search(tree));
  }

  @Test
  void theTreeHas346People() {
    assertThat(tree.getAllPeople()).hasSize(346);
  }

  @Test
  @DisabledIfEnvironmentVariable(named = "GOLDEN_WRITE", matches = "true")
  void peopleMatchTheGoldenFile() throws Exception {
    FamilyTreeGolden.assertPeopleMatch(
        FamilyTreeGolden.people(tree), FamilyTreeGolden.read("people.tsv"));
  }

  @Test
  @DisabledIfEnvironmentVariable(named = "GOLDEN_WRITE", matches = "true")
  void kinshipMatchesTheGoldenFile() throws Exception {
    assertThat(FamilyTreeGolden.kinship(tree)).isEqualTo(FamilyTreeGolden.read("kinship.tsv"));
  }

  @Test
  @DisabledIfEnvironmentVariable(named = "GOLDEN_WRITE", matches = "true")
  void searchMatchesTheGoldenFile() throws Exception {
    assertThat(FamilyTreeGolden.search(tree)).isEqualTo(FamilyTreeGolden.read("search.tsv"));
  }
}
