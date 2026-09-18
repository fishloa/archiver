package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Carrying one re-transcribed page through the rest of the pipeline.
 *
 * <p>Against a real database on purpose. The first version of {@code advanceSinglePage} wrote a
 * {@code pipeline_event.event} of "page_retranscribed", which reads perfectly well and which the
 * CHECK constraint on that column rejects. Nothing caught it until it aborted the import of 27
 * pages that had already been transcribed at a credit each — the text was written, the insert
 * threw, and the remaining pages were left untouched. No unit test can catch that, because the
 * constraint lives in the schema.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class AdvanceSinglePageTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private PipelineStateMachine stateMachine;
  @Autowired private JdbcClient jdbc;

  /** A complete record with one page, as a re-transcription would find it. */
  private long[] seedRecord(String lang) {
    Long archiveId =
        jdbc.sql(
                """
                INSERT INTO archive (name, country) VALUES ('test archive', 'AT')
                RETURNING id
                """)
            .query(Long.class)
            .single();
    Long recordId =
        jdbc.sql(
                """
                INSERT INTO record (archive_id, source_system, source_record_id, title, status,
                                    lang, metadata_lang, translation_quality)
                VALUES (?, 'test', ?, 'seeded', 'complete', ?, 'de', 'bulk')
                RETURNING id
                """)
            .params(archiveId, "rec-" + System.nanoTime(), lang)
            .query(Long.class)
            .single();
    Long attachmentId =
        jdbc.sql(
                """
                INSERT INTO attachment (record_id, role, path, mime)
                VALUES (?, 'page', 'test/p1.jpg', 'image/jpeg')
                RETURNING id
                """)
            .params(recordId)
            .query(Long.class)
            .single();
    Long pageId =
        jdbc.sql(
                """
                INSERT INTO page (record_id, seq, attachment_id) VALUES (?, 1, ?)
                RETURNING id
                """)
            .params(recordId, attachmentId)
            .query(Long.class)
            .single();
    jdbc.sql(
            """
            INSERT INTO page_text (page_id, engine, text_raw, content_type)
            VALUES (?, 'transkribus:579509', 'Wien III., Metternichg. 10', 'text/plain')
            """)
        .params(pageId)
        .update();
    return new long[] {recordId, pageId};
  }

  @Test
  void writesEventsTheSchemaAccepts() {
    long[] ids = seedRecord("de");

    assertThatCode(() -> stateMachine.advanceSinglePage(ids[0], ids[1])).doesNotThrowAnyException();

    List<String> events =
        jdbc.sql("SELECT stage || '/' || event FROM pipeline_event WHERE record_id = ?")
            .params(ids[0])
            .query(String.class)
            .list();
    assertThat(events).contains("ocr/completed", "pdf_build/started");
  }

  @Test
  void enqueuesTranslationForThatPageAndRebuildsTheRecord() {
    long[] ids = seedRecord("de");

    stateMachine.advanceSinglePage(ids[0], ids[1]);

    List<String> jobs =
        jdbc.sql("SELECT kind || ':' || coalesce(page_id::text, '-') FROM job WHERE record_id = ?")
            .params(ids[0])
            .query(String.class)
            .list();

    // The page that changed is translated; the PDF and the embedding are record-level, because the
    // text layer and the chunks both include this page.
    assertThat(jobs)
        .contains("translate_page:" + ids[1], "build_searchable_pdf:-", "embed_record:-");
  }

  @Test
  void englishContentIsCopiedRatherThanTranslated() {
    long[] ids = seedRecord("en");

    stateMachine.advanceSinglePage(ids[0], ids[1]);

    String textEn =
        jdbc.sql("SELECT text_en FROM page_text WHERE page_id = ?")
            .params(ids[1])
            .query(String.class)
            .single();
    assertThat(textEn).isEqualTo("Wien III., Metternichg. 10");

    List<String> kinds =
        jdbc.sql("SELECT kind FROM job WHERE record_id = ?")
            .params(ids[0])
            .query(String.class)
            .list();
    assertThat(kinds).doesNotContain("translate_page");
  }

  @Test
  void manyPagesOfOneRecordQueueTheRecordLevelWorkOnce() {
    // A 27-page import enqueued 27 PDF rebuilds and 27 re-embeddings of the same records before
    // this guard existed. Both jobs cover the whole record, and embedding is charged per record.
    long[] first = seedRecord("de");
    long recordId = first[0];
    Long attachmentId =
        jdbc.sql(
                "INSERT INTO attachment (record_id, role, path, mime)"
                    + " VALUES (?, 'page', 'test/p2.jpg', 'image/jpeg') RETURNING id")
            .params(recordId)
            .query(Long.class)
            .single();
    Long secondPage =
        jdbc.sql("INSERT INTO page (record_id, seq, attachment_id) VALUES (?, 2, ?) RETURNING id")
            .params(recordId, attachmentId)
            .query(Long.class)
            .single();
    jdbc.sql(
            "INSERT INTO page_text (page_id, engine, text_raw, content_type)"
                + " VALUES (?, 'transkribus:579509', 'zweite Seite', 'text/plain')")
        .params(secondPage)
        .update();

    stateMachine.advanceSinglePage(recordId, first[1]);
    stateMachine.advanceSinglePage(recordId, secondPage);

    Long pdfJobs =
        jdbc.sql("SELECT count(*) FROM job WHERE record_id = ? AND kind = 'build_searchable_pdf'")
            .params(recordId)
            .query(Long.class)
            .single();
    Long embedJobs =
        jdbc.sql("SELECT count(*) FROM job WHERE record_id = ? AND kind = 'embed_record'")
            .params(recordId)
            .query(Long.class)
            .single();
    Long translateJobs =
        jdbc.sql("SELECT count(*) FROM job WHERE record_id = ? AND kind = 'translate_page'")
            .params(recordId)
            .query(Long.class)
            .single();

    assertThat(pdfJobs).isEqualTo(1);
    assertThat(embedJobs).isEqualTo(1);
    // Translation is per page, so both pages are queued.
    assertThat(translateJobs).isEqualTo(2);
  }

  @Test
  void aStaleTranslationIsRemovedBeforeTheNewOneIsQueued() {
    long[] ids = seedRecord("de");
    jdbc.sql(
            """
            INSERT INTO page_translation (page_id, model, text_en)
            VALUES (?, 'stale-engine', 'Vienna - Neustadt - Klippipark')
            """)
        .params(ids[1])
        .update();

    stateMachine.advanceSinglePage(ids[0], ids[1]);

    // A translation of the transcription we have just replaced would otherwise sit alongside the
    // new one and could win on retrieval — which is how a wrong reading survives its own
    // correction.
    Long remaining =
        jdbc.sql("SELECT count(*) FROM page_translation WHERE page_id = ?")
            .params(ids[1])
            .query(Long.class)
            .single();
    assertThat(remaining).isZero();
  }
}
