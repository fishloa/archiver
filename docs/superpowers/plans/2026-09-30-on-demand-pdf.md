# On-Demand PDFs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every PDF the archive produces is built on demand by a background worker and downloaded when ready, through one asynchronous mechanism, so a 942-page record is no different from a three-page extract.

**Architecture:** A `pdf_export` table holds requests (record, variant, page ids, a fingerprint of what those pages were). A `PdfExportQueue` service does all the SQL; a `PdfExportWorker` claims a queued export with `SKIP LOCKED`, builds it to a file through a scratch directory under the storage root, renames it into `exports/{xx}/{uuid}.pdf`, and marks it ready for 24 hours. Three endpoints (create, status, file) replace the two synchronous ones, and one Svelte button serves every download point in the viewer.

**Tech Stack:** Java 25 / Spring Boot 4.1, Spring `JdbcTemplate`, Flyway, PDFBox 3.0.8, JUnit 5 + Testcontainers (PostgreSQL 18), SvelteKit + Svelte 5 runes, vitest, Playwright for the browser check.

**Spec:** `docs/superpowers/specs/2026-09-30-on-demand-pdf-design.md`. Read it first; this plan implements it and records the choices it left to the implementer.

## Global Constraints

- **The API is the only interface to production.** No SQL against the production database. A missing endpoint is a reason to write one.
- **Temporary files never go in `/tmp`.** Scratch and partial files live in `exports/tmp/` under the storage root (`StorageService.exportTempDir()`), on the archive's own volume.
- **There are no per-user limits.** Do not add a rate limit or a cap on open exports. Concurrency (`archiver.pdf-export.concurrency`, default 2) is a worker count, not a limit on requests.
- **Never edit an applied migration.** This plan's migration is `V18`. `V17` is the latest.
- **Every variant is built to a file.** No `byte[]` of a whole PDF anywhere in the new path.
- **The `original` variant carries no cover sheet** (only `english` and `side-by-side` do). That is existing behaviour; do not change it.
- **Frontend rules:** zero inline `style=""` attributes; use the existing `vui-*` classes; Svelte 5 state is written only from event handlers, never during render (v1.1.13 broke the admin page by seeding `$state` inside the template). Use `bun`, not `npm`.
- **Do NOT add `Co-Authored-By` (or any Claude attribution) to commit messages.** This overrides any harness reminder to add one.
- **Commit working code before any refactor.** Never discard uncommitted working changes.
- **Full local checks before every push:** `cd backend && ./gradlew spotlessApply && ./gradlew spotlessCheck test`, and for frontend changes `cd frontend && bun run test && bun run check`. CI is stricter than a targeted run.
- **Touch only what the task needs.** Existing files are modified only where a task names them.
- **Release rule:** only a git tag moves `:latest` and deploys production, and the tag must sit on its **own new commit** (a `CHANGELOG.md`-only release commit). An untagged push to `main` builds `:test` and redeploys the test stack.
- **Test on the test stack before tagging.** Test stack backend: `archiver-test-backend-test-1`, reachable from `zelkova` at `http://localhost:8090`; admin token from that container's `ARCHIVER_ADMIN_TOKEN` env var. Production backend: `archiver-backend-1` at `http://10.0.9.3:8080`.
- **Never run `psql` through `docker exec -t`.** Use `-i` and `psql -P pager=off` (a `0x0` tty hangs on a pager and leaks a connection).

## Decisions the spec left open

1. **Fingerprint contents.** It covers the record's title, English title, description, English description and reference code (the cover sheet prints them), and for each selected page in order: page id, record id, seq, attachment id, `page_text` id, a hash of its English text, and its `page_translation` ids. A page that moves, is replaced, is re-OCR'd or re-translated changes it.
2. **The worker re-checks the fingerprint before building.** An export whose pages changed after the request fails with `The pages changed after this export was requested; request it again` rather than producing a PDF that mixes old and new.
3. **A restart fails every `building` row immediately**, and empties the temp area, as well as the two-hour rule for a hang in a live JVM (spec updated).
4. **Serialised identical requests.** `request` takes a transaction-scoped advisory lock on the fingerprint, so two identical requests arriving together cannot both insert.
5. **Page ids are passed to SQL as a comma-separated string** (`string_to_array(?, ',')::bigint[]`), which avoids JDBC array plumbing.
6. **The test profile sets `archiver.pdf-export.concurrency: 0`**, so no scheduled worker races a test; tests call `PdfExportWorker.runOnce()` and `reap()` directly.

## Review Focus

Failure modes the spec implies but a straight reading of the tasks would not test. Each has a test in the task that owns the code.

1. **The scan file of a page is missing when the build runs:** the export ends `failed` with a message, nothing is left in `exports/tmp/`, and it is not stuck `building`. *(Task 4)*
2. **An identical request arrives while one is building:** it joins the running export instead of starting a second build of a 600 MB file. *(Tasks 3, 5)*
3. **A page is edited between the request and the build:** the export fails with "changed" and never produces a mixed PDF. *(Task 4)*
4. **A link is followed after the 24 hours:** `410`, not `404` or `500`; the file is deleted by the reaper; the row is purged seven days later. *(Tasks 4, 5)*
5. **The backend restarts during a build:** at startup the `building` row is failed and the temp area emptied, so nothing is `building` for ever. *(Task 4)*

## File Structure

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V18__pdf_export.sql` (create) | The `pdf_export` table. |
| `backend/src/main/java/place/icomb/archiver/service/StorageService.java` (modify) | The exports area: temp dir, addresses, placing, free space. |
| `backend/src/main/java/place/icomb/archiver/service/PdfExportService.java` (modify) | `buildToFile`; later the `byte[]` API is removed. |
| `backend/src/main/java/place/icomb/archiver/service/PdfExportQueue.java` (create) | All SQL for exports: request, claim, finish, fail, expire, purge, fingerprint. |
| `backend/src/main/java/place/icomb/archiver/service/PdfExportWorker.java` (create) | Builds one claimed export; reaps; recovers after a restart. |
| `backend/src/main/java/place/icomb/archiver/controller/PdfExportController.java` (create) | The three endpoints. |
| `backend/src/main/java/place/icomb/archiver/config/WorkerSchedulingConfig.java` (modify) | Registers the workers and the reaper; pool size. |
| `backend/src/main/java/place/icomb/archiver/service/IngestService.java` (modify) | `deleteRecord` removes the record's export files. |
| `backend/src/main/java/place/icomb/archiver/controller/FileController.java` (modify) | The two synchronous PDF endpoints are removed. |
| `backend/src/main/java/place/icomb/archiver/controller/ApiController.java`, `mcp/ArchiverMcpTools.java` (modify) | The PDF link becomes the export address. |
| `backend/src/test/java/place/icomb/archiver/PdfFixtures.java` (create) | Records, pages and real scans for tests that build PDFs. |
| `backend/src/test/resources/application-test.yml` (modify) | `pdf-export.concurrency: 0`, `min-free-bytes: 0`. |
| `frontend/src/lib/pdf-export.ts` (create), `frontend/tests/pdf-export.test.ts` (create) | The client logic as pure functions, tested. |
| `frontend/src/lib/components/PdfExportButton.svelte` (create) | The one download button. |
| `frontend/src/routes/records/[id]/+page.svelte`, `.../pages/[seq]/+page.svelte`, `frontend/src/lib/messages/{en,de,cs}.ts` (modify) | Use the button; new strings. |
| `.claude/skills/archiver-api/SKILL.md`, `CHANGELOG.md` (modify) | Documentation, release. |

---

### Task 1: The exports area in `StorageService`

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/service/StorageService.java`
- Test: `backend/src/test/java/place/icomb/archiver/service/StorageServiceTest.java`

**Interfaces:**
- Produces (used by Tasks 2, 4, 6):
  - `Path exportTempDir()` — `<root>/exports/tmp`, created if absent.
  - `void clearExportTemp()` — empties that directory, keeps it.
  - `String newExportPath()` — `exports/{xx}/{uuid}.pdf`.
  - `void placeExport(Path finished, String relativePath)` — atomic rename into place.
  - `Path exportFile(String relativePath)` — the file of a finished export.
  - `long freeBytes()` — usable bytes on the archive's filesystem.

- [ ] **Step 1: Write the failing tests**

Append inside `StorageServiceTest` (before its final `}`):

```java
  // --- the exports area --------------------------------------------------------------------

  @Test
  void theExportTempAreaIsUnderTheStorageRootNotTmp(@TempDir Path root) {
    StorageService service = new StorageService(root, (Path) null);

    Path dir = service.exportTempDir();

    assertThat(dir).isEqualTo(root.resolve("exports/tmp")).isDirectory();
  }

  @Test
  void clearingTheExportTempAreaEmptiesItAndKeepsIt(@TempDir Path root) throws Exception {
    StorageService service = new StorageService(root, (Path) null);
    Path dir = service.exportTempDir();
    Files.writeString(dir.resolve("a.partial"), "x");
    Files.createDirectories(dir.resolve("nested"));
    Files.writeString(dir.resolve("nested/b.tmp"), "y");

    service.clearExportTemp();

    assertThat(dir).isDirectory();
    try (var entries = Files.list(dir)) {
      assertThat(entries).isEmpty();
    }
  }

  @Test
  void anExportAddressIsShardedUniqueAndSaysNothingAboutTheRecord(@TempDir Path root) {
    StorageService service = new StorageService(root, (Path) null);

    String first = service.newExportPath();
    String second = service.newExportPath();

    assertThat(first).matches("exports/[0-9a-f]{2}/[0-9a-f-]{36}\\.pdf");
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void aFinishedExportIsRenamedIntoPlaceFromTheTempArea(@TempDir Path root) throws Exception {
    StorageService service = new StorageService(root, (Path) null);
    Path finished = service.exportTempDir().resolve("done.pdf.partial");
    Files.writeString(finished, "pdf bytes");
    String address = service.newExportPath();

    service.placeExport(finished, address);

    assertThat(finished).doesNotExist();
    assertThat(Files.readString(service.exportFile(address))).isEqualTo("pdf bytes");
    assertThat(service.exportFile(address)).isEqualTo(root.resolve(address));
  }

  @Test
  void freeSpaceIsReportedForTheArchiveVolume(@TempDir Path root) {
    assertThat(new StorageService(root, (Path) null).freeBytes()).isPositive();
  }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*StorageServiceTest' 2>&1 | grep -E "FAILED|error:|BUILD"`
Expected: compilation FAILS (`exportTempDir`, `clearExportTemp`, `newExportPath`, `placeExport`, `exportFile`, `freeBytes` do not exist).

- [ ] **Step 3: Implement**

In `StorageService.java`, add before `private void writeFile`:

```java
  /**
   * Where a PDF export's scratch files and unfinished output live.
   *
   * <p>Under the storage root, on the archive's own volume, rather than in {@code /tmp}: the size of
   * the largest export is then bounded by the disk, not by the container's scratch space.
   */
  public Path exportTempDir() {
    Path dir = storageRoot.resolve("exports/tmp");
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create " + dir, e);
    }
    return dir;
  }

  /** Empties the export temp area. Only safe when no build can be running, that is at startup. */
  public void clearExportTemp() {
    Path dir = exportTempDir();
    try (var entries = Files.list(dir)) {
      for (Path entry : (Iterable<Path>) entries::iterator) {
        deleteTree(entry);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to empty " + dir, e);
    }
  }

  /** A fresh address for a finished export: exports/{xx}/{uuid}.pdf. Says nothing about the record. */
  public String newExportPath() {
    String name = java.util.UUID.randomUUID().toString();
    return "exports/" + name.substring(0, 2) + "/" + name + ".pdf";
  }

  /** Moves a finished file from the temp area to its address: a rename, on one filesystem. */
  public void placeExport(Path finished, String relativePath) {
    Path target = storageRoot.resolve(relativePath);
    try {
      Files.createDirectories(target.getParent());
      Files.move(finished, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to place " + relativePath, e);
    }
  }

  /** The file of a finished export. */
  public Path exportFile(String relativePath) {
    return storageRoot.resolve(relativePath);
  }

  /** Usable bytes on the filesystem that holds the archive. */
  public long freeBytes() {
    try {
      return Files.getFileStore(storageRoot).getUsableSpace();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read free space for " + storageRoot, e);
    }
  }

  private static void deleteTree(Path path) throws IOException {
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc)
              throws IOException {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./gradlew spotlessApply -q && ./gradlew test --tests '*StorageServiceTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "storage: an exports area under the storage root, with its own temp directory

PDF exports need scratch space the size of their output. PDFBox defaults to /tmp, which in the
backend container may be small; the exports area puts it on the archive's own volume, where the
finished file can be renamed into place."
```

---

### Task 2: Build every variant to a file

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/service/PdfExportService.java`
- Create: `backend/src/test/java/place/icomb/archiver/PdfFixtures.java`
- Test: `backend/src/test/java/place/icomb/archiver/service/PdfExportServiceTest.java`

**Interfaces:**
- Consumes: `StorageService.exportTempDir()` (Task 1) — by the caller, not this task.
- Produces (used by Task 4):
  - `int PdfExportService.buildToFile(Long recordId, List<Integer> seqNumbers, Variant variant, Path target, Path scratchDir) throws IOException` — builds any variant straight to `target`, backed by PDFBox scratch files in `scratchDir`; returns the PDF page count; throws `IOException("No valid pages found for the given selection")` when nothing rendered.
  - `PdfFixtures.archive(JdbcTemplate)`, `.record(JdbcTemplate, long archiveId, String title)`, `.page(JdbcTemplate, Path storageRoot, long recordId, int seq, String english)` — each returns the new id (Tasks 3–7 reuse them).

- [ ] **Step 1: Create the fixtures**

Create `backend/src/test/java/place/icomb/archiver/PdfFixtures.java`:

```java
package place.icomb.archiver;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.jdbc.core.JdbcTemplate;

/** Records, pages and real scans for tests that build or request PDFs. */
public final class PdfFixtures {

  private PdfFixtures() {}

  public static long archive(JdbcTemplate jdbc) {
    return jdbc.queryForObject(
        "INSERT INTO archive (name) VALUES (?) RETURNING id",
        Long.class,
        "Fixture Archive " + UUID.randomUUID());
  }

  /** A complete record with no pages yet. */
  public static long record(JdbcTemplate jdbc, long archiveId, String title) {
    return jdbc.queryForObject(
        "INSERT INTO record (archive_id, source_system, source_record_id, title, lang,"
            + " metadata_lang, status) VALUES (?, 'test', ?, ?, 'de', 'de', 'complete')"
            + " RETURNING id",
        Long.class,
        archiveId,
        "fixture-" + UUID.randomUUID(),
        title);
  }

  /**
   * A real 400x600 scan on disk, its attachment and page, and a finished transcription and
   * translation. The export decodes the image, so a placeholder byte array would not do.
   *
   * @return the page id
   */
  public static long page(
      JdbcTemplate jdbc, Path storageRoot, long recordId, int seq, String english)
      throws IOException {
    BufferedImage scan = new BufferedImage(400, 600, BufferedImage.TYPE_INT_RGB);
    var g = scan.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, 400, 600);
    g.dispose();

    String name = UUID.randomUUID().toString();
    String relative = "attachments/" + name.substring(0, 2) + "/" + name + ".jpg";
    Path file = storageRoot.resolve(relative);
    Files.createDirectories(file.getParent());
    try (var out = Files.newOutputStream(file)) {
      ImageIO.write(scan, "jpg", out);
    }

