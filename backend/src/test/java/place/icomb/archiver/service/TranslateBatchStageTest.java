package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import place.icomb.archiver.ai.Translator;
import place.icomb.archiver.model.Job;

/**
 * Pages that are never sent to the model.
 *
 * <p>A photograph comes back from Mistral OCR as figure references and nothing else. Sent to the
 * translator, record 4080's bed photograph came back as seven fictitious Prague birth, marriage and
 * death certificates — English that reads as a transcription of the archive and is not.
 */
class TranslateBatchStageTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final TranslateBatchStage stage =
      new TranslateBatchStage(
          mock(Translator.class), "translate_page", jdbc, mock(TranslationService.class));

  private Job page(String textRaw) {
    when(jdbc.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("text_raw", textRaw)));
    Job job = new Job();
    job.setPageId(151045L);
    return job;
  }

  @Test
  void aPhotographIsResolvedWithoutAskingTheModel() {
    Job job = page("![img-0.jpeg](img-0.jpeg)\n\n![img-1.jpeg](img-1.jpeg)");

    assertThat(stage.resolveLocally(job)).isTrue();
    verify(jdbc).update("UPDATE page_text SET text_en = '' WHERE page_id = ?", 151045L);
  }

  @Test
  void aPageWithWordsIsStillTranslated() {
    Job job = page("![img-0.jpeg](img-0.jpeg)\n\nPrag, 3. Mai 1940.");

    assertThat(stage.resolveLocally(job)).isFalse();
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }
}
