package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import place.icomb.archiver.service.PdfExportService.Variant;

/**
 * Tests error handling: exceptions during building, database errors during state updates, and
 * cleanup of orphaned files.
 */
class PdfExportWorkerFailureTest {

  @TempDir Path temp;

  private PdfExportQueue queue;
  private PdfExportService pdfExportService;
  private StorageService storageService;
  private PdfExportWorker worker;

  @BeforeEach
  void setup() {
    queue = mock(PdfExportQueue.class);
    pdfExportService = mock(PdfExportService.class);
    storageService = mock(StorageService.class);
    worker = new PdfExportWorker(queue, pdfExportService, storageService, 24, 0);

    // Common stubbing
    when(queue.claimNext())
        .thenReturn(
            Optional.of(
                new PdfExportQueue.Claimed("id-1", 1L, Variant.ORIGINAL, List.of(10L), "fp")));
    when(queue.fingerprint(1L, List.of(10L))).thenReturn("fp");
    when(queue.seqsOf(1L, List.of(10L))).thenReturn(List.of(1));
    when(storageService.freeBytes()).thenReturn(Long.MAX_VALUE);
    when(storageService.exportTempDir()).thenReturn(temp);
    when(storageService.newExportPath()).thenReturn("exports/aa/x.pdf");
  }

  @Test
  void anErrorDuringTheBuildFailsTheExportInsteadOfEscaping() throws Exception {
    when(pdfExportService.buildToFile(any(), any(), any(), any(), any()))
        .thenThrow(new OutOfMemoryError("Java heap space"));

    assertThat(worker.runOnce()).isTrue();

    verify(queue).markFailed(eq("id-1"), contains("heap"));
    assertThat(Files.list(temp).findAny()).isEmpty();
  }

  @Test
  void aFinishedFileIsDeletedIfTheRowCannotBeMarkedReady() throws Exception {
    doAnswer(
            invocation -> {
              Path target = invocation.getArgument(3);
              Files.writeString(target, "pdf-content");
              return 1;
            })
        .when(pdfExportService)
        .buildToFile(any(), any(), any(), any(), any());
    doThrow(new RuntimeException("db down"))
        .when(queue)
        .markReady(any(String.class), any(String.class), any(Long.class), any(Duration.class));

    assertThat(worker.runOnce()).isTrue();

    verify(storageService).deleteStoredFile("exports/aa/x.pdf");
    verify(queue).markFailed(eq("id-1"), any());
  }

  @Test
  void aDatabaseErrorWhileMarkingFailedDoesNotKillTheWorker() throws Exception {
    when(pdfExportService.buildToFile(any(), any(), any(), any(), any()))
        .thenThrow(new IOException("no scan"));
    doThrow(new RuntimeException("db down")).when(queue).markFailed(any(), any());

    assertThat(worker.runOnce()).isTrue();
  }
}
