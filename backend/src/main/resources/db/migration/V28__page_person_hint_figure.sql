-- A hint may say a figure on the page shows the person; figure_id is the OCR engine's image id.
ALTER TABLE page_person_match ADD COLUMN figure_id text;

-- One person may be named in the text and shown in several figures of the same page.
ALTER TABLE page_person_match DROP CONSTRAINT page_person_match_page_id_person_id_key;
ALTER TABLE page_person_match
    ADD CONSTRAINT page_person_match_page_person_figure_key
    UNIQUE NULLS NOT DISTINCT (page_id, person_id, figure_id);

-- Hints describe one transcription. Every path that stores a page's text inserts a page_text row,
-- so the new text drops the old hints here, and figure ids can never outlive their OCR run.
CREATE FUNCTION page_text_drop_hints() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    DELETE FROM page_person_match WHERE page_id = NEW.page_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER page_text_drop_hints AFTER INSERT ON page_text
    FOR EACH ROW EXECUTE FUNCTION page_text_drop_hints();

-- Hints now run only when an administrator asks. Matching jobs the old pipeline queued, or would
-- retry (the audit re-queues failed jobs with attempts left), must not run as paid hint jobs.
UPDATE job
   SET status = 'completed', error = 'cancelled: hints run only on request', finished_at = now()
 WHERE kind = 'match_persons'
   AND (status IN ('pending', 'claimed') OR (status = 'failed' AND attempts < 3));