    long attachment =
        jdbc.queryForObject(
            "INSERT INTO attachment (record_id, role, path, mime, bytes)"
                + " VALUES (?, 'page_image', ?, 'image/jpeg', ?) RETURNING id",
            Long.class,
            recordId,
            relative,
            Files.size(file));
    long page =
        jdbc.queryForObject(
            "INSERT INTO page (record_id, seq, attachment_id, width, height)"
                + " VALUES (?, ?, ?, 400, 600) RETURNING id",
            Long.class,
            recordId,
            seq,
            attachment);
    jdbc.update(
        "INSERT INTO page_text (page_id, engine, text_raw, text_en, content_type)"
            + " VALUES (?, 'ocr_page_mistral', ?, ?, 'text/markdown')",
        page,
        "Text " + seq,
        english);
    jdbc.update(
        "INSERT INTO page_translation (page_id, model, text_en) VALUES (?, 'test-model', ?)",
        page,
        english);
    jdbc.update(
        "UPDATE record SET page_count = (SELECT count(*) FROM page WHERE record_id = ?),"
            + " attachment_count = (SELECT count(*) FROM attachment WHERE record_id = ?)"
            + " WHERE id = ?",
        recordId,
        recordId,
        recordId);
    return page;
  }
}
```

- [ ] **Step 2: Write the failing tests**

In `PdfExportServiceTest.java`, add these imports if absent: `java.nio.file.Files`, `java.nio.file.Path` (already imported), `org.junit.jupiter.api.io.TempDir` (not needed). Append inside the class before its final `}`:

```java
  // --- building to a file ------------------------------------------------------------------

  private Path scratch() throws Exception {
    return Files.createTempDirectory("pdf-scratch");
  }

  @Test
  void everyVariantCanBeBuiltStraightToAFile() throws Exception {
    for (PdfExportService.Variant variant : PdfExportService.Variant.values()) {
      Path dir = scratch();
      Path out = dir.resolve(variant + ".pdf");

      int pages = pdfExportService.buildToFile(recordId, List.of(1), variant, out, dir);

      assertThat(out).exists();
      assertThat(pages).as(variant.toString()).isGreaterThanOrEqualTo(1);
      try (PDDocument doc = Loader.loadPDF(out.toFile())) {
        assertThat(doc.getNumberOfPages()).as(variant.toString()).isEqualTo(pages);
      }
    }
  }

  @Test
  void theFileBuildCarriesTheSameContentAsTheInMemoryBuild() throws Exception {
    Path dir = scratch();
    Path out = dir.resolve("english.pdf");

    pdfExportService.buildToFile(recordId, List.of(1), PdfExportService.Variant.ENGLISH, out, dir);

    assertThat(textOf(Files.readAllBytes(out))).contains("Expropriation of the Czech nobility");
  }

  @Test
  void scratchFilesGoToTheDirectoryGivenAndAreGoneAfterwards() throws Exception {
    Path dir = scratch();
    Path out = dir.resolve("original.pdf");

    pdfExportService.buildToFile(recordId, List.of(1), PdfExportService.Variant.ORIGINAL, out, dir);

    try (var entries = Files.list(dir)) {
      assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("original.pdf");
    }
  }

  @Test
  void aSelectionThatNamesNoPageIsAnErrorNotAnEmptyFile() throws Exception {
    Path dir = scratch();
    Path out = dir.resolve("none.pdf");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                pdfExportService.buildToFile(
                    recordId, List.of(99), PdfExportService.Variant.ORIGINAL, out, dir))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("No valid pages");
  }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*PdfExportServiceTest' 2>&1 | grep -E "FAILED|error:|BUILD"`
Expected: compilation FAILS (`buildToFile` does not exist).

- [ ] **Step 4: Refactor the builders and add `buildToFile`**

In `PdfExportService.java`:

(a) Add imports: `org.apache.pdfbox.io.MemoryUsageSetting`, `org.apache.pdfbox.io.RandomAccessStreamCache`, `org.apache.pdfbox.io.ScratchFile`.

(b) Add, just after the `Variant` enum:

```java
  /** Draws a variant into an open document. */
  @FunctionalInterface
  private interface Render {
    void into(PDDocument doc) throws IOException;
  }

  /** Runs a render into a fresh in-memory document and returns its bytes. */
  private byte[] toBytes(Render render) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      render.into(doc);
      if (doc.getNumberOfPages() == 0) {
        throw new IOException("No valid pages found for the given selection");
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }

  /**
   * Builds any variant of the selected pages straight to a file.
   *
   * <p>The document is backed by PDFBox scratch files in {@code scratchDir}, and saved without
   * ever being held as one array, so a 942-page record of full-resolution scans is no different
   * from a three-page extract. The caller chooses the scratch directory: the archive's own volume,
   * not {@code /tmp}.
   *
   * @return the number of PDF pages written
   */
  public int buildToFile(
      Long recordId, List<Integer> seqNumbers, Variant variant, Path target, Path scratchDir)
      throws IOException {
    RandomAccessStreamCache.StreamCacheCreateFunction cache =
        () -> new ScratchFile(MemoryUsageSetting.setupTempFileOnly().setTempDir(scratchDir.toFile()));
    try (PDDocument doc = new PDDocument(cache)) {
      switch (variant) {
        case ORIGINAL -> renderOriginal(doc, recordId, seqNumbers);
        case ENGLISH -> renderEnglish(doc, recordId, seqNumbers);
        case SIDE_BY_SIDE -> renderSideBySide(doc, recordId, seqNumbers);
      }
      if (doc.getNumberOfPages() == 0) {
        throw new IOException("No valid pages found for the given selection");
      }
      doc.save(target.toFile());
      return doc.getNumberOfPages();
    }
  }
```

(c) Turn `buildEnglishPdf` into `renderEnglish`. Replace its signature and opening `try (...) {` line:

```java
  private byte[] buildEnglishPdf(Long recordId, List<Integer> seqNumbers) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      MarkdownPdfRenderer renderer = newRenderer(doc);
```

with:

```java
  private byte[] buildEnglishPdf(Long recordId, List<Integer> seqNumbers) throws IOException {
    return toBytes(doc -> renderEnglish(doc, recordId, seqNumbers));
  }

  private void renderEnglish(PDDocument doc, Long recordId, List<Integer> seqNumbers)
      throws IOException {
    {
      MarkdownPdfRenderer renderer = newRenderer(doc);
```

and delete the method's tail, which reads:

```java

      if (doc.getNumberOfPages() == 0) {
        throw new IOException("No valid pages found for the given selection");
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }
```

