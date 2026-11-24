-- A hand correction may now be made to a page's German or Czech translation, as it could to the
-- English. These are the translations a PDF export makes on demand.
ALTER TABLE manual_correction DROP CONSTRAINT manual_correction_field_check;
ALTER TABLE manual_correction ADD CONSTRAINT manual_correction_field_check
    CHECK (field IN ('text_raw', 'text_en', 'text_de', 'text_cs', 'title', 'description',
                     'title_en', 'description_en'));
