package place.icomb.archiver.service;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper around {@link java.net.http.HttpClient} with automatic retry and exponential backoff.
 *
 * <p>Retries on 5xx, 429 (rate-limit), timeouts, and transport errors. Does not retry on 4xx
 * (except 429) — those are thrown immediately.
 *
 * <pre>{@code
 * var http = ResilientHttpClient.builder()
 *     .connectTimeout(Duration.ofSeconds(10))
 *     .maxRetries(4)
 *     .build();
 *
 * HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
 * }</pre>
 */
public class ResilientHttpClient {

  private static final Logger log = LoggerFactory.getLogger(ResilientHttpClient.class);

  private static final List<Long> DEFAULT_BACKOFF_SECONDS = List.of(1L, 3L, 10L, 30L);

  private final HttpClient client;
  private final int maxRetries;
  private final List<Long> backoffSeconds;

  private ResilientHttpClient(HttpClient client, int maxRetries, List<Long> backoffSeconds) {
    this.client = client;
    this.maxRetries = maxRetries;
    this.backoffSeconds = backoffSeconds;
  }

  /**
   * Send a request with automatic retry on transient failures.
   *
   * @throws IOException after all retries are exhausted
   * @throws InterruptedException if the thread is interrupted during backoff sleep
   */
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
      throws IOException, InterruptedException {
    IOException lastException = null;

    for (int attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        HttpResponse<T> response = client.send(request, bodyHandler);
        int status = response.statusCode();

        // 4xx (except 429) — don't retry
        if (status >= 400 && status < 500 && status != 429) {
          return response;
        }

        // 2xx/3xx — success
        if (status < 400) {
          return response;
        }

        // 5xx or 429 — retry
        lastException =
            new IOException("HTTP " + status + " from " + request.method() + " " + request.uri());
        if (attempt < maxRetries) {
          long wait = backoff(attempt);
          log.warn(
              "{} {} attempt {}/{} failed (HTTP {}) — retrying in {}s",
              request.method(),
              request.uri(),
              attempt,
              maxRetries,
              status,
              wait);
          Thread.sleep(wait * 1000);
        }

      } catch (java.net.http.HttpTimeoutException e) {
        lastException = new IOException(e.getMessage(), e);
        if (attempt < maxRetries) {
          long wait = backoff(attempt);
          log.warn(
              "{} {} attempt {}/{} timed out — retrying in {}s",
              request.method(),
              request.uri(),
              attempt,
              maxRetries,
              wait);
          Thread.sleep(wait * 1000);
        }

      } catch (IOException e) {
        lastException = e;
        if (attempt < maxRetries) {
          long wait = backoff(attempt);
          log.warn(
              "{} {} attempt {}/{} failed ({}) — retrying in {}s",
              request.method(),
              request.uri(),
              attempt,
              maxRetries,
              e.getClass().getSimpleName(),
              wait);
          Thread.sleep(wait * 1000);
        }
      }
    }

    log.error(
        "{} {} failed after {} attempts: {}",
        request.method(),
        request.uri(),
        maxRetries,
        lastException != null ? lastException.getMessage() : "unknown");
    throw lastException != null ? lastException : new IOException("request failed");
  }

  private long backoff(int attempt) {
    int idx = Math.min(attempt - 1, backoffSeconds.size() - 1);
    return backoffSeconds.get(idx);
  }

  /** Returns the underlying {@link HttpClient} for cases that need direct access. */
  public HttpClient unwrap() {
    return client;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private Duration connectTimeout = Duration.ofSeconds(10);
    private int maxRetries = 4;
    private List<Long> backoffSeconds = DEFAULT_BACKOFF_SECONDS;

    public Builder connectTimeout(Duration timeout) {
      this.connectTimeout = timeout;
      return this;
    }

    public Builder maxRetries(int maxRetries) {
      this.maxRetries = maxRetries;
      return this;
    }

    public Builder backoffSeconds(List<Long> backoffSeconds) {
      this.backoffSeconds = backoffSeconds;
      return this;
    }

    public ResilientHttpClient build() {
      HttpClient client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
      return new ResilientHttpClient(client, maxRetries, backoffSeconds);
    }
  }
}
