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

  private final Path storageRoot;

  public StorageService(Path storageRoot) {
    this.storageRoot = storageRoot;
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

  /** Resolves the full filesystem path for an attachment. */
  public Path getPath(Attachment attachment) {
    return storageRoot.resolve(attachment.getPath());
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
