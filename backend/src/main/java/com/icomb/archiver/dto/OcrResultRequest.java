package com.icomb.archiver.dto;

public record OcrResultRequest(String engine, Float confidence, String textRaw, String hocr) {}
