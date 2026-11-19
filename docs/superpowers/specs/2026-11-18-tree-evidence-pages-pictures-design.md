# Family tree: evidence, page links and pictures — design

Status: approved in conversation 2026-11-12 … 2026-11-18, awaiting review of this document.

## Why

The family tree (the `gen_*` tables, `GenealogyService` as the only writer) backs a §58c citizenship
case. Its value is that every fact can be traced to evidence. Today three things are hard:

1. A document that arrives from outside (a birth certificate by post, a web page) cannot be used to
   add or correct a fact in one step, and a source that is not an archive record cannot be cited at
   all through MCP.
2. A page that shows a person is linked to them in one direction only: the person shows the page, the
   page does not show the person.
3. A picture on a scan cannot reliably become a person's image: MCP needs pixel crops chosen blind,
   the portrait appears only on the person's own page, and a picture cannot be edited once added.

The automatic person matching (`match_persons`, `page_person_match`) does not work: its output is
noise. Linking is therefore **manual**. The computer may only suggest, and only when asked.

## Goals

- Edit the tree from external evidence: one call finds or creates the source and cites the fact.
- Sources inside the archive (a record, optionally a page) or outside it (a URL, or free text).
- A free-text source can later be pointed at the record once the document is scanned, and its
  citations follow.
- Citation quality is GEDCOM QUAY 0–3.
- Add, correct and delete every kind of fact (names, events, couples, parent links, places, sources,
  citations, record links, media, media tags) through MCP as well as REST.
- `get_person` shows each fact's citations (already true; keep it so).
- Conflicting evidence is kept as alternative facts, each cited. Nothing is overwritten to resolve a
  conflict.
- A page shows who is on it; a person's portrait shows wherever the person appears.
- A picture the OCR found on a page can be chosen by number, not by pixels.
- A picture can be tagged with more people, untagged, re-captioned, re-cropped, and made or unmade a
  portrait.
- Suggestions ("hints") for people and pictures are produced by a job someone starts, hidden by
  default, and become links only when confirmed.

## Non-goals

- Automatic linking of any kind. No pipeline stage creates links or hints.
- Face recognition.
- A public (non-admin) editing surface.
- Changing the tree's read-model golden files.

## Anti-goals (would make this a failure even if it works)

- A hint shown as if it were a fact: on the page's people list, the person's page, `get_person`,
  `find_people_in_document`, exports, or anything Claude reports as evidence.
- Any money spent per record without someone asking (the matching stage stays off).
- A picture cut at anything other than the stored original's pixels (`MediaImages`: copied pixel for
  pixel, never scaled).
- An edit without a reason, outside `GenealogyService`, or that cannot be undone.
- Inline `style=""` in the frontend (project rule); bun, not npm.

## Constraints

- All tree writes go through `GenealogyService`, carry a reason, append `gen_change` rows and are
  undoable by `GenealogyHistoryService`. Administrators only (`ROLE_ADMIN`; MCP calls now carry it,
  v1.1.32). Fail closed.
- Migrations are new files (next is V28). Never edit an applied one.
- `FamilyTreeReadModelTest` golden files must still pass unchanged.
- No SQL against production; production data changes go through the API.
- Released only by a tag, only when the owner says "Release".

## The parts

### 1. Sources and citations (MCP)

The REST API already has generic `POST/PUT/DELETE /api/admin/genealogy/{kind}` for every entity.
MCP has only the create tools listed in the skill (`add_person` … `tree_changes`) plus `cite`.

- `cite` gains outside sources: besides `sourceId` and `recordId`, it accepts `url` and/or
  `sourceTitle` (+ optional `author`, `notes`). It finds an existing source with the same record, the
  same URL, or the same title and no record/URL, before creating one. One call, one undoable write.
- New MCP tools: `update_fact` and `delete_fact` taking `kind` (name, event, couple, parent_link,
  place, place_name, source, citation, record_link, media, media_person) and `id`, with the same
  field set the REST `PUT` takes. Thin wrappers over the existing generic service paths.
- Repointing a source: `update_fact kind=source` with `recordId` set on a free-text or URL source.
  Its citations follow automatically because they reference the source.
- Alternative facts: adding a second event of the same tag on a person is allowed and kept; each is
  cited separately. *Builder's call (owner did not decide):* how the read model picks which one the
  tree header shows; suggested — the one with the highest-quality citation, ties to the older row,
  with the others listed as alternatives on the person page.

### 2. A page shows who is on it

On `/records/[id]/pages/[seq]`, a "People on this page" list, built from confirmed data only:

- record links to this page (`gen_person_record.page_id`), with role and note;
- record links to the whole record (`page_id` null), shown on every page of the record and marked
  "linked to the record";
- people tagged in a picture cut from this page (`gen_media.page_id` → `gen_media_person`).

Each entry links to the person and shows their portrait (part 3). The current `page_person_match`
list is removed from this view's default and moves under hints (part 6). The record detail page
(`/records/[id]`) gets the same treatment. MCP `find_people_in_document` returns confirmed links;
hints only on request (a parameter), labelled as hints.

### 2a. Linking from the page

Linking never depends on hints. On any page, an administrator can start from what they are reading:

