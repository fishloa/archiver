package place.icomb.archiver.controller;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
  public ResponseEntity<Map<String, Object>> enqueueReocr() {
    // Enqueue ocr_page_qwen3vl jobs for all pages of records that have completed OCR
    List<Map<String, Object>> pages =
        jdbcTemplate.queryForList(
            """
            SELECT p.id AS page_id, r.id AS record_id, r.lang
            FROM page p
            JOIN record r ON r.id = p.record_id
            WHERE r.status IN ('ocr_done', 'pdf_pending', 'pdf_done',
                               'translating', 'embedding', 'complete')
            ORDER BY r.id, p.seq
            """);

    int enqueued = 0;
    for (Map<String, Object> row : pages) {
      Long pageId = ((Number) row.get("page_id")).longValue();
      Long recordId = ((Number) row.get("record_id")).longValue();
      String lang = (String) row.get("lang");
      String payload = lang != null ? "{\"lang\":\"" + lang + "\"}" : null;
      jobService.enqueueJob("ocr_page_qwen3vl", recordId, pageId, payload);
      enqueued++;
    }

    log.info("Enqueued {} ocr_page_qwen3vl jobs for re-OCR", enqueued);
    return ResponseEntity.ok(Map.of("jobsEnqueued", enqueued));
  }
}
