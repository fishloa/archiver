package place.icomb.archiver.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import place.icomb.archiver.model.PageText;
import place.icomb.archiver.repository.PageTextRepository;

/**
 * Brings transcriptions made in the Transkribus web app back into the archive.
 *
 * <p>This exists because of a hole in what the account may do: the super models — Text Titan II and
 * the rest — can only be *started* from the web app, since the API refuses them, but the API may
 * read what they produced. So the loop is upload by API, run by hand, import by API.
 *
 * <p>The join is the file name. Pages are uploaded as {@code rec<recordId>_seq<pageSeq>.jpg} and
 * Transkribus keeps that in the PAGE XML's {@code imageFilename}, so a finished document says which
 * archive page each transcript belongs to without anyone keeping a list.
 */
public class TranskribusImportService {

  private static final Logger log = LoggerFactory.getLogger(TranskribusImportService.class);

  /** {@code rec3505_seq0110.jpg} — the convention that makes an import self-describing. */
  private static final Pattern FILE_NAME = Pattern.compile("rec(\\d+)_seq(\\d+)");

  private final JdbcTemplate jdbcTemplate;
  private final PageTextRepository pageTextRepository;
  private final PipelineStateMachine stateMachine;

  public TranskribusImportService(
      JdbcTemplate jdbcTemplate,
      PageTextRepository pageTextRepository,
      PipelineStateMachine stateMachine) {
    this.jdbcTemplate = jdbcTemplate;
    this.pageTextRepository = pageTextRepository;
    this.stateMachine = stateMachine;
  }

  /**
   * What happened to one page.
   *
   * @param outcome {@code imported}, or why it was not
   */
  public record PageOutcome(
      String imageFileName,
      Long recordId,
      Integer seq,
      Integer modelId,
      Integer chars,
      String outcome) {}

  /** Parsed {@code rec<record>_seq<page>} from an uploaded file name. */
  static long[] parseFileName(String imageFileName) {
    Matcher m = FILE_NAME.matcher(imageFileName == null ? "" : imageFileName);
    return m.find() ? new long[] {Long.parseLong(m.group(1)), Long.parseLong(m.group(2))} : null;
  }

  /**
   * Imports every transcript in a Transkribus document.
   *
   * @param advance whether each imported page should then be re-translated, have the record's PDF
   *     rebuilt and the record re-embedded
   */
  public List<PageOutcome> importDocument(
      TranskribusTrpClient client, int collId, long docId, boolean advance) throws Exception {

    var outcomes = new ArrayList<PageOutcome>();

    for (TranskribusTrpClient.TranscriptRef ref : client.listTranscripts(collId, docId)) {
      long[] parsed = parseFileName(ref.imageFileName());
      if (parsed == null) {
        outcomes.add(
            new PageOutcome(
                ref.imageFileName(),
                null,
                null,
                null,
                null,
                "skipped: file name is not rec<record>_seq<page>"));
        continue;
      }
      long recordId = parsed[0];
      int seq = (int) parsed[1];

      Long pageId =
          jdbcTemplate
              .query(
                  "SELECT id FROM page WHERE record_id = ? AND seq = ?",
                  (rs, i) -> rs.getLong(1),
                  recordId,
                  seq)
              .stream()
              .findFirst()
              .orElse(null);
      if (pageId == null) {
        outcomes.add(
            new PageOutcome(
                ref.imageFileName(),
                recordId,
                seq,
                null,
                null,
                "skipped: no such page in the archive"));
        continue;
      }

      String pageXml = client.fetchTranscript(ref.url());
      int modelId = TranskribusTrpClient.modelIdFromPageXml(pageXml);
      String text = TranskribusTrpClient.textFromPageXml(pageXml);

      if (text.isBlank()) {
        // An empty transcript is a layout with no recognition run over it. Writing it would
        // replace a poor transcription with none at all and read as "this page is blank".
        outcomes.add(
            new PageOutcome(
                ref.imageFileName(), recordId, seq, modelId, 0, "skipped: transcript has no text"));
        continue;
      }

      // Replace rather than append: page_text is UNIQUE on page_id, and the history trigger copies
      // the outgoing transcription into page_ocr_history, so the engine that got it wrong stays on
      // the record even though its text does not.
      pageTextRepository.deleteByPageId(pageId);

      PageText pt = new PageText();
      pt.setPageId(pageId);
      // The model is part of the provenance: a free community model and a paid super model are not
      // the same evidence, and this one comes from the XML rather than from what we assume ran.
      pt.setEngine(modelId > 0 ? "transkribus:" + modelId : "transkribus");
      pt.setContentType(OcrContentType.PLAIN);
      pt.setTextRaw(text);
      pt.setHocr(pageXml);
      pt.setCreatedAt(Instant.now());
      pageTextRepository.save(pt);

      if (advance) {
        stateMachine.advanceSinglePage(recordId, pageId);
      }

      log.info(
          "Imported Transkribus page: record={} seq={} model={} chars={} tool='{}'",
          recordId,
          seq,
          modelId,
          text.length(),
          ref.toolName());
      outcomes.add(
          new PageOutcome(ref.imageFileName(), recordId, seq, modelId, text.length(), "imported"));
    }
    return outcomes;
  }
}
