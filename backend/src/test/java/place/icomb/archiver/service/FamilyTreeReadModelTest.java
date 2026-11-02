package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The database-backed tree answers exactly as the text-file tree did: the golden files were
 * captured from the in-memory tree before the move (see {@link FamilyTreeGolden}).
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class FamilyTreeReadModelTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private FamilyTreeService tree;

  @Test
  void theTreeStillHas346People() {
    assertThat(tree.getAllPeople()).hasSize(346);
  }

  @Test
  void everyPersonMatchesTheGoldenFile() throws Exception {
    FamilyTreeGolden.assertPeopleMatchAfterMove(
        FamilyTreeGolden.people(tree), FamilyTreeGolden.read("people.tsv"));
  }

  @Test
  void kinshipMatchesTheGoldenFile() throws Exception {
    FamilyTreeGolden.assertKinshipMatchAfterMove(
        FamilyTreeGolden.kinship(tree),
        FamilyTreeGolden.read("kinship.tsv"),
        FamilyTreeGolden.read("people.tsv"));
  }

  @Test
  void searchMatchesTheGoldenFile() throws Exception {
    FamilyTreeGolden.assertSearchMatchAfterMove(
        FamilyTreeGolden.search(tree), FamilyTreeGolden.read("search.tsv"));
  }

  @Test
  void theFamilySurnameIsAddedOnceAndOnlyToTheDisplayName() {
    assertThat(tree.getPerson(76).name).isEqualTo("Johann Rudolf Czernin");
    assertThat(tree.getPerson(322).name).isEqualTo("Alexander Friedrich Josef Paul Maria Czernin");
    assertThat(tree.getPerson(1).name).isEqualTo("Humprecht Czernin z Chudenic");
    assertThat(tree.getPerson(76).matchName()).isEqualTo("Johann Rudolf");
    assertThat(tree.getPerson(322).matchName()).isEqualTo("Alexander Friedrich Josef Paul Maria");
    assertThat(tree.getPerson(1).matchName()).isEqualTo("Humprecht Czernin z Chudenic");
  }

  @Test
  void personMatchingSeesTheLegacyNamesExactly() throws Exception {
    java.util.Map<Integer, String> legacy = new java.util.HashMap<>();
    for (String line : FamilyTreeGolden.read("people.tsv")) {
      String[] c = line.split("\t", -1);
      legacy.put(Integer.parseInt(c[0]), c[8]);
    }
    for (var p : tree.getAllPeople()) {
      assertThat(p.matchName()).as("person " + p.id).isEqualTo(legacy.get(p.id));
    }
  }

  @Test
  void czerninPlusAForenameFindsThatForename() {
    var hits = tree.search("czernin jaromir", 5);
    assertThat(hits).isNotEmpty();
    assertThat(hits).allSatisfy(h -> assertThat(h.name()).contains("Jarom").endsWith("Czernin"));
    assertThat(hits.getFirst().score()).isEqualTo(1.0);
  }

  @Test
  void aSnapshotLoadedBeforeInvalidateIsNotPublished() {
    tree.invalidate();
    java.util.concurrent.atomic.AtomicReference<Object> stale =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    tree.onLoadedHook =
        loaded -> {
          if (calls.getAndIncrement() == 0) {
            stale.set(loaded);
            tree.invalidate();
          }
        };
    try {
      Object returned = tree.treeSnapshot();
      assertThat(returned).isNotSameAs(stale.get());
    } finally {
      tree.onLoadedHook = null;
    }
  }
}
