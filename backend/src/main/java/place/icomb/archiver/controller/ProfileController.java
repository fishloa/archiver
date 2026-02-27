package place.icomb.archiver.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
@RequestMapping("/api/profile")
public class ProfileController {

  private final AppUserRepository userRepository;
  private final AppUserEmailRepository emailRepository;

  public ProfileController(
      AppUserRepository userRepository, AppUserEmailRepository emailRepository) {
    this.userRepository = userRepository;
    this.emailRepository = emailRepository;
  }

  private AppUser currentUser() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getDetails() instanceof AppUser user)) {
      return null;
    }
    return user;
  }

  private String currentLoginEmail() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null ? auth.getName() : null;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> getProfile() {
    AppUser user = currentUser();
    if (user == null) {
      return ResponseEntity.status(401).build();
    }

    List<AppUserEmail> emails = emailRepository.findByUserId(user.getId());

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", user.getId());
    result.put("displayName", user.getDisplayName() != null ? user.getDisplayName() : "");
    result.put("role", user.getRole());
    result.put("loginEmail", currentLoginEmail());
    result.put(
        "emails",
        emails.stream()
            .map(
                e -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("id", e.getId());
                  m.put("email", e.getEmail());
                  return m;
                })
            .toList());
    return ResponseEntity.ok(result);
  }

  @PutMapping
  public ResponseEntity<Map<String, Object>> updateProfile(@RequestBody Map<String, Object> body) {
    AppUser user = currentUser();
    if (user == null) {
      return ResponseEntity.status(401).build();
    }

    // Re-fetch to get latest state
    user = userRepository.findById(user.getId()).orElse(null);
    if (user == null) {
      return ResponseEntity.status(401).build();
    }

    if (body.containsKey("displayName")) {
      user.setDisplayName((String) body.get("displayName"));
    }
    userRepository.save(user);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", user.getId());
    result.put("displayName", user.getDisplayName());
    return ResponseEntity.ok(result);
  }

  @PostMapping("/emails")
  public ResponseEntity<Map<String, Object>> addEmail(@RequestBody Map<String, String> body) {
    AppUser user = currentUser();
    if (user == null) {
      return ResponseEntity.status(401).build();
    }

    String email = body.get("email");
    if (email == null || email.isBlank()) {
      return ResponseEntity.badRequest().build();
    }

    AppUserEmail ue = new AppUserEmail();
    ue.setUserId(user.getId());
    ue.setEmail(email.trim().toLowerCase());
    ue = emailRepository.save(ue);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", ue.getId());
    result.put("email", ue.getEmail());
    return ResponseEntity.status(201).body(result);
  }

  @DeleteMapping("/emails/{emailId}")
  public ResponseEntity<?> removeEmail(@PathVariable Long emailId) {
    AppUser user = currentUser();
    if (user == null) {
      return ResponseEntity.status(401).build();
    }

    String loginEmail = currentLoginEmail();

    return emailRepository
        .findById(emailId)
        .filter(e -> e.getUserId().equals(user.getId()))
        .map(
            e -> {
              if (e.getEmail().equalsIgnoreCase(loginEmail)) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Cannot remove the email you are logged in with"));
              }
              emailRepository.deleteById(emailId);
              return ResponseEntity.noContent().build();
            })
        .orElse(ResponseEntity.notFound().build());
  }
}
