-- Record the media type of each OCR transcription.
--
-- Mistral's OCR model returns markdown (headings, tables, lists); every other
-- engine returns flat text. The two are not distinguishable by inspection: a
-- typescript's centred page number "- 5 -" is byte-identical to a markdown
-- bullet, so consumers cannot sniff the format and must be told it.
--
-- text_raw stays verbatim as the engine produced it — nothing is escaped or
-- normalised on write, so the stored transcription remains exactly what the
-- OCR engine said.

ALTER TABLE page_text
    ADD COLUMN content_type text NOT NULL DEFAULT 'text/plain';

-- Loose check: media types are an open set, so validate the shape rather than
-- enumerate values, which would need a migration per new engine.
ALTER TABLE page_text
    ADD CONSTRAINT page_text_content_type_check
    CHECK (content_type ~ '^[a-z]+/[a-z0-9.+-]+$');

-- Backfill: only the Mistral OCR worker has ever written markdown.
UPDATE page_text SET content_type = 'text/markdown' WHERE engine = 'mistral-ocr';

COMMENT ON COLUMN page_text.content_type IS
    'IANA media type of text_raw, e.g. text/plain or text/markdown. Set by the '
    'producing OCR worker; consumers must not infer it from the content.';
