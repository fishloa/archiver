package place.icomb.archiver.genealogy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.genealogy.GenealogyException.Kind;
import place.icomb.archiver.service.FamilyTreeService;
import place.icomb.archiver.service.StorageService;

/** Merging two records of one person, and undoing writes. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class GenealogyHistoryTest {

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

  @Autowired private GenealogyService genealogy;
  @Autowired private GenealogyHistoryService history;
  @Autowired private FamilyTreeService tree;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StorageService storage;

  @BeforeEach
  void signIn() {
    GenealogyFixtures.signInAsAdmin();
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  private static Map<String, Object> body(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  private static Kind kindOf(Runnable r) {
    try {
      r.run();
    } catch (GenealogyException e) {
      return e.kind();
    }
    throw new AssertionError("expected a GenealogyException");
  }

  private int person(String given) {
    return ((Number)
            genealogy.createPerson(body("given", given, "surname", "Test"), "setup").get("id"))
        .intValue();
  }

  private long id(Map<String, Object> row) {
    return ((Number) row.get("id")).longValue();
  }

  private long lastChange() {
    return jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM gen_change", Long.class);
  }

  /** Every kind of link a person can have, all on {@code gone}, plus a few keep already shares. */
  private record World(
      int keep,
      int gone,
      int partner,
      int child,
      int parent,
      int alias,
      long page,
      long sharedPage,
      long media,
      long user) {}

  private World world() throws Exception {
    int keep = person("Keep");
    int gone = person("Gone");
    int partner = person("Partner");
    int child = person("Child");
    int parent = person("Parent");
    int alias = person("Alias");
    genealogy.create(
        GenEntity.EVENT, body("personId", gone, "type", "RESI", "dateText", "1930"), "setup");
    genealogy.create(GenEntity.COUPLE, body("person1Id", gone, "person2Id", partner), "setup");
    genealogy.create(GenEntity.PARENT_LINK, body("parentId", gone, "childId", child), "setup");
    genealogy.create(GenEntity.PARENT_LINK, body("parentId", parent, "childId", gone), "setup");
    genealogy.create(GenEntity.PARENT_LINK, body("parentId", parent, "childId", keep), "setup");
    long source = jdbc.queryForObject("SELECT id FROM gen_source ORDER BY id LIMIT 1", Long.class);
    genealogy.create(GenEntity.CITATION, body("sourceId", source, "personId", gone), "setup");
    long record = GenealogyFixtures.record(jdbc);
    byte[] png = GenealogyFixtures.encode(GenealogyFixtures.pattern(8, 8), "png");
    long page = GenealogyFixtures.page(jdbc, storage, record, 1, png, "image/png");
    long sharedPage = GenealogyFixtures.page(jdbc, storage, record, 2, png, "image/png");
    genealogy.create(
        GenEntity.RECORD_LINK,
        body("personId", gone, "recordId", record, "role", "mentioned"),
        "setup");
    long media = id(genealogy.create(GenEntity.MEDIA, body("pageId", page), "setup"));
    genealogy.create(
        GenEntity.MEDIA_PERSON,
        body("mediaId", media, "personId", gone, "isPortrait", true),
        "setup");
    jdbc.update(
        "INSERT INTO page_person_match (page_id, person_id, person_name, score) VALUES (?, ?, 'Gone', 0.9)",
        page,
        gone);
    jdbc.update(
        "INSERT INTO page_person_match (page_id, person_id, person_name, score) VALUES (?, ?, 'Gone', 0.5),"
            + " (?, ?, 'Keep', 0.7)",
        sharedPage,
        gone,
        sharedPage,
        keep);
    long user =
        jdbc.queryForObject(
            "INSERT INTO app_user (display_name, role, family_tree_person_id) VALUES ('Merge', 'user', ?)"
                + " RETURNING id",
            Long.class,
            gone);
    jdbc.update("UPDATE gen_person SET merged_into = ? WHERE id = ?", gone, alias);
    return new World(keep, gone, partner, child, parent, alias, page, sharedPage, media, user);
  }

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  @Test
  void aMergeRepointsEveryKindOfLink() throws Exception {
    World w = world();
    history.mergePersons(w.keep(), w.gone(), "same man, two entries");

    int g = w.gone();
    assertThat(count("SELECT merged_into FROM gen_person WHERE id = ?", g)).isEqualTo(w.keep());
    assertThat(count("SELECT count(*) FROM gen_name WHERE person_id = ?", g)).isZero();
    assertThat(count("SELECT count(*) FROM gen_name WHERE person_id = ? AND is_primary", w.keep()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM gen_name WHERE person_id = ?", w.keep())).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM gen_event WHERE person_id = ?", g)).isZero();
    assertThat(count("SELECT count(*) FROM gen_couple WHERE ? IN (person1_id, person2_id)", g))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM gen_couple WHERE person1_id = ? AND person2_id = ?",
                w.keep(),
                w.partner()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM gen_parent_child WHERE ? IN (parent_id, child_id)", g))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM gen_parent_child WHERE parent_id = ? AND child_id = ?",
                w.keep(),
                w.child()))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM gen_parent_child WHERE parent_id = ? AND child_id = ?",
                w.parent(),
                w.keep()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM gen_citation WHERE person_id = ?", g)).isZero();
    assertThat(count("SELECT count(*) FROM gen_person_record WHERE person_id = ?", g)).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM gen_media_person WHERE person_id = ? AND is_portrait",
                w.keep()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM page_person_match WHERE person_id = ?", g)).isZero();
    assertThat(count("SELECT count(*) FROM page_person_match WHERE person_id = ?", w.keep()))
        .isEqualTo(2);
    assertThat(count("SELECT family_tree_person_id FROM app_user WHERE id = ?", w.user()))
        .isEqualTo(w.keep());
    assertThat(count("SELECT merged_into FROM gen_person WHERE id = ?", w.alias()))
        .isEqualTo(w.keep());
    assertThat(tree.getPerson(g).id).isEqualTo(w.keep());
  }

  @Test
  void aMergeWritesOneAuditRowPerRowItChanged() throws Exception {
    World w = world();
    long before = lastChange();
    Map<String, Object> out = history.mergePersons(w.keep(), w.gone(), "duplicate");
    assertThat(count("SELECT count(*) FROM gen_change WHERE id > ?", before))
        .isEqualTo(((Number) out.get("changed")).intValue());
    assertThat(
            count(
                "SELECT count(*) FROM gen_change WHERE id > ? AND entity = 'page_person_match'",
                before))
        .isEqualTo(2);
    assertThat(
            count("SELECT count(*) FROM gen_change WHERE id > ? AND entity = 'app_user'", before))
        .isEqualTo(1);
  }

  @Test
  void undoingAMergePutsEverythingBack() throws Exception {
    World w = world();
    history.mergePersons(w.keep(), w.gone(), "duplicate");
    history.undo(lastChange(), "they were two people after all");

    int g = w.gone();
    assertThat(
            jdbc.queryForObject(
                "SELECT merged_into FROM gen_person WHERE id = ?", Integer.class, g))
        .isNull();
    assertThat(count("SELECT count(*) FROM gen_name WHERE person_id = ? AND is_primary", g))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM gen_parent_child WHERE parent_id = ? AND child_id = ?",
                w.parent(),
                g))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM page_person_match WHERE person_id = ?", g)).isEqualTo(2);
    assertThat(count("SELECT family_tree_person_id FROM app_user WHERE id = ?", w.user()))
        .isEqualTo(g);
    assertThat(
            count("SELECT count(*) FROM gen_media_person WHERE person_id = ? AND is_portrait", g))
        .isEqualTo(1);
    assertThat(count("SELECT merged_into FROM gen_person WHERE id = ?", w.alias())).isEqualTo(g);
    assertThat(tree.getPerson(g).id).isEqualTo(g);
  }

  @Test
  void undoIsRefusedOnceTheRowHasChangedAgain() {
    int p = person("Hedwig");
    genealogy.update(GenEntity.PERSON, p, body("notes", "first"), "one");
    long first = lastChange();
    genealogy.update(GenEntity.PERSON, p, body("notes", "second"), "two");
    long second = lastChange();

    assertThat(kindOf(() -> history.undo(first, "wrong order"))).isEqualTo(Kind.CONFLICT);
    assertThat(jdbc.queryForObject("SELECT notes FROM gen_person WHERE id = ?", String.class, p))
        .isEqualTo("second");

    history.undo(second, "back one");
    assertThat(jdbc.queryForObject("SELECT notes FROM gen_person WHERE id = ?", String.class, p))
        .isEqualTo("first");
    history.undo(first, "back two");
    assertThat(jdbc.queryForObject("SELECT notes FROM gen_person WHERE id = ?", String.class, p))
        .isNull();
    assertThat(kindOf(() -> history.undo(first, "again"))).isEqualTo(Kind.CONFLICT);
  }

  @Test
  void undoingACreateRemovesItAndUndoingADeleteRestoresIt() {
    int p = person("Irma");
    long created = lastChange();
    genealogy.create(GenEntity.EVENT, body("personId", p, "type", "BIRT", "dateText", "1901"), "x");
    assertThat(kindOf(() -> history.undo(created, "created by mistake"))).isEqualTo(Kind.CONFLICT);

    genealogy.delete(GenEntity.PERSON, p, "duplicate");
    history.undo(lastChange(), "not a duplicate");
    assertThat(count("SELECT count(*) FROM gen_person WHERE id = ?", p)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM gen_name WHERE person_id = ? AND is_primary", p))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM gen_event WHERE person_id = ?", p)).isEqualTo(1);

    int q = person("Jana");
    history.undo(lastChange(), "created by mistake");
    assertThat(count("SELECT count(*) FROM gen_person WHERE id = ?", q)).isZero();
  }

  @Test
  void undoNeedsAReasonAndAChangeThatExists() {
    assertThat(kindOf(() -> history.undo(lastChange(), " "))).isEqualTo(Kind.BAD_REQUEST);
    assertThat(kindOf(() -> history.undo(99999999L, "x"))).isEqualTo(Kind.NOT_FOUND);
  }

  @Test
  void aMergeRefusesThingsThatCannotBeOnePerson() {
    int a = person("Karl");
    int b = person("Karla");
    genealogy.create(GenEntity.COUPLE, body("person1Id", a, "person2Id", b), "x");
    assertThat(kindOf(() -> history.mergePersons(a, b, "x"))).isEqualTo(Kind.CONFLICT);
    assertThat(kindOf(() -> history.mergePersons(a, a, "x"))).isEqualTo(Kind.BAD_REQUEST);
    assertThat(kindOf(() -> history.mergePersons(a, 122, "x"))).isEqualTo(Kind.CONFLICT);
    assertThat(kindOf(() -> history.mergePersons(a, 999999, "x"))).isEqualTo(Kind.NOT_FOUND);

    int c = person("Child");
    int p1 = person("P1");
    int p2 = person("P2");
    int p3 = person("P3");
    int twin = person("Twin");
    genealogy.create(GenEntity.PARENT_LINK, body("parentId", p1, "childId", c), "x");
    genealogy.create(GenEntity.PARENT_LINK, body("parentId", p2, "childId", c), "x");
    genealogy.create(GenEntity.PARENT_LINK, body("parentId", p3, "childId", twin), "x");
    assertThat(kindOf(() -> history.mergePersons(c, twin, "x"))).isEqualTo(Kind.CONFLICT);
    assertThat(count("SELECT count(*) FROM gen_parent_child WHERE child_id = ?", twin))
        .isEqualTo(1);
  }

  @Test
  void undoingACreateRefusesWhenOthersDependOnIt() {
    int p = person("Dep");
    long created = lastChange();
    jdbc.update(
        "INSERT INTO app_user (display_name, role, family_tree_person_id) VALUES ('U', 'user', ?)",
        p);
    assertThat(kindOf(() -> history.undo(created, "created by mistake"))).isEqualTo(Kind.CONFLICT);
    assertThat(count("SELECT count(*) FROM gen_person WHERE id = ?", p)).isEqualTo(1);
  }

  @Test
  void undoingAParentLinkDeleteRechecksParentRules() {
    int child = person("ChildX");
    int existing = person("Existing");
    int a = person("A");
    int b = person("B");
    genealogy.create(
        GenEntity.PARENT_LINK, body("parentId", existing, "childId", child, "type", "birth"), "x");
    Map<String, Object> link =
        genealogy.create(
            GenEntity.PARENT_LINK, body("parentId", a, "childId", child, "type", "birth"), "x");
    long linkId = id(link);
    genealogy.delete(GenEntity.PARENT_LINK, linkId, "x");
    long deleted = lastChange();
    genealogy.create(
        GenEntity.PARENT_LINK, body("parentId", b, "childId", child, "type", "birth"), "x");
    assertThat(kindOf(() -> history.undo(deleted, "restore"))).isEqualTo(Kind.CONFLICT);
  }

  @Test
  void undoFallsBackToTheTimeAndAuthorKeyForRowsWithoutATransactionId() {
    int p = person("Legacy");
    long created = lastChange();
    jdbc.update("UPDATE gen_change SET txid = NULL WHERE id = ?", created);
    history.undo(created, "created by mistake");
    assertThat(count("SELECT count(*) FROM gen_person WHERE id = ?", p)).isZero();
  }

  @Test
  void newChangesGetTheirTransactionIdByDefault() {
    assertThat(
            jdbc.queryForObject(
                "SELECT pg_get_expr(adbin, adrelid) FROM pg_attrdef d JOIN pg_attribute a"
                    + " ON a.attrelid = d.adrelid AND a.attnum = d.adnum"
                    + " WHERE d.adrelid = 'gen_change'::regclass AND a.attname = 'txid'",
                String.class))
        .contains("txid_current()");
  }

  @Test
  void aMergeRefusesAncestorMerges() {
    int keep = person("Keep");
    int mid = person("Mid");
    int gone = person("Gone");
    genealogy.create(GenEntity.PARENT_LINK, body("parentId", gone, "childId", mid), "x");
    genealogy.create(GenEntity.PARENT_LINK, body("parentId", mid, "childId", keep), "x");
    long beforeChanges = jdbc.queryForObject("SELECT count(*) FROM gen_change", Long.class);
    assertThat(kindOf(() -> history.mergePersons(keep, gone, "x"))).isEqualTo(Kind.CONFLICT);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM gen_change", Long.class))
        .isEqualTo(beforeChanges);
  }
}
