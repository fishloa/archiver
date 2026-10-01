package place.icomb.archiver.service;

/** Formal pipeline states for record processing. Single source of truth for valid statuses. */
public enum PipelineState {
  INGESTING,
  OCR_PENDING,
  OCR_DONE,
  TRANSLATING,
  EMBEDDING,
  MATCHING,
  COMPLETE;

  public String toDb() {
    return name().toLowerCase();
  }

  public static PipelineState fromDb(String s) {
    return valueOf(s.toUpperCase());
  }
}
