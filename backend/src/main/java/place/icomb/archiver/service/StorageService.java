package place.icomb.archiver.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import place.icomb.archiver.model.Attachment;

@Service
public class StorageService {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(StorageService.class);

  private final Path storageRoot;

  /**
   * A second root, read only, consulted when a file is not under {@link #storageRoot}.
   *
   * <p>Exists so a test deployment can read the real 253 GB of scans without copying them and
   * without any path by which it could write to them: the archive is mounted read only, and
   * everything the test writes goes to its own root. Unset in production, where there is one root
   * and this is never consulted.
   */
  private final Path readOnlyRoot;

  @org.springframework.beans.factory.annotation.Autowired
  public StorageService(
      Path storageRoot,
      @org.springframework.beans.factory.annotation.Value("${archiver.storage.readonly-root:}")
          String readOnlyStorageRoot) {
    this(storageRoot, pathOrNull(readOnlyStorageRoot));
  }

  /** For tests, and for the constructor above once the property has been resolved. */
  public StorageService(Path storageRoot, Path readOnlyStorageRoot) {
    this.storageRoot = storageRoot;
    this.readOnlyRoot = readOnlyStorageRoot;
    if (readOnlyRoot != null) {
      log.info(
          "Falling back to read-only storage at {} for files not under {}",
          readOnlyRoot,
          storageRoot);
    }
  }

  private static Path pathOrNull(String root) {
    return root == null || root.isBlank() ? null : Path.of(root);
  }

  /**
   * Stores a page image and returns its path relative to the storage root: attachments/{first two
   * hex of the name}/{uuid}.jpg.
   *
   * <p>The name is minted here and says nothing about the page. The old layout,
   * records/{recordId}/attachments/pages/p{seq}.jpg, was a function of the record the page belongs
   * to and its position in it — the two things moving a page changes — so a move had to copy files
   * and could not be one transaction, and the sequence number collided when an insert renumbered
   * pages (record 4006 lost seven images that way on 19 September 2026).
   */
  public String storePageImage(byte[] imageBytes) {
    String relativePath = newAttachmentPath();
    writeFile(relativePath, imageBytes);
    return relativePath;
  }

  private static String newAttachmentPath() {
    String name = java.util.UUID.randomUUID().toString();
    return "attachments/" + name.substring(0, 2) + "/" + name + ".jpg";
  }