- **Link a picture.** Click one of the numbered OCR figures, or drag a box around a picture the OCR
  missed. Pick the person by typing their name (the tree's fuzzy search), tick "portrait" if it is
  one, add an optional caption, save. This is one undoable write: the `gen_media` crop, cut from the
  stored original at full resolution, and the `gen_media_person` tag. The picture appears on the
  person at once.
- **Add a person to the page.** Pick the person, a role (subject, mentioned, author, signatory,
  witness, recipient, pictured, other) and an optional note; it creates a `gen_person_record` row for
  this page and the person appears in "People on this page".
- Both ask for a reason, prefilled with the record and page (editable), as every tree write does.
- The same from MCP: `add_media` with `recordId`+`seq` or `pageId` and `figure: N` or a pixel crop;
  `link_record` with `seq` (exists).

### 3. Portraits everywhere

A small round portrait next to a person's name wherever a person appears on the site: search
results, the family-tree views, the relatives on a person page (parents, spouses, children), and the
page people list. No portrait → the name alone, no placeholder. Not in PDF exports. Images come from
the existing family-tree media image route (cropped, unscaled original, sized by CSS). Read APIs that
list people carry the portrait media id so the frontend needs no extra call per person.

### 4. Choosing a picture by number

Mistral OCR stored, for ~22,000 pages, figure boxes in its response (`images[]` with
`top_left_x/y`, `bottom_right_x/y` in the engine's page space; `OcrImageService` already scales them
to the scan and cuts them).

- `get_page` (MCP) and the page viewer list the page's figures, numbered 1… in the engine's order,
  each with its pixel box on the stored scan and a thumbnail.
- `add_media` accepts `figure: N` instead of a pixel crop; the server converts it to the pixel box
  with the same arithmetic `OcrImageService` uses and stores an ordinary pixel crop. Pixel crops still
  work.
- The admin crop tool (`CropSelector` in `PersonEditor`) offers "use picture N" next to drag-to-crop.

### 5. Editing pictures after adding them

Through MCP (`update_fact`/`delete_fact` on `media` and `media_person`, plus a `tag_media` tool for
adding a person to existing media) and the admin editor:

- tag another person in a picture; remove a tag;
- change caption, date, or crop (pixel or `figure: N`);
- set or unset which of a person's pictures is the portrait (one portrait per person is already a
  unique index; setting a new one unsets the old in the same write).

### 6. Hints: suggestions on request only

- The `match_persons` stage is taken out of the pipeline: `EMBEDDING → COMPLETE` directly, no
  `match_persons` jobs enqueued. Records currently in `matching` are advanced by the audit.
- Existing `page_person_match` rows are kept and treated as hints.
- "Give me hints" is a job started by an admin, for a record or one page: a button on the record /
  page view, and an MCP tool (`suggest_people`) taking a record or a single page. Asked for, it runs
  at once: no confirmation step and no page cap (owner's decision). It reports how many pages it
  will read (one model call each) as it starts. It suggests (a) tree people named in the text and (b) for OCR figures, who a
  picture may show, from the caption and surrounding text.
- A new run on a record replaces that record's previous hints. Confirmed links are never touched.
- Hints are hidden by default. Admins see a "show suggestions" switch on the page and record views;
  each hint has "confirm", which creates the record link or media tag through `GenealogyService` with
  the hint as the reason. Non-admins never see hints.
- *Builder's call:* whether picture hints share `page_person_match` (with a figure column) or get
  their own table; and which model the job uses (the configured translation-class model is the
  obvious default).

## Left to the builder

- Endpoint shapes, DTOs, the MCP parameter names beyond those named above.
- How the hint job is queued (an ordinary job kind is expected).
- Frontend component structure, within the Verdant design system and scoped styles.
- Test strategy, following the repo's: Testcontainers, Java `HttpClient`, vitest.

## Done means

Deployed to production (after "Release") and checked there: a page shows its linked people with
portraits; a figure chosen by number becomes a portrait cut at full resolution; an outside source is
cited in one call and later repointed to a record; a hint run on one record produces hidden hints,
one is confirmed, and nothing else changes; no `match_persons` job is enqueued by a new record.

## Decisions made after the builder check (author's calls, owner to check)

1. **Picture hints confirm into media.** Confirming "picture N may show X" creates the `gen_media`
   crop (from the figure's box) and the `gen_media_person` tag in one undoable write. It does not
   make the picture a portrait; that stays an explicit choice.
2. **Figures are keyed by the engine's figure id** (`img.id` in the stored OCR response, which
   `OcrImageService` already uses); the number 1, 2, 3 is for display only. `add_media figure:N`
   resolves N to that id at call time and stores an ordinary pixel crop, so later re-OCR cannot move
   a saved picture. Re-OCR of a page deletes that page's hints.
3. **Record links are `gen_person_record`** (V21), not a new table. A deleted page turns a page link
   into a record link (`ON DELETE SET NULL`), which is still true and is acceptable. Page moves keep
   page ids, so links and media follow the page.
4. **Alternative facts must not change the golden files.** The tie-break applies only where a
   person has more than one event of a tag; verify the golden data against it, and if any golden
   person has duplicates, keep the old choice for them. A "disputed" marker appears on the person
   page only, not in search or kinship output.
5. **Delete refuses when referenced** (409), as the REST API already does for people: a source with
   citations, a place with events. Undo restores whole rows. Repointing a source to a record keeps its
   URL and title.
6. **Hints never leave admin responses.** Every reader of `page_person_match` (UI routes, `/api/v1`,
   MCP `find_people_in_document`) returns hints only to an administrator who asks for them.
   Non-admins never receive them.
7. **Records stuck in `matching`** are advanced to `complete` once, by an admin-triggered audit pass
   after the release. It makes no model calls.
8. **Portraits are carried in a side map**, not added to the golden-covered read-model DTOs, so
   `FamilyTreeReadModelTest` is untouched.
