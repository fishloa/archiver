package place.icomb.archiver.genealogy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.service.StorageService;

/**
 * The stored scan behind a page: its pixel size, and the image a media item shows.
 *
 * <p>Nothing derived is stored and nothing is rescaled. A whole-page item is the stored file's own
 * bytes; a cropped one is the region's pixels copied out of the decoded original and encoded as
 * lossless PNG.
 */
@Service
public class MediaImages {

  public record Dimensions(int width, int height) {}

  public record Image(byte[] bytes, String contentType) {}

  private final JdbcTemplate jdbc;
  private final AttachmentRepository attachments;
  private final StorageService storage;

  public MediaImages(JdbcTemplate jdbc, AttachmentRepository attachments, StorageService storage) {
    this.jdbc = jdbc;
    this.attachments = attachments;
    this.storage = storage;
  }

  /** The pixel size of the stored original, read from its header without decoding it. */
  public Dimensions dimensions(long pageId) {
    Path file = original(pageId);
    try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
      Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
      if (readers == null || !readers.hasNext()) {
        throw GenealogyException.conflict("the image of page %d cannot be read".formatted(pageId));
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(in);
        return new Dimensions(reader.getWidth(0), reader.getHeight(0));
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw GenealogyException.conflict("the image of page %d cannot be read".formatted(pageId));
    }
  }

  public Image image(long mediaId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT page_id, crop_left, crop_top, crop_width, crop_height FROM gen_media"
                + " WHERE id = ?",
            mediaId);
    if (rows.isEmpty()) {
      throw GenealogyException.notFound("media %d not found".formatted(mediaId));
    }
    Map<String, Object> m = rows.get(0);
    long pageId = ((Number) m.get("page_id")).longValue();
    Attachment attachment = attachment(pageId);
    Path file = storage.getPath(attachment);
    try {
      if (m.get("crop_left") == null) {
        String mime =
            attachment.getMime() != null ? attachment.getMime() : "application/octet-stream";
        return new Image(Files.readAllBytes(file), mime);
      }
      BufferedImage full = ImageIO.read(file.toFile());
      if (full == null) {
        throw GenealogyException.conflict("the image of page %d cannot be read".formatted(pageId));
      }
      int left = (Integer) m.get("crop_left");
      int top = (Integer) m.get("crop_top");
      int width = (Integer) m.get("crop_width");
      int height = (Integer) m.get("crop_height");
      if (left + width > full.getWidth() || top + height > full.getHeight()) {
        throw GenealogyException.conflict(
            "media %d's crop lies outside page %d's current %dx%d image"
                .formatted(mediaId, pageId, full.getWidth(), full.getHeight()));
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(full.getSubimage(left, top, width, height), "png", out);
      return new Image(out.toByteArray(), "image/png");
    } catch (IOException e) {
      throw GenealogyException.conflict("the image of page %d cannot be read".formatted(pageId));
    }
  }

  Attachment attachment(long pageId) {
    List<Map<String, Object>> page =
        jdbc.queryForList("SELECT attachment_id FROM page WHERE id = ?", pageId);
    if (page.isEmpty()) {
      throw GenealogyException.notFound("page %d not found".formatted(pageId));
    }
    long attachmentId = ((Number) page.get(0).get("attachment_id")).longValue();
    return attachments
        .findById(attachmentId)
        .orElseThrow(
            () -> GenealogyException.notFound("page %d has no stored image".formatted(pageId)));
  }

  Path original(long pageId) {
    return storage.getPath(attachment(pageId));
  }
}
