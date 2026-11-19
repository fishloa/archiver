package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import place.icomb.archiver.genealogy.GenealogyException;
import place.icomb.archiver.genealogy.GenealogyFixtures;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class OcrFiguresTest {

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

  @Autowired private OcrFigures figures;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StorageService storage;

  private static JsonNode image(String json) throws Exception {
    return new ObjectMapper().readTree(json);
  }

  @Test
  void boxScalesEnginePageOntoTheScan() throws Exception {
    JsonNode img =
        image(
            "{\"id\":\"img-0.jpeg\",\"top_left_x\":100,\"top_left_y\":200,"
                + "\"bottom_right_x\":300,\"bottom_right_y\":500}");
    assertThat(OcrFigures.box(1, img, 2.0, 2.0, 2000, 2800))
        .isEqualTo(new OcrFigures.Figure(1, "img-0.jpeg", 200, 400, 400, 600));
  }

  @Test
  void boxOverrunningTheRightEdgeIsClamped() throws Exception {
    JsonNode img =
        image(
            "{\"id\":\"img-0.jpeg\",\"top_left_x\":900,\"top_left_y\":200,"
                + "\"bottom_right_x\":1100,\"bottom_right_y\":500}");
    OcrFigures.Figure f = OcrFigures.box(1, img, 2.0, 2.0, 2000, 2800);
    assertThat(f.left()).isEqualTo(1800);
    assertThat(f.width()).isEqualTo(200);
  }

  private long pageWithRaw(String raw) throws Exception {
    long record = GenealogyFixtures.record(jdbc);
    long page =
        GenealogyFixtures.page(
            jdbc, storage, record, 1, GenealogyFixtures.png(200, 280), "image/png");
    jdbc.update(
        "INSERT INTO page_text (page_id, engine, text_raw, content_type, raw_response)"
            + " VALUES (?, 'ocr_page_mistral', 'x', 'text/markdown', ?::jsonb)",
        page,
        raw);
    return page;
  }

  @Test
  void figuresAreNumberedFromOneInEngineOrder() throws Exception {
    long page =
        pageWithRaw(
            "{\"pages\":[{\"dimensions\":{\"width\":100,\"height\":140},\"images\":["
                + "{\"id\":\"img-0.jpeg\",\"top_left_x\":10,\"top_left_y\":20,"
                + "\"bottom_right_x\":30,\"bottom_right_y\":50},"
                + "{\"id\":\"img-1.jpeg\",\"top_left_x\":0,\"top_left_y\":0,"
                + "\"bottom_right_x\":50,\"bottom_right_y\":70}]}]}");

    assertThat(figures.figures(page))
        .containsExactly(
            new OcrFigures.Figure(1, "img-0.jpeg", 20, 40, 40, 60),
            new OcrFigures.Figure(2, "img-1.jpeg", 0, 0, 100, 140));
    assertThat(figures.figure(page, 2).id()).isEqualTo("img-1.jpeg");
    assertThatThrownBy(() -> figures.figure(page, 3))
        .isInstanceOf(GenealogyException.class)
        .hasMessage("page %d has 2 OCR figures; there is no figure 3".formatted(page));
  }

  @Test
  void pageWithoutRawResponseHasNoFigures() throws Exception {
    long record = GenealogyFixtures.record(jdbc);
    long page =
        GenealogyFixtures.page(
            jdbc, storage, record, 1, GenealogyFixtures.png(20, 28), "image/png");
    assertThat(figures.figures(page)).isEmpty();
    assertThatThrownBy(() -> figures.figure(page, 1))
        .isInstanceOf(GenealogyException.class)
        .hasMessage("page %d has 0 OCR figures; there is no figure 1".formatted(page));
  }
}
