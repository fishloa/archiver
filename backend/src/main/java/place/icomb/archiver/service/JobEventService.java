package place.icomb.archiver.service;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class JobEventService {

  private static final Logger log = LoggerFactory.getLogger(JobEventService.class);
  private static final long SSE_TIMEOUT = 30 * 60 * 1000L; // 30 minutes

  /** How long a worker is considered alive after its last API call. */
  private static final long WORKER_TTL_SECONDS = 60;

  private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

  /** Tracks workers by UUID — updated on every API call and SSE connect. */
  private final ConcurrentHashMap<String, WorkerEntry> workers = new ConcurrentHashMap<>();

  private record WorkerEntry(List<String> kinds, Instant lastSeen) {}

  /** How long a scraper is considered alive after its last heartbeat. */
  private static final long SCRAPER_TTL_SECONDS = 90;

  /** Tracks scrapers by ID — updated via heartbeat endpoint. */
  private final ConcurrentHashMap<String, ScraperEntry> scrapers = new ConcurrentHashMap<>();

  private record ScraperEntry(
      String sourceSystem,
      String sourceName,
      Instant lastSeen,
      long recordsIngested,
      long pagesIngested) {}

  // ---------------------------------------------------------------------------
  // Worker tracking (from any API call via X-Worker-Id / X-Worker-Kinds headers)
  // ---------------------------------------------------------------------------

  /** Called on every authenticated processor API request to track the worker. */
  public void touchWorker(String workerId, String kindsHeader) {
    if (workerId == null || workerId.isBlank()) return;
    List<String> kinds =
        (kindsHeader != null && !kindsHeader.isBlank())
            ? List.of(kindsHeader.split(","))
            : List.of();
    workers.put(workerId, new WorkerEntry(kinds, Instant.now()));
  }

  /**
   * Returns a map of job kind -> number of connected workers that handle that kind. Workers are
   * tracked by UUID and expire after WORKER_TTL_SECONDS of inactivity.
   */
  public Map<String, Integer> getWorkerCounts() {
    Instant cutoff = Instant.now().minusSeconds(WORKER_TTL_SECONDS);
    Map<String, Integer> counts = new LinkedHashMap<>();
    var it = workers.entrySet().iterator();
    while (it.hasNext()) {
      var entry = it.next();
      if (entry.getValue().lastSeen().isBefore(cutoff)) {
        it.remove();
        continue;
      }
      for (String kind : entry.getValue().kinds()) {
        counts.merge(kind, 1, Integer::sum);
      }
    }
    return counts;
  }

  // ---------------------------------------------------------------------------
  // Scraper tracking (via heartbeat endpoint)
  // ---------------------------------------------------------------------------

  /** Called by the heartbeat endpoint to track a running scraper. */
  public void touchScraper(
      String scraperId,
      String sourceSystem,
      String sourceName,
      long recordsIngested,
      long pagesIngested) {
    if (scraperId == null || scraperId.isBlank()) return;
    scrapers.put(
        scraperId,
        new ScraperEntry(sourceSystem, sourceName, Instant.now(), recordsIngested, pagesIngested));
  }

  /** Returns a list of active scrapers (those seen within SCRAPER_TTL_SECONDS). */
  public List<Map<String, Object>> getActiveScrapers() {
    Instant cutoff = Instant.now().minusSeconds(SCRAPER_TTL_SECONDS);
    List<Map<String, Object>> result = new java.util.ArrayList<>();
    var it = scrapers.entrySet().iterator();
    while (it.hasNext()) {
      var entry = it.next();
      if (entry.getValue().lastSeen().isBefore(cutoff)) {
        it.remove();
        continue;
      }
      var s = entry.getValue();
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("scraperId", entry.getKey());
      m.put("sourceSystem", s.sourceSystem());
      m.put("sourceName", s.sourceName());
      m.put("recordsIngested", s.recordsIngested());
      m.put("pagesIngested", s.pagesIngested());
      m.put("lastSeen", s.lastSeen().toString());
      result.add(m);
    }
    return result;
  }

  // ---------------------------------------------------------------------------
  // SSE event streaming
  // ---------------------------------------------------------------------------

  public SseEmitter subscribe(String workerId, List<String> kinds) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
    emitters.add(emitter);

    // Also track via worker map
    if (workerId != null && !workerId.isBlank()) {
      workers.put(
          workerId, new WorkerEntry(kinds != null ? List.copyOf(kinds) : List.of(), Instant.now()));
    }

    Runnable cleanup = () -> emitters.remove(emitter);
    emitter.onCompletion(cleanup);
    emitter.onTimeout(cleanup);
    emitter.onError(e -> cleanup.run());

    try {
      emitter.send(SseEmitter.event().comment("connected"));
    } catch (IOException e) {
      cleanup.run();
    }
    return emitter;
  }

  public SseEmitter subscribe() {
    return subscribe(null, null);
  }

  public void jobEnqueued(String kind) {
    List<SseEmitter> dead = new java.util.ArrayList<>();
    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name("job").data(Map.of("kind", kind)));
      } catch (Exception e) {
        dead.add(emitter);
      }
    }
    emitters.removeAll(dead);
  }
}
