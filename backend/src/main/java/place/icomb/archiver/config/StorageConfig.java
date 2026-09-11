package place.icomb.archiver.config;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {

  @Bean
  public Path storageRoot(@Value("${archiver.storage.root}") String root) {
    return Path.of(root);
  }

  /**
   * An optional second root, mounted read only, for a deployment that should read the archive but
   * never write to it.
   *
   * <p>Empty in production. A test stack sets it to the real store and keeps its own writable root,
   * so it can open 253 GB of scans without copying them and without any path by which it could
   * damage them.
   */
  @Bean
  public Path readOnlyStorageRoot(@Value("${archiver.storage.readonly-root:}") String root) {
    return root == null || root.isBlank() ? null : Path.of(root);
  }
}
