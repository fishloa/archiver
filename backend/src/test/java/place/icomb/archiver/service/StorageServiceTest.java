package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import place.icomb.archiver.model.Attachment;

/**
 * Reading the archive without being able to write to it.
 *
 * <p>A test deployment mounts the real 253 GB of scans read only and keeps its own writable root,
 * so it can exercise OCR, PDF export and the viewer against real files with no path by which it
 * could damage the originals.
 */
class StorageServiceTest {

  private Attachment at(String path) {
    Attachment a = new Attachment();
    a.setPath(path);
    return a;
  }

  @Test
  void readsFromItsOwnRootFirst(@TempDir Path writable, @TempDir Path archive) throws Exception {
    Files.createDirectories(writable.resolve("records/1"));
    Files.createDirectories(archive.resolve("records/1"));
    Files.writeString(writable.resolve("records/1/p.jpg"), "mine");
    Files.writeString(archive.resolve("records/1/p.jpg"), "theirs");

    StorageService service = new StorageService(writable, archive);

    assertThat(Files.readString(service.getPath(at("records/1/p.jpg")))).isEqualTo("mine");
  }

  @Test
  void fallsBackToTheReadOnlyArchive(@TempDir Path writable, @TempDir Path archive)
      throws Exception {
    Files.createDirectories(archive.resolve("records/1"));
    Files.writeString(archive.resolve("records/1/p.jpg"), "the real scan");

    StorageService service = new StorageService(writable, archive);

    assertThat(Files.readString(service.getPath(at("records/1/p.jpg")))).isEqualTo("the real scan");
  }

  @Test
  void writesNeverGoToTheReadOnlyArchive(@TempDir Path writable, @TempDir Path archive)
      throws Exception {
    StorageService service = new StorageService(writable, archive);

    String stored = service.storePageImage(7L, 1, "new".getBytes());

    assertThat(writable.resolve(stored)).exists();
    assertThat(archive.resolve(stored)).doesNotExist();
  }

  @Test
  void withNoSecondRootItBehavesAsBefore(@TempDir Path writable) {
    StorageService service = new StorageService(writable, (Path) null);

    assertThat(service.getPath(at("records/1/p.jpg")))
        .isEqualTo(writable.resolve("records/1/p.jpg"));
  }

  @Test
  void aMissingFileResolvesToThePrimaryRootSoTheErrorPointsAtThisDeployment(
      @TempDir Path writable, @TempDir Path archive) {
    StorageService service = new StorageService(writable, archive);

    assertThat(service.getPath(at("records/9/missing.jpg")))
        .isEqualTo(writable.resolve("records/9/missing.jpg"));
  }
}
