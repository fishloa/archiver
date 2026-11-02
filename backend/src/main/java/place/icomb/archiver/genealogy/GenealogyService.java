package place.icomb.archiver.genealogy;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import place.icomb.archiver.genealogy.GenEntity.Field;
import place.icomb.archiver.service.FamilyTreeService;

/**
 * The only writer of the genealogy. Every write takes a reason, is validated, and appends one
 * {@code gen_change} row per row it changed, holding the row before and after.
 *
 * <p>Rows that belong to another (a person's names and events, an event's citations) are deleted
 * with it, each audited. Rows that merely point at it (a couple, a parent link, a match, a user's
 * "this is me") block the delete with a 409; merging is how a duplicate person goes.
 */
@Service
public class GenealogyService {

  static final Set<String> SEXES = Set.of("M", "F", "U");
  static final Set<String> NAME_TYPES =
      Set.of("birth", "married", "alias", "religious", "title", "other");
  static final Set<String> COUPLE_TYPES = Set.of("marriage", "partnership", "unknown");
  static final Set<String> LINK_TYPES = Set.of("birth", "adopted", "step", "foster", "unknown");
  static final Set<String> EVENT_TYPES =
      Set.of(
          "BIRT", "CHR", "BAPM", "DEAT", "BURI", "MARR", "DIV", "ADOP", "OCCU", "TITL", "RESI",
          "NATU", "EMIG", "IMMI", "EDUC", "EVEN");
  static final Set<String> ROLES =
      Set.of(
          "subject",
          "mentioned",
          "author",
          "signatory",
          "witness",
          "recipient",
          "pictured",
          "other");

  private record Ref(GenEntity entity, String column) {}

  /** Rows deleted along with the row they belong to. */
  private static final Map<GenEntity, List<Ref>> OWNED =
      Map.of(
          GenEntity.PERSON,
          List.of(
              new Ref(GenEntity.CITATION, "person_id"),
              new Ref(GenEntity.EVENT, "person_id"),
              new Ref(GenEntity.NAME, "person_id")),
          GenEntity.NAME,
          List.of(new Ref(GenEntity.CITATION, "name_id")),
          GenEntity.EVENT,
          List.of(new Ref(GenEntity.CITATION, "event_id")),
          GenEntity.COUPLE,
          List.of(new Ref(GenEntity.CITATION, "couple_id"), new Ref(GenEntity.EVENT, "couple_id")),
          GenEntity.PARENT_LINK,
          List.of(new Ref(GenEntity.CITATION, "parent_child_id")),
          GenEntity.PLACE,
          List.of(new Ref(GenEntity.PLACE_NAME, "place_id")),
          GenEntity.MEDIA,
          List.of(new Ref(GenEntity.MEDIA_PERSON, "media_id")));

  /** Rows that point at a row and so forbid deleting it. */
  private static final Map<GenEntity, List<Ref>> BLOCKING =
      Map.of(
          GenEntity.PERSON,
          List.of(
              new Ref(GenEntity.COUPLE, "person1_id"),
              new Ref(GenEntity.COUPLE, "person2_id"),
              new Ref(GenEntity.PARENT_LINK, "parent_id"),
              new Ref(GenEntity.PARENT_LINK, "child_id"),
              new Ref(GenEntity.RECORD_LINK, "person_id"),
              new Ref(GenEntity.MEDIA_PERSON, "person_id"),
              new Ref(GenEntity.PAGE_PERSON_MATCH, "person_id"),
              new Ref(GenEntity.APP_USER, "family_tree_person_id"),
              new Ref(GenEntity.PERSON, "merged_into")),
          GenEntity.COUPLE,
          List.of(new Ref(GenEntity.PARENT_LINK, "couple_id")),
          GenEntity.PLACE,
          List.of(new Ref(GenEntity.EVENT, "place_id"), new Ref(GenEntity.PLACE, "parent_id")),
          GenEntity.SOURCE,
          List.of(new Ref(GenEntity.CITATION, "source_id")));

