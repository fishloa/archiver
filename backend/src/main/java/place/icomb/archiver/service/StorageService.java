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
   * Copies a stored file to a newly minted attachment address in this deployment's own root.
   *
   * <p>The original is left alone: the caller deletes it only once the row points at the copy.
   * Reads through the read-only archive if one is configured, so a test deployment can migrate its
   * own catalogue without being able to touch the real scans.
   *
   * @return the new relative path, or null when the source file does not exist
   */
  public String copyToNewAddress(String legacyRelativePath) {
    Path source = resolveForRead(legacyRelativePath);
    if (!Files.exists(source)) {
      return null;
    }
    String relativePath = newAttachmentPath();
    Path target = storageRoot.resolve(relativePath);
    Path partial = target.resolveSibling(target.getFileName() + ".partial");
    try {
      Files.createDirectories(target.getParent());
      Files.copy(source, partial, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
