package place.icomb.archiver.dto;

public record OcrResultRequest(String engine, Float confidence, String textRaw, String hocr) {}
