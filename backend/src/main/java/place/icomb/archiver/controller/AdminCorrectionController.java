package place.icomb.archiver.controller;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.service.ManualCorrectionService;
import place.icomb.archiver.service.ManualCorrectionService.CorrectionException;

/**
 * Hand corrections to a stored transcription, translation or title: an exact passage (the default),
 * or with {@code "replaceAll": true} a whole page text or translation. Under {@code /api/admin}
 * because it changes what the archive says; every correction is kept in an audit row.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminCorrectionController {

  private final ManualCorrectionService corrections;

  public AdminCorrectionController(ManualCorrectionService corrections) {
    this.corrections = corrections;
  }

  @PostMapping("/pages/{pageId}/corrections")
  public ResponseEntity<Map<String, Object>> correctPage(
      @PathVariable long pageId, @RequestBody(required = false) Map<String, Object> body) {
    try {
      Map<String, Object> in = body == null ? Map.of() : body;
      return ResponseEntity.ok(
          corrections.correctPage(
              pageId,
              text(in, "field"),
              text(in, "find"),
              text(in, "replace"),
              text(in, "reason"),
              flag(in, "replaceAll")));
    } catch (CorrectionException e) {
      return refuse(e);
    }
  }

  @PostMapping("/records/{recordId}/corrections")
  public ResponseEntity<Map<String, Object>> correctRecord(
      @PathVariable long recordId, @RequestBody(required = false) Map<String, Object> body) {
    try {
      Map<String, Object> in = body == null ? Map.of() : body;
      return ResponseEntity.ok(
          corrections.correctRecord(
              recordId,
              text(in, "field"),
              text(in, "find"),
              text(in, "replace"),
              text(in, "reason")));
    } catch (CorrectionException e) {
      return refuse(e);
    }
  }

  @GetMapping("/records/{recordId}/corrections")
  public List<Map<String, Object>> list(@PathVariable long recordId) {
    return corrections.list(recordId);
  }

  /** A string field, or null when absent; anything else is a bad request, not a cast error. */
  private static String text(Map<String, Object> in, String key) {
    Object v = in.get(key);
    if (v == null) {
      return null;
    }
    if (!(v instanceof String s)) {
      throw new CorrectionException(
          ManualCorrectionService.Kind.BAD_REQUEST, key + " must be a string");
    }
    return s;
  }

  private static boolean flag(Map<String, Object> in, String key) {
    Object v = in.get(key);
    if (v == null) {
      return false;
    }
    if (!(v instanceof Boolean b)) {
      throw new CorrectionException(
          ManualCorrectionService.Kind.BAD_REQUEST, key + " must be true or false");
    }
    return b;
  }

  private static ResponseEntity<Map<String, Object>> refuse(CorrectionException e) {
    HttpStatus status =
        switch (e.kind()) {
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
          case CONFLICT -> HttpStatus.CONFLICT;
        };
    return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
  }
}