replacing it with just `    }\n  }`. (The bare `{ … }` block keeps the body's indentation and braces balanced, the same way `renderOriginal` is already written.)

(d) Do the same for `buildSideBySidePdf`. Replace its signature plus the lines up to and including the first `try`/renderer lines:

```java
  private byte[] buildSideBySidePdf(Long recordId, List<Integer> seqNumbers) throws IOException {
    final float margin = 28f;
    final float gutter = 16f;
    final float footerHeight = 22f;

    try (PDDocument doc = new PDDocument()) {
      MarkdownPdfRenderer renderer = newRenderer(doc);
```

with:

```java
  private byte[] buildSideBySidePdf(Long recordId, List<Integer> seqNumbers) throws IOException {
    return toBytes(doc -> renderSideBySide(doc, recordId, seqNumbers));
  }

  private void renderSideBySide(PDDocument doc, Long recordId, List<Integer> seqNumbers)
      throws IOException {
    final float margin = 28f;
    final float gutter = 16f;
    final float footerHeight = 22f;

    {
      MarkdownPdfRenderer renderer = newRenderer(doc);
```

and delete its identical tail (`if (doc.getNumberOfPages() == 0) { throw … } ByteArrayOutputStream out … return out.toByteArray();`), leaving `    }\n  }`.

(e) Make `buildOriginalPdf` a one-liner. Replace its whole body so it reads:

```java
  private byte[] buildOriginalPdf(Long recordId, List<Integer> seqNumbers) throws IOException {
    return toBytes(doc -> renderOriginal(doc, recordId, seqNumbers));
  }
```

- [ ] **Step 5: Run the whole PDF test class**

Run: `cd backend && ./gradlew spotlessApply -q && ./gradlew test --tests '*PdfExportServiceTest' 2>&1 | grep -E "FAILED|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`. Every existing test still passes because `buildPdf` still returns the same bytes through `toBytes`, and the four new tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "pdf: build any variant straight to a file through a scratch directory

The English and side-by-side builders assembled the whole document on the heap, and so did the
original one when asked for a page range. All three now render into an open document, and
buildToFile saves it to a file backed by PDFBox scratch files in a directory the caller chooses.
The byte[] API is kept for now, implemented on the same render methods."
```

---

### Task 3: The table, and the queue that owns all its SQL

**Files:**
- Create: `backend/src/main/resources/db/migration/V18__pdf_export.sql`
- Create: `backend/src/main/java/place/icomb/archiver/service/PdfExportQueue.java`
- Test: `backend/src/test/java/place/icomb/archiver/service/PdfExportQueueTest.java` (create)

**Interfaces:**
- Consumes: `PdfExportService.Variant`, `PdfExportService.parsePageRange(String)` (existing); `PdfFixtures` (Task 2).
- Produces (used by Tasks 4–7), all on `PdfExportQueue`:
  - `record View(String id, String state, int pageCount, String variant, Long bytes, Instant expiresAt, String error)`
  - `record Requested(View view, boolean created)`
  - `record Claimed(String id, long recordId, Variant variant, List<Long> pageIds, String fingerprint)`
  - `record Row(String id, long recordId, String state, String path, Variant variant, String error)`
  - `static Variant parseVariant(String)` — `original | english | side-by-side` (null/blank → ORIGINAL; unknown → `IllegalArgumentException`)
  - `static String fileSuffix(Variant)` — `-original | -english | -original-and-english`
  - `List<Long> pageIdsFor(long recordId, String pages)` — blank `pages` = every page in order; a malformed range throws `IllegalArgumentException`
  - `String fingerprint(long recordId, List<Long> pageIds)`
  - `Requested request(long recordId, Variant variant, List<Long> pageIds)`
  - `Optional<View> find(String id)`, `Optional<Row> row(String id)` — both return empty for an id that is not a UUID
  - `Optional<Claimed> claimNext()`
  - `List<Integer> seqsOf(long recordId, List<Long> pageIds)`
  - `void markReady(String id, String path, long bytes, Duration ttl)`, `void markFailed(String id, String error)`
  - `int failAllBuilding(String reason)`, `int failInterrupted(Duration olderThan)`
  - `List<String> expireDue()` — marks due exports `expired`, returns their file paths
  - `int purgeOld()` — deletes `expired`/`failed` rows older than seven days
  - `List<String> pathsForRecord(long recordId)`

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/place/icomb/archiver/service/PdfExportQueueTest.java`:

```java
package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.PdfFixtures;
import place.icomb.archiver.service.PdfExportService.Variant;

/**
 * Requests, reuse, claiming and expiry — everything the queue decides, without building a PDF.
 *
 * <p>The fingerprint is what stops an old export being served for changed pages, so most of this
 * is about what changes it and what must not.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class PdfExportQueueTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private PdfExportQueue queue;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Path storageRoot;

  private long record;
  private List<Long> pages;

  @BeforeEach
  void seed() throws Exception {
    jdbc.execute("DELETE FROM pdf_export");
    jdbc.execute("DELETE FROM record");
    long archive = PdfFixtures.archive(jdbc);
    record = PdfFixtures.record(jdbc, archive, "Lagebericht");
    pages =
        List.of(
            PdfFixtures.page(jdbc, storageRoot, record, 1, "One"),
            PdfFixtures.page(jdbc, storageRoot, record, 2, "Two"),
            PdfFixtures.page(jdbc, storageRoot, record, 3, "Three"));
  }

  private PdfExportQueue.Requested request() {
    return queue.request(record, Variant.ORIGINAL, pages);
  }

  private void state(String id, String state) {
    jdbc.update("UPDATE pdf_export SET state = ? WHERE id = ?::uuid", state, id);
  }

  // --- selecting pages ---------------------------------------------------------------------

  @Test
  void noSelectionMeansEveryPageInOrder() {
    assertThat(queue.pageIdsFor(record, null)).containsExactlyElementsOf(pages);
    assertThat(queue.pageIdsFor(record, "  ")).containsExactlyElementsOf(pages);
  }

  @Test
  void aRangeSelectsThosePagesByIdInOrder() {
    assertThat(queue.pageIdsFor(record, "1,3")).containsExactly(pages.get(0), pages.get(2));
    assertThat(queue.pageIdsFor(record, "2-3")).containsExactly(pages.get(1), pages.get(2));
  }

  @Test
  void aRangeNamingNoPageSelectsNothing() {
    assertThat(queue.pageIdsFor(record, "99")).isEmpty();
  }

  @Test
  void aMalformedRangeIsRefused() {
    assertThatThrownBy(() -> queue.pageIdsFor(record, "a-b"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theVariantSpellingsTheApiAcceptsAreParsed() {
    assertThat(PdfExportQueue.parseVariant(null)).isEqualTo(Variant.ORIGINAL);
    assertThat(PdfExportQueue.parseVariant("english")).isEqualTo(Variant.ENGLISH);
    assertThat(PdfExportQueue.parseVariant("side-by-side")).isEqualTo(Variant.SIDE_BY_SIDE);
    assertThat(PdfExportQueue.parseVariant("SideBySide")).isEqualTo(Variant.SIDE_BY_SIDE);
    assertThatThrownBy(() -> PdfExportQueue.parseVariant("pdf"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- requesting and reuse ----------------------------------------------------------------

  @Test
  void aNewRequestIsQueued() {
    PdfExportQueue.Requested r = request();

    assertThat(r.created()).isTrue();
    assertThat(r.view().state()).isEqualTo("queued");
    assertThat(r.view().pageCount()).isEqualTo(3);
    assertThat(r.view().variant()).isEqualTo("original");
    assertThat(r.view().bytes()).isNull();
  }

  @Test
  void anIdenticalRequestJoinsOneThatIsQueuedOrBuilding() {
    PdfExportQueue.Requested first = request();
    assertThat(request().view().id()).isEqualTo(first.view().id());

    state(first.view().id(), "building");
    PdfExportQueue.Requested joined = request();

    assertThat(joined.created()).isFalse();
    assertThat(joined.view().id()).isEqualTo(first.view().id());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pdf_export", Long.class)).isEqualTo(1L);
  }

  @Test
  void aFinishedExportIsReusedUntilItExpires() {
    String id = request().view().id();
    queue.markReady(id, "exports/aa/x.pdf", 10, Duration.ofHours(24));

    PdfExportQueue.Requested again = request();
    assertThat(again.created()).isFalse();
    assertThat(again.view().id()).isEqualTo(id);
    assertThat(again.view().state()).isEqualTo("ready");
    assertThat(again.view().bytes()).isEqualTo(10L);
    assertThat(again.view().expiresAt()).isNotNull();

    jdbc.update("UPDATE pdf_export SET expires_at = now() - interval '1 minute'");
    assertThat(request().created()).isTrue();
  }

  @Test
  void aDifferentVariantOrSelectionIsADifferentExport() {
    String original = request().view().id();

    assertThat(queue.request(record, Variant.ENGLISH, pages).view().id()).isNotEqualTo(original);
    assertThat(queue.request(record, Variant.ORIGINAL, pages.subList(0, 2)).view().id())
        .isNotEqualTo(original);
  }

  @Test
  void aFailedExportIsNotReused() {
    String id = request().view().id();
    queue.markFailed(id, "boom");

    assertThat(request().created()).isTrue();
  }

  // --- what makes an export stale ----------------------------------------------------------

  @Test
  void theFingerprintIsStableWhenNothingChanges() {
    assertThat(queue.fingerprint(record, pages)).isEqualTo(queue.fingerprint(record, pages));
  }

  @Test
  void changingTheEnglishTextChangesTheFingerprint() {
    String before = queue.fingerprint(record, pages);
    jdbc.update("UPDATE page_text SET text_en = 'Changed' WHERE page_id = ?", pages.get(1));
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);
  }

  @Test
  void aNewTranslationChangesTheFingerprint() {
    String before = queue.fingerprint(record, pages);
    jdbc.update(
        "INSERT INTO page_translation (page_id, model, text_en) VALUES (?, 'better', 'x')",
        pages.get(0));
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);
  }

  @Test
  void aReOcrdPageChangesTheFingerprint() {
    String before = queue.fingerprint(record, pages);
    jdbc.update("DELETE FROM page_text WHERE page_id = ?", pages.get(2));
    jdbc.update(
        "INSERT INTO page_text (page_id, engine, text_raw, text_en) VALUES (?, 'other', 'r', 'Three')",
        pages.get(2));
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);
  }

  @Test
  void aReplacedScanChangesTheFingerprint() {
    String before = queue.fingerprint(record, pages);
    long other =
        jdbc.queryForObject(
            "INSERT INTO attachment (record_id, role, path) VALUES (?, 'page_image', 'attachments/zz/new.jpg') RETURNING id",
            Long.class,
            record);
    jdbc.update("UPDATE page SET attachment_id = ? WHERE id = ?", other, pages.get(0));
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);
  }

  @Test
  void aMovedOrRenumberedPageChangesTheFingerprint() {
    String before = queue.fingerprint(record, pages);
    jdbc.update("UPDATE page SET seq = 9 WHERE id = ?", pages.get(2));
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);

    jdbc.update("UPDATE page SET seq = 3 WHERE id = ?", pages.get(2));
    long other = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Other");
    jdbc.update("UPDATE page SET record_id = ? WHERE id = ?", other, pages.get(2));
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);
  }

  @Test
  void theCoverSheetsRecordFieldsAreInTheFingerprint() {
    String before = queue.fingerprint(record, pages);
    jdbc.update("UPDATE record SET title_en = 'A title' WHERE id = ?", record);
    assertThat(queue.fingerprint(record, pages)).isNotEqualTo(before);
  }

  // --- claiming and finishing --------------------------------------------------------------

  @Test
  void claimingTakesTheOldestQueuedExportAndMarksItBuilding() {
    String first = request().view().id();
    queue.request(record, Variant.ENGLISH, pages);

    PdfExportQueue.Claimed claimed = queue.claimNext().orElseThrow();

    assertThat(claimed.id()).isEqualTo(first);
    assertThat(claimed.recordId()).isEqualTo(record);
    assertThat(claimed.variant()).isEqualTo(Variant.ORIGINAL);
    assertThat(claimed.pageIds()).containsExactlyElementsOf(pages);
    assertThat(queue.find(first).orElseThrow().state()).isEqualTo("building");
    assertThat(queue.claimNext().orElseThrow().id()).isNotEqualTo(first);
    assertThat(queue.claimNext()).isEmpty();
  }

  @Test
  void readyRecordsTheFileSizeAndExpiry() {
    String id = request().view().id();
    queue.markReady(id, "exports/aa/x.pdf", 1234, Duration.ofHours(24));

    PdfExportQueue.Row row = queue.row(id).orElseThrow();
    assertThat(row.state()).isEqualTo("ready");
    assertThat(row.path()).isEqualTo("exports/aa/x.pdf");
    assertThat(queue.find(id).orElseThrow().bytes()).isEqualTo(1234L);
    long hours =
        jdbc.queryForObject(
            "SELECT round(extract(epoch FROM expires_at - now()) / 3600) FROM pdf_export",
            Long.class);
    assertThat(hours).isEqualTo(24L);
  }

  @Test
  void failedRecordsTheErrorAndAnUnknownOrMalformedIdIsSimplyAbsent() {
    String id = request().view().id();
    queue.markFailed(id, "no scan");

    assertThat(queue.find(id).orElseThrow().error()).isEqualTo("no scan");
    assertThat(queue.find("not-a-uuid")).isEmpty();
    assertThat(queue.row("00000000-0000-0000-0000-000000000000")).isEmpty();
  }

  @Test
  void seqsAreResolvedFromPageIdsInOrder() {
    assertThat(queue.seqsOf(record, List.of(pages.get(2), pages.get(0)))).containsExactly(1, 3);
  }

  // --- expiry and recovery -----------------------------------------------------------------

  @Test
  void dueExportsAreExpiredAndTheirFilesReturned() {
    String id = request().view().id();
    queue.markReady(id, "exports/aa/x.pdf", 10, Duration.ofHours(24));
    jdbc.update("UPDATE pdf_export SET expires_at = now() - interval '1 second'");

    assertThat(queue.expireDue()).containsExactly("exports/aa/x.pdf");
    assertThat(queue.find(id).orElseThrow().state()).isEqualTo("expired");
    assertThat(queue.expireDue()).isEmpty();
  }

  @Test
  void anExportStillInDateIsNotExpired() {
    queue.markReady(request().view().id(), "exports/aa/x.pdf", 10, Duration.ofHours(24));
    assertThat(queue.expireDue()).isEmpty();
  }

  @Test
  void aBuildThatHasRunTooLongIsFailedAsInterrupted() {
    String id = request().view().id();
    queue.claimNext();
    jdbc.update("UPDATE pdf_export SET started_at = now() - interval '3 hours'");

    assertThat(queue.failInterrupted(Duration.ofHours(2))).isEqualTo(1);

    assertThat(queue.find(id).orElseThrow().state()).isEqualTo("failed");
    assertThat(queue.find(id).orElseThrow().error()).isEqualTo("interrupted");
  }

  @Test
  void aRecentBuildIsLeftAlone() {
    request();
    queue.claimNext();
    assertThat(queue.failInterrupted(Duration.ofHours(2))).isZero();
  }

  @Test
  void aRestartFailsEveryBuildingExportAtOnce() {
    String id = request().view().id();
    queue.claimNext();

    assertThat(queue.failAllBuilding("interrupted by a restart")).isEqualTo(1);

    assertThat(queue.find(id).orElseThrow().error()).isEqualTo("interrupted by a restart");
  }

  @Test
  void oldExpiredAndFailedRowsArePurgedButRecentOnesAreKept() {
    String expired = request().view().id();
    queue.markReady(expired, "exports/aa/x.pdf", 1, Duration.ofHours(24));
    jdbc.update("UPDATE pdf_export SET expires_at = now() - interval '1 second'");
    queue.expireDue();
    String failed = queue.request(record, Variant.ENGLISH, pages).view().id();
    queue.markFailed(failed, "x");
    jdbc.update(
        "UPDATE pdf_export SET created_at = now() - interval '8 days' WHERE id = ?::uuid", expired);

    assertThat(queue.purgeOld()).isEqualTo(1);

    assertThat(queue.find(expired)).isEmpty();
    assertThat(queue.find(failed)).isPresent();
  }

  @Test
  void aRecordsExportFilesAreListedForDeletion() {
    String id = request().view().id();
    queue.markReady(id, "exports/aa/x.pdf", 1, Duration.ofHours(24));
    queue.request(record, Variant.ENGLISH, pages);

    assertThat(queue.pathsForRecord(record)).containsExactly("exports/aa/x.pdf");
  }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*PdfExportQueueTest' 2>&1 | grep -E "FAILED|error:|BUILD"`
Expected: compilation FAILS (`PdfExportQueue` does not exist).

- [ ] **Step 3: Create the migration**

Create `backend/src/main/resources/db/migration/V18__pdf_export.sql`:

```sql
-- PDF exports: a request for a PDF, built later by a worker and downloaded when it is ready.
--
-- Its own table rather than the pipeline's job table: completing a pipeline job calls the state
-- machine, and an export has no business anywhere near that.
--
-- fingerprint says what the pages WERE when the export was requested (their ids, scans, text and
-- translations, and the record's cover-sheet fields). An export is reused only while the same
-- pages still produce the same fingerprint.
CREATE TABLE pdf_export (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id   bigint NOT NULL REFERENCES record(id) ON DELETE CASCADE,
    variant     text   NOT NULL CHECK (variant IN ('original', 'english', 'side_by_side')),
    page_ids    bigint[] NOT NULL,
    fingerprint text   NOT NULL,
    state       text   NOT NULL DEFAULT 'queued'
                CHECK (state IN ('queued', 'building', 'ready', 'failed', 'expired')),
    path        text,
    bytes       bigint,
    error       text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    started_at  timestamptz,
    finished_at timestamptz,
    expires_at  timestamptz
);

-- Claiming and the reaper only ever look at the few rows still in flight.
CREATE INDEX idx_pdf_export_open ON pdf_export (state) WHERE state IN ('queued', 'building');
CREATE INDEX idx_pdf_export_record ON pdf_export (record_id);
```

- [ ] **Step 4: Create the queue**

Create `backend/src/main/java/place/icomb/archiver/service/PdfExportQueue.java`:

```java
package place.icomb.archiver.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import place.icomb.archiver.service.PdfExportService.Variant;

/**
 * Every statement that touches {@code pdf_export}: requesting, reusing, claiming, finishing,
 * expiring.
 *
 * <p>Page ids go to SQL as a comma-separated string and come back the same way, which avoids JDBC
 * array plumbing for no loss.
 */
@Service
public class PdfExportQueue {

  /** What a caller sees of an export. */
  public record View(
      String id,
      String state,
      int pageCount,
      String variant,
      Long bytes,
      Instant expiresAt,
      String error) {}

  /** A request's outcome: the export, and whether this call created it. */
  public record Requested(View view, boolean created) {}

  /** An export a worker has taken. */
  public record Claimed(
      String id, long recordId, Variant variant, List<Long> pageIds, String fingerprint) {}

  /** The columns the file endpoint needs. */
  public record Row(
      String id, long recordId, String state, String path, Variant variant, String error) {}

  private static final String VIEW_SQL =
      "SELECT id::text AS id, state, cardinality(page_ids) AS page_count, variant, bytes,"
          + " expires_at, error FROM pdf_export";

  private static final RowMapper<View> VIEW =
      (rs, n) -> {
        Timestamp expires = rs.getTimestamp("expires_at");
        return new View(
            rs.getString("id"),
            rs.getString("state"),
            rs.getInt("page_count"),
            apiName(fromDb(rs.getString("variant"))),
            (Long) rs.getObject("bytes"),
            expires == null ? null : expires.toInstant(),
            rs.getString("error"));
      };

  private final JdbcTemplate jdbc;
  private final PdfExportService pdfExportService;

  public PdfExportQueue(JdbcTemplate jdbc, PdfExportService pdfExportService) {
    this.jdbc = jdbc;
    this.pdfExportService = pdfExportService;
  }

  // --- variants ----------------------------------------------------------------------------

  static String dbName(Variant v) {
    return switch (v) {
      case ORIGINAL -> "original";
      case ENGLISH -> "english";
      case SIDE_BY_SIDE -> "side_by_side";
    };
  }

  static Variant fromDb(String s) {
    return switch (s) {
      case "english" -> Variant.ENGLISH;
      case "side_by_side" -> Variant.SIDE_BY_SIDE;
      default -> Variant.ORIGINAL;
    };
  }

  /** The API's spelling: original, english or side-by-side. */
  static String apiName(Variant v) {
    return v == Variant.SIDE_BY_SIDE ? "side-by-side" : dbName(v);
  }

  /** Reads the API's spelling; nothing given means the scans. */
  public static Variant parseVariant(String s) {
    if (s == null || s.isBlank()) {
      return Variant.ORIGINAL;
    }
    return switch (s.trim().toLowerCase(Locale.ROOT)) {
      case "original" -> Variant.ORIGINAL;
      case "english" -> Variant.ENGLISH;
      case "side-by-side", "sidebyside", "side_by_side" -> Variant.SIDE_BY_SIDE;
      default -> throw new IllegalArgumentException("Unknown variant: " + s);
    };
  }

  /** Named for what the file contains, so a folder of exports reads without opening them. */
  public static String fileSuffix(Variant v) {
    return switch (v) {
      case ORIGINAL -> "-original";
      case ENGLISH -> "-english";
      case SIDE_BY_SIDE -> "-original-and-english";
    };
  }

  // --- selecting pages and fingerprinting them ---------------------------------------------

  /**
   * The ids of the pages a selection names, in page order.
   *
   * @param pages a range like {@code 1,3,5-10}; blank means every page
   * @throws IllegalArgumentException if the range is malformed
   */
  public List<Long> pageIdsFor(long recordId, String pages) {
    if (pages == null || pages.isBlank()) {
      return jdbc.queryForList(
          "SELECT id FROM page WHERE record_id = ? ORDER BY seq", Long.class, recordId);
    }
    List<Integer> seqs = pdfExportService.parsePageRange(pages);
    if (seqs.isEmpty()) {
      return List.of();
    }
    String marks = String.join(", ", Collections.nCopies(seqs.size(), "?"));
    List<Object> args = new ArrayList<>();
    args.add(recordId);
    args.addAll(seqs);
    return jdbc.queryForList(
        "SELECT id FROM page WHERE record_id = ? AND seq IN (" + marks + ") ORDER BY seq",
        Long.class,
        args.toArray());
  }

  /**
   * What these pages are right now: an md5 over the record's cover-sheet fields and, for each page
   * in order, its id, record, position, scan, text, a hash of its English text, and its
   * translations. Re-OCR, a new translation, a replaced scan or a moved page all change it.
   */
  public String fingerprint(long recordId, List<Long> pageIds) {
    return jdbc.queryForObject(
        """
        SELECT md5(
          coalesce((SELECT md5(concat_ws('|', r.title, r.title_en, r.description,
                                         r.description_en, r.reference_code))
                    FROM record r WHERE r.id = ?), '')
          || coalesce(string_agg(
               concat_ws(':', p.id, p.record_id, p.seq, p.attachment_id,
                         coalesce(pt.id::text, '-'),
                         md5(coalesce(pt.text_en, '')),
                         coalesce((SELECT string_agg(t.id::text, ',' ORDER BY t.id)
                                   FROM page_translation t WHERE t.page_id = p.id), '-')),
               '|' ORDER BY sel.ord), ''))
        FROM unnest(string_to_array(?, ',')::bigint[]) WITH ORDINALITY AS sel(page_id, ord)
        JOIN page p ON p.id = sel.page_id
        LEFT JOIN page_text pt ON pt.page_id = p.id
        """,
        String.class,
        recordId,
        csv(pageIds));
  }

  private static String csv(List<Long> ids) {
    return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
  }

  private static List<Long> ids(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    return java.util.Arrays.stream(csv.split(",")).map(Long::valueOf).toList();
  }

  /** The positions of the given pages within a record, in order. */
  public List<Integer> seqsOf(long recordId, List<Long> pageIds) {
    return jdbc.queryForList(
        "SELECT seq FROM page WHERE record_id = ?"
            + " AND id IN (SELECT unnest(string_to_array(?, ',')::bigint[])) ORDER BY seq",
        Integer.class,
        recordId,
        csv(pageIds));
  }

  // --- requesting --------------------------------------------------------------------------

  /**
   * Asks for an export, reusing an identical one when there is one.
   *
   * <p>Identical means the same record, variant and fingerprint, and either still queued or
   * building, or finished and not yet expired. Serialised on the fingerprint, so two identical
   * requests arriving together cannot both insert.
   */
  @Transactional
  public Requested request(long recordId, Variant variant, List<Long> pageIds) {
    String fingerprint = fingerprint(recordId, pageIds);
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", fingerprint);

    List<View> reusable =
        jdbc.query(
            VIEW_SQL
                + " WHERE record_id = ? AND variant = ? AND fingerprint = ?"
                + " AND (state IN ('queued', 'building')"
                + "      OR (state = 'ready' AND expires_at > now()))"
                + " ORDER BY created_at DESC LIMIT 1",
            VIEW,
            recordId,
            dbName(variant),
            fingerprint);
    if (!reusable.isEmpty()) {
      return new Requested(reusable.get(0), false);
    }

    String id =
        jdbc.queryForObject(
            "INSERT INTO pdf_export (record_id, variant, page_ids, fingerprint)"
                + " VALUES (?, ?, string_to_array(?, ',')::bigint[], ?) RETURNING id::text",
            String.class,
            recordId,
            dbName(variant),
            csv(pageIds),
            fingerprint);
    return new Requested(find(id).orElseThrow(), true);
  }

  // --- reading -----------------------------------------------------------------------------

  private static Optional<UUID> uuid(String id) {
    try {
      return Optional.of(UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  public Optional<View> find(String id) {
    return uuid(id).flatMap(u -> jdbc.query(VIEW_SQL + " WHERE id = ?::uuid", VIEW, u.toString()).stream().findFirst());
  }

  public Optional<Row> row(String id) {
    return uuid(id)
        .flatMap(
            u ->
                jdbc
                    .query(
                        "SELECT id::text AS id, record_id, state, path, variant, error"
                            + " FROM pdf_export WHERE id = ?::uuid",
                        (rs, n) ->
                            new Row(
                                rs.getString("id"),
                                rs.getLong("record_id"),
                                rs.getString("state"),
                                rs.getString("path"),
                                fromDb(rs.getString("variant")),
                                rs.getString("error")),
                        u.toString())
                    .stream()
                    .findFirst());
  }

  /** The files of a record's exports, for deleting with the record. */
  public List<String> pathsForRecord(long recordId) {
    return jdbc.queryForList(
        "SELECT path FROM pdf_export WHERE record_id = ? AND path IS NOT NULL",
        String.class,
        recordId);
  }

  // --- working -----------------------------------------------------------------------------

  /**
   * Takes the oldest queued export and marks it building. {@code SKIP LOCKED}, so workers never
   * wait on each other and never take the same row.
   */
  @Transactional
  public Optional<Claimed> claimNext() {
    return jdbc
        .query(
            """
            UPDATE pdf_export SET state = 'building', started_at = now()
            WHERE id = (SELECT id FROM pdf_export WHERE state = 'queued'
                        ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1)
            RETURNING id::text AS id, record_id, variant,
                      array_to_string(page_ids, ',') AS page_ids, fingerprint
            """,
            (rs, n) ->
                new Claimed(
                    rs.getString("id"),
                    rs.getLong("record_id"),
                    fromDb(rs.getString("variant")),
                    ids(rs.getString("page_ids")),
                    rs.getString("fingerprint")))
        .stream()
        .findFirst();
  }

  public void markReady(String id, String path, long bytes, Duration ttl) {
    jdbc.update(
        "UPDATE pdf_export SET state = 'ready', path = ?, bytes = ?, finished_at = now(),"
            + " expires_at = now() + make_interval(secs => ?) WHERE id = ?::uuid",
        path,
        bytes,
        (double) ttl.toSeconds(),
        id);
  }

  public void markFailed(String id, String error) {
    jdbc.update(
        "UPDATE pdf_export SET state = 'failed', error = ?, finished_at = now()"
            + " WHERE id = ?::uuid",
        error,
        id);
  }

  // --- recovering and expiring -------------------------------------------------------------

  /** Fails every building export: at startup no build can be running in a fresh JVM. */
  public int failAllBuilding(String reason) {
    return jdbc.update(
        "UPDATE pdf_export SET state = 'failed', error = ?, finished_at = now()"
            + " WHERE state = 'building'",
        reason);
  }

  /** Fails a build that has run longer than it should: a hang in a live JVM. */
  public int failInterrupted(Duration olderThan) {
    return jdbc.update(
        "UPDATE pdf_export SET state = 'failed', error = 'interrupted', finished_at = now()"
            + " WHERE state = 'building' AND started_at < now() - make_interval(secs => ?)",
        (double) olderThan.toSeconds());
  }

  /** Marks finished exports past their expiry {@code expired}, returning their files to delete. */
  public List<String> expireDue() {
    return jdbc.queryForList(
        "UPDATE pdf_export SET state = 'expired' WHERE state = 'ready' AND expires_at <= now()"
            + " RETURNING path",
        String.class);
  }

  /** Deletes expired and failed rows a week old, so a stale link says 410 rather than 404. */
  public int purgeOld() {
    return jdbc.update(
        "DELETE FROM pdf_export WHERE state IN ('expired', 'failed')"
            + " AND created_at < now() - interval '7 days'");
  }
}
```

- [ ] **Step 5: Run the tests**

Run: `cd backend && ./gradlew spotlessApply -q && ./gradlew test --tests '*PdfExportQueueTest' 2>&1 | grep -E "FAILED|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`. If `aReplacedScanChangesTheFingerprint` fails on a foreign key, the inserted attachment needs `record_id` only (it has it); if `theFingerprint…` fails with a SQL error, read the message: the usual fault is a missing cast in the `concat_ws` arguments.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "pdf: a table of export requests and the queue that owns its SQL

An export is a record, a variant and a set of page ids, stamped with a fingerprint of what those
pages were. Identical requests join an export in flight or reuse a finished one, serialised on
the fingerprint; workers claim with SKIP LOCKED; expiry, restart recovery and purging are plain
statements. Its own table, so nothing here touches the pipeline's state machine."
```

---

### Task 4: The worker: build, reap, recover

**Files:**
- Create: `backend/src/main/java/place/icomb/archiver/service/PdfExportWorker.java`
- Modify: `backend/src/test/resources/application-test.yml`
- Test: `backend/src/test/java/place/icomb/archiver/service/PdfExportWorkerTest.java` (create)

**Interfaces:**
- Consumes: `PdfExportQueue` (all of Task 3), `PdfExportService.buildToFile` (Task 2), `StorageService` exports area (Task 1), `PdfFixtures` (Task 2).
- Produces (used by Tasks 5–7):
  - `PdfExportWorker(PdfExportQueue, PdfExportService, StorageService, long ttlHours, long minFreeBytes)` — the last two from `archiver.pdf-export.ttl-hours` (default 24) and `archiver.pdf-export.min-free-bytes` (default 2 GiB).
  - `boolean runOnce()` — claims and builds one export; `false` when nothing was queued.
  - `void drain()` — `runOnce()` until the queue is empty.
  - `void reap()` — fails builds running over two hours, expires due exports and deletes their files, purges week-old rows.
  - `void recoverAfterRestart()` — fails every building export and empties the temp area; runs on `ApplicationReadyEvent`.

- [ ] **Step 1: Turn the scheduled worker off in tests**

In `backend/src/test/resources/application-test.yml`, under the existing `archiver:` key, add (next to `storage:`, same indentation):

```yaml
  pdf-export:
    concurrency: 0
    min-free-bytes: 0
```

so that no scheduled worker races a test. Tests call `runOnce()` and `reap()` themselves.

- [ ] **Step 2: Write the failing tests**

Create `backend/src/test/java/place/icomb/archiver/service/PdfExportWorkerTest.java`:

```java
package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.PdfFixtures;
import place.icomb.archiver.service.PdfExportService.Variant;

/**
 * Building an export, and everything around it that can go wrong: a scan that has gone, pages
 * that changed, a restart mid-build, a file past its expiry.
 *
 * <p>The worker's scheduled tasks are off in the test profile, so each test drives it directly.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class PdfExportWorkerTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private PdfExportWorker worker;
  @Autowired private PdfExportQueue queue;
  @Autowired private StorageService storage;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Path storageRoot;

  private long record;
  private List<Long> pages;

  @BeforeEach
  void seed() throws Exception {
    ReflectionTestUtils.setField(worker, "minFreeBytes", 0L);
    jdbc.execute("DELETE FROM pdf_export");
    jdbc.execute("DELETE FROM record");
    storage.clearExportTemp();
    record = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Lagebericht");
    pages =
        List.of(
            PdfFixtures.page(jdbc, storageRoot, record, 1, "One"),
            PdfFixtures.page(jdbc, storageRoot, record, 2, "Two"),
            PdfFixtures.page(jdbc, storageRoot, record, 3, "Three"));
  }

  private String request(Variant variant) {
    return queue.request(record, variant, pages).view().id();
  }

  private boolean tempIsEmpty() throws Exception {
    try (var entries = Files.list(storage.exportTempDir())) {
      return entries.findAny().isEmpty();
    }
  }

  // --- building ----------------------------------------------------------------------------

  @Test
  void anExportIsBuiltAndPlacedAndMarkedReady() throws Exception {
    String id = request(Variant.ORIGINAL);

    assertThat(worker.runOnce()).isTrue();

    PdfExportQueue.View view = queue.find(id).orElseThrow();
    assertThat(view.state()).isEqualTo("ready");
    PdfExportQueue.Row row = queue.row(id).orElseThrow();
    Path file = storage.exportFile(row.path());
    assertThat(row.path()).matches("exports/[0-9a-f]{2}/[0-9a-f-]{36}\\.pdf");
    assertThat(file).exists();
    assertThat(view.bytes()).isEqualTo(Files.size(file));
    try (PDDocument doc = Loader.loadPDF(file.toFile())) {
      assertThat(doc.getNumberOfPages()).isEqualTo(3);
    }
    long hours =
        jdbc.queryForObject(
            "SELECT round(extract(epoch FROM expires_at - now()) / 3600) FROM pdf_export",
            Long.class);
    assertThat(hours).isEqualTo(24L);
    assertThat(tempIsEmpty()).as("nothing left in the temp area").isTrue();
  }

  @Test
  void theOtherVariantsBuildToo() throws Exception {
    for (Variant variant : List.of(Variant.ENGLISH, Variant.SIDE_BY_SIDE)) {
      String id = request(variant);
      worker.runOnce();
      assertThat(queue.find(id).orElseThrow().state()).as(variant.toString()).isEqualTo("ready");
      try (PDDocument doc =
          Loader.loadPDF(storage.exportFile(queue.row(id).orElseThrow().path()).toFile())) {
        assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(3);
      }
    }
  }

  @Test
  void runOnceReportsWhenThereWasNothingToDo() {
    assertThat(worker.runOnce()).isFalse();
  }

  @Test
  void drainBuildsEverythingQueued() {
    String a = request(Variant.ORIGINAL);
    String b = request(Variant.ENGLISH);

    worker.drain();

    assertThat(queue.find(a).orElseThrow().state()).isEqualTo("ready");
    assertThat(queue.find(b).orElseThrow().state()).isEqualTo("ready");
  }

  // --- failing cleanly ---------------------------------------------------------------------

  @Test
  void aMissingScanFailsTheExportAndLeavesNothingBehind() throws Exception {
    String id = request(Variant.ORIGINAL);
    String scan =
        jdbc.queryForObject(
            "SELECT a.path FROM page p JOIN attachment a ON a.id = p.attachment_id WHERE p.id = ?",
            String.class,
            pages.get(1));
    Files.delete(storageRoot.resolve(scan));

    worker.runOnce();

    PdfExportQueue.View view = queue.find(id).orElseThrow();
    assertThat(view.state()).isEqualTo("failed");
    assertThat(view.error()).isNotBlank();
    assertThat(tempIsEmpty()).as("scratch and partial files are removed").isTrue();
  }

  @Test
  void pagesChangedAfterTheRequestFailTheExportRatherThanMixOldAndNew() {
    String id = request(Variant.ENGLISH);
    jdbc.update("UPDATE page_text SET text_en = 'Edited' WHERE page_id = ?", pages.get(0));

    worker.runOnce();

    PdfExportQueue.View view = queue.find(id).orElseThrow();
    assertThat(view.state()).isEqualTo("failed");
    assertThat(view.error()).contains("changed");
  }

  @Test
  void aBuildRefusesToStartWithoutFreeSpace() {
    ReflectionTestUtils.setField(worker, "minFreeBytes", Long.MAX_VALUE);
    String id = request(Variant.ORIGINAL);

    worker.runOnce();

    PdfExportQueue.View view = queue.find(id).orElseThrow();
    assertThat(view.state()).isEqualTo("failed");
    assertThat(view.error()).containsIgnoringCase("free space");
  }

  @Test
  void aLongErrorIsCutToFiveHundredCharactersAndAnEmptyOneStillSaysSomething() {
    assertThat(PdfExportWorker.abbreviate("y".repeat(2000))).hasSize(500);
    assertThat(PdfExportWorker.abbreviate("short")).isEqualTo("short");
    assertThat(PdfExportWorker.abbreviate(null)).isNotBlank();
    assertThat(PdfExportWorker.abbreviate("  ")).isNotBlank();
  }

  // --- reaping -----------------------------------------------------------------------------

  @Test
  void anExpiredExportsFileIsDeletedAndItsRowMarked() throws Exception {
    String id = request(Variant.ORIGINAL);
    worker.runOnce();
    Path file = storage.exportFile(queue.row(id).orElseThrow().path());
    assertThat(file).exists();
    jdbc.update("UPDATE pdf_export SET expires_at = now() - interval '1 second'");

    worker.reap();

    assertThat(file).doesNotExist();
    assertThat(queue.find(id).orElseThrow().state()).isEqualTo("expired");
  }

  @Test
  void anExportStillInDateIsLeftAlone() throws Exception {
    String id = request(Variant.ORIGINAL);
    worker.runOnce();
    Path file = storage.exportFile(queue.row(id).orElseThrow().path());

    worker.reap();

    assertThat(file).exists();
    assertThat(queue.find(id).orElseThrow().state()).isEqualTo("ready");
  }

  @Test
  void aBuildThatHasHungForHoursIsFailedByTheReaper() {
    String id = request(Variant.ORIGINAL);
    queue.claimNext();
    jdbc.update("UPDATE pdf_export SET started_at = now() - interval '3 hours'");

    worker.reap();

    assertThat(queue.find(id).orElseThrow().error()).isEqualTo("interrupted");
  }

  // --- restarting --------------------------------------------------------------------------

  @Test
  void aRestartFailsWhatWasBuildingAndEmptiesTheTempArea() throws Exception {
    String id = request(Variant.ORIGINAL);
    queue.claimNext();
    Files.writeString(storage.exportTempDir().resolve("half.pdf.partial"), "half");
    Files.createDirectories(storage.exportTempDir().resolve("PDFBox-scratch"));

    worker.recoverAfterRestart();

    PdfExportQueue.View view = queue.find(id).orElseThrow();
    assertThat(view.state()).isEqualTo("failed");
    assertThat(view.error()).isEqualTo("interrupted by a restart");
    assertThat(tempIsEmpty()).isTrue();
  }

  @Test
  void aRestartLeavesFinishedAndQueuedExportsAlone() {
    String ready = request(Variant.ORIGINAL);
    worker.runOnce();
    String queued = request(Variant.ENGLISH);

    worker.recoverAfterRestart();

    assertThat(queue.find(ready).orElseThrow().state()).isEqualTo("ready");
    assertThat(queue.find(queued).orElseThrow().state()).isEqualTo("queued");
  }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*PdfExportWorkerTest' 2>&1 | grep -E "FAILED|error:|BUILD"`
Expected: compilation FAILS (`PdfExportWorker` does not exist).

- [ ] **Step 4: Create the worker**

Create `backend/src/main/java/place/icomb/archiver/service/PdfExportWorker.java`:

```java
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
          pdfExportService.buildToFile(
              export.recordId(), seqs, export.variant(), partial, temp);

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
      queue.markFailed(export.id(), abbreviate(e.getMessage() == null ? e.toString() : e.getMessage()));
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

  /** Housekeeping: fail hung builds, expire finished exports, delete their files, purge old rows. */
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
      log.warn("{} PDF export(s) were building when the backend stopped and have been failed", failed);
    }
  }

  static String abbreviate(String message) {
    if (message == null || message.isBlank()) {
      return "The PDF could not be built";
    }
    return message.length() <= MAX_ERROR ? message : message.substring(0, MAX_ERROR);
  }
}
```

- [ ] **Step 5: Run the tests**

Run: `cd backend && ./gradlew spotlessApply -q && ./gradlew test --tests '*PdfExportWorkerTest' --tests '*PdfExportQueueTest' --tests '*PdfExportServiceTest' 2>&1 | grep -E "FAILED|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "pdf: a worker that builds exports, reaps expired ones and recovers after a restart

