package place.icomb.archiver.genealogy;

import java.io.IOException;
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

/** The stored scan behind a page, read for its pixel size. */
@Service
public class MediaImages {

  public record Dimensions(int width, int height) {}

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
