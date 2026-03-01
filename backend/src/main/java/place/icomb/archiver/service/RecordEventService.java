package place.icomb.archiver.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class RecordEventService {

  private static final Logger log = LoggerFactory.getLogger(RecordEventService.class);
  private static final long SSE_TIMEOUT = 30 * 60 * 1000L; // 30 minutes

  private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

  public SseEmitter subscribe() {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
    emitters.add(emitter);
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(e -> emitters.remove(emitter));
    // Send initial event to flush HTTP response headers
    try {
      emitter.send(SseEmitter.event().comment("connected"));
    } catch (IOException e) {
      emitters.remove(emitter);
    }
    return emitter;
  }

  public void broadcast(String event, Object data) {
    List<SseEmitter> dead = new java.util.ArrayList<>();
    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name(event).data(data));
      } catch (Exception e) {
        dead.add(emitter);
      }
    }
    emitters.removeAll(dead);
    if (!dead.isEmpty()) {
      log.debug("Removed {} dead SSE emitters, {} remaining", dead.size(), emitters.size());
    }
  }

  public void recordChanged(Long recordId, String action) {
    broadcast("record", Map.of("id", recordId, "action", action));
  }

  public void pipelineChanged(String kind, String status) {
    broadcast("record", Map.of("action", "pipeline", "kind", kind, "status", status));
  }
}
