package place.icomb.archiver.controller;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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

  @PostMapping("/enqueue-reocr")
  public ResponseEntity<Map<String, Object>> enqueueReocr(
      @RequestParam(defaultValue = "0") long recordId,
      @RequestParam(defaultValue = "1000") int limit) {
    // Enqueue ocr_page_qwen3vl jobs, skipping pages that already have a pending/running job
    String sql =
        """
        SELECT p.id AS page_id, r.id AS record_id, r.lang
        FROM page p
        JOIN record r ON r.id = p.record_id
        WHERE r.status IN ('ocr_done', 'pdf_pending', 'pdf_done',
                           'translating', 'embedding', 'complete')
          AND NOT EXISTS (
            SELECT 1 FROM job j
            WHERE j.page_id = p.id AND j.kind = 'ocr_page_qwen3vl'
              AND j.status IN ('pending', 'running')
          )
        """;
    if (recordId > 0) {
      sql += " AND r.id = " + recordId;
    }
    sql += " ORDER BY r.id, p.seq LIMIT " + limit;

    List<Map<String, Object>> pages = jdbcTemplate.queryForList(sql);

    int enqueued = 0;
    for (Map<String, Object> row : pages) {
      Long pageId = ((Number) row.get("page_id")).longValue();
      Long recId = ((Number) row.get("record_id")).longValue();
      String lang = (String) row.get("lang");
      String payload = lang != null ? "{\"lang\":\"" + lang + "\"}" : null;
      jobService.enqueueJob("ocr_page_qwen3vl", recId, pageId, payload);
      enqueued++;
    }

    log.info("Enqueued {} ocr_page_qwen3vl jobs for re-OCR", enqueued);
    return ResponseEntity.ok(Map.of("jobsEnqueued", enqueued));
  }
}