  private final JdbcTemplate jdbc;
  private final FamilyTreeService tree;
  private final MediaImages images;

  public GenealogyService(JdbcTemplate jdbc, FamilyTreeService tree, MediaImages images) {
    this.jdbc = jdbc;
    this.tree = tree;
    this.images = images;
  }

  /** A new person with their primary name ({@code given}, {@code surname}, ...). */
  @Transactional
  public Map<String, Object> createPerson(Map<String, Object> body, String reason) {
    requireReason(reason);
    Map<String, Object> personBody = new LinkedHashMap<>();
    Map<String, Object> nameBody = new LinkedHashMap<>();
    Set<String> personFields = jsonNames(GenEntity.PERSON);
    for (var e : body.entrySet()) {
      if (personFields.contains(e.getKey())) {
        personBody.put(e.getKey(), e.getValue());
      } else {
        nameBody.put(e.getKey(), e.getValue());
      }
    }
    Map<String, Object> person = create(GenEntity.PERSON, personBody, reason);
    nameBody.put("personId", person.get("id"));
    nameBody.put("isPrimary", true);
    nameBody.putIfAbsent("type", "birth");
    Map<String, Object> name = create(GenEntity.NAME, nameBody, reason);
    Map<String, Object> out = new LinkedHashMap<>(person);
    out.put("primaryNameId", name.get("id"));
    return out;
  }

  @Transactional
  public Map<String, Object> create(GenEntity entity, Map<String, Object> body, String reason) {
    try {
      requireReason(reason);
      requireWritable(entity);
      Map<String, Object> values = coerce(entity, withPlace(entity, body, reason));
      validate(entity, null, values, reason);
      derive(entity, values);
      List<String> columns = new ArrayList<>(values.keySet());
      String sql =
          columns.isEmpty()
              ? "INSERT INTO %s AS t DEFAULT VALUES RETURNING to_jsonb(t.*)::text"
                  .formatted(entity.table())
              : "INSERT INTO %s AS t (%s) VALUES (%s) RETURNING to_jsonb(t.*)::text"
                  .formatted(
                      entity.table(),
                      String.join(", ", columns),
                      String.join(", ", Collections.nCopies(columns.size(), "?")));
      Map<String, Object> after =
          Rows.json(jdbc.queryForObject(sql, String.class, values.values().toArray()));
      audit(entity, id(after), "create", null, after, reason);
      changed();
      return Rows.camel(after);
    } catch (DataIntegrityViolationException e) {
      throw GenealogyException.badRequest(e.getMostSpecificCause().getMessage());
    }
  }

  @Transactional
  public Map<String, Object> update(
      GenEntity entity, long id, Map<String, Object> body, String reason) {
    try {
      requireReason(reason);
      requireWritable(entity);
      Map<String, Object> current = lock(entity, id);
      Map<String, Object> values = coerce(entity, withPlace(entity, body, reason));
      if (values.isEmpty()) {
        throw GenealogyException.badRequest("nothing to change");
      }
      Map<String, Object> merged = new LinkedHashMap<>(current);
      merged.putAll(values);
      validate(entity, current, merged, reason);
      derive(entity, merged);
      if (values.containsKey("date_text")) {
        for (String k :
            entity == GenEntity.EVENT
                ? List.of("date_text", "date_from", "date_to")
                : List.of("date_text")) {
          values.put(k, merged.get(k));
        }
      }
      Map<String, Object> after = write(entity, id, values);
      audit(entity, id, "update", current, after, reason);
      changed();
      return Rows.camel(after);
    } catch (DataIntegrityViolationException e) {
      throw GenealogyException.badRequest(e.getMostSpecificCause().getMessage());
    }
  }

