-- Keep the OCR engine's full response alongside the extracted text.
--
-- Mistral's OCR returns far more than the markdown we currently read: per-block
-- bounding boxes with a dpi-bearing `dimensions` object, block type labels
-- (header/text/table), detected tables and hyperlinks, and the model version that
-- produced the transcription. All of it is discarded today.
--
-- The bounding boxes are the immediate prize. pdf-worker currently spreads the
-- invisible text layer evenly down the page — every line starts at x=10 and its y is
-- "line N of M" rather than where the words actually are — so selecting text in a
-- searchable PDF highlights the wrong region, badly so on multi-column pages.
--
-- Stored now rather than later because the positional data arrives free with a
-- re-OCR. Adding this column afterwards would mean paying to OCR 128,484 pages twice.
--
-- Measured cost: ~6.5 KB per page, ~0.83 GB across the archive, against a 2.8 GB
-- database and 108 GB of page images.
--
-- jsonb rather than text so blocks can be queried and indexed without parsing in
-- application code. Nullable: engines that return nothing structured beyond the text
-- itself (Claude, Qwen — both chat completions) leave it null rather than storing noise.

ALTER TABLE page_text
    ADD COLUMN raw_response jsonb;

COMMENT ON COLUMN page_text.raw_response IS
    'Verbatim JSON response from the OCR engine, where the engine returns structure '
    'beyond the text (Mistral OCR: blocks with bounding boxes, dimensions, tables). '
    'Null for engines that return only text.';
