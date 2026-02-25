package place.icomb.archiver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
      // 1. Embed the query via OpenAI
      float[] queryEmbedding = embedText(query);

      // 2. Build pgvector query string
      StringBuilder vecStr = new StringBuilder("[");
      for (int i = 0; i < queryEmbedding.length; i++) {
        if (i > 0) vecStr.append(",");
        vecStr.append(queryEmbedding[i]);
      }
      vecStr.append("]");

      // 3. Search pgvector
      List<Map<String, Object>> rows = jdbcTemplate.queryForList(
          """
          SELECT tc.record_id, tc.page_id, tc.chunk_index, tc.content,
                 1 - (tc.embedding <=> ?::vector) AS score,
                 r.title AS record_title, r.title_en AS record_title_en,
                 r.reference_code, r.description_en
          FROM text_chunk tc
          JOIN record r ON r.id = tc.record_id
          ORDER BY tc.embedding <=> ?::vector
          LIMIT ?
          """,
          vecStr.toString(),
          vecStr.toString(),
          limit);

      List<Map<String, Object>> results = new java.util.ArrayList<>();
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

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.openai.com/v1/embeddings"))
        .header("Authorization", "Bearer " + openaiApiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new RuntimeException("OpenAI API error: " + response.statusCode() + " " + response.body());
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
