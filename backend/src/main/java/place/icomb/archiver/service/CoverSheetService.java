package place.icomb.archiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The cover sheet that opens a record's PDF extract.
 *
 * <p>These extracts are evidence in a citizenship application, so the sheet leads with the citation
 * a reader would put in a footnote, and states plainly that the English is machine translation and
 * the original-language transcription is the authoritative text. A translated page detached from
 * that statement invites a reader to treat a machine's reading of a 1942 typescript as the document
 * itself.
 *
 * <p>Written to survive a sparse record. Of the 3,127 records with pages, description, dates and
 * reference code are present on virtually all, container and finding aid on ~91%, and index terms
 * on 21. Every block disappears cleanly when its data is absent.
 */
@Service
public class CoverSheetService {

  private static final Logger log = LoggerFactory.getLogger(CoverSheetService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final DateTimeFormatter MONTH_YEAR =
      DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH).withZone(ZoneId.of("UTC"));

  /** An ISO date on its own, as several archives' catalogue fields carry it. */
  private static final java.util.regex.Pattern ISO_DATE =
      java.util.regex.Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");

  /**
   * Source-metadata keys already shown elsewhere on the sheet, or pure plumbing.
   *
   * <p>Every scraper stores its own catalogue JSON and most of it repeats the mapped columns: on a
   * typical vademecum record the raw object holds title, desc, obsah, datace, sig and inv again.
   * Printed unfiltered the block is the same record twice, with a UUID in the middle.
   */
  private static final Set<String> SKIP_KEYS =
      Set.of(
          "xid",
          "scans",
          "title",
          "desc",
          "obsah",
          "datace",
          "sig",
          "inv",
          "karton_number",
          "karton_type",
          "karton",
          "finding_aid_number",
          "referencecode",
          "sourceurl",
          "description",
          "raw_arolsen",
          "archive_id",
          "daterangetext",
          "signatura",
          "nad",
          "zpusob",
          "vedouci",
          "vyrizuje",
          "language",
          "provider",
          "requestdate");

  /** Display names for the catalogue keys worth showing. Anything else is prettified. */
  private static final Map<String, String> KEY_LABELS =
      Map.ofEntries(
          Map.entry("fond_name", "Fond"),
          Map.entry("fond", "Fond"),
          Map.entry("fondcislo", "Fond no."),
          Map.entry("nad_number", "Fond no."),
          Map.entry("folia", "Folios"),
          Map.entry("cj", "File ref."),
          Map.entry("dorucenodne", "Received"),
          Map.entry("extent", "Extent"),
          Map.entry("level", "Level"),
          Map.entry("provenance", "Provenance"),
          Map.entry("library", "Library"),
          Map.entry("source", "Source"),
          Map.entry("signatures", "Signatures"),
          Map.entry("bundesarchiv_ref", "Bundesarchiv ref."),
          Map.entry("gauakt_nr", "Gauakt no."),
          Map.entry("gz", "Geschäftszeichen"),
          Map.entry("obtained_via", "Obtained via"),
          Map.entry("format", "Format"),
          Map.entry("subject", "Subject"),
          Map.entry("isbn", "ISBN"),
          Map.entry("ticket", "Ticket"));

  private final JdbcTemplate jdbc;
  private final TranslationService translationService;

  public CoverSheetService(JdbcTemplate jdbc, TranslationService translationService) {
    this.jdbc = jdbc;
    this.translationService = translationService;
  }

  /**
   * The crest, as a vector form ready to draw into this document.
   *
   * <p>Imported per document because a form XObject belongs to the document that owns it. Returns
   * null if the resource is missing: a cover sheet without a crest is still a cover sheet.
   */
  static PDFormXObject loadCrest(PDDocument target) {
    try (var in = CoverSheetService.class.getResourceAsStream("/images/czernin-crest.pdf")) {
      if (in == null) return null;
      try (PDDocument crest = Loader.loadPDF(in.readAllBytes())) {
        return new LayerUtility(target).importPageAsForm(crest, 0);
      }
    } catch (Exception e) {
      log.warn("Could not load the crest for the cover sheet", e);
      return null;
    }
  }

