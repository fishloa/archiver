package place.icomb.archiver.service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import place.icomb.archiver.genealogy.legacy.LegacyTreeParser;

/**
 * The family tree as the rest of the archive reads it: search, kinship and people, over the
 * genealogy tables. {@code GenealogyService} is the only writer and calls {@link #invalidate()}
 * after each commit; the next read rebuilds the tree.
 *
 * <p>"Tree members" are the people the in-memory tree held (a legacy section) and anyone descended
 * from them by birth. Search and {@link #getAllPeople()} cover members only, so person matching and
 * search answer exactly as they did; spouses are reachable by id and by {@code search(q, n, true)}.
 */
@Service
public class FamilyTreeService {

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

  private static final String ALEXANDER = "Alexander Friedrich Josef Paul Maria";
  private static final String FAMILY_SURNAME = "Czernin";

  private static final Map<String, String> EVENT_WORDS =
      Map.ofEntries(
          Map.entry("BIRT", "birth"),
          Map.entry("CHR", "christening"),
          Map.entry("BAPM", "baptism"),
          Map.entry("DEAT", "death"),
          Map.entry("BURI", "burial"),
          Map.entry("MARR", "marriage"),
          Map.entry("DIV", "divorce"),
          Map.entry("ADOP", "adoption"),
          Map.entry("OCCU", "occupation"),
          Map.entry("TITL", "title"),
          Map.entry("RESI", "residence"),
          Map.entry("NATU", "naturalisation"),
          Map.entry("EMIG", "emigration"),
          Map.entry("IMMI", "immigration"),
          Map.entry("EDUC", "education"),
          Map.entry("EVEN", "event"));

  private final JdbcTemplate jdbc;
  private volatile Snapshot snapshot;
  private volatile long snapshotGen;
  private final AtomicLong generation = new AtomicLong();
  volatile java.util.function.Consumer<Object> onLoadedHook;

  private record Snapshot(
      List<Person> members,
      Map<Integer, Person> byId,
      Map<Integer, Integer> mergedInto,
      Person alexander,
      Map<Integer, List<Integer>> birthParents,
      Map<Integer, List<Integer>> partners) {}

  @Autowired
  public FamilyTreeService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private FamilyTreeService(Snapshot fixed) {
    this.jdbc = null;
    this.snapshot = fixed;
  }

  /** A tree over a fixed list of people, for tests that need no database. */
  static FamilyTreeService forPeople(List<Person> people) {
    Map<Integer, Person> byId = new LinkedHashMap<>();
    Map<Integer, List<Integer>> parents = new HashMap<>();
    Person alexander = null;
    for (Person p : people) {
      byId.put(p.id, p);
      if (p.parent != null) parents.put(p.id, List.of(p.parent.id));
      if (alexander == null && p.fullEntry != null && p.fullEntry.contains(ALEXANDER)) {
        alexander = p;
      }
    }
    return new FamilyTreeService(
        new Snapshot(List.copyOf(people), byId, Map.of(), alexander, parents, Map.of()));
  }

  /** Drops the cached tree; the next read loads it again. */
  public void invalidate() {
    if (jdbc != null) {
      synchronized (this) {
        generation.incrementAndGet();
        snapshot = null;
        snapshotGen = 0L;
      }
    }
  }

  private Snapshot tree() {
    while (true) {
      long g = generation.get();
      Snapshot cached = snapshot;
      if (cached != null && snapshotGen == g) {
        if (generation.get() != g) {
          continue;
        }
        return cached;
      }
      synchronized (this) {
        cached = snapshot;
        if (cached == null || snapshotGen != g) {
          if (generation.get() != g) {
            continue;
          }
          Snapshot loaded = load();
          if (onLoadedHook != null) {
            onLoadedHook.accept(loaded);
          }
          if (generation.get() == g && (snapshot == null || snapshotGen < g)) {
            snapshot = loaded;
            snapshotGen = g;
          }
          cached =
              (generation.get() == g && snapshot != null && snapshotGen == g) ? snapshot : loaded;
        }
      }
      if (generation.get() != g) {
        continue;
      }
      return cached;
    }
  }

