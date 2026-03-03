package place.icomb.archiver.controller;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.service.JobService;

@RestController
@RequestMapping("/api/admin")
public class AdminPipelineController {

  private static final Logger log = LoggerFactory.getLogger(AdminPipelineController.class);

  private final JdbcTemplate jdbcTemplate;
  private final JobService jobService;

  public AdminPipelineController(JdbcTemplate jdbcTemplate, JobService jobService) {
    this.jdbcTemplate = jdbcTemplate;
    this.jobService = jobService;
  }

  /** Returns 200 for admin users. Used by nginx auth_request to gate admin-only proxies. */
  @GetMapping("/check")
  public ResponseEntity<Void> checkAdmin() {
    return ResponseEntity.ok().build();
  }

  @PostMapping("/enqueue-reocr")
  public ResponseEntity<Map<String, Object>> enqueueReocr(
      @RequestParam(defaultValue = "0") long recordId,
      @RequestParam(defaultValue = "1000") int limit) {
    // Find records to OCR with Qwen, skipping those already in ocr_pending with Qwen jobs
    String sql =
        """
        SELECT DISTINCT r.id AS record_id, r.lang
        FROM record r
        JOIN page p ON p.record_id = r.id
        WHERE r.status IN ('ocr_done', 'pdf_pending', 'pdf_done',
                           'translating', 'embedding', 'complete')
          AND NOT EXISTS (
            SELECT 1 FROM job j
            WHERE j.record_id = r.id AND j.kind = 'ocr_page_qwen3vl'
              AND j.status IN ('pending', 'claimed')
          )
        """;
    if (recordId > 0) {
      sql += " AND r.id = " + recordId;
    }
    sql += " ORDER BY r.id LIMIT " + limit;

    List<Map<String, Object>> records = jdbcTemplate.queryForList(sql);

    int totalJobs = 0;
    int totalRecords = 0;
    for (Map<String, Object> row : records) {
      Long recId = ((Number) row.get("record_id")).longValue();
      String lang = (String) row.get("lang");

      // Clean up downstream data for this record
      jobService.resetForOcr(recId);

      // Enqueue Qwen OCR jobs for each page
      String payload = lang != null ? "{\"lang\":\"" + lang + "\"}" : null;
      List<Long> pageIds =
          jdbcTemplate.queryForList(
              "SELECT id FROM page WHERE record_id = ? ORDER BY seq", Long.class, recId);
      for (Long pageId : pageIds) {
        jobService.enqueueJob("ocr_page_qwen3vl", recId, pageId, payload);
        totalJobs++;
      }
      totalRecords++;
    }

    log.info("Enqueued {} ocr_page_qwen3vl jobs across {} records", totalJobs, totalRecords);
    return ResponseEntity.ok(Map.of("jobsEnqueued", totalJobs, "recordsReset", totalRecords));
  }
}