It re-checks the fingerprint before building, so an export whose pages changed since the request
fails rather than mixing old and new; builds to a partial file in the archive's own temp area and
renames it into place; removes its own debris on failure. At startup every building export is
failed and the temp area emptied, and a reaper expires finished exports after 24 hours, deletes
their files, and fails a build hung for two hours."
```

---

### Task 5: The HTTP API

**Files:**
- Create: `backend/src/main/java/place/icomb/archiver/controller/PdfExportController.java`
- Test: `backend/src/test/java/place/icomb/archiver/controller/PdfExportApiTest.java` (create)

**Interfaces:**
- Consumes: `PdfExportQueue` (Task 3), `PdfExportWorker.runOnce()` (Task 4, tests only), `StorageService.exportFile` (Task 1), `RecordRepository.existsById`.
- Produces:
  - `POST /api/records/{recordId}/pdf-exports` body `{"variant": optional, "pages": optional}` → `200` (an identical export is `ready` and unexpired) or `202` (new, or joined one in flight), body `{id, state, pageCount, variant, bytes, expiresAt, error}`; `404` unknown record; `400` unknown variant, malformed range, non-text fields, or no page matched.
  - `GET /api/pdf-exports/{id}` → `200` the same body; `404` unknown or not a UUID.
  - `GET /api/pdf-exports/{id}/file` → `200` the PDF as an attachment named `record-{recordId}{-original|-english|-original-and-english}.pdf`; `409` while `queued`/`building`, or `failed` (body carries the error); `410` once `expired` or if the file is gone; `404` unknown.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/place/icomb/archiver/controller/PdfExportApiTest.java`:

