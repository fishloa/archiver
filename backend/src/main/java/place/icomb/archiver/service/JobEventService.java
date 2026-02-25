package place.icomb.archiver.service;

import java.io.IOException;
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

  private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

  /** Tracks connected workers by their self-assigned UUID. */
  private final ConcurrentHashMap<String, WorkerInfo> workers = new ConcurrentHashMap<>();

  private record WorkerInfo(SseEmitter emitter, List<String> kinds) {}

  public SseEmitter subscribe(String workerId, List<String> kinds) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
    emitters.add(emitter);

    // If this worker was already connected, evict the stale emitter
    if (workerId != null) {
      WorkerInfo old = workers.put(workerId, new WorkerInfo(emitter, kinds != null ? List.copyOf(kinds) : List.of()));
      if (old != null) {
        emitters.remove(old.emitter());
        try {
          old.emitter().complete();
        } catch (Exception ignored) {
        }
      }
    }

    Runnable cleanup =
        () -> {
          emitters.remove(emitter);
          if (workerId != null) {
            // Only remove if this emitter is still the current one for this worker
            workers.remove(workerId, new WorkerInfo(emitter, kinds != null ? List.copyOf(kinds) : List.of()));
          }
        };
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

  /** Backward-compatible subscribe without worker tracking. */
  public SseEmitter subscribe() {
    return subscribe(null, null);
  }

  public void jobEnqueued(String kind) {
    List<SseEmitter> dead = new java.util.ArrayList<>();
    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name("job").data(Map.of("kind", kind)));
      } catch (IOException e) {
        dead.add(emitter);
      }
    }
    emitters.removeAll(dead);
  }

  /**
   * Returns a map of job kind -> number of connected workers that handle that kind. Uses the
   * UUID-keyed worker map so each physical worker is counted exactly once, regardless of
   * reconnections.
   */
  public Map<String, Integer> getWorkerCounts() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (WorkerInfo info : workers.values()) {
      for (String kind : info.kinds()) {
        counts.merge(kind, 1, Integer::sum);
      }
    }
    return counts;
  }
}
