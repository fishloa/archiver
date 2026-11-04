package place.icomb.archiver.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.config.ProxyAuthFilter;

/**
 * Access requests from people who signed in with an address that is not on the allowlist, and the
 * admin's decision on them.
 *
 * <p>The requester side lives under {@code /api/auth/}, which is open, so each call checks for
 * itself that the caller has signed in: {@link ProxyAuthFilter} sets the signed-in-as attribute
 * only for a trusted proxy peer. The admin side lives under {@code /api/admin/}, admin only.
 */
@RestController
public class AccessRequestController {

  private static final int MAX_NAME = 200;
  private static final int MAX_MESSAGE = 2000;

  private final JdbcClient jdbc;

  public AccessRequestController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/api/auth/access-request")
  public ResponseEntity<Map<String, Object>> mine(HttpServletRequest request) {
    String email = signedInAs(request);
    if (email == null) {
      return ResponseEntity.status(401).build();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("email", email);
    findByEmail(email)
        .ifPresentOrElse(
            row -> {
              body.put("status", row.get("status"));
              body.put("name", row.get("name"));
              body.put("message", row.get("message"));
              body.put("createdAt", row.get("created_at"));
            },
            () -> body.put("status", "none"));
    return ResponseEntity.ok(body);
  }

  @PostMapping("/api/auth/access-request")
  @Transactional
  public ResponseEntity<Map<String, Object>> submit(
      HttpServletRequest request, @RequestBody Map<String, Object> body) {
    String email = signedInAs(request);
    if (email == null) {
      return ResponseEntity.status(401).build();
    }
    String name = trimmed(body.get("name"));
    String message = trimmed(body.get("message"));
    if (name.isEmpty() || name.length() > MAX_NAME || message.length() > MAX_MESSAGE) {
      return ResponseEntity.badRequest()
          .body(
              Map.of(
                  "error",
                  "name is required (at most "
                      + MAX_NAME
                      + " characters); message at most "
                      + MAX_MESSAGE));
    }

    Optional<Map<String, Object>> existing = findByEmail(email);
    if (existing.isPresent() && !"pending".equals(existing.get().get("status"))) {
      return ResponseEntity.status(409)
          .body(Map.of("error", "already decided", "status", existing.get().get("status")));
    }
    if (existing.isPresent()) {
      jdbc.sql(
              "UPDATE access_request SET name = :name, message = :message, updated_at = now()"
                  + " WHERE email = :email")
          .param("name", name)
          .param("message", message)
          .param("email", email)
          .update();
      return ResponseEntity.ok(Map.of("status", "pending"));
    }
    jdbc.sql("INSERT INTO access_request (email, name, message) VALUES (:email, :name, :message)")
        .param("email", email)
        .param("name", name)
        .param("message", message)
        .update();
    return ResponseEntity.status(201).body(Map.of("status", "pending"));
  }

  @GetMapping("/api/admin/access-requests")
  public List<Map<String, Object>> list(@RequestParam(defaultValue = "pending") String status) {
    String sql =
        "SELECT id, email, name, message, status, created_at, updated_at, decided_at, decided_by,"
            + " user_id FROM access_request";
    if ("all".equals(status)) {
      return jdbc.sql(sql + " ORDER BY created_at DESC").query().listOfRows();
    }
    return jdbc.sql(sql + " WHERE status = :status ORDER BY created_at DESC")
        .param("status", status)
        .query()
        .listOfRows();
  }

  /**
   * Grants access. The address is added to an existing user when {@code userId} is given, otherwise
   * a new user is created with {@code displayName} (default: the name the requester gave) and
   * {@code role} (default: user). An address already on the allowlist is left as it is.
   */
  @PostMapping("/api/admin/access-requests/{id}/approve")
  @Transactional
  public ResponseEntity<Map<String, Object>> approve(
      @PathVariable long id,
      @RequestBody(required = false) Map<String, Object> body,
      Authentication auth) {
    Map<String, Object> options = body == null ? Map.of() : body;
    Optional<Map<String, Object>> row = findById(id);
    if (row.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    if (!"pending".equals(row.get().get("status"))) {
      return ResponseEntity.status(409).body(Map.of("error", "not pending"));
    }
    String email = (String) row.get().get("email");
    String role = trimmed(options.getOrDefault("role", "user")).toLowerCase();
    if (!role.equals("user") && !role.equals("admin")) {
      return ResponseEntity.badRequest().body(Map.of("error", "role must be user or admin"));
    }

    Long userId =
        jdbc.sql("SELECT user_id FROM app_user_email WHERE email = :email")
            .param("email", email)
            .query(Long.class)
            .optional()
            .orElse(null);
    if (userId == null) {
      Object requestedUser = options.get("userId");
      if (requestedUser instanceof Number n) {
        userId = n.longValue();
        boolean exists =
            jdbc.sql("SELECT count(*) FROM app_user WHERE id = :id")
                    .param("id", userId)
                    .query(Long.class)
                    .single()
                > 0;
        if (!exists) {
          return ResponseEntity.badRequest().body(Map.of("error", "no such user"));
        }
      } else {
        String displayName = trimmed(options.get("displayName"));
        if (displayName.isEmpty()) {
          displayName = (String) row.get().get("name");
        }
        userId =
            jdbc.sql(
                    "INSERT INTO app_user (display_name, role, created_at, updated_at)"
                        + " VALUES (:name, :role, now(), now()) RETURNING id")
                .param("name", displayName)
                .param("role", role)
                .query(Long.class)
                .single();
      }
      jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:userId, :email)")
          .param("userId", userId)
          .param("email", email)
          .update();
    }

    decide(id, "approved", auth, userId);
    return ResponseEntity.ok(Map.of("status", "approved", "userId", userId));
  }

  @PostMapping("/api/admin/access-requests/{id}/deny")
  @Transactional
  public ResponseEntity<Map<String, Object>> deny(@PathVariable long id, Authentication auth) {
    Optional<Map<String, Object>> row = findById(id);
    if (row.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    if (!"pending".equals(row.get().get("status"))) {
      return ResponseEntity.status(409).body(Map.of("error", "not pending"));
    }
    decide(id, "denied", auth, null);
    return ResponseEntity.ok(Map.of("status", "denied"));
  }

  /** Removes a request, so that a denied address may ask again. Granted access is untouched. */
  @DeleteMapping("/api/admin/access-requests/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id) {
    int removed = jdbc.sql("DELETE FROM access_request WHERE id = :id").param("id", id).update();
    return removed == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
  }

  private void decide(long id, String status, Authentication auth, Long userId) {
    jdbc.sql(
            "UPDATE access_request SET status = :status, decided_at = now(), updated_at = now(),"
                + " decided_by = :by, user_id = :userId WHERE id = :id")
        .param("status", status)
        .param("by", auth == null ? "unknown" : auth.getName())
        .param("userId", userId)
        .param("id", id)
        .update();
  }

  private Optional<Map<String, Object>> findByEmail(String email) {
    return jdbc
        .sql("SELECT * FROM access_request WHERE email = :email")
        .param("email", email)
        .query()
        .listOfRows()
        .stream()
        .findFirst();
  }

  private Optional<Map<String, Object>> findById(long id) {
    return jdbc
        .sql("SELECT * FROM access_request WHERE id = :id")
        .param("id", id)
        .query()
        .listOfRows()
        .stream()
        .findFirst();
  }

  private static String signedInAs(HttpServletRequest request) {
    Object value = request.getAttribute(ProxyAuthFilter.SIGNED_IN_AS_ATTRIBUTE);
    return value instanceof String s && !s.isBlank() ? s.trim().toLowerCase() : null;
  }

  private static String trimmed(Object value) {
    return value instanceof String s ? s.trim() : "";
  }
}
