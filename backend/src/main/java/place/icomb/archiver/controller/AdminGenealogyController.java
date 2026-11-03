package place.icomb.archiver.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.genealogy.GedcomExporter;
import place.icomb.archiver.genealogy.GenEntity;
import place.icomb.archiver.genealogy.GenealogyException;
import place.icomb.archiver.genealogy.GenealogyHistoryService;
import place.icomb.archiver.genealogy.GenealogyService;
import place.icomb.archiver.genealogy.Rows;

/**
 * Editing the genealogy. Administrators only (SecurityConfig requires ROLE_ADMIN for every method
 * under {@code /api/admin}); every write carries {@code "reason"} in its JSON body and leaves one
 * {@code gen_change} row per row it changed.
 */
@RestController
@RequestMapping("/api/admin/genealogy")
public class AdminGenealogyController {

  private static final Map<String, GenEntity> KINDS =
      Map.ofEntries(
          Map.entry("persons", GenEntity.PERSON),
          Map.entry("names", GenEntity.NAME),
          Map.entry("events", GenEntity.EVENT),
          Map.entry("couples", GenEntity.COUPLE),
          Map.entry("parent-links", GenEntity.PARENT_LINK),
          Map.entry("places", GenEntity.PLACE),
          Map.entry("place-names", GenEntity.PLACE_NAME),
          Map.entry("sources", GenEntity.SOURCE),
          Map.entry("citations", GenEntity.CITATION),
          Map.entry("record-links", GenEntity.RECORD_LINK),
          Map.entry("media", GenEntity.MEDIA),
          Map.entry("media-people", GenEntity.MEDIA_PERSON));

  private final GenealogyService genealogy;
  private final GenealogyHistoryService history;
  private final JdbcTemplate jdbc;
  private final GedcomExporter gedcom;

  public AdminGenealogyController(
      GenealogyService genealogy,
      GenealogyHistoryService history,
      JdbcTemplate jdbc,
      GedcomExporter gedcom) {
    this.genealogy = genealogy;
    this.history = history;
    this.jdbc = jdbc;
    this.gedcom = gedcom;
  }

  /** The whole tree as GEDCOM 7; living people are left out unless {@code living=include}. */
  @GetMapping("/export.ged")
  public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "exclude") String living) {
    if (!living.equals("exclude") && !living.equals("include")) {
      throw GenealogyException.badRequest("living must be exclude or include");
    }
    return ResponseEntity.ok()
        .contentType(
            org.springframework.http.MediaType.parseMediaType(
                "text/vnd.familysearch.gedcom;charset=UTF-8"))
        .header(
            org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"czernin.ged\"")
        .body(
            gedcom
                .export(living.equals("include"))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @PostMapping("/{kind}")
  public ResponseEntity<Map<String, Object>> create(
      @PathVariable String kind, @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> in = fields(body);
    String reason = reason(in);
    GenEntity entity = kind(kind);
    Map<String, Object> out;
    if (entity == GenEntity.PERSON) {
      out = genealogy.createPerson(in, reason);
    } else if (entity == GenEntity.MEDIA && in.containsKey("personId")) {
      Object person = in.remove("personId");
      Object portrait = in.remove("isPortrait");
      if (!(person instanceof Number n) || (portrait != null && !(portrait instanceof Boolean))) {
        throw GenealogyException.badRequest(
            "personId must be a number and isPortrait true or false");
      }
      out = genealogy.createMedia(in, n.intValue(), Boolean.TRUE.equals(portrait), reason);
    } else {
      out = genealogy.create(entity, in, reason);
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(out);
  }

  @PostMapping("/persons/{personId}/names")
  public ResponseEntity<Map<String, Object>> addName(
      @PathVariable int personId, @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> in = fields(body);
    String reason = reason(in);
    in.put("personId", personId);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(genealogy.create(GenEntity.NAME, in, reason));
  }

  @PutMapping("/{kind}/{id}")
  public Map<String, Object> update(
      @PathVariable String kind,
      @PathVariable long id,
      @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> in = fields(body);
    String reason = reason(in);
    return genealogy.update(kind(kind), id, in, reason);
  }

  @DeleteMapping("/{kind}/{id}")
  public Map<String, Object> delete(
      @PathVariable String kind,
      @PathVariable long id,
      @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> in = fields(body);
    return genealogy.delete(kind(kind), id, reason(in));
  }

  @PostMapping("/persons/{keepId}/merge/{goneId}")
  public Map<String, Object> merge(
      @PathVariable int keepId,
      @PathVariable int goneId,
      @RequestBody(required = false) Map<String, Object> body) {
    return history.mergePersons(keepId, goneId, reason(fields(body)));
  }

  @GetMapping("/changes")
  public List<Map<String, Object>> changes(
      @RequestParam(required = false) String entity,
      @RequestParam(required = false) Long entityId,
      @RequestParam(required = false) Integer personId,
      @RequestParam(defaultValue = "100") int limit) {
    if (personId != null) {
      return genealogy.personChanges(personId, limit);
    }
    return genealogy.changes(entity, entityId, limit);
  }

  @PostMapping("/changes/{changeId}/undo")
  public Map<String, Object> undo(
      @PathVariable long changeId, @RequestBody(required = false) Map<String, Object> body) {
    return history.undo(changeId, reason(fields(body)));
  }

  @GetMapping("/review")
  public List<Map<String, Object>> review() {
    return genealogy.review();
  }

  /** Places by name, for the place picker. */
  @GetMapping("/places")
  public List<Map<String, Object>> places(
      @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "50") int limit) {
    return Rows.camel(
        jdbc.queryForList(
            "SELECT id, name, parent_id, lat, lon FROM gen_place"
                + " WHERE name ILIKE '%' || ? || '%' ORDER BY name, id LIMIT ?",
            q, Math.max(1, Math.min(limit, 1000))));
  }

  /** Sources by title, for the citation picker. */
  @GetMapping("/sources")
  public List<Map<String, Object>> sources(
      @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "50") int limit) {
    return Rows.camel(
        jdbc.queryForList(
            "SELECT id, title, author, record_id, url FROM gen_source"
                + " WHERE title ILIKE '%' || ? || '%' ORDER BY title, id LIMIT ?",
            q, Math.max(1, Math.min(limit, 1000))));
  }

  @ExceptionHandler(GenealogyException.class)
  public ResponseEntity<Map<String, Object>> refuse(GenealogyException e) {
    HttpStatus status =
        switch (e.kind()) {
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
          case CONFLICT -> HttpStatus.CONFLICT;
        };
    return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
  }

  private static GenEntity kind(String kind) {
    GenEntity entity = KINDS.get(kind);
    if (entity == null) {
      throw GenealogyException.notFound("no genealogy collection '%s'".formatted(kind));
    }
    return entity;
  }

  private static Map<String, Object> fields(Map<String, Object> body) {
    return body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
  }

  /** Takes {@code reason} out of the body, so the rest is only the entity's fields. */
  private static String reason(Map<String, Object> in) {
    Object reason = in.remove("reason");
    if (reason != null && !(reason instanceof String)) {
      throw GenealogyException.badRequest("reason must be a string");
    }
    GenealogyService.requireReason((String) reason);
    return (String) reason;
  }
}