  /** Everything the sheet prints, gathered once. */
  public record Cover(
      String archiveName,
      String country,
      String title,
      String titleEn,
      String description,
      String dates,
      String language,
      int pageCount,
      String citation,
      List<String[]> catalogue,
      List<String[]> sourceRecord,
      List<String> indexTerms,
      String ocrEngines,
      String translationModel,
      int transcribed,
      int translated,
      Instant ingestedAt,
      String selection) {}

  // -------------------------------------------------------------------------
  // Gathering
  // -------------------------------------------------------------------------

  /**
   * @param selectedSeqs the pages this extract contains, so a partial extract says so rather than
   *     claiming to be the whole file
   */
  public Cover gather(Long recordId, List<Integer> selectedSeqs) {
    Map<String, Object> r =
        jdbc.queryForMap(
            """
            SELECT r.*, a.name AS archive_name, a.country
            FROM record r JOIN archive a ON a.id = r.archive_id
            WHERE r.id = ?
            """,
            recordId);

    JsonNode raw = readJson(str(r.get("raw_source_metadata")));

    int pageCount = num(r.get("page_count"));
    if (pageCount == 0) {
      Integer counted =
          jdbc.queryForObject(
              "SELECT count(*) FROM page WHERE record_id = ?", Integer.class, recordId);
      pageCount = counted == null ? 0 : counted;
    }

    // Catalogue: the fields the archive itself indexes by.
    List<String[]> catalogue = new ArrayList<>();
    addIf(catalogue, "Reference code", str(r.get("reference_code")));
    addIf(catalogue, "Dates", str(r.get("date_range_text")));
    String container =
        joinNonBlank(" ", str(r.get("container_type")), str(r.get("container_number")));
    addIf(catalogue, "Container", container);
    addIf(catalogue, "Finding aid", str(r.get("finding_aid_number")));
    addIf(catalogue, "Inventory no.", str(r.get("inventory_number")));
    addIf(catalogue, "Call number", str(r.get("call_number")));
    addIf(catalogue, "Language", languageName(str(r.get("lang"))));

    // Source record: whatever the archive's own catalogue carried that is not already above.
    List<String[]> sourceRecord = new ArrayList<>();
    if (raw != null && raw.isObject()) {
      for (Map.Entry<String, JsonNode> e : raw.properties()) {
        String key = e.getKey();
        String lower = key.toLowerCase(Locale.ROOT);
        if (SKIP_KEYS.contains(lower)) continue;
        String value = e.getValue() == null || e.getValue().isNull() ? "" : e.getValue().asText("");
        if (value.isBlank() || value.length() > 200) continue;
        sourceRecord.add(
            new String[] {KEY_LABELS.getOrDefault(lower, prettify(key)), humaniseDate(value)});
      }
    }

    // Processing provenance.
    String engines =
        jdbc.query(
            """
            SELECT string_agg(DISTINCT pt.engine, ', ' ORDER BY pt.engine)
            FROM page p JOIN page_text pt ON pt.page_id = p.id WHERE p.record_id = ?
            """,
            rs -> rs.next() ? rs.getString(1) : null,
            recordId);

    // The models actually behind this record's English, named honestly: a record can be part
    // upgraded, and claiming the better model for pages that never got it would misdescribe the
    // evidence.
    String model = describeModels(translationService.modelBreakdown(recordId));

    int transcribed =
        orZero(
            jdbc.queryForObject(
                "SELECT count(*) FROM page p JOIN page_text pt ON pt.page_id = p.id WHERE p.record_id = ?",
                Integer.class,
                recordId));
    int translated =
        orZero(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM page p JOIN page_text pt ON pt.page_id = p.id
                WHERE p.record_id = ? AND pt.text_en IS NOT NULL AND pt.text_en <> ''
                """,
                Integer.class,
                recordId));

    return new Cover(
        str(r.get("archive_name")),
        str(r.get("country")),
        str(r.get("title")),
        str(r.get("title_en")),
        str(r.get("description")),
        str(r.get("date_range_text")),
        languageName(str(r.get("lang"))),
        pageCount,
        buildCitation(r, raw),
        catalogue,
        sourceRecord,
        indexTerms(str(r.get("index_terms"))),
        engines,
        model,
        transcribed,
        translated,
        r.get("created_at") instanceof java.sql.Timestamp ts ? ts.toInstant() : null,
        describeSelection(selectedSeqs, pageCount));
  }

  /** "mistral-medium-latest", or "mistral-medium-latest (12 pages), mistral-small-latest (26)". */
  static String describeModels(List<String[]> breakdown) {
    if (breakdown.isEmpty()) return "";
    if (breakdown.size() == 1) return breakdown.get(0)[0];
    List<String> parts = new ArrayList<>();
    for (String[] row : breakdown) {
      parts.add(row[0] + " (" + row[1] + " pages)");
    }
    return String.join(", ", parts);
  }

  /**
   * The footnote line, assembled from whichever parts exist.
   *
   * <p>archive.citation_template is empty on every archive here, so there is nothing to fill in;
   * the citation is built from the catalogue fields instead and simply omits what is missing.
   */
  String buildCitation(Map<String, Object> r, JsonNode raw) {
    List<String> parts = new ArrayList<>();
    addIfPresent(parts, str(r.get("archive_name")));

    String fond = firstOf(raw, "fond_name", "fond");
    String fondNo = firstOf(raw, "fondCislo", "nad_number", "nad");
    if (!fond.isBlank()) {
      parts.add("fond " + fond + (fondNo.isBlank() ? "" : " (" + fondNo + ")"));
    } else if (!fondNo.isBlank()) {
      parts.add("fond " + fondNo);
    }

    addIfPresent(parts, prefixed("sign. ", str(r.get("reference_code"))));
    addIfPresent(parts, prefixed("inv. ", str(r.get("inventory_number"))));
    String container =
        joinNonBlank(" ", str(r.get("container_type")), str(r.get("container_number")));
    addIfPresent(parts, container.isBlank() ? "" : container);
    addIfPresent(parts, prefixed("fol. ", firstOf(raw, "folia")));

    return parts.isEmpty() ? "" : String.join(", ", parts) + ".";
  }

  /** "This extract contains pages 5-7 of 38", or null when the whole record is included. */
  String describeSelection(List<Integer> seqs, int pageCount) {
    if (seqs == null || seqs.isEmpty() || seqs.size() >= pageCount) {
      return null;
    }
    return "This extract contains "
        + (seqs.size() == 1 ? "page " : "pages ")
        + compressRanges(seqs)
        + " of "
        + pageCount
        + ".";
  }

  static String compressRanges(List<Integer> seqs) {
    List<Integer> sorted = new ArrayList<>(new java.util.TreeSet<>(seqs));
    StringBuilder sb = new StringBuilder();
    int i = 0;
    while (i < sorted.size()) {
      int start = sorted.get(i);
      int end = start;
      while (i + 1 < sorted.size() && sorted.get(i + 1) == end + 1) {
        end = sorted.get(++i);
      }
      if (sb.length() > 0) sb.append(", ");
      sb.append(start);
      if (end > start) sb.append(end == start + 1 ? ", " + end : "–" + end);
      i++;
    }
    return sb.toString();
  }

  // -------------------------------------------------------------------------
  // Rendering
  // -------------------------------------------------------------------------

  /** Draws the cover, adding as many pages as it needs (almost always one). */
  public void render(
      PDDocument doc,
      MarkdownPdfRenderer renderer,
      PDRectangle size,
      Cover c,
      Long recordId,
      String recordUrl,
      float margin)
      throws IOException {

    float width = size.getWidth() - 2 * margin;
    Layout l = new Layout(doc, renderer, size, margin, width, recordUrl);
    l.newPage();

    l.masthead("Czernin Archive  ·  Record " + recordId);

    // Then whose archive the document itself came from.
    l.text(upper(joinNonBlank(" · ", c.archiveName(), c.country())), 9f, true, 3f);
    l.rule(1.0f, 10f);

    l.text(c.title(), 15f, true, 4f);
    if (!c.titleEn().isBlank() && !c.titleEn().equalsIgnoreCase(c.title())) {
      l.text(c.titleEn(), 10.5f, false, 4f);
    }
    l.text(
        joinNonBlank("   ·   ", c.dates(), c.language(), c.pageCount() + " pages"), 9f, false, 12f);

    if (!c.citation().isBlank()) {
      l.section("CITATION");
      l.text(c.citation(), 9.5f, false, 12f);
    }

    if (!c.description().isBlank()) {
      l.section("DESCRIPTION");
      l.text(c.description(), 9f, false, 12f);
    }

    if (!c.catalogue().isEmpty() || !c.sourceRecord().isEmpty()) {
      l.twoColumns("CATALOGUE", c.catalogue(), "SOURCE RECORD", c.sourceRecord());
    }

    if (!c.indexTerms().isEmpty()) {
      l.section("INDEX TERMS");
      l.text(String.join("  ·  ", c.indexTerms()), 9f, false, 12f);
    }

    l.section("ABOUT THIS COPY");
    for (String line : aboutThisCopy(c)) {
      l.text(line, 8.5f, false, 3f);
    }

    l.finish();
  }

  /**
   * The statement that keeps a machine translation from being read as the document.
   *
   * <p>Named models and counts rather than a generic disclaimer: a reader assessing the evidence
   * can tell that page 12 was never transcribed, or that this record was translated by the better
   * model, without opening the archive.
   */
  List<String> aboutThisCopy(Cover c) {
    List<String> out = new ArrayList<>();
    if (c.selection() != null) {
      out.add(c.selection());
    }
    String transcription =
        c.transcribed()
            + " of "
            + c.pageCount()
            + " pages transcribed"
            + (c.ocrEngines() == null || c.ocrEngines().isBlank() ? "" : " by " + c.ocrEngines())
            + ".";
    out.add(transcription);
    if (c.translated() > 0) {
      out.add(
          "English is machine translation"
              + (c.translationModel() == null || c.translationModel().isBlank()
                  ? ""
                  : " (" + c.translationModel() + ")")
              + ", not checked by a translator. The "
              + (c.language().isBlank() ? "original-language" : c.language())
              + " transcription is the authoritative text.");
    }
    if (c.ingestedAt() != null) {
      out.add(
          "Ingested "
              + formatDate(c.ingestedAt())
              + "  ·  this extract generated "
              + formatDate(Instant.now())
              + ".");
    }
    return out;
  }

  /** Page-building cursor: draws top-down and starts a new sheet when it runs out of room. */
  private final class Layout {
    private final PDDocument doc;
    private final MarkdownPdfRenderer renderer;
    private final PDRectangle size;
    private final float margin;
    private final float width;
    private final String recordUrl;

    private PDPage page;
    private PDPageContentStream cs;
    private float y;

    Layout(
        PDDocument doc,
        MarkdownPdfRenderer renderer,
        PDRectangle size,
        float margin,
        float width,
        String recordUrl) {
      this.doc = doc;
      this.renderer = renderer;
      this.size = size;
      this.margin = margin;
      this.width = width;
      this.recordUrl = recordUrl;
    }

    void newPage() throws IOException {
      close();
      page = new PDPage(size);
      doc.addPage(page);
      cs = new PDPageContentStream(doc, page);
      y = size.getHeight() - margin;
    }

    private float bottom() {
      return margin + renderer.footerBandHeight(size.getWidth());
    }

    void ensure(float needed) throws IOException {
      if (y - needed < bottom()) {
        newPage();
      }
    }

    /**
     * The archive's own masthead: crest, then which record this is.
     *
     * <p>The crest is drawn from a one-page PDF rather than a bitmap, so it stays sharp at any zoom
     * and in print. It was produced from the same SVG the site uses; importing it as a form keeps
     * it vector without pulling an SVG rasteriser into the backend.
     */
    void masthead(String title) throws IOException {
      float crestHeight = 34f;
      float textX = margin;

      PDFormXObject crest = loadCrest(doc);
      if (crest != null) {
        PDRectangle box = crest.getBBox();
        float scale = crestHeight / box.getHeight();
        float crestWidth = box.getWidth() * scale;
        cs.saveGraphicsState();
        cs.transform(new Matrix(scale, 0, 0, scale, margin, y - crestHeight));
        cs.drawForm(crest);
        cs.restoreGraphicsState();
        textX = margin + crestWidth + 10f;
      }

      // Baseline centred against the crest rather than sitting on its foot.
      float titleSize = 16f;
      renderer.drawText(cs, title, textX, y - crestHeight * 0.62f, titleSize, true);
      y -= crestHeight + 12f;
    }

    void text(String s, float fontSize, boolean bold, float gapAfter) throws IOException {
      if (s == null || s.isBlank()) return;
      for (String line : renderer.wrapPlain(s, bold, fontSize, width)) {
        ensure(fontSize * 1.35f);
        y -= fontSize * 1.15f;
        renderer.drawText(cs, line, margin, y, fontSize, bold);
        y -= fontSize * 0.2f;
      }
      y -= gapAfter;
    }

    void rule(float thickness, float gapAfter) throws IOException {
      ensure(gapAfter + 4f);
      y -= 4f;
      renderer.drawRule(cs, margin, y, width, thickness);
      y -= gapAfter;
    }

    void section(String label) throws IOException {
      ensure(30f);
      y -= 6f;
      renderer.drawRule(cs, margin, y, width, 0.4f);
      y -= 13f;
      renderer.drawText(cs, label, margin, y, 7.5f, true);
      y -= 12f;
    }

    /** Two label/value columns side by side, each a block that can outlive the other. */
    void twoColumns(String leftLabel, List<String[]> left, String rightLabel, List<String[]> right)
        throws IOException {
      float colWidth = (width - 24f) / 2f;
      float rowHeight = 12.5f;
      int rows = Math.max(left.size(), right.size());
      ensure(rows * rowHeight + 34f);

      y -= 6f;
      renderer.drawRule(cs, margin, y, width, 0.4f);
      y -= 13f;
      renderer.drawText(cs, leftLabel, margin, y, 7.5f, true);
      if (!right.isEmpty()) {
        renderer.drawText(cs, rightLabel, margin + colWidth + 24f, y, 7.5f, true);
      }
      y -= 13f;

      float labelWidth = 74f;
      float startY = y;
      float leftY = drawColumn(left, margin, colWidth, labelWidth, startY, rowHeight);
      float rightY =
          drawColumn(right, margin + colWidth + 24f, colWidth, labelWidth, startY, rowHeight);
      y = Math.min(leftY, rightY) - 6f;
    }

    private float drawColumn(
        List<String[]> rows,
        float x,
        float colWidth,
        float labelWidth,
        float startY,
        float rowHeight)
        throws IOException {
      float cy = startY;
      for (String[] row : rows) {
        List<String> valueLines = renderer.wrapPlain(row[1], false, 8.5f, colWidth - labelWidth);
        renderer.drawText(cs, row[0], x, cy, 8f, false);
        for (String line : valueLines) {
          renderer.drawText(cs, line, x + labelWidth, cy, 8.5f, false);
          cy -= rowHeight;
        }
        if (valueLines.isEmpty()) {
          cy -= rowHeight;
        }
      }
      return cy;
    }

    void finish() throws IOException {
      close();
    }

    private void close() throws IOException {
      if (cs != null) {
        renderer.drawFooter(
            page, cs, size.getWidth(), margin, margin, recordUrl, "Archive Cover Sheet");
        cs.close();
        cs = null;
      }
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /** "7th September 2026" — the archive's own date style, used for every date on the sheet. */
  static String formatDate(java.time.temporal.TemporalAccessor when) {
    java.time.LocalDate d = java.time.LocalDate.from(java.time.ZonedDateTime.from(when));
    return ordinal(d.getDayOfMonth()) + " " + MONTH_YEAR.format(d.atStartOfDay(ZoneId.of("UTC")));
  }

  static String formatDate(Instant when) {
    return formatDate(when.atZone(ZoneId.of("UTC")));
  }

  /** Rewrites a bare ISO date into the sheet's style, and leaves anything else alone. */
  static String humaniseDate(String value) {
    if (value == null) return "";
    var m = ISO_DATE.matcher(value.strip());
    if (!m.matches()) return value;
    try {
      return formatDate(
          java.time.LocalDate.of(
                  Integer.parseInt(m.group(1)),
                  Integer.parseInt(m.group(2)),
                  Integer.parseInt(m.group(3)))
              .atStartOfDay(ZoneId.of("UTC")));
    } catch (Exception e) {
      return value;
    }
  }

  static String ordinal(int day) {
    // 11th, 12th and 13th are the exceptions that a bare last-digit rule gets wrong.
    if (day >= 11 && day <= 13) return day + "th";
    return day
        + switch (day % 10) {
          case 1 -> "st";
          case 2 -> "nd";
          case 3 -> "rd";
          default -> "th";
        };
  }

  private static void addIf(List<String[]> out, String label, String value) {
    if (value != null && !value.isBlank()) out.add(new String[] {label, value});
  }

  private static void addIfPresent(List<String> out, String value) {
    if (value != null && !value.isBlank()) out.add(value);
  }

  private static String prefixed(String prefix, String value) {
    return value == null || value.isBlank() ? "" : prefix + value;
  }

  private static String firstOf(JsonNode raw, String... keys) {
    if (raw == null) return "";
    for (String k : keys) {
      JsonNode n = raw.get(k);
      if (n != null && !n.isNull() && !n.asText("").isBlank()) return n.asText();
    }
    return "";
  }

  private static String joinNonBlank(String sep, String... parts) {
    List<String> kept = new ArrayList<>();
    for (String p : parts) {
      if (p != null && !p.isBlank()) kept.add(p.strip());
    }
    return String.join(sep, kept);
  }

  private static String prettify(String key) {
    String spaced = key.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ').strip();
    return spaced.isEmpty() ? key : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
  }

  private static List<String> indexTerms(String json) {
    List<String> out = new ArrayList<>();
    if (json == null || json.isBlank()) return out;
    try {
      for (JsonNode n : MAPPER.readTree(json)) {
        String v = n.asText("");
        if (!v.isBlank()) out.add(v);
      }
    } catch (Exception e) {
      log.debug("Unreadable index_terms", e);
    }
    return out;
  }

  private static JsonNode readJson(String json) {
    if (json == null || json.isBlank()) return null;
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      return null;
    }
  }

  /** ISO 639-1 to an English language name, via the JDK's own locale data. */
  static String languageName(String code) {
    if (code == null || code.isBlank()) return "";
    String name = Locale.of(code).getDisplayLanguage(Locale.ENGLISH);
    return name.isBlank() || name.equalsIgnoreCase(code) ? code : name;
  }

  private static String upper(String s) {
    return s == null ? "" : s.toUpperCase(Locale.ENGLISH);
  }

  private static String str(Object o) {
    return o == null ? "" : o.toString();
  }

  private static int num(Object o) {
    return o instanceof Number n ? n.intValue() : 0;
  }

  private static int orZero(Integer i) {
    return i == null ? 0 : i;
  }
}
