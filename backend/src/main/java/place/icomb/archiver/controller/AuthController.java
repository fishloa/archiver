package place.icomb.archiver.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.config.ProxyAuthFilter;
import place.icomb.archiver.model.AppUser;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  @GetMapping("/me")
  public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();

    if (auth == null || !(auth.getDetails() instanceof AppUser user)) {
      var anonymous = new java.util.LinkedHashMap<String, Object>();
      anonymous.put("authenticated", false);
      Object signedInAs = request.getAttribute(ProxyAuthFilter.SIGNED_IN_AS_ATTRIBUTE);
      if (signedInAs instanceof String email && !email.isBlank()) {
        anonymous.put("signedInAs", email);
      }
      return ResponseEntity.ok(anonymous);
    }

    var result = new java.util.LinkedHashMap<String, Object>();
    result.put("authenticated", true);
    result.put("email", auth.getName());
    result.put("displayName", user.getDisplayName() != null ? user.getDisplayName() : "");
    result.put("role", user.getRole());
    result.put("lang", user.getLang() != null ? user.getLang() : "en");
    result.put("familyTreePersonId", user.getFamilyTreePersonId());
    return ResponseEntity.ok(result);
  }
}
