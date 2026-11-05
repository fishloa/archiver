# Multi-language PDF export (English, German, Czech)

## Goal

The translation PDF and the side-by-side PDF (scan facing text) can be made in **English, German
or Czech**, chosen per export. Example: a German original with a Czech translation beside it, for
a Czech archive or court.

## Requirements

1. **Languages:** `en`, `de`, `cs` (ISO 639-1). Anything else is a 400 / IllegalArgumentException
   naming the accepted values. The default is `en`, so every existing caller behaves exactly as now.
2. **What text a page gets in language L:**
   - `L = en`: the existing best English translation (`TranslationService.bestEnglish*`). Unchanged.
   - `L` = the record's content language (`record.lang`): the transcription itself (`text_raw`,
     read through its `content_type` as now), not a translation. German into German is never
     paid for.
   - otherwise: the best stored translation of that page into L. If none exists, the export worker
     translates the page while it builds the export, stores the result, then renders.
3. **Pay once, in the table we already have.** Translations into any language are stored in the
   existing `page_translation` table, which gains a `lang` column (NOT NULL DEFAULT 'en', CHECK in
   ('en','de','cs')); its text column `text_en` is renamed `text`, and its unique key becomes
   (page_id, lang, model). Existing rows become lang='en' unchanged. Every existing reader and
   writer of `page_translation` filters `lang = 'en'`, so English behaves exactly as now. The
   existing ranking (`TranslationService`, `ai_implementation` order) picks the best model per
   page **and language**. A later export, of any variant, any page selection or any requester,
   reuses the stored translation and never calls the model again for that page and language. Only
   an export that needs a missing translation triggers one call per missing page, and never for
   pages outside the selection. `page_text.text_en` stays the English cache only.
4. **Blank pages:** an empty `text_raw` gives an empty translation with no model call (same rule
   as `SyncTranslateWorker`).
5. **English pipeline unchanged in behaviour.** `page_text.text_en`, the batch/sync translate
   workers, the ranking, search, the viewer, hand corrections and the state machine produce
   exactly what they do today; their `page_translation` queries only gain the `lang = 'en'` filter
   and the column rename.
6. **Translator:** the same configured TRANSLATION implementation from `AiRegistry` (the one the
   sync path uses, i.e. its endpoint, key and model), called synchronously, one page per request.
   `ChatTranslator`'s instruction becomes parameterised by target language ("into English" / "into
   Czech" / "into German"), keeping the evidence-preservation wording. The existing English request
   text must stay byte-identical (batch requests must not change). `parse` cleanup
   (`unwrapCodeFence`, `stripPreamble`) also recognises Czech/German translation preambles
   ("Here is the Czech translation:", "### Czech translation", "Překlad:", "Übersetzung:").
   Tables are repaired on the way in (`Markdown.repairTables`), as for English.
7. **Exports:** `pdf_export` gains `lang` (NOT NULL DEFAULT 'en', CHECK in ('en','de','cs')). The
   variant called `english` today means "translation": it accepts `translation` as its API name,
   with `english` still accepted as an alias for `translation` with lang=en. Reuse of an existing
   export matches on lang too. The fingerprint covers the new table's rows for the selected pages
   and the lang, so a new or changed translation is never served from a stale file. For `original`,
   lang is irrelevant and stored as 'en'.
8. **File names / labels** say the language: `-czech`, `-original-and-czech`, `-german`,
   `-original-and-german`; the English names stay `-english`, `-original-and-english`. The
   side-by-side and translation pages and the cover sheet say which language the text is in and
   whether it is a transcription or a translation, and which model produced the translations
   shown, as the English cover sheet does now. The cover sheet's record title/description stay as
   they are now (English).
9. **Fonts:** Czech diacritics (ě š č ř ž ý á í é ů ú ť ď ň and capitals) must render. DejaVu
   covers them; a test must prove a Czech page renders without missing-glyph errors.
10. **Every entry point gets `lang`:** the REST export endpoint, the MCP tools `export_pdf` (and
    `get_pdf_export` / `download_pdf` report lang), and the record page's export UI in the frontend
    (a language choice next to the variant; Verdant classes, no inline styles, i18n strings via the
    existing i18n mechanism so `make test-frontend` passes).
11. **Failure:** if no translator is configured, or the model call fails, the export goes to
    `failed` with a readable error, and translations already stored by that export stay stored
    (nothing paid for is thrown away).

## Out of scope

Czech/German in the page viewer, search, the machine API, or hand corrections of the new
translations; translating record metadata into cs/de; automatic (pipeline) translation into cs/de.
