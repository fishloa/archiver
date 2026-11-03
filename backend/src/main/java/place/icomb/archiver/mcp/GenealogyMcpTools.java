package place.icomb.archiver.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import place.icomb.archiver.genealogy.GenEntity;
import place.icomb.archiver.genealogy.GenealogyException;
import place.icomb.archiver.genealogy.GenealogyHistoryService;
import place.icomb.archiver.genealogy.GenealogyService;

/**
 * Editing the family tree over MCP. Administrators only, failing closed like {@code delete_page}:
 * with no provable {@code ROLE_ADMIN} every tool throws. Every write needs a reason and is undoable
 * with {@code undo_change}.
 */
@Component
public class GenealogyMcpTools {

  private final GenealogyService genealogy;
  private final GenealogyHistoryService history;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public GenealogyMcpTools(
      GenealogyService genealogy,
      GenealogyHistoryService history,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions) {
    this.genealogy = genealogy;
    this.history = history;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactions);
  }

  private void requireAdmin() {
    var auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    boolean admin =
        auth != null
            && auth.isAuthenticated()
            && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    if (!admin) {
      throw new IllegalStateException("this tool is available to administrators only");
    }
  }

  /**
   * One tool call is one write: a place or source it has to make goes in the same transaction, and
   * so into the same undo.
   */
  private Map<String, Object> run(Supplier<Map<String, Object>> write) {
    requireAdmin();
    return tx.execute(
        status -> {
          try {
            return write.get();
          } catch (GenealogyException e) {
            status.setRollbackOnly();
            return Map.of("error", String.valueOf(e.getMessage()));
          }
        });
  }

  private static Map<String, Object> body(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  @McpTool(
      name = "add_person",
      title = "Add Person to the Family Tree",
      description =
          "Create a person with their primary name. sex is M, F or U. Returns the new person's"
              + " id. Give a reason (the evidence). Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> addPerson(
      @McpToolParam(description = "Given names", required = false) String given,
      @McpToolParam(description = "Surname", required = false) String surname,
      @McpToolParam(description = "Title before the name, e.g. Graf", required = false)
          String prefix,
      @McpToolParam(description = "M, F or U", required = false) String sex,
      @McpToolParam(description = "Free-text notes", required = false) String notes,
      @McpToolParam(description = "Why: the document or reasoning") String reason) {
    return run(
        () ->
            genealogy.createPerson(
                body(
                    "given", given, "surname", surname, "prefix", prefix, "sex", sex, "notes",
                    notes),
                reason));
  }

  @McpTool(
      name = "update_person",
      title = "Update Person",
      description =
          "Change a person's sex, living flag, notes or review note. Only the fields given change."
              + " Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> updatePerson(
      @McpToolParam(description = "Person id") int personId,
      @McpToolParam(description = "M, F or U", required = false) String sex,
      @McpToolParam(description = "Still living", required = false) Boolean living,
      @McpToolParam(description = "Notes", required = false) String notes,
      @McpToolParam(description = "What still needs checking", required = false) String needsReview,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () ->
            genealogy.update(
                GenEntity.PERSON,
                personId,
                body("sex", sex, "living", living, "notes", notes, "needsReview", needsReview),
                reason));
  }

  @McpTool(
      name = "add_name",
      title = "Add Name",
      description =
          "Add a name to a person: type birth, married, alias, religious, title or other. With"
              + " isPrimary true it becomes the name the tree shows. Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> addName(
      @McpToolParam(description = "Person id") int personId,
      @McpToolParam(description = "birth, married, alias, religious, title, other") String type,
      @McpToolParam(description = "Given names", required = false) String given,
      @McpToolParam(description = "Surname", required = false) String surname,
      @McpToolParam(description = "Title before the name", required = false) String prefix,
      @McpToolParam(description = "Suffix", required = false) String suffix,
      @McpToolParam(description = "Nickname", required = false) String nickname,
      @McpToolParam(description = "Make this the primary name", required = false) Boolean isPrimary,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () ->
            genealogy.create(
                GenEntity.NAME,
                body(
                    "personId",
                    personId,
                    "type",
                    type,
                    "given",
                    given,
                    "surname",
                    surname,
                    "prefix",
                    prefix,
                    "suffix",
                    suffix,
                    "nickname",
                    nickname,
                    "isPrimary",
                    isPrimary),
                reason));
  }

  @McpTool(
      name = "add_event",
      title = "Add Event",
      description =
          "Add a GEDCOM 7 event (BIRT, CHR, BAPM, DEAT, BURI, MARR, DIV, ADOP, OCCU, TITL, RESI,"
              + " NATU, EMIG, IMMI, EDUC, EVEN) to a person, or MARR/DIV to a couple. date is a"
              + " GEDCOM 7 date: '12 MAR 1890', 'ABT 1890', 'BET 1657 AND 1664', 'AFT 1501'."
              + " place is a place name, matched exactly or created. Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> addEvent(
      @McpToolParam(description = "Person id, or give coupleId", required = false) Integer personId,
      @McpToolParam(description = "Couple id, for MARR and DIV", required = false) Long coupleId,
      @McpToolParam(description = "GEDCOM 7 event tag") String type,
      @McpToolParam(description = "GEDCOM 7 date", required = false) String date,
      @McpToolParam(description = "Place name", required = false) String place,
      @McpToolParam(description = "Description", required = false) String description,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () ->
            genealogy.create(
                GenEntity.EVENT,
                body(
                    "personId",
                    personId,
                    "coupleId",
                    coupleId,
                    "type",
                    type,
                    "dateText",
                    date,
                    "placeName",
                    place,
                    "description",
                    description),
                reason));
  }

  @McpTool(
      name = "update_event",
      title = "Update Event",
      description =
          "Change an event's type, date, place or description. Only the fields given change."
              + " Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> updateEvent(
      @McpToolParam(description = "Event id") long eventId,
      @McpToolParam(description = "GEDCOM 7 event tag", required = false) String type,
      @McpToolParam(description = "GEDCOM 7 date", required = false) String date,
      @McpToolParam(description = "Place name", required = false) String place,
      @McpToolParam(description = "Description", required = false) String description,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () ->
            genealogy.update(
                GenEntity.EVENT,
                eventId,
                body(
                    "type", type, "dateText", date, "placeName", place, "description", description),
                reason));
  }

  @McpTool(
      name = "link_parent",
      title = "Link Parent and Child",
      description =
          "Record that parentId is a parent of childId: type birth, adopted, step, foster or"
              + " unknown; coupleId names the parents' couple. A child has at most two birth"
              + " parents, and no one can be their own ancestor. Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> linkParent(
      @McpToolParam(description = "Parent's person id") int parentId,
      @McpToolParam(description = "Child's person id") int childId,
      @McpToolParam(description = "birth, adopted, step, foster, unknown", required = false)
          String type,
      @McpToolParam(description = "The parents' couple id", required = false) Long coupleId,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () ->
            genealogy.create(
                GenEntity.PARENT_LINK,
                body("parentId", parentId, "childId", childId, "type", type, "coupleId", coupleId),
                reason));
  }

  @McpTool(
      name = "add_couple",
      title = "Add Couple",
      description =
          "Record a couple: type marriage, partnership or unknown; sortOrder is 1 for a first"
              + " marriage, 2 for a second. Add the wedding with add_event (MARR, coupleId)."
              + " Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> addCouple(
      @McpToolParam(description = "One partner's person id") int person1Id,
      @McpToolParam(description = "The other partner's person id") int person2Id,
      @McpToolParam(description = "marriage, partnership, unknown", required = false) String type,
      @McpToolParam(description = "1 for the first marriage", required = false) Integer sortOrder,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () ->
            genealogy.create(
                GenEntity.COUPLE,
                body(
                    "person1Id",
                    person1Id,
                    "person2Id",
                    person2Id,
                    "type",
                    type,
                    "sortOrder",
                    sortOrder),
                reason));
  }

  @McpTool(
      name = "cite",
      title = "Cite Evidence",
      description =
          "Attach evidence to exactly one fact: give one of eventId, nameId, coupleId,"
              + " parentChildId, personId. The source is sourceId, or the archive record"
              + " recordId (its source is made on first use); seq or pageId names the page."
              + " quality is GEDCOM QUAY: 0 unreliable, 1 questionable, 2 secondary, 3 primary."
              + " Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> cite(
      @McpToolParam(description = "Source id", required = false) Long sourceId,
      @McpToolParam(description = "Archive record id, instead of sourceId", required = false)
          Long recordId,
      @McpToolParam(description = "Page sequence in the record", required = false) Integer seq,
      @McpToolParam(description = "Page id, instead of seq", required = false) Long pageId,
      @McpToolParam(description = "Where in the source, or what it says", required = false)
          String detail,
      @McpToolParam(description = "QUAY 0-3", required = false) Integer quality,
      @McpToolParam(description = "Event id", required = false) Long eventId,
      @McpToolParam(description = "Name id", required = false) Long nameId,
      @McpToolParam(description = "Couple id", required = false) Long coupleId,
      @McpToolParam(description = "Parent link id", required = false) Long parentChildId,
      @McpToolParam(description = "Person id", required = false) Integer personId,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () -> {
          Long source = sourceId != null ? sourceId : recordSource(recordId, reason);
          Long page = pageId != null ? pageId : page(recordId, seq);
          return genealogy.create(
              GenEntity.CITATION,
              body(
                  "sourceId",
                  source,
                  "pageId",
                  page,
                  "detail",
                  detail,
                  "quality",
                  quality,
                  "eventId",
                  eventId,
                  "nameId",
                  nameId,
                  "coupleId",
                  coupleId,
                  "parentChildId",
                  parentChildId,
                  "personId",
                  personId),
              reason);
        });
  }

  @McpTool(
      name = "link_record",
      title = "Link Person to Document",
      description =
          "Record that a person appears in an archive record (optionally on one page): role"
              + " subject, mentioned, author, signatory, witness, recipient, pictured or other."
              + " It does not prove a fact; use cite for that. Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> linkRecord(
      @McpToolParam(description = "Person id") int personId,
      @McpToolParam(description = "Record id") long recordId,
      @McpToolParam(description = "Page sequence", required = false) Integer seq,
      @McpToolParam(description = "Role", required = false) String role,
      @McpToolParam(description = "Note", required = false) String note,
      @McpToolParam(description = "QUAY 0-3", required = false) Integer quality,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () ->
            genealogy.create(
                GenEntity.RECORD_LINK,
                body(
                    "personId",
                    personId,
                    "recordId",
                    recordId,
                    "pageId",
                    page(recordId, seq),
                    "role",
                    role,
                    "note",
                    note,
                    "quality",
                    quality),
                reason));
  }

  @McpTool(
      name = "add_media",
      title = "Add Photo or Image",
      description =
          "Make an image from a page scan, whole or cropped (cropLeft, cropTop, cropWidth,"
              + " cropHeight in pixels of the stored original), and attach it to people;"
              + " portraitPersonId makes it that person's portrait. Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> addMedia(
      @McpToolParam(description = "Record id") long recordId,
      @McpToolParam(description = "Page sequence") int seq,
      @McpToolParam(description = "Crop left", required = false) Integer cropLeft,
      @McpToolParam(description = "Crop top", required = false) Integer cropTop,
      @McpToolParam(description = "Crop width", required = false) Integer cropWidth,
      @McpToolParam(description = "Crop height", required = false) Integer cropHeight,
      @McpToolParam(description = "Caption", required = false) String caption,
      @McpToolParam(description = "GEDCOM 7 date of the image", required = false) String date,
      @McpToolParam(description = "People shown", required = false) List<Integer> personIds,
      @McpToolParam(description = "Whose portrait this is", required = false)
          Integer portraitPersonId,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () -> {
          Map<String, Object> media =
              genealogy.create(
                  GenEntity.MEDIA,
                  body(
                      "pageId",
                      page(recordId, seq),
                      "cropLeft",
                      cropLeft,
                      "cropTop",
                      cropTop,
                      "cropWidth",
                      cropWidth,
                      "cropHeight",
                      cropHeight,
                      "caption",
                      caption,
                      "dateText",
                      date),
                  reason);
          java.util.LinkedHashSet<Integer> people = new java.util.LinkedHashSet<>();
          if (personIds != null) people.addAll(personIds);
          if (portraitPersonId != null) people.add(portraitPersonId);
          for (int person : people) {
            genealogy.create(
                GenEntity.MEDIA_PERSON,
                body(
                    "mediaId",
                    media.get("id"),
                    "personId",
                    person,
                    "isPortrait",
                    portraitPersonId != null && portraitPersonId == person),
                reason);
          }
          Map<String, Object> out = new LinkedHashMap<>(media);
          out.put("people", List.copyOf(people));
          out.put("imageUrl", "/api/family-tree/media/" + media.get("id") + "/image");
          return out;
        });
  }

  @McpTool(
      name = "merge_persons",
      title = "Merge Two People",
      description =
          "goneId is a duplicate of keepId: every name, event, couple, parent link, citation,"
              + " document link, image, page match and user link moves to keepId, and goneId"
              + " points at keepId from then on. Undoable with undo_change. Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true))
  public Map<String, Object> mergePersons(
      @McpToolParam(description = "The person who stays") int keepId,
      @McpToolParam(description = "The duplicate") int goneId,
      @McpToolParam(description = "Why they are one person") String reason) {
    return run(() -> history.mergePersons(keepId, goneId, reason));
  }

  @McpTool(
      name = "undo_change",
      title = "Undo a Tree Change",
      description =
          "Undo the write that change id belongs to (find it with tree_changes). Refused if what"
              + " it changed has been changed again since. Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> undoChange(
      @McpToolParam(description = "gen_change id") long changeId,
      @McpToolParam(description = "Why") String reason) {
    return run(() -> history.undo(changeId, reason));
  }

  @McpTool(
      name = "tree_changes",
      title = "Family Tree Change History",
      description =
          "The audit of tree edits, newest first: one person's (personId), or one kind of row"
              + " (entity: person, name, event, couple, parent_link, place, place_name, source,"
              + " citation, record_link, media, media_person). Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
  public Map<String, Object> treeChanges(
      @McpToolParam(description = "Person id", required = false) Integer personId,
      @McpToolParam(description = "Entity kind", required = false) String entity,
      @McpToolParam(description = "Row id within the entity kind", required = false) Long entityId,
      @McpToolParam(description = "How many. Default 50.", required = false) Integer limit) {
    return run(
        () ->
            Map.of(
                "changes",
                personId != null
                    ? genealogy.personChanges(personId, limit != null ? limit : 50)
                    : genealogy.changes(entity, entityId, limit != null ? limit : 50)));
  }

  /** The source standing for an archive record, made on first use. */
  private Long recordSource(Long recordId, String reason) {
    if (recordId == null) {
      throw GenealogyException.badRequest("give sourceId or recordId");
    }
    List<Long> found =
        jdbc.queryForList(
            "SELECT id FROM gen_source WHERE record_id = ? ORDER BY id LIMIT 1",
            Long.class,
            recordId);
    if (!found.isEmpty()) return found.get(0);
    List<Map<String, Object>> record =
        jdbc.queryForList(
            "SELECT coalesce(title_en, title, 'Record ' || id) AS title, reference_code"
                + " FROM record WHERE id = ?",
            recordId);
    if (record.isEmpty()) {
      throw GenealogyException.notFound("record %d not found".formatted(recordId));
    }
    return ((Number)
            genealogy
                .create(
                    GenEntity.SOURCE,
                    body(
                        "title", record.get(0).get("title"),
                        "recordId", recordId,
                        "notes", record.get(0).get("reference_code")),
                    reason)
                .get("id"))
        .longValue();
  }

  private Long page(Long recordId, Integer seq) {
    if (recordId == null || seq == null) return null;
    List<Long> found =
        jdbc.queryForList(
            "SELECT id FROM page WHERE record_id = ? AND seq = ?", Long.class, recordId, seq);
    if (found.isEmpty()) {
      throw GenealogyException.notFound("no page %d in record %d".formatted(seq, recordId));
    }
    return found.get(0);
  }
}
