package place.icomb.archiver.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import place.icomb.archiver.model.Job;

/**
 * Internal worker that embeds a record's text for semantic search.
 *
 * <p>Replaces the Python embed-worker. That worker reached the provider through its own
 * environment, and when the compose file and the stack drifted apart it was pointed at a different
 * model from the one the backend used for queries — bge-m3 serving passages while the backend sent
 * Qwen3's instruction prefix with every search. Both return 1024 dimensions and the endpoint
 * ignores the model field, so nothing failed; retrieval simply got worse. In-process, the query
 * side and the passage side read one configuration and cannot diverge.
 *
 * <p>Instances are created by {@link place.icomb.archiver.config.WorkerSchedulingConfig}.
 */
public class EmbedRecordWorker extends GenericWorker {

  private static final Logger log = LoggerFactory.getLogger(EmbedRecordWorker.class);
  private static final String JOB_KIND = "embed_record";

  /** Chunks per provider request. Sized for throughput; the client halves on a size refusal. */
  private static final int BATCH_SIZE = 128;

  private final JdbcTemplate jdbc;
  private final EmbeddingClient embeddings;

  public EmbedRecordWorker(
      String workerId,
      JobService jobService,
      JobEventService jobEventService,
      JdbcTemplate jdbc,
      EmbeddingClient embeddings) {
    super(jobService, jobEventService, JOB_KIND, workerId);
    this.jdbc = jdbc;
    this.embeddings = embeddings;
  }

  @Override
  protected Logger log() {
    return log;
  }

  @Override
  protected String model() {
    return embeddings.model();
  }

  @Override
  protected String providerUrl() {
    return embeddings.providerUrl();
  }

  /** A chunk on its way to the provider. */
  private record Pending(Long pageId, int index, String content, String heading) {}

  @Override
  protected void processJob(Job job) throws Exception {
    Long recordId = job.getRecordId();
    if (recordId == null) {
      throw new IllegalStateException("embed_record job " + job.getId() + " has no record");
    }

    List<Pending> chunks = new ArrayList<>();
    chunks.addAll(metadataChunks(recordId));
    chunks.addAll(pageChunks(recordId));

    if (chunks.isEmpty()) {
      log.info("Record {} has no text to embed", recordId);
      jdbc.update("DELETE FROM text_chunk WHERE record_id = ?", recordId);
      return;
    }

    List<float[]> vectors = new ArrayList<>(chunks.size());
    for (int from = 0; from < chunks.size(); from += BATCH_SIZE) {
      int to = Math.min(from + BATCH_SIZE, chunks.size());
      vectors.addAll(
          embeddings.embed(chunks.subList(from, to).stream().map(Pending::content).toList()));
    }

    store(recordId, chunks, vectors);
    log.info("Embedded record {}: {} chunks", recordId, chunks.size());
  }

  /**
   * The record's own catalogue text, embedded as its own chunk.
   *
   * <p>A search for a record's subject should find the record even when no single page states it.
   */
  private List<Pending> metadataChunks(Long recordId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT title, title_en, reference_code, description, description_en"
                + " FROM record WHERE id = ?",
            recordId);
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<String, Object> r = rows.get(0);
    StringBuilder text = new StringBuilder();
    String title = str(r.get("title_en")).isBlank() ? str(r.get("title")) : str(r.get("title_en"));
    if (!title.isBlank()) {
      text.append("Title: ").append(title).append('\n');
    }
    if (!str(r.get("reference_code")).isBlank()) {
      text.append("Reference: ").append(str(r.get("reference_code"))).append('\n');
    }
    String description =
        str(r.get("description_en")).isBlank()
            ? str(r.get("description"))
            : str(r.get("description_en"));
    if (!description.isBlank()) {
      text.append("Description: ").append(description).append('\n');
    }
    if (text.toString().isBlank()) {
      return List.of();
    }

    List<Pending> out = new ArrayList<>();
    int i = 0;
    for (String piece : TextChunker.chunkText(text.toString())) {
      out.add(new Pending(null, i++, piece, ""));
    }
    return out;
  }

  /**
   * Every page's transcription, chunked.
   *
   * <p>The original text is embedded rather than the English translation: the model is
   * cross-lingual, so an English query retrieves a German page directly, and waiting for a
   * translation would leave a page unsearchable until it arrived.
   */
  private List<Pending> pageChunks(Long recordId) {
    List<Map<String, Object>> pages =
        jdbc.queryForList(
            """
            SELECT p.id AS page_id, pt.text_raw, pt.text_en, pt.content_type
            FROM page p JOIN page_text pt ON pt.page_id = p.id
            WHERE p.record_id = ?
            ORDER BY p.seq
            """,
            recordId);

    List<Pending> out = new ArrayList<>();
    for (Map<String, Object> page : pages) {
      String text = str(page.get("text_raw"));
      if (text.isBlank()) {
        text = str(page.get("text_en"));
      }
      if (text.isBlank()) {
        continue;
      }
      Long pageId = ((Number) page.get("page_id")).longValue();
      String contentType = str(page.get("content_type"));
      int i = 0;
      for (TextChunker.Chunk chunk : TextChunker.chunkDocument(text, contentType)) {
        out.add(new Pending(pageId, i++, chunk.content(), chunk.heading()));
      }
    }
    return out;
  }

  /**
   * Replaces the record's chunks in one transaction, so a search never sees a half-embedded record.
   */
  private void store(Long recordId, List<Pending> chunks, List<float[]> vectors) {
    if (chunks.size() != vectors.size()) {
      throw new IllegalStateException(
          "Got " + vectors.size() + " vectors for " + chunks.size() + " chunks");
    }
    jdbc.update("DELETE FROM text_chunk WHERE record_id = ?", recordId);
    for (int i = 0; i < chunks.size(); i++) {
      Pending c = chunks.get(i);
      jdbc.update(
          """
          INSERT INTO text_chunk (record_id, page_id, chunk_index, content, heading, embedding,
                                  created_at)
          VALUES (?, ?, ?, ?, ?, ?::halfvec, now())
          """,
          recordId,
          c.pageId(),
          c.index(),
          c.content(),
          c.heading(),
          toVector(vectors.get(i)));
    }
  }

  private static String toVector(float[] values) {
    StringBuilder sb = new StringBuilder(values.length * 8).append('[');
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(values[i]);
    }
    return sb.append(']').toString();
  }

  private static String str(Object o) {
    return o == null ? "" : o.toString();
  }
}
