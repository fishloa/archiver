package place.icomb.archiver.dto;

/**
 * OCR result posted by an external worker. {@code contentType} is the IANA media type of {@code
 * textRaw}; workers that predate the field send null and are treated as {@code text/plain}.
 */
public record OcrResultRequest(
    String engine, Float confidence, String textRaw, String hocr, String contentType) {}
