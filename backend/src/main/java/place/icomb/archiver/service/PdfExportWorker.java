package place.icomb.archiver.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Builds PDF exports a person has asked for.
 *
 * <p>One export at a time per call: take the oldest queued one, check its pages are still the pages
 * that were asked for, build it to a file in the archive's own temp area, rename it into place, and
 * mark it ready. A failure marks it failed with the reason; the person asks again, which makes a
 * new export. Scheduling lives in {@code WorkerSchedulingConfig}.
 */
@Service
public class PdfExportWorker {

  private static final Logger log = LoggerFactory.getLogger(PdfExportWorker.class);

  /** A build still running after this is taken to be hung. */
  private static final Duration HUNG_AFTER = Duration.ofHours(2);

  private static final int MAX_ERROR = 500;

  private final PdfExportQueue queue;
  private final PdfExportService pdfExportService;
  private final StorageService storageService;
  private final Duration ttl;
  private long minFreeBytes;

  public PdfExportWorker(
      PdfExportQueue queue,
      PdfExportService pdfExportService,
      StorageService storageService,
      @Value("${archiver.pdf-export.ttl-hours:24}") long ttlHours,
      @Value("${archiver.pdf-export.min-free-bytes:2147483648}") long minFreeBytes) {
    this.queue = queue;
    this.pdfExportService = pdfExportService;
    this.storageService = storageService;
    this.ttl = Duration.ofHours(ttlHours);
    this.minFreeBytes = minFreeBytes;
  }

  /** Builds every queued export, one after another, until none is left. */
  public void drain() {
    while (runOnce()) {
      // keep going
    }
  }

  /**
   * Takes and builds one export.
   *
   * @return false when nothing was queued
   */
  public boolean runOnce() {
    Optional<PdfExportQueue.Claimed> claimed = queue.claimNext();
    if (claimed.isEmpty()) {
      return false;
    }
    PdfExportQueue.Claimed export = claimed.get();
    long started = System.currentTimeMillis();
    Path partial = null;
    try {
      if (storageService.freeBytes() < minFreeBytes) {
        throw new IOException("Not enough free space on the archive volume to build a PDF");
      }
      String now = queue.fingerprint(export.recordId(), export.pageIds());
      if (!now.equals(export.fingerprint())) {
        throw new IOException(
            "The pages changed after this export was requested; request it again");
      }
      List<Integer> seqs = queue.seqsOf(export.recordId(), export.pageIds());
      if (seqs.size() != export.pageIds().size()) {
        throw new IOException("Some of the requested pages are no longer in the record");
      }

      Path temp = storageService.exportTempDir();
      partial = temp.resolve(export.id() + ".pdf.partial");
      int pdfPages =
          pdfExportService.buildToFile(export.recordId(), seqs, export.variant(), partial, temp);

      long bytes = Files.size(partial);
      String address = storageService.newExportPath();
      storageService.placeExport(partial, address);
      partial = null;
      queue.markReady(export.id(), address, bytes, ttl);
      log.info(
          "PDF export {} ready: record={} variant={} pages={} bytes={} ({}ms)",
          export.id(),
          export.recordId(),
          export.variant(),
          pdfPages,
          bytes,
          System.currentTimeMillis() - started);
    } catch (Exception e) {
      log.error("PDF export {} failed: record={}", export.id(), export.recordId(), e);
      queue.markFailed(
          export.id(), abbreviate(e.getMessage() == null ? e.toString() : e.getMessage()));
    } finally {
      if (partial != null) {
        try {
          Files.deleteIfExists(partial);
        } catch (IOException ignored) {
          // The temp area is emptied at startup.
        }
      }
    }
    return true;
  }

  /**
   * Housekeeping: fail hung builds, expire finished exports, delete their files, purge old rows.
   */
  public void reap() {
    int hung = queue.failInterrupted(HUNG_AFTER);
    List<String> expired = queue.expireDue();
    expired.forEach(storageService::deleteStoredFile);
    int purged = queue.purgeOld();
    if (hung + expired.size() + purged > 0) {
      log.info(
          "PDF exports reaped: {} hung build(s) failed, {} expired, {} old row(s) purged",
          hung,
          expired.size(),
          purged);
    }
  }

  /**
   * At startup no build can be running in a fresh JVM, so any export still marked building was
   * interrupted, and anything in the temp area is debris.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void recoverAfterRestart() {
    int failed = queue.failAllBuilding("interrupted by a restart");
    storageService.clearExportTemp();
    if (failed > 0) {
      log.warn(
          "{} PDF export(s) were building when the backend stopped and have been failed", failed);
    }
  }

  static String abbreviate(String message) {
    if (message == null || message.isBlank()) {
      return "The PDF could not be built";
    }
    return message.length() <= MAX_ERROR ? message : message.substring(0, MAX_ERROR);
  }
}
