package place.icomb.archiver.genealogy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.service.StorageService;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PagePeopleServiceTest {

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

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StorageService storage;
  @Autowired private PagePeopleService people;

  private static final int X = 322;
  private static final int Y = 323;

  private long record;
  private long p1;
  private long p2;
  private long p3;

  @BeforeEach
  void fixtures() throws Exception {
    jdbc.update("DELETE FROM gen_person_record WHERE person_id IN (?, ?)", X, Y);
    jdbc.update("DELETE FROM gen_media_person WHERE person_id IN (?, ?)", X, Y);
    record = GenealogyFixtures.record(jdbc);
    byte[] png = GenealogyFixtures.png(40, 40);
    p1 = GenealogyFixtures.page(jdbc, storage, record, 1, png, "image/png");
    p2 = GenealogyFixtures.page(jdbc, storage, record, 2, png, "image/png");
    p3 = GenealogyFixtures.page(jdbc, storage, record, 3, png, "image/png");
  }

  private long media(long page, int person, boolean portrait) {
    long id =
        jdbc.queryForObject(
            "INSERT INTO gen_media (page_id) VALUES (?) RETURNING id", Long.class, page);
    jdbc.update(
        "INSERT INTO gen_media_person (media_id, person_id, is_portrait) VALUES (?, ?, ?)",
        id,
        person,
        portrait);
    return id;
  }

  @Test
  void aPageLinkBeatsARecordLinkAndOtherPagesSeeTheRecordLink() {
    jdbc.update(
        "INSERT INTO gen_person_record (person_id, record_id, role) VALUES (?, ?, 'mentioned')",
        X,
        record);
    jdbc.update(
        "INSERT INTO gen_person_record (person_id, record_id, page_id, role, note)"
            + " VALUES (?, ?, ?, 'signatory', 'signs at the foot')",
        X,
        record,
        p2);

    List<Map<String, Object>> on2 = people.forPage(p2);
    assertThat(on2).hasSize(1);
    assertThat(on2.get(0)).containsEntry("personId", X).containsEntry("via", "page");
    assertThat(on2.get(0)).containsEntry("role", "signatory");
    assertThat(on2.get(0).get("roles")).isEqualTo(List.of("signatory", "mentioned"));
    assertThat(on2.get(0)).containsEntry("note", "signs at the foot");

    List<Map<String, Object>> on1 = people.forPage(p1);
    assertThat(on1).hasSize(1);
    assertThat(on1.get(0)).containsEntry("via", "record").containsEntry("role", "mentioned");
  }

  @Test
  void aPictureCutFromThePageCountsAsPresence() {
    long mediaId = media(p3, X, false);

    List<Map<String, Object>> on3 = people.forPage(p3);
    assertThat(on3).hasSize(1);
    assertThat(on3.get(0))
        .containsEntry("personId", X)
        .containsEntry("via", "picture")
        .containsEntry("mediaId", mediaId);
    assertThat(people.forPage(p1)).isEmpty();

    List<Map<String, Object>> whole = people.forRecord(record);
    assertThat(whole).hasSize(1);
    assertThat(whole.get(0)).containsEntry("pageSeq", 3);
  }

  @Test
  void hintsAreNeverShown() {
    jdbc.update(
        "INSERT INTO page_person_match (page_id, person_id, person_name, score)"
            + " VALUES (?, ?, 'x', 0.9)",
        p2,
        X);
    assertThat(people.forPage(p2)).isEmpty();
    assertThat(people.forRecord(record)).isEmpty();
  }

  @Test
  void portraitsMapOnlyThoseWhoHaveOne() {
    long mediaId = media(p1, X, true);
    media(p1, Y, false);

    Map<Integer, String> urls = people.portraits(Set.of(X, Y));
    assertThat(urls).containsOnlyKeys(X);
    assertThat(urls.get(X)).isEqualTo("/api/family-tree/media/" + mediaId + "/image");

    jdbc.update(
        "INSERT INTO gen_person_record (person_id, record_id, role) VALUES (?, ?, 'subject')",
        X,
        record);
    assertThat(people.forPage(p1).stream().filter(e -> e.get("personId").equals(X)).toList())
        .singleElement()
        .satisfies(e -> assertThat(e.get("portraitUrl")).isEqualTo(urls.get(X)));
  }

  private HttpResponse<String> get(String path) throws Exception {
    String email = "page-people-reader@example.com";
    jdbc.update("DELETE FROM app_user_email WHERE email = ?", email);
    jdbc.update("DELETE FROM app_user WHERE display_name = 'PagePeople Reader'");
    long uid =
        jdbc.queryForObject(
            "INSERT INTO app_user (display_name, role) VALUES ('PagePeople Reader', 'user')"
                + " RETURNING id",
            Long.class);
    jdbc.update("INSERT INTO app_user_email (user_id, email) VALUES (?, ?)", uid, email);
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/family-tree" + path))
                .header("X-Auth-Email", email)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void theOldMatchRoutesAreGoneAndTheNewOnesAnswer() throws Exception {
    assertThat(get("/page-matches/1").statusCode()).isEqualTo(404);
    assertThat(get("/record-matches/1").statusCode()).isEqualTo(404);
    media(p1, X, true);
    assertThat(get("/page-people/" + p1).body()).contains("\"personId\":" + X);
    assertThat(get("/record-people/" + record).statusCode()).isEqualTo(200);
    assertThat(get("/pages/" + p1 + "/figures").body()).isEqualTo("[]");
    assertThat(get("/portraits?ids=" + X + "," + Y).body()).contains("\"" + X + "\"");
  }
}
