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
   * Stores a page image and returns the relative path from the storage root. Path format:
   * records/{recordId}/attachments/pages/p{seq:04d}.jpg
   */
  public String storePageImage(Long recordId, int seq, byte[] imageBytes) {
    String relativePath = String.format("records/%d/attachments/pages/p%04d.jpg", recordId, seq);
    writeFile(relativePath, imageBytes);
    return relativePath;
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
