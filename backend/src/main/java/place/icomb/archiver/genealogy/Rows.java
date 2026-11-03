package place.icomb.archiver.genealogy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.postgresql.util.PGobject;

/** Database rows as the API shows them: camelCase keys, ISO dates, JSON as JSON. */
public final class Rows {

  private static final ObjectMapper JSON = new ObjectMapper();

  private Rows() {}

  public static List<Map<String, Object>> camel(List<Map<String, Object>> rows) {
    List<Map<String, Object>> out = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) out.add(camel(row));
    return out;
  }

  public static Map<String, Object> camel(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (var e : row.entrySet()) out.put(camelCase(e.getKey()), value(e.getValue()));
    return out;
  }

  /** A JSON object, as text, read into a map; null stays null. */
  public static Map<String, Object> json(String text) {
    if (text == null) return null;
    try {
      return JSON.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("not a JSON object: " + text, e);
    }
  }

  public static String toJson(Map<String, Object> value) {
    if (value == null) return null;
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String camelCase(String snake) {
    StringBuilder sb = new StringBuilder();
    boolean up = false;
    for (char c : snake.toCharArray()) {
      if (c == '_') {
        up = true;
      } else {
        sb.append(up ? Character.toUpperCase(c) : c);
        up = false;
      }
    }
    return sb.toString();
  }

  private static Object value(Object v) {
    if (v instanceof Timestamp t) return t.toInstant().toString();
    if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
    if (v instanceof UUID u) return u.toString();
    if (v instanceof PGobject o && o.getType() != null && o.getType().startsWith("json")) {
      if (o.getValue() == null) return null;
      try {
        return JSON.readValue(o.getValue(), Object.class);
      } catch (Exception e) {
        throw new IllegalStateException("not JSON: " + o.getValue(), e);
      }
    }
    if (v instanceof PGobject o) return o.getValue();
    return v;
  }
}
