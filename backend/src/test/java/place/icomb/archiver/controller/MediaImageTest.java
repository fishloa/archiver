package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import javax.imageio.ImageIO;
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
import place.icomb.archiver.genealogy.GenEntity;
import place.icomb.archiver.genealogy.GenealogyFixtures;
import place.icomb.archiver.genealogy.GenealogyService;
import place.icomb.archiver.service.StorageService;

/** A media item's image is the original's own pixels: never rescaled, never re-encoded lossily. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaImageTest {

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
  private static final String USER = "media-reader@example.com";

  @BeforeEach
  void setUp() {
    GenealogyFixtures.signInAsAdmin();
    jdbc.update("DELETE FROM app_user_email WHERE email = ?", USER);
    jdbc.update("DELETE FROM app_user WHERE display_name = 'MediaReader'");
    Long id =
        jdbc.queryForObject(
            "INSERT INTO app_user (display_name, role) VALUES ('MediaReader', 'user') RETURNING id",
            Long.class);
    jdbc.update("INSERT INTO app_user_email (user_id, email) VALUES (?, ?)", id, USER);
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  private HttpResponse<byte[]> image(long mediaId) throws Exception {
    return http.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + port + "/api/family-tree/media/" + mediaId + "/image"))
            .header("X-Auth-Email", USER)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private long media(long pageId, Integer... crop) {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("pageId", pageId);
    if (crop.length == 4) {
      body.put("cropLeft", crop[0]);
      body.put("cropTop", crop[1]);
      body.put("cropWidth", crop[2]);
      body.put("cropHeight", crop[3]);
    }
    return ((Number) genealogy.create(GenEntity.MEDIA, body, "photo").get("id")).longValue();
  }

  private static void assertSamePixels(
      BufferedImage crop, BufferedImage original, int left, int top) {
    for (int y = 0; y < crop.getHeight(); y++) {
      for (int x = 0; x < crop.getWidth(); x++) {
        assertThat(crop.getRGB(x, y))
            .as("pixel %d,%d", x, y)
            .isEqualTo(original.getRGB(left + x, top + y));
      }
    }
  }

  @Test
  void aWholePageItemIsTheStoredFileByteForByte() throws Exception {
    byte[] original = GenealogyFixtures.png(40, 30);
    long record = GenealogyFixtures.record(jdbc);
    long page = GenealogyFixtures.page(jdbc, storage, record, 1, original, "image/png");

    HttpResponse<byte[]> r = image(media(page));

    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Content-Type")).contains("image/png");
    assertThat(r.body()).isEqualTo(original);
  }

  @Test
  void aCropIsTheRegionsExactPixelsAsPng() throws Exception {
    byte[] original = GenealogyFixtures.png(40, 30);
    long record = GenealogyFixtures.record(jdbc);
    long page = GenealogyFixtures.page(jdbc, storage, record, 1, original, "image/png");

    HttpResponse<byte[]> r = image(media(page, 5, 6, 10, 8));

    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Content-Type")).contains("image/png");
    BufferedImage crop = ImageIO.read(new ByteArrayInputStream(r.body()));
    assertThat(crop.getWidth()).isEqualTo(10);
    assertThat(crop.getHeight()).isEqualTo(8);
    assertSamePixels(crop, ImageIO.read(new ByteArrayInputStream(original)), 5, 6);
  }

  @Test
  void aCropTouchingTheImageEdgeIsAllowedAndExact() throws Exception {
    byte[] original = GenealogyFixtures.png(40, 30);
    long record = GenealogyFixtures.record(jdbc);
    long page = GenealogyFixtures.page(jdbc, storage, record, 1, original, "image/png");

    BufferedImage crop =
        ImageIO.read(new ByteArrayInputStream(image(media(page, 30, 20, 10, 10)).body()));

    assertSamePixels(crop, ImageIO.read(new ByteArrayInputStream(original)), 30, 20);
  }

  @Test
  void aCropOfAJpegScanCopiesTheDecodedPixelsWithoutRecompressing() throws Exception {
    byte[] jpeg = GenealogyFixtures.jpeg(64, 48);
    long record = GenealogyFixtures.record(jdbc);
    long page = GenealogyFixtures.page(jdbc, storage, record, 1, jpeg, "image/jpeg");

    HttpResponse<byte[]> r = image(media(page, 8, 8, 16, 16));

    assertThat(r.headers().firstValue("Content-Type")).contains("image/png");
    assertSamePixels(
        ImageIO.read(new ByteArrayInputStream(r.body())),
        ImageIO.read(new ByteArrayInputStream(jpeg)),
        8,
        8);
    assertThat(image(media(page)).body()).isEqualTo(jpeg);
  }

  @Test
  void aCropTheCurrentScanNoLongerContainsIsAConflictNotAnError() throws Exception {
    long record = GenealogyFixtures.record(jdbc);
    long page =
        GenealogyFixtures.page(
            jdbc, storage, record, 1, GenealogyFixtures.png(40, 30), "image/png");
    long media = media(page, 30, 20, 10, 10);
    String smaller = storage.storePageImage(GenealogyFixtures.png(20, 20));
    jdbc.update(
        "UPDATE attachment SET path = ? WHERE id = (SELECT attachment_id FROM page WHERE id = ?)",
        smaller,
        page);

    assertThat(image(media).statusCode()).isEqualTo(409);
    assertThat(image(99999999L).statusCode()).isEqualTo(404);
  }

  @Test
  void aCropOfALargerFixtureStillPixelMatches() throws Exception {
    byte[] original = GenealogyFixtures.png(120, 90);
    long record = GenealogyFixtures.record(jdbc);
    long page = GenealogyFixtures.page(jdbc, storage, record, 1, original, "image/png");

    BufferedImage crop =
        ImageIO.read(new ByteArrayInputStream(image(media(page, 50, 40, 30, 20)).body()));

    assertSamePixels(crop, ImageIO.read(new ByteArrayInputStream(original)), 50, 40);
  }

  @Test
  void anUndecodableScanIsReportedAsUnprocessable() throws Exception {
    long record = GenealogyFixtures.record(jdbc);
    long page =
        GenealogyFixtures.page(
            jdbc, storage, record, 1, GenealogyFixtures.png(40, 30), "image/png");
    long media = media(page, 5, 6, 10, 8);
    byte[] garbage = new byte[64];
    new java.util.Random(1).nextBytes(garbage);
    String stored = storage.storePageImage(garbage);
    jdbc.update(
        "UPDATE attachment SET path = ? WHERE id = (SELECT attachment_id FROM page WHERE id = ?)",
        stored,
        page);

    HttpResponse<byte[]> r = image(media);

    assertThat(r.statusCode()).isEqualTo(422);
    String body = new String(r.body(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(body).contains("this scan cannot be decoded for cropping");
    assertThat(body).contains("the whole page is still available");
  }
}
