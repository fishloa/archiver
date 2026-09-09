package place.icomb.archiver.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which job kinds each pipeline stage runs.
 *
 * <p>One source of truth, used both to build the dashboard and to hold a stage. Gates were first
 * built per job kind, which is the right mechanism but the wrong control: the interface is
 * organised by stage, so holding "Translation (metadata)" left page translation running and the
 * hold looked ignored. An operator holds a stage; the kinds are an implementation detail.
 */
public final class PipelineStages {

  public static final String OCR = "OCR";
  public static final String PDF_BUILD = "PDF Build";
  public static final String EMBEDDING = "Embedding";
  public static final String TRANSLATION = "Translation";
  public static final String MATCHING = "Person matching";

  private static final Map<String, List<String>> KINDS = new LinkedHashMap<>();

  static {
    KINDS.put(OCR, List.of("ocr_page_mistral", "ocr_page_claude", "ocr_page_qwen3vl"));
    KINDS.put(PDF_BUILD, List.of("build_searchable_pdf"));
    KINDS.put(EMBEDDING, List.of("embed_record"));
    KINDS.put(TRANSLATION, List.of("translate_page", "translate_page_upgrade", "translate_record"));
    KINDS.put(MATCHING, List.of("match_persons"));
  }

  private PipelineStages() {}

  /** Stage names in pipeline order. */
  public static List<String> names() {
    return List.copyOf(KINDS.keySet());
  }

  /** The job kinds a stage runs. Empty for an unknown stage. */
  public static List<String> kindsOf(String stage) {
    return KINDS.getOrDefault(stage, List.of());
  }

  public static Map<String, List<String>> all() {
    return Map.copyOf(KINDS);
  }
}
