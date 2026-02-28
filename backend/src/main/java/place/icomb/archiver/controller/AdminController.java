package place.icomb.archiver.controller;

import java.util.ArrayList; // structured email response
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.model.AppUser;
import place.icomb.archiver.model.AppUserEmail;
import place.icomb.archiver.repository.AppUserEmailRepository;
import place.icomb.archiver.repository.AppUserRepository;

@RestController
@RequestMapping("/api/admin/users")
public class AdminController {

  private final AppUserRepository userRepository;
  private final AppUserEmailRepository emailRepository;
  private final JdbcTemplate jdbcTemplate;

  public AdminController(
      AppUserRepository userRepository,
      AppUserEmailRepository emailRepository,
      JdbcTemplate jdbcTemplate) {
    this.userRepository = userRepository;
    this.emailRepository = emailRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  @GetMapping
  public ResponseEntity<List<Map<String, Object>>> listUsers() {
    List<Map<String, Object>> users =
        jdbcTemplate.queryForList(
            "SELECT id, display_name, role, created_at, updated_at FROM app_user ORDER BY id");

    List<Map<String, Object>> emails =
        jdbcTemplate.queryForList("SELECT id, user_id, email FROM app_user_email ORDER BY id");

    Map<Object, List<Map<String, Object>>> emailsByUser =
        emails.stream().collect(Collectors.groupingBy(e -> e.get("user_id")));

    for (Map<String, Object> user : users) {
      List<Map<String, Object>> userEmails = emailsByUser.getOrDefault(user.get("id"), List.of());
      List<Map<String, Object>> structured = new ArrayList<>();
      for (Map<String, Object> e : userEmails) {
        Map<String, Object> em = new LinkedHashMap<>();
        em.put("id", e.get("id"));
        em.put("email", e.get("email"));
        structured.add(em);
      }
      user.put("emails", structured);
    }

    return ResponseEntity.ok(users);
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, Object> body) {
    String displayName = (String) body.get("displayName");
    String role = ((String) body.getOrDefault("role", "user")).toLowerCase();

    AppUser user = new AppUser();
    user.setDisplayName(displayName);
    user.setRole(role);
    user.setCreatedAt(java.time.Instant.now());
    user.setUpdatedAt(java.time.Instant.now());
    user = userRepository.save(user);

    @SuppressWarnings("unchecked")
    List<String> emails = (List<String>) body.get("emails");
    if (emails != null) {
      for (String email : emails) {
        if (email != null && !email.isBlank()) {
          AppUserEmail ue = new AppUserEmail();
          ue.setUserId(user.getId());
          ue.setEmail(email.trim().toLowerCase());
          emailRepository.save(ue);
        }
      }
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", user.getId());
    result.put("displayName", user.getDisplayName());
    result.put("role", user.getRole());
    return ResponseEntity.status(201).body(result);
  }

  @PutMapping("/{id}")
  public ResponseEntity<Map<String, Object>> updateUser(
      @PathVariable Long id, @RequestBody Map<String, Object> body) {
    return userRepository
        .findById(id)
        .map(
            user -> {
              if (body.containsKey("displayName")) {
                user.setDisplayName((String) body.get("displayName"));
              }
              if (body.containsKey("role")) {
                user.setRole(((String) body.get("role")).toLowerCase());
              }
              userRepository.save(user);

              Map<String, Object> result = new LinkedHashMap<>();
              result.put("id", user.getId());
              result.put("displayName", user.getDisplayName());
              result.put("role", user.getRole());
              return ResponseEntity.ok(result);
            })
        .orElse(ResponseEntity.notFound().build());
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
    if (userRepository.existsById(id)) {
      userRepository.deleteById(id);
      return ResponseEntity.noContent().build();
    }
    return ResponseEntity.notFound().build();
  }

  @PostMapping("/{id}/emails")
  public ResponseEntity<Map<String, Object>> addEmail(
      @PathVariable Long id, @RequestBody Map<String, String> body) {
    if (!userRepository.existsById(id)) {
      return ResponseEntity.notFound().build();
    }
    String email = body.get("email");
    if (email == null || email.isBlank()) {
      return ResponseEntity.badRequest().build();
    }

    AppUserEmail ue = new AppUserEmail();
    ue.setUserId(id);
    ue.setEmail(email.trim().toLowerCase());
    ue = emailRepository.save(ue);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", ue.getId());
    result.put("userId", ue.getUserId());
    result.put("email", ue.getEmail());
    return ResponseEntity.status(201).body(result);
  }

  @DeleteMapping("/{id}/emails/{emailId}")
  public ResponseEntity<Void> removeEmail(@PathVariable Long id, @PathVariable Long emailId) {
    return emailRepository
        .findById(emailId)
        .filter(e -> e.getUserId().equals(id))
        .map(
            e -> {
              emailRepository.deleteById(emailId);
              return ResponseEntity.noContent().<Void>build();
            })
        .orElse(ResponseEntity.notFound().build());
  }
}
