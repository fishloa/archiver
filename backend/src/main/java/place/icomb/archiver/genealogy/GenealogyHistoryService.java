package place.icomb.archiver.genealogy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merging two records of one person, and undoing a change.
 *
 * <p>A write is every {@code gen_change} row its transaction appended: they share {@code
 * changed_at} (the transaction's start) and {@code changed_by}. Undo restores the whole write, last
 * row first, and only when every row it touched still looks exactly as the write left it.
 */
@Service
public class GenealogyHistoryService {

  private final JdbcTemplate jdbc;
  private final GenealogyService genealogy;

  public GenealogyHistoryService(JdbcTemplate jdbc, GenealogyService genealogy) {
    this.jdbc = jdbc;
    this.genealogy = genealogy;
  }

  /**
   * Re-points every link of {@code goneId} to {@code keepId} and marks {@code goneId} merged into
   * it. Links {@code keepId} already has (the same parent, the same page match, the same image) are
   * not duplicated: the gone person's copy is deleted, its citations moved first.
   */
  @Transactional
  public Map<String, Object> mergePersons(int keepId, int goneId, String reason) {
    GenealogyService.requireReason(reason);
    if (keepId == goneId) {
      throw GenealogyException.badRequest("a person cannot be merged into themselves");
    }
    for (int id : List.of(keepId, goneId)) {
      List<Map<String, Object>> rows =
          jdbc.queryForList("SELECT merged_into FROM gen_person WHERE id = ? FOR UPDATE", id);
      if (rows.isEmpty()) throw GenealogyException.notFound("person %d not found".formatted(id));
      if (rows.get(0).get("merged_into") != null) {
        throw GenealogyException.conflict(
            "person %d was already merged into %s".formatted(id, rows.get(0).get("merged_into")));
      }
    }
    Integer between =
        jdbc.queryForObject(
            "SELECT (SELECT count(*) FROM gen_couple WHERE (person1_id, person2_id) IN ((?, ?), (?, ?)))"
                + " + (SELECT count(*) FROM gen_parent_child"
                + "    WHERE (parent_id, child_id) IN ((?, ?), (?, ?)))",
            Integer.class,
            keepId,
            goneId,
            goneId,
            keepId,
            keepId,
            goneId,
            goneId,
            keepId);
    if (between > 0) {
      throw GenealogyException.conflict(
          "persons %d and %d are linked to each other; remove that link first"
              .formatted(keepId, goneId));
    }

    int changed = 0;
    for (long name : ids("SELECT id FROM gen_name WHERE person_id = ?", goneId)) {
      changed +=
          repoint(GenEntity.NAME, name, Map.of("person_id", keepId, "is_primary", false), reason);
    }
    changed += repointAll(GenEntity.EVENT, "person_id", keepId, goneId, reason);
    changed += repointAll(GenEntity.COUPLE, "person1_id", keepId, goneId, reason);
    changed += repointAll(GenEntity.COUPLE, "person2_id", keepId, goneId, reason);
    changed += moveParentLinks("parent_id", "child_id", keepId, goneId, reason);
    changed += moveParentLinks("child_id", "parent_id", keepId, goneId, reason);
    changed += repointAll(GenEntity.CITATION, "person_id", keepId, goneId, reason);
    changed += repointAll(GenEntity.RECORD_LINK, "person_id", keepId, goneId, reason);

    boolean keepHasPortrait =
        jdbc.queryForObject(
                "SELECT count(*) FROM gen_media_person WHERE person_id = ? AND is_portrait",
                Integer.class,
                keepId)
            > 0;
    for (Map<String, Object> mp :
        jdbc.queryForList(
            "SELECT id, media_id, is_portrait FROM gen_media_person WHERE person_id = ?", goneId)) {
      long id = ((Number) mp.get("id")).longValue();
      boolean duplicate =
          jdbc.queryForObject(
                  "SELECT count(*) FROM gen_media_person WHERE media_id = ? AND person_id = ?",
                  Integer.class,
                  mp.get("media_id"),
                  keepId)
              > 0;
      if (duplicate) {
        changed += remove(GenEntity.MEDIA_PERSON, id, reason);
      } else {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("person_id", keepId);
        if (keepHasPortrait) set.put("is_portrait", false);
        changed += repoint(GenEntity.MEDIA_PERSON, id, set, reason);
      }
    }

    for (Map<String, Object> m :
        jdbc.queryForList(
            "SELECT id, page_id FROM page_person_match WHERE person_id = ?", goneId)) {
      long id = ((Number) m.get("id")).longValue();
      boolean duplicate =
          jdbc.queryForObject(
                  "SELECT count(*) FROM page_person_match WHERE page_id = ? AND person_id = ?",
                  Integer.class,
                  m.get("page_id"),
                  keepId)
              > 0;
      changed +=
          duplicate
              ? remove(GenEntity.PAGE_PERSON_MATCH, id, reason)
              : partial(GenEntity.PAGE_PERSON_MATCH, id, "person_id", goneId, keepId, reason);
    }
    for (long user : ids("SELECT id FROM app_user WHERE family_tree_person_id = ?", goneId)) {
      changed += partial(GenEntity.APP_USER, user, "family_tree_person_id", goneId, keepId, reason);
    }
    changed += repointAll(GenEntity.PERSON, "merged_into", keepId, goneId, reason);

    Integer birthParents =
        jdbc.queryForObject(
            "SELECT count(*) FROM gen_parent_child WHERE child_id = ? AND type = 'birth'",
            Integer.class,
            keepId);
    if (birthParents > 2) {
      throw GenealogyException.conflict(
          "merging would give person %d %d birth parents".formatted(keepId, birthParents));
    }

    changed += repoint(GenEntity.PERSON, goneId, Map.of("merged_into", keepId), reason);
    genealogy.changed();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("keep", keepId);
    out.put("gone", goneId);
    out.put("changed", changed);
    return out;
  }

  /** Restores what the write containing {@code changeId} replaced, if nothing has moved since. */
  @Transactional
  public Map<String, Object> undo(long changeId, String reason) {
    GenealogyService.requireReason(reason);
    List<Map<String, Object>> write =
        jdbc.queryForList(
            "SELECT id, entity, entity_id, before::text AS before, after::text AS after"
                + " FROM gen_change WHERE (changed_at, changed_by) ="
                + " (SELECT changed_at, changed_by FROM gen_change WHERE id = ?)"
                + " ORDER BY id DESC",
            changeId);
    if (write.isEmpty()) {
      throw GenealogyException.notFound("change %d not found".formatted(changeId));
    }

    for (Map<String, Object> row : write) {
      GenEntity entity = GenEntity.ofKey((String) row.get("entity"));
      long id = ((Number) row.get("entity_id")).longValue();
      String after = (String) row.get("after");
      boolean present = exists(entity, id);
      boolean unchanged =
          after == null
              ? !present
              : present
                  && Boolean.TRUE.equals(
                      jdbc.queryForObject(
                          "SELECT (to_jsonb(t.*) - 'updated_at') @> (?::jsonb - 'updated_at')"
                              + " FROM %s t WHERE id = ?".formatted(entity.table()),
                          Boolean.class,
                          after,
                          id));
      if (!unchanged) {
        throw GenealogyException.conflict(
            "%s %d has changed since change %d; undo the later change first"
                .formatted(entity.key(), id, ((Number) row.get("id")).longValue()));
      }
    }

    List<Long> undone = new ArrayList<>();
    try {
      for (Map<String, Object> row : write) {
        GenEntity entity = GenEntity.ofKey((String) row.get("entity"));
        long id = ((Number) row.get("entity_id")).longValue();
        Map<String, Object> before = Rows.json((String) row.get("before"));
        Map<String, Object> current = current(entity, id);
        if (before == null) {
          jdbc.update("DELETE FROM %s WHERE id = ?".formatted(entity.table()), id);
          genealogy.audit(entity, id, "undo", current, null, reason);
        } else if (current == null) {
          jdbc.update(
              "INSERT INTO %s SELECT * FROM jsonb_populate_record(NULL::%s, ?::jsonb)"
                  .formatted(entity.table(), entity.table()),
              Rows.toJson(before));
          genealogy.audit(entity, id, "undo", null, current(entity, id), reason);
        } else {
          List<String> columns = new ArrayList<>(before.keySet());
          columns.removeAll(List.of("id", "created_at", "updated_at"));
          String list = String.join(", ", columns);
          jdbc.update(
              "UPDATE %s t SET (%s) = (SELECT %s FROM jsonb_populate_record(NULL::%s, ?::jsonb))%s"
                      .formatted(
                          entity.table(),
                          list,
                          list,
                          entity.table(),
                          entity.writable() ? ", updated_at = now()" : "")
                  + " WHERE t.id = ?",
              Rows.toJson(before),
              id);
          Map<String, Object> now = current(entity, id);
          if (!entity.writable()) {
            now.keySet().retainAll(before.keySet());
            current.keySet().retainAll(before.keySet());
          }
          genealogy.audit(entity, id, "undo", current, now, reason);
        }
        undone.add(((Number) row.get("id")).longValue());
      }
    } catch (DataIntegrityViolationException e) {
      throw GenealogyException.conflict(
          "change %d cannot be undone: the data it restored would conflict with what is there now"
              .formatted(changeId));
    }
    genealogy.changed();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("undone", undone);
    return out;
  }

  private int repointAll(GenEntity entity, String column, int keepId, int goneId, String reason) {
    int n = 0;
    for (long id :
        ids("SELECT id FROM %s WHERE %s = ?".formatted(entity.table(), column), goneId)) {
      n += repoint(entity, id, Map.of(column, keepId), reason);
    }
    return n;
  }

  /** Moves the gone person's side of each parent link, dropping links keep already has. */
  private int moveParentLinks(
      String goneSide, String otherSide, int keepId, int goneId, String reason) {
    int n = 0;
    for (Map<String, Object> link :
        jdbc.queryForList(
            "SELECT id, %s AS other FROM gen_parent_child WHERE %s = ?"
                .formatted(otherSide, goneSide),
            goneId)) {
      long id = ((Number) link.get("id")).longValue();
      List<Long> existing =
          jdbc.queryForList(
              "SELECT id FROM gen_parent_child WHERE %s = ? AND %s = ?"
                  .formatted(goneSide, otherSide),
              Long.class,
              keepId,
              link.get("other"));
      if (existing.isEmpty()) {
        n += repoint(GenEntity.PARENT_LINK, id, Map.of(goneSide, keepId), reason);
      } else {
        for (long citation : ids("SELECT id FROM gen_citation WHERE parent_child_id = ?", id)) {
          n +=
              repoint(
                  GenEntity.CITATION, citation, Map.of("parent_child_id", existing.get(0)), reason);
        }
        n += remove(GenEntity.PARENT_LINK, id, reason);
      }
    }
    return n;
  }

  private int repoint(GenEntity entity, long id, Map<String, Object> set, String reason) {
    Map<String, Object> before = current(entity, id);
    List<String> sets = new ArrayList<>();
    for (String column : set.keySet()) sets.add(column + " = ?");
    sets.add("updated_at = now()");
    List<Object> args = new ArrayList<>(set.values());
    args.add(id);
    jdbc.update(
        "UPDATE %s SET %s WHERE id = ?".formatted(entity.table(), String.join(", ", sets)),
        args.toArray());
    genealogy.audit(entity, id, "merge", before, current(entity, id), reason);
    return 1;
  }

  private int partial(
      GenEntity entity, long id, String column, Object from, Object to, String reason) {
    jdbc.update("UPDATE %s SET %s = ? WHERE id = ?".formatted(entity.table(), column), to, id);
    Map<String, Object> before = new LinkedHashMap<>();
    before.put(column, from);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put(column, to);
    genealogy.audit(entity, id, "merge", before, after, reason);
    return 1;
  }

  private int remove(GenEntity entity, long id, String reason) {
    Map<String, Object> before = current(entity, id);
    jdbc.update("DELETE FROM %s WHERE id = ?".formatted(entity.table()), id);
    genealogy.audit(entity, id, "delete", before, null, reason);
    return 1;
  }

  private Map<String, Object> current(GenEntity entity, long id) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT to_jsonb(t.*)::text FROM %s t WHERE id = ?".formatted(entity.table()),
            String.class,
            id);
    return rows.isEmpty() ? null : Rows.json(rows.get(0));
  }

  private boolean exists(GenEntity entity, long id) {
    return jdbc.queryForObject(
            "SELECT count(*) FROM %s WHERE id = ?".formatted(entity.table()), Integer.class, id)
        > 0;
  }

  private List<Long> ids(String sql, Object arg) {
    return jdbc.queryForList(sql, Long.class, arg);
  }
}
