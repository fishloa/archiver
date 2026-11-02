package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.genealogy.GedcomDate;
import place.icomb.archiver.genealogy.GenEntity;
import place.icomb.archiver.genealogy.GenealogyFixtures;
import place.icomb.archiver.genealogy.GenealogyService;
import place.icomb.archiver.service.StorageService;

/** The GEDCOM 7 export: well-formed, complete, and private about the living when asked. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GedcomExportTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StorageService storage;
  @Autowired private GenealogyService genealogy;

  private final HttpClient http = HttpClient.newHttpClient();
  private static final String ADMIN = "gedcom-admin@example.com";
  private static final Pattern LINE =
      Pattern.compile("^(0|[1-9][0-9]*) (?:(@[A-Z0-9_]+@) )?([A-Z_][A-Z0-9_]*)(?: (.*))?$");
  private static final Pattern POINTER = Pattern.compile("^@[A-Z0-9_]+@$");

  @BeforeEach
  void setUp() {
    GenealogyFixtures.signInAsAdmin();
    jdbc.update("DELETE FROM app_user_email WHERE email = ?", ADMIN);
    jdbc.update("DELETE FROM app_user WHERE display_name = 'GedcomAdmin'");
    Long id =
        jdbc.queryForObject(
            "INSERT INTO app_user (display_name, role) VALUES ('GedcomAdmin', 'admin') RETURNING id",
            Long.class);
    jdbc.update("INSERT INTO app_user_email (user_id, email) VALUES (?, ?)", id, ADMIN);
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  private String export(String living) throws Exception {
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/api/admin/genealogy/export.ged?living="
                            + living))
                .header("X-Auth-Email", ADMIN)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    assertThat(r.headers().firstValue("Content-Type"))
        .hasValueSatisfying(t -> assertThat(t).startsWith("text/vnd.familysearch.gedcom"));
    return r.body();
  }

  /** Line grammar, level nesting, one definition per xref, every pointer defined, dates parse. */
  private static void assertWellFormed(String ged) {
    assertThat(ged).startsWith("0 HEAD\n1 GEDC\n2 VERS 7.0\n").endsWith("0 TRLR\n");
    Set<String> defined = new HashSet<>();
    List<String> pointed = new ArrayList<>();
    int previous = -1;
    for (String line : ged.split("\n")) {
      Matcher m = LINE.matcher(line);
      assertThat(m.matches()).as(line).isTrue();
      int level = Integer.parseInt(m.group(1));
      assertThat(level).as(line).isLessThanOrEqualTo(previous + 1);
      previous = level;
      if (m.group(2) != null) {
        assertThat(level).as(line).isZero();
        assertThat(defined.add(m.group(2))).as("defined twice: " + line).isTrue();
      }
      String payload = m.group(4);
      if (payload != null && POINTER.matcher(payload).matches()) pointed.add(payload);
      if (m.group(3).equals("DATE")) GedcomDate.parse(payload);
    }
    assertThat(defined).containsAll(pointed);
  }

  private static String record(String ged, String xref) {
    int start = ged.indexOf("0 " + xref + " ");
    assertThat(start).as(xref).isNotNegative();
    int end = ged.indexOf("\n0 ", start + 1);
    return ged.substring(start, end + 1);
  }

  @Test
  void theExportIsWellFormedGedcom7() throws Exception {
    String ged = export("include");
    assertWellFormed(ged);
    assertWellFormed(export("exclude"));
  }

  @Test
  void alexanderHasHisNameEventsCitationsAndFamilies() throws Exception {
    String ged = export("include");
    String alexander = record(ged, "@I322@");
    assertThat(alexander)
        .contains(
            "1 NAME Alexander Friedrich Josef Paul Maria /Czernin/\n2 TYPE BIRTH\n"
                + "2 GIVN Alexander Friedrich Josef Paul Maria\n2 SURN Czernin\n")
        .containsPattern("1 BIRT\n2 DATE 30 APR 1913\n2 PLAC Wien\n2 SOUR @S\\d+@\n3 QUAY 1\n")
        .containsPattern("1 DEAT\n2 DATE 5 APR 2002\n2 PLAC Oxford\n")
        .contains("1 FAMC @F")
        .containsPattern("1 _UID [0-9a-f-]{36}\n");
    Matcher fams = Pattern.compile("1 FAMS (@F\\d+@)").matcher(alexander);
    assertThat(fams.find()).isTrue();
    String family = record(ged, fams.group(1));
    assertThat(family)
        .contains("1 HUSB @I322@")
        .containsPattern("1 WIFE @I\\d+@")
        .contains("1 CHIL @I323@");
    assertThat(family).containsPattern("1 MARR\n2 DATE 9 JUL 1949\n2 PLAC Haywards Heath\n");
    assertThat(record(ged, "@I323@")).contains("2 PEDI BIRTH");
    assertThat(ged).contains("1 TITL genealogy.euweb.cz Czernin pages (legacy text file)");
  }

  @Test
  void anImageCarriesItsFileAndCrop() throws Exception {
    long record = GenealogyFixtures.record(jdbc);
    long page =
        GenealogyFixtures.page(
            jdbc, storage, record, 1, GenealogyFixtures.png(40, 30), "image/png");
    Map<String, Object> media =
        genealogy.create(
            GenEntity.MEDIA,
            Map.of(
                "pageId",
                page,
                "cropLeft",
                5,
                "cropTop",
                6,
                "cropWidth",
                10,
                "cropHeight",
                8,
                "caption",
                "Wedding, 1949"),
            "photo");
    genealogy.create(
        GenEntity.MEDIA_PERSON,
        Map.of("mediaId", media.get("id"), "personId", 322, "isPortrait", true),
        "photo");
    long attachment =
        jdbc.queryForObject("SELECT attachment_id FROM page WHERE id = ?", Long.class, page);

    String ged = export("include");
    assertWellFormed(ged);
    String xref = "@O" + media.get("id") + "@";
    assertThat(record(ged, "@I322@"))
        .contains(
            "1 OBJE "
                + xref
                + "\n2 CROP\n3 TOP 6\n3 LEFT 5\n3 HEIGHT 8\n3 WIDTH 10\n2 TITL Wedding, 1949\n");
    assertThat(record(ged, xref))
        .isEqualTo(
            "0 "
                + xref
                + " OBJE\n1 FILE https://archive.czernin.eu/api/files/"
                + attachment
                + "\n2 FORM image/png\n");
  }

  @Test
  void aPersonWithNoEventsIsStillAValidRecord() throws Exception {
    int id =
        ((Number)
                genealogy
                    .createPerson(
                        Map.of("given", "Nobody", "surname", "Known"), "a name in a letter")
                    .get("id"))
            .intValue();
    String ged = export("include");
    assertWellFormed(ged);
    assertThat(record(ged, "@I" + id + "@"))
        .matches(
            "0 @I"
                + id
                + "@ INDI\n1 NAME Nobody /Known/\n2 TYPE BIRTH\n2 GIVN Nobody\n2 SURN Known\n"
                + "1 SEX U\n1 _UID [0-9a-f-]{36}\n");
  }

  @Test
  void livingPeopleAndFamiliesOfOnlyTheLivingCanBeLeftOut() throws Exception {
    int a =
        ((Number) genealogy.createPerson(Map.of("given", "Living", "living", true), "x").get("id"))
            .intValue();
    int b =
        ((Number) genealogy.createPerson(Map.of("given", "Alive", "living", true), "x").get("id"))
            .intValue();
    long couple =
        ((Number)
                genealogy
                    .create(GenEntity.COUPLE, Map.of("person1Id", a, "person2Id", b), "x")
                    .get("id"))
            .longValue();

    String included = export("include");
    assertThat(included)
        .contains("0 @I324@ INDI")
        .contains("0 @I" + a + "@ INDI")
        .contains("0 @F" + couple + "@ FAM");

    String excluded = export("exclude");
    assertWellFormed(excluded);
    assertThat(excluded)
        .doesNotContain("@I324@")
        .doesNotContain("@I" + a + "@")
        .doesNotContain("@F" + couple + "@")
        .contains("0 @I322@ INDI");
  }

  @Test
  void aMarriageToALivingPersonKeepsItsDetailsPrivate() throws Exception {
    int living =
        ((Number) genealogy.createPerson(Map.of("given", "Spouse", "living", true), "x").get("id"))
            .intValue();
    long couple =
        ((Number)
                genealogy
                    .create(GenEntity.COUPLE, Map.of("person1Id", 322, "person2Id", living), "x")
                    .get("id"))
            .longValue();
    genealogy.create(
        GenEntity.EVENT, Map.of("coupleId", couple, "type", "MARR", "dateText", "2 FEB 2002"), "x");

    assertThat(export("include")).contains("2 DATE 2 FEB 2002");
    String excluded = export("exclude");
    assertWellFormed(excluded);
    assertThat(excluded).doesNotContain("2 FEB 2002");
  }

  @Test
  void continuationLinesNeverCarryACarriageReturn() throws Exception {
    genealogy.update(GenEntity.PERSON, 322, Map.of("notes", "first\r\nsecond"), "x");
    assertThat(export("include")).doesNotContain("\r").contains("first\n");
  }

  @Test
  void livingMustBeExcludeOrInclude() throws Exception {
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/api/admin/genealogy/export.ged?living=maybe"))
                .header("X-Auth-Email", ADMIN)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(400);
  }
}
