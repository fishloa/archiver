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
public class JobEventService {

  private static final Logger log = LoggerFactory.getLogger(JobEventService.class);
  private static final long SSE_TIMEOUT = 30 * 60 * 1000L; // 30 minutes

  private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

  public SseEmitter subscribe() {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
    emitters.add(emitter);
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(e -> emitters.remove(emitter));
    return emitter;
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
}
