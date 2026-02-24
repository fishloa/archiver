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
}
