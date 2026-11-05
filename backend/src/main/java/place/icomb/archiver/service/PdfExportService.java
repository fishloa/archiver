package place.icomb.archiver.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessStreamCache;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.model.Page;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.repository.PageRepository;

@Service
public class PdfExportService {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(PdfExportService.class);

  /** Base URL used in the side-by-side footer, so an extract can be traced back to the archive. */
  @org.springframework.beans.factory.annotation.Value(
      "${archiver.public-url:https://archive.czernin.eu}")
  private String publicUrl;

  private final PageRepository pageRepository;
  private final AttachmentRepository attachmentRepository;
  private final StorageService storageService;
  private final OcrImageService ocrImageService;
  private final CoverSheetService coverSheetService;
  private final TranslationService translationService;
  private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  public PdfExportService(
      PageRepository pageRepository,
      AttachmentRepository attachmentRepository,
      StorageService storageService,
      OcrImageService ocrImageService,
      CoverSheetService coverSheetService,
      TranslationService translationService,
      org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.storageService = storageService;
    this.ocrImageService = ocrImageService;
    this.coverSheetService = coverSheetService;
    this.translationService = translationService;
    this.jdbcTemplate = jdbcTemplate;
  }

  /** A renderer wired to this record's scans, so figures can be cut out and drawn inline. */
  private MarkdownPdfRenderer newRenderer(PDDocument doc) throws IOException {
    MarkdownPdfRenderer renderer = new MarkdownPdfRenderer(doc);
    renderer.setOcrImageService(ocrImageService);
    return renderer;
  }

  static String textLabel(String lang, boolean transcription) {
    return CoverSheetService.languageName(lang)
        + (transcription ? " transcription" : " translation");
  }

  private boolean transcribedIn(Long recordId, String lang) {
    return !"en".equals(lang)
        && lang.equals(
            jdbcTemplate.queryForObject(
                "SELECT lang FROM record WHERE id = ?", String.class, recordId));
  }

  private java.util.Map<Integer, String> textsIn(
      Long recordId, String lang, boolean transcription) {
    if ("en".equals(lang)) {
      return translationService.bestEnglishByRecord(recordId);
    }
    if (!transcription) {
      return translationService.bestByRecord(recordId, lang);
    }
    java.util.Map<Integer, String> out = new java.util.HashMap<>();
    jdbcTemplate.query(
        "SELECT p.seq, pt.text_raw FROM page p JOIN page_text pt ON pt.page_id = p.id"
            + " WHERE p.record_id = ?",
        rs -> {
          if (rs.getString(2) != null) {
            out.put(rs.getInt(1), rs.getString(2));
          }
        },
        recordId);
    return out;
  }

  /**
   * Opens an extract with the record's cover sheet.
   *
   * <p>On the two generated exports only. The scan export is what the stored searchable PDFs are
   * built from, and prefixing those would mean rebuilding all 3,127 of them.
   */
  private void addCoverSheet(
      PDDocument doc,
      MarkdownPdfRenderer renderer,
      PDRectangle size,
      Long recordId,
      List<Integer> seqNumbers,
      String lang,
      float margin)
      throws IOException {
    try {
      CoverSheetService.Cover cover = coverSheetService.gather(recordId, seqNumbers, lang);
      coverSheetService.render(
          doc, renderer, size, cover, recordId, publicUrl + "/records/" + recordId, margin);
    } catch (Exception e) {
      // An extract without its cover is still the document; failing the export is not better.
      log.warn("Could not build the cover sheet for record {}", recordId, e);
    }
  }

  /** What an export contains. */
  public enum Variant {
    /** The scanned images, exactly as held. */
    ORIGINAL,
    /** The text of each page, rendered from its markdown: English unless asked otherwise. */
    TRANSLATION,
    /** Scan and translation facing each other on one landscape page. */
    SIDE_BY_SIDE
  }

  /**
   * Builds any variant of the selected pages straight to a file.
   *
   * <p>The document is backed by PDFBox scratch files in {@code scratchDir}, and saved without ever
   * being held as one array, so a 942-page record of full-resolution scans is no different from a
   * three-page extract. The caller chooses the scratch directory: the archive's own volume, not
   * {@code /tmp}.
   *
   * <p>TRANSLATION renders one PDF page per source page, so the export lines up with the original
   * page for page: a page with no text still produces a page rather than shifting everything after
   * it, which would make the two impossible to read side by side.
   *
   * @param lang en, de or cs; the scans ignore it
   * @return the number of PDF pages written
   */
  public int buildToFile(
      Long recordId,
      List<Integer> seqNumbers,
      Variant variant,
      String lang,
      Path target,
      Path scratchDir)
      throws IOException {
    RandomAccessStreamCache.StreamCacheCreateFunction cache =
        () ->
            new ScratchFile(MemoryUsageSetting.setupTempFileOnly().setTempDir(scratchDir.toFile()));
    try (PDDocument doc = new PDDocument(cache)) {
      switch (variant) {
        case ORIGINAL -> renderOriginal(doc, recordId, seqNumbers);
        case TRANSLATION -> renderTranslation(doc, recordId, seqNumbers, lang);
        case SIDE_BY_SIDE -> renderSideBySide(doc, recordId, seqNumbers, lang);
      }
      if (doc.getNumberOfPages() == 0) {
        throw new IOException("No valid pages found for the given selection");
      }
      doc.save(target.toFile());
      return doc.getNumberOfPages();
    }
  }

