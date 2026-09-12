package place.icomb.archiver.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What is actually deployed.
 *
 * <p>Production used to run whatever {@code :latest} happened to be when a stack was last
 * redeployed, with no way to tell from the outside which commit that was. Releases are tagged now,
 * and the tag is stamped into the image, so this answers the question without shelling into a
 * container.
 */
@RestController
@RequestMapping("/api/version")
public class VersionController {

  private final String version;
  private final String commit;

  public VersionController(
      @Value("${archiver.version:dev}") String version,
      @Value("${archiver.commit:unknown}") String commit) {
    this.version = version;
    this.commit = commit;
  }

  @GetMapping
  public Map<String, String> version() {
    Map<String, String> out = new LinkedHashMap<>();
    out.put("version", version);
    out.put("commit", commit);
    return out;
  }
}
