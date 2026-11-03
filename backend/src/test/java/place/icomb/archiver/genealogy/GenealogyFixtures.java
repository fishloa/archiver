package place.icomb.archiver.genealogy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import place.icomb.archiver.service.StorageService;

/** Records, pages and scans for genealogy tests, written straight to the test database. */
public final class GenealogyFixtures {

  private GenealogyFixtures() {}

  public static void signInAsAdmin() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "genealogy-admin@example.com",
                "n/a",
                List.of(
                    new SimpleGrantedAuthority("ROLE_USER"),
                    new SimpleGrantedAuthority("ROLE_ADMIN"))));
  }

  public static void signInAsUser() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "reader@example.com", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
  }

  public static long record(JdbcTemplate jdbc) {
    Long archive =
        jdbc.queryForObject(
            "INSERT INTO archive (name) VALUES (?) RETURNING id",
            Long.class,
            "genealogy test " + System.nanoTime());
    return jdbc.queryForObject(
        """
        INSERT INTO record (archive_id, source_system, source_record_id, title, status, lang,
                            metadata_lang)
        VALUES (?, 'test', ?, 'a letter', 'complete', 'de', 'de') RETURNING id
        """,
        Long.class,
        archive,
        "gen-" + System.nanoTime());
  }

  /** A page whose stored scan is {@code bytes}, labelled {@code mime}. */
  public static long page(
      JdbcTemplate jdbc,
      StorageService storage,
      long recordId,
      int seq,
      byte[] bytes,
      String mime) {
    String path = storage.storePageImage(bytes);
    Long attachment =
        jdbc.queryForObject(
            "INSERT INTO attachment (record_id, role, path, mime, bytes) VALUES (?, 'page_image', ?,"
                + " ?, ?) RETURNING id",
            Long.class,
            recordId,
            path,
            mime,
            (long) bytes.length);
    return jdbc.queryForObject(
        "INSERT INTO page (record_id, seq, attachment_id) VALUES (?, ?, ?) RETURNING id",
        Long.class,
        recordId,
        seq,
        attachment);
  }

  /** Every pixel different: (x, y) is coloured from its own coordinates. */
  public static BufferedImage pattern(int width, int height) {
    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        img.setRGB(x, y, ((x * 7) & 0xff) << 16 | ((y * 13) & 0xff) << 8 | ((x + y) & 0xff));
      }
    }
    return img;
  }

  public static byte[] encode(BufferedImage img, String format) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, format, out);
    return out.toByteArray();
  }

  public static byte[] png(int width, int height) throws Exception {
    return encode(pattern(width, height), "png");
  }

  public static byte[] jpeg(int width, int height) throws Exception {
    return encode(pattern(width, height), "jpg");
  }
}