```java
package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.PdfFixtures;
import place.icomb.archiver.service.PdfExportWorker;

/**
 * Asking for a PDF, waiting for it, and downloading it — the whole flow through HTTP, as an
 * ordinary signed-in user rather than an administrator.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PdfExportApiTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  private static final String EMAIL = "pdf-export-user@example.com";

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Path storageRoot;
  @Autowired private PdfExportWorker worker;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();
  private String base;
  private long record;

  @BeforeEach
  void setUp() throws Exception {
    base = "http://localhost:" + port + "/api";
    jdbc.execute("DELETE FROM pdf_export");
    jdbc.execute("DELETE FROM record");
    jdbc.update("DELETE FROM app_user_email WHERE email = ?", EMAIL);
    jdbc.update("DELETE FROM app_user WHERE display_name = 'PdfExportUser'");
    Long user =
        jdbc.queryForObject(
            "INSERT INTO app_user (display_name, role) VALUES ('PdfExportUser', 'user')"
                + " RETURNING id",
            Long.class);
    jdbc.update("INSERT INTO app_user_email (user_id, email) VALUES (?, ?)", user, EMAIL);

    record = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Lagebericht");
    for (int seq = 1; seq <= 3; seq++) {
      PdfFixtures.page(jdbc, storageRoot, record, seq, "English " + seq);
    }
  }

  private HttpResponse<String> post(String path, String body, boolean signedIn) throws Exception {
    HttpRequest.Builder req =
        HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (signedIn) {
      req.header("X-Auth-Email", EMAIL);
    }
    return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path, boolean signedIn) throws Exception {
    HttpRequest.Builder req = HttpRequest.newBuilder().uri(URI.create(base + path)).GET();
    if (signedIn) {
      req.header("X-Auth-Email", EMAIL);
    }
    return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<byte[]> getBytes(String path) throws Exception {
    return http.send(
        HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .header("X-Auth-Email", EMAIL)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private JsonNode created(String body, int status) throws Exception {
    HttpResponse<String> resp = post("/records/" + record + "/pdf-exports", body, true);
    assertThat(resp.statusCode()).as(resp.body()).isEqualTo(status);
    return json.readTree(resp.body());
  }

  private long exports() {
    return jdbc.queryForObject("SELECT count(*) FROM pdf_export", Long.class);
  }

  // --- the flow ----------------------------------------------------------------------------

  @Test
  void requestWaitThenDownloadTheWholeRecord() throws Exception {
    JsonNode made = created("{}", 202);
    String id = made.get("id").asText();
    assertThat(made.get("state").asText()).isEqualTo("queued");
    assertThat(made.get("pageCount").asInt()).isEqualTo(3);
    assertThat(made.get("variant").asText()).isEqualTo("original");

    HttpResponse<String> early = get("/pdf-exports/" + id + "/file", true);
    assertThat(early.statusCode()).as("not ready yet").isEqualTo(409);

    worker.runOnce();

    JsonNode status = json.readTree(get("/pdf-exports/" + id, true).body());
    assertThat(status.get("state").asText()).isEqualTo("ready");
    assertThat(status.get("bytes").asLong()).isPositive();
    assertThat(status.get("expiresAt").asText()).isNotBlank();

    HttpResponse<byte[]> file = getBytes("/pdf-exports/" + id + "/file");
    assertThat(file.statusCode()).isEqualTo(200);
    assertThat(file.headers().firstValue("Content-Type").orElse("")).contains("application/pdf");
    assertThat(file.headers().firstValue("Content-Disposition").orElse(""))
        .contains("attachment")
        .contains("record-" + record + "-original.pdf");
    try (PDDocument doc = Loader.loadPDF(file.body())) {
      assertThat(doc.getNumberOfPages()).isEqualTo(3);
    }
  }

  @Test
  void aRangeExportsOnlyThosePages() throws Exception {
    String id = created("{\"pages\":\"2-3\"}", 202).get("id").asText();
    worker.runOnce();

    try (PDDocument doc = Loader.loadPDF(getBytes("/pdf-exports/" + id + "/file").body())) {
      assertThat(doc.getNumberOfPages()).isEqualTo(2);
    }
  }

  @Test
  void theEnglishAndSideBySideVariantsAreNamedForTheirContent() throws Exception {
    String english = created("{\"variant\":\"english\"}", 202).get("id").asText();
    String both = created("{\"variant\":\"side-by-side\"}", 202).get("id").asText();
    worker.drain();

    assertThat(
            getBytes("/pdf-exports/" + english + "/file")
                .headers()
                .firstValue("Content-Disposition")
                .orElse(""))
        .contains("record-" + record + "-english.pdf");
    assertThat(
            getBytes("/pdf-exports/" + both + "/file")
                .headers()
                .firstValue("Content-Disposition")
                .orElse(""))
        .contains("record-" + record + "-original-and-english.pdf");
  }

  // --- reuse -------------------------------------------------------------------------------

  @Test
  void anIdenticalRequestWhileOneIsQueuedJoinsIt() throws Exception {
    String first = created("{}", 202).get("id").asText();

    String second = created("{}", 202).get("id").asText();

    assertThat(second).isEqualTo(first);
    assertThat(exports()).isEqualTo(1L);
  }

  @Test
  void anIdenticalRequestAfterItIsReadyAnswersOkWithTheSameExport() throws Exception {
    String first = created("{}", 202).get("id").asText();
    worker.runOnce();

    JsonNode again = created("{}", 200);

    assertThat(again.get("id").asText()).isEqualTo(first);
    assertThat(again.get("state").asText()).isEqualTo("ready");
    assertThat(exports()).isEqualTo(1L);
  }

  @Test
  void changingAPagesTextStartsANewExportInsteadOfServingTheOldOne() throws Exception {
    String first = created("{}", 202).get("id").asText();
    worker.runOnce();
    jdbc.update("UPDATE page_text SET text_en = 'Corrected' WHERE page_id = (SELECT id FROM page WHERE record_id = ? AND seq = 1)", record);

    String second = created("{}", 202).get("id").asText();

    assertThat(second).isNotEqualTo(first);
  }

  // --- states the file endpoint refuses ----------------------------------------------------

  @Test
  void anExpiredExportIsGoneNotMissing() throws Exception {
    String id = created("{}", 202).get("id").asText();
    worker.runOnce();
    jdbc.update("UPDATE pdf_export SET expires_at = now() - interval '1 second'");
    worker.reap();

    assertThat(get("/pdf-exports/" + id + "/file", true).statusCode()).isEqualTo(410);
    assertThat(json.readTree(get("/pdf-exports/" + id, true).body()).get("state").asText())
        .isEqualTo("expired");
  }

  @Test
  void aFileThatHasVanishedFromDiskIsGone() throws Exception {
    String id = created("{}", 202).get("id").asText();
    worker.runOnce();
    String path = jdbc.queryForObject("SELECT path FROM pdf_export", String.class);
    Files.delete(storageRoot.resolve(path));

    assertThat(get("/pdf-exports/" + id + "/file", true).statusCode()).isEqualTo(410);
  }

  @Test
  void aFailedExportExplainsWhy() throws Exception {
    String id = created("{}", 202).get("id").asText();
    jdbc.update("UPDATE pdf_export SET state = 'failed', error = 'no scan for page 2'");

    HttpResponse<String> resp = get("/pdf-exports/" + id + "/file", true);

    assertThat(resp.statusCode()).isEqualTo(409);
    assertThat(resp.body()).contains("no scan for page 2");
  }

  @Test
  void anUnknownOrMalformedIdIsNotFound() throws Exception {
    assertThat(get("/pdf-exports/00000000-0000-0000-0000-000000000000", true).statusCode())
        .isEqualTo(404);
    assertThat(get("/pdf-exports/00000000-0000-0000-0000-000000000000/file", true).statusCode())
        .isEqualTo(404);
    assertThat(get("/pdf-exports/not-a-uuid", true).statusCode()).isEqualTo(404);
    assertThat(get("/pdf-exports/not-a-uuid/file", true).statusCode()).isEqualTo(404);
  }

  // --- refusals ----------------------------------------------------------------------------

  @Test
  void badRequestsAreRefusedAndCreateNothing() throws Exception {
    for (String body :
        List.of(
            "{\"variant\":\"pdf\"}",
            "{\"variant\":7}",
            "{\"pages\":\"a-b\"}",
            "{\"pages\":99}",
            "{\"pages\":\"99\"}")) {
      HttpResponse<String> resp = post("/records/" + record + "/pdf-exports", body, true);
      assertThat(resp.statusCode()).as(body).isEqualTo(400);
    }
    assertThat(exports()).isZero();
  }

  @Test
  void aRecordWithNoPagesHasNothingToExport() throws Exception {
    long empty = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Empty");

    HttpResponse<String> resp = post("/records/" + empty + "/pdf-exports", "{}", true);

    assertThat(resp.statusCode()).isEqualTo(400);
    assertThat(exports()).isZero();
  }

  @Test
  void anUnknownRecordIsNotFound() throws Exception {
    assertThat(post("/records/99999999/pdf-exports", "{}", true).statusCode()).isEqualTo(404);
  }

  @Test
  void anyoneSignedInMayAskButAnonymousCallersAreRefused() throws Exception {
    String id = created("{}", 202).get("id").asText();

    assertThat(post("/records/" + record + "/pdf-exports", "{}", false).statusCode())
        .isIn(401, 403);
    assertThat(get("/pdf-exports/" + id, false).statusCode()).isIn(401, 403);
    assertThat(get("/pdf-exports/" + id + "/file", false).statusCode()).isIn(401, 403);
  }

  @Test
  void thereIsNoLimitOnHowManyAPersonMayHaveOpen() throws Exception {
    for (int i = 0; i < 8; i++) {
      created("{\"pages\":\"" + (i % 3 + 1) + "\",\"variant\":\"" + (i < 3 ? "original" : i < 6 ? "english" : "side-by-side") + "\"}", 202);
    }
    // Eight requests, distinct selections or variants: each is accepted.
    assertThat(exports()).isGreaterThanOrEqualTo(7L);
  }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*PdfExportApiTest' 2>&1 | grep -E "FAILED|error:|BUILD"`
Expected: every test FAILS with `404` (no endpoint), except compilation succeeds because the test uses only HTTP and the worker from Task 4.

- [ ] **Step 3: Create the controller**

Create `backend/src/main/java/place/icomb/archiver/controller/PdfExportController.java`:

