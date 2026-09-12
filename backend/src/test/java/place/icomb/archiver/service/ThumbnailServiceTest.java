package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import place.icomb.archiver.model.Attachment;

class ThumbnailServiceTest {

  private Attachment attachment(Long id, String path, String mime) {
    Attachment a = new Attachment();
    a.setId(id);
    a.setPath(path);
    a.setMime(mime);
    return a;
  }

  private Path writeScan(Path root, String relative, int w, int h) throws Exception {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(Color.DARK_GRAY);
    g.fillRect(0, 0, w, h);
    g.dispose();
    ImageIO.write(img, "jpg", file.toFile());
    return file;
  }

  @Test
  void downscalesAScanToTheLongEdge(@TempDir Path root) throws Exception {
    writeScan(root, "records/1/attachments/pages/p0001.jpg", 3000, 2000);
    var service = new ThumbnailService(new StorageService(root, (Path) null), root);

    Path thumb =
        service.thumbnailFor(attachment(7L, "records/1/attachments/pages/p0001.jpg", "image/jpeg"));

    assertThat(thumb).isNotNull();
    BufferedImage out = ImageIO.read(thumb.toFile());
    assertThat(out.getWidth()).isEqualTo(ThumbnailService.MAX_EDGE);
    assertThat(out.getHeight()).isEqualTo(267);
    // The point of the exercise: the thumbnail is a fraction of the scan it came from.
    assertThat(Files.size(thumb))
        .isLessThan(Files.size(root.resolve("records/1/attachments/pages/p0001.jpg")));
  }

  @Test
  void doesNotUpscaleAnImageSmallerThanTheTarget(@TempDir Path root) throws Exception {
    writeScan(root, "records/1/attachments/pages/small.jpg", 120, 80);
    var service = new ThumbnailService(new StorageService(root, (Path) null), root);

    Path thumb =
        service.thumbnailFor(attachment(8L, "records/1/attachments/pages/small.jpg", "image/jpeg"));

    BufferedImage out = ImageIO.read(thumb.toFile());
    assertThat(out.getWidth()).isEqualTo(120);
    assertThat(out.getHeight()).isEqualTo(80);
  }

  @Test
  void cachesTheResultInsteadOfRebuildingIt(@TempDir Path root) throws Exception {
    writeScan(root, "records/1/attachments/pages/p0001.jpg", 800, 600);
    var service = new ThumbnailService(new StorageService(root, (Path) null), root);
    Attachment a = attachment(9L, "records/1/attachments/pages/p0001.jpg", "image/jpeg");

    Path first = service.thumbnailFor(a);
    var stamp = Files.getLastModifiedTime(first);
    Path second = service.thumbnailFor(a);

    assertThat(second).isEqualTo(first);
    assertThat(Files.getLastModifiedTime(second)).isEqualTo(stamp);
  }

  @Test
  void buildsThumbnailsForScansOnTheReadOnlyArchiveWithoutWritingToIt(
      @TempDir Path own, @TempDir Path archive) throws Exception {
    writeScan(archive, "records/1/attachments/pages/p0001.jpg", 1600, 1200);
    var service = new ThumbnailService(new StorageService(own, archive), own);

    Path thumb =
        service.thumbnailFor(
            attachment(10L, "records/1/attachments/pages/p0001.jpg", "image/jpeg"));

    assertThat(thumb).isNotNull();
    assertThat(thumb).startsWith(own);
    // A test stack mounting production's scans must never write into them.
    assertThat(Files.walk(archive).filter(p -> p.toString().contains("thumbnails")).findAny())
        .isEmpty();
  }

  @Test
  void returnsNullForANonImageSoTheCallerCanFallBack(@TempDir Path root) {
    var service = new ThumbnailService(new StorageService(root, (Path) null), root);

    assertThat(service.thumbnailFor(attachment(11L, "records/1/doc.pdf", "application/pdf")))
        .isNull();
  }

  @Test
  void returnsNullWhenTheScanIsMissing(@TempDir Path root) {
    var service = new ThumbnailService(new StorageService(root, (Path) null), root);

    assertThat(service.thumbnailFor(attachment(12L, "records/1/gone.jpg", "image/jpeg"))).isNull();
  }

  @Test
  void evictRemovesTheCachedCopy(@TempDir Path root) throws Exception {
    writeScan(root, "records/1/attachments/pages/p0001.jpg", 800, 600);
    var service = new ThumbnailService(new StorageService(root, (Path) null), root);
    Attachment a = attachment(13L, "records/1/attachments/pages/p0001.jpg", "image/jpeg");

    Path thumb = service.thumbnailFor(a);
    service.evict(13L);

    assertThat(Files.exists(thumb)).isFalse();
  }
}
