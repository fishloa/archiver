-- Fix missing ON DELETE CASCADE on text_chunk.record_id and text_chunk.page_id.
-- Without this, deleting a record that has been embedded fails with FK violation.

ALTER TABLE text_chunk
  DROP CONSTRAINT text_chunk_record_id_fkey,
  ADD CONSTRAINT text_chunk_record_id_fkey
    FOREIGN KEY (record_id) REFERENCES record(id) ON DELETE CASCADE;

ALTER TABLE text_chunk
  DROP CONSTRAINT text_chunk_page_id_fkey,
  ADD CONSTRAINT text_chunk_page_id_fkey
    FOREIGN KEY (page_id) REFERENCES page(id) ON DELETE CASCADE;