  /** The highest page number any selection may name when the record's own size is unknown. */
  public static final int MAX_PAGE_NUMBER = 100_000;

  /**
   * Parses a page range string like "1,2,3,5-19,21,23" into a sorted set of individual page
   * numbers, none above {@link #MAX_PAGE_NUMBER}.
   */
  public List<Integer> parsePageRange(String rangeStr) {
    return parsePageRange(rangeStr, MAX_PAGE_NUMBER);
  }

  /**
   * Parses a page range like {@code 1,2,5-19}, clamping every range to {@code maxSeq} before it is
   * expanded, so a range such as {@code 1-99999999} costs no more than the record has pages.
   *
   * @return the sorted, unique page numbers from 1 to {@code maxSeq}; empty if {@code maxSeq < 1}
   * @throws IllegalArgumentException if the range is malformed
   */
  public List<Integer> parsePageRange(String rangeStr, int maxSeq) {
    TreeSet<Integer> pages = new TreeSet<>();
    if (rangeStr == null || rangeStr.isBlank() || maxSeq < 1) {
      return new ArrayList<>(pages);
    }
    for (String part : rangeStr.split(",")) {
      part = part.trim();
      if (part.isEmpty()) continue;
      if (part.contains("-")) {
        String[] bounds = part.split("-", 2);
        int start = Integer.parseInt(bounds[0].trim());
        int end = Integer.parseInt(bounds[1].trim());
        if (start > end) {
          throw new IllegalArgumentException("Invalid range: " + part);
        }
        addClamped(pages, start, end, maxSeq);
      } else {
        int n = Integer.parseInt(part);
        addClamped(pages, n, n, maxSeq);
      }
    }
    return new ArrayList<>(pages);
  }

  private static void addClamped(TreeSet<Integer> pages, int start, int end, int maxSeq) {
    int last = Math.min(end, maxSeq);
    for (int i = start; i <= last; i++) {
      pages.add(i);
    }
  }

