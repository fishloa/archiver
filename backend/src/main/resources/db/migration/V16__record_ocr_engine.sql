-- Which OCR engine a record's pages are read with, chosen by whoever ingests them.
--
-- Until now the engine came from one deployment-wide setting, so a record of
-- handwriting was read by the print engine and had to be found and re-OCR'd page by
-- page afterwards. The scraper or the operator knows what the document is at ingest
-- time; this lets them say so once.
--
-- NULL means "use archiver.ocr.default-engine", which is what every existing record
-- did and continues to do.
ALTER TABLE record ADD COLUMN ocr_engine text;

COMMENT ON COLUMN record.ocr_engine IS
  'Job kind used to OCR this record''s pages, e.g. ocr_page_mistral or ocr_page_transkribus. NULL = the deployment default.';
