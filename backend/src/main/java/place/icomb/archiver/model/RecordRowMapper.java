package place.icomb.archiver.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.postgresql.util.PGobject;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.RowMapper;

/** BeanPropertyRowMapper for Record with JSONB indexTerms conversion. */
public final class RecordRowMapper {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static final RowMapper<Record> INSTANCE;

  static {
    DefaultConversionService cs = new DefaultConversionService();
    cs.addConverter(
        PGobject.class,
        List.class,
        (Converter<PGobject, List<String>>)
            source -> {
              String value = source.getValue();
              if (value == null || value.isBlank()) return null;
              try {
                return MAPPER.readValue(value, new TypeReference<>() {});
              } catch (Exception e) {
                return null;
              }
            });
    INSTANCE = BeanPropertyRowMapper.newInstance(Record.class, cs);
  }

  private RecordRowMapper() {}
}
