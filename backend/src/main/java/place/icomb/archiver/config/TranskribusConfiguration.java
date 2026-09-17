package place.icomb.archiver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import place.icomb.archiver.repository.PageTextRepository;
import place.icomb.archiver.service.PipelineStateMachine;
import place.icomb.archiver.service.TranskribusImportService;

/** Wiring for the Transkribus import path. */
@Configuration
public class TranskribusConfiguration {

  /**
   * Imports transcriptions made in the Transkribus web app.
   *
   * <p>A bean rather than a component so the dependency on {@link PipelineStateMachine} is stated
   * here: importing a page is only half the job — the page then has to be carried through
   * translation, the record's PDF and re-embedding.
   */
  @Bean
  public TranskribusImportService transkribusImportService(
      JdbcTemplate jdbcTemplate,
      PageTextRepository pageTextRepository,
      PipelineStateMachine stateMachine) {
    return new TranskribusImportService(jdbcTemplate, pageTextRepository, stateMachine);
  }
}
