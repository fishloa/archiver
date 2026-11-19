package place.icomb.archiver.controller;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.genealogy.GenealogyException;
import place.icomb.archiver.genealogy.HintService;

/**
 * Hints, for administrators only (SecurityConfig requires ROLE_ADMIN under {@code /api/admin}): run
 * them for a record or a page, list them, confirm one into a link or a picture tag.
 */
@RestController
@RequestMapping("/api/admin/genealogy/hints")
public class AdminHintController {

  private final HintService hints;

  public AdminHintController(HintService hints) {
    this.hints = hints;
  }

  @PostMapping("/run")
  public Map<String, Object> run(@RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> in = body == null ? Map.of() : body;
    return hints.run(number(in, "recordId"), number(in, "pageId"));
  }

  @GetMapping
  public List<Map<String, Object>> list(
      @RequestParam(required = false) Long pageId, @RequestParam(required = false) Long recordId) {
    return hints.list(pageId, recordId);
  }

  @PostMapping("/{id}/confirm")
  public Map<String, Object> confirm(
      @PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> in = body == null ? Map.of() : body;
    Object portrait = in.get("portrait");
    if (portrait != null && !(portrait instanceof Boolean)) {
      throw GenealogyException.badRequest("portrait must be true or false");
    }
    return hints.confirm(id, text(in, "reason"), text(in, "role"), (Boolean) portrait);
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

  private static Long number(Map<String, Object> in, String key) {
    Object v = in.get(key);
    if (v == null) return null;
    if (!(v instanceof Number n)) {
      throw GenealogyException.badRequest(key + " must be a number");
    }
    return n.longValue();
  }

  private static String text(Map<String, Object> in, String key) {
    Object v = in.get(key);
    if (v != null && !(v instanceof String)) {
      throw GenealogyException.badRequest(key + " must be a string");
    }
    return (String) v;
  }
}
