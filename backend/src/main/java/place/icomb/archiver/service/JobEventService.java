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

  /** Tracks which job kinds each connected worker handles. */
  private final ConcurrentHashMap<SseEmitter, List<String>> workerKinds =
      new ConcurrentHashMap<>();

  public SseEmitter subscribe(List<String> kinds) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
    emitters.add(emitter);
    if (kinds != null && !kinds.isEmpty()) {
      workerKinds.put(emitter, List.copyOf(kinds));
    }
    Runnable cleanup =
        () -> {
          emitters.remove(emitter);
          workerKinds.remove(emitter);
        };
    emitter.onCompletion(cleanup);
    emitter.onTimeout(cleanup);
    emitter.onError(e -> cleanup.run());
    // Send initial event to flush HTTP response headers
    try {
      emitter.send(SseEmitter.event().comment("connected"));
    } catch (IOException e) {
      cleanup.run();
    }
    return emitter;
  }

  /** Backward-compatible subscribe without kinds. */
  public SseEmitter subscribe() {
    return subscribe(null);
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
    for (SseEmitter e : dead) {
      emitters.remove(e);
      workerKinds.remove(e);
    }
  }

  /**
   * Returns a map of job kind -> number of connected workers that handle that kind. A worker
   * handling multiple kinds (e.g. translate_page + translate_record) is counted once per kind.
   */
  public Map<String, Integer> getWorkerCounts() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (List<String> kinds : workerKinds.values()) {
      for (String kind : kinds) {
        counts.merge(kind, 1, Integer::sum);
      }
    }
    return counts;
  }
}
