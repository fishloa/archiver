package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.repository.AttachmentRepository;

/**
 * Crops the figures Mistral OCR found on a page out of the scan.
 *
 * <p>The engine reports each figure as a box in its own page space and refers to it from the
 * markdown as {@code ![img-0.jpeg](img-0.jpeg)}, but never sends the bytes. 21,996 of this
 * archive's pages carry at least one — signatures, stamps, letterheads, seals — and on a
 * countersigned order the signature block is the evidence, so dropping them loses the part that
 * matters most.
 *
 * <p>Shared by the page viewer and the PDF exports so a figure is cut the same way in both.
 */
@Service
public class OcrImageService {

  private static final Logger log = LoggerFactory.getLogger(OcrImageService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final JdbcTemplate jdbcTemplate;
  private final AttachmentRepository attachmentRepository;
  private final StorageService storageService;

  public OcrImageService(
      JdbcTemplate jdbcTemplate,
      AttachmentRepository attachmentRepository,
      StorageService storageService) {
    this.jdbcTemplate = jdbcTemplate;
    this.attachmentRepository = attachmentRepository;
    this.storageService = storageService;
  }

  /** One figure, as JPEG bytes, with the pixel size it was cut at. */
  public record Crop(byte[] jpeg, int width, int height) {}

  /** Crops a single figure by the id the markdown refers to. */
  public Optional<Crop> crop(Long pageId, String imageId) {
    return Optional.ofNullable(cropAll(pageId).get(imageId));
  }

  /**
   * Crops every figure on a page, keyed by markdown id.
   *
   * <p>One pass over the scan: an export renders whole records, and re-decoding a 30 MB TIFF per
   * figure would dominate the export's cost.
   */
  public Map<String, Crop> cropAll(Long pageId) {
    Map<String, Crop> out = new LinkedHashMap<>();
    var rows =
        jdbcTemplate.queryForList(
            """
            SELECT p.attachment_id, pt.raw_response::text AS raw_response
            FROM page p JOIN page_text pt ON pt.page_id = p.id
            WHERE p.id = ?
            """,
            pageId);
    if (rows.isEmpty() || rows.get(0).get("attachment_id") == null) {
      return out;
    }
    String rawResponse = (String) rows.get(0).get("raw_response");
    if (rawResponse == null || rawResponse.isBlank()) {
      return out;
    }

    try {
      JsonNode page = MAPPER.readTree(rawResponse).path("pages").path(0);
      double pw = page.path("dimensions").path("width").asDouble(0);
      double ph = page.path("dimensions").path("height").asDouble(0);
      if (pw <= 0 || ph <= 0 || !page.path("images").elements().hasNext()) {
        return out;
      }

      Attachment attachment =
          attachmentRepository
              .findById(((Number) rows.get(0).get("attachment_id")).longValue())
              .orElse(null);
      if (attachment == null) {
        return out;
      }
      BufferedImage full = ImageIO.read(storageService.getPath(attachment).toFile());
      if (full == null) {
        return out;
      }

      // Box coordinates live in the engine's own page space; scale onto the real scan.
      double sx = full.getWidth() / pw;
      double sy = full.getHeight() / ph;

      for (JsonNode img : page.path("images")) {
        String id = img.path("id").asText("");
        if (id.isEmpty()) continue;
        int x = (int) Math.max(0, Math.floor(img.path("top_left_x").asDouble() * sx));
        int y = (int) Math.max(0, Math.floor(img.path("top_left_y").asDouble() * sy));
        int w =
            (int)
                Math.ceil(
                    (img.path("bottom_right_x").asDouble() - img.path("top_left_x").asDouble())
                        * sx);
        int h =
            (int)
                Math.ceil(
                    (img.path("bottom_right_y").asDouble() - img.path("top_left_y").asDouble())
                        * sy);
        w = Math.max(1, Math.min(w, full.getWidth() - x));
        h = Math.max(1, Math.min(h, full.getHeight() - y));
        if (x >= full.getWidth() || y >= full.getHeight()) continue;

        BufferedImage crop = full.getSubimage(x, y, w, h);
        // getSubimage shares the parent raster; JPEG writing needs a standalone RGB image, and
        // copying also lets the full scan be collected once the loop ends.
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        rgb.createGraphics().drawImage(crop, 0, 0, null);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(rgb, "jpg", buf);
        out.put(id, new Crop(buf.toByteArray(), w, h));
      }
    } catch (Exception e) {
      // A page whose figures cannot be cut still renders its text.
      log.warn("Could not crop OCR figures for page {}", pageId, e);
    }
    return out;
  }
}
