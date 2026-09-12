package place.icomb.archiver.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import place.icomb.archiver.model.Attachment;

/**
 * Downscaled copies of page scans, cached on disk.
 *
 * <p>The record list and page strip asked for a "thumbnail" and were served the original scan — a
 * multi-megabyte archival image, once per tile. A page of fifty results moved hundreds of megabytes
 * to draw images a couple of hundred pixels wide.
 *
 * <p>The cache is keyed by attachment id and lives under the deployment's own storage root, never
 * the read-only archive: a test stack mounting production's scans must be able to build thumbnails
 * without writing to them.
 */
@Service
public class ThumbnailService {

  private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);

  /** Long edge of a generated thumbnail, in pixels. */
  static final int MAX_EDGE = 400;

  private final StorageService storageService;
  private final Path cacheRoot;

  public ThumbnailService(StorageService storageService, Path storageRoot) {
    this.storageService = storageService;
    this.cacheRoot = storageRoot.resolve("derivatives/thumbnails");
  }

  /**
   * Returns the cached thumbnail for an attachment, generating it on first request.
   *
   * @return the thumbnail path, or null if the attachment is not an image this can decode
   */
  public Path thumbnailFor(Attachment attachment) {
    if (!isImage(attachment)) {
      return null;
    }
    Path cached = cacheRoot.resolve(attachment.getId() + ".jpg");
    if (Files.exists(cached)) {
      return cached;
    }
    Path source = storageService.getPath(attachment);
    if (!Files.exists(source)) {
      return null;
    }
    try {
      return generate(source, cached);
    } catch (IOException | RuntimeException e) {
      // A scan this cannot decode is not worth failing the page over — the caller falls back
      // to the original, which is what every request got before this existed.
      log.warn(
          "Thumbnail generation failed for attachment {}: {}", attachment.getId(), e.toString());
      return null;
    }
  }

  private Path generate(Path source, Path cached) throws IOException {
    BufferedImage original;
    try (InputStream in = Files.newInputStream(source)) {
      original = ImageIO.read(in);
    }
    if (original == null) {
      return null;
    }

    int w = original.getWidth();
    int h = original.getHeight();
    double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(w, h));
    int tw = Math.max(1, (int) Math.round(w * scale));
    int th = Math.max(1, (int) Math.round(h * scale));

    // TYPE_INT_RGB, not the source type: scans arrive as greyscale, indexed and CMYK, and JPEG
    // cannot write most of them. Flattening to RGB makes every source writable.
    BufferedImage scaled = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = scaled.createGraphics();
    try {
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.drawImage(original, 0, 0, tw, th, null);
    } finally {
      g.dispose();
    }

    Files.createDirectories(cached.getParent());
    // Written beside the target and moved into place, so a concurrent reader never sees a
    // half-written JPEG.
    Path tmp = Files.createTempFile(cached.getParent(), "thumb-", ".jpg");
    try {
      if (!ImageIO.write(scaled, "jpg", tmp.toFile())) {
        throw new IOException("no JPEG writer available");
      }
      Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      Files.deleteIfExists(tmp);
      throw e;
    }
    return cached;
  }

  private boolean isImage(Attachment attachment) {
    String mime = attachment.getMime();
    if (mime != null) {
      return mime.startsWith("image/");
    }
    String path = attachment.getPath();
    if (path == null) {
      return false;
    }
    String lower = path.toLowerCase();
    return lower.endsWith(".jpg")
        || lower.endsWith(".jpeg")
        || lower.endsWith(".png")
        || lower.endsWith(".tif")
        || lower.endsWith(".tiff");
  }

  /** Removes a cached thumbnail, so a replaced scan is not served from a stale copy. */
  public void evict(Long attachmentId) {
    try {
      Files.deleteIfExists(cacheRoot.resolve(attachmentId + ".jpg"));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to evict thumbnail " + attachmentId, e);
    }
  }
}