  /**
   * Scan and translation facing each other, one landscape page per source page.
   *
   * <p>The scan carries an invisible text layer positioned from the OCR engine's own block
   * coordinates, so selecting or searching text in the image half lands on the right words — rather
   * than the evenly-spread approximation that puts every line at the left margin.
   *
   * <p>A footer links back to the page in the archive, so a printed or forwarded extract can be
   * traced to its source.
   */
  private void renderSideBySide(
      PDDocument doc, Long recordId, List<Integer> seqNumbers, String lang) throws IOException {
    final float margin = 28f;
    final float gutter = 16f;
    final float footerHeight = 22f;

    {
      MarkdownPdfRenderer renderer = newRenderer(doc);
      PDRectangle landscape =
          new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());

      addCoverSheet(doc, renderer, landscape, recordId, seqNumbers, lang, margin);

      boolean english = "en".equals(lang);
      boolean transcription = transcribedIn(recordId, lang);
      String label = english ? "" : textLabel(lang, transcription);
      java.util.Map<Integer, String> best = textsIn(recordId, lang, transcription);

      for (int seq : seqNumbers) {
        List<java.util.Map<String, Object>> rows =
            jdbcTemplate.queryForList(
                """
                SELECT p.id AS page_id, p.attachment_id, pt.text_en, pt.text_raw,
                       pt.raw_response::text AS raw_response
                FROM page p LEFT JOIN page_text pt ON pt.page_id = p.id
                WHERE p.record_id = ? AND p.seq = ?
                """,
                recordId,
                seq);
        if (rows.isEmpty()) {
          continue;
        }
        java.util.Map<String, Object> row = rows.get(0);

        String shown = best.getOrDefault(seq, english ? (String) row.get("text_en") : null);
        String raw = (String) row.get("text_raw");
        boolean hasText = shown != null && !shown.isBlank();
        String rightText = hasText ? shown : (raw == null ? "" : raw);
        String heading = hasText ? label : "";

        float halfWidth = (landscape.getWidth() - 2 * margin - gutter) / 2f;
        float contentTop = landscape.getHeight() - margin;
        float contentBottom = margin + footerHeight;

        Long pageId = row.get("page_id") == null ? null : ((Number) row.get("page_id")).longValue();
        List<MarkdownPdfRenderer.El> lines =
            renderer.layoutTo(rightText, halfWidth, contentTop - contentBottom, pageId);
        int lineIndex = 0;
        int pageOfPage = 0;

        do {
          PDPage page = new PDPage(landscape);
          doc.addPage(page);
          pageOfPage++;
          try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            // Left: the scan, on the first page for this source page only — a long translation
            // continues beside blank space rather than repeating the image.
            if (pageOfPage == 1 && row.get("attachment_id") != null) {
              drawScanWithInvisibleText(
                  doc,
                  cs,
                  renderer,
                  ((Number) row.get("attachment_id")).longValue(),
                  (String) row.get("raw_response"),
                  raw,
                  margin,
                  contentBottom,
                  halfWidth,
                  contentTop - contentBottom);
            }

            // Right: the translation.
            lineIndex =
                renderer.drawElements(
                    cs, lines, lineIndex, margin + halfWidth + gutter, contentTop, contentBottom);
            renderer.drawText(
                cs, heading, margin + halfWidth + gutter, contentTop + 8f, 7.5f, false);

            // Footer: where this page lives in the archive, and which page it is.
            renderer.drawFooter(
                page,
                cs,
                landscape.getWidth(),
                margin,
                margin,
                publicUrl + "/records/" + recordId + "/pages/" + seq,
                MarkdownPdfRenderer.pageLabel(seq, pageOfPage > 1));
          }
        } while (lineIndex < lines.size());
      }
    }
  }

  /** Draws the scan scaled into a box, with an invisible text layer over it. */
  private void drawScanWithInvisibleText(
      PDDocument doc,
      PDPageContentStream cs,
      MarkdownPdfRenderer renderer,
      long attachmentId,
      String rawResponseJson,
      String plainText,
      float boxX,
      float boxY,
      float boxW,
      float boxH)
      throws IOException {
    Attachment attachment = attachmentRepository.findById(attachmentId).orElse(null);
    if (attachment == null) {
      return;
    }
    PDImageXObject image =
        PDImageXObject.createFromFileByContent(storageService.getPath(attachment).toFile(), doc);

    float scale = Math.min(boxW / image.getWidth(), boxH / image.getHeight());
    float drawW = image.getWidth() * scale;
    float drawH = image.getHeight() * scale;
    float drawX = boxX + (boxW - drawW) / 2f;
    float drawY = boxY + (boxH - drawH) / 2f;
    cs.drawImage(image, drawX, drawY, drawW, drawH);

    drawInvisibleTextLayer(cs, renderer, rawResponseJson, drawX, drawY, drawW, drawH);
  }

  /**
   * Draws the OCR text invisibly over a drawn image.
   *
   * <p>Positioned from the engine's block boxes and scaled onto wherever the image landed, so a
   * selection in the PDF matches the words under it.
   */
  private void drawInvisibleTextLayer(
      PDPageContentStream cs,
      MarkdownPdfRenderer renderer,
      String rawResponseJson,
      float drawX,
      float drawY,
      float drawW,
      float drawH)
      throws IOException {
    List<Block> blocks = parseBlocks(rawResponseJson);
    if (blocks.isEmpty()) {
      return;
    }

    cs.setRenderingMode(org.apache.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER);
    for (Block b : blocks) {
      if (b.content() == null || b.content().isBlank()) continue;

      float sx = drawW / b.pageWidth();
      float sy = drawH / b.pageHeight();
      String[] blockLines = b.content().split("\\n");
      float blockH = (b.bottom() - b.top()) * sy;
      float lineH = Math.max(4f, blockH / Math.max(1, blockLines.length));

      for (int i = 0; i < blockLines.length; i++) {
        // Plain text, not markdown: this layer is what a reader's search box matches against,
        // and an unstripped line puts "![img-0.jpeg](img-0.jpeg)" and heading hashes into the
        // searchable text of the scan.
        String text = renderer.forDrawing(toPlainText(blockLines[i]));
        if (text.isBlank()) continue;
        float size = Math.max(3f, Math.min(lineH * 0.85f, 14f));
        // PDF space starts at the bottom of the page, the scan's at the top.
        float y = drawY + drawH - (b.top() * sy) - (i + 1) * lineH;
        float x = drawX + b.left() * sx;
        cs.beginText();
        cs.setFont(renderer.regularFont(), size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
      }
    }
    cs.setRenderingMode(org.apache.pdfbox.pdmodel.graphics.state.RenderingMode.FILL);
  }

  /** Strips markdown so the invisible layer holds what the page actually says. */
  private static String toPlainText(String line) {
    return MarkdownPdfRenderer.stripInline(line.replaceFirst("^#{1,6}\\s+", ""));
  }

  private record Block(
      String content,
      float left,
      float top,
      float right,
      float bottom,
      float pageWidth,
      float pageHeight) {}

  /** Reads block boxes from the OCR response, tolerating anything unexpected. */
  private List<Block> parseBlocks(String rawResponseJson) {
    List<Block> out = new java.util.ArrayList<>();
    if (rawResponseJson == null || rawResponseJson.isBlank()) return out;
    try {
      com.fasterxml.jackson.databind.JsonNode root =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawResponseJson);
      com.fasterxml.jackson.databind.JsonNode page = root.path("pages").path(0);
      float pw = (float) page.path("dimensions").path("width").asDouble(0);
      float ph = (float) page.path("dimensions").path("height").asDouble(0);
      if (pw <= 0 || ph <= 0) return out;
      for (com.fasterxml.jackson.databind.JsonNode b : page.path("blocks")) {
        out.add(
            new Block(
                b.path("content").asText(""),
                (float) b.path("top_left_x").asDouble(0),
                (float) b.path("top_left_y").asDouble(0),
                (float) b.path("bottom_right_x").asDouble(0),
                (float) b.path("bottom_right_y").asDouble(0),
                pw,
                ph));
      }
    } catch (Exception e) {
      // An unreadable response costs the invisible layer, not the export.
      log.warn("Could not read OCR blocks for the invisible text layer", e);
    }
    return out;
  }

  private void renderTranslation(
      PDDocument doc, Long recordId, List<Integer> seqNumbers, String lang) throws IOException {
    {
      MarkdownPdfRenderer renderer = newRenderer(doc);
      addCoverSheet(doc, renderer, PDRectangle.A4, recordId, seqNumbers, lang, 56f);
      boolean english = "en".equals(lang);
      boolean transcription = transcribedIn(recordId, lang);
      String label = english ? "" : textLabel(lang, transcription);
      java.util.Map<Integer, String> best = textsIn(recordId, lang, transcription);
      for (int seq : seqNumbers) {
        List<java.util.Map<String, Object>> rows =
            jdbcTemplate.queryForList(
                """
                SELECT p.id AS page_id, pt.text_en, pt.text_raw
                FROM page p JOIN page_text pt ON pt.page_id = p.id
                WHERE p.record_id = ? AND p.seq = ?
                """,
                recordId,
                seq);

        String text = "";
        String note = "";
        Long pageId = null;
        if (!rows.isEmpty()) {
          pageId = ((Number) rows.get(0).get("page_id")).longValue();
          String shown =
              best.getOrDefault(seq, english ? (String) rows.get(0).get("text_en") : null);
          if (shown != null && !shown.isBlank()) {
            text = shown;
            note = label;
          } else {
            // Untranslated pages fall back to the original transcription rather than appearing
            // blank, so the export never silently omits a page's content.
            String raw = (String) rows.get(0).get("text_raw");
            text = raw == null ? "" : raw;
            note = raw == null || raw.isBlank() ? "[no text]" : "[not translated - original text]";
          }
        } else {
          note = "[no text]";
        }
        renderer.renderPage(
            doc, text, note, pageId, publicUrl + "/records/" + recordId + "/pages/" + seq, seq);
      }
    }
  }

  /** Draws the scans with their invisible text layers into an open document. */
  private void renderOriginal(PDDocument doc, Long recordId, List<Integer> seqNumbers)
      throws IOException {
    {
      MarkdownPdfRenderer renderer = newRenderer(doc);

      for (int seq : seqNumbers) {
        Page page = pageRepository.findByRecordIdAndSeq(recordId, seq).orElse(null);
        if (page == null || page.getAttachmentId() == null) {
          continue;
        }
        Attachment attachment = attachmentRepository.findById(page.getAttachmentId()).orElse(null);
        if (attachment == null) {
          continue;
        }

        Path imagePath = storageService.getPath(attachment);
        PDImageXObject image = PDImageXObject.createFromFileByContent(imagePath.toFile(), doc);

        float imgWidth = image.getWidth();
        float imgHeight = image.getHeight();
        PDPage pdfPage = new PDPage(new PDRectangle(imgWidth, imgHeight));
        doc.addPage(pdfPage);

        List<java.util.Map<String, Object>> rows =
            jdbcTemplate.queryForList(
                "SELECT raw_response::text AS raw_response FROM page_text WHERE page_id = ?",
                page.getId());
        String rawResponse = rows.isEmpty() ? null : (String) rows.get(0).get("raw_response");

        try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
          cs.drawImage(image, 0, 0, imgWidth, imgHeight);
          drawInvisibleTextLayer(cs, renderer, rawResponse, 0, 0, imgWidth, imgHeight);
        }
      }
    }
  }
}
