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

  private final PageRepository pageRepository;
  private final AttachmentRepository attachmentRepository;
  private final StorageService storageService;
  private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  public PdfExportService(
      PageRepository pageRepository,
      AttachmentRepository attachmentRepository,
      StorageService storageService,
      org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
    this.pageRepository = pageRepository;
    this.attachmentRepository = attachmentRepository;
    this.storageService = storageService;
    this.jdbcTemplate = jdbcTemplate;
  }

  /** What an export contains. */
  public enum Variant {
    /** The scanned images, exactly as held. */
    ORIGINAL,
    /** The English translation, rendered from the markdown the OCR produced. */
    ENGLISH
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
    if (variant == Variant.ENGLISH) {
      return buildEnglishPdf(recordId, seqNumbers);
    }
    return buildOriginalPdf(recordId, seqNumbers);
  }

  private byte[] buildEnglishPdf(Long recordId, List<Integer> seqNumbers) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      MarkdownPdfRenderer renderer = new MarkdownPdfRenderer(doc);
      for (int seq : seqNumbers) {
        List<java.util.Map<String, Object>> rows =
            jdbcTemplate.queryForList(
                """
                SELECT pt.text_en, pt.text_raw
                FROM page p JOIN page_text pt ON pt.page_id = p.id
                WHERE p.record_id = ? AND p.seq = ?
                """,
                recordId,
                seq);

        String text = "";
        String note = "";
        if (!rows.isEmpty()) {
          String en = (String) rows.get(0).get("text_en");
          if (en != null && !en.isBlank()) {
            text = en;
          } else {
            // Untranslated pages fall back to the original transcription rather than appearing
            // blank, so the export never silently omits a page's content.
            String raw = (String) rows.get(0).get("text_raw");
            text = raw == null ? "" : raw;
            note =
                raw == null || raw.isBlank() ? "  [no text]" : "  [not translated - original text]";
          }
        } else {
          note = "  [no text]";
        }
        renderer.renderPage(doc, text, "Page " + seq + note);
      }

      if (doc.getNumberOfPages() == 0) {
        throw new IOException("No valid pages found for the given selection");
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }

  private byte[] buildOriginalPdf(Long recordId, List<Integer> seqNumbers) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      for (int seq : seqNumbers) {
        Page page = pageRepository.findByRecordIdAndSeq(recordId, seq).orElse(null);
        if (page == null || page.getAttachmentId() == null) {
          continue; // skip missing pages
        }

        Attachment attachment = attachmentRepository.findById(page.getAttachmentId()).orElse(null);
        if (attachment == null) {
          continue;
        }

        Path imagePath = storageService.getPath(attachment);
        PDImageXObject image = PDImageXObject.createFromFileByContent(imagePath.toFile(), doc);

        // Size the PDF page to match the image dimensions (in points, 72 dpi)
        float imgWidth = image.getWidth();
        float imgHeight = image.getHeight();
        PDRectangle pageSize = new PDRectangle(imgWidth, imgHeight);
        PDPage pdfPage = new PDPage(pageSize);
        doc.addPage(pdfPage);

        try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
          cs.drawImage(image, 0, 0, imgWidth, imgHeight);
        }
      }

      if (doc.getNumberOfPages() == 0) {
        throw new IOException("No valid pages found for the given selection");
      }

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      doc.save(out);
      return out.toByteArray();
    }
  }
}
