package place.icomb.archiver.genealogy;

import java.util.Arrays;
import java.util.List;

/**
 * Every kind of row a genealogy write can change, with the fields a caller may set.
 *
 * <p>{@link #key()} is what {@code gen_change.entity} records. {@code page_person_match} and {@code
 * app_user} are here only because a merge re-points them; they cannot be written directly.
 */
public enum GenEntity {
  PERSON(
      "person",
      "gen_person",
      true,
      f("sex", Type.TEXT),
      f("living", Type.BOOL),
      f("notes", Type.TEXT),
      f("needsReview", Type.TEXT)),
  NAME(
      "name",
      "gen_name",
      true,
      f("personId", Type.INT),
      f("type", Type.TEXT),
      f("given", Type.TEXT),
      f("surname", Type.TEXT),
      f("prefix", Type.TEXT),
      f("suffix", Type.TEXT),
      f("nickname", Type.TEXT),
      f("isPrimary", Type.BOOL),
      f("sortOrder", Type.INT)),
  COUPLE(
      "couple",
      "gen_couple",
      true,
      f("person1Id", Type.INT),
      f("person2Id", Type.INT),
      f("type", Type.TEXT),
      f("sortOrder", Type.INT),
      f("notes", Type.TEXT)),
  PARENT_LINK(
      "parent_link",
      "gen_parent_child",
      true,
      f("parentId", Type.INT),
      f("childId", Type.INT),
      f("type", Type.TEXT),
      f("coupleId", Type.LONG)),
  PLACE(
      "place",
      "gen_place",
      true,
      f("name", Type.TEXT),
      f("parentId", Type.LONG),
      f("lat", Type.DECIMAL),
      f("lon", Type.DECIMAL)),
  PLACE_NAME(
      "place_name",
      "gen_place_name",
      true,
      f("placeId", Type.LONG),
      f("name", Type.TEXT),
      f("lang", Type.TEXT),
      f("fromYear", Type.INT),
      f("toYear", Type.INT)),
  EVENT(
      "event",
      "gen_event",
      true,
      f("personId", Type.INT),
      f("coupleId", Type.LONG),
      f("type", Type.TEXT),
      f("dateText", Type.TEXT),
      f("placeId", Type.LONG),
      f("description", Type.TEXT),
      f("sortOrder", Type.INT)),
  SOURCE(
      "source",
      "gen_source",
      true,
      f("title", Type.TEXT),
      f("author", Type.TEXT),
      f("recordId", Type.LONG),
      f("url", Type.TEXT),
      f("notes", Type.TEXT)),
  CITATION(
      "citation",
      "gen_citation",
      true,
      f("sourceId", Type.LONG),
      f("pageId", Type.LONG),
      f("detail", Type.TEXT),
      f("quality", Type.INT),
      f("eventId", Type.LONG),
      f("nameId", Type.LONG),
      f("coupleId", Type.LONG),
      f("parentChildId", Type.LONG),
      f("personId", Type.INT)),
  RECORD_LINK(
      "record_link",
      "gen_person_record",
      true,
      f("personId", Type.INT),
      f("recordId", Type.LONG),
      f("pageId", Type.LONG),
      f("role", Type.TEXT),
      f("note", Type.TEXT),
      f("quality", Type.INT)),
  MEDIA(
      "media",
      "gen_media",
      true,
      f("pageId", Type.LONG),
      f("cropLeft", Type.INT),
      f("cropTop", Type.INT),
      f("cropWidth", Type.INT),
      f("cropHeight", Type.INT),
      f("caption", Type.TEXT),
      f("dateText", Type.TEXT)),
  MEDIA_PERSON(
      "media_person",
      "gen_media_person",
      true,
      f("mediaId", Type.LONG),
      f("personId", Type.INT),
      f("isPortrait", Type.BOOL),
      f("sortOrder", Type.INT)),
  PAGE_PERSON_MATCH("page_person_match", "page_person_match", false),
  APP_USER("app_user", "app_user", false);

  public enum Type {
    INT,
    LONG,
    TEXT,
    BOOL,
    DECIMAL
  }

  public record Field(String json, String column, Type type) {}

  private final String key;
  private final String table;
  private final boolean writable;
  private final List<Field> fields;

  GenEntity(String key, String table, boolean writable, Field... fields) {
    this.key = key;
    this.table = table;
    this.writable = writable;
    this.fields = List.of(fields);
  }

  private static Field f(String json, Type type) {
    return new Field(json, snake(json), type);
  }

  public String key() {
    return key;
  }

  public String table() {
    return table;
  }

  /** False for the two tables outside the genealogy that only a merge touches. */
  public boolean writable() {
    return writable;
  }

  public List<Field> fields() {
    return fields;
  }

  public static GenEntity ofKey(String key) {
    return Arrays.stream(values())
        .filter(e -> e.key.equals(key))
        .findFirst()
        .orElseThrow(() -> GenealogyException.badRequest("unknown entity '%s'".formatted(key)));
  }

  static String snake(String camel) {
    StringBuilder sb = new StringBuilder();
    for (char c : camel.toCharArray()) {
      if (Character.isUpperCase(c)) {
        sb.append('_').append(Character.toLowerCase(c));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
