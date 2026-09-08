-- One current transcription per page, with a permanent record of every OCR run.
--
-- Background. The pipeline has only ever enqueued ONE OCR engine per page, chosen by
-- archiver.ocr.default-engine. It was never designed to run engines concurrently. But the
-- write path only ever INSERTed, so switching that env var and re-running left the new
-- transcription sitting NEXT TO the old one instead of replacing it: 65,221 pages carry
-- two engines' output, and 814 (page, engine) pairs are outright duplicates from retries.
--
-- V1 anticipated this and created page_search.best_engine to materialise a winner. That
-- table was never populated or read. In its absence both read paths (ViewerController,
-- ApiController) improvised `max(confidence)` — which is wrong, because confidence is not
-- comparable across engines. Paddle emits a per-character CTC score (avg 0.878); the vision
-- LLMs emit nothing at all. NULL sorts lowest, so paddle won every contested page and the
-- archive has been serving its WORST transcription for 65,221 pages:
--
--   paddle  0.891  ...Eingegangon an 17.1X.1943 ... Ministeramts, MS Herrn
--   qwen3vl (null) ...Eingegangen am 17.IX.1943 ... Ministeramts, Herrn Min
--
-- Left alone this would have silently swallowed the mistral re-OCR too: 66,796 new markdown
-- rows, all NULL confidence, all outranked by the paddle rows they were meant to replace.
--
-- What this migration does. Collapses page_text to one row per page (the best available
-- transcription), enforces that with a UNIQUE constraint so it cannot drift again, and
-- preserves which engine produced what in a slim history table.
--
-- History is kept because the job table cannot serve as one: only 4,969 ocr_page_paddle
-- jobs survive against 66,796 paddle page_text rows — the reset path deletes jobs, so 93%
-- of paddle's run history is already gone. Once the superseded page_text rows are deleted
-- there would be no record anywhere that paddle ever ran.

-- ---------------------------------------------------------------------------
-- 1. Normalise engine names.
--
-- The column mixes two namespaces: job kinds ('ocr_page_paddle', 'ocr_page_qwen3vl') and
-- engine names ('qwen3vl', 'claude', 'mistral-ocr', 'pdfbox'). qwen3vl appears under BOTH
-- labels, which is why it looks like two engines in a GROUP BY. Engine names win — the job
-- kind is a scheduling concern and does not belong in a provenance column.
-- ---------------------------------------------------------------------------

UPDATE page_text SET engine = 'paddle'   WHERE engine = 'ocr_page_paddle';
UPDATE page_text SET engine = 'qwen3vl'  WHERE engine = 'ocr_page_qwen3vl';
UPDATE page_text SET engine = 'claude'   WHERE engine = 'ocr_page_claude';
UPDATE page_text SET engine = 'mistral-ocr' WHERE engine = 'ocr_page_mistral';

-- ---------------------------------------------------------------------------
-- 2. Provenance table.
--
-- Deliberately carries no text: it answers "which engine transcribed this page, when, and
-- how much did it produce", not "what did it say". 132k rows of fixed-width columns rather
-- than ~0.5 GB of superseded transcriptions.
-- ---------------------------------------------------------------------------

CREATE TABLE page_ocr_history (
    id            BIGSERIAL PRIMARY KEY,
    page_id       BIGINT      NOT NULL REFERENCES page(id) ON DELETE CASCADE,
    engine        TEXT        NOT NULL,
    confidence    REAL,
    content_type  TEXT        NOT NULL,
    chars         INTEGER     NOT NULL,
    ocr_at        TIMESTAMPTZ NOT NULL,
    -- NULL means this row describes the transcription currently in page_text.
    superseded_at TIMESTAMPTZ
);

CREATE INDEX idx_page_ocr_history_page   ON page_ocr_history(page_id);
CREATE INDEX idx_page_ocr_history_engine ON page_ocr_history(engine);

COMMENT ON TABLE page_ocr_history IS
    'One row per OCR run, including runs whose text has since been replaced. Carries no '
    'text — page_text holds the current transcription. superseded_at IS NULL identifies '
    'the run that produced it.';

