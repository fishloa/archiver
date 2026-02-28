package place.icomb.archiver.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import place.icomb.archiver.controller.ApiController;
import place.icomb.archiver.controller.SemanticSearchController;
import place.icomb.archiver.service.FamilyTreeService;
import place.icomb.archiver.service.FamilyTreeService.Person;
import place.icomb.archiver.service.PersonMatchService;

@Component
public class ArchiverMcpTools {

  private final ApiController apiController;
  private final SemanticSearchController semanticSearchController;
  private final FamilyTreeService familyTreeService;
  private final PersonMatchService personMatchService;
  private final JdbcTemplate jdbcTemplate;

  public ArchiverMcpTools(
      ApiController apiController,
      SemanticSearchController semanticSearchController,
      FamilyTreeService familyTreeService,
      PersonMatchService personMatchService,
      JdbcTemplate jdbcTemplate) {
    this.apiController = apiController;
    this.semanticSearchController = semanticSearchController;
    this.familyTreeService = familyTreeService;
    this.personMatchService = personMatchService;
    this.jdbcTemplate = jdbcTemplate;
  }

  @McpTool(
      name = "list_archives",
      description =
          "List all archives in the system with record counts. "
              + "Returns archive id, name, country, and number of records in each.")
  public List<Map<String, Object>> listArchives() {
    return jdbcTemplate.queryForList(
        "SELECT a.id, a.name, a.country, COUNT(r.id) AS record_count "
            + "FROM archive a LEFT JOIN record r ON r.archive_id = a.id "
            + "GROUP BY a.id, a.name, a.country ORDER BY a.id");
  }

  @McpTool(
      name = "search_documents",
      description =
          "Full-text keyword search across record titles, descriptions, reference codes, and OCR"
              + " text. Supports multi-word queries (AND logic) and exclusions with -prefix. Returns"
              + " paginated results with metadata.")
  public Map<String, Object> searchDocuments(
      @McpToolParam(
              description = "Search query. Multiple words are AND'd. Prefix with - to exclude.")
          String q,
      @McpToolParam(description = "Filter by archive ID", required = false) Long archiveId,
      @McpToolParam(description = "Page number (0-based). Default 0.", required = false)
          Integer page,
      @McpToolParam(description = "Page size. Default 20.", required = false) Integer size) {
    return apiController.search(
        q,
        archiveId,
        page != null ? page : 0,
        size != null ? size : 20,
        "https://archiver.icomb.place/api");
  }

