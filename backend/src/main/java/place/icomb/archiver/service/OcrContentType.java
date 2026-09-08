package place.icomb.archiver.service;

/**
 * Media types an OCR engine may produce for {@code page_text.text_raw}.
 *
 * <p>Consumers must read the stored value rather than inspect the text: a typescript's centred page
 * number {@code "- 5 -"} is byte-identical to a markdown bullet, so the two formats cannot be told
 * apart by looking at them.
 */
public final class OcrContentType {

  /** Flat text with no markup. Produced by Qwen, Claude and the PDFBox text extractor. */
  public static final String PLAIN = "text/plain";

  /** Markdown with headings, tables and lists. Produced by Mistral's OCR model. */
  public static final String MARKDOWN = "text/markdown";

  private OcrContentType() {}
}
