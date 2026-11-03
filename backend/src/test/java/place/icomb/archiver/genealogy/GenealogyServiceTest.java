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

/** The edit rules of the genealogy, and the audit row every write leaves. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class GenealogyServiceTest {

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
  @Autowired private FamilyTreeService tree;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StorageService storage;

  private static final int ALEXANDER = 322;
  private static final int ALEXANDER_HANNIBAL = 324;
  private static final int HUMPRECHT = 1;

  @BeforeEach
  void signIn() {
    GenealogyFixtures.signInAsAdmin();
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  private long audits() {
    return jdbc.queryForObject("SELECT count(*) FROM gen_change", Long.class);
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

  private int newPerson(String given, String surname) {
    return ((Number)
            genealogy
                .createPerson(body("given", given, "surname", surname, "sex", "F"), "test person")
                .get("id"))
        .intValue();
  }

  @Test
  void everyWriteNeedsAReason() {
    long before = audits();
    for (String reason : new String[] {null, "", "   "}) {
      assertThat(kindOf(() -> genealogy.createPerson(body("given", "Anna"), reason)))
          .isEqualTo(Kind.BAD_REQUEST);
      assertThat(
              kindOf(
                  () -> genealogy.update(GenEntity.PERSON, ALEXANDER, body("notes", "x"), reason)))
          .isEqualTo(Kind.BAD_REQUEST);
      assertThat(kindOf(() -> genealogy.delete(GenEntity.EVENT, 1, reason)))
          .isEqualTo(Kind.BAD_REQUEST);
    }
    assertThat(audits()).isEqualTo(before);
  }

  @Test
  void aNewPersonGetsANumberAbove348AndTwoAuditRows() {
    long before = audits();
    Map<String, Object> out =
        genealogy.createPerson(body("given", "Anna", "surname", "Novák", "sex", "F"), "letter 12");
    int id = ((Number) out.get("id")).intValue();
    assertThat(id).isGreaterThan(348);
    assertThat(out.get("sex")).isEqualTo("F");
    assertThat(out.get("primaryNameId")).isNotNull();
    assertThat(audits()).isEqualTo(before + 2);
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT op, before, after::text AS after, reason, changed_by FROM gen_change"
                + " WHERE entity = 'person' AND entity_id = ?",
            id);
    assertThat(audit.get("op")).isEqualTo("create");
    assertThat(audit.get("before")).isNull();
    assertThat((String) audit.get("after")).contains("\"id\": " + id);
    assertThat(audit.get("reason")).isEqualTo("letter 12");
    assertThat(audit.get("changed_by")).isEqualTo("genealogy-admin@example.com");
  }

  @Test
  void anUpdateKeepsWhatTheRowWasAndWhatItBecame() {
    int id = newPerson("Berta", "Musterfrau");
    long before = audits();
    genealogy.update(GenEntity.PERSON, id, body("notes", "born in Vienna"), "baptism register");
    assertThat(audits()).isEqualTo(before + 1);
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT before ->> 'notes' AS was, after ->> 'notes' AS now FROM gen_change"
                + " WHERE entity = 'person' AND entity_id = ? AND op = 'update'",
            id);
    assertThat(audit.get("was")).isNull();
    assertThat(audit.get("now")).isEqualTo("born in Vienna");
  }

  @Test
  void anEventDateMustBeGedcom7() {
    int id = newPerson("Carla", "Beispiel");
    for (String bad : new String[] {"31 FEB 1900", "sometime in 1900", "30.4.1913", "BET 1900"}) {
      assertThat(
              kindOf(
                  () ->
                      genealogy.create(
                          GenEntity.EVENT,
                          body("personId", id, "type", "BIRT", "dateText", bad),
                          "register")))
          .as(bad)
          .isEqualTo(Kind.BAD_REQUEST);
    }
    Map<String, Object> ok =
        genealogy.create(
            GenEntity.EVENT,
            body("personId", id, "type", "BIRT", "dateText", "ABT 1900"),
            "register");
    assertThat(ok.get("dateFrom")).isEqualTo("1900-01-01");
    assertThat(ok.get("dateTo")).isEqualTo("1900-12-31");
  }

  @Test
  void aThirdBirthParentIsRefused() {
    int third = newPerson("Dora", "Dritte");
    assertThat(
            kindOf(
                () ->
                    genealogy.create(
                        GenEntity.PARENT_LINK,
                        body("parentId", third, "childId", ALEXANDER_HANNIBAL, "type", "birth"),
                        "a mistake")))
        .isEqualTo(Kind.CONFLICT);
    genealogy.create(
        GenEntity.PARENT_LINK,
        body("parentId", third, "childId", ALEXANDER_HANNIBAL, "type", "foster"),
        "fostered");
  }

  @Test
  void creatingAPersonWithAnExplicitNullRequiredValueIsBadRequest() {
    assertThat(
            kindOf(
                () ->
                    genealogy.createPerson(
                        body("given", "Anna", "surname", "Novák", "sex", null), "test person")))
        .isEqualTo(Kind.BAD_REQUEST);
  }

  @Test
  void aParentLinkWithTypeNullIsTreatedAsBirthAndCountsTowardTheCap() {
    int third = newPerson("Dora", "Dritte");
    assertThat(
            kindOf(
                () ->
                    genealogy.create(
                        GenEntity.PARENT_LINK,
                        body("parentId", third, "childId", ALEXANDER_HANNIBAL, "type", null),
                        "a mistake")))
        .isEqualTo(Kind.CONFLICT);
  }

  @Test
  void aParentLinkThatMakesACycleIsRefused() {
    assertThat(
            kindOf(
                () ->
                    genealogy.create(
                        GenEntity.PARENT_LINK,
                        body(
                            "parentId",
                            ALEXANDER_HANNIBAL,
                            "childId",
                            HUMPRECHT,
                            "type",
                            "adopted"),
                        "a mistake")))
        .isEqualTo(Kind.CONFLICT);
  }

  @Test
  void aPersonWhoIsStillReferencedCannotBeDeleted() {
    long before = audits();
    GenealogyException e =
        (GenealogyException)
            org.assertj.core.api.Assertions.catchThrowable(
                () -> genealogy.delete(GenEntity.PERSON, ALEXANDER, "cleanup"));
    assertThat(e.kind()).isEqualTo(Kind.CONFLICT);
    assertThat(e.getMessage()).contains("merge");
    assertThat(audits()).isEqualTo(before);
  }

  @Test
  void deletingAnUnlinkedPersonTakesTheirNamesEventsAndCitationsEachAudited() {
    int id = newPerson("Emma", "Einzeln");
    Map<String, Object> event =
        genealogy.create(
            GenEntity.EVENT, body("personId", id, "type", "BIRT", "dateText", "1900"), "x");
    long source = jdbc.queryForObject("SELECT id FROM gen_source ORDER BY id LIMIT 1", Long.class);
    genealogy.create(
        GenEntity.CITATION,
        body("sourceId", source, "eventId", event.get("id"), "quality", 2),
        "x");
    long before = audits();
    Map<String, Object> out = genealogy.delete(GenEntity.PERSON, id, "duplicate entry");
    assertThat(out.get("deleted")).isEqualTo(4);
    assertThat(audits()).isEqualTo(before + 4);
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM gen_person WHERE id = ?", Integer.class, id))
        .isZero();
  }

  @Test
  void aCitationProvesExactlyOneFact() {
    long source = jdbc.queryForObject("SELECT id FROM gen_source ORDER BY id LIMIT 1", Long.class);
    assertThat(kindOf(() -> genealogy.create(GenEntity.CITATION, body("sourceId", source), "x")))
        .isEqualTo(Kind.BAD_REQUEST);
    assertThat(
            kindOf(
                () ->
                    genealogy.create(
                        GenEntity.CITATION,
                        body("sourceId", source, "personId", ALEXANDER, "nameId", 1),
                        "x")))
        .isEqualTo(Kind.BAD_REQUEST);
    assertThat(
            kindOf(
                () ->
                    genealogy.create(
                        GenEntity.CITATION,
                        body("sourceId", source, "personId", ALEXANDER, "quality", 4),
                        "x")))
        .isEqualTo(Kind.BAD_REQUEST);
  }

  @Test
  void makingANamePrimaryDemotesTheOldOneInTheSameWrite() {
    int id = newPerson("Franziska", "Erst");
    long before = audits();
    Map<String, Object> alias =
        genealogy.create(
            GenEntity.NAME,
            body(
                "personId",
                id,
                "type",
                "married",
                "given",
                "Franziska",
                "surname",
                "Zweit",
                "isPrimary",
                true),
            "marriage record");
    assertThat(audits()).isEqualTo(before + 2);
    assertThat(
            jdbc.queryForList(
                "SELECT id FROM gen_name WHERE person_id = ? AND is_primary", Long.class, id))
        .containsExactly(((Number) alias.get("id")).longValue());
    assertThat(
            kindOf(
                () ->
                    genealogy.update(
                        GenEntity.NAME,
                        ((Number) alias.get("id")).longValue(),
                        body("isPrimary", false),
                        "x")))
        .isEqualTo(Kind.CONFLICT);
    assertThat(
            kindOf(
                () ->
                    genealogy.delete(GenEntity.NAME, ((Number) alias.get("id")).longValue(), "x")))
        .isEqualTo(Kind.CONFLICT);
  }

  @Test
  void theTreeShowsAnEditOnceItIsCommitted() {
    long name =
        jdbc.queryForObject(
            "SELECT id FROM gen_name WHERE person_id = ? AND is_primary",
            Long.class,
            ALEXANDER_HANNIBAL);
    assertThat(tree.getPerson(ALEXANDER_HANNIBAL).name)
        .isEqualTo("Alexander Hannibal Czernin Fishlock");
    genealogy.update(GenEntity.NAME, name, body("surname", "Fishlock"), "split the surname");
    genealogy.update(GenEntity.NAME, name, body("given", "Alexander Hannibal Czernin"), "same");
    assertThat(tree.getPerson(ALEXANDER_HANNIBAL).name)
        .isEqualTo("Alexander Hannibal Czernin Fishlock");
    genealogy.update(GenEntity.NAME, name, body("given", "Alexander Hannibal"), "same");
    assertThat(tree.getPerson(ALEXANDER_HANNIBAL).name).isEqualTo("Alexander Hannibal Fishlock");
  }

  @Test
  void unknownFieldsAndWrongTypesAreBadRequests() {
    assertThat(
            kindOf(() -> genealogy.update(GenEntity.PERSON, ALEXANDER, body("colour", "red"), "x")))
        .isEqualTo(Kind.BAD_REQUEST);
    assertThat(
            kindOf(() -> genealogy.update(GenEntity.PERSON, ALEXANDER, body("living", "yes"), "x")))
        .isEqualTo(Kind.BAD_REQUEST);
    assertThat(kindOf(() -> genealogy.update(GenEntity.PERSON, ALEXANDER, body("sex", "X"), "x")))
        .isEqualTo(Kind.BAD_REQUEST);
    assertThat(kindOf(() -> genealogy.update(GenEntity.PERSON, 999999, body("notes", "x"), "x")))
        .isEqualTo(Kind.NOT_FOUND);
  }

  @Test
  void aMergedPersonCannotBeGivenNewFacts() {
    assertThat(
            kindOf(
                () ->
                    genealogy.create(GenEntity.EVENT, body("personId", 122, "type", "RESI"), "x")))
        .isEqualTo(Kind.CONFLICT);
  }

  @Test
  void aRecordLinkPageMustBeInThatRecord() {
    long recordA = GenealogyFixtures.record(jdbc);
    long recordB = GenealogyFixtures.record(jdbc);
    long pageB = GenealogyFixtures.page(jdbc, storage, recordB, 1, "x".getBytes(), "image/jpeg");
    assertThat(
            kindOf(
                () ->
                    genealogy.create(
                        GenEntity.RECORD_LINK,
                        body(
                            "personId",
                            ALEXANDER,
                            "recordId",
                            recordA,
                            "pageId",
                            pageB,
                            "role",
                            "subject"),
                        "x")))
        .isEqualTo(Kind.BAD_REQUEST);
    genealogy.create(
        GenEntity.RECORD_LINK,
        body("personId", ALEXANDER, "recordId", recordB, "pageId", pageB, "role", "subject"),
        "x");
  }

  @Test
  void aCropMustLieInsideTheStoredImage() throws Exception {
    long record = GenealogyFixtures.record(jdbc);
    long page =
        GenealogyFixtures.page(
            jdbc,
            storage,
            record,
            1,
            GenealogyFixtures.encode(GenealogyFixtures.pattern(40, 30), "png"),
            "image/png");
    for (int[] crop : new int[][] {{30, 0, 11, 10}, {0, 25, 10, 6}, {-1, 0, 5, 5}, {0, 0, 0, 5}}) {
      assertThat(
              kindOf(
                  () ->
                      genealogy.create(
                          GenEntity.MEDIA,
                          body(
                              "pageId",
                              page,
                              "cropLeft",
                              crop[0],
                              "cropTop",
                              crop[1],
                              "cropWidth",
                              crop[2],
                              "cropHeight",
                              crop[3]),
                          "x")))
          .as(java.util.Arrays.toString(crop))
          .isEqualTo(Kind.BAD_REQUEST);
    }
    assertThat(
            kindOf(
                () ->
                    genealogy.create(
                        GenEntity.MEDIA, body("pageId", page, "cropLeft", 0, "cropTop", 0), "x")))
        .isEqualTo(Kind.BAD_REQUEST);
    genealogy.create(
        GenEntity.MEDIA,
        body("pageId", page, "cropLeft", 30, "cropTop", 20, "cropWidth", 10, "cropHeight", 10),
        "exactly the bottom-right corner");
    genealogy.create(GenEntity.MEDIA, body("pageId", page), "the whole page");
  }

  @Test
  void aSecondPortraitReplacesTheFirst() throws Exception {
    long record = GenealogyFixtures.record(jdbc);
    long page =
        GenealogyFixtures.page(
            jdbc,
            storage,
            record,
            1,
            GenealogyFixtures.encode(GenealogyFixtures.pattern(4, 4), "png"),
            "image/png");
    long m1 =
        ((Number) genealogy.create(GenEntity.MEDIA, body("pageId", page), "x").get("id"))
            .longValue();
    long m2 =
        ((Number) genealogy.create(GenEntity.MEDIA, body("pageId", page), "x").get("id"))
            .longValue();
    genealogy.create(
        GenEntity.MEDIA_PERSON,
        body("mediaId", m1, "personId", ALEXANDER, "isPortrait", true),
        "x");
    genealogy.create(
        GenEntity.MEDIA_PERSON,
        body("mediaId", m2, "personId", ALEXANDER, "isPortrait", true),
        "x");
    assertThat(
            jdbc.queryForList(
                "SELECT media_id FROM gen_media_person WHERE person_id = ? AND is_portrait",
                Long.class,
                ALEXANDER))
        .containsExactly(m2);
  }

  @Test
  void anEventPlaceGivenByNameIsReusedOrMadeInTheSameWrite() {
    int id = newPerson("Greta", "Ort");
    long before = audits();
    Map<String, Object> first =
        genealogy.create(
            GenEntity.EVENT,
            body("personId", id, "type", "RESI", "placeName", " Nirgendwo-Test "),
            "census");
    assertThat(audits()).isEqualTo(before + 2);
    Map<String, Object> second =
        genealogy.create(
            GenEntity.EVENT,
            body("personId", id, "type", "RESI", "placeName", "Nirgendwo-Test"),
            "census");
    assertThat(second.get("placeId")).isEqualTo(first.get("placeId"));
    assertThat(audits()).isEqualTo(before + 3);
    Map<String, Object> cleared =
        genealogy.update(
            GenEntity.EVENT, ((Number) second.get("id")).longValue(), body("placeName", ""), "x");
    assertThat(cleared.get("placeId")).isNull();
  }
}
