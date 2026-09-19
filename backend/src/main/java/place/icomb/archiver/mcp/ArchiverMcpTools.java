package place.icomb.archiver.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
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
  private final place.icomb.archiver.service.JobService jobService;

  public ArchiverMcpTools(
      ApiController apiController,
      SemanticSearchController semanticSearchController,
      FamilyTreeService familyTreeService,
      PersonMatchService personMatchService,
      JdbcTemplate jdbcTemplate,
      place.icomb.archiver.service.JobService jobService) {
    this.apiController = apiController;
    this.semanticSearchController = semanticSearchController;
    this.familyTreeService = familyTreeService;
    this.personMatchService = personMatchService;
    this.jdbcTemplate = jdbcTemplate;
    this.jobService = jobService;
  }

  /**
   * Refuses anything but an administrator, and refuses when it cannot tell.
   *
   * <p>The MCP session filter grants {@code ROLE_ADMIN} to an administrator, so the caller's role
   * is knowable here. It fails closed: if no authenticated context reaches the tool invocation the
   * answer is no, which at worst makes a tool unusable and never leaves it unguarded.
   */
  private boolean isHeld(long recordId) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT ai_held_at IS NOT NULL FROM record WHERE id = ?", Boolean.class, recordId));
  }

  private void requireAdmin() {
    var auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    boolean admin =
        auth != null
            && auth.isAuthenticated()
            && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    if (!admin) {
      throw new IllegalStateException("this tool is available to administrators only");
    }
  }

  @McpTool(
      name = "list_archives",
      title = "List Archives",
      description =
          "List all archives in the system with record counts. "
              + "Returns archive id, name, country, and number of records in each.",
      annotations =
          @McpTool.McpAnnotations(
              title = "List Archives",
              readOnlyHint = true,
              destructiveHint = false))
  public List<Map<String, Object>> listArchives() {
    return jdbcTemplate.queryForList(
        "SELECT a.id, a.name, a.country, COUNT(r.id) AS record_count "
            + "FROM archive a LEFT JOIN record r ON r.archive_id = a.id "
            + "GROUP BY a.id, a.name, a.country ORDER BY a.id");
  }

  @McpTool(
      name = "search_documents",
      title = "Search Documents",
      description =
          "Full-text keyword search across record titles, descriptions, reference codes, and OCR"
              + " text. Supports multi-word queries (AND logic) and exclusions with -prefix. Returns"
              + " paginated results with metadata.",
      annotations =
          @McpTool.McpAnnotations(
              title = "Search Documents",
              readOnlyHint = true,
              destructiveHint = false))
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
        "https://archive.czernin.eu/api");
  }

  @McpTool(
      name = "semantic_search",
      title = "Semantic Search",
      description =
          "Natural language vector similarity search across OCR text chunks. Uses OpenAI embeddings"
              + " + pgvector with keyword boosting. Best for conceptual queries like 'letters about"
              + " property confiscation'. Returns matching text chunks with record context.",
      annotations =
          @McpTool.McpAnnotations(
              title = "Semantic Search",
              readOnlyHint = true,
              destructiveHint = false))
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
      title = "Get Document",
      description =
          "Get a complete document by record ID. Returns full metadata (title, description, date"
              + " range, reference code, both original and English translation), the whole record"
              + " as one string in fullText (original) and fullTextEn (English) with pages"
              + " separated by a form feed, every page individually with its own OCR text and"
              + " translation, and links to images/PDF. Each page states the media type of its"
              + " text in contentType (text/markdown or text/plain) - read it, do not guess:"
              + " a page number like '- 5 -' is indistinguishable from a markdown bullet.",
      annotations =
          @McpTool.McpAnnotations(
              title = "Get Document",
              readOnlyHint = true,
              destructiveHint = false))
  public Map<String, Object> getDocument(@McpToolParam(description = "Record ID") Long recordId) {
    return apiController.getDocument(recordId, "https://archive.czernin.eu/api");
  }

  @McpTool(
      name = "browse_documents",
      title = "Browse Documents",
      description =
          "Browse documents with optional archive filter. Returns paginated list of records "
              + "sorted by creation date (newest first). Use this to explore what's in an archive.",
      annotations =
          @McpTool.McpAnnotations(
              title = "Browse Documents",
              readOnlyHint = true,
              destructiveHint = false))
  public Map<String, Object> browseDocuments(
      @McpToolParam(description = "Filter by archive ID", required = false) Long archiveId,
      @McpToolParam(description = "Page number (0-based). Default 0.", required = false)
          Integer page,
      @McpToolParam(description = "Page size. Default 20.", required = false) Integer size) {
    return apiController.listDocuments(
        archiveId,
        page != null ? page : 0,
        size != null ? size : 20,
        "https://archive.czernin.eu/api");
  }

  @McpTool(
      name = "search_family_tree",
      title = "Search Family Tree",
      description =
          "Fuzzy search the Czernin family genealogy by name. Handles diacritics, typos, and "
              + "partial matches. Returns matching people with birth/death years, genealogy codes, "
              + "and relevance scores.",
      annotations =
          @McpTool.McpAnnotations(
              title = "Search Family Tree",
              readOnlyHint = true,
              destructiveHint = false))
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
      title = "Get Person",
      description =
          "Get full details for a person in the Czernin family tree by their ID. Returns name,"
              + " birth/death info, life events (birth, death, marriages), children, spouses, and"
              + " kinship relationship to Alexander Friedrich Josef Czernin.",
      annotations =
          @McpTool.McpAnnotations(
              title = "Get Person",
              readOnlyHint = true,
              destructiveHint = false))
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
      title = "Find People in Document",
      description =
          "Find Czernin family members mentioned in a document's OCR text. Uses fuzzy name "
              + "matching with temporal disambiguation (boosts people alive during the document's "
              + "date range). Returns matched people with confidence scores and text context.",
      annotations =
          @McpTool.McpAnnotations(
              title = "Find People in Document",
              readOnlyHint = true,
              destructiveHint = false))
  public List<Map<String, Object>> findPeopleInDocument(
      @McpToolParam(description = "Record ID of the document to analyze") Long recordId) {
    var matches = personMatchService.getRecordMatches(recordId);
    List<Map<String, Object>> out = new ArrayList<>();
    for (var m : matches) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("personId", m.personId());
      entry.put("personName", m.personName());
      entry.put("maxScore", m.maxScore());
      entry.put("pageCount", m.pageSeqs().size());
      out.add(entry);
    }
    return out;
  }

  @McpTool(
      name = "list_jobs",
      title = "List Pipeline Jobs",
      description =
          "List processing jobs, newest first. Filter by record, page, kind or status "
              + "(pending, claimed, completed, failed). This is how a job's id is found. "
              + "Kinds include ocr_page_mistral, ocr_page_transkribus, translate_page, "
              + "translate_record, build_searchable_pdf, embed_record, match_persons.",
      annotations =
          @McpTool.McpAnnotations(
              title = "List Pipeline Jobs",
              readOnlyHint = true,
              destructiveHint = false))
  public List<Map<String, Object>> listJobs(
      @McpToolParam(description = "Filter by record id", required = false) Long recordId,
      @McpToolParam(description = "Filter by page id", required = false) Long pageId,
      @McpToolParam(description = "Filter by job kind", required = false) String kind,
      @McpToolParam(description = "Filter by status", required = false) String status,
      @McpToolParam(description = "Max rows, default 50, capped at 500", required = false)
          Integer limit) {
    int capped = limit == null ? 50 : Math.max(1, Math.min(limit, 500));
    return jdbcTemplate.queryForList(
        """
        SELECT id, kind, status,
               record_id  AS "recordId",
               page_id    AS "pageId",
               attempts,
               created_at AS "createdAt",
               finished_at AS "finishedAt",
               left(error, 300) AS error
          FROM job
         WHERE (?::bigint IS NULL OR record_id = ?::bigint)
           AND (?::bigint IS NULL OR page_id   = ?::bigint)
           AND (?::text   IS NULL OR kind      = ?::text)
           AND (?::text   IS NULL OR status    = ?::text)
         ORDER BY id DESC
         LIMIT ?
        """,
        recordId,
        recordId,
        pageId,
        pageId,
        kind,
        kind,
        status,
        status,
        capped);
  }

  /**
   * The panic button, and the only writing tool exposed over MCP.
   *
   * <p>It is here because it is the one action worth taking immediately and from anywhere: a record
   * left in a bad state — a transcription overwritten, a page emptied — otherwise goes on being
   * translated, embedded and matched at cost per call. Holding it stops that without destroying
   * anything: the queued jobs stay pending and run when the hold is lifted.
   *
   * <p>Everything that spends money or throws work away — re-OCR, cancelling jobs, importing a
   * Transkribus collection — stays behind the admin token and off this interface.
   */
  @McpTool(
      name = "hold_record",
      title = "Hold or Release a Record's AI Processing",
      description =
          "Stop all AI processing for a record (OCR, translation, embedding, person matching), "
              + "or lift a hold. Queued jobs are not cancelled: they wait and run when the hold "
              + "is lifted. Use when a record is in a broken state and further processing would "
              + "cost money reproducing the fault. Pass hold=false to release. Always give a "
              + "reason: a hold without one is indistinguishable from a stuck record.",
      annotations =
          @McpTool.McpAnnotations(
              title = "Hold or Release a Record's AI Processing",
              readOnlyHint = false,
              destructiveHint = false,
              idempotentHint = true))
  public Map<String, Object> holdRecord(
      @McpToolParam(description = "Record id") Long recordId,
      @McpToolParam(description = "Why it is being held") String reason,
      @McpToolParam(description = "true to hold (default), false to release", required = false)
          Boolean hold) {

    requireAdmin();

    boolean holding = hold == null || hold;
    int updated =
        holding
            ? jdbcTemplate.update(
                "UPDATE record SET ai_held_at = now(), ai_hold_reason = ? WHERE id = ?",
                reason,
                recordId)
            : jdbcTemplate.update(
                "UPDATE record SET ai_held_at = NULL, ai_hold_reason = NULL WHERE id = ?",
                recordId);

    if (updated == 0) {
      return Map.of("error", "no record " + recordId);
    }

    Long waiting =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM job WHERE record_id = ? AND status IN ('pending', 'claimed')",
            Long.class,
            recordId);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("recordId", recordId);
    out.put("held", holding);
    out.put("reason", reason);
    out.put("jobsWaiting", waiting == null ? 0 : waiting);
    return out;
  }

  @McpTool(
      name = "cancel_job",
      title = "Cancel One Queued Job",
      description =
          "Cancel a single pending or claimed job by its id — find the id with list_jobs. A "
              + "finished job is left alone. To stop everything a record has queued, hold the "
              + "record instead: cancelling its jobs without holding it only invites the "
              + "pipeline to enqueue them again. Administrators only.",
      annotations =
          @McpTool.McpAnnotations(
              title = "Cancel One Queued Job",
              readOnlyHint = false,
              destructiveHint = true))
  public Map<String, Object> cancelJob(
      @McpToolParam(description = "Job id, from list_jobs") Long jobId,
      @McpToolParam(description = "Why it is being cancelled", required = false) String reason) {
    requireAdmin();

    List<Map<String, Object>> stopped =
        jdbcTemplate.queryForList(
            """
            UPDATE job
               SET status = 'failed', error = ?, finished_at = now()
             WHERE id = ? AND status IN ('pending', 'claimed')
            RETURNING id, kind, status, record_id AS "recordId", page_id AS "pageId"
            """,
            "cancelled via MCP" + (reason == null || reason.isBlank() ? "" : ": " + reason),
            jobId);

    return Map.of("cancelled", stopped.size(), "jobs", stopped);
  }

  @McpTool(
      name = "reocr_page",
      title = "Re-transcribe One Page",
      description =
          "Read one page again on a chosen engine and carry that page through translation, the "
              + "record's PDF and re-embedding. Address it by recordId and seq, or by pageId. "
              + "Choose the engine by what the page is: 'mistral' for print and typescript, "
              + "where it reads several times more text than a handwriting model; 'transkribus' "
              + "for handwriting. This costs money per call. Administrators only.",
      annotations =
          @McpTool.McpAnnotations(
              title = "Re-transcribe One Page",
              readOnlyHint = false,
              destructiveHint = false))
  public Map<String, Object> reocrPage(
      @McpToolParam(description = "Record id, with seq", required = false) Long recordId,
      @McpToolParam(description = "Page sequence within the record", required = false) Integer seq,
      @McpToolParam(description = "Page id, instead of recordId and seq", required = false)
          Long pageId,
      @McpToolParam(description = "'mistral' for print, 'transkribus' for handwriting")
          String engine) {
    requireAdmin();

    // The hold is checked before anything else that can fail, so a held record answers "held"
    // rather than some incidental complaint about the page number.
    if (recordId != null && isHeld(recordId)) {
      return Map.of("error", "record " + recordId + " is on AI hold; release it first");
    }

    Long resolved = pageId;
    if (resolved == null) {
      if (recordId == null || seq == null) {
        return Map.of("error", "give either pageId, or recordId and seq");
      }
      resolved =
          jdbcTemplate
              .query(
                  "SELECT id FROM page WHERE record_id = ? AND seq = ?",
                  (rs, i) -> rs.getLong(1),
                  recordId,
                  seq)
              .stream()
              .findFirst()
              .orElse(null);
      if (resolved == null) {
        return Map.of("error", "no page %d in record %d".formatted(seq, recordId));
      }
    }

    Long owner =
        jdbcTemplate.queryForObject(
            "SELECT record_id FROM page WHERE id = ?", Long.class, resolved);
    if (owner == null) {
      return Map.of("error", "no page " + resolved);
    }
    if (isHeld(owner)) {
      // Queueing work for a held record would sit pending until the hold lifts, then run — which
      // is exactly what the hold was put on to prevent.
      return Map.of("error", "record " + owner + " is on AI hold; release it first");
    }

    String kind =
        "transkribus".equalsIgnoreCase(engine)
            ? "ocr_page_transkribus"
            : (engine != null && engine.startsWith("ocr_page_") ? engine : "ocr_page_mistral");
    String lang =
        jdbcTemplate.queryForObject("SELECT lang FROM record WHERE id = ?", String.class, owner);

    jobService.enqueueJob(
        kind, owner, resolved, "{\"lang\":\"%s\",\"andThen\":\"full\"}".formatted(lang));

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("recordId", owner);
    out.put("pageId", resolved);
    out.put("jobKind", kind);
    return out;
  }
}
