package place.icomb.archiver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
  // The same embedder the passage side uses. Queries and passages must reach the same model at
  // the same width, and only the query side adds the instruction prefix — when those came from
  // separate properties they did, for two days, describe different models, which degrades every
  // search while raising no error at all.
  private final place.icomb.archiver.ai.RegistryEmbedder embedder;
  private final place.icomb.archiver.service.ResilientHttpClient httpClient =
      place.icomb.archiver.service.ResilientHttpClient.builder().build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  public SemanticSearchController(
      JdbcTemplate jdbcTemplate, place.icomb.archiver.ai.RegistryEmbedder embedder) {
    this.jdbcTemplate = jdbcTemplate;
    this.embedder = embedder;
  }

  @PostMapping("/search/semantic")
  public ResponseEntity<Map<String, Object>> semanticSearch(@RequestBody Map<String, Object> body) {
    String query = (String) body.get("query");
    int limit = body.containsKey("limit") ? ((Number) body.get("limit")).intValue() : 10;

    if (query == null || query.isBlank()) {
      return ResponseEntity.ok(Map.of("results", List.of()));
    }

    if (!embedder.isConfigured()) {
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

      // Hybrid search: combine vector nearest-neighbors with keyword-matching chunks.
      // Vector-only misses keyword hits outside the top-200; keyword-only misses semantic matches.
      String keywordFilter;
      List<Object> kwFilterParams = new ArrayList<>();
      if (keywords.isEmpty()) {
        keywordFilter = "FALSE";
      } else {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < keywords.size(); i++) {
          if (i > 0) sb.append(" OR ");
          sb.append("lower(tc.content) LIKE '%' || ? || '%'");
          kwFilterParams.add(keywords.get(i));
        }
        sb.append(")");
        keywordFilter = sb.toString();
      }

      String sql =
          """
          WITH vec_candidates AS (
            SELECT tc.record_id, tc.page_id, tc.chunk_index, tc.content,
                   1 - (tc.embedding <=> ?::halfvec) AS sem_score
            FROM text_chunk tc
            ORDER BY tc.embedding <=> ?::halfvec
            LIMIT 500
          ),
          kw_candidates AS (
            SELECT tc.record_id, tc.page_id, tc.chunk_index, tc.content,
                   1 - (tc.embedding <=> ?::halfvec) AS sem_score
            FROM text_chunk tc
            WHERE %s
            LIMIT 500
          ),
          candidates AS (
            SELECT * FROM vec_candidates
            UNION
            SELECT * FROM kw_candidates
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
            AND (r.kw_score > 0 OR r.sem_score >= 0.45)
          ORDER BY r.hybrid_score DESC
          LIMIT ?
          """
              .formatted(keywordFilter, keywordBoostExpr);

      List<Object> allParams = new ArrayList<>();
      allParams.add(vecStr.toString()); // vec_candidates: sem_score
      allParams.add(vecStr.toString()); // vec_candidates: ORDER BY
      allParams.add(vecStr.toString()); // kw_candidates: sem_score
      allParams.addAll(kwFilterParams); // kw_candidates: WHERE filter
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
    // The instruction prefix goes on the QUERY side only; the worker embeds passages without
    // one. Both come from the same registration, so the model, the width and the prefix cannot
    // describe different things.
    String prefixedText = embedder.queryPrefix() + text;
    String jsonBody =
        objectMapper.writeValueAsString(
            Map.of(
                "model", embedder.model(),
                "input", List.of(prefixedText),
                "dimensions", embedder.dimensions()));

    var requestBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(embedder.baseUrl() + embedder.endpointPath()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

    String apiKey = embedder.apiKey();
    if (apiKey != null && !apiKey.isBlank()) {
      requestBuilder.header("Authorization", "Bearer " + apiKey);
    }

    HttpResponse<String> response =
        httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException("TEI API error: " + response.statusCode() + " " + response.body());
    }

    // OpenAI-compatible response: {"data":[{"index":0,"embedding":[...]}]}
    var tree = objectMapper.readTree(response.body());
    var embeddingNode = tree.get("data").get(0).get("embedding");
    float[] embedding = new float[embeddingNode.size()];
    for (int i = 0; i < embeddingNode.size(); i++) {
      embedding[i] = (float) embeddingNode.get(i).doubleValue();
    }
    return embedding;
  }
}
