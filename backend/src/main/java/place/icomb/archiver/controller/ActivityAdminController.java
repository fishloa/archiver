package place.icomb.archiver.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.service.ActivityService;

@RestController
public class ActivityAdminController {

  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 1000;

  private final ActivityService activity;

  public ActivityAdminController(ActivityService activity) {
    this.activity = activity;
  }

  @GetMapping("/api/admin/activity/sessions")
  public ResponseEntity<?> sessions(
      @RequestParam(required = false) Long userId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) Integer limit) {
    try {
      return ResponseEntity.ok(
          activity.sessions(userId, parse(from, false), parse(to, true), clamp(limit)));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/api/admin/activity/events")
  public ResponseEntity<?> events(
      @RequestParam(required = false) Long userId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) Integer limit) {
    try {
      return ResponseEntity.ok(
          activity.events(userId, parse(from, false), parse(to, true), clamp(limit)));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  private static int clamp(Integer limit) {
    if (limit == null || limit < 1) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limit, MAX_LIMIT);
  }

  /** An ISO instant, or a plain date (UTC; as an upper bound, the start of the next day). */
  private static OffsetDateTime parse(String value, boolean upper) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      if (value.length() == 10) {
        LocalDate d = LocalDate.parse(value);
        return (upper ? d.plusDays(1) : d).atStartOfDay().atOffset(ZoneOffset.UTC);
      }
      return Instant.parse(value).atOffset(ZoneOffset.UTC);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("not a date or ISO instant: " + value);
    }
  }
}
