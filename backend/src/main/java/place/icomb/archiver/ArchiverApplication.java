package place.icomb.archiver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ArchiverApplication {

  public static void main(String[] args) {
    SpringApplication.run(ArchiverApplication.class, args);
  }
}
