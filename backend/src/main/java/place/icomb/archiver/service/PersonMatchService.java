package place.icomb.archiver.service;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import place.icomb.archiver.model.PagePersonMatch;
import place.icomb.archiver.model.PageText;
import place.icomb.archiver.repository.PagePersonMatchRepository;
import place.icomb.archiver.repository.PageRepository;
import place.icomb.archiver.repository.PageTextRepository;
import place.icomb.archiver.repository.RecordRepository;
import place.icomb.archiver.service.FamilyTreeService.Person;

@Service
public class PersonMatchService {
  private static final Logger log = LoggerFactory.getLogger(PersonMatchService.class);

  private static final int MAX_MATCHES_PER_PAGE = 10;
  private static final double MIN_SCORE = 0.3;
  private static final int CONTEXT_CHARS = 100;
  private static final int PROXIMITY_WINDOW = 10;

  private final FamilyTreeService familyTreeService;
  private final PagePersonMatchRepository matchRepo;
  private final PageTextRepository pageTextRepo;
  private final PageRepository pageRepo;
  private final RecordRepository recordRepo;

  public PersonMatchService(
      FamilyTreeService familyTreeService,
      PagePersonMatchRepository matchRepo,
      PageTextRepository pageTextRepo,
      PageRepository pageRepo,
      RecordRepository recordRepo) {
    this.familyTreeService = familyTreeService;
    this.matchRepo = matchRepo;
    this.pageTextRepo = pageTextRepo;
    this.pageRepo = pageRepo;
    this.recordRepo = recordRepo;
  }

  public List<PagePersonMatch> getPageMatches(Long pageId) {
    if (matchRepo.existsByPageId(pageId)) {
      return matchRepo.findByPageId(pageId);
    }
    return matchPage(pageId);
  }

  public List<RecordPersonMatch> getRecordMatches(Long recordId) {
    var pages = pageRepo.findByRecordId(recordId);
    if (pages.isEmpty()) return List.of();

    List<Long> pageIds = pages.stream().map(p -> p.getId()).toList();

    // Ensure all pages are computed
    for (Long pageId : pageIds) {
      if (!matchRepo.existsByPageId(pageId)) {
        matchPage(pageId);
      }
    }

    List<PagePersonMatch> allMatches = matchRepo.findByPageIdIn(pageIds);

    // Aggregate by personId
    Map<Integer, RecordPersonMatchBuilder> byPerson = new LinkedHashMap<>();
    for (PagePersonMatch m : allMatches) {
      byPerson.computeIfAbsent(m.getPersonId(), k -> new RecordPersonMatchBuilder(m)).addMatch(m);
    }

    List<RecordPersonMatch> results = new ArrayList<>();
    for (var builder : byPerson.values()) {
      results.add(builder.build());
    }

    results.sort(Comparator.comparingDouble(RecordPersonMatch::maxScore).reversed());
    return results;
  }

  public void invalidateAll() {
    matchRepo.truncateAll();
    log.info("Cleared all cached person matches");
  }

