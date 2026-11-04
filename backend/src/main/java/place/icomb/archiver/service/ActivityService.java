package place.icomb.archiver.service;

import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import place.icomb.archiver.model.AppUser;

@Service
public class ActivityService {

  private static final Logger log = LoggerFactory.getLogger(ActivityService.class);

  public static final int RETENTION_DAYS = 730;
  public static final int SESSION_GAP_MINUTES = 30;
  private static final int MAX_TEXT = 500;

  private final JdbcClient jdbc;
  private final ThreadPoolExecutor writer =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(1000),
          r -> {
            Thread t = new Thread(r, "activity-writer");
            t.setDaemon(true);
            return t;
          });

  public ActivityService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @PreDestroy
  void shutdown() {
    writer.shutdown();
  }

  /** Queues a row; drops it if the queue is full. Never throws. */
  public void record(
      AppUser user, String method, String path, String query, String accept, int status) {
    OffsetDateTime at = OffsetDateTime.now();
    try {
      writer.execute(
          () -> {
            try {
              write(user, at, method, path, query, accept, status);
            } catch (RuntimeException e) {
              log.warn("could not record activity for user {}", user.getId(), e);
            }
          });
    } catch (RejectedExecutionException e) {
      log.warn("activity queue full, dropping event");
    }
  }

  private void write(
      AppUser user,
      OffsetDateTime at,
      String method,
      String path,
      String query,
      String accept,
      int status) {
    var event =
        ActivityClassifier.classify(method, path, query, accept, this::pageTarget).orElse(null);
    if (event == null) {
      return;
    }
    String email =
        jdbc.sql("SELECT email FROM app_user_email WHERE user_id = :id ORDER BY id LIMIT 1")
            .param("id", user.getId())
            .query(String.class)
            .optional()
            .orElse("user-" + user.getId());
    jdbc.sql(
            "INSERT INTO user_activity (user_id, email, at, method, path, query, status, kind,"
                + " target) VALUES (:uid, :email, :at, :method, :path, :query, :status, :kind,"
                + " :target)")
        .param("uid", user.getId())
        .param("email", email)
        .param("at", at)
        .param("method", method)
        .param("path", truncate(path))
        .param("query", query == null || query.isEmpty() ? null : truncate(query))
        .param("status", status)
        .param("kind", event.kind())
        .param("target", event.target() == null ? null : truncate(event.target()))
        .update();
  }

  private static String truncate(String s) {
    return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
  }

  private String pageTarget(Long pageId) {
    return jdbc.sql("SELECT record_id || '/' || seq FROM page WHERE id = :id")
        .param("id", pageId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  @Scheduled(cron = "0 17 3 * * *")
  public int purge() {
    int n =
        jdbc.sql("DELETE FROM user_activity WHERE at < now() - make_interval(days => :d)")
            .param("d", RETENTION_DAYS)
            .update();
    if (n > 0) {
      log.info("purged {} user_activity rows older than {} days", n, RETENTION_DAYS);
    }
    return n;
  }

  public List<Map<String, Object>> sessions(
      Long userId, OffsetDateTime from, OffsetDateTime to, int limit) {
    return jdbc.sql(
            """
            WITH ev AS (
              SELECT a.id, a.user_id, a.email, a.at, a.kind,
                     COALESCE(a.user_id::text, a.email) AS who,
                     lag(a.at) OVER (PARTITION BY COALESCE(a.user_id::text, a.email)
                                     ORDER BY a.at, a.id) AS prev
              FROM user_activity a
              WHERE (CAST(:uid AS bigint) IS NULL OR a.user_id = CAST(:uid AS bigint))
                AND (CAST(:from AS timestamptz) IS NULL OR a.at >= CAST(:from AS timestamptz))
                AND (CAST(:to AS timestamptz) IS NULL OR a.at < CAST(:to AS timestamptz))
            ), marked AS (
              SELECT *, CASE WHEN prev IS NULL
                              OR at - prev > make_interval(mins => :gap) THEN 1 ELSE 0 END AS fresh
              FROM ev
            ), numbered AS (
              SELECT *, sum(fresh) OVER (PARTITION BY who ORDER BY at, id) AS sess FROM marked
            )
            SELECT n.user_id, max(n.email) AS email, max(u.display_name) AS display_name,
                   min(n.at) AS started_at, max(n.at) AS ended_at,
                   EXTRACT(EPOCH FROM (max(n.at) - min(n.at)))::bigint AS duration_seconds,
                   count(*) AS event_count,
                   count(*) FILTER (WHERE n.kind IN ('view_record', 'view_page', 'view_person',
                                                     'search')) AS view_count,
                   count(*) FILTER (WHERE n.kind = 'edit') AS edit_count
            FROM numbered n LEFT JOIN app_user u ON u.id = n.user_id
            GROUP BY n.who, n.user_id, n.sess
            ORDER BY min(n.at) DESC
            LIMIT :limit
            """)
        .param("uid", userId)
        .param("from", from)
        .param("to", to)
        .param("gap", SESSION_GAP_MINUTES)
        .param("limit", limit)
        .query()
        .listOfRows();
  }

  public List<Map<String, Object>> events(
      Long userId, OffsetDateTime from, OffsetDateTime to, int limit) {
    return jdbc.sql(
            """
            SELECT id, user_id, email, at, method, path, query, status, kind, target
            FROM user_activity
            WHERE (CAST(:uid AS bigint) IS NULL OR user_id = CAST(:uid AS bigint))
              AND (CAST(:from AS timestamptz) IS NULL OR at >= CAST(:from AS timestamptz))
              AND (CAST(:to AS timestamptz) IS NULL OR at <= CAST(:to AS timestamptz))
            ORDER BY at DESC, id DESC
            LIMIT :limit
            """)
        .param("uid", userId)
        .param("from", from)
        .param("to", to)
        .param("limit", limit)
        .query()
        .listOfRows();
  }
}
