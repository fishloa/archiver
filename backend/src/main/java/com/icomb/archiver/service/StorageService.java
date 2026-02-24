package com.icomb.archiver.service;

import com.icomb.archiver.model.Attachment;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

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
    String relativePath =
        String.format("records/%d/attachments/pages/p%04d.jpg", recordId, seq);
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
