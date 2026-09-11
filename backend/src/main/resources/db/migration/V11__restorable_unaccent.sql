-- The database could not be restored from its own dump.
--
-- page_text has a generated column, text_norm, defined as
-- public.immutable_unaccent(lower(text_raw)). That function called unaccent($1) unqualified, and
-- pg_dump restores with search_path set empty, so evaluating the generated column during
-- CREATE TABLE failed with "function unaccent(text) does not exist". page_text was therefore never
-- created, and every COPY into it failed after it — the transcriptions of all 128,831 pages,
-- silently absent from the restore while the rest of the schema came back cleanly.
--
-- It worked in normal operation only because a session's search_path includes public. The fault
-- was invisible until someone tried to restore, which is the worst time to find it.
--
-- Discovered building the test environment. The fix is to qualify the call; behaviour is
-- unchanged, so the stored values stay correct and nothing needs recomputing.

CREATE OR REPLACE FUNCTION public.immutable_unaccent(text)
  RETURNS text
  LANGUAGE sql
  IMMUTABLE PARALLEL SAFE
AS $$
  SELECT public.unaccent($1)
$$;

-- to_tsvector needs no qualification: pg_catalog is always on the search path. Pinning the
-- function's own search_path as well, so it cannot be resolved differently by a caller that has
-- set one.
ALTER FUNCTION public.immutable_unaccent(text) SET search_path = public, pg_catalog;
ALTER FUNCTION public.immutable_to_tsvector(regconfig, text) SET search_path = public, pg_catalog;