  @Transactional
  public Map<String, Object> delete(GenEntity entity, long id, String reason) {
    try {
      requireReason(reason);
      requireWritable(entity);
      Map<String, Object> current = lock(entity, id);
      if (entity == GenEntity.NAME && Boolean.TRUE.equals(current.get("is_primary"))) {
        throw GenealogyException.conflict(
            "name %d is the primary name; make another name primary first".formatted(id));
      }
      List<String> blockers = blockers(entity, id);
      if (!blockers.isEmpty()) {
        throw GenealogyException.conflict(
            "%s %d is still referenced by %s%s"
                .formatted(
                    entity.key(),
                    id,
                    String.join(", ", blockers),
                    entity == GenEntity.PERSON ? "; merge the person instead" : ""));
      }
      int removed = deleteWithOwned(entity, id, "delete", reason);
      changed();
      return Map.of("entity", entity.key(), "id", id, "deleted", removed);
    } catch (DataIntegrityViolationException e) {
      throw GenealogyException.badRequest(e.getMostSpecificCause().getMessage());
    }
  }

  /**
   * A media item made from a page and attached to one person, as one write; with {@code portrait}
   * it becomes their portrait.
   */
  @Transactional
  public Map<String, Object> createMedia(
      Map<String, Object> body, int personId, boolean portrait, String reason) {
    Map<String, Object> media = create(GenEntity.MEDIA, body, reason);
    Map<String, Object> link = new LinkedHashMap<>();
    link.put("mediaId", media.get("id"));
    link.put("personId", personId);
    link.put("isPortrait", portrait);
    create(GenEntity.MEDIA_PERSON, link, reason);
    return media;
  }

  /**
   * An event may name its place as {@code placeName}: the place of that exact name, made (and
   * audited) in the same write when there is none.
   */
  private Map<String, Object> withPlace(GenEntity entity, Map<String, Object> body, String reason) {
    if (entity != GenEntity.EVENT || !body.containsKey("placeName")) {
      return body;
    }
    Map<String, Object> out = new LinkedHashMap<>(body);
    Object name = out.remove("placeName");
    if (name != null && !(name instanceof String)) {
      throw GenealogyException.badRequest("placeName must be a string");
    }
    if (name == null || ((String) name).isBlank()) {
      out.put("placeId", null);
      return out;
    }
    String trimmed = ((String) name).trim();
    List<Long> found =
        jdbc.queryForList(
            "SELECT id FROM gen_place WHERE name = ? ORDER BY id LIMIT 1", Long.class, trimmed);
    Object placeId =
        found.isEmpty()
            ? create(GenEntity.PLACE, Map.of("name", trimmed), reason).get("id")
            : found.get(0);
    out.put("placeId", placeId);
    return out;
  }

  /** The audit, newest first, optionally only one entity kind or one row. */
  public List<Map<String, Object>> changes(String entity, Long entityId, int limit) {
    StringBuilder sql = new StringBuilder("SELECT * FROM gen_change WHERE true");
    List<Object> args = new ArrayList<>();
    if (entity != null) {
      sql.append(" AND entity = ?");
      args.add(GenEntity.ofKey(entity).key());
    }
    if (entityId != null) {
      sql.append(" AND entity_id = ?");
      args.add(entityId);
    }
    sql.append(" ORDER BY id DESC LIMIT ?");
    args.add(Math.max(1, Math.min(limit, 500)));
    return Rows.camel(jdbc.queryForList(sql.toString(), args.toArray()));
  }

  /** Every change that touched one person, their names, events, couples and links. */
  public List<Map<String, Object>> personChanges(int personId, int limit) {
    return Rows.camel(
        jdbc.queryForList(
            """
            SELECT * FROM gen_change
            WHERE (entity = 'person' AND entity_id = ?)
               OR (entity IN ('name', 'event', 'citation', 'record_link', 'media_person')
                   AND ((before ->> 'person_id')::int = ? OR (after ->> 'person_id')::int = ?))
               OR (entity = 'couple'
                   AND ? IN ((before ->> 'person1_id')::int, (before ->> 'person2_id')::int,
                             (after ->> 'person1_id')::int, (after ->> 'person2_id')::int))
               OR (entity = 'parent_link'
                   AND ? IN ((before ->> 'parent_id')::int, (before ->> 'child_id')::int,
                             (after ->> 'parent_id')::int, (after ->> 'child_id')::int))
            ORDER BY id DESC LIMIT ?
            """,
            personId,
            personId,
            personId,
            personId,
            personId,
            Math.max(1, Math.min(limit, 500))));
  }