```java
package place.icomb.archiver.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.repository.RecordRepository;
import place.icomb.archiver.service.PdfExportQueue;
import place.icomb.archiver.service.PdfExportService.Variant;
import place.icomb.archiver.service.StorageService;

/**
 * Every PDF the archive makes: ask for it, wait, download it.
 *
 * <p>There is one path whatever the size. A whole 942-page record and a three-page extract are
 * both requested here, built by a worker, and fetched when ready, so nothing holds a request open
 * or a document in memory while it is assembled.
 */
@RestController
@RequestMapping("/api")
public class PdfExportController {

  private final PdfExportQueue queue;
  private final RecordRepository recordRepository;
  private final StorageService storageService;

  public PdfExportController(
      PdfExportQueue queue, RecordRepository recordRepository, StorageService storageService) {
    this.queue = queue;
    this.recordRepository = recordRepository;
    this.storageService = storageService;
  }

  @PostMapping("/records/{recordId}/pdf-exports")
  public ResponseEntity<Map<String, Object>> create(
      @PathVariable long recordId, @RequestBody(required = false) Map<String, Object> body) {
    if (!recordRepository.existsById(recordId)) {
      return error(HttpStatus.NOT_FOUND, "Record %d not found".formatted(recordId));
    }
    Map<String, Object> in = body == null ? Map.of() : body;

    Variant variant;
    String pages;
    try {
      variant = PdfExportQueue.parseVariant(text(in, "variant"));
      pages = text(in, "pages");
    } catch (IllegalArgumentException e) {
      return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    List<Long> pageIds;
    try {
      pageIds = queue.pageIdsFor(recordId, pages);
    } catch (IllegalArgumentException e) {
      return error(HttpStatus.BAD_REQUEST, "Malformed page range: " + pages);
    }
    if (pageIds.isEmpty()) {
      return error(HttpStatus.BAD_REQUEST, "No page matches that selection");
    }

    PdfExportQueue.Requested requested = queue.request(recordId, variant, pageIds);
    boolean reusedFinished = !requested.created() && "ready".equals(requested.view().state());
    return ResponseEntity.status(reusedFinished ? HttpStatus.OK : HttpStatus.ACCEPTED)
        .body(describe(requested.view()));
  }

  @GetMapping("/pdf-exports/{id}")
  public ResponseEntity<Map<String, Object>> status(@PathVariable String id) {
    return queue
        .find(id)
        .map(view -> ResponseEntity.ok(describe(view)))
        .orElseGet(() -> error(HttpStatus.NOT_FOUND, "No such export"));
  }

  @GetMapping("/pdf-exports/{id}/file")
  public ResponseEntity<?> file(@PathVariable String id) {
    Optional<PdfExportQueue.Row> found = queue.row(id);
    if (found.isEmpty()) {
      return error(HttpStatus.NOT_FOUND, "No such export");
    }
    PdfExportQueue.Row row = found.get();
    return switch (row.state()) {
      case "ready" -> {
        Path file = storageService.exportFile(row.path());
        if (!Files.exists(file)) {
          yield error(HttpStatus.GONE, "The file is no longer available; request the PDF again");
        }
        String name = "record-" + row.recordId() + PdfExportQueue.fileSuffix(row.variant()) + ".pdf";
        yield ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
            .contentLength(file.toFile().length())
            .body(new FileSystemResource(file));
      }
      case "expired" -> error(HttpStatus.GONE, "This PDF has expired; request it again");
      case "failed" ->
          error(HttpStatus.CONFLICT, row.error() == null ? "The PDF could not be built" : row.error());
      default -> error(HttpStatus.CONFLICT, "The PDF is still being prepared");
    };
  }

  private static Map<String, Object> describe(PdfExportQueue.View v) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", v.id());
    out.put("state", v.state());
    out.put("pageCount", v.pageCount());
    out.put("variant", v.variant());
    out.put("bytes", v.bytes());
    out.put("expiresAt", v.expiresAt() == null ? null : v.expiresAt().toString());
    out.put("error", v.error());
    return out;
  }

  private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", String.valueOf(message)));
  }

  private static String text(Map<String, Object> body, String key) {
    Object v = body.get(key);
    if (v == null) {
      return null;
    }
    if (!(v instanceof String s)) {
      throw new IllegalArgumentException(key + " must be text");
    }
    return s;
  }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./gradlew spotlessApply -q && ./gradlew test --tests '*PdfExportApiTest' 2>&1 | grep -E "FAILED|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`. If `thereIsNoLimit…` counts fewer than 7 (two requests collapsed into one because their selection and variant match), adjust the selections in that test so eight are distinct; the point is only that none is refused.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "pdf: endpoints to ask for a PDF, poll it, and download it

POST /api/records/{id}/pdf-exports answers 202 with an export id (200 if an identical finished one
is reused), GET /api/pdf-exports/{id} reports its state, and GET .../file streams it once ready:
409 while queued, building or failed, 410 once expired. Any signed-in user may ask; there is no
per-user limit."
```

---

### Task 6: Schedule the worker and the reaper

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/config/WorkerSchedulingConfig.java`

**Interfaces:**
- Consumes: `PdfExportWorker.drain()`, `PdfExportWorker.reap()` (Task 4); property `archiver.pdf-export.concurrency` (default 2).
- Produces: `archiver.pdf-export.concurrency` scheduled drain tasks (poll 3 s) and one reaper task (every 15 minutes), both only when the concurrency is above zero.

- [ ] **Step 1: Make the edits**

Run this (it fails loudly if any anchor is not found exactly once, and writes nothing then):

```bash
cd backend && python3 - <<'PY'
import re
p='src/main/java/place/icomb/archiver/config/WorkerSchedulingConfig.java'
s=open(p).read()

def sub(pattern, repl, count=1, flags=0):
    global s
    found=len(re.findall(pattern, s, flags))
    assert found==count, (found, pattern)
    s=re.sub(pattern, repl, s, flags=flags)

# constructor parameters
sub(r'long pdfPollInterval\) \{',
    'long pdfPollInterval,\n'
    '      @Value("${archiver.pdf-export.concurrency:2}") int pdfExportConcurrency,\n'
    '      place.icomb.archiver.service.PdfExportWorker pdfExportWorker) {')
# fields
sub(r'(private final long pdfPollInterval;\n)',
    r'\1  private final int pdfExportConcurrency;\n  private final place.icomb.archiver.service.PdfExportWorker pdfExportWorker;\n')
# assignments
sub(r'(this\.pdfPollInterval = pdfPollInterval;\n)',
    r'\1    this.pdfExportConcurrency = pdfExportConcurrency;\n    this.pdfExportWorker = pdfExportWorker;\n')
# pool size: the export workers, and one thread for the reaper
sub(r'(\+ pdfConcurrency\n(\s*))(\+ embedConcurrency;)',
    r'\1+ pdfExportConcurrency\n\2+ (pdfExportConcurrency > 0 ? 1 : 0)\n\2\3')
# registration, right after the searchable-PDF workers
sub(r'("Registered \{\} searchable PDF worker\(s\) \(poll=\{\}ms\)", pdfConcurrency, pdfPollInterval\);\n    \}\n)',
    r'''\1
    // On-demand PDF exports. A build holds a scheduler thread for as long as it runs, so each
    // worker counts toward the pool above. The reaper is one more thread, every 15 minutes.
    for (int i = 0; i < pdfExportConcurrency; i++) {
      registrar.addFixedDelayTask(pdfExportWorker::drain, Duration.ofSeconds(3));
    }
    if (pdfExportConcurrency > 0) {
      registrar.addFixedDelayTask(pdfExportWorker::reap, Duration.ofMinutes(15));
      log.info("Registered {} PDF export worker(s) and the reaper", pdfExportConcurrency);
    }
''')
open(p,'w').write(s)
print("edited")
PY
git diff --stat
```

Expected: `edited`, and `git diff --stat` shows only `WorkerSchedulingConfig.java` changed.

- [ ] **Step 2: Compile and run the whole suite**

Run: `cd backend && ./gradlew spotlessApply -q && ./gradlew compileJava 2>&1 | grep -E "error|BUILD"`
Then: `./gradlew test 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL` for both. The full run matters here: a wiring mistake in this class fails every Spring test, not just one. The scheduled tasks are off in tests (`concurrency: 0`), so nothing races.

- [ ] **Step 3: Commit**

```bash
git add backend/src
git commit -m "pdf: schedule the export workers and the reaper

Each worker holds a scheduler thread for the length of a build, so the pool is sized for them, and
the reaper gets one more. archiver.pdf-export.concurrency defaults to 2 and is 0 in tests, where
nothing should race the test that drives the worker."
```

---

### Task 7: Deleting a record deletes its export files

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/service/IngestService.java`
- Test: `backend/src/test/java/place/icomb/archiver/controller/PdfExportApiTest.java`

**Interfaces:**
- Consumes: `PdfExportQueue.pathsForRecord(long)` (Task 3); `IngestService.deleteFilesAfterCommit` (existing private helper).
- Produces: `IngestService.deleteRecord` removes the record's export files after commit, as it does its page images.

- [ ] **Step 1: Write the failing test**

Append to `PdfExportApiTest` (add `@Autowired private place.icomb.archiver.service.IngestService ingestService;` next to the other autowired fields):

```java
  // --- deleting a record -------------------------------------------------------------------

  @Test
  void deletingARecordRemovesItsExportFilesAndRows() throws Exception {
    String id = created("{}", 202).get("id").asText();
    worker.runOnce();
    String path = jdbc.queryForObject("SELECT path FROM pdf_export", String.class);
    Path file = storageRoot.resolve(path);
    assertThat(file).exists();

    ingestService.deleteRecord(record);

    assertThat(file).doesNotExist();
    assertThat(exports()).isZero();
    assertThat(get("/pdf-exports/" + id, true).statusCode()).isEqualTo(404);
  }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests '*PdfExportApiTest' 2>&1 | grep -E "FAILED|error:|BUILD"`
Expected: `deletingARecordRemovesItsExportFilesAndRows` FAILS on `assertThat(file).doesNotExist()` (the row is gone by `ON DELETE CASCADE`, the file is not).

- [ ] **Step 3: Implement**

In `IngestService.java`: read the constructor and its fields (around lines 35–62), then add a `private final PdfExportQueue pdfExportQueue;` field, a `PdfExportQueue pdfExportQueue` constructor parameter **last**, and `this.pdfExportQueue = pdfExportQueue;` in the body, following the existing style. Then, in `deleteRecord`, directly after the statement that ends `.toList());` of the existing `deleteFilesAfterCommit(attachmentRepository.findByRecordId(recordId)…)` call and before `storageService.deleteRecordFiles(recordId);`, add:

```java
    // Export files hang off the record by a cascading foreign key, so the rows go with it; their
    // files live under exports/, which deleting the record's own directory does not reach.
    deleteFilesAfterCommit(pdfExportQueue.pathsForRecord(recordId));
```

Then find every place `IngestService` is constructed by hand: `grep -rn "new IngestService(" backend/src`. Expected: none (Spring wires it). If there is one, add the new argument.

- [ ] **Step 4: Run the tests**

Run: `cd backend && ./gradlew spotlessApply -q && ./gradlew test --tests '*PdfExportApiTest' --tests '*StorageMigrationTest' --tests '*ReplacePageAndRecordTest' 2>&1 | grep -E "FAILED|error:|BUILD"`
Expected: `BUILD SUCCESSFUL`. The other two classes delete records and pages, so they prove nothing else in `IngestService` changed.

- [ ] **Step 5: Commit**

```bash
git add backend/src
git commit -m "pdf: deleting a record deletes its export files

The rows cascade with the record; the files live under exports/ and would have been left behind
for ever, since deleting the record's own directory does not reach them."
```

---

### Task 8: The viewer asks, waits and downloads

**Files:**
- Create: `frontend/src/lib/pdf-export.ts`
- Create: `frontend/tests/pdf-export.test.ts`
- Create: `frontend/src/lib/components/PdfExportButton.svelte`
- Modify: `frontend/src/routes/records/[id]/+page.svelte`
- Modify: `frontend/src/routes/records/[id]/pages/[seq]/+page.svelte`
- Modify: `frontend/src/lib/messages/en.ts`, `de.ts`, `cs.ts`

**Interfaces:**
- Consumes: the three endpoints from Task 5.
- Produces: `PdfExportButton` with props `recordId: number`, `variant?: string` (default `original`), `pages?: string` (default `''`, meaning the whole record), `label: string`, `class?: string`.

- [ ] **Step 1: Write the failing tests**

Create `frontend/tests/pdf-export.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import {
	createUrl,
	describeState,
	errorFrom,
	exportRequest,
	fileUrl,
	nextPollDelay,
	statusUrl
} from '../src/lib/pdf-export';

describe('addresses', () => {
	it('builds the three endpoint addresses', () => {
		expect(createUrl(4037)).toBe('/api/records/4037/pdf-exports');
		expect(statusUrl('abc-123')).toBe('/api/pdf-exports/abc-123');
		expect(fileUrl('abc-123')).toBe('/api/pdf-exports/abc-123/file');
	});

	it('encodes an id rather than trusting it', () => {
		expect(statusUrl('a/b')).toBe('/api/pdf-exports/a%2Fb');
	});
});

describe('exportRequest', () => {
	it('asks for the whole record when no pages are given', () => {
		expect(exportRequest('original', '')).toEqual({ variant: 'original' });
		expect(exportRequest('english', '   ')).toEqual({ variant: 'english' });
	});

	it('passes a trimmed page selection through', () => {
		expect(exportRequest('side-by-side', ' 1,3,5-10 ')).toEqual({
			variant: 'side-by-side',
			pages: '1,3,5-10'
		});
	});
});

describe('nextPollDelay', () => {
	it('polls quickly at first, then backs off to a ceiling', () => {
		expect([0, 1, 2, 3, 4].map(nextPollDelay)).toEqual([2000, 2000, 2000, 2000, 2000]);
		expect(nextPollDelay(5)).toBe(2500);
		expect(nextPollDelay(6)).toBe(3000);
		expect(nextPollDelay(10)).toBe(5000);
		expect(nextPollDelay(500)).toBe(5000);
	});

	it('never gets shorter as the wait gets longer', () => {
		let previous = 0;
		for (let attempt = 0; attempt < 40; attempt++) {
			const delay = nextPollDelay(attempt);
			expect(delay).toBeGreaterThanOrEqual(previous);
			previous = delay;
		}
	});
});

describe('describeState', () => {
	it('keeps waiting while queued or building', () => {
		expect(describeState('queued', null)).toEqual({ terminal: false, ok: false, message: '' });
		expect(describeState('building', null)).toEqual({ terminal: false, ok: false, message: '' });
	});

	it('is done and good when ready', () => {
		expect(describeState('ready', null)).toEqual({ terminal: true, ok: true, message: '' });
	});

	it('is done and bad when failed, saying why', () => {
		const failed = describeState('failed', 'no scan for page 2');
		expect(failed.terminal).toBe(true);
		expect(failed.ok).toBe(false);
		expect(failed.message).toBe('no scan for page 2');
	});

	it('says something useful when it failed with no reason', () => {
		expect(describeState('failed', null).message).not.toBe('');
	});

	it('tells the person to ask again when expired', () => {
		const expired = describeState('expired', null);
		expect(expired.terminal).toBe(true);
		expect(expired.ok).toBe(false);
		expect(expired.message).toMatch(/request it again/i);
	});
});

