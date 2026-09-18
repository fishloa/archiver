package place.icomb.archiver.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import place.icomb.archiver.model.Job;
import place.icomb.archiver.repository.PageTextRepository;

/**
 * What happens after this engine finishes a page.
 *
 * <p>{@code POST /api/admin/reocr-page} asks for one page to be read again and then carried through
 * translation, the record's PDF and its embedding. That instruction travels in the job payload as
 * {@code andThen}, and it was honoured only by the Transkribus worker — so a page sent back to
 * Mistral was re-transcribed and then left with the translation of the text it had just replaced.
 * Record 4006 page 28 displayed an English "Unable to translate…" over 3,406 characters of
 * perfectly good German because of it.
 *
 * <p>The opposite case matters just as much: a record being transcribed for the first time enqueues
 * one job per page with no {@code andThen}, and firing the follow-on there would translate and
 * re-embed the whole record once per page.
 */
class OcrBatchStageCarryOnwardTest {

  private final PipelineStateMachine stateMachine = mock(PipelineStateMachine.class);
  private final PageTextRepository pageTexts = mock(PageTextRepository.class);

  private final OcrBatchStage stage =
      new OcrBatchStage(
          "mistral-ocr-latest",
          pageId -> java.nio.file.Path.of("/dev/null"),
          pageTexts,
          mock(JobService.class),
          stateMachine);

  private static final String RESULT =
      "{\"pages\":[{\"markdown\":\"Wien III., Metternichg. 10\"}]}";

  private Job job(String payload) {
    Job job = new Job();
    job.setId(1L);
    job.setKind("ocr_page_mistral");
    job.setRecordId(4006L);
    job.setPageId(147299L);
    job.setPayload(payload);
    return job;
  }

  private void apply(Job job) throws Exception {
    stage.applyResult(job, new ObjectMapper().readTree(RESULT));
  }

  @Test
  void aPageReReadOnDemandIsCarriedOnward() throws Exception {
    apply(job("{\"lang\":\"de\",\"andThen\":\"full\"}"));

    verify(stateMachine).advanceSinglePage(4006L, 147299L);
  }

  @Test
  void anOrdinaryPageOfAFirstPassIsNot() throws Exception {
    // The record-level state machine advances the record once every page has finished.
    apply(job("{\"lang\":\"de\"}"));

    verify(stateMachine, never()).advanceSinglePage(anyLong(), anyLong());
  }

  @Test
  void anExplicitNoneIsHonoured() throws Exception {
    apply(job("{\"lang\":\"de\",\"andThen\":\"none\"}"));

    verify(stateMachine, never()).advanceSinglePage(anyLong(), anyLong());
  }

  @Test
  void aJobWithNoPayloadIsNotCarriedOnward() throws Exception {
    apply(job(null));

    verify(stateMachine, never()).advanceSinglePage(anyLong(), anyLong());
  }

  @Test
  void aBrokenPayloadDoesNotFailTheJob() throws Exception {
    // The transcription is already saved; failing here would have the page read again at cost.
    apply(job("{not json"));

    verify(stateMachine, never()).advanceSinglePage(anyLong(), anyLong());
  }
}
