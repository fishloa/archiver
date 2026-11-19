# Tree Evidence, Page Links and Pictures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Manual, evidenced linking of people to pages and pictures, portraits everywhere, pictures chosen by OCR figure, full MCP edit surface, and person "hints" that run only when asked and are visible only to admins.

**Architecture:** All tree writes stay in `GenealogyService` (generic `create/update/delete` + `gen_change`). New read endpoints assemble confirmed links per page/record and a portrait side map, so the golden-pinned `FamilyTreeService` read model is untouched. The existing `match_persons` job kind is kept but leaves the pipeline: it becomes the on-request hint job, its model call goes through `AiRegistry` like every other, and all hint reads move under `/api/admin/**`.

**Tech Stack:** Java 25 / Spring Boot 4.1, Spring Data JDBC + `JdbcTemplate`, Spring AI MCP (`@McpTool`), Flyway, Testcontainers; SvelteKit 5 (runes), Tailwind v4 + Verdant `--vui-*`, vitest, bun.

**Spec:** `docs/superpowers/specs/2026-11-18-tree-evidence-pages-pictures-design.md`

## Global Constraints

- Every tree write goes through `GenealogyService`, requires a non-blank `reason`, is one undoable write, and is admin-only, failing closed (MCP: `GenealogyMcpTools.run(...)`; REST: `/api/admin/**`).
- A hint is never returned to a non-admin and never appears in "People on this page", the person page, `get_person`, `find_people_in_document` (unless an admin passes `includeHints=true`), exports or `/api/v1`.
- No pipeline stage enqueues `match_persons`. Nothing spends model calls without someone asking.
- Media crops are stored as pixel crops of the stored original and cut pixel for pixel (`MediaImages`), never scaled.
- `FamilyTreeReadModelTest` golden files are not regenerated or edited.
- New migration is `V28__…`; never edit an applied migration.
- Frontend: zero inline `style=""`; bun; every new UI string in `frontend/src/lib/messages/{en,de,cs}.ts` (`bun test tests/i18n-completeness.test.ts`).
- Never call OpenAI. Every model call goes through `AiRegistry` (the archive's configured provider). `PersonMatchService`'s direct `api.openai.com` call and any OpenAI key/config it reads are deleted.
- Commits carry no Claude attribution lines.
- Backend tests: Testcontainers + Java `HttpClient`, not REST Assured. Run `./gradlew spotlessApply` before commit.
- Done means released (only on the owner's "Release") and verified in production (Task 11).

## Review Focus

1. A page with a whole-record link and a page link for the same person → one entry, page link wins (no duplicate). Test in Task 4.
2. `figure: N` out of range, or a page with no OCR figures / non-Mistral OCR → 400 naming how many figures the page has. Test in Task 2/3.
3. A non-admin (role `user`) calling any hint endpoint or `find_people_in_document` with `includeHints=true` → 403 / no hints. Test in Task 5.
4. Confirming the same picture hint twice, or a hint whose page was re-OCR'd → 409 / 404, no second media row. Test in Task 5.
5. Making a second picture the portrait unsets the first in the same write, and undo restores the first. Test in Task 3.

---

### Task 1: Take person matching out of the pipeline

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/service/PipelineStateMachine.java:265-282`
- Test: `backend/src/test/java/place/icomb/archiver/service/PipelineNoMatchingTest.java` (new; model on `AdvanceSinglePageTest`)

**Interfaces:** Produces: no `match_persons` job is enqueued by `autoAdvance`. `PipelineState.MATCHING` stays (historical records), with an unconditional exit.

- [ ] **Step 1: Failing tests**
  - `embeddedRecordGoesStraightToComplete`: record in `embedding` with a completed `embed_record` job → `autoAdvance` → status `complete`; `SELECT count(*) FROM job WHERE record_id=? AND kind='match_persons'` = 0.
  - `recordStuckInMatchingIsReleased`: record in `matching`, no match job → `autoAdvance` → `complete`.
- [ ] **Step 2:** `./gradlew test --tests '*PipelineNoMatchingTest'` → FAIL.
- [ ] **Step 3:** Replace the `EMBEDDING → MATCHING` transition with `EMBEDDING → COMPLETE` (guard `embedJobComplete`, action logs `embedding completed`). Change `MATCHING → COMPLETE` guard to `ctx -> true`. Remove `matchJobComplete()` if unused. Leave `match_persons` in `JobService` cancel lists.
- [ ] **Step 4:** Test PASS; `./gradlew test --tests '*Pipeline*' --tests '*AdvanceSinglePage*'` PASS.
- [ ] **Step 5:** Commit `Pipeline: person matching no longer runs on every record`.

### Task 2: OCR figures as numbered boxes in scan pixels

**Files:**
- Create: `backend/src/main/java/place/icomb/archiver/service/OcrFigures.java`
- Modify: `backend/src/main/java/place/icomb/archiver/service/OcrImageService.java:99-118` (use the shared box arithmetic)
- Test: `backend/src/test/java/place/icomb/archiver/service/OcrFiguresTest.java`

**Interfaces:**
- Produces: `public record Figure(int number, String id, int left, int top, int width, int height)`;
  `@Service OcrFigures { List<Figure> figures(long pageId); Figure figure(long pageId, int number) /* GenealogyException.badRequest if absent */; static Figure box(int number, JsonNode img, double sx, double sy, int fullW, int fullH) }`.
- Numbering: 1-based, in `pages[0].images[]` order. Scan size from `MediaImages.dimensions(pageId)` (no image decode). Pixel arithmetic is exactly OcrImageService's (floor x/y, ceil w/h, clamp to the image); OcrImageService calls `OcrFigures.box`.

- [ ] **Step 1: Failing tests** (pure `box` unit tests + one Testcontainers test with a seeded page, attachment of known size and a `page_text.raw_response`):
  - engine page 1000×1400, scan 2000×2800, image `{id:"img-0.jpeg", top_left_x:100, top_left_y:200, bottom_right_x:300, bottom_right_y:500}` → `Figure(1,"img-0.jpeg",200,400,400,600)`.
  - box overrunning the right edge is clamped to the scan width.
  - `figure(pageId, 3)` on a 2-figure page → `GenealogyException` with message `page <id> has 2 OCR figures; there is no figure 3`.
  - page with no `raw_response` → `figures` empty, `figure(…,1)` → message `page <id> has 0 OCR figures; there is no figure 1`.
- [ ] **Step 2:** FAIL. **Step 3:** implement. **Step 4:** PASS, plus existing `OcrImageService`/PDF tests still PASS.
- [ ] **Step 5:** Commit `OCR figures as numbered pixel boxes`.

### Task 3: MCP edit surface — outside sources, update/delete, figures, tags

**Files:**
- Modify: `backend/src/main/java/place/icomb/archiver/mcp/GenealogyMcpTools.java`
- Test: `backend/src/test/java/place/icomb/archiver/mcp/McpGenealogyToolsTest.java`

**Interfaces:** Consumes `OcrFigures.figure(pageId, n)`. Produces MCP tools:
- `cite(... existing ..., url String, sourceTitle String, author String, sourceNotes String)`. Source resolution order: `sourceId`; `recordId` (existing `recordSource`); `url` → first `gen_source` with that `url`, else create `{title: sourceTitle ?: url, author, url, notes}`; `sourceTitle` alone → first source with that title and `record_id IS NULL AND url IS NULL`, else create. None given → `give sourceId, recordId, url or sourceTitle`.
- `update_fact(String kind, long id, Map<String,Object> fields, String reason)` → `genealogy.update(GenEntity.byKey(kind), id, fields, reason)`. `kind` ∈ name, event, couple, parent_link, place, place_name, source, citation, record_link, media, media_person; anything else → error listing them. For `media` with `fields.figure`, replace it by the figure's four crop fields.
- `delete_fact(String kind, long id, String reason)` → `genealogy.delete(...)` (existing blockers give the 409 text).
- `tag_media(long mediaId, int personId, Boolean portrait, String reason)` → create `MEDIA_PERSON`.
- `add_media`: `recordId`+`seq` **or** `pageId`; new `figure Integer` (mutually exclusive with crop fields → `give figure or a pixel crop, not both`).
- Add `GenEntity.byKey(String)` if absent.

- [ ] **Step 1: Failing tests** (signed in as admin unless stated):
  - `citingAUrlTwiceMakesOneSource`: cite eventId with `url="https://example.org/a"`, `sourceTitle="Parish site"` twice → one `gen_source` with that url, two citations.
  - `aFreeTextSourceIsRepointedAndItsCitationsFollow`: cite with `sourceTitle="Letter from X"` → `update_fact(source, id, {recordId})` → `get_person` shows the citation under a source with that `recordId`; title unchanged.
  - `updateAndDeleteAFact`: add_event → `update_fact(event,{dateText:"5 APR 1934"})` → date changed; `delete_fact(event)` → gone; `undo_change` restores.
  - `deletingACitedSourceIsRefused`: → result `error` contains `still referenced by`.
  - `addMediaByFigure`: seeded page with figures → `add_media(pageId, figure:1, portraitPersonId)` → `gen_media` crop equals `OcrFigures.figure(page,1)` box.
  - `figureAndCropTogetherIsRefused`, `figureOutOfRangeIsRefused` (Review Focus 2).
  - `aSecondPortraitReplacesTheFirstAndUndoRestoresIt` (Review Focus 5): two media, `tag_media(m2, p, portrait:true)` → only m2 portrait; undo → m1 portrait again.
  - `nonAdminIsRefused`: role user → `update_fact` error `administrators only`.
- [ ] **Step 2:** FAIL. **Step 3:** implement. **Step 4:** `./gradlew test --tests '*McpGenealogyTools*' --tests '*Genealogy*'` PASS.
- [ ] **Step 5:** Commit `MCP: outside sources, update/delete any fact, pictures by figure, tagging`.

### Task 4: Confirmed people per page/record, figures and portraits (read API)

**Files:**
- Create: `backend/src/main/java/place/icomb/archiver/genealogy/PagePeopleService.java`
- Modify: `backend/src/main/java/place/icomb/archiver/controller/FamilyTreeController.java` (add routes; delete `page-matches` and `record-matches`)
- Modify: `backend/src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java` (`get_page` adds `figures`; `find_people_in_document` returns confirmed people)
- Test: `backend/src/test/java/place/icomb/archiver/genealogy/PagePeopleServiceTest.java`

**Interfaces:**
- `PagePeopleService.forPage(long pageId) -> List<Map>` and `forRecord(long recordId) -> List<Map>`; entry keys: `personId, name, birthYear, deathYear, portraitUrl (nullable), via ("page"|"record"|"picture"), role, note, mediaId (picture only), pageSeq (record view)`. One entry per person per page; precedence page > picture > record, keeping all roles in `roles`.
- `portraits(Collection<Integer> ids) -> Map<Integer,String>` (`/api/family-tree/media/{id}/image` of the `is_portrait` row; merged people resolved via `merged_into`).
- Routes (signed-in users): `GET /api/family-tree/page-people/{pageId}`, `GET /api/family-tree/record-people/{recordId}`, `GET /api/family-tree/pages/{pageId}/figures` (→ `List<Figure>`), `GET /api/family-tree/portraits?ids=1,2,3`.
- MCP `find_people_in_document(recordId, includeHints Boolean)` → `{people: forRecord(...)}`; `hints` only when `includeHints` and caller is admin (Task 5 supplies hint reads).

- [ ] **Step 1: Failing tests:**
  - person linked to record R (no page) and to page 2 of R → `forPage(p2)` one entry `via=page`; `forPage(p1)` one entry `via=record` (Review Focus 1).
  - media cut from page 3 tagged with person X → `forPage(p3)` contains X `via=picture`, `mediaId`.
  - `page_person_match` row for page 2 → not in `forPage(p2)`.
  - `portraits([X, Y])` → X's URL, Y absent.
  - `GET /api/family-tree/page-matches/1` → 404.
- [ ] **Step 2:** FAIL. **Step 3:** implement. **Step 4:** PASS + `FamilyTreeReadModelTest` PASS unchanged.
- [ ] **Step 5:** Commit `Read API: confirmed people per page and record, figures, portraits`.

### Task 5: Hints on request, admin only

**Files:**
- Create: `backend/src/main/resources/db/migration/V28__page_person_hint_figure.sql`
- Modify: `backend/src/main/java/place/icomb/archiver/service/PersonMatchService.java`, `PersonMatchWorker.java`, `model/PagePersonMatch.java`, `repository/PagePersonMatchRepository.java`
- Create: `backend/src/main/java/place/icomb/archiver/controller/AdminHintController.java`
- Modify: `backend/src/main/java/place/icomb/archiver/mcp/GenealogyMcpTools.java` (`suggest_people`), the code path that stores a new `page_text` for a page (drop that page's hints)
- Test: `backend/src/test/java/place/icomb/archiver/genealogy/HintsTest.java`, `PersonMatchServiceTest` (existing, adjust)

**Interfaces:**
- V28: `ALTER TABLE page_person_match ADD COLUMN figure_id text;` (null = person-in-text hint; set = "this figure may show the person").
- Job: kind `match_persons`, enqueued only by `POST /api/admin/genealogy/hints/run {recordId}` or `{pageId}` (record id derived) → `{jobId, pages}`. `PersonMatchWorker.processJob`: `job.getPageId() != null ? matchPage(pageId) : matchRecord(recordId)`. A run first deletes existing hints for its scope (replace, never append). It does not touch `gen_*`.
- Model call: replace the hard-coded `https://api.openai.com/v1/chat/completions` with `AiRegistry.best(AiCapability.TRANSLATION)` + `registry.credential(...)`, POSTed like `SyncTranslateWorker.translate` (endpoint + endpointPath, Bearer). Prompt adds the page's figures (`OcrFigures.figures`) with any caption text adjacent to `![img-N]` in the markdown and asks for `{"people":[{personId,score,context}],"figures":[{figureId,personId,score,context}]}`. On model failure the job fails (no silent heuristic-only fallback).
- `GET /api/admin/genealogy/hints?pageId=|recordId=` → hint rows enriched with name/years/figure number.
- `POST /api/admin/genealogy/hints/{id}/confirm {reason?, role?, portrait?}` → in one write: text hint → `RECORD_LINK {personId, recordId, pageId, role: role ?: "mentioned", note: context}`; figure hint → `createMedia({pageId, crop of the figure}, personId, portrait ?: false, ...)`. Default reason `confirmed hint #<id> (page <seq> of record <id>)`. Confirming deletes the hint row in the same transaction. Hint gone → 404.
- New `page_text` stored for a page (OCR / re-OCR / import) → `DELETE FROM page_person_match WHERE page_id = ?`.
- MCP `suggest_people(recordId | recordId+seq | pageId)` (admin) → enqueues as above; `find_people_in_document(includeHints=true)` returns `hints` for admins.

- [ ] **Step 1: Failing tests** (model stubbed via the registry, as `TranslatorStub` does):
  - `runningHintsOnAPageReplacesThatPagesHintsOnly`.
  - `noOpenAiAnywhere`: `grep -rn "openai.com" backend/src/main` returns nothing (assert in a test reading the source tree, or as a Step-4 command).
  - `noRecordEnqueuesMatchingByItself` (covered by Task 1; assert again after ingest-complete fixture).
  - `confirmingATextHintMakesAPageLinkAndRemovesTheHint`; `gen_change` has one write with the default reason.
  - `confirmingAFigureHintCutsTheFigureAndTagsThePerson` — media crop equals the figure box; not portrait unless `portrait:true`.
  - `confirmingTwiceIs404` (Review Focus 4).
  - `reOcrDropsThePagesHints`.
  - `nonAdminGets403OnEveryHintRoute` and `find_people_in_document(includeHints=true)` as user → no `hints` key (Review Focus 3).
- [ ] **Step 2:** FAIL. **Step 3:** implement. **Step 4:** `./gradlew test --tests '*Hints*' --tests '*PersonMatch*' --tests '*Mcp*'` PASS.
- [ ] **Step 5:** Commit `Hints: run on request, admin only, confirm into links`.

### Task 6: Frontend API helpers and the portrait avatar

**Files:**
- Modify: `frontend/src/lib/server/api.ts` (remove `fetchPagePersonMatches`, `fetchRecordPersonMatches`; add below)
- Create: `frontend/src/lib/components/genealogy/PersonAvatar.svelte`
- Test: `frontend/src/lib/components/genealogy/PersonAvatar.test.ts`

**Interfaces:**
- `fetchPagePeople(email, pageId): Promise<PagePerson[]>`, `fetchRecordPeople(email, recordId)`, `fetchPageFigures(email, pageId): Promise<Figure[]>`, `fetchPortraits(email, ids: number[]): Promise<Record<number,string>>`, `fetchHints(email, {pageId?|recordId?})`, `runHints(email, {pageId?|recordId?})`, `confirmHint(email, id, body)`.
- `PersonAvatar` props `{ url: string | null; name: string; size?: 'sm' | 'md' }`: renders a round `<img alt={name}>` when `url`, nothing when null. Scoped `<style>`, no inline style.
- [ ] Steps: test (renders img with alt when url; renders nothing when null) → FAIL → implement → `bun run test` PASS → `bun run check` 0 errors → commit `Frontend: people/figure/portrait/hint API and avatar`.

### Task 7: Page and record views — people, linking from the page, suggestions

**Files:**
- Modify: `frontend/src/routes/records/[id]/pages/[seq]/+page.server.ts`, `+page.svelte`
- Modify: `frontend/src/routes/records/[id]/+page.server.ts`, `+page.svelte` (people list replaces the matches block at ~505)
- Create: `frontend/src/lib/components/genealogy/PagePeople.svelte`, `PageLinker.svelte`, `HintList.svelte`
- Modify: `frontend/src/lib/messages/{en,de,cs}.ts`

**Interfaces:**
- `PagePeople` `{ people: PagePerson[] }` — avatar, name link to `/family-tree?person=<id>`, role, "linked to the record" tag for `via=record`.
- `PageLinker` (admin only) — figure overlay numbers 1..n over the scan as an SVG overlay (`viewBox` = scan pixel size, one `<rect>` + number per figure; no inline styles), click a figure or drag with `CropSelector`; `PersonPicker`; portrait checkbox; caption; reason prefilled `from record <id> page <seq>` (editable). Saves via form actions posting to `/api/admin/genealogy/media` (`createMedia` path) or `record-links`. Second mode "Add person": PersonPicker + role select (subject, mentioned, author, signatory, witness, recipient, pictured, other) + note.
- `HintList` (admin only) — hidden until "Show suggestions" is toggled; lists hints with Confirm; "Suggest people" button runs hints for this page (page view) or record (record view) and shows the page count returned.
- Non-admins: only `PagePeople`.
- [ ] Steps: add vitest for `PagePeople` (record-tag shown for `via=record`) and `HintList` (hidden by default) → FAIL → implement → `bun run test`, `bun run check`, i18n test PASS → run `make dev-backend` + `make dev-frontend` locally, link a figure on a seeded page as admin and see it under People; non-admin sees no suggestions control → commit `Page view: people on this page, link from the page, suggestions`.

### Task 8: Portraits everywhere

**Files:**
- Modify: `frontend/src/routes/family-tree/+page.server.ts`, `+page.svelte` (search results, relatives), `frontend/src/lib/components/genealogy/PersonFacts.svelte` (parents, spouses, children)
- [ ] The server load collects every person id it renders, calls `fetchPortraits` once, passes the map; each name gets `<PersonAvatar url={portraits[id] ?? null} …>`. Test: vitest on `PersonFacts` with a portrait map shows the avatar for a parent. `bun run test`, `bun run check` PASS. Verify locally. Commit `Portraits beside names across the tree`.

### Task 9: Edit pictures after adding them; disputed facts

**Files:**
- Modify: `frontend/src/lib/components/genealogy/PersonEditor.svelte`, `CropSelector.svelte` (optional `figures` prop → "use picture N" buttons), `PersonFacts.svelte`
- [ ] Per media item (admin edit mode): edit caption/date (`PUT media/{id}`), re-crop by drag or figure (`PUT media/{id}` with crop), tag a person (`POST media-people`), untag (`DELETE media-people/{id}`), make/unmake portrait (`PUT media-people/{id} {isPortrait}`), each with a reason field.
- [ ] `PersonFacts`: when a person has more than one event of the same tag with different `dateText` or `place`, mark each "disputed" and show both with their citations. Read model unchanged.
- [ ] Tests: vitest for disputed marker (two BIRT with different dates → two "disputed" labels; one → none). `bun run test`, `bun run check` PASS; local check of a re-crop. Commit `Edit pictures after adding them; show disputed facts`.

### Task 10: Documentation

**Files:** `.claude/skills/archiver-api/SKILL.md` (MCP tools table: `update_fact`, `delete_fact`, `tag_media`, `suggest_people`, `cite` outside sources, `add_media figure`; hint endpoints), `CLAUDE.md` (pipeline: `embedding → complete`; `match_persons` is on-request hints, not a stage; `page_person_match` holds admin-only hints).
- [ ] Edit, commit `Docs: tree evidence tools and on-request hints`.

### Task 11: Full checks, release, production verification

- [ ] `make lint`, `cd backend && ./gradlew test`, `make test-frontend`, `cd frontend && bun run check` — all green. Fix before going on.
- [ ] Merge to main after the owner agrees; tag only when the owner says **"Release"**; `jk run view archiver <n> --wait`; webhook; `GET /api/version` shows the tag; containers healthy.
- [ ] In production (through MCP/API only, reasons given, undo afterwards where it was only a test):
  1. record 4093 page 3 shows Wolfgang, Gerda (record) and Nikolaus (page) in People with portraits where present;
  2. a figure on a real Mistral page chosen by number becomes a portrait; the crop's pixel size equals the figure box on the full scan;
  3. `cite` with a URL source, then `update_fact` repointing it to a record;
  4. `suggest_people` on one page → hints visible only to admin; confirm one; nothing else changed (`tree_changes`);
  5. a new ingest goes `embedding → complete` with no `match_persons` job (`list_jobs`).
- [ ] Once the owner releases: run the admin audit (`POST /api/admin/audit`) so records left in `matching` complete; confirm none remain.