  /** People whose import or editing left something to check, with the reason. */
  public List<Map<String, Object>> review() {
    return Rows.camel(
        jdbc.queryForList(
            """
            SELECT p.id, concat_ws(' ', n.given, n.surname) AS name, p.needs_review,
                   p.legacy_entry
            FROM gen_person p
            LEFT JOIN gen_name n ON n.person_id = p.id AND n.is_primary
            WHERE p.needs_review IS NOT NULL AND p.merged_into IS NULL
            ORDER BY p.id
            """));
  }

  private void validate(
      GenEntity entity, Map<String, Object> current, Map<String, Object> row, String reason) {
    Long self = current == null ? null : id(current);
    switch (entity) {
      case PERSON -> {
        if (current != null && current.get("merged_into") != null) {
          throw GenealogyException.conflict(
              "person %d was merged into %s".formatted(self, current.get("merged_into")));
        }
        oneOf("sex", row.get("sex"), SEXES, false);
      }
      case NAME -> {
        livePerson(row.get("person_id"), "personId", true);
        oneOf("type", row.get("type"), NAME_TYPES, false);
        if (blank(row.get("given")) && blank(row.get("surname"))) {
          throw GenealogyException.badRequest("a name needs a given name or a surname");
        }
        boolean primary = Boolean.TRUE.equals(row.get("is_primary"));
        if (current != null && Boolean.TRUE.equals(current.get("is_primary")) && !primary) {
          throw GenealogyException.conflict(
              "name %d is the primary name; make another name primary instead".formatted(self));
        }
        if (current != null
            && !row.get("person_id").equals(current.get("person_id"))
            && Boolean.TRUE.equals(current.get("is_primary"))) {
          throw GenealogyException.conflict("a primary name cannot move to another person");
        }
        if (primary) {
          demoteOther(
              GenEntity.NAME, "is_primary", "person_id", row.get("person_id"), self, reason);
        }
      }
      case COUPLE -> {
        livePerson(row.get("person1_id"), "person1Id", true);
        livePerson(row.get("person2_id"), "person2Id", true);
        if (row.get("person1_id").equals(row.get("person2_id"))) {
          throw GenealogyException.badRequest("a couple needs two different people");
        }
        oneOf("type", row.get("type"), COUPLE_TYPES, false);
      }
      case PARENT_LINK -> validateParentLink(self, row);
      case PLACE -> {
        if (blank(row.get("name"))) throw GenealogyException.badRequest("name is required");
        exists(GenEntity.PLACE, row.get("parent_id"), "parentId");
        if (self != null && row.get("parent_id") != null) {
          Long cur = ((Number) row.get("parent_id")).longValue();
          Set<Long> seen = new HashSet<>();
          while (cur != null && seen.add(cur)) {
            if (cur.equals(self)) {
              throw GenealogyException.conflict("a place cannot be inside itself");
            }
            cur =
                jdbc.queryForObject(
                    "SELECT parent_id FROM gen_place WHERE id = ?", Long.class, cur);
          }
        }
        range("lat", row.get("lat"), -90, 90);
        range("lon", row.get("lon"), -180, 180);
      }
      case PLACE_NAME -> {
        requireRef(GenEntity.PLACE, row.get("place_id"), "placeId");
        if (blank(row.get("name"))) throw GenealogyException.badRequest("name is required");
        Object lang = row.get("lang");
        if (lang != null && !lang.toString().matches("[a-z]{2}")) {
          throw GenealogyException.badRequest("lang must be a two-letter ISO 639-1 code");
        }
        Object from = row.get("from_year");
        Object to = row.get("to_year");
        if (from != null && to != null && ((Number) from).intValue() > ((Number) to).intValue()) {
          throw GenealogyException.badRequest("fromYear is after toYear");
        }
      }
      case EVENT -> {
        if ((row.get("person_id") == null) == (row.get("couple_id") == null)) {
          throw GenealogyException.badRequest("an event belongs to exactly one person or couple");
        }
        livePerson(row.get("person_id"), "personId", false);
        exists(GenEntity.COUPLE, row.get("couple_id"), "coupleId");
        oneOf("type", row.get("type"), EVENT_TYPES, true);
        date(row.get("date_text"));
        exists(GenEntity.PLACE, row.get("place_id"), "placeId");
      }
      case SOURCE -> {
        if (blank(row.get("title"))) throw GenealogyException.badRequest("title is required");
        existsIn("record", row.get("record_id"), "recordId");
      }
      case CITATION -> {
        requireRef(GenEntity.SOURCE, row.get("source_id"), "sourceId");
        existsIn("page", row.get("page_id"), "pageId");
        quality(row.get("quality"));
        int targets = 0;
        for (Ref target :
            List.of(
                new Ref(GenEntity.EVENT, "event_id"),
                new Ref(GenEntity.NAME, "name_id"),
                new Ref(GenEntity.COUPLE, "couple_id"),
                new Ref(GenEntity.PARENT_LINK, "parent_child_id"),
                new Ref(GenEntity.PERSON, "person_id"))) {
          if (row.get(target.column()) != null) {
            targets++;
            exists(target.entity(), row.get(target.column()), target.column());
          }
        }
        if (targets != 1) {
          throw GenealogyException.badRequest(
              "a citation proves exactly one fact: give one of eventId, nameId, coupleId,"
                  + " parentChildId, personId");
        }
      }
      case RECORD_LINK -> {
        livePerson(row.get("person_id"), "personId", true);
        existsIn("record", row.get("record_id"), "recordId");
        if (row.get("record_id") == null) {
          throw GenealogyException.badRequest("recordId is required");
        }
        if (row.get("page_id") != null) {
          List<Long> owner =
              jdbc.queryForList(
                  "SELECT record_id FROM page WHERE id = ?", Long.class, row.get("page_id"));
          if (owner.isEmpty()) {
            throw GenealogyException.notFound("page %s not found".formatted(row.get("page_id")));
          }
          if (owner.get(0) != ((Number) row.get("record_id")).longValue()) {
            throw GenealogyException.badRequest(
                "page %s is not in record %s".formatted(row.get("page_id"), row.get("record_id")));
          }
        }
        oneOf("role", row.get("role"), ROLES, false);
        quality(row.get("quality"));
      }
      case MEDIA -> validateMedia(row);
      case MEDIA_PERSON -> {
        requireRef(GenEntity.MEDIA, row.get("media_id"), "mediaId");
        livePerson(row.get("person_id"), "personId", true);
        Integer clash =
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_media_person WHERE media_id = ? AND person_id = ?"
                    + " AND id IS DISTINCT FROM ?::bigint",
                Integer.class,
                row.get("media_id"),
                row.get("person_id"),
                self);
        if (clash > 0) {
          throw GenealogyException.conflict("that person is already linked to that image");
        }
        if (Boolean.TRUE.equals(row.get("is_portrait"))) {
          demoteOther(
              GenEntity.MEDIA_PERSON,
              "is_portrait",
              "person_id",
              row.get("person_id"),
              self,
              reason);
        }
      }
      default -> throw GenealogyException.badRequest(entity.key() + " cannot be written");
    }
  }

  private void validateParentLink(Long self, Map<String, Object> row) {
    livePerson(row.get("parent_id"), "parentId", true);
    livePerson(row.get("child_id"), "childId", true);
    int parent = ((Number) row.get("parent_id")).intValue();
    int child = ((Number) row.get("child_id")).intValue();
    jdbc.queryForList("SELECT id FROM gen_person WHERE id = ? FOR UPDATE", child);
    if (parent == child) {
      throw GenealogyException.badRequest("a person cannot be their own parent");
    }
    Object t = row.get("type");
    String type = t == null ? "birth" : t.toString();
    if (t == null) row.put("type", "birth");
    oneOf("type", row.get("type"), LINK_TYPES, false);
    Integer duplicate =
        jdbc.queryForObject(
            "SELECT count(*) FROM gen_parent_child WHERE parent_id = ? AND child_id = ?"
                + " AND id IS DISTINCT FROM ?::bigint",
            Integer.class,
            parent,
            child,
            self);
    if (duplicate > 0) {
      throw GenealogyException.conflict(
          "person %d is already a parent of %d".formatted(parent, child));
    }
    if ("birth".equals(type)) {
      Integer birthParents =
          jdbc.queryForObject(
              "SELECT count(*) FROM gen_parent_child WHERE child_id = ? AND type = 'birth'"
                  + " AND id IS DISTINCT FROM ?::bigint",
              Integer.class,
              child,
              self);
      if (birthParents >= 2) {
        throw GenealogyException.conflict(
            "person %d already has two birth parents".formatted(child));
      }
    }
    if (isAncestor(child, parent, self)) {
      throw GenealogyException.conflict(
          "person %d is an ancestor of %d; the link would make a cycle".formatted(child, parent));
    }
    Object couple = row.get("couple_id");
    if (couple != null) {
      List<Map<String, Object>> c =
          jdbc.queryForList("SELECT person1_id, person2_id FROM gen_couple WHERE id = ?", couple);
      if (c.isEmpty()) throw GenealogyException.notFound("couple %s not found".formatted(couple));
      if (!List.of(c.get(0).get("person1_id"), c.get(0).get("person2_id")).contains(parent)) {
        throw GenealogyException.badRequest(
            "couple %s does not include parent %d".formatted(couple, parent));
      }
    }
  }

  /** True when {@code ancestor} is {@code person} or above them through any parent link. */
  private boolean isAncestor(int ancestor, int person, Long ignoringLink) {
    Deque<Integer> queue = new ArrayDeque<>(List.of(person));
    Set<Integer> seen = new HashSet<>();
    while (!queue.isEmpty()) {
      int cur = queue.poll();
      if (cur == ancestor) return true;
      if (!seen.add(cur)) continue;
      queue.addAll(
          jdbc.queryForList(
              "SELECT parent_id FROM gen_parent_child WHERE child_id = ? AND id IS DISTINCT FROM ?::bigint",
              Integer.class,
              cur,
              ignoringLink));
    }
    return false;
  }

  private void validateMedia(Map<String, Object> row) {
    Object page = row.get("page_id");
    if (page == null) throw GenealogyException.badRequest("pageId is required");
    existsIn("page", page, "pageId");
    date(row.get("date_text"));
    List<String> crop = List.of("crop_left", "crop_top", "crop_width", "crop_height");
    long given = crop.stream().filter(c -> row.get(c) != null).count();
    if (given == 0) return;
    if (given != 4) {
      throw GenealogyException.badRequest(
          "give all of cropLeft, cropTop, cropWidth and cropHeight, or none for the whole page");
    }
    int left = ((Number) row.get("crop_left")).intValue();
    int top = ((Number) row.get("crop_top")).intValue();
    int width = ((Number) row.get("crop_width")).intValue();
    int height = ((Number) row.get("crop_height")).intValue();
    if (left < 0 || top < 0 || width <= 0 || height <= 0) {
      throw GenealogyException.badRequest("the crop must have a positive size inside the image");
    }
    MediaImages.Dimensions size = images.dimensions(((Number) page).longValue());
    if ((long) left + width > size.width() || (long) top + height > size.height()) {
      throw GenealogyException.badRequest(
          "the crop %d,%d %dx%d lies outside the %dx%d image"
              .formatted(left, top, width, height, size.width(), size.height()));
    }
  }

  private Map<String, Object> coerce(GenEntity entity, Map<String, Object> body) {
    Map<String, Field> byJson = new LinkedHashMap<>();
    for (Field f : entity.fields()) byJson.put(f.json(), f);
    Map<String, Object> out = new LinkedHashMap<>();
    for (var e : body.entrySet()) {
      Field f = byJson.get(e.getKey());
      if (f == null) {
        throw GenealogyException.badRequest(
            "%s has no field '%s'; it has %s".formatted(entity.key(), e.getKey(), byJson.keySet()));
      }
      out.put(f.column(), value(f, e.getValue()));
    }
    return out;
  }

  private static Object value(Field f, Object v) {
    if (v == null) return null;
    String bad = "%s must be %s".formatted(f.json(), f.type().name().toLowerCase());
    return switch (f.type()) {
      case TEXT -> {
        if (!(v instanceof String s)) throw GenealogyException.badRequest(bad);
        yield s.isBlank() ? null : s.trim();
      }
      case BOOL -> {
        if (!(v instanceof Boolean b)) throw GenealogyException.badRequest(bad);
        yield b;
      }
      case INT -> {
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
          throw GenealogyException.badRequest(bad);
        }
        yield n.intValue();
      }
      case LONG -> {
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
          throw GenealogyException.badRequest(bad);
        }
        yield n.longValue();
      }
      case DECIMAL -> {
        if (!(v instanceof Number n)) throw GenealogyException.badRequest(bad);
        yield new BigDecimal(n.toString());
      }
    };
  }

  private static void derive(GenEntity entity, Map<String, Object> row) {
    if (entity == GenEntity.EVENT && row.containsKey("date_text")) {
      GedcomDate d = GedcomDate.parse((String) row.get("date_text"));
      row.put("date_text", d == null ? null : d.text());
      row.put("date_from", d == null ? null : d.from());
      row.put("date_to", d == null ? null : d.to());
    }
    if (entity == GenEntity.MEDIA && row.get("date_text") != null) {
      row.put("date_text", GedcomDate.parse((String) row.get("date_text")).text());
    }
  }

  private Map<String, Object> lock(GenEntity entity, long id) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT to_jsonb(t.*)::text FROM %s t WHERE id = ? FOR UPDATE"
                .formatted(entity.table()),
            String.class,
            id);
    if (rows.isEmpty()) {
      throw GenealogyException.notFound("%s %d not found".formatted(entity.key(), id));
    }
    return Rows.json(rows.get(0));
  }

  private Map<String, Object> write(GenEntity entity, long id, Map<String, Object> values) {
    List<String> sets = new ArrayList<>();
    for (String column : values.keySet()) sets.add(column + " = ?");
    if (entity.writable()) sets.add("updated_at = now()");
    List<Object> args = new ArrayList<>(values.values());
    args.add(id);
    return Rows.json(
        jdbc.queryForObject(
            "UPDATE %s AS t SET %s WHERE id = ? RETURNING to_jsonb(t.*)::text"
                .formatted(entity.table(), String.join(", ", sets)),
            String.class,
            args.toArray()));
  }

  /** Deletes a row and every row that belongs to it, deepest first, one audit row each. */
  private int deleteWithOwned(GenEntity entity, long id, String op, String reason) {
    int removed = 0;
    for (Ref owned : OWNED.getOrDefault(entity, List.of())) {
      for (Long child :
          jdbc.queryForList(
              "SELECT id FROM %s WHERE %s = ? ORDER BY id"
                  .formatted(owned.entity().table(), owned.column()),
              Long.class,
              id)) {
        removed += deleteWithOwned(owned.entity(), child, op, reason);
      }
    }
    Map<String, Object> before = lock(entity, id);
    jdbc.update("DELETE FROM %s WHERE id = ?".formatted(entity.table()), id);
    audit(entity, id, op, before, null, reason);
    return removed + 1;
  }

  private List<String> blockers(GenEntity entity, long id) {
    List<String> out = new ArrayList<>();
    for (Ref ref : BLOCKING.getOrDefault(entity, List.of())) {
      Integer n =
          jdbc.queryForObject(
              "SELECT count(*) FROM %s WHERE %s = ?".formatted(ref.entity().table(), ref.column()),
              Integer.class,
              id);
      if (n > 0) out.add("%d %s (%s)".formatted(n, ref.entity().key(), ref.column()));
    }
    return out;
  }

  /** Clears a one-per-person flag (primary name, portrait) on the person's other row. */
  private void demoteOther(
      GenEntity entity, String flag, String owner, Object ownerId, Long self, String reason) {
    for (Long other :
        jdbc.queryForList(
            "SELECT id FROM %s WHERE %s = ? AND %s AND id IS DISTINCT FROM ?::bigint"
                .formatted(entity.table(), owner, flag),
            Long.class,
            ownerId,
            self)) {
      Map<String, Object> before = lock(entity, other);
      Map<String, Object> after = write(entity, other, Map.of(flag, false));
      audit(entity, other, "update", before, after, reason);
    }
  }

  void audit(
      GenEntity entity,
      long id,
      String op,
      Map<String, Object> before,
      Map<String, Object> after,
      String reason) {
    jdbc.update(
        "INSERT INTO gen_change (entity, entity_id, op, before, after, reason, changed_by)"
            + " VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)",
        entity.key(),
        id,
        op,
        Rows.toJson(before),
        Rows.toJson(after),
        reason.trim(),
        who());
  }

  /** Rebuilds the read model once the transaction has committed. */
  void changed() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              tree.invalidate();
            }
          });
    } else {
      tree.invalidate();
    }
  }

  static long id(Map<String, Object> row) {
    return ((Number) row.get("id")).longValue();
  }

  static String who() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null ? auth.getName() : "unknown";
  }

  private static Set<String> jsonNames(GenEntity entity) {
    Set<String> out = new HashSet<>();
    for (Field f : entity.fields()) out.add(f.json());
    return out;
  }

  public static void requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw GenealogyException.badRequest("reason is required");
    }
  }

  private static void requireWritable(GenEntity entity) {
    if (!entity.writable()) {
      throw GenealogyException.badRequest(entity.key() + " cannot be written directly");
    }
  }

  private void livePerson(Object id, String field, boolean required) {
    if (id == null) {
      if (required) throw GenealogyException.badRequest(field + " is required");
      return;
    }
    List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT merged_into FROM gen_person WHERE id = ?", id);
    if (rows.isEmpty()) throw GenealogyException.notFound("person %s not found".formatted(id));
    Object into = rows.get(0).get("merged_into");
    if (into != null) {
      throw GenealogyException.conflict(
          "person %s was merged into %s; use %s".formatted(id, into, into));
    }
  }

  private void requireRef(GenEntity entity, Object id, String field) {
    if (id == null) throw GenealogyException.badRequest(field + " is required");
    exists(entity, id, field);
  }

  private void exists(GenEntity entity, Object id, String field) {
    existsIn(entity.table(), id, field);
  }

  private void existsIn(String table, Object id, String field) {
    if (id == null) return;
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM %s WHERE id = ?".formatted(table), Integer.class, id);
    if (n == 0) throw GenealogyException.notFound("%s %s not found".formatted(field, id));
  }

  private static void oneOf(String field, Object value, Set<String> allowed, boolean required) {
    if (value == null) {
      if (required) throw GenealogyException.badRequest(field + " is required");
      return;
    }
    if (!allowed.contains(value.toString())) {
      throw GenealogyException.badRequest(
          "%s must be one of %s".formatted(field, allowed.stream().sorted().toList()));
    }
  }

  private static void date(Object text) {
    try {
      GedcomDate.parse((String) text);
    } catch (GedcomDate.InvalidDateException e) {
      throw GenealogyException.badRequest(e.getMessage());
    }
  }

  private static void quality(Object q) {
    if (q != null && (((Number) q).intValue() < 0 || ((Number) q).intValue() > 3)) {
      throw GenealogyException.badRequest("quality is a GEDCOM QUAY from 0 to 3");
    }
  }

  private static void range(String field, Object v, double min, double max) {
    if (v != null && (((Number) v).doubleValue() < min || ((Number) v).doubleValue() > max)) {
      throw GenealogyException.badRequest(
          "%s must be between %s and %s".formatted(field, min, max));
    }
  }

  private static boolean blank(Object v) {
    return v == null || v.toString().isBlank();
  }
}
