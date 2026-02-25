package place.icomb.archiver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SemanticSearchController {

  private static final Logger log = LoggerFactory.getLogger(SemanticSearchController.class);

  private static final Set<String> STOP_WORDS =
      Set.of(
          "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
          "have", "has", "had", "do", "does", "did", "will", "would", "could",
          "should", "may", "might", "shall", "can", "need", "dare", "ought",
          "used", "to", "of", "in", "for", "on", "with", "at", "by", "from",
          "as", "into", "through", "during", "before", "after", "above", "below",
          "between", "out", "off", "over", "under", "again", "further", "then",
          "once", "here", "there", "when", "where", "why", "how", "all", "both",
          "each", "few", "more", "most", "other", "some", "such", "no", "nor",
          "not", "only", "own", "same", "so", "than", "too", "very", "just",
          "don", "now", "and", "but", "or", "if", "while", "about", "any",
          "what", "which", "who", "whom", "this", "that", "these", "those",
          "i", "me", "my", "we", "our", "you", "your", "he", "him", "his",
          "she", "her", "it", "its", "they", "them", "their", "see", "get",
          "got", "find", "found", "know", "think", "tell", "say", "said");

  private final JdbcTemplate jdbcTemplate;
  private final String openaiApiKey;
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  public SemanticSearchController(
      JdbcTemplate jdbcTemplate,
      @Value("${archiver.openai.api-key:}") String openaiApiKey) {
    this.jdbcTemplate = jdbcTemplate;
    this.openaiApiKey = openaiApiKey;
  }

  @PostMapping("/search/semantic")
  public ResponseEntity<Map<String, Object>> semanticSearch(@RequestBody Map<String, Object> body) {
    String query = (String) body.get("query");
    int limit = body.containsKey("limit") ? ((Number) body.get("limit")).intValue() : 10;

    if (query == null || query.isBlank()) {
      return ResponseEntity.ok(Map.of("results", List.of()));
    }

    if (openaiApiKey == null || openaiApiKey.isBlank()) {
      return ResponseEntity.status(503)
          .body(Map.of("error", "OpenAI API key not configured"));
    }

    try {
      // 1. Extract keywords (non-stop words, 3+ chars)
      List<String> keywords =
          List.of(query.toLowerCase().replaceAll("[^a-z0-9\\s]", "").split("\\s+")).stream()
              .filter(w -> w.length() >= 3 && !STOP_WORDS.contains(w))
              .distinct()
              .collect(Collectors.toList());

      log.info("Semantic search: query='{}', keywords={}", query, keywords);

      // 2. Embed the query via OpenAI
      float[] queryEmbedding = embedText(query);

      // 3. Build pgvector query string
      StringBuilder vecStr = new StringBuilder("[");
      for (int i = 0; i < queryEmbedding.length; i++) {
        if (i > 0) vecStr.append(",");
        vecStr.append(queryEmbedding[i]);
      }
      vecStr.append("]");

      // 4. Hybrid search: semantic similarity + keyword/trigram boost
      //    - keyword_boost: 0.3 if any keyword fuzzy-matches a word in the content (pg_trgm)
      //    - This boosts results containing names like "czernin"/"cernin" above generic matches
      String keywordBoostExpr;
      List<Object> params = new ArrayList<>();

      if (keywords.isEmpty()) {
        keywordBoostExpr = "0.0";
      } else {
        // Build: GREATEST(similarity(lower(content), kw1), similarity(lower(content), kw2), ...)
        // pg_trgm similarity handles fuzzy matching (czernin ≈ cernin)
        StringBuilder sb = new StringBuilder("GREATEST(");
        for (int i = 0; i < keywords.size(); i++) {
          if (i > 0) sb.append(", ");
          // Use word_similarity for substring matching (finds "czernin" inside long text)
          sb.append("word_similarity(?, lower(tc.content))");
          params.add(keywords.get(i));
        }
        sb.append(")");
        keywordBoostExpr = sb.toString();
      }

      // Build full query
      // params order: [keyword params..., vec, vec, vec, limit]
      String sql =
          """
          WITH scored AS (
            SELECT tc.record_id, tc.page_id, tc.chunk_index, tc.content,
                   1 - (tc.embedding <=> ?::vector) AS sem_score,
                   %s AS kw_score
            FROM text_chunk tc
            WHERE 1 - (tc.embedding <=> ?::vector) >= 0.20
          ),
          ranked AS (
            SELECT *,
                   sem_score + (kw_score * 0.5) AS hybrid_score,
                   ROW_NUMBER() OVER (PARTITION BY record_id ORDER BY sem_score + (kw_score * 0.5) DESC) AS rn
            FROM scored
          )
          SELECT r.record_id, r.page_id, r.chunk_index, r.content,
                 r.hybrid_score AS score, r.sem_score, r.kw_score,
                 rec.title AS record_title, rec.title_en AS record_title_en,
                 rec.reference_code, rec.description_en
          FROM ranked r
          JOIN record rec ON rec.id = r.record_id
          WHERE r.rn = 1
          ORDER BY r.hybrid_score DESC
          LIMIT ?
          """
              .formatted(keywordBoostExpr);

      // Assemble params: keyword params first, then vec (x3), then limit
      List<Object> allParams = new ArrayList<>(params);
      allParams.add(vecStr.toString()); // for sem_score
      allParams.add(vecStr.toString()); // for WHERE filter
      allParams.add(limit);

      List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, allParams.toArray());

      List<Map<String, Object>> results = new ArrayList<>();
      for (var row : rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", row.get("record_id"));
        result.put("pageId", row.get("page_id"));
        result.put("chunkIndex", row.get("chunk_index"));
        result.put("content", row.get("content"));
        result.put("score", row.get("score"));
        result.put("recordTitle", row.get("record_title"));
        result.put("recordTitleEn", row.get("record_title_en"));
        result.put("referenceCode", row.get("reference_code"));
        result.put("descriptionEn", row.get("description_en"));
        results.add(result);
      }

      return ResponseEntity.ok(Map.of("results", results));

    } catch (Exception e) {
      log.error("Semantic search failed", e);
      return ResponseEntity.internalServerError()
          .body(Map.of("error", "Search failed: " + e.getMessage()));
    }
  }

  private float[] embedText(String text) throws Exception {
    String escapedText = objectMapper.writeValueAsString(text);
    String jsonBody = "{\"model\": \"text-embedding-3-small\", \"input\": " + escapedText + "}";

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/embeddings"))
            .header("Authorization", "Bearer " + openaiApiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();

    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException(
          "OpenAI API error: " + response.statusCode() + " " + response.body());
    }

    var tree = objectMapper.readTree(response.body());
    var embeddingNode = tree.get("data").get(0).get("embedding");
    float[] embedding = new float[embeddingNode.size()];
    for (int i = 0; i < embeddingNode.size(); i++) {
      embedding[i] = (float) embeddingNode.get(i).doubleValue();
    }
    return embedding;
  }
}
