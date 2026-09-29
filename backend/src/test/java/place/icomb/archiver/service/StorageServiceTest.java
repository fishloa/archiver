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

    String stored = service.storePageImage("new".getBytes());

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

  @Test
  void aPageImageIsAddressedByItsOwnIdentityNotByRecordOrPosition(@TempDir Path writable) {
    // The address must survive the page moving to another record or another position — the two
    // things the old records/{id}/attachments/pages/p{seq} layout was a function of.
    StorageService service = new StorageService(writable, (Path) null);

    String stored = service.storePageImage("scan".getBytes());

    assertThat(stored).matches("attachments/[0-9a-f]{2}/[0-9a-f-]{36}\\.jpg");
    assertThat(stored.substring(12, 14)).isEqualTo(stored.substring(15, 17));
    assertThat(writable.resolve(stored)).exists();
  }

  @Test
  void twoImagesNeverShareAFileEvenWhenTheirBytesAreIdentical(@TempDir Path writable) {
    // Record 4006 lost seven images when two pages computed the same name. A name that is not
    // derived from anything about the page cannot collide.
    StorageService service = new StorageService(writable, (Path) null);

    String first = service.storePageImage("same".getBytes());
    String second = service.storePageImage("same".getBytes());

    assertThat(first).isNotEqualTo(second);
    assertThat(writable.resolve(first)).exists();
    assertThat(writable.resolve(second)).exists();
  }

  @Test
  void aFileInItsOwnRootIsLinkedNotCopied(@TempDir Path writable) throws Exception {
    // Same bytes under both names, nothing read or rewritten — and the old name still there, so a
    // row still pointing at it keeps working until the caller drops it.
    Files.createDirectories(writable.resolve("records/5/attachments/pages"));
    Path old = writable.resolve("records/5/attachments/pages/p0001.jpg");
    Files.writeString(old, "old scan");
    StorageService service = new StorageService(writable, (Path) null);

    String placed = service.placeAtNewAddress("records/5/attachments/pages/p0001.jpg");

    assertThat(placed).startsWith("attachments/");
    assertThat(Files.readString(writable.resolve(placed))).isEqualTo("old scan");
    assertThat(Files.isSameFile(old, writable.resolve(placed))).isTrue();
  }

  @Test
  void droppingTheOldNameLeavesTheNewOneIntact(@TempDir Path writable) throws Exception {
    Files.createDirectories(writable.resolve("records/5/attachments/pages"));
    Files.writeString(writable.resolve("records/5/attachments/pages/p0001.jpg"), "old scan");
    StorageService service = new StorageService(writable, (Path) null);
    String placed = service.placeAtNewAddress("records/5/attachments/pages/p0001.jpg");

    service.deleteStoredFile("records/5/attachments/pages/p0001.jpg");

    assertThat(Files.readString(writable.resolve(placed))).isEqualTo("old scan");
  }

  @Test
  void copiesOutOfTheReadOnlyArchiveIntoItsOwnRoot(@TempDir Path writable, @TempDir Path archive)
      throws Exception {
    Files.createDirectories(archive.resolve("records/5/attachments/pages"));
    Files.writeString(archive.resolve("records/5/attachments/pages/p0001.jpg"), "real scan");
    StorageService service = new StorageService(writable, archive);

    String copied = service.placeAtNewAddress("records/5/attachments/pages/p0001.jpg");

    assertThat(Files.readString(writable.resolve(copied))).isEqualTo("real scan");
    assertThat(
            Files.isSameFile(
                archive.resolve("records/5/attachments/pages/p0001.jpg"), writable.resolve(copied)))
        .as("a read-only source is copied, never linked into the writable root")
        .isFalse();
    assertThat(archive.resolve(copied)).doesNotExist();
  }

  @Test
  void aMissingSourceIsAnAnswerNotAnException(@TempDir Path writable) {
    StorageService service = new StorageService(writable, (Path) null);

    assertThat(service.placeAtNewAddress("records/9/attachments/pages/p0001.jpg")).isNull();
  }

  @Test
  void deletingAStoredFileNeverReachesTheReadOnlyArchive(
      @TempDir Path writable, @TempDir Path archive) throws Exception {
    Files.createDirectories(archive.resolve("records/5/attachments/pages"));
    Files.writeString(archive.resolve("records/5/attachments/pages/p0001.jpg"), "real scan");
    StorageService service = new StorageService(writable, archive);

    service.deleteStoredFile("records/5/attachments/pages/p0001.jpg");

    assertThat(archive.resolve("records/5/attachments/pages/p0001.jpg")).exists();
  }

  @Test
  void deletingAStoredFileRemovesItFromItsOwnRoot(@TempDir Path writable) {
    StorageService service = new StorageService(writable, (Path) null);
    String stored = service.storePageImage("scan".getBytes());

    service.deleteStoredFile(stored);

    assertThat(writable.resolve(stored)).doesNotExist();
  }
}
