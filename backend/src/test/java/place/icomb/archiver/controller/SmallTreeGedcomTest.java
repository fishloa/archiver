package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.genealogy.GedcomExporter;
import place.icomb.archiver.genealogy.GenEntity;
import place.icomb.archiver.genealogy.GenealogyFixtures;
import place.icomb.archiver.genealogy.GenealogyService;
import place.icomb.archiver.service.StorageService;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class SmallTreeGedcomTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private GenealogyService genealogy;
  @Autowired private StorageService storage;

  @Test
  void theSmallTreeMatchesItsGoldenFile() throws Exception {
    GenealogyFixtures.signInAsAdmin();
    jdbc.update("UPDATE app_user SET family_tree_person_id = NULL");
    jdbc.update("DELETE FROM gen_citation");
    jdbc.update("DELETE FROM gen_event");
    jdbc.update("DELETE FROM gen_parent_child");
    jdbc.update("DELETE FROM gen_couple");
    jdbc.update("DELETE FROM gen_media_person");
    jdbc.update("DELETE FROM gen_media");
    jdbc.update("DELETE FROM gen_name");
    jdbc.update("DELETE FROM gen_person");
    jdbc.update("DELETE FROM gen_source");
    jdbc.execute("SELECT setval('gen_person_id_seq', 1, false)");
    jdbc.execute("SELECT setval('gen_name_id_seq', 1, false)");
    jdbc.execute("SELECT setval('gen_couple_id_seq', 1, false)");
    jdbc.execute("SELECT setval('gen_parent_child_id_seq', 1, false)");
    jdbc.execute("SELECT setval('gen_event_id_seq', 1, false)");
    jdbc.execute("SELECT setval('gen_source_id_seq', 1, false)");
    jdbc.execute("SELECT setval('gen_citation_id_seq', 1, false)");
    jdbc.execute("SELECT setval('gen_media_id_seq', 1, false)");

    int alice =
        ((Number)
                genealogy
                    .createPerson(Map.of("given", "Alice", "surname", "Test", "living", false), "x")
                    .get("id"))
            .intValue();
    int bob =
        ((Number)
                genealogy
                    .createPerson(Map.of("given", "Bob", "surname", "Test", "living", false), "x")
                    .get("id"))
            .intValue();
    int carol =
        ((Number)
                genealogy
                    .createPerson(Map.of("given", "Carol", "surname", "Test", "living", false), "x")
                    .get("id"))
            .intValue();
    for (int pid : new int[] {alice, bob, carol}) {
      genealogy.create(
          GenEntity.EVENT, Map.of("personId", pid, "type", "BIRT", "dateText", "1 JAN 1900"), "x");
    }

    long couple =
        ((Number)
                genealogy
                    .create(
                        GenEntity.COUPLE,
                        Map.of("person1Id", alice, "person2Id", bob, "type", "marriage"),
                        "x")
                    .get("id"))
            .longValue();
    genealogy.create(
        GenEntity.EVENT, Map.of("coupleId", couple, "type", "MARR", "dateText", "1 JAN 2000"), "x");
    genealogy.create(
        GenEntity.PARENT_LINK,
        Map.of("parentId", alice, "childId", carol, "type", "birth", "coupleId", couple),
        "x");
    genealogy.create(
        GenEntity.PARENT_LINK,
        Map.of("parentId", bob, "childId", carol, "type", "birth", "coupleId", couple),
        "x");

    long source =
        ((Number) genealogy.create(GenEntity.SOURCE, Map.of("title", "Source"), "x").get("id"))
            .longValue();
    genealogy.create(
        GenEntity.CITATION,
        Map.of("sourceId", source, "personId", alice, "quality", 2, "detail", "p. 1"),
        "x");

    long record = GenealogyFixtures.record(jdbc);
    long page =
        GenealogyFixtures.page(
            jdbc, storage, record, 1, GenealogyFixtures.png(10, 10), "image/png");
    Map<String, Object> media =
        genealogy.create(
            GenEntity.MEDIA,
            Map.of(
                "pageId",
                page,
                "cropLeft",
                1,
                "cropTop",
                2,
                "cropWidth",
                3,
                "cropHeight",
                4,
                "caption",
                "Wedding"),
            "x");
    genealogy.create(
        GenEntity.MEDIA_PERSON,
        Map.of("mediaId", media.get("id"), "personId", alice, "isPortrait", true),
        "x");

    String actual = new GedcomExporter(jdbc).export(false);
    String expected =
        new String(
            getClass().getResourceAsStream("/gedcom/small-tree.ged").readAllBytes(),
            StandardCharsets.UTF_8);
    assertThat(normalize(actual)).isEqualTo(normalize(expected));
  }

  private static String normalize(String ged) {
    return ged.lines().map(SmallTreeGedcomTest::normalizeUid).collect(Collectors.joining("\n"))
        + (ged.endsWith("\n") ? "\n" : "");
  }

  private static String normalizeUid(String line) {
    for (String tag : new String[] {"UID", "_UID"}) {
      if (line.startsWith("1 " + tag + " ")) {
        return "1 " + tag + " 00000000-0000-0000-0000-000000000000";
      }
    }
    return line;
  }
}