  /**
   * Gives a stored file a newly minted attachment address in this deployment's own root, leaving
   * the file at its old path too.
   *
   * <p>When the file is in this root it is hard-linked: instant, nothing is read or rewritten, and
   * both names are the same bytes until the caller drops the old one. That is a move done in the
   * only order that is safe — the new name exists before the row is pointed at it, and the old name
   * goes after — so at every instant the row names a file that exists. A plain rename would take
   * the file from under any reader that still holds the old path.
   *
   * <p>When it cannot be linked — the file is only in the read-only archive, as on a test stack, or
   * the filesystem has no hard links — it is copied, and the copy is compared byte for byte with
   * its source before it is kept, because the caller then deletes the original and for a scan the
   * original may be the only one. A copy that differs is discarded and reported as a failure.
   *
   * @return the new relative path, or null when the source file does not exist
   */
  public String placeAtNewAddress(String legacyRelativePath) {
    Path source = resolveForRead(legacyRelativePath);
    if (!Files.exists(source)) {
      return null;
    }
    String relativePath = newAttachmentPath();
    Path target = storageRoot.resolve(relativePath);
    try {
      Files.createDirectories(target.getParent());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to prepare " + relativePath, e);
    }

    if (source.startsWith(storageRoot)) {
      try {
        Files.createLink(target, source);
        return relativePath;
      } catch (UnsupportedOperationException | IOException e) {
        log.info("Cannot hard-link {} ({}); copying instead", legacyRelativePath, e.toString());
      }
    }

    Path partial = target.resolveSibling(target.getFileName() + ".partial");
    try {
      Files.copy(source, partial, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      if (Files.mismatch(source, partial) != -1L) {
        throw new IOException("copy differs from its source");
      }
      Files.move(partial, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      try {
        Files.deleteIfExists(partial);
      } catch (IOException ignored) {
        // The partial file is harmless; the next attempt mints a new name.
      }
      throw new UncheckedIOException("Failed to copy " + legacyRelativePath, e);
    }
    return relativePath;
  }

  /**
   * Deletes one stored file from this deployment's own root, if it is there.
   *
   * <p>Never touches the read-only archive, and never throws: it runs after the database has
   * already committed to the file being gone, when failing would only hide the commit.
   */
  public void deleteStoredFile(String relativePath) {
    try {
      Files.deleteIfExists(storageRoot.resolve(relativePath));
    } catch (IOException e) {
      log.warn("Could not delete stored file {}: {}", relativePath, e.toString());
    }
  }

  /**
   * Stores a PDF and returns the relative path from the storage root. Path format:
   * records/{recordId}/attachments/record.pdf
   */
  public String storePdf(Long recordId, byte[] pdfBytes) {
    String relativePath = String.format("records/%d/attachments/record.pdf", recordId);
    writeFile(relativePath, pdfBytes);
    return relativePath;
  }

  /**
   * Stores a derivative file and returns the relative path from the storage root. Path format:
   * records/{recordId}/derivatives/{derivType}/{filename}
   */
  public String storeDeriv(Long recordId, String derivType, String filename, byte[] bytes) {
    String relativePath =
        String.format("records/%d/derivatives/%s/%s", recordId, derivType, filename);
    writeFile(relativePath, bytes);
    return relativePath;
  }

  /** Streams a derivative file to disk without buffering in memory. */
  public String storeDerivStream(Long recordId, String derivType, String filename, InputStream in) {
    String relativePath =
        String.format("records/%d/derivatives/%s/%s", recordId, derivType, filename);
    try {
      Path fullPath = storageRoot.resolve(relativePath);
      Files.createDirectories(fullPath.getParent());
      Files.copy(in, fullPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write file: " + relativePath, e);
    }
    return relativePath;
  }

  /** Resolves the full filesystem path for an attachment. */
  public Path getPath(Attachment attachment) {
    return resolveForRead(attachment.getPath());
  }

  /**
   * Where to read a stored file from.
   *
   * <p>Its own root first, so anything this deployment wrote wins; then the read-only archive, if
   * one is configured. Returns the primary path when the file is in neither, so a missing file
   * fails where a caller expects it to.
   */
  public Path resolveForRead(String relativePath) {
    Path primary = storageRoot.resolve(relativePath);
    if (readOnlyRoot == null || java.nio.file.Files.exists(primary)) {
      return primary;
    }
    Path fallback = readOnlyRoot.resolve(relativePath);
    return java.nio.file.Files.exists(fallback) ? fallback : primary;
  }

  /** Opens an InputStream for the given attachment. */
  public Resource streamFile(Attachment attachment) {
    try {
      Path path = getPath(attachment);
      InputStream is = Files.newInputStream(path, StandardOpenOption.READ);
      return new InputStreamResource(is);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to stream file: " + attachment.getPath(), e);
    }
  }

  /** Deletes all stored files for a record (the entire records/{recordId}/ directory). */
  public void deleteRecordFiles(Long recordId) {
    Path recordDir = storageRoot.resolve("records/" + recordId);
    if (!Files.exists(recordDir)) {
      return;
    }
    try {
      Files.walkFileTree(
          recordDir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to delete record files: " + recordDir, e);
    }
  }

  // --- the exports area
  // -----------------------------------------------------------------------

  /**
   * Where a PDF export's scratch files and unfinished output live.
   *
   * <p>Under the storage root, on the archive's own volume, rather than in {@code /tmp}: the size
   * of the largest export is then bounded by the disk, not by the container's scratch space.
   */
  public Path exportTempDir() {
    Path dir = storageRoot.resolve("exports/tmp");
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create " + dir, e);
    }
    return dir;
  }

  /** Empties the export temp area. Only safe when no build can be running, that is at startup. */
  public void clearExportTemp() {
    Path dir = exportTempDir();
    try (var entries = Files.list(dir)) {
      for (Path entry : (Iterable<Path>) entries::iterator) {
        deleteTree(entry);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to empty " + dir, e);
    }
  }

  /**
   * A fresh address for a finished export: exports/{xx}/{uuid}.pdf. Says nothing about the record.
   */
  public String newExportPath() {
    String name = java.util.UUID.randomUUID().toString();
    return "exports/" + name.substring(0, 2) + "/" + name + ".pdf";
  }

  /** Moves a finished file from the temp area to its address: a rename, on one filesystem. */
  public void placeExport(Path finished, String relativePath) {
    Path target = storageRoot.resolve(relativePath);
    try {
      Files.createDirectories(target.getParent());
      Files.move(finished, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to place " + relativePath, e);
    }
  }

  /** The file of a finished export. */
  public Path exportFile(String relativePath) {
    return storageRoot.resolve(relativePath);
  }

  /** Usable bytes on the filesystem that holds the archive. */
  public long freeBytes() {
    try {
      return Files.getFileStore(storageRoot).getUsableSpace();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read free space for " + storageRoot, e);
    }
  }

  private static void deleteTree(Path path) throws IOException {
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private void writeFile(String relativePath, byte[] data) {
    try {
      Path fullPath = storageRoot.resolve(relativePath);
      Files.createDirectories(fullPath.getParent());
      Files.write(fullPath, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write file: " + relativePath, e);
    }
  }
}
