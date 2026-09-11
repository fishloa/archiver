package place.icomb.archiver.ai;

/** The three things this archive asks a model to do. */
public enum AiCapability {
  /** Transcribe a scanned page. */
  OCR,
  /** Translate page text or catalogue metadata into English. */
  TRANSLATION,
  /** Turn text into a vector for semantic search. */
  EMBEDDING
}
