package place.icomb.archiver.service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class FamilyTreeService {
  private static final Logger log = LoggerFactory.getLogger(FamilyTreeService.class);

  private static final Set<String> TITLE_WORDS =
      Set.of(
          "count",
          "graf",
          "gf",
          "grafin",
          "gfn",
          "freiherr",
          "frhr",
          "frn",
          "furst",
          "fst",
          "prinz",
          "pr",
          "princess",
          "pss",
          "hon",
          "hon.");

  private static final Pattern CODE_PREFIX = Pattern.compile("^([A-Z]\\d+)\\.\\s+");
  private static final Pattern BIRTH_PATTERN = Pattern.compile("\\*([^;]+?)(?=;|$)");
  private static final Pattern DEATH_PATTERN = Pattern.compile("\\+([^;]+?)(?=;|$)");
  private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(1[0-9]{3}|20[0-9]{2})\\b");
  private static final Pattern SPOUSE_PATTERN =
      Pattern.compile("(?:^|;)\\s*(?:\\d?m[\\.:]|m\\.)\\s*(.+?)(?=;\\s*\\dm|$)");

  @Autowired @Lazy private PersonMatchService personMatchService;

  @Value("${archiver.family-tree.file:}")
  private String externalFilePath;

  private volatile List<Person> allPeople = List.of();
  private volatile Map<Integer, Person> peopleById = Map.of();
  private volatile Person alexander;

  @PostConstruct
  public void init() {
    reload();
  }

  public synchronized void reload() {
    try {
      List<String> lines = readFile();
      List<Person> parsed = parse(lines);
      mergeCrossSections(parsed);

      Map<Integer, Person> byId = new LinkedHashMap<>();
      for (Person p : parsed) byId.put(p.id, p);

      this.allPeople = List.copyOf(parsed);
      this.peopleById = Map.copyOf(byId);
      this.alexander = findAlexander(parsed);

      log.info(
          "Family tree loaded: {} people, alexander={}",
          parsed.size(),
          alexander != null ? alexander.id + " " + alexander.name : "NOT FOUND");

      // Clear cached person matches since the family tree data changed
      try {
        personMatchService.invalidateAll();
      } catch (Exception e) {
        // May fail during startup when DB is not ready yet — that's fine
        log.debug(
            "Could not invalidate person matches (expected during startup): {}", e.getMessage());
      }
    } catch (IOException e) {
      log.error("Failed to load genealogy file", e);
    }
  }

  // --- Public API ---

  public List<SearchResult> search(String query, int limit) {
    if (query == null || query.isBlank()) return List.of();
    List<String> qTokens = tokenize(normalize(query));
    if (qTokens.isEmpty()) return List.of();

    List<SearchResult> results = new ArrayList<>();
    for (Person p : allPeople) {
      double score = score(qTokens, p);
      if (score > 0.1) {
        results.add(
            new SearchResult(
                p.id,
                p.name,
                p.fullEntry,
                p.section,
                p.fullCode(),
                score,
                p.birthYear,
                p.deathYear));
      }
    }
    results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
    return results.size() > limit ? results.subList(0, limit) : results;
  }

  public RelationshipResult relate(int personId) {
    Person person = peopleById.get(personId);
    if (person == null || alexander == null) return null;
    if (person.id == alexander.id) {
      return new RelationshipResult(
          personId,
          person.name,
          "This is Alexander himself",
          "Alexander Friedrich Josef Paul Maria Czernin, nicknamed Lucki/Papi",
          person.name,
          person.id,
          0,
          0);
    }

    // Collect ancestors of the queried person with step count
    Map<Integer, Integer> personAncestors = new LinkedHashMap<>();
    Person cur = person;
    int steps = 0;
    while (cur != null) {
      personAncestors.put(cur.id, steps);
      cur = cur.parent;
      steps++;
    }

    // Walk up from Alexander to find LCA
    cur = alexander;
    int alexSteps = 0;
    while (cur != null) {
      if (personAncestors.containsKey(cur.id)) {
        // Found LCA
        Person lca = cur;
        int dA = personAncestors.get(cur.id); // steps from person to LCA
        int dB = alexSteps; // steps from Alexander to LCA

        String kinship = kinshipLabel(dA, dB);
        String path = buildPathDescription(person, alexander, lca, dA, dB);

        return new RelationshipResult(
            personId, person.name, kinship, path, lca.name, lca.id, dA, dB);
      }
      cur = cur.parent;
      alexSteps++;
    }

    return null; // no common ancestor found
  }

  public Person getPerson(int id) {
    return peopleById.get(id);
  }

  public List<Map<String, Object>> getLifeEvents(Person p) {
    List<Map<String, Object>> events = new ArrayList<>();

    String text = CODE_PREFIX.matcher(p.fullEntry).replaceFirst("");
    text = text.replaceFirst("^\\[\\d?m\\.?\\]\\s*", "");

    String[] segments = text.split(";");
    for (String seg : segments) {
      String s = seg.trim();
      if (s.isEmpty() || s.startsWith("all children")) continue;

      int starIdx = idxOutsideParens(s, '*');
      int plusIdx = idxOutsideParens(s, '+');

      if (starIdx >= 0) {
        String afterStar = s.substring(starIdx + 1);
        int plusInBirth = idxOutsideParens(afterStar, '+');
        if (plusInBirth >= 0) {
          events.add(
              lifeEvent(
                  "birth", afterStar.substring(0, plusInBirth).replaceAll(",\\s*$", "").trim()));
          events.add(lifeEvent("death", afterStar.substring(plusInBirth + 1).trim()));
        } else {
          events.add(lifeEvent("birth", afterStar.trim()));
        }
      } else if (plusIdx >= 0) {
        events.add(lifeEvent("death", s.substring(plusIdx + 1).trim()));
      } else if (s.matches("^\\d?m[\\.:].*") || s.startsWith("m.")) {
        String mt = s.replaceFirst("^\\d?m[\\.:]\\s*", "").trim();
        events.add(lifeEvent(s.contains("(div") ? "marriage & divorce" : "marriage", mt));
      }
    }

    events.sort(
        Comparator.comparingInt(
            e -> {
              Object y = e.get("year");
              return y instanceof Integer ? (Integer) y : 9999;
            }));
    return events;
  }

  private int idxOutsideParens(String s, char target) {
    int depth = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '(') depth++;
      else if (c == ')') depth = Math.max(0, depth - 1);
      else if (c == target && depth == 0) return i;
    }
    return -1;
  }

  private Map<String, Object> lifeEvent(String type, String text) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", type);
    m.put("text", text);
    m.put("year", extractYear(text));
    return m;
  }

  public List<Person> getAllPeople() {
    return allPeople;
  }

  Set<String> getTitleWords() {
    return TITLE_WORDS;
  }

  public int getPersonCount() {
    return allPeople.size();
  }

  // --- Parsing ---

  private List<String> readFile() throws IOException {
    if (externalFilePath != null && !externalFilePath.isBlank()) {
      return Files.readAllLines(Path.of(externalFilePath));
    }
    var resource = new ClassPathResource("czernin-genealogy.txt");
    return new String(resource.getInputStream().readAllBytes()).lines().toList();
  }

  private List<Person> parse(List<String> lines) {
    List<Person> people = new ArrayList<>();
    String section = null;
    // depthStack[d] = most recent person at depth d
    Map<Integer, Person> depthStack = new HashMap<>();
    int nextId = 1;

    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) continue;
      if (isSkipLine(trimmed)) continue;

      // Detect section headers
      if (trimmed.startsWith("CZERNIN 1")) {
        section = "CZERNIN 1";
        depthStack.clear();
        continue;
      }
      if (trimmed.startsWith("CZERNIN 2")) {
        section = "CZERNIN 2";
        depthStack.clear();
        continue;
      }
      if (trimmed.startsWith("CZERNIN 3")) {
        section = "CZERNIN 3";
        depthStack.clear();
        continue;
      }
      if (section == null) continue;

      int spaces = countLeadingSpaces(line);
      int depth = spaces / 2;

      Person p = parsePerson(trimmed, section, depth, nextId);
      if (p == null) continue;
      nextId++;

      // Build parent-child from depth
      if (depth > 0) {
        Person parent = depthStack.get(depth - 1);
        if (parent != null) {
          p.parent = parent;
          parent.children.add(p);
          // Build accumulated code
          p.parentCode = parent.fullCode();
        }
      }

      depthStack.put(depth, p);
      // Clear deeper entries
      depthStack.entrySet().removeIf(e -> e.getKey() > depth);

      people.add(p);
    }

    return people;
  }

  private boolean isSkipLine(String trimmed) {
    return trimmed.startsWith("=")
        || trimmed.startsWith("Source:")
        || trimmed.startsWith("Notation:")
        || trimmed.startsWith("Indentation")
        || trimmed.startsWith("END OF FILE")
        || trimmed.startsWith("CZERNIN FAMILY")
        || trimmed.startsWith("---")
        || trimmed.contains("THIS IS ALEXANDER");
  }

  private int countLeadingSpaces(String line) {
    int n = 0;
    for (int i = 0; i < line.length() && line.charAt(i) == ' '; i++) n++;
    return n;
  }

  private Person parsePerson(String line, String section, int depth, int id) {
    Person p = new Person();
    p.id = id;
    p.section = section;
    p.depth = depth;
    p.fullEntry = line;
    p.children = new ArrayList<>();
    p.spouses = new ArrayList<>();

    String rest = line;

    // Extract code prefix like "A1. " or "B2. "
    Matcher cm = CODE_PREFIX.matcher(rest);
    if (cm.find()) {
      p.code = cm.group(1);
      rest = rest.substring(cm.end());
    }

    // Handle marriage-number prefixed children like "[1m.] " or "[2m.] "
    if (rest.startsWith("[")) {
      int bracket = rest.indexOf(']');
      if (bracket > 0) {
        rest = rest.substring(bracket + 1).trim();
      }
    }

    // Extract name: text up to first comma, semicolon, or '*'
    int nameEnd = rest.length();
    for (int i = 0; i < rest.length(); i++) {
      char c = rest.charAt(i);
      if (c == ',' || c == ';' || c == '*') {
        nameEnd = i;
        break;
      }
    }
    p.name = rest.substring(0, nameEnd).trim();
    if (p.name.isEmpty()) return null;

    // Remove ">>> CONTINUES..." suffix from name
    int contIdx = p.name.indexOf(">>>");
    if (contIdx >= 0) {
      p.name = p.name.substring(0, contIdx).trim();
    }

    // Parse birth
    Matcher bm = BIRTH_PATTERN.matcher(rest);
    if (bm.find()) {
      String birthInfo = bm.group(1).trim();
      // Remove parenthesized spouse birth info
      if (!birthInfo.startsWith("(")) {
        p.birthYear = extractYear(birthInfo);
        p.birthPlace = extractPlace(birthInfo);
      }
    }

    // Parse death
    Matcher dm = DEATH_PATTERN.matcher(rest);
    if (dm.find()) {
      String deathInfo = dm.group(1).trim();
      if (!deathInfo.startsWith("(")) {
        p.deathYear = extractYear(deathInfo);
      }
    }

    // Parse spouses — simple: find text after "m. " or "Nm:" patterns
    extractSpouses(rest, p);

    return p;
  }

  private void extractSpouses(String text, Person p) {
    // Split by semicolons and look for marriage markers
    String[] parts = text.split(";");
    for (String part : parts) {
      String t = part.trim();
      // Match "m. date Name" or "1m: date Name" etc.
      if (t.matches("^\\d?m[\\.:].*") || t.startsWith("m.")) {
        // Remove the marriage prefix and optional date
        String spouse = t.replaceFirst("^\\d?m[\\.:]\\s*", "");
        // Remove leading date like "1594 " or "ca 1594 "
        spouse = spouse.replaceFirst("^(?:ca\\s+)?\\d{4}\\s+", "");
        // Remove trailing parenthetical death info
        spouse = spouse.replaceAll("\\([^)]*\\)\\s*$", "").trim();
        // Remove >>> continues
        int ci = spouse.indexOf(">>>");
        if (ci >= 0) spouse = spouse.substring(0, ci).trim();
        if (!spouse.isEmpty()) p.spouses.add(spouse);
      }
    }
  }

  private Integer extractYear(String text) {
    Matcher m = YEAR_PATTERN.matcher(text);
    return m.find() ? Integer.parseInt(m.group(1)) : null;
  }

  private String extractPlace(String birthInfo) {
    // Birth info is like "Wien 30.4.1913" or "Praha 1.8.1833" or just "1447"
    // Place is text before the first digit sequence that looks like a date
    String cleaned = birthInfo.replaceAll("\\(.*\\)", "").trim();
    // Remove "ca " prefix
    cleaned = cleaned.replaceFirst("^ca\\s+", "");
    // Try to get text before the first date-like pattern
    String place = cleaned.replaceAll("\\d+[\\./]\\d+[\\./]?\\d*", "").replaceAll("\\d{4}", "");
    place = place.replaceAll("[,.]\\s*$", "").trim();
    return place.isEmpty() ? null : place;
  }

  // --- Cross-section merging ---

  private void mergeCrossSections(List<Person> people) {
    // Find I5 and I9 in CZERNIN 1 that have ">>> CONTINUES"
    Person i5 = null, i9 = null;
    for (Person p : people) {
      if (!"CZERNIN 1".equals(p.section)) continue;
      if (p.fullEntry.contains(">>> CONTINUES IN CZERNIN 2")) i5 = p;
      if (p.fullEntry.contains(">>> CONTINUES IN CZERNIN 3")) i9 = p;
    }

    // Find CZERNIN 2 root and CZERNIN 3 root (depth 0)
    Person c2root = null, c3root = null;
    for (Person p : people) {
      if ("CZERNIN 2".equals(p.section) && p.depth == 0 && p.parent == null) c2root = p;
      if ("CZERNIN 3".equals(p.section) && p.depth == 0 && p.parent == null) c3root = p;
    }

    // Merge: move children of c2root/c3root to i5/i9
    if (i5 != null && c2root != null) {
      for (Person child : c2root.children) {
        child.parent = i5;
        i5.children.add(child);
      }
      people.remove(c2root);
      log.info("Merged CZERNIN 2 ({} children) under I5 ({})", c2root.children.size(), i5.name);
    }
    if (i9 != null && c3root != null) {
      for (Person child : c3root.children) {
        child.parent = i9;
        i9.children.add(child);
      }
      people.remove(c3root);
      log.info("Merged CZERNIN 3 ({} children) under I9 ({})", c3root.children.size(), i9.name);
    }
  }

  // --- Alexander lookup ---

  private Person findAlexander(List<Person> people) {
    for (Person p : people) {
      if (p.fullEntry.contains("Alexander Friedrich Josef Paul Maria")) {
        return p;
      }
    }
    return null;
  }

  // --- Fuzzy search ---

  private double score(List<String> queryTokens, Person person) {
    List<String> nameTokens = tokenize(normalize(person.name));
    // Also search the fullEntry for broader matching
    List<String> entryTokens = tokenize(normalize(person.fullEntry));

    double exactMatches = 0, substringMatches = 0, fuzzyMatches = 0;

    for (String qt : queryTokens) {
      boolean matched = false;
      // Check name tokens first (higher value)
      for (String nt : nameTokens) {
        if (nt.equals(qt)) {
          exactMatches++;
          matched = true;
          break;
        }
      }
      if (matched) continue;

      for (String nt : nameTokens) {
        if (nt.contains(qt) || qt.contains(nt)) {
          substringMatches++;
          matched = true;
          break;
        }
      }
      if (matched) continue;

      for (String nt : nameTokens) {
        if (qt.length() >= 3 && nt.length() >= 3 && levenshtein(qt, nt) <= 2) {
          fuzzyMatches++;
          matched = true;
          break;
        }
      }
      if (matched) continue;

      // Fall back to entry tokens with lower weight
      for (String et : entryTokens) {
        if (et.equals(qt)) {
          exactMatches += 0.3;
          break;
        }
      }
    }

    double raw =
        (exactMatches * 3.0 + substringMatches * 1.5 + fuzzyMatches * 0.5)
            / (queryTokens.size() * 3.0);
    return Math.min(1.0, raw);
  }

  String normalize(String s) {
    String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);
    return nfd.replaceAll("\\p{Mn}", "").toLowerCase();
  }

  List<String> tokenize(String s) {
    return Arrays.stream(s.split("[\\s,;.()]+"))
        .filter(t -> !t.isEmpty() && t.length() > 1 && !TITLE_WORDS.contains(t))
        .toList();
  }

  int levenshtein(String a, String b) {
    int[][] dp = new int[a.length() + 1][b.length() + 1];
    for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
    for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
    for (int i = 1; i <= a.length(); i++) {
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        dp[i][j] = Math.min(dp[i - 1][j] + 1, Math.min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost));
      }
    }
    return dp[a.length()][b.length()];
  }

  // --- Kinship ---

  private String kinshipLabel(int dA, int dB) {
    if (dA == 1 && dB == 1) return "sibling";
    if (dA == 0) return descend(dB);
    if (dB == 0) return ascend(dA);
    if (dA == 1 && dB == 2) return "uncle/aunt";
    if (dA == 2 && dB == 1) return "nephew/niece";
    if (dA == 1 && dB > 2) return "great-".repeat(dB - 2) + "uncle/aunt";
    if (dB == 1 && dA > 2) return "great-".repeat(dA - 2) + "nephew/niece";
    if (dA == dB) return ordinal(dA - 1) + " cousin";
    int cousinDeg = Math.min(dA, dB) - 1;
    int removed = Math.abs(dA - dB);
    return ordinal(cousinDeg)
        + " cousin, "
        + removed
        + (removed == 1 ? " time" : " times")
        + " removed";
  }

  private String ascend(int n) {
    if (n == 1) return "child";
    if (n == 2) return "grandchild";
    return "great-".repeat(n - 2) + "grandchild";
  }

  private String descend(int n) {
    if (n == 1) return "parent";
    if (n == 2) return "grandparent";
    return "great-".repeat(n - 2) + "grandparent";
  }

  private String ordinal(int n) {
    if (n <= 0) return String.valueOf(n);
    int mod100 = n % 100;
    int mod10 = n % 10;
    String suffix =
        (mod10 == 1 && mod100 != 11)
            ? "st"
            : (mod10 == 2 && mod100 != 12) ? "nd" : (mod10 == 3 && mod100 != 13) ? "rd" : "th";
    return n + suffix;
  }

  private String buildPathDescription(
      Person person, Person alex, Person lca, int dPerson, int dAlex) {
    var sb = new StringBuilder();

    // Collect path from person to LCA
    List<Person> personPath = pathToAncestor(person, lca);
    List<Person> alexPath = pathToAncestor(alex, lca);

    if (dPerson == 1 && dAlex == 1) {
      // Siblings
      sb.append(person.name)
          .append(" and Alexander are siblings (children of ")
          .append(lca.name)
          .append(")");
    } else if (dPerson >= 2 && dAlex >= 2) {
      // Cousins — describe through the divergence point
      // Person's ancestor at depth 1 from LCA and Alex's ancestor at depth 1 from LCA
      Person personBranch = personPath.size() >= 2 ? personPath.get(personPath.size() - 2) : person;
      Person alexBranch = alexPath.size() >= 2 ? alexPath.get(alexPath.size() - 2) : alex;

      if (dPerson == 2 && dAlex == 2) {
        sb.append(person.name)
            .append("'s father ")
            .append(personBranch.name)
            .append(" and Alexander's father ")
            .append(alexBranch.name)
            .append(" were siblings (children of ")
            .append(lca.name)
            .append(")");
      } else {
        sb.append("Their lines diverge at ")
            .append(lca.name)
            .append(" — ")
            .append(person.name)
            .append(" descends through ")
            .append(personBranch.name)
            .append(", Alexander through ")
            .append(alexBranch.name);
      }
    } else {
      sb.append(person.name).append(" and Alexander share the common ancestor ").append(lca.name);
      sb.append(" (").append(dPerson).append(" generations from ").append(person.name);
      sb.append(", ").append(dAlex).append(" from Alexander)");
    }

    return sb.toString();
  }

  private List<Person> pathToAncestor(Person from, Person ancestor) {
    List<Person> path = new ArrayList<>();
    Person cur = from;
    while (cur != null) {
      path.add(cur);
      if (cur.id == ancestor.id) break;
      cur = cur.parent;
    }
    return path;
  }

  // --- Inner types ---

  public static class Person {
    public int id;
    public String name;
    public String fullEntry;
    public Integer birthYear;
    public Integer deathYear;
    public String birthPlace;
    public String section;
    public String code; // local code like "D6"
    public String parentCode; // accumulated parent code like "C6"
    public Person parent;
    public List<Person> children;
    public List<String> spouses;
    public int depth;

    public String fullCode() {
      if (parentCode != null && code != null) return parentCode + "." + code;
      if (code != null) return code;
      return "";
    }
  }

  public record SearchResult(
      int personId,
      String name,
      String fullEntry,
      String section,
      String code,
      double score,
      Integer birthYear,
      Integer deathYear) {}

  public record RelationshipResult(
      int personId,
      String personName,
      String kinshipLabel,
      String pathDescription,
      String commonAncestorName,
      int commonAncestorId,
      int stepsFromPerson,
      int stepsFromAlexander) {}
}
