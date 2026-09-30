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
