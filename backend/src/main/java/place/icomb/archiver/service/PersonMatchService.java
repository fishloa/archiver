package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
  private static final int MAX_LLM_CANDIDATES = 20;
  private static final double MIN_SCORE = 0.3;
  private static final int CONTEXT_CHARS = 100;
  private static final int PROXIMITY_WINDOW = 3;
  private static final String FAMILY_SURNAME = "czernin";

  private final FamilyTreeService familyTreeService;
  private final PagePersonMatchRepository matchRepo;
  private final PageTextRepository pageTextRepo;
  private final PageRepository pageRepo;
  private final RecordRepository recordRepo;
  private final String openaiApiKey;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public PersonMatchService(
      FamilyTreeService familyTreeService,
      PagePersonMatchRepository matchRepo,
      PageTextRepository pageTextRepo,
      PageRepository pageRepo,
      RecordRepository recordRepo,
      @Value("${archiver.openai.api-key:}") String openaiApiKey) {
    this.familyTreeService = familyTreeService;
    this.matchRepo = matchRepo;
    this.pageTextRepo = pageTextRepo;
    this.pageRepo = pageRepo;
    this.recordRepo = recordRepo;
    this.openaiApiKey = openaiApiKey;
    this.httpClient = HttpClient.newHttpClient();
    this.objectMapper = new ObjectMapper();
  }

  /** Constructor for unit testing — no repos, no API key. */
  PersonMatchService(FamilyTreeService familyTreeService) {
    this(familyTreeService, null, null, null, null, "");
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

    Integer docYear = getDocumentYear(pageId);

    // Stage 1: Heuristic pre-filter to find candidates
    List<MatchResult> candidates = computeMatches(text, people, docYear);
    if (candidates.isEmpty()) return List.of();

    // Stage 2: LLM re-ranking (falls back to heuristic if unavailable)
    List<MatchResult> finalResults = llmRerank(text, candidates, docYear);
    if (finalResults == null) {
      finalResults = candidates.stream().limit(MAX_MATCHES_PER_PAGE).toList();
    }

    // Store matches
    List<PagePersonMatch> stored = new ArrayList<>();
    for (MatchResult mr : finalResults) {
      PagePersonMatch match = new PagePersonMatch();
      match.setPageId(pageId);
      match.setPersonId(mr.personId);
      match.setPersonName(mr.personName);
      match.setScore((float) Math.min(1.0, mr.score));
      match.setContext(mr.context);
      stored.add(matchRepo.save(match));
    }

    log.debug("Page {} matched {} persons", pageId, stored.size());
    return stored;
  }

  // --- Core matching algorithm (package-private for testing) ---

  record MatchResult(int personId, String personName, double score, String context) {}

  List<MatchResult> computeMatches(String text, List<Person> people, Integer docYear) {
    String normalizedText = familyTreeService.normalize(text);
    List<String> textTokens = familyTreeService.tokenize(normalizedText);
    if (textTokens.isEmpty()) return List.of();

    // Build inverted index: token -> persons that have this token in their name
    Map<String, List<PersonToken>> tokenToPersons = new HashMap<>();
    Set<String> titleWords = familyTreeService.getTitleWords();

    for (Person person : people) {
      List<String> nameTokens = getPersonNameTokens(person, titleWords);
      if (nameTokens.isEmpty()) continue;

      List<String> givenNames = getOrderedGivenNames(person, titleWords);

      for (String token : nameTokens) {
        tokenToPersons
            .computeIfAbsent(token, k -> new ArrayList<>())
            .add(new PersonToken(person, nameTokens, givenNames));
      }
    }

    // Find matches: for each text token, check fuzzy matches against person tokens.
    // Re-score at each match position to find the best proximity score per person.
    Map<Integer, MatchCandidate> candidates = new HashMap<>();

    for (int i = 0; i < textTokens.size(); i++) {
      String textToken = textTokens.get(i);

      for (var entry : tokenToPersons.entrySet()) {
        String personToken = entry.getKey();
        if (!fuzzyMatch(textToken, personToken)) continue;

        for (PersonToken pt : entry.getValue()) {
          Person person = pt.person;
          double score = computeProximityScore(textTokens, i, pt.allNameTokens);
          if (score < MIN_SCORE) continue;

          // Apply graduated name-position bonus: first given name gets highest boost
          double nameBonus = computeNamePositionBonus(textTokens, i, pt.orderedGivenNames);
          score *= nameBonus;

          var existing = candidates.get(person.id);
          if (existing == null || score > existing.bestScore) {
            candidates.put(person.id, new MatchCandidate(person, score, i));
          }
        }
      }
    }

    // Apply temporal adjustment after finding best scores
    candidates
        .values()
        .removeIf(
            mc -> {
              mc.bestScore = applyTemporalAdjustment(mc.bestScore, mc.person, docYear);
              return mc.bestScore < MIN_SCORE;
            });

    // Sort by score, return top candidates for LLM input
    return candidates.values().stream()
        .sorted(Comparator.comparingDouble((MatchCandidate mc) -> mc.bestScore).reversed())
        .limit(MAX_LLM_CANDIDATES)
        .map(
            mc ->
                new MatchResult(
                    mc.person.id,
                    mc.person.name,
                    mc.bestScore,
                    extractContext(
                        text,
                        familyTreeService.tokenize(familyTreeService.normalize(text)),
                        mc.bestTokenIndex)))
        .toList();
  }

  // --- LLM re-ranking ---

  private List<MatchResult> llmRerank(String text, List<MatchResult> candidates, Integer docYear) {
    if (openaiApiKey == null || openaiApiKey.isBlank()) return null;
    if (candidates.isEmpty()) return null;

    try {
      StringBuilder prompt = new StringBuilder();
      prompt.append(
          "You are identifying people mentioned in a historical document from the Czernin noble "
              + "family archive (Bohemian/Austrian aristocracy).\n\n");
      prompt.append("Document text:\n\"\"\"\n");
      prompt.append(text, 0, Math.min(text.length(), 4000));
      prompt.append("\n\"\"\"\n\n");
      if (docYear != null) {
        prompt.append("Approximate document year: ").append(docYear).append("\n\n");
      }
      prompt.append("Candidate family members (from heuristic pre-filter):\n");
      for (MatchResult mr : candidates) {
        Person p = familyTreeService.getPerson(mr.personId);
        prompt.append("- ID ").append(mr.personId).append(": ").append(mr.personName);
        if (p != null) {
          if (p.birthYear != null) prompt.append(", born ").append(p.birthYear);
          if (p.deathYear != null) prompt.append(", died ").append(p.deathYear);
          if (p.birthPlace != null && !p.birthPlace.isBlank())
            prompt.append(", birthplace: ").append(p.birthPlace);
        }
        prompt.append(" (heuristic score: ").append(String.format("%.2f", mr.score)).append(")\n");
      }
      prompt.append(
          "\nIdentify which candidates are ACTUALLY referenced in the document text. "
              + "Consider name matches, titles (Graf/Count), locations, dates, and context. "
              + "Only include people with clear textual evidence — do not guess.\n\n"
              + "Respond with JSON only:\n"
              + "{\"matches\": [{\"personId\": N, \"score\": 0.0-1.0, \"evidence\": \"brief quote\"}]}\n"
              + "If nobody clearly matches, respond: {\"matches\": []}");

      String jsonBody =
          objectMapper.writeValueAsString(
              Map.of(
                  "model",
                  "gpt-4o-mini",
                  "messages",
                  List.of(
                      Map.of(
                          "role",
                          "system",
                          "content",
                          "You are a genealogy research assistant. "
                              + "Respond only with valid JSON, no markdown."),
                      Map.of("role", "user", "content", prompt.toString())),
                  "temperature",
                  0.1,
                  "max_tokens",
                  500));

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://api.openai.com/v1/chat/completions"))
              .header("Authorization", "Bearer " + openaiApiKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        log.warn("OpenAI API error: {} {}", response.statusCode(), response.body());
        return null;
      }

      var tree = objectMapper.readTree(response.body());
      String content = tree.get("choices").get(0).get("message").get("content").asText();

      // Strip markdown code fences if present
      content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
      var matchesNode = objectMapper.readTree(content).get("matches");

      if (matchesNode == null || !matchesNode.isArray()) return null;

      // Build lookup from candidates
      Map<Integer, MatchResult> candidateMap = new HashMap<>();
      for (MatchResult mr : candidates) {
        candidateMap.put(mr.personId, mr);
      }

      List<MatchResult> results = new ArrayList<>();
      for (var node : matchesNode) {
        int personId = node.get("personId").asInt();
        double score = node.get("score").asDouble();
        var candidate = candidateMap.get(personId);
        if (candidate == null || score < 0.1) continue;

        results.add(new MatchResult(personId, candidate.personName, score, candidate.context));
      }

      // Sort by score descending, limit to MAX_MATCHES_PER_PAGE
      results.sort(Comparator.comparingDouble(MatchResult::score).reversed());
      if (results.size() > MAX_MATCHES_PER_PAGE) {
        results = results.subList(0, MAX_MATCHES_PER_PAGE);
      }

      log.info(
          "Page LLM re-ranked: {} matches from {} candidates", results.size(), candidates.size());
      return results;

    } catch (Exception e) {
      log.warn("LLM re-ranking failed, falling back to heuristic: {}", e.getMessage());
      return null;
    }
  }

  // --- Heuristic helper methods ---

  private static final Pattern YEAR_IN_TEXT = Pattern.compile("\\b(1[0-9]{3}|20[0-9]{2})\\b");

  /**
   * Get the approximate year of a document from its record's date range. Returns the midpoint of
   * dateStartYear/dateEndYear, or falls back to parsing years from dateRangeText.
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
    if (end != null) return end;

    // Fallback: parse years from dateRangeText (e.g. "1939, 1942 - 1943")
    String dateText = record.getDateRangeText();
    if (dateText != null && !dateText.isBlank()) {
      Matcher m = YEAR_IN_TEXT.matcher(dateText);
      int minYear = Integer.MAX_VALUE, maxYear = Integer.MIN_VALUE;
      while (m.find()) {
        int y = Integer.parseInt(m.group(1));
        minYear = Math.min(minYear, y);
        maxYear = Math.max(maxYear, y);
      }
      if (minYear != Integer.MAX_VALUE) {
        return (minYear + maxYear) / 2;
      }
    }
    return null;
  }

  /**
   * Adjusts a person's match score based on whether they were plausibly alive when the document was
   * written. Eliminates people who could not have been alive; boosts those who were.
   */
  double applyTemporalAdjustment(double score, Person person, Integer docYear) {
    if (docYear == null) return score;
    Integer birth = person.birthYear;
    Integer death = person.deathYear;
    if (birth == null && death == null) return score;

    // Hard eliminate: born more than 5 years after document (no reasonable margin needed)
    if (birth != null && birth > docYear + 5) {
      return 0.0;
    }
    // Hard eliminate: died more than 100 years before document
    if (death != null && death < docYear - 100) {
      return 0.0;
    }

    // Penalty: died before document date (but within 100 years — could be referenced historically)
    if (death != null && death < docYear) {
      int yearsBefore = docYear - death;
      // Gradual penalty: recently deceased get mild penalty, long-dead get heavy
      double penalty = Math.max(0.3, 1.0 - yearsBefore * 0.02);
      return score * penalty;
    }

    // Boost: person was plausibly alive at document time (allow > 1.0 for ranking)
    return score * 1.15;
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

  /** Family surname — every person in the tree is a Czernin. */
  private List<String> getPersonNameTokens(Person person, Set<String> titleWords) {
    List<String> tokens = new ArrayList<>();
    String normalized = familyTreeService.normalize(person.name);
    for (String t : normalized.split("[\\s,;.()]+")) {
      if (!t.isEmpty() && t.length() > 1 && !titleWords.contains(t)) {
        tokens.add(t);
      }
    }

    // Every person in this family tree is a Czernin. Documents typically reference
    // people as "Rudolf Czernin" or "Graf Czernin", so the surname must be matchable.
    if (!tokens.contains(FAMILY_SURNAME)) {
      tokens.add(FAMILY_SURNAME);
    }

    // Add birthplace as a discriminator — e.g. "Dymokury" helps identify which Rudolf
    // when the document says "Rudolf Czernin /Dymokur/"
    // birthPlace may contain death place too (e.g. "Dymokury , +Wien"), extract only birth part
    if (person.birthPlace != null && !person.birthPlace.isBlank()) {
      String rawPlace = person.birthPlace.split("[,+]")[0].trim();
      String normPlace = familyTreeService.normalize(rawPlace);
      if (normPlace.length() >= 4 && !tokens.contains(normPlace)) {
        tokens.add(normPlace);
      }
    }

    return tokens;
  }

  /**
   * Returns the given names from person.name in their original order, excluding title words. Used
   * for name-position bonus scoring.
   */
  List<String> getOrderedGivenNames(Person person, Set<String> titleWords) {
    List<String> givenNames = new ArrayList<>();
    String normalized = familyTreeService.normalize(person.name);
    for (String t : normalized.split("[\\s,;.()]+")) {
      if (!t.isEmpty() && t.length() > 1 && !titleWords.contains(t)) {
        givenNames.add(t);
      }
    }
    return givenNames;
  }

  /**
   * Computes a graduated bonus based on which name position matched in the text. First given name
   * match → highest bonus, second → less, etc. This helps disambiguate e.g. "Rudolf" as a first
   * name (person 234) vs "Rudolf" as a 4th middle name (person 258).
   */
  double computeNamePositionBonus(
      List<String> textTokens, int matchIndex, List<String> orderedGivenNames) {
    if (orderedGivenNames.isEmpty()) return 1.0;

    int windowStart = Math.max(0, matchIndex - PROXIMITY_WINDOW);
    int windowEnd = Math.min(textTokens.size(), matchIndex + PROXIMITY_WINDOW + 1);

    // Find the earliest-positioned given name that appears in the text window
    int bestNamePosition = Integer.MAX_VALUE;
    for (int j = windowStart; j < windowEnd; j++) {
      for (int pos = 0; pos < orderedGivenNames.size(); pos++) {
        if (pos >= bestNamePosition) break; // can't improve
        if (fuzzyMatch(textTokens.get(j), orderedGivenNames.get(pos))) {
          bestNamePosition = pos;
          break;
        }
      }
    }

    if (bestNamePosition == Integer.MAX_VALUE) return 1.0; // no given name matched in window

    // Graduated bonus: 1st name → 1.3x, 2nd → 1.2x, 3rd → 1.1x, 4th+ → 1.0x
    return switch (bestNamePosition) {
      case 0 -> 1.3;
      case 1 -> 1.2;
      case 2 -> 1.1;
      default -> 1.0;
    };
  }

  private boolean fuzzyMatch(String textToken, String personToken) {
    if (textToken.equals(personToken)) return true;
    // Substring match only for tokens >= 4 chars to avoid "in" matching "katerina" etc.
    if (textToken.length() >= 4 && personToken.length() >= 4) {
      if (textToken.contains(personToken) || personToken.contains(textToken)) return true;
    }
    // Levenshtein ≤ 1 — tolerates single OCR errors (e.g. "rudol" → "rudolf")
    // but prevents false positives like "adolf" ↔ "rudolf" (distance 2) or "paul" ↔ "laut"
    if (textToken.length() >= 4
        && personToken.length() >= 4
        && familyTreeService.levenshtein(textToken, personToken) <= 1) {
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

    // Track used text positions — each text token can only satisfy one name token.
    // Prevents "ottokar" counting for both "otto" (substring) and "ottokar" (exact).
    Set<Integer> usedPositions = new HashSet<>();
    for (String nameToken : nameTokens) {
      for (int j = windowStart; j < windowEnd; j++) {
        if (usedPositions.contains(j)) continue;
        if (fuzzyMatch(textTokens.get(j), nameToken)) {
          matched++;
          usedPositions.add(j);
          break;
        }
      }
    }

    // Require at least 2 matched tokens for multi-token names — matching just the
    // surname alone (e.g. "czernin") shouldn't be enough to identify a specific person.
    if (nameTokens.size() >= 2 && matched < 2) {
      return 0.0;
    }

    // Documents typically use surname + 1-2 given names (e.g. "Rudolf Czernin").
    // Don't penalize people with many given names — cap denominator at 3.
    int requiredTokens = Math.min(nameTokens.size(), 3);
    return Math.min(1.0, (double) matched / requiredTokens);
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

  private record PersonToken(
      Person person, List<String> allNameTokens, List<String> orderedGivenNames) {}

  static class MatchCandidate {
    final Person person;
    double bestScore;
    int bestTokenIndex;

    MatchCandidate(Person person, double score, int tokenIndex) {
      this.person = person;
      this.bestScore = score;
      this.bestTokenIndex = tokenIndex;
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