  private List<PagePersonMatch> matchPage(Long pageId) {
    String text = getBestText(pageId);
    if (text == null || text.isBlank()) {
      return List.of();
    }

    List<Person> people = familyTreeService.getAllPeople();
    if (people.isEmpty()) return List.of();

    // Look up document date range for temporal disambiguation
    Integer docYear = getDocumentYear(pageId);

    String normalizedText = familyTreeService.normalize(text);
    List<String> textTokens = familyTreeService.tokenize(normalizedText);
    if (textTokens.isEmpty()) return List.of();

    // Build inverted index: token -> persons that have this token in their name
    Map<String, List<PersonToken>> tokenToPersons = new HashMap<>();
    Set<String> titleWords = familyTreeService.getTitleWords();

    for (Person person : people) {
      List<String> nameTokens = getPersonNameTokens(person, titleWords);
      if (nameTokens.isEmpty()) continue;

      for (String token : nameTokens) {
        tokenToPersons
            .computeIfAbsent(token, k -> new ArrayList<>())
            .add(new PersonToken(person, nameTokens));
      }
    }

    // Find matches: for each text token, check fuzzy matches against person tokens
    Map<Integer, MatchCandidate> candidates = new HashMap<>();

    for (int i = 0; i < textTokens.size(); i++) {
      String textToken = textTokens.get(i);

      for (var entry : tokenToPersons.entrySet()) {
        String personToken = entry.getKey();
        if (!fuzzyMatch(textToken, personToken)) continue;

        for (PersonToken pt : entry.getValue()) {
          Person person = pt.person;
          if (candidates.containsKey(person.id)) {
            candidates.get(person.id).addTokenMatch(i, textToken);
            continue;
          }

          double score = computeProximityScore(textTokens, i, pt.allNameTokens);
          if (score >= MIN_SCORE) {
            // Apply temporal boost/penalty based on document date
            score = applyTemporalAdjustment(score, person, docYear);
            if (score >= MIN_SCORE) {
              candidates.put(person.id, new MatchCandidate(person, score, i));
            }
          }
        }
      }
    }

    // Sort by score, take top N
    List<MatchCandidate> sorted =
        candidates.values().stream()
            .sorted(Comparator.comparingDouble(MatchCandidate::score).reversed())
            .limit(MAX_MATCHES_PER_PAGE)
            .toList();

    // Store matches
    List<PagePersonMatch> stored = new ArrayList<>();
    for (MatchCandidate mc : sorted) {
      PagePersonMatch match = new PagePersonMatch();
      match.setPageId(pageId);
      match.setPersonId(mc.person.id);
      match.setPersonName(mc.person.name);
      match.setScore((float) mc.score());
      match.setContext(extractContext(text, textTokens, mc.firstTokenIndex));
      stored.add(matchRepo.save(match));
    }

    log.debug("Page {} matched {} persons", pageId, stored.size());
    return stored;
  }

  /**
   * Get the approximate year of a document from its record's date range. Returns the midpoint of
   * dateStartYear/dateEndYear, or whichever is available.
   */
  private Integer getDocumentYear(Long pageId) {
    var page = pageRepo.findById(pageId).orElse(null);
    if (page == null) return null;
    var record = recordRepo.findById(page.getRecordId()).orElse(null);
    if (record == null) return null;

    Integer start = record.getDateStartYear();
    Integer end = record.getDateEndYear();
    if (start != null && end != null) return (start + end) / 2;
    if (start != null) return start;
    return end;
  }

  /**
   * Adjusts a person's match score based on whether they were plausibly alive when the document was
   * written. A person alive during the document's date gets a boost; one clearly dead before or
   * born after gets a penalty.
   */
  private double applyTemporalAdjustment(double score, Person person, Integer docYear) {
    if (docYear == null) return score;
    Integer birth = person.birthYear;
    Integer death = person.deathYear;
    if (birth == null && death == null) return score;

    // Person was alive if: born before docYear and died after docYear (with some margin)
    int margin = 20; // allow some slack for uncertain dates
    boolean possiblyAlive = true;

    if (birth != null && birth > docYear + margin) {
      possiblyAlive = false; // born well after the document
    }
    if (death != null && death < docYear - margin) {
      possiblyAlive = false; // died well before the document
    }

    if (possiblyAlive) {
      // Boost: person was plausibly alive at document time
      return Math.min(1.0, score * 1.15);
    } else {
      // Penalty: person was likely not alive — reduce score significantly
      return score * 0.6;
    }
  }

  private String getBestText(Long pageId) {
    List<PageText> texts = pageTextRepo.findByPageId(pageId);
    if (texts.isEmpty()) return null;
    // Prefer English translation, fall back to raw text
    for (PageText pt : texts) {
      if (pt.getTextEn() != null && !pt.getTextEn().isBlank()) return pt.getTextEn();
    }
    for (PageText pt : texts) {
      if (pt.getTextRaw() != null && !pt.getTextRaw().isBlank()) return pt.getTextRaw();
    }
    return null;
  }

