package place.icomb.archiver.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.model.AppUser;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  @GetMapping("/me")
  public ResponseEntity<Map<String, Object>> me() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();

    if (auth == null || !(auth.getDetails() instanceof AppUser user)) {
      return ResponseEntity.ok(Map.of("authenticated", false));
    }

    return ResponseEntity.ok(
        Map.of(
            "authenticated",
            true,
            "email",
            auth.getName(),
            "displayName",
            user.getDisplayName() != null ? user.getDisplayName() : "",
            "role",
            user.getRole(),
            "lang",
            user.getLang() != null ? user.getLang() : "en"));
  }
}
