package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import place.icomb.archiver.model.PageText;
import place.icomb.archiver.repository.PageTextRepository;

/**
 * What an import is not allowed to do to a page that already has text.
 *
 * <p>On 18 September 2026 {@code POST /api/admin/import-transkribus} was called with no document
 * id. It swept a Transkribus collection of nine documents back over the archive: record 4006 page
 * 28 fell from 3,406 characters of Mistral to 491 of Text Titan II — a handwriting model laid over
 * a typescript — and twenty-odd unchanged pages were rewritten identically, each one queueing a
 * translation, a PDF rebuild and an embedding that were charged for and changed nothing.
 *
 * <p>The three pages were recoverable only because the images are still there to read again. {@code
 * page_ocr_history} keeps the engine name and a character count, never the text.
 */
class TranskribusImportGuardTest {

  private final TranskribusTrpClient client = mock(TranskribusTrpClient.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final PageTextRepository pageTexts = mock(PageTextRepository.class);
  private final PipelineStateMachine stateMachine = mock(PipelineStateMachine.class);

  private final TranskribusImportService service =
      new TranskribusImportService(jdbc, pageTexts, stateMachine);

  private static final long PAGE_ID = 147299L;

  /** A PAGE XML whose Creator names the model, as Transkribus writes it. */
  private static String pageXml(String text) {
    return """
        <PcGts xmlns="http://schema.primaresearch.org/PAGE/gts/pagecontent/2013-07-15">
        <Metadata><Creator>prov=READ-COOP:name=TrHtr:model_id=579509</Creator></Metadata>
        <Page imageFilename="rec4006_seq0028.jpg"><TextRegion><TextLine>
        <TextEquiv><Unicode>%s</Unicode></TextEquiv></TextLine></TextRegion></Page></PcGts>
        """
        .formatted(text);
  }

  private static PageText stored(String engine, String text) {
    PageText pt = new PageText();
    pt.setPageId(PAGE_ID);
    pt.setEngine(engine);
    pt.setTextRaw(text);
    return pt;
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void transcriptIsOnOfferAndThePageExists() throws Exception {
    when(client.listTranscripts(anyInt(), anyLong()))
        .thenReturn(
            List.of(
                new TranskribusTrpClient.TranscriptRef(
                    1, "rec4006_seq0028.jpg", "https://transkribus.test/t/1", "TrHtr", 1L)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of(PAGE_ID));
  }

  private List<TranskribusImportService.PageOutcome> importWith(String incoming, boolean overwrite)
      throws Exception {
    when(client.fetchTranscript(anyString())).thenReturn(pageXml(incoming));
    return service.importDocument(client, 2516426, 18941356L, true, overwrite);
  }

  @Test
  void aShorterTranscriptFromAnotherEngineIsRefused() throws Exception {
    when(pageTexts.findCurrentByPageId(PAGE_ID))
        .thenReturn(Optional.of(stored("mistral-ocr", "x".repeat(3406))));

    var outcomes = importWith("y".repeat(491), false);

    assertThat(outcomes)
        .singleElement()
        .extracting("outcome")
        .asString()
        .contains("overwrite=true");
    verify(pageTexts, never()).save(any());
    verify(stateMachine, never()).advanceSinglePage(anyLong(), anyLong());
  }

  @Test
  void overwriteMeansIt() throws Exception {
    when(pageTexts.findCurrentByPageId(PAGE_ID))
        .thenReturn(Optional.of(stored("mistral-ocr", "x".repeat(3406))));

    var outcomes = importWith("y".repeat(491), true);

    assertThat(outcomes).singleElement().extracting("outcome").isEqualTo("imported");
    verify(pageTexts).save(any());
  }

  @Test
  void aLongerTranscriptFromAnotherEngineNeedsNoPermission() throws Exception {
    // The guard is about losing text, not about which engine wins.
    when(pageTexts.findCurrentByPageId(PAGE_ID))
        .thenReturn(Optional.of(stored("mistral-ocr", "x".repeat(100))));

    var outcomes = importWith("y".repeat(900), false);

    assertThat(outcomes).singleElement().extracting("outcome").isEqualTo("imported");
    verify(pageTexts).save(any());
  }

  @Test
  void reimportingTheSameTextChangesNothingAndCostsNothing() throws Exception {
    when(pageTexts.findCurrentByPageId(PAGE_ID))
        .thenReturn(Optional.of(stored("transkribus:579509", "Wien III., Metternichg. 10")));

    var outcomes = importWith("Wien III., Metternichg. 10", false);

    assertThat(outcomes)
        .singleElement()
        .extracting("outcome")
        .isEqualTo("skipped: already stored, unchanged");
    verify(pageTexts, never()).save(any());
    // The expensive half: advancing the page pays to reproduce what is already stored.
    verify(stateMachine, never()).advanceSinglePage(anyLong(), anyLong());
  }

  @Test
  void aCorrectionFromTheSameModelStillLands() throws Exception {
    // Re-running the same model after fixing the layout is the normal reason to import twice, and
    // the result is often shorter — a spurious region removed. Same engine, so no permission.
    when(pageTexts.findCurrentByPageId(PAGE_ID))
        .thenReturn(Optional.of(stored("transkribus:579509", "x".repeat(900))));

    var outcomes = importWith("corrected", false);

    assertThat(outcomes).singleElement().extracting("outcome").isEqualTo("imported");
    verify(pageTexts).save(any());
  }

  @Test
  void aPageWithNoTextYetIsImportedWithoutQuestion() throws Exception {
    when(pageTexts.findCurrentByPageId(PAGE_ID)).thenReturn(Optional.empty());

    var outcomes = importWith("first transcription", false);

    assertThat(outcomes).singleElement().extracting("outcome").isEqualTo("imported");
    verify(pageTexts).save(any());
  }
}