describe('errorFrom', () => {
	it('reads the error out of a JSON body', () => {
		expect(errorFrom(400, '{"error":"No page matches that selection"}')).toBe(
			'No page matches that selection'
		);
	});

	it('falls back to the status when the body is not JSON or has no error', () => {
		expect(errorFrom(502, '<html>Bad gateway</html>')).toBe('Request failed (502)');
		expect(errorFrom(500, '{"other":1}')).toBe('Request failed (500)');
		expect(errorFrom(500, '')).toBe('Request failed (500)');
	});
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && bun run test -- pdf-export 2>&1 | tail -15`
Expected: FAIL: `Failed to resolve import "../src/lib/pdf-export"`.

- [ ] **Step 3: Write the pure logic**

Create `frontend/src/lib/pdf-export.ts`:

```ts
/**
 * Asking the backend for a PDF, waiting for it, and fetching it.
 *
 * Every PDF is built on request and takes the same path, whatever its size: POST to ask, poll the
 * export until it is ready, then navigate to its file. This holds the parts of that which are plain
 * logic, so they can be tested without a browser.
 */

export type ExportState = 'queued' | 'building' | 'ready' | 'failed' | 'expired';

export interface ExportView {
	id: string;
	state: ExportState;
	pageCount: number;
	variant: string;
	bytes: number | null;
	expiresAt: string | null;
	error: string | null;
}

export interface Described {
	/** Nothing more will happen to this export: stop polling. */
	terminal: boolean;
	/** It finished and the file can be fetched. */
	ok: boolean;
	/** What to tell the person when it did not. Empty otherwise. */
	message: string;
}

export function createUrl(recordId: number): string {
	return `/api/records/${recordId}/pdf-exports`;
}

export function statusUrl(id: string): string {
	return `/api/pdf-exports/${encodeURIComponent(id)}`;
}

export function fileUrl(id: string): string {
	return `${statusUrl(id)}/file`;
}

/** The request body: the whole record unless pages are given. */
export function exportRequest(variant: string, pages: string): { variant: string; pages?: string } {
	const trimmed = pages.trim();
	return trimmed ? { variant, pages: trimmed } : { variant };
}

/** Milliseconds to wait before asking again: brisk at first, then easing off to five seconds. */
export function nextPollDelay(attempt: number): number {
	if (attempt < 5) return 2000;
	return Math.min(5000, 2000 + (attempt - 4) * 500);
}

export function describeState(state: ExportState, error: string | null): Described {
	switch (state) {
		case 'ready':
			return { terminal: true, ok: true, message: '' };
		case 'failed':
			return { terminal: true, ok: false, message: error ?? 'The PDF could not be built' };
		case 'expired':
			return { terminal: true, ok: false, message: 'This PDF has expired; request it again' };
		default:
			return { terminal: false, ok: false, message: '' };
	}
}

/** The backend's error message if the body carries one, otherwise the status. */
export function errorFrom(status: number, body: string): string {
	try {
		const parsed = JSON.parse(body);
		if (parsed && typeof parsed.error === 'string') return parsed.error;
	} catch {
		// not JSON: fall through to the status
	}
	return `Request failed (${status})`;
}
```

- [ ] **Step 4: Run the tests**

Run: `cd frontend && bun run test -- pdf-export 2>&1 | tail -8`
Expected: all pass.

- [ ] **Step 5: Create the button**

Create `frontend/src/lib/components/PdfExportButton.svelte`:

```svelte
<script lang="ts">
	import { Download, Loader } from 'lucide-svelte';
	import { t } from '$lib/i18n';
	import {
		createUrl,
		describeState,
		errorFrom,
		exportRequest,
		fileUrl,
		nextPollDelay,
		statusUrl,
		type ExportView
	} from '$lib/pdf-export';

	let {
		recordId,
		variant = 'original',
		pages = '',
		label,
		class: klass = 'vui-btn vui-btn-primary vui-btn-sm'
	}: {
		recordId: number;
		variant?: string;
		pages?: string;
		label: string;
		class?: string;
	} = $props();

	// State is written only from the click handler below, never while rendering.
	let working = $state(false);
	let problem = $state<string | null>(null);

	async function readJson(res: Response): Promise<ExportView> {
		if (!res.ok) throw new Error(errorFrom(res.status, await res.text()));
		return (await res.json()) as ExportView;
	}

	async function start() {
		working = true;
		problem = null;
		try {
			let view = await readJson(
				await fetch(createUrl(recordId), {
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify(exportRequest(variant, pages))
				})
			);
			for (let attempt = 0; !describeState(view.state, view.error).terminal; attempt++) {
				await new Promise((resolve) => setTimeout(resolve, nextPollDelay(attempt)));
				view = await readJson(await fetch(statusUrl(view.id)));
			}
			const outcome = describeState(view.state, view.error);
			if (!outcome.ok) throw new Error(outcome.message);
			// An attachment, so the page stays where it is and the browser saves the file.
			window.location.assign(fileUrl(view.id));
		} catch (e) {
			problem = e instanceof Error ? e.message : String(e);
		} finally {
			working = false;
		}
	}
</script>

<button type="button" class={klass} disabled={working} onclick={start}>
	{#if working}
		<Loader size={13} strokeWidth={2} class="animate-spin" /> {$t('record.pdfPreparing')}
	{:else}
		<Download size={13} strokeWidth={2} /> {label}
	{/if}
</button>
{#if problem}
	<span class="text-danger text-[length:var(--vui-text-sm)]" role="alert">
		{$t('record.pdfFailed')}: {problem}
	</span>
{/if}
```

- [ ] **Step 6: Add the strings**

Run:

```bash
cd frontend && python3 - <<'PY'
adds = {
    'en': ["'record.pdfPreparing': 'Preparing PDF…'", "'record.pdfFailed': 'Could not make the PDF'"],
    'de': ["'record.pdfPreparing': 'PDF wird vorbereitet…'", "'record.pdfFailed': 'Das PDF konnte nicht erstellt werden'"],
    'cs': ["'record.pdfPreparing': 'Připravuji PDF…'", "'record.pdfFailed': 'PDF se nepodařilo vytvořit'"],
}
for lang, lines in adds.items():
    p = f'src/lib/messages/{lang}.ts'
    s = open(p).read()
    marker = "\t'record.downloadPdf':"
    assert s.count(marker) == 1, (lang, s.count(marker))
    i = s.index(marker)
    j = s.index("\n", i) + 1
    s = s[:j] + "".join("\t" + l + ",\n" for l in lines) + s[j:]
    open(p, 'w').write(s)
print("strings added")
PY
bun run test 2>&1 | tail -6
```

Expected: `strings added`, then the whole frontend suite passes, including `i18n-completeness` (it fails if the three languages disagree on keys).

- [ ] **Step 7: Use the button**

Run:

```bash
cd frontend && python3 - <<'PY'
IMPORT = "\timport PdfExportButton from '$lib/components/PdfExportButton.svelte';\n"

# ---- the record page --------------------------------------------------------------------
p = 'src/routes/records/[id]/+page.svelte'
s = open(p).read()

def rep(a, b):
    global s
    assert s.count(a) == 1, (s.count(a), a[:70])
    s = s.replace(a, b)

rep("\timport StatusBadge from '$lib/components/StatusBadge.svelte';\n",
    "\timport StatusBadge from '$lib/components/StatusBadge.svelte';\n" + IMPORT)

# the href and its comment go: the button builds the request itself
start = s.index("\t/**\n\t * Whole-record download, honouring the export choice.")
end = s.index("\t);\n", s.index("let wholeRecordHref")) + len("\t);\n")
s = s[:start] + s[end:].lstrip("\n")

rep("""		{#if record.pdfAttachmentId || pages.length > 0}
			<a href={wholeRecordHref} class="vui-btn vui-btn-primary vui-btn-sm" target="_blank">
				<Download size={13} strokeWidth={2} /> {downloadLabel}
			</a>
		{/if}""",
"""		<!--
			Every variant is rendered on request, the scans included: the old stored PDFs carry a text
			layer built before block coordinates were kept, so a selection returned text from
			somewhere else on the page. The page picker feeds this button; empty means the whole record.
		-->
		{#if pages.length > 0}
			<PdfExportButton
				recordId={record.id}
				variant={exportVariant}
				pages={exportPages}
				label={downloadLabel}
			/>
		{/if}""")

rep("""				<a
					href="/api/records/{record.id}/export-pdf?pages={encodeURIComponent(kParam)}&variant={exportVariant}"
					class="vui-btn vui-btn-sm !bg-emerald-600 !border-emerald-600 !text-white"
					target="_blank"
				>
					<Download size={13} strokeWidth={2} /> Download {kCount} kept
				</a>""",
"""				<PdfExportButton
					recordId={record.id}
					variant={exportVariant}
					pages={kParam}
					label="Download {kCount} kept"
					class="vui-btn vui-btn-sm !bg-emerald-600 !border-emerald-600 !text-white"
				/>""")
open(p, 'w').write(s)

# ---- the page viewer --------------------------------------------------------------------
p = 'src/routes/records/[id]/pages/[seq]/+page.svelte'
s = open(p).read()
assert s.count("\timport ") >= 1
s = s.replace("\timport ", IMPORT + "\timport ", 1)
rep("""			<a
				href="/api/records/{record.id}/export-pdf?pages={encodeURIComponent(pagesParam)}"
				class="vui-btn vui-btn-primary vui-btn-sm !bg-emerald-600 !border-emerald-600"
				target="_blank"
			>
				<Download size={13} strokeWidth={2} /> {$t('page.downloadKept')}
			</a>""",
"""			<PdfExportButton
				recordId={record.id}
				pages={pagesParam}
				label={$t('page.downloadKept')}
				class="vui-btn vui-btn-primary vui-btn-sm !bg-emerald-600 !border-emerald-600"
			/>""")
open(p, 'w').write(s)
print("viewer edited")
PY
git diff --stat
```

Expected: `viewer edited`; the diff touches only the two route files.

- [ ] **Step 8: Check the frontend**

Run: `cd frontend && bun run test 2>&1 | tail -6 && bun run check 2>&1 | tail -15`
Expected: tests pass; `svelte-check found 0 errors`. If it reports `Download` as an unused import in either route file, remove it from that file's `lucide-svelte` import list. If it reports `downloadLabel` or `exportVariant` issues, read them: both are still used. The record page's `{#if record.sourceUrl || record.pdfAttachmentId || pages.length > 0}` outer condition stays as it is.

- [ ] **Step 9: Commit**

```bash
git add frontend
git commit -m "viewer: every PDF download asks, waits and downloads through one button

The whole-record button, the kept-pages button and the page viewer's kept-pages button all went
through a synchronous link that held the request open while the backend assembled the document.
They now share PdfExportButton: it asks for the export, polls its state, shows Preparing PDF…, and
navigates to the file when it is ready. The logic that is not DOM is in pdf-export.ts, with tests."
```

---

### Task 9: Retire the synchronous PDF paths

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/controller/FileController.java`
- Modify: `backend/src/main/java/place/icomb/archiver/service/PdfExportService.java`
- Modify: `backend/src/main/java/place/icomb/archiver/controller/ApiController.java`
- Modify: `backend/src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java`
- Modify: `backend/src/test/java/place/icomb/archiver/service/PdfExportServiceTest.java`
- Modify: `.claude/skills/archiver-api/SKILL.md`
- Test: `backend/src/test/java/place/icomb/archiver/controller/PdfExportApiTest.java`

**Interfaces:**
- Consumes: `PdfExportService.buildToFile` (Task 2).
- Produces: `GET /api/records/{id}/pdf` and `GET /api/records/{id}/export-pdf` no longer exist; the machine API carries `links.pdfExport` (single record) and `pdfExportUrl` (search and browse results) whenever the record has pages.

- [ ] **Step 1: Write the failing tests**

Append to `PdfExportApiTest`:

```java
  // --- the old paths are gone --------------------------------------------------------------

  @Test
  void theSynchronousPdfUrlsNoLongerExist() throws Exception {
    assertThat(get("/records/" + record + "/pdf", true).statusCode()).isEqualTo(404);
    assertThat(get("/records/" + record + "/export-pdf?pages=1-3", true).statusCode())
        .isEqualTo(404);
  }

  @Test
  void theMachineApiPointsAtTheExportAddress() throws Exception {
    JsonNode doc = json.readTree(get("/v1/documents/" + record, true).body());

    assertThat(doc.get("links").has("pdf")).isFalse();
    assertThat(doc.get("links").get("pdfExport").asText())
        .endsWith("/records/" + record + "/pdf-exports");
  }

  @Test
  void aRecordWithNoPagesOffersNoExportAddress() throws Exception {
    long empty = PdfFixtures.record(jdbc, PdfFixtures.archive(jdbc), "Empty");

    JsonNode doc = json.readTree(get("/v1/documents/" + empty, true).body());

    assertThat(doc.get("links").has("pdfExport")).isFalse();
  }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*PdfExportApiTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `theSynchronousPdfUrlsNoLongerExist` and `theMachineApiPointsAtTheExportAddress` FAIL; `aRecordWithNoPagesOffersNoExportAddress` passes already.

- [ ] **Step 3: Remove the two endpoints from `FileController`**

Run:

```bash
cd backend && python3 - <<'PY'
p='src/main/java/place/icomb/archiver/controller/FileController.java'
s=open(p).read()
def rep(a,b):
    global s
    assert s.count(a)==1,(s.count(a),a[:70])
    s=s.replace(a,b)
i=s.index('  @GetMapping("/records/{recordId}/pdf")')
s=s[:i].rstrip()+"\n}\n"
rep("  private final PdfExportService pdfExportService;\n","")
rep("      ThumbnailService thumbnailService,\n      PdfExportService pdfExportService) {","      ThumbnailService thumbnailService) {")
rep("    this.pdfExportService = pdfExportService;\n","")
rep("import place.icomb.archiver.service.PdfExportService;\n","")
rep(" * Serving bytes: page scans, thumbnails, and the PDF exports built on demand.",
    " * Serving bytes: page scans and thumbnails. PDFs are requested and fetched through\n * {@link PdfExportController}.")
open(p,'w').write(s)
print("FileController edited")
PY
./gradlew spotlessApply -q && ./gradlew compileJava 2>&1 | grep -E "error|BUILD"
```

Expected: `FileController edited`, then `BUILD SUCCESSFUL` (spotless removes the imports that became unused; if the compile reports one still referenced, it is a real use and must stay).

- [ ] **Step 4: Remove the `byte[]` API from `PdfExportService`, and move its tests to `buildToFile`**

Run:

```bash
cd backend && python3 - <<'PY'
p='src/main/java/place/icomb/archiver/service/PdfExportService.java'
s=open(p).read()
def cut(start_marker, end_marker, include_end=True):
    global s
    assert s.count(start_marker)==1,(s.count(start_marker),start_marker[:60])
    i=s.index(start_marker)
    j=s.index(end_marker,i)+(len(end_marker) if include_end else 0)
    s=s[:i]+s[j:].lstrip("\n")

# the two public buildPdf methods and their Javadoc
cut("  /**\n   * Builds a PDF containing the specified page images for a record.",
    "      case ORIGINAL -> buildOriginalPdf(recordId, seqNumbers);\n    };\n  }\n")
# the Render interface and toBytes
cut("  /** Draws a variant into an open document. */",
    "      return out.toByteArray();\n    }\n  }\n")
# the three one-line wrappers
for name,render in [("buildEnglishPdf","renderEnglish"),("buildSideBySidePdf","renderSideBySide")]:
    cut(f"  private byte[] {name}(Long recordId, List<Integer> seqNumbers) throws IOException {{\n    return toBytes(doc -> {render}(doc, recordId, seqNumbers));\n  }}\n","  }\n")
# buildOriginalPdf, with the Javadoc that sat above it
cut("  /**\n   * The scans, with an invisible text layer over each.",
    "    return toBytes(doc -> renderOriginal(doc, recordId, seqNumbers));\n  }\n")
open(p,'w').write(s)
print("PdfExportService trimmed")
PY
grep -n "toBytes\|buildPdf\|Render\b" src/main/java/place/icomb/archiver/service/PdfExportService.java
./gradlew spotlessApply -q && ./gradlew compileJava 2>&1 | grep -E "error|BUILD"
```

Expected: `PdfExportService trimmed`, the `grep` prints nothing, and the compile succeeds. If the `cut` for the English or side-by-side wrapper removed too much (its end marker `  }\n` is the first one after the wrapper, which is the wrapper's own closing brace), restore with `git checkout backend/src/main/java/place/icomb/archiver/service/PdfExportService.java`, re-apply only Task 2 from the log, and redo this step by hand.

Then, in `buildToFile`'s Javadoc, append this paragraph (it was on the removed `buildPdf`):

```java
   *
   * <p>ENGLISH renders one PDF page per source page, so the export lines up with the original page
   * for page: a page with no text still produces a page rather than shifting everything after it,
   * which would make the two impossible to read side by side.
```

Now move the tests. Run:

```bash
cd backend && python3 - <<'PY'
p='src/test/java/place/icomb/archiver/service/PdfExportServiceTest.java'
s=open(p).read()
n=s.count("pdfExportService.buildPdf(")
s=s.replace("pdfExportService.buildPdf(","build(")
helper='''
  /** Builds a variant to a file, the way the worker does, and reads it back. */
  private byte[] build(Long recordId, List<Integer> seqNumbers, PdfExportService.Variant variant)
      throws Exception {
    Path dir = Files.createTempDirectory("pdf-build");
    Path out = dir.resolve("out.pdf");
    pdfExportService.buildToFile(recordId, seqNumbers, variant, out, dir);
    return Files.readAllBytes(out);
  }
'''
marker="  private String textOf(byte[] pdf) throws Exception {"
assert s.count(marker)==1
s=s.replace(marker, helper.lstrip("\n")+"\n"+marker)
s=s.replace("theFileBuildCarriesTheSameContentAsTheInMemoryBuild","theFileBuildCarriesTheTranslation")
open(p,'w').write(s)
print("tests moved:", n)
PY
./gradlew spotlessApply -q && ./gradlew test --tests '*PdfExportServiceTest' 2>&1 | grep -E "FAILED|error:|BUILD"
```

Expected: `tests moved: N` (about 14), then `BUILD SUCCESSFUL`. Every existing assertion now runs against the file-based build, so they check the new path rather than the one being removed.

- [ ] **Step 5: Point the machine API at the export address**

Run:

```bash
cd backend && python3 - <<'PY'
p='src/main/java/place/icomb/archiver/controller/ApiController.java'
s=open(p).read()
def rep(a,b,count=1):
    global s
    assert s.count(a)==count,(s.count(a),a[:70])
    s=s.replace(a,b)
rep('''    if (record.getPdfAttachmentId() != null) {
      links.put("pdf", baseUrl + "/records/" + recordId + "/pdf");
    }''','''    if (record.getPageCount() > 0) {
      // POST here to have a PDF built: it answers with an id to poll, then a file to fetch.
      links.put("pdfExport", baseUrl + "/records/" + recordId + "/pdf-exports");
    }''')
rep('''      if (r.getPdfAttachmentId() != null) {
        result.put("pdfUrl", baseUrl + "/records/" + r.getId() + "/pdf");
      }''','''      if (r.getPageCount() > 0) {
        result.put("pdfExportUrl", baseUrl + "/records/" + r.getId() + "/pdf-exports");
      }''',2)
open(p,'w').write(s)

p='src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java'
s=open(p).read()
rep("links to images/PDF.","links to the page images and to the address a PDF is requested from (pdfExportUrl).")
open(p,'w').write(s)
print("machine API edited")
PY
./gradlew spotlessApply -q && ./gradlew test --tests '*PdfExportApiTest' --tests '*ApiDocumentTest' 2>&1 | grep -E "FAILED|error:|BUILD"
```

Expected: `machine API edited`, then `BUILD SUCCESSFUL`.

- [ ] **Step 6: Update the API skill**

Run:

```bash
python3 - <<'PY'
p='.claude/skills/archiver-api/SKILL.md'
s=open(p).read()
a=s.index("## Exports")
b=s.index("## After a release")
new='''## PDFs

Every PDF is built on demand and takes the same asynchronous path, whatever its size: ask, poll,
download. Any signed-in user may ask; there is no limit on how many.

```bash
POST /api/records/{id}/pdf-exports   {"variant":"original","pages":"46-50"}   → 202 {id,state,…}
GET  /api/pdf-exports/{id}                                                   → {state,bytes,expiresAt,error}
GET  /api/pdf-exports/{id}/file                                              → the PDF (200 once ready)
```

`variant` is `original` (the scans with a searchable text layer), `english` or `side-by-side`;
both fields are optional (`original`, the whole record). `pages` takes ranges and comma lists
(`1,3,5-10`). `state` runs `queued`, `building`, `ready`, or ends `failed` (with `error`) or
`expired`. An identical request is reused: `200` while a finished one is unexpired, `202` and the
same id while one is in flight. A finished PDF is kept **24 hours**; after that the file answers
`410` and you ask again. `409` from `…/file` means not ready yet (or failed, with the reason).

Machine clients find the address in `links.pdfExport` (one record) or `pdfExportUrl` (search and
browse results). `GET /api/records/{id}/pdf` and `…/export-pdf` no longer exist.

'''
s=s[:a]+new+s[b:]
open(p,'w').write(s)
print("skill updated")
PY
grep -n "export-pdf\|/pdf\b" .claude/skills/archiver-api/SKILL.md | head
```

Expected: `skill updated`; the `grep` shows only the sentence saying the old URLs no longer exist.

- [ ] **Step 7: Check that nothing else still uses the old paths**

Run: `grep -rn "export-pdf\|records/[^ ]*/pdf\b\|buildPdf(" backend/src frontend/src frontend/tests | grep -v "pdf-exports" | head`
Expected: no output.

- [ ] **Step 8: Run the whole backend and commit**

Run: `cd backend && ./gradlew spotlessCheck test 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`.

```bash
git add -A
git commit -m "pdf: retire the synchronous paths; the machine API points at the export address

GET /api/records/{id}/pdf and /export-pdf are removed, with the byte[] builders behind them:
every PDF now takes the asynchronous path. The existing builder tests run against buildToFile,
so they exercise the code that remains. links.pdf and pdfUrl become pdfExport and pdfExportUrl,
present whenever a record has pages. The stored PDFs are no longer served; retiring them is a
separate cleanup."
```

---

### Task 10: Every check, the test stack, the browser, the big record, release

**Files:**
- Modify: `CHANGELOG.md` (release entry)

**Interfaces:**
- Consumes: everything above, deployed as the `:test` build.

- [ ] **Step 1: Run every check locally**

Run, and read the output:

```bash
cd backend && ./gradlew spotlessApply && ./gradlew spotlessCheck test 2>&1 | grep -E "FAILED|BUILD"
python3 - <<'PY'
import glob,re
t=f=e=sk=0
for x in glob.glob('build/test-results/test/*.xml'):
    m=re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"',open(x).read())
    a,b,c,d=map(int,m.groups()); t+=a;sk+=b;f+=c;e+=d
print("total",t,"skipped",sk,"failures",f,"errors",e)
PY
cd ../frontend && bun run test 2>&1 | tail -4 && bun run check 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`; **0 failures, 0 errors**, the total above the previous 468; frontend tests pass; `svelte-check found 0 errors`.

- [ ] **Step 2: Push untagged and wait for the test build**

```bash
git push origin main
```

Then `jk run ls archiver | head -2` (never `jk run start`), and `jk run view archiver <n> --wait`. Expected: `Result: SUCCESS`, and `jk log archiver <n> | grep "Deploying to"` shows `test`.

- [ ] **Step 3: Confirm the test stack is on the new build and the worker is running**

```bash
ssh zelkova 'curl -s http://localhost:8090/api/version; echo; docker logs --since 5m archiver-test-backend-test-1 2>&1 | grep -E "PDF export worker|Registered .* PDF export|ERROR" | tail -5'
```

Expected: the commit of the Step 2 push, and `Registered 2 PDF export worker(s) and the reaper`.

- [ ] **Step 4: Drive the API on the test stack**

From `zelkova`, with the test stack's admin token (`docker inspect archiver-test-backend-test-1`, env `ARCHIVER_ADMIN_TOKEN`), pick a small real record (a handful of pages, found with `GET /api/v1/documents?archiveId=…&size=5`) and:

1. `POST /api/records/{id}/pdf-exports` with `{}` → expect `202`; poll `GET /api/pdf-exports/{id}` until `ready`; `GET …/file` → save it and confirm `pdfinfo` reports the record's page count.
2. Repeat the `POST` → expect `200` and the **same id**.
3. `POST` with `{"variant":"english","pages":"1-2"}` and `{"variant":"side-by-side"}` → both reach `ready`.
4. `GET /api/records/{id}/pdf` and `…/export-pdf?pages=1` → both `404`.
5. `docker exec archiver-test-backend-test-1 sh -c 'ls -la /data/archiver_store/exports/tmp'` → empty.

Record what was checked. **Stop and report if any step fails; do not release.**

- [ ] **Step 5: The browser check**

The test UI is at `http://zelkova:3090` (its nginx supplies a fixed identity, so no sign-in). With Playwright:

1. Navigate to a small record's page. Click the download button. Confirm it shows the spinner and **Preparing PDF…**, stays disabled while it works, and a file downloads when it finishes (the browser's download event fires; the page does not navigate away).
2. Choose **English translation** in the variant select and download again; confirm a second, different file.
3. Type `1-2` in the page picker and download; confirm the PDF has two pages.
4. Keep a page on its viewer and use **Download kept** there.
5. Open the browser console: **no `PAGEERROR`**, no `state_unsafe_mutation`, no failed requests other than intended ones.

This is the check that caught the v1.1.13 failure; do not skip it because the component "looks simple".

- [ ] **Step 6: The large record**

Export the largest record on the test stack, **3780** (its stored PDF is 604 MB), and record the cost. From `zelkova`:

```bash
# before
docker exec archiver-test-backend-test-1 df -h /data/archiver_store | tail -1
# request, then watch while it builds
docker stats --no-stream --format '{{.Name}} mem={{.MemUsage}} cpu={{.CPUPerc}}' archiver-test-backend-test-1
docker exec archiver-test-backend-test-1 sh -c 'du -sh /data/archiver_store/exports/tmp'
```

Take the `docker stats` and `du` readings a few times while the export is `building`. Then record: wall-clock time from `POST` to `ready`, the backend container's peak memory, the peak size of `exports/tmp/`, the final file size, and free space after. Request a large `english` range (say `1-200`) the same way. Confirm afterwards that `exports/tmp/` is empty again.

If the backend's memory climbs with the size of the output, the build is holding the document on the heap and **the release is blocked**; report the readings. If `exports/tmp/` exhausts the volume, report that instead.

- [ ] **Step 7: Release**

Add above `## v1.1.15` in `CHANGELOG.md` a `## v1.1.16 — <date>` entry covering: every PDF now built on demand through one asynchronous path (request, poll, download), for all three variants and any page selection; finished PDFs kept 24 hours and reused for identical requests, with a fingerprint that makes any edit, re-OCR, translation or move start a new one; temp files in a temp area under the storage root; the viewer's three download buttons sharing one; the old `/pdf` and `/export-pdf` URLs removed and the machine API's `pdf` links replaced by `pdfExport`; the stored PDFs no longer served. Then:

```bash
git add CHANGELOG.md
git commit -m "release: v1.1.16 — every PDF is built on demand"
git tag v1.1.16
git push origin main
git push origin v1.1.16
```

- [ ] **Step 8: Verify production**

Find and wait for the release build (`jk run ls archiver | head -2`; `jk run view archiver <n> --wait`; expect `SUCCESS` and `Deploying to PRODUCTION (v1.1.16)` in `jk log`). Then on `zelkova`:

- `curl -s http://10.0.9.3:8080/api/version` → `v1.1.16` at the tag's commit.
- `curl -s -o /dev/null -w "%{http_code}" https://archive.czernin.eu/` → `200`.
- `docker ps -a --filter name=archiver-` → backend and frontend `Up`, nothing restarting.
- `docker logs --since 5m archiver-backend-1 2>&1 | grep -E "Registered .* PDF export|ERROR"` → the registration line, no errors.
- One export through the production API with the admin token, a small record: `POST` → poll → `ready` → the file opens.

Do not request a large production export as a release check.

---

## Self-Review

**Spec coverage** (`2026-09-30-on-demand-pdf-design.md`):
- One mechanism for every PDF and variant; page selection resolved to page ids → Tasks 3, 4, 5, 8.
- `pdf_export` table, own table not `job` → Task 3 (`V18`).
- Files at `exports/{xx}/{uuid}.pdf`, built to `.partial` in the temp area and renamed → Tasks 1, 4.
- Temp area `exports/tmp/` under the storage root, never `/tmp`; PDFBox scratch directed there → Tasks 1, 2 (`buildToFile`), 4 (`recoverAfterRestart` empties it).
- Fingerprint contents and reuse rules → Task 3 tests (every change that must alter it, and stability).
- API: `POST` 200/202/404/400, `GET` status, `GET` file 200/409/410/404 → Task 5. The `429` row was removed from the spec and is not implemented; Task 5 has a test that eight open exports are all accepted.
- Claiming with `SKIP LOCKED`; concurrency default 2 → Tasks 3, 6.
- Failure with a message; no retry → Task 4.
- Interrupted builds: restart fails them at once, two-hour rule for a hang → Tasks 3, 4 (spec updated to match).
- Expiry 24 hours, reaper every 15 minutes, row purge after 7 days → Tasks 3, 4, 6.
- Deleting a record removes its export files → Task 7.
- Free-space guard 2 GB → Task 4 (test sets the threshold via reflection).
- Viewer: one helper, pure functions tested, button states, three places replaced, strings in three languages → Task 8.
- Machine API `links.pdf`/`pdfUrl` → `pdfExport`/`pdfExportUrl`; MCP wording; skill → Task 9.
- Old URLs and `byte[]` builders removed → Task 9.
- Testing section: integration, frontend, browser, massive → Tasks 3–5, 8, 10 (Steps 5, 6).
- Out of scope respected: the pipeline's PDF stage and stored PDFs are untouched (`buildRecordPdfToFile` stays for `SearchablePdfWorker`).

**Placeholder scan:** no `TBD`/`TODO`/"handle edge cases". Three steps tell the implementer to read before editing because the exact text was not captured: Task 7 Step 3 (the `IngestService` constructor, whose shape is described and whose new lines are given), Task 8 Step 8 (an unused `Download` import, if `svelte-check` reports one), and Task 9 Step 4 (a recovery path if a `cut` removes too much). Each states the exact change or the exact fallback.

**Type consistency:** `PdfExportQueue.View/Requested/Claimed/Row` are defined in Task 3 and used unchanged in Tasks 4–5. `Variant` is `PdfExportService.Variant` throughout; `PdfExportQueue.parseVariant`, `dbName`, `fromDb`, `apiName` and `fileSuffix` are defined once in Task 3. `buildToFile(Long, List<Integer>, Variant, Path, Path)` (Task 2) is called with that signature in Task 4 and the Task 9 test helper. `PdfExportWorker.runOnce/drain/reap/recoverAfterRestart` (Task 4) match Tasks 5–7 and 9. The frontend `ExportView`, `describeState`, `nextPollDelay`, `errorFrom`, `exportRequest`, `createUrl`, `statusUrl`, `fileUrl` are defined in Task 8 Step 3 and used unchanged by the component in Step 5.
