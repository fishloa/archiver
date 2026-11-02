package place.icomb.archiver.genealogy.legacy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/**
 * Migration V22: the one-time import of the legacy genealogy file into the tables V21 made.
 *
 * <p>A Spring bean so that Spring Boot hands it to Flyway with the SQL migrations; Flyway's own
 * classpath scan is not relied on inside the packaged jar. The file is read from {@code
 * db/legacy/czernin-genealogy.txt} and nothing else reads it.
 */
@Component
public class V22__Import_legacy_genealogy extends BaseJavaMigration {

  static final String FILE = "/db/legacy/czernin-genealogy.txt";

  @Override
  public void migrate(Context context) throws Exception {
    List<String> lines;
    try (InputStream in = V22__Import_legacy_genealogy.class.getResourceAsStream(FILE)) {
      lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
    JdbcTemplate jdbc =
        new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true));
    if (jdbc.queryForObject("SELECT count(*) FROM gen_person", Long.class) > 0) {
      throw new IllegalStateException(
          "gen_person is not empty; V22 imports only into empty tables");
    }
    new LegacyImport(jdbc).run(lines);
  }
}
