package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import place.icomb.archiver.genealogy.GenealogyException;
import place.icomb.archiver.genealogy.MediaImages;

/** The figures the OCR engine found on a page, as numbered boxes in the stored scan's pixels. */
@Service
public class OcrFigures {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A figure's 1-based number on its page, its markdown id, and its box in scan pixels. */
  public record Figure(int number, String id, int left, int top, int width, int height) {}

  private final JdbcTemplate jdbc;
  private final MediaImages images;

  public OcrFigures(JdbcTemplate jdbc, MediaImages images) {
    this.jdbc = jdbc;
    this.images = images;
  }

  public List<Figure> figures(long pageId) {
    var rows =
        jdbc.queryForList(
            """
            SELECT p.attachment_id, pt.raw_response::text AS raw_response
            FROM page p JOIN page_text pt ON pt.page_id = p.id
            WHERE p.id = ?
            """,
            pageId);
    if (rows.isEmpty() || rows.get(0).get("attachment_id") == null) {
      return List.of();
    }
    String raw = (String) rows.get(0).get("raw_response");
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    JsonNode page;
    try {
      page = MAPPER.readTree(raw).path("pages").path(0);
    } catch (Exception e) {
      return List.of();
    }
    double pw = page.path("dimensions").path("width").asDouble(0);
    double ph = page.path("dimensions").path("height").asDouble(0);
    if (pw <= 0 || ph <= 0 || !page.path("images").elements().hasNext()) {
      return List.of();
    }
    MediaImages.Dimensions scan = images.dimensions(pageId);
    double sx = scan.width() / pw;
    double sy = scan.height() / ph;
    List<Figure> out = new ArrayList<>();
    int number = 0;
    for (JsonNode img : page.path("images")) {
      number++;
      Figure f = box(number, img, sx, sy, scan.width(), scan.height());
      if (f != null) {
        out.add(f);
      }
    }
    return out;
  }

  public Figure figure(long pageId, int number) {
    List<Figure> all = figures(pageId);
    return all.stream()
        .filter(f -> f.number() == number)
        .findFirst()
        .orElseThrow(
            () ->
                GenealogyException.badRequest(
                    "page %d has %d OCR figures; there is no figure %d"
                        .formatted(pageId, all.size(), number)));
  }

  /**
   * The figure's box in scan pixels, or null when it has no id or starts outside the scan. Floors
   * the origin, ceils the size, and clamps to the scan.
   */
  public static Figure box(int number, JsonNode img, double sx, double sy, int fullW, int fullH) {
    String id = img.path("id").asText("");
    if (id.isEmpty()) {
      return null;
    }
    int x = (int) Math.max(0, Math.floor(img.path("top_left_x").asDouble() * sx));
    int y = (int) Math.max(0, Math.floor(img.path("top_left_y").asDouble() * sy));
    int w =
        (int)
            Math.ceil(
                (img.path("bottom_right_x").asDouble() - img.path("top_left_x").asDouble()) * sx);
    int h =
        (int)
            Math.ceil(
                (img.path("bottom_right_y").asDouble() - img.path("top_left_y").asDouble()) * sy);
    w = Math.max(1, Math.min(w, fullW - x));
    h = Math.max(1, Math.min(h, fullH - y));
    if (x >= fullW || y >= fullH) {
      return null;
    }
    return new Figure(number, id, x, y, w, h);
  }
}
