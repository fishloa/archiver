package place.icomb.archiver.controller;

import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to responses.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring's own request errors keep their
 * status — an unknown URL is 404, a wrong method 405, a body that is not JSON 400, a path segment
 * of the wrong type 400, an unsupported media type 415 — instead of falling into the catch-all and
 * reading as a server fault. Every body has the shape the rest of the API uses: {@code error} and
 * {@code timestamp}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @Override
  protected ResponseEntity<Object> handleAsyncRequestTimeoutException(
      AsyncRequestTimeoutException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    // SSE connections time out normally — not an error, and the response is already committed
    return null;
  }

  /** Spring's own request errors, in the API's usual shape. */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String message =
        body instanceof org.springframework.http.ProblemDetail p && p.getDetail() != null
            ? p.getDetail()
            : String.valueOf(ex.getMessage());
    return ResponseEntity.status(status)
        .headers(headers)
        .body(Map.of("error", message, "timestamp", Instant.now().toString()));
  }

  /** A write that duplicates existing data, or lost a race: the caller can look again. */
  @ExceptionHandler({DuplicateKeyException.class, ConcurrencyFailureException.class})
  public ResponseEntity<Map<String, Object>> handleConflict(Exception ex) {
    log.warn("Conflicting write: {}", ex.toString());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            Map.of(
                "error",
                "The change conflicts with existing data or a concurrent change",
                "timestamp",
                Instant.now().toString()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleNotFound(IllegalArgumentException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(
            Map.of(
                "error", ex.getMessage(),
                "timestamp", Instant.now().toString()));
  }

  @ExceptionHandler(SecurityException.class)
  public ResponseEntity<Map<String, Object>> handleUnauthorized(SecurityException ex) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(
            Map.of(
                "error", ex.getMessage(),
                "timestamp", Instant.now().toString()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
    log.error("Unhandled exception", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", "Internal server error", "timestamp", Instant.now().toString()));
  }
}
