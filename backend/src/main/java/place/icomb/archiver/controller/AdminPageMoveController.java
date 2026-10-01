package place.icomb.archiver.controller;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.service.PageMoveException;
import place.icomb.archiver.service.PageMoveService;

/**
 * Moves pages between records. Under {@code /api/admin} because it rearranges an archive.
 *
 * <p>The page is named by its id, not its position: ids are stable, positions shift as earlier
 * pages move, and a caller iterating over positions would move the wrong pages.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminPageMoveController {

  private final PageMoveService moves;

  public AdminPageMoveController(PageMoveService moves) {
    this.moves = moves;
  }

  @PostMapping("/records/{recordId}/pages/{pageId}/move")
  public ResponseEntity<Map<String, Object>> movePage(
      @PathVariable long recordId,
      @PathVariable long pageId,
      @RequestBody(required = false) Map<String, Object> body) {
    try {
      Map<String, Object> in = body == null ? Map.of() : body;
      long target = requiredLong(in, "targetRecordId");
      Integer seq = in.get("seq") == null ? null : requiredInt(in, "seq");
      boolean cross = flag(in, "allowCrossArchive");
      return ResponseEntity.ok(describe(moves.movePage(recordId, pageId, target, seq, cross)));
    } catch (PageMoveException e) {
      return refuse(e);
    }
  }

  static Map<String, Object> describe(PageMoveService.Moved m) {
    Map<String, Object> out = new HashMap<>();
    out.put("pageIds", m.pageIds());
    out.put("sourceRecordId", m.sourceRecordId());
    out.put("sourcePageCount", m.sourcePageCount());
    out.put("targetRecordId", m.targetRecordId());
    out.put("targetPageCount", m.targetPageCount());
    return out;
  }

  static ResponseEntity<Map<String, Object>> refuse(PageMoveException e) {
    HttpStatus status =
        switch (e.kind()) {
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
          case CONFLICT -> HttpStatus.CONFLICT;
        };
    return ResponseEntity.status(status).body(Map.of("error", String.valueOf(e.getMessage())));
  }

  static long requiredLong(Map<String, Object> body, String key) {
    Object v = body.get(key);
    if (!(v instanceof Number n)) {
      throw new PageMoveException(
          PageMoveException.Kind.BAD_REQUEST, key + " is required and must be a number");
    }
    try {
      return new BigDecimal(n.toString()).longValueExact();
    } catch (ArithmeticException | NumberFormatException e) {
      throw new PageMoveException(
          PageMoveException.Kind.BAD_REQUEST, key + " must be a whole number in range");
    }
  }

  static int requiredInt(Map<String, Object> body, String key) {
    long v = requiredLong(body, key);
    if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
      throw new PageMoveException(
          PageMoveException.Kind.BAD_REQUEST, key + " must be a whole number in range");
    }
    return (int) v;
  }

  static boolean flag(Map<String, Object> body, String key) {
    Object v = body.get(key);
    if (v == null) {
      return false;
    }
    if (!(v instanceof Boolean b)) {
      throw new PageMoveException(
          PageMoveException.Kind.BAD_REQUEST, key + " must be true or false");
    }
    return b;
  }
}