  private List<String> getPersonNameTokens(Person person, Set<String> titleWords) {
    List<String> tokens = new ArrayList<>();
    String normalized = familyTreeService.normalize(person.name);
    for (String t : normalized.split("[\\s,;.()]+")) {
      if (!t.isEmpty() && t.length() > 1 && !titleWords.contains(t)) {
        tokens.add(t);
      }
    }
    // Also add spouse names for matching
    if (person.spouses != null) {
      for (String spouse : person.spouses) {
        String normSpouse = familyTreeService.normalize(spouse);
        for (String t : normSpouse.split("[\\s,;.()]+")) {
          if (!t.isEmpty() && t.length() > 2 && !titleWords.contains(t)) {
            tokens.add(t);
          }
        }
      }
    }
    return tokens;
  }

  private boolean fuzzyMatch(String textToken, String personToken) {
    if (textToken.equals(personToken)) return true;
    if (textToken.contains(personToken) || personToken.contains(textToken)) return true;
    if (textToken.length() >= 3
        && personToken.length() >= 3
        && familyTreeService.levenshtein(textToken, personToken) <= 2) {
      return true;
    }
    return false;
  }

  private double computeProximityScore(
      List<String> textTokens, int matchIndex, List<String> nameTokens) {
    if (nameTokens.size() <= 1) {
      // Single-token names are risky for false positives — require longer token
      String token = nameTokens.getFirst();
      return token.length() >= 5 ? 0.35 : 0.0;
    }

    int matched = 0;
    int windowStart = Math.max(0, matchIndex - PROXIMITY_WINDOW);
    int windowEnd = Math.min(textTokens.size(), matchIndex + PROXIMITY_WINDOW + 1);

    for (String nameToken : nameTokens) {
      for (int j = windowStart; j < windowEnd; j++) {
        if (fuzzyMatch(textTokens.get(j), nameToken)) {
          matched++;
          break;
        }
      }
    }

    return (double) matched / nameTokens.size();
  }

  private String extractContext(String originalText, List<String> tokens, int tokenIndex) {
    if (tokenIndex < 0 || tokenIndex >= tokens.size()) return null;
    String matchToken = tokens.get(tokenIndex);

    // Find the match position in original text (case-insensitive)
    String lowerText = originalText.toLowerCase();
    int pos = lowerText.indexOf(matchToken);
    if (pos < 0) return null;

    int start = Math.max(0, pos - CONTEXT_CHARS / 2);
    int end = Math.min(originalText.length(), pos + matchToken.length() + CONTEXT_CHARS / 2);

    String snippet = originalText.substring(start, end).replaceAll("\\s+", " ").trim();
    if (start > 0) snippet = "..." + snippet;
    if (end < originalText.length()) snippet = snippet + "...";
    return snippet;
  }

  // --- Inner types ---

  private record PersonToken(Person person, List<String> allNameTokens) {}

  private static class MatchCandidate {
    final Person person;
    double bestScore;
    int firstTokenIndex;

    MatchCandidate(Person person, double score, int tokenIndex) {
      this.person = person;
      this.bestScore = score;
      this.firstTokenIndex = tokenIndex;
    }

    void addTokenMatch(int tokenIndex, String token) {
      // Keep first occurrence for context extraction
    }

    double score() {
      return bestScore;
    }
  }

  public record RecordPersonMatch(
      int personId, String personName, double maxScore, int pageCount) {}

  private static class RecordPersonMatchBuilder {
    int personId;
    String personName;
    double maxScore;
    Set<Long> pageIds = new HashSet<>();

    RecordPersonMatchBuilder(PagePersonMatch first) {
      this.personId = first.getPersonId();
      this.personName = first.getPersonName();
      this.maxScore = first.getScore();
      this.pageIds.add(first.getPageId());
    }

    void addMatch(PagePersonMatch m) {
      pageIds.add(m.getPageId());
      maxScore = Math.max(maxScore, m.getScore());
    }

    RecordPersonMatch build() {
      return new RecordPersonMatch(personId, personName, maxScore, pageIds.size());
    }
  }
}
