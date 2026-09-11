package place.icomb.archiver.service;

import java.util.List;

/**
 * Which translation is preferred when a page has more than one.
 *
 * <p>Explicit precedence, not an inferred score. The OCR engines were ranked by confidence, which
 * is not comparable across engines, and the archive consequently served its worst transcription for
 * 65,221 pages. A list is dull but it cannot be wrong by accident.
 *
 * <p>Order is best first, measured over 20 archive pages: mistral-medium lost no dates or reference
 * numbers, mistral-small dropped a document date and two file references on one page in twenty, and
 * the legacy translations were produced from OCR text that has since been replaced.
 */
public final class TranslationModels {

  public static final String UPGRADE_MODEL = "mistral-medium-latest";
  public static final String BULK_MODEL = "mistral-small-latest";
  public static final String LEGACY = "legacy";

  private static final List<String> BEST_FIRST = List.of(UPGRADE_MODEL, BULK_MODEL, LEGACY);

  private TranslationModels() {}

  /** Lower is better. Unknown models rank last but are still usable. */
  public static int rank(String model) {
    int i = BEST_FIRST.indexOf(model);
    return i < 0 ? BEST_FIRST.size() : i;
  }

  /** True when {@code candidate} should replace {@code current} as the page's shown translation. */
  public static boolean outranks(String candidate, String current) {
    if (current == null) return true;
    return rank(candidate) < rank(current);
  }

  /**
   * Preference order, best first, for handing to SQL.
   *
   * <p>Exports rank translations with this rather than reading page_text.text_en, which is only a
   * cache of the preferred text and can fall behind: 54 pages across 3 records had a paid-for
   * mistral-medium upgrade sitting in page_translation while the cached English was still
   * mistral-small. An export must show the best text the archive holds.
   */
  public static String[] bestFirst() {
    return BEST_FIRST.toArray(new String[0]);
  }

  /**
   * The preference order as a PostgreSQL array literal, for binding to {@code ?::text[]}.
   *
   * <p>A literal rather than a Java array: the driver will not bind {@code String[]} to a text[]
   * parameter, and as a trailing argument to JdbcTemplate's varargs it silently expands into three
   * separate parameters instead of one.
   *
   * <p>This is the compiled-in fallback. The live order comes from {@code ai_implementation} via
   * {@link place.icomb.archiver.ai.AiRegistry}, so changing which model is preferred is a row
   * update rather than a release. The two are seeded identically, and a registry that returns
   * nothing falls back here rather than ranking everything equally — which would let a worse
   * translation overwrite a better one.
   */
  public static String ranksLiteral() {
    return "{" + String.join(",", BEST_FIRST) + "}";
  }

  /** Job kind that translates a page at the given quality. */
  public static String jobKindFor(String quality) {
    return "best".equals(quality) ? "translate_page_upgrade" : "translate_page";
  }

  /** The model that quality resolves to, for logging and for the cover sheet. */
  public static String modelFor(String quality) {
    return "best".equals(quality) ? UPGRADE_MODEL : BULK_MODEL;
  }

  /** The model an upgrade would use. */
  public static String upgradeModel() {
    return UPGRADE_MODEL;
  }
}
