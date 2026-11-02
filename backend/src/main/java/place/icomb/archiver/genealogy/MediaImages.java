package place.icomb.archiver.genealogy;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
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

  /** Raised when a stored scan cannot be decoded for cropping. */
  public static class UndecodableScanException extends RuntimeException {
    public UndecodableScanException(String message) {
      super(message);
    }
  }

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
        try {
          return new Image(Files.readAllBytes(file), mime);
        } catch (IOException e) {
          throw GenealogyException.conflict(
              "the image of page %d cannot be read".formatted(pageId));
        }
      }
      int left = (Integer) m.get("crop_left");
      int top = (Integer) m.get("crop_top");
      int width = (Integer) m.get("crop_width");
      int height = (Integer) m.get("crop_height");
      try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
        if (stream == null) {
          throw new UndecodableScanException(
              "this scan cannot be decoded for cropping (no ImageReader); the whole page is still available");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
        if (readers == null || !readers.hasNext()) {
          throw new UndecodableScanException(
              "this scan cannot be decoded for cropping (no ImageReader for this format); the whole page is still available");
        }
        ImageReader reader = readers.next();
        try {
          reader.setInput(stream);
          int fullWidth;
          int fullHeight;
          try {
            fullWidth = reader.getWidth(0);
            fullHeight = reader.getHeight(0);
          } catch (IOException e) {
            throw undecodable(e);
          }
          if (left + width > fullWidth || top + height > fullHeight) {
            throw GenealogyException.conflict(
                "media %d's crop lies outside page %d's current %dx%d image"
                    .formatted(mediaId, pageId, fullWidth, fullHeight));
          }
          ImageReadParam param = reader.getDefaultReadParam();
          param.setSourceRegion(new Rectangle(left, top, width, height));
          BufferedImage region;
          try {
            region = reader.read(0, param);
          } catch (IOException e) {
            throw undecodable(e);
          } catch (RuntimeException e) {
            if (e instanceof GenealogyException) throw e;
            throw undecodable(e);
          }
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          ImageIO.write(region, "png", out);
          return new Image(out.toByteArray(), "image/png");
        } finally {
          reader.dispose();
        }
      }
    } catch (IOException e) {
      throw undecodable(e);
    } catch (RuntimeException e) {
      if (e instanceof GenealogyException || e instanceof UndecodableScanException) throw e;
      throw undecodable(e);
    }
  }

  private static UndecodableScanException undecodable(Throwable cause) {
    String detail = cause == null ? null : cause.getMessage();
    if (detail == null || detail.isBlank()) {
      detail = cause == null ? "unknown" : cause.getClass().getSimpleName();
    }
    return new UndecodableScanException(
        "this scan cannot be decoded for cropping ("
            + detail
            + "); the whole page is still available");
  }

  Attachment attachment(long pageId) {
    List<Map<String, Object>> page =
        jdbc.queryForList("SELECT attachment_id FROM page WHERE id = ?", pageId);
    if (page.isEmpty()) {
      throw GenealogyException.notFound("page %d not found".formatted(pageId));
    }
    Object attachmentIdValue = page.get(0).get("attachment_id");
    if (attachmentIdValue == null) {
      throw GenealogyException.notFound("page %d has no stored image".formatted(pageId));
    }
    long attachmentId = ((Number) attachmentIdValue).longValue();
    return attachments
        .findById(attachmentId)
        .orElseThrow(
            () -> GenealogyException.notFound("page %d has no stored image".formatted(pageId)));
  }

  Path original(long pageId) {
    return storage.getPath(attachment(pageId));
  }
}
