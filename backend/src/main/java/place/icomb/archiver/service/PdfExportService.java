package place.icomb.archiver.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
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
  private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  public PdfExportService(
      PageRepository pageRepository,
      AttachmentRepository attachmentRepository,
      StorageService storageService,
      OcrImageService ocrImageService,
      org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.storageService = storageService;
    this.ocrImageService = ocrImageService;
    this.jdbcTemplate = jdbcTemplate;
  }

  /** A renderer wired to this record's scans, so figures can be cut out and drawn inline. */
  private MarkdownPdfRenderer newRenderer(PDDocument doc) throws IOException {
    MarkdownPdfRenderer renderer = new MarkdownPdfRenderer(doc);
    renderer.setOcrImageService(ocrImageService);
    return renderer;
  }

  /** What an export contains. */
  public enum Variant {
    /** The scanned images, exactly as held. */
    ORIGINAL,
    /** The English translation, rendered from the markdown the OCR produced. */
    ENGLISH,
    /** Scan and translation facing each other on one landscape page. */
    SIDE_BY_SIDE
  }

  /**
   * Parses a page range string like "1,2,3,5-19,21,23" into a sorted set of individual page
   * numbers.
   */
  public List<Integer> parsePageRange(String rangeStr) {
    TreeSet<Integer> pages = new TreeSet<>();
    if (rangeStr == null || rangeStr.isBlank()) {
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
        for (int i = start; i <= end; i++) {
          pages.add(i);
        }
      } else {
        pages.add(Integer.parseInt(part));
      }
    }
    return new ArrayList<>(pages);
  }

  /**
   * Builds a PDF containing the specified page images for a record. Returns the PDF as a byte
   * array.
   */
  public byte[] buildPdf(Long recordId, List<Integer> seqNumbers) throws IOException {
    return buildPdf(recordId, seqNumbers, Variant.ORIGINAL);
  }

  /**
   * Builds an export of the selected pages.
   *
   * <p>ENGLISH renders one PDF page per source page, so the export lines up with the original page
   * for page — a page with no text still produces a page rather than shifting everything after it,
   * which would make the two impossible to read side by side.
   */
  public byte[] buildPdf(Long recordId, List<Integer> seqNumbers, Variant variant)
      throws IOException {
    return switch (variant) {
      case ENGLISH -> buildEnglishPdf(recordId, seqNumbers);
      case SIDE_BY_SIDE -> buildSideBySidePdf(recordId, seqNumbers);
      case ORIGINAL -> buildOriginalPdf(recordId, seqNumbers);
    };
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
  private byte[] buildSideBySidePdf(Long recordId, List<Integer> seqNumbers) throws IOException {
    final float margin = 28f;
    final float gutter = 16f;
    final float footerHeight = 22f;

    try (PDDocument doc = new PDDocument()) {
      MarkdownPdfRenderer renderer = newRenderer(doc);
      PDRectangle landscape =
          new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());

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

        String english = (String) row.get("text_en");
        String raw = (String) row.get("text_raw");
        String rightText =
            english != null && !english.isBlank() ? english : (raw == null ? "" : raw);

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

      if (doc.getNumberOfPages() == 0) {
        throw new IOException("No valid pages found for the given selection");
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
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

  private byte[] buildEnglishPdf(Long recordId, List<Integer> seqNumbers) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      MarkdownPdfRenderer renderer = newRenderer(doc);
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
          String en = (String) rows.get(0).get("text_en");
          if (en != null && !en.isBlank()) {
            text = en;
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

      if (doc.getNumberOfPages() == 0) {
        throw new IOException("No valid pages found for the given selection");
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }

  /**
   * The scans, with an invisible text layer over each.
   *
   * <p>The text is positioned from the OCR engine's own block coordinates, so selecting or
   * searching in the exported scan lands on the right words. Without it the export is a bag of
   * pictures: visually complete and completely unsearchable.
   */
  private byte[] buildOriginalPdf(Long recordId, List<Integer> seqNumbers) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      renderOriginal(doc, recordId, seqNumbers);
      if (doc.getNumberOfPages() == 0) {
        throw new IOException("No valid pages found for the given selection");
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }

  /**
   * Builds the stored searchable PDF for a whole record, straight to a file.
   *
   * <p>Records here run to 942 pages of full-resolution scans, so the document is backed by a
   * temporary file rather than the heap and saved without ever being held as one array. This is the
   * path the searchable-PDF worker uses; the byte-array variants serve interactive exports, which
   * are page selections.
   *
   * @return number of PDF pages written
   */
  public int buildRecordPdfToFile(Long recordId, Path target) throws IOException {
    List<Integer> seqNumbers =
        jdbcTemplate.queryForList(
            "SELECT seq FROM page WHERE record_id = ? ORDER BY seq", Integer.class, recordId);
    if (seqNumbers.isEmpty()) {
      throw new IOException("Record " + recordId + " has no pages");
    }
    try (PDDocument doc =
        new PDDocument(org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache())) {
      renderOriginal(doc, recordId, seqNumbers);
      if (doc.getNumberOfPages() == 0) {
        throw new IOException("Record " + recordId + " produced no pages");
      }
      doc.save(target.toFile());
      return doc.getNumberOfPages();
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
