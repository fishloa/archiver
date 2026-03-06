package place.icomb.archiver.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.List;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;

@Configuration
public class JdbcConfig extends AbstractJdbcConfiguration {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public List<?> userConverters() {
    return List.of(
        new PGobjectToStringConverter(),
        new PGobjectToStringListConverter(),
        new StringListToPGobjectConverter());
  }

  /** Converts PostgreSQL JSONB values (returned as PGobject) to plain String. */
  @ReadingConverter
  static class PGobjectToStringConverter implements Converter<PGobject, String> {

    @Override
    public String convert(PGobject source) {
      return source.getValue();
    }
  }

  /** Reads a JSONB array into List&lt;String&gt;. */
  @ReadingConverter
  static class PGobjectToStringListConverter implements Converter<PGobject, List<String>> {

    @Override
    public List<String> convert(PGobject source) {
      String value = source.getValue();
      if (value == null || value.isBlank()) return null;
      try {
        return MAPPER.readValue(value, new TypeReference<>() {});
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("Failed to parse JSONB array: " + value, e);
      }
    }
  }

  /** Writes List&lt;String&gt; as a JSONB PGobject. */
  @WritingConverter
  static class StringListToPGobjectConverter implements Converter<List<String>, PGobject> {

    @Override
    public PGobject convert(List<String> source) {
      PGobject pg = new PGobject();
      pg.setType("jsonb");
      try {
        pg.setValue(MAPPER.writeValueAsString(source));
      } catch (JsonProcessingException | SQLException e) {
        throw new IllegalStateException("Failed to write JSONB array", e);
      }
      return pg;
    }
  }
}