  @McpTool(
      name = "semantic_search",
      description =
          "Natural language vector similarity search across OCR text chunks. Uses OpenAI embeddings"
              + " + pgvector with keyword boosting. Best for conceptual queries like 'letters about"
              + " property confiscation'. Returns matching text chunks with record context.")
  public Object semanticSearch(
      @McpToolParam(description = "Natural language search query") String query,
      @McpToolParam(description = "Max results to return. Default 10.", required = false)
          Integer limit) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", query);
    if (limit != null) body.put("limit", limit);
    ResponseEntity<Map<String, Object>> response = semanticSearchController.semanticSearch(body);
    return response.getBody();
  }

  @McpTool(
      name = "get_document",
      description =
          "Get a complete document by record ID. Returns full metadata (title, description, date"
              + " range, reference code, both original and English translation), all pages with OCR"
              + " text (original + English), and links to images/PDF.")
  public Map<String, Object> getDocument(@McpToolParam(description = "Record ID") Long recordId) {
    return apiController.getDocument(recordId, "https://archiver.icomb.place/api");
  }

  @McpTool(
      name = "browse_documents",
      description =
          "Browse documents with optional archive filter. Returns paginated list of records "
              + "sorted by creation date (newest first). Use this to explore what's in an archive.")
  public Map<String, Object> browseDocuments(
      @McpToolParam(description = "Filter by archive ID", required = false) Long archiveId,
      @McpToolParam(description = "Page number (0-based). Default 0.", required = false)
          Integer page,
      @McpToolParam(description = "Page size. Default 20.", required = false) Integer size) {
    return apiController.listDocuments(
        archiveId,
        page != null ? page : 0,
        size != null ? size : 20,
        "https://archiver.icomb.place/api");
  }

  @McpTool(
      name = "search_family_tree",
      description =
          "Fuzzy search the Czernin family genealogy by name. Handles diacritics, typos, and "
              + "partial matches. Returns matching people with birth/death years, genealogy codes, "
              + "and relevance scores.")
  public List<Map<String, Object>> searchFamilyTree(
      @McpToolParam(description = "Name to search for (e.g. 'Eugen Czernin', 'Theobald')") String q,
      @McpToolParam(description = "Max results. Default 10.", required = false) Integer limit) {
    var results = familyTreeService.search(q, limit != null ? limit : 10);
    List<Map<String, Object>> out = new ArrayList<>();
    for (var r : results) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("personId", r.personId());
      m.put("name", r.name());
      m.put("fullEntry", r.fullEntry());
      m.put("section", r.section());
      m.put("code", r.code());
      m.put("score", r.score());
      m.put("birthYear", r.birthYear());
      m.put("deathYear", r.deathYear());
      out.add(m);
    }
    return out;
  }

  @McpTool(
      name = "get_person",
      description =
          "Get full details for a person in the Czernin family tree by their ID. Returns name,"
              + " birth/death info, life events (birth, death, marriages), children, spouses, and"
              + " kinship relationship to Alexander Friedrich Josef Czernin.")
  public Map<String, Object> getPerson(
      @McpToolParam(description = "Person ID from the family tree") int personId) {
    Person person = familyTreeService.getPerson(personId);
    if (person == null) {
      return Map.of("error", "Person not found", "personId", personId);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", person.id);
    result.put("name", person.name);
    result.put("fullEntry", person.fullEntry);
    result.put("section", person.section);
    result.put("code", person.fullCode());
    result.put("birthYear", person.birthYear);
    result.put("deathYear", person.deathYear);
    result.put("birthPlace", person.birthPlace);
    result.put("spouses", person.spouses);

    // Children
    List<Map<String, Object>> children = new ArrayList<>();
    if (person.children != null) {
      for (Person child : person.children) {
        Map<String, Object> cm = new LinkedHashMap<>();
        cm.put("id", child.id);
        cm.put("name", child.name);
        cm.put("birthYear", child.birthYear);
        cm.put("deathYear", child.deathYear);
        children.add(cm);
      }
    }
    result.put("children", children);

    // Parent
    if (person.parent != null) {
      result.put("parent", Map.of("id", person.parent.id, "name", person.parent.name));
    }

    // Life events
    result.put("lifeEvents", familyTreeService.getLifeEvents(person));

    // Kinship to Alexander
    var rel = familyTreeService.relate(personId);
    if (rel != null) {
      Map<String, Object> kinship = new LinkedHashMap<>();
      kinship.put("kinshipLabel", rel.kinshipLabel());
      kinship.put("pathDescription", rel.pathDescription());
      kinship.put("commonAncestor", rel.commonAncestorName());
      kinship.put("stepsFromPerson", rel.stepsFromPerson());
      kinship.put("stepsFromRef", rel.stepsFromRef());
      result.put("kinshipToAlexander", kinship);
    }

    return result;
  }

  @McpTool(
      name = "find_people_in_document",
      description =
          "Find Czernin family members mentioned in a document's OCR text. Uses fuzzy name "
              + "matching with temporal disambiguation (boosts people alive during the document's "
              + "date range). Returns matched people with confidence scores and text context.")
  public List<Map<String, Object>> findPeopleInDocument(
      @McpToolParam(description = "Record ID of the document to analyze") Long recordId) {
    var matches = personMatchService.getRecordMatches(recordId);
    List<Map<String, Object>> out = new ArrayList<>();
    for (var m : matches) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("personId", m.personId());
      entry.put("personName", m.personName());
      entry.put("maxScore", m.maxScore());
      entry.put("pageCount", m.pageCount());
      out.add(entry);
    }
    return out;
  }
}
