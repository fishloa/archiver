package place.icomb.archiver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The schema has to survive a restore.
 *
 * <p>It did not. page_text carries a generated column calling immutable_unaccent, which called
 * unaccent() unqualified; pg_dump restores with search_path empty, so evaluating that column during
 * CREATE TABLE failed and the table was never created. Every COPY into it failed after that — the
 * transcriptions of 128,831 pages, absent from the restore while everything else came back cleanly
 * and the exit status stayed zero.
 *
 * <p>It worked in daily use because a session's search_path includes public. Nobody had restored a
 * dump, so nobody knew. This asserts the property directly, with no search_path to lean on.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class DatabaseRestoreTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private JdbcTemplate jdbc;

  @Test
  void generatedColumnsResolveWithNoSearchPath() {
    // Exactly what pg_dump does before replaying a schema.
    jdbc.execute("SET search_path = ''");
    try {
      String normalised =
          jdbc.queryForObject(
              "SELECT public.immutable_unaccent('Bořkův Přemysl Žofie')", String.class);
      assertThat(normalised).isEqualTo("Borkuv Premysl Zofie");
    } finally {
      jdbc.execute("SET search_path = public");
    }
  }

  @Test
  void aTableWithThatGeneratedColumnCanBeCreatedWithNoSearchPath() {
    // The failure was not in calling the function but in creating a table whose generated column
    // calls it, which is what a restore does.
    jdbc.execute("SET search_path = ''");
    try {
      jdbc.execute(
          """
          CREATE TABLE public.restore_probe (
              id     bigint PRIMARY KEY,
              raw    text,
              norm   text GENERATED ALWAYS AS (public.immutable_unaccent(lower(raw))) STORED
          )
          """);
      jdbc.update("INSERT INTO public.restore_probe (id, raw) VALUES (1, 'ČERNÍN')");
      assertThat(
              jdbc.queryForObject(
                  "SELECT norm FROM public.restore_probe WHERE id = 1", String.class))
          .isEqualTo("cernin");
    } finally {
      jdbc.execute("DROP TABLE IF EXISTS public.restore_probe");
      jdbc.execute("SET search_path = public");
    }
  }

  @Test
  void theFunctionsPinTheirOwnSearchPath() {
    // A caller that has set an odd search_path must not change how these resolve.
    Integer pinned =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM pg_proc
            WHERE proname IN ('immutable_unaccent', 'immutable_to_tsvector')
              AND proconfig IS NOT NULL
            """,
            Integer.class);
    assertThat(pinned).isEqualTo(2);
  }
}