-- ---------------------------------------------------------------------------
-- 3. Rank every existing row, best first.
--
-- pdfbox ranks above every OCR engine because it is not OCR: it is the embedded text layer
-- of a born-digital PDF, i.e. the original characters rather than a guess at them.
-- Remaining order follows the fidelity testing done on this archive's own pages.
-- ---------------------------------------------------------------------------

CREATE TEMP TABLE pt_ranked AS
SELECT id,
       page_id,
       row_number() OVER (
           PARTITION BY page_id
           ORDER BY CASE engine
                        WHEN 'pdfbox'      THEN 10
                        WHEN 'mistral-ocr' THEN 20
                        WHEN 'claude'      THEN 30
                        WHEN 'qwen3vl'     THEN 40
                        WHEN 'paddle'      THEN 90
                        ELSE 1000
                    END,
                    created_at DESC,
                    id DESC
       ) AS rn
FROM page_text;

-- Record every run — survivors and superseded alike — before anything is deleted.
INSERT INTO page_ocr_history (page_id, engine, confidence, content_type, chars, ocr_at, superseded_at)
SELECT pt.page_id,
       pt.engine,
       pt.confidence,
       pt.content_type,
       length(pt.text_raw),
       pt.created_at,
       CASE WHEN r.rn = 1 THEN NULL ELSE now() END
FROM page_text pt
JOIN pt_ranked r ON r.id = pt.id;

DELETE FROM page_text pt USING pt_ranked r WHERE pt.id = r.id AND r.rn > 1;

DROP TABLE pt_ranked;

-- ---------------------------------------------------------------------------
-- 4. Make it impossible to drift again.
-- ---------------------------------------------------------------------------

ALTER TABLE page_text ADD CONSTRAINT page_text_page_id_key UNIQUE (page_id);

-- ---------------------------------------------------------------------------
-- 5. Record history automatically.
--
-- A trigger rather than four worker call sites: the requirement is that no transcription
-- may be replaced without leaving a trace, and that is an invariant of the table, not a
-- convention every future engine's author has to remember.
-- ---------------------------------------------------------------------------

CREATE FUNCTION page_text_record_history() RETURNS trigger AS $$
BEGIN
    UPDATE page_ocr_history
       SET superseded_at = now()
     WHERE page_id = NEW.page_id
       AND superseded_at IS NULL;

    INSERT INTO page_ocr_history
           (page_id, engine, confidence, content_type, chars, ocr_at, superseded_at)
    VALUES (NEW.page_id, NEW.engine, NEW.confidence, NEW.content_type,
            length(NEW.text_raw), NEW.created_at, NULL);

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER page_text_history
    AFTER INSERT ON page_text
    FOR EACH ROW EXECUTE FUNCTION page_text_record_history();

-- Deletion must close the history entry too, or the invariant breaks: JobService.resetForOcr
-- and the admin reset both DELETE page_text and wait for a worker to re-OCR, which would
-- otherwise leave a row flagged current (superseded_at IS NULL) with no text behind it for
-- the whole duration of the run. With this, superseded_at IS NULL means exactly "page_text
-- holds this transcription right now".
CREATE FUNCTION page_text_close_history() RETURNS trigger AS $$
BEGIN
    UPDATE page_ocr_history
       SET superseded_at = now()
     WHERE page_id = OLD.page_id
       AND superseded_at IS NULL;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER page_text_history_close
    AFTER DELETE ON page_text
    FOR EACH ROW EXECUTE FUNCTION page_text_close_history();

-- ---------------------------------------------------------------------------
-- 6. Index the foreign keys that lacked one.
--
-- text_chunk.page_id is the one that bites: deleting a page scanned all 87,338 chunks.
-- ---------------------------------------------------------------------------

CREATE INDEX idx_text_chunk_page      ON text_chunk(page_id);
CREATE INDEX idx_record_pdf_att       ON record(pdf_attachment_id);
CREATE INDEX idx_app_user_email_user  ON app_user_email(user_id);
