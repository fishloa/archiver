package place.icomb.archiver.ai;

/**
 * How a provider accepts more than one item of work.
 *
 * <p>This is not a boolean, and treating it as one is what made {@code maxBatchSize} ambiguous.
 * Three different mechanisms are already in use here, and the number means something different in
 * each: a thousand pages in one uploaded file, a hundred and twenty-eight strings in one request
 * body, and one item per request are not the same kind of "batch".
 */
public enum BatchStyle {

  /**
   * Upload a file of requests, create a job, poll it, download the output. Mistral's batch API.
   * {@code maxBatchSize} is how many requests go in the uploaded file.
   */
  ASYNC_JOB,

  /**
   * Many inputs in one request body, answered in one response. OpenAI-compatible embeddings. {@code
   * maxBatchSize} is how many inputs go in the array.
   */
  INLINE_ARRAY,

  /**
   * One item per request. Anything with no batch facility at all — a local Ollama server, or a chat
   * endpoint used one document at a time. {@code maxBatchSize} is necessarily 1.
   */
  SINGLE
}
