-- Transkribus HTR as a second OCR engine, for handwriting.
--
-- Mistral OCR reads typescript well and cannot read German handwriting at all. Worse, it does not
-- fail on it: given a page of Kurrent it emits fluent, plausible German that is substantially
-- wrong, so the page comes back looking like a clean transcription. Record 3505 has the surname
-- "Czernin" transcribed as Germin, Gernin, Perrin and Chernin on eleven pages, and nothing in the
-- output flags any of them. This archive backs a citizenship submission, where a wrong
-- transcription that reads well is more dangerous than no transcription at all.
--
-- Transkribus is registered as a separate engine, never as a replacement, and never the default:
-- its jobs are enqueued deliberately, per page, because the quota is credits per month rather
-- than pages per minute. One credit is one page.

ALTER TABLE job DROP CONSTRAINT job_kind_check;

ALTER TABLE job ADD CONSTRAINT job_kind_check CHECK (
  kind = ANY (ARRAY[
    'ocr_page_paddle',
    'ocr_page_abbyy',
    'ocr_page_qwen3vl',
    'ocr_page_claude',
    'ocr_page_mistral',
    'ocr_page_transkribus',
    'build_searchable_pdf',
    'generate_thumbs',
    'translate_page',
    'translate_page_upgrade',
    'translate_record',
    'embed_record',
    'match_persons'
  ])
);

-- One row per plan tier, because the plan is what actually changes. Measured against the live
-- account rather than assumed:
--
--   * API access is NOT part of the Scholar plan. The modern Metagrapho API answers 401 and the
--     classic API refuses the TrHtr super models with "You are not allowed for TrHtr Recognition!",
--     with the plan attached and credits on the account. The web app can run them; the API cannot.
--   * So a German or English handwritten page is transcribed by a person pressing Run in the web
--     app, and imported here. A Czech page needs no click: Czech has no super model, its models
--     are PyLaia, and PyLaia the API is allowed to start.
--   * Text Titan II does not do Czech at all.
--
-- Switching tier is enabling the other row.
--
-- Both rows sit below rank 1, which is Mistral, so anything asking for "the best OCR engine"
-- still gets Mistral and the credits are not spent by the ordinary pipeline. Each row is inert
-- until TRANSKRIBUS_PASSWORD exists in the deployment, because AiRegistry treats an enabled row
-- with no credential as unconfigured rather than as on.
--
-- Every htrId below was read from Transkribus's own public model catalogue
-- (GET https://transkribus.eu/TrpServer/rest/models/text, no authentication required), not from
-- documentation that may have drifted. Models were chosen for breadth of training data rather
-- than headline CER: the lowest-CER German models are single-collection or single-writer models
-- (Anton Bruckner at 0.009, one archive's register hand at 0.017) which do not generalise to a
-- personnel file. A super model trained on tens of millions of words is the safer default.
INSERT INTO ai_implementation (
  id, capability, provider, model, base_url, endpoint_path,
  credential_env, max_batch_size, rank, enabled, settings
) VALUES
  (
    'transkribus:free-supermodels',
    'OCR', 'transkribus', 'Transkribus super models (per language)',
    'https://transkribus.eu/processing/v1', '/processes',
    'TRANSKRIBUS_PASSWORD', 1, 2, true,
    jsonb_build_object(
      'jobKind', 'ocr_page_transkribus',
      -- de: German Genius, a German-only super model, 21.1M words.
      -- cs: Transkribus Czech Handwriting M1. Not the lowest CER on offer (6.58% against 4.60%
      --     for "Old Czech Handwriting (with spaces), 1st version") but the only Czech model
      --     trained on ces+deu+lat+slk together, which is what the ABS files are: Czech
      --     interrogation protocols quoting German originals. The lower-CER alternatives are
      --     early modern Czech, three centuries off this material.
      --     Every Czech model is PyLaia, so Czech pages can be started through the API and need
      --     no human in the loop — unlike German and English, whose best models are TrHtr.
      -- en: English Elder, a super model, 10.8M words.
      -- default: Text Titan I, multilingual, for anything else (French, Italian, Latin).
      'htrByLang', jsonb_build_object('de', 265149, 'cs', 263129, 'en', 265029),
      'htrId', 51170,
      -- Typescript, for a photostat the ordinary engine mangles. Transkribus Typewriter is
      -- trained on typewritten pages specifically; for Czech the Czech/Slovak print model, at
      -- 1.20% CER the most accurate model available to this account — worth remembering for the
      -- ABS holdings, which are mostly Czech typescript.
      'printHtrByLang', jsonb_build_object('cs', 42352),
      'printHtrId', 37545,
      'languageModel', 'built-in',
      'tokenUrl', 'https://account.readcoop.eu/auth/realms/readcoop/protocol/openid-connect/token',
      'clientId', 'processing-api-client',
      'usernameEnv', 'TRANSKRIBUS_USERNAME',
      'monthlyCredits', 50,
      'pollIntervalMs', 5000,
      'maxPollMs', 900000,
      'tickIntervalMs', 20000
    )
  ),
  (
    'transkribus:text-titan-ii',
    'OCR', 'transkribus', 'Text Titan II',
    'https://transkribus.eu/processing/v1', '/processes',
    'TRANSKRIBUS_PASSWORD', 1, 3, false,
    jsonb_build_object(
      'jobKind', 'ocr_page_transkribus',
      -- Text Titan II: eng, deu, ita, lat, fra, fin, swe, nld, por, dan, spa, nor. CER 0.043 on
      -- 250M words. It does NOT do Czech — the catalogue's isoLanguages list has no "ces" — so
      -- Czech keeps its own model below. Verified on record 3505 page 110: it read "31. XII. 1943
      -- verlängert wurde" and "Wien III., Metternichg. 10" exactly right where every free model
      -- mangled both, and still lost the flourished signature ("Germin" for "Czernin").
      'htrId', 579509,
      -- Czech has no super model. Transkribus Czech Handwriting M1 is the broadest one, and being
      -- PyLaia it can be started through the API — unlike the TrHtr super models, which the API
      -- refuses with "You are not allowed for TrHtr Recognition!" even on a paid plan. So German
      -- and English handwriting need a human to press Run in the web app, and Czech does not.
      'htrByLang', jsonb_build_object('cs', 263129),
      'printHtrId', 37545,
      'languageModel', 'built-in',
      'tokenUrl', 'https://account.readcoop.eu/auth/realms/readcoop/protocol/openid-connect/token',
      'clientId', 'processing-api-client',
      'usernameEnv', 'TRANSKRIBUS_USERNAME',
      'monthlyCredits', 150,
      'pollIntervalMs', 5000,
      'maxPollMs', 900000,
      'tickIntervalMs', 20000,
      'requiresPaidPlan', true
    )
  )
ON CONFLICT (id) DO NOTHING;