  /** Package-private test seam: forces a load and returns the snapshot as an opaque value. */
  Object treeSnapshot() {
    return tree();
  }

  // --- Public API ---

  public List<SearchResult> search(String query, int limit) {
    return search(query, limit, false);
  }

  /** With {@code everyone}, spouses and other people outside the tree are searched too. */
  public List<SearchResult> search(String query, int limit, boolean everyone) {
    if (query == null || query.isBlank()) return List.of();
    List<String> qTokens = tokenize(normalize(query));
    if (qTokens.isEmpty()) return List.of();

    Snapshot t = tree();
    Collection<Person> pool = everyone ? t.byId().values() : t.members();
    List<SearchResult> results = new ArrayList<>();
    for (Person p : pool) {
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
    Person alexander = tree().alexander();
    if (alexander == null) return null;
    return relate(personId, alexander.id);
  }

  public RelationshipResult relate(int personId, int referencePersonId) {
    Snapshot t = tree();
    Person person = resolve(t, personId);
    Person ref = resolve(t, referencePersonId);
    if (person == null || ref == null) return null;
    if (person.id == ref.id) {
      String desc =
          ref.id == (t.alexander() != null ? t.alexander().id : -1)
              ? "Alexander Friedrich Josef Paul Maria Czernin, nicknamed Lucki/Papi"
              : "This is " + ref.name;
      return new RelationshipResult(
          person.id,
          person.name,
          "This is " + ref.name,
          desc,
          person.name,
          person.id,
          0,
          0,
          ref.name);
    }
    RelationshipResult blood = blood(t, person, ref);
    if (blood != null) return blood;
    return byMarriage(t, person, ref);
  }

  /** A person merged into another resolves to the one they were merged into. */
  public Person getPerson(int id) {
    return resolve(tree(), id);
  }

  /**
   * A person's events in date order, their couples' marriages and divorces included. Each keeps the
   * keys the text-file tree gave ({@code type}, {@code text}, {@code year}) and adds the stored
   * fact: {@code id}, {@code tag}, {@code date}, {@code dateFrom}, {@code dateTo}, {@code place},
   * {@code placeId}, {@code description}, {@code coupleId}, {@code spouseId}, {@code citations}.
   */
  public List<Map<String, Object>> getLifeEvents(Person p) {
    if (jdbc == null) return List.of();
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            SELECT e.id, e.type, e.date_text, e.date_from, e.date_to, e.place_id,
                   pl.name AS place, e.description, e.couple_id, e.sort_order,
                   CASE WHEN c.person1_id = ? THEN c.person2_id ELSE c.person1_id END AS spouse_id,
                   EXISTS (SELECT 1 FROM gen_event d
                           WHERE d.couple_id = e.couple_id AND d.type = 'DIV') AS divorced
            FROM gen_event e
            LEFT JOIN gen_place pl ON pl.id = e.place_id
            LEFT JOIN gen_couple c ON c.id = e.couple_id
            WHERE e.person_id = ? OR c.person1_id = ? OR c.person2_id = ?
            """,
            p.id,
            p.id,
            p.id,
            p.id);
    Map<Long, List<Map<String, Object>>> citations = citationsByEvent(rows);
    List<Map<String, Object>> events = new ArrayList<>();
    for (Map<String, Object> r : rows) {
      String tag = (String) r.get("type");
      Integer spouseId = r.get("couple_id") == null ? null : (Integer) r.get("spouse_id");
      Person spouse = spouseId == null ? null : getPerson(spouseId);
      String word = EVENT_WORDS.getOrDefault(tag, "event");
      if (tag.equals("MARR") && Boolean.TRUE.equals(r.get("divorced"))) {
        word = "marriage & divorce";
      }
      LocalDate from = toDate(r.get("date_from"));
      LocalDate to = toDate(r.get("date_to"));
      Integer year = yearOf(from, to);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("type", word);
      m.put("text", text(r, spouse));
      m.put("year", year);
      m.put("id", ((Number) r.get("id")).longValue());
      m.put("tag", tag);
      m.put("date", r.get("date_text"));
      m.put("dateFrom", from == null ? null : from.toString());
      m.put("dateTo", to == null ? null : to.toString());
      m.put("place", r.get("place"));
      m.put("placeId", r.get("place_id"));
      m.put("description", r.get("description"));
      m.put("coupleId", r.get("couple_id"));
      m.put("spouseId", spouseId);
      m.put("sortOrder", r.get("sort_order"));
      m.put("citations", citations.getOrDefault(((Number) r.get("id")).longValue(), List.of()));
      events.add(m);
    }
    events.sort(
        Comparator.<Map<String, Object>>comparingInt(
                e -> e.get("year") instanceof Integer y ? y : 9999)
            .thenComparingInt(e -> (Integer) e.get("sortOrder"))
            .thenComparingLong(e -> (Long) e.get("id")));
    events.forEach(e -> e.remove("sortOrder"));
    return events;
  }

  public List<Person> getAllPeople() {
    return tree().members();
  }

  Set<String> getTitleWords() {
    return TITLE_WORDS;
  }

  public int getPersonCount() {
    return tree().members().size();
  }

  // --- Loading ---

  private Snapshot load() {
    Map<Integer, Person> all = new LinkedHashMap<>();
    Map<Integer, Integer> mergedInto = new HashMap<>();
    jdbc.query(
        """
        SELECT p.id, p.legacy_entry, p.legacy_section, p.legacy_code, p.legacy_depth,
               p.merged_into, n.given, n.surname
        FROM gen_person p
        LEFT JOIN gen_name n ON n.person_id = p.id AND n.is_primary
        ORDER BY p.id
        """,
        rs -> {
          int id = rs.getInt("id");
          int into = rs.getInt("merged_into");
          if (!rs.wasNull()) {
            mergedInto.put(id, into);
            return;
          }
          Person p = new Person();
          p.id = id;
          String given = rs.getString("given");
          String surname = rs.getString("surname");
          p.name = displayName(given, surname);
          p.surname = surname;
          String entry = rs.getString("legacy_entry");
          p.matchingName =
              entry != null
                  ? LegacyTreeParser.legacyName(entry)
                  : FAMILY_SURNAME.equals(surname) ? displayName(given, null) : p.name;
          p.fullEntry = entry != null ? entry : p.name;
          p.section = rs.getString("legacy_section");
          p.code = rs.getString("legacy_code");
          p.depth = rs.getInt("legacy_depth");
          p.children = new ArrayList<>();
          p.spouses = new ArrayList<>();
          all.put(id, p);
        });

    Set<Integer> born = new HashSet<>();
    Set<Integer> died = new HashSet<>();
    jdbc.query(
        """
        SELECT e.person_id, e.type, e.date_from, e.date_to, pl.name AS place
        FROM gen_event e LEFT JOIN gen_place pl ON pl.id = e.place_id
        WHERE e.person_id IS NOT NULL AND e.type IN ('BIRT', 'DEAT')
        ORDER BY e.sort_order, e.id
        """,
        rs -> {
          Person p = all.get(rs.getInt("person_id"));
          if (p == null) return;
          Integer year =
              yearOf(
                  rs.getObject("date_from", LocalDate.class),
                  rs.getObject("date_to", LocalDate.class));
          if (rs.getString("type").equals("BIRT")) {
            if (born.add(p.id)) {
              p.birthYear = year;
              p.birthPlace = rs.getString("place");
            }
          } else if (died.add(p.id)) {
            p.deathYear = year;
          }
        });

    Map<Integer, List<Integer>> birthParents = new HashMap<>();
    jdbc.query(
        "SELECT parent_id, child_id FROM gen_parent_child WHERE type = 'birth'"
            + " ORDER BY child_id, parent_id",
        rs -> {
          int parent = rs.getInt("parent_id");
          int child = rs.getInt("child_id");
          if (all.containsKey(parent) && all.containsKey(child)) {
            birthParents.computeIfAbsent(child, k -> new ArrayList<>()).add(parent);
          }
        });
    for (var e : birthParents.entrySet()) {
      Person child = all.get(e.getKey());
      Person parent = all.get(e.getValue().get(0));
      child.parent = parent;
    }
    for (Person p : all.values()) {
      for (int parentId : birthParents.getOrDefault(p.id, List.of())) {
        all.get(parentId).children.add(p);
      }
    }

    Map<Integer, List<Integer>> partners = new HashMap<>();
    jdbc.query(
        "SELECT person1_id, person2_id FROM gen_couple ORDER BY sort_order, id",
        rs -> {
          int a = rs.getInt("person1_id");
          int b = rs.getInt("person2_id");
          if (all.containsKey(a) && all.containsKey(b)) {
            partners.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
            partners.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
            all.get(a).spouses.add(all.get(b).name);
            all.get(b).spouses.add(all.get(a).name);
          }
        });

    Set<Integer> members = new LinkedHashSet<>();
    Deque<Person> queue = new ArrayDeque<>();
    for (Person p : all.values()) {
      if (p.section != null) {
        members.add(p.id);
        queue.add(p);
      }
    }
    while (!queue.isEmpty()) {
      for (Person child : queue.poll().children) {
        if (members.add(child.id)) queue.add(child);
      }
    }
    List<Person> memberList = new ArrayList<>();
    Person alexander = null;
    for (Person p : all.values()) {
      if (members.contains(p.id)) memberList.add(p);
      if (alexander == null && p.fullEntry.contains(ALEXANDER)) alexander = p;
    }
    return new Snapshot(
        List.copyOf(memberList),
        Collections.unmodifiableMap(all),
        Map.copyOf(mergedInto),
        alexander,
        birthParents,
        partners);
  }

  private static String displayName(String given, String surname) {
    if (given == null) return surname == null ? "" : surname;
    return surname == null ? given : given + " " + surname;
  }

  private Map<Long, List<Map<String, Object>>> citationsByEvent(List<Map<String, Object>> events) {
    Map<Long, List<Map<String, Object>>> out = new HashMap<>();
    if (events.isEmpty()) return out;
    List<Long> ids = events.stream().map(e -> ((Number) e.get("id")).longValue()).toList();
    String in = String.join(",", Collections.nCopies(ids.size(), "?"));
    jdbc.query(
        "SELECT c.id, c.event_id, c.source_id, s.title, c.page_id, c.detail, c.quality"
            + " FROM gen_citation c JOIN gen_source s ON s.id = c.source_id"
            + " WHERE c.event_id IN ("
            + in
            + ") ORDER BY c.id",
        rs -> {
          Map<String, Object> c = new LinkedHashMap<>();
          c.put("id", rs.getLong("id"));
          c.put("sourceId", rs.getLong("source_id"));
          c.put("sourceTitle", rs.getString("title"));
          long page = rs.getLong("page_id");
          c.put("pageId", rs.wasNull() ? null : page);
          c.put("detail", rs.getString("detail"));
          int q = rs.getInt("quality");
          c.put("quality", rs.wasNull() ? null : q);
          out.computeIfAbsent(rs.getLong("event_id"), k -> new ArrayList<>()).add(c);
        },
        ids.toArray());
    return out;
  }

  private static String text(Map<String, Object> r, Person spouse) {
    List<String> parts = new ArrayList<>();
    for (String key : List.of("description", "date_text", "place")) {
      if (r.get(key) != null) parts.add((String) r.get(key));
    }
    if (spouse != null && "MARR".equals(r.get("type"))) parts.add(spouse.name);
    return String.join(", ", parts);
  }

  private static Integer yearOf(LocalDate from, LocalDate to) {
    if (from != null) return from.getYear();
    if (to != null) return to.getYear();
    return null;
  }

  private static LocalDate toDate(Object value) {
    if (value == null) return null;
    if (value instanceof java.sql.Date d) return d.toLocalDate();
    return (LocalDate) value;
  }

  private static Person resolve(Snapshot t, int id) {
    Integer into = t.mergedInto().get(id);
    for (int hops = 0; into != null && hops < 32; hops++) {
      id = into;
      into = t.mergedInto().get(id);
    }
    return t.byId().get(id);
  }

  // --- Fuzzy search ---

  /**
   * Forenames score as they always did; a query word that matches none of them may still match the
   * surname exactly, so "Czernin Rudolf" finds the Rudolfs.
   */
  private double score(List<String> queryTokens, Person person) {
    List<String> nameTokens = tokenize(normalize(person.matchName()));
    List<String> surnameTokens =
        person.surname == null ? List.of() : tokenize(normalize(person.surname));
    List<String> entryTokens = tokenize(normalize(person.fullEntry));

    double exactMatches = 0, substringMatches = 0, fuzzyMatches = 0;

    for (String qt : queryTokens) {
      boolean matched = false;
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

      for (String st : surnameTokens) {
        if (st.equals(qt)) {
          exactMatches++;
          matched = true;
          break;
        }
      }
      if (matched) continue;

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
    return Arrays.stream(s.split("[\\s,;.()/]+"))
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

  /** Distances from {@code from} up its birth parents, lowest parent id explored first. */
  private static Map<Integer, Integer> ancestors(Snapshot t, int from, Map<Integer, Integer> via) {
    Map<Integer, Integer> dist = new LinkedHashMap<>();
    Deque<Integer> queue = new ArrayDeque<>();
    dist.put(from, 0);
    queue.add(from);
    while (!queue.isEmpty()) {
      int cur = queue.poll();
      for (int parent : t.birthParents().getOrDefault(cur, List.of())) {
        if (!dist.containsKey(parent)) {
          dist.put(parent, dist.get(cur) + 1);
          via.put(parent, cur);
          queue.add(parent);
        }
      }
    }
    return dist;
  }

  private RelationshipResult blood(Snapshot t, Person person, Person ref) {
    Map<Integer, Integer> viaPerson = new HashMap<>();
    Map<Integer, Integer> viaRef = new HashMap<>();
    Map<Integer, Integer> up = ancestors(t, person.id, viaPerson);
    Map<Integer, Integer> refUp = ancestors(t, ref.id, viaRef);
    Integer best = null;
    for (var e : up.entrySet()) {
      Integer dB = refUp.get(e.getKey());
      if (dB == null) continue;
      if (best == null) {
        best = e.getKey();
        continue;
      }
      int dA = e.getValue();
      int bA = up.get(best);
      int bB = refUp.get(best);
      if (dA + dB < bA + bB
          || (dA + dB == bA + bB && dB < bB)
          || (dA + dB == bA + bB && dB == bB && e.getKey() < best)) {
        best = e.getKey();
      }
    }
    if (best == null) return null;
    Person lca = t.byId().get(best);
    int dA = up.get(best);
    int dB = refUp.get(best);
    return new RelationshipResult(
        person.id,
        person.name,
        kinshipLabel(dA, dB),
        buildPathDescription(
            person, ref, lca, dA, dB, path(t, viaPerson, best), path(t, viaRef, best)),
        lca.name,
        lca.id,
        dA,
        dB,
        ref.name);
  }

  /** The line from the ancestor back down to where the walk started, the ancestor first. */
  private static List<Person> path(Snapshot t, Map<Integer, Integer> via, int ancestor) {
    List<Person> down = new ArrayList<>();
    Integer cur = ancestor;
    while (cur != null) {
      down.add(t.byId().get(cur));
      cur = via.get(cur);
    }
    return down;
  }

  private RelationshipResult byMarriage(Snapshot t, Person person, Person ref) {
    List<Integer> personPartners = t.partners().getOrDefault(person.id, List.of());
    if (personPartners.contains(ref.id)) {
      return new RelationshipResult(
          person.id,
          person.name,
          "spouse",
          person.name + " is the spouse of " + ref.name,
          null,
          0,
          0,
          0,
          ref.name);
    }
    RelationshipResult best = null;
    String bestLabel = null;
    String bestPath = null;
    for (int x : personPartners) {
      RelationshipResult r = blood(t, t.byId().get(x), ref);
      if (r != null && better(r, best)) {
        best = r;
        bestLabel = spouseOf(r.kinshipLabel());
        bestPath =
            person.name + " is married to " + t.byId().get(x).name + "; " + r.pathDescription();
      }
    }
    for (int y : t.partners().getOrDefault(ref.id, List.of())) {
      RelationshipResult r = blood(t, person, t.byId().get(y));
      if (r != null && better(r, best)) {
        best = r;
        bestLabel = ofSpouse(r.kinshipLabel());
        bestPath = ref.name + " is married to " + t.byId().get(y).name + "; " + r.pathDescription();
      }
    }
    if (best == null) return null;
    return new RelationshipResult(
        person.id,
        person.name,
        bestLabel,
        bestPath,
        best.commonAncestorName(),
        best.commonAncestorId(),
        best.stepsFromPerson(),
        best.stepsFromRef(),
        ref.name);
  }

  private static boolean better(RelationshipResult r, RelationshipResult best) {
    if (best == null) return true;
    return r.stepsFromPerson() + r.stepsFromRef() < best.stepsFromPerson() + best.stepsFromRef();
  }

  /** The person is married to someone who is the reference's {@code label}. */
  static String spouseOf(String label) {
    return switch (label) {
      case "sibling" -> "sibling-in-law";
      case "child" -> "child-in-law";
      case "parent" -> "step-parent";
      default -> "spouse of " + label;
    };
  }

  /** The reference is married to someone whose {@code label} the person is. */
  static String ofSpouse(String label) {
    return switch (label) {
      case "sibling" -> "sibling-in-law";
      case "parent" -> "parent-in-law";
      case "child" -> "step-child";
      default -> label + " of spouse";
    };
  }

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
      Person person,
      Person ref,
      Person lca,
      int dPerson,
      int dRef,
      List<Person> personLine,
      List<Person> refLine) {
    var sb = new StringBuilder();
    String refName = ref.name;

    if (dPerson == 1 && dRef == 1) {
      sb.append(person.name)
          .append(" and ")
          .append(refName)
          .append(" are siblings (children of ")
          .append(lca.name)
          .append(")");
    } else if (dPerson >= 2 && dRef >= 2) {
      Person personBranch = personLine.size() >= 2 ? personLine.get(1) : person;
      Person refBranch = refLine.size() >= 2 ? refLine.get(1) : ref;

      if (dPerson == 2 && dRef == 2) {
        sb.append(person.name)
            .append("'s father ")
            .append(personBranch.name)
            .append(" and ")
            .append(refName)
            .append("'s father ")
            .append(refBranch.name)
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
            .append(", ")
            .append(refName)
            .append(" through ")
            .append(refBranch.name);
      }
    } else {
      sb.append(person.name)
          .append(" and ")
          .append(refName)
          .append(" share the common ancestor ")
          .append(lca.name);
      sb.append(" (").append(dPerson).append(" generations from ").append(person.name);
      sb.append(", ").append(dRef).append(" from ").append(refName).append(")");
    }

    return sb.toString();
  }

  // --- Inner types ---

  public static class Person {
    public int id;

    /** Given names and surname, as shown everywhere. */
    public String name;

    public String surname;

    /** What person matching scores on; see {@link #matchName()}. */
    public String matchingName;

    public String fullEntry;
    public Integer birthYear;
    public Integer deathYear;
    public String birthPlace;
    public String section;
    public String code;
    public String parentCode;
    public Person parent;
    public List<Person> children;
    public List<String> spouses;
    public int depth;

    /**
     * The name person matching scores on: the legacy name for people imported from the text file
     * (their forenames, never the Czernin surname the tree now adds); the given names only when the
     * surname is Czernin, otherwise the display name; and {@link #name} when nothing else is set,
     * as for people built by hand in tests.
     */
    public String matchName() {
      return matchingName != null ? matchingName : name;
    }

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
      int stepsFromRef,
      String refPersonName) {}
}
