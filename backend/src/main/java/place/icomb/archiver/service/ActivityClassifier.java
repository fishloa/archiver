package place.icomb.archiver.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a request belongs in the activity log and what kind of event it is. The deny-list
 * of noise lives here and nowhere else.
 *
 * <p>Noise, for GET requests: SSE streams, file and image fetches, thumbnails, {@code /api/auth/},
 * version, actuator, stats and polling endpoints (pipeline stats, source status, PDF-export status,
 * translation status, translate capabilities, archive list), secondary calls a page load makes
 * alongside its main one (a record's page list, page detail, person matches) and the activity log's
 * own endpoints.
 */
public final class ActivityClassifier {

  public record Event(String kind, String target) {}

  private static final Pattern NOISE =
      Pattern.compile(
          "^/api/(records/events"
              + "|files(/.*)?"
              + "|auth(/.*)?"
              + "|version(/.*)?"
              + "|pipeline/.*"
              + "|viewer/source-status"
              + "|pdf-exports/\\d+"
              + "|records/archives"
              + "|records/\\d+/(pages|translation-status|timeline)"
              + "|pages/\\d+"
              + "|pages/\\d+/ocr-image/.*"
              + "|translate/capabilities"
              + "|family-tree/(page|record)-matches/\\d+"
              + "|admin/(activity(/.*)?|stats)"
              + "|.*/image(/.*|\\..*)?"
              + "|.*/thumbnails?(/.*)?"
              + ")$");

  private static final Pattern RECORD = Pattern.compile("^/api/records/(\\d+)$");
  private static final Pattern DOCUMENT = Pattern.compile("^/api/v1/documents/(\\d+)$");
  private static final Pattern PAGE_TEXT = Pattern.compile("^/api/pages/(\\d+)/text$");
  private static final Pattern PERSON = Pattern.compile("^/api/family-tree/person/(\\d+)$");
  private static final Pattern SEARCH =
      Pattern.compile("^/api/(search|family-tree/search|v1/search)$");

  private ActivityClassifier() {}

  /**
   * @param pageLookup resolves a page id to {@code "recordId/seq"}, or null
   */
  public static Optional<Event> classify(
      String method, String path, String query, String accept, Function<Long, String> pageLookup) {
    if (accept != null && accept.contains("text/event-stream")) {
      return Optional.empty();
    }
    if (path.startsWith("/actuator")) {
      return Optional.empty();
    }
    boolean read = "GET".equals(method) || "HEAD".equals(method);
    if (!read) {
      if ("OPTIONS".equals(method)) {
        return Optional.empty();
      }
      if ("POST".equals(method) && "/api/search/semantic".equals(path)) {
        return Optional.of(new Event("search", null));
      }
      return Optional.of(new Event("edit", null));
    }
    if (NOISE.matcher(path).matches()) {
      return Optional.empty();
    }
    Matcher m = RECORD.matcher(path);
    if (m.matches()) {
      return Optional.of(new Event("view_record", m.group(1)));
    }
    m = DOCUMENT.matcher(path);
    if (m.matches()) {
      return Optional.of(new Event("view_record", m.group(1)));
    }
    m = PAGE_TEXT.matcher(path);
    if (m.matches()) {
      return Optional.of(new Event("view_page", pageLookup.apply(Long.parseLong(m.group(1)))));
    }
    m = PERSON.matcher(path);
    if (m.matches()) {
      return Optional.of(new Event("view_person", m.group(1)));
    }
    if (SEARCH.matcher(path).matches()) {
      return Optional.of(new Event("search", queryParam(query, "q")));
    }
    return Optional.of(new Event("other", null));
  }

  static String queryParam(String query, String name) {
    if (query == null) {
      return null;
    }
    for (String part : query.split("&")) {
      int eq = part.indexOf('=');
      if (eq > 0 && part.substring(0, eq).equals(name)) {
        try {
          return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
          return part.substring(eq + 1);
        }
      }
    }
    return null;
  }
}
