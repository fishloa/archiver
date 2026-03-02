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
          "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "have", "has", "had",
          "do", "does", "did", "will", "would", "could", "should", "may", "might", "shall", "can",
          "need", "dare", "ought", "used", "to", "of", "in", "for", "on", "with", "at", "by",
          "from", "as", "into", "through", "during", "before", "after", "above", "below", "between",
          "out", "off", "over", "under", "again", "further", "then", "once", "here", "there",
          "when", "where", "why", "how", "all", "both", "each", "few", "more", "most", "other",
          "some", "such", "no", "nor", "not", "only", "own", "same", "so", "than", "too", "very",
          "just", "don", "now", "and", "but", "or", "if", "while", "about", "any", "what", "which",
          "who", "whom", "this", "that", "these", "those", "i", "me", "my", "we", "our", "you",
          "your", "he", "him", "his", "she", "her", "it", "its", "they", "them", "their", "see",
          "get", "got", "find", "found", "know", "think", "tell", "say", "said");

  private final JdbcTemplate jdbcTemplate;
  private final String teiUrl;
  private final String teiKey;
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  public SemanticSearchController(
      JdbcTemplate jdbcTemplate,
      @Value("${archiver.embed.tei-url:}") String teiUrl,
      @Value("${archiver.embed.tei-key:}") String teiKey) {
    this.jdbcTemplate = jdbcTemplate;
    this.teiUrl = teiUrl;
    this.teiKey = teiKey;
  }

  @PostMapping("/search/semantic")
  public ResponseEntity<Map<String, Object>> semanticSearch(@RequestBody Map<String, Object> body) {
    String query = (String) body.get("query");
    int limit = body.containsKey("limit") ? ((Number) body.get("limit")).intValue() : 10;

    if (query == null || query.isBlank()) {
      return ResponseEntity.ok(Map.of("results", List.of()));
    }

    if (teiUrl == null || teiUrl.isBlank()) {
      return ResponseEntity.status(503).body(Map.of("error", "Embedding service not configured"));
    }

    try {
      // 1. Extract keywords (non-stop words, 3+ chars)
      List<String> keywords =
          List.of(query.toLowerCase().replaceAll("[^a-z0-9\\s]", "").split("\\s+")).stream()
              .filter(w -> w.length() >= 3 && !STOP_WORDS.contains(w))
              .distinct()
              .collect(Collectors.toList());

      log.info("Semantic search: query='{}', keywords={}", query, keywords);

      // 2. Embed the query via TEI
      long t0 = System.currentTimeMillis();
      float[] queryEmbedding = embedText(query);
      long tEmbed = System.currentTimeMillis();
      log.info("Embedding took {} ms", tEmbed - t0);

      // 3. Build pgvector query string
      StringBuilder vecStr = new StringBuilder("[");
      for (int i = 0; i < queryEmbedding.length; i++) {
        if (i > 0) vecStr.append(",");
        vecStr.append(queryEmbedding[i]);
      }
      vecStr.append("]");

      // 4. Hybrid search: semantic + keyword hit counting
      //    Each keyword that appears in the content (via pg_trgm word_similarity >= 0.5)
      //    adds 0.5 to the score. This means actual keyword presence dominates.
      //    A chunk with "czernin" gets +0.5, one without gets +0.0 for that keyword.
      String keywordBoostExpr;
      List<Object> params = new ArrayList<>();

      if (keywords.isEmpty()) {
        keywordBoostExpr = "0.0";
      } else {
        // Sum of per-keyword hits using case-insensitive substring match (LIKE on lowered content).
        // Each keyword that appears in the content adds 0.5.
        // word_similarity() was removed — it added ~100ms per search on 200 candidates.
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < keywords.size(); i++) {
          if (i > 0) sb.append(" + ");
          sb.append("CASE WHEN lower(c.content) LIKE '%' || ? || '%' THEN 0.5 ELSE 0.0 END");
          params.add(keywords.get(i));
        }
        sb.append(")");
        keywordBoostExpr = sb.toString();
      }

      // Use ORDER BY <=> LIMIT to leverage the HNSW index (fast ANN lookup),
      // then apply keyword scoring on the small candidate set.
      String sql =
          """
          WITH candidates AS (
            SELECT tc.record_id, tc.page_id, tc.chunk_index, tc.content,
                   1 - (tc.embedding <=> ?::vector) AS sem_score
            FROM text_chunk tc
            ORDER BY tc.embedding <=> ?::vector
            LIMIT 200
          ),
          scored AS (
            SELECT c.*,
                   %s AS kw_score
            FROM candidates c
            WHERE c.sem_score >= 0.20
          ),
          ranked AS (
            SELECT *,
                   sem_score + kw_score AS hybrid_score,
                   ROW_NUMBER() OVER (PARTITION BY record_id ORDER BY sem_score + kw_score DESC) AS rn
            FROM scored
          )
          SELECT r.record_id, r.page_id, r.chunk_index, r.content,
                 r.hybrid_score AS score, r.sem_score, r.kw_score,
                 rec.title AS record_title, rec.title_en AS record_title_en,
                 rec.reference_code, rec.description_en,
                 p.seq AS page_seq
          FROM ranked r
          JOIN record rec ON rec.id = r.record_id
          LEFT JOIN page p ON p.id = r.page_id
          WHERE r.rn = 1
            AND (r.kw_score > 0 OR r.sem_score >= 0.65)
          ORDER BY r.hybrid_score DESC
          LIMIT ?
          """
              .formatted(keywordBoostExpr);

      List<Object> allParams = new ArrayList<>();
      allParams.add(vecStr.toString()); // candidates: sem_score
      allParams.add(vecStr.toString()); // candidates: ORDER BY
      allParams.addAll(params); // keyword ?'s in scored CTE
      allParams.add(limit);

      long tDbStart = System.currentTimeMillis();
      List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, allParams.toArray());
      long tDbEnd = System.currentTimeMillis();
      log.info("DB query took {} ms, returned {} rows", tDbEnd - tDbStart, rows.size());

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
        result.put("pageSeq", row.get("page_seq"));
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
    String jsonBody = objectMapper.writeValueAsString(Map.of("inputs", text));

    var requestBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(teiUrl + "/embed"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

    if (teiKey != null && !teiKey.isBlank()) {
      requestBuilder.header("Authorization", "Bearer " + teiKey);
    }

    HttpResponse<String> response =
        httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException("TEI API error: " + response.statusCode() + " " + response.body());
    }

    // TEI returns float[][] — first element is the embedding for our single input
    var tree = objectMapper.readTree(response.body());
    var embeddingNode = tree.get(0);
    float[] embedding = new float[embeddingNode.size()];
    for (int i = 0; i < embeddingNode.size(); i++) {
      embedding[i] = (float) embeddingNode.get(i).doubleValue();
    }
    return embedding;
  }
}
