package place.icomb.archiver.config;

import java.util.List;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;

@Configuration
public class JdbcConfig extends AbstractJdbcConfiguration {

  @Override
  public List<?> userConverters() {
    return List.of(new PGobjectToStringConverter());
  }

  /** Converts PostgreSQL JSONB values (returned as PGobject) to plain String. */
  @ReadingConverter
  static class PGobjectToStringConverter implements Converter<PGobject, String> {

    @Override
    public String convert(PGobject source) {
      return source.getValue();
    }
  }
}
