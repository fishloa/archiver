# Genealogy Tree Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the read-only family tree parsed from `czernin-genealogy.txt` at startup with a genealogy stored in PostgreSQL (`gen_*` tables), imported once from the legacy file with ids 1–348 preserved, editable by administrators through the web UI and MCP tools with a reason and an undoable audit row for every write, evidence-backed through sources and citations, and exportable as GEDCOM 7.

**Architecture:** Migration V21 creates the `gen_*` tables. V22 is a Java Flyway migration (a Spring bean that Spring Boot hands to Flyway) that runs the old parser, kept verbatim as `LegacyTreeParser` so ids are reproduced exactly, plus a new `LegacyLineParser` that turns each line into structured facts, and writes them through `LegacyImport`. V23 remaps references to the two folded section roots and adds the foreign keys from `page_person_match` and `app_user`. `FamilyTreeService` keeps its public methods and answer shapes but becomes a cached read model over the tables, invalidated after every committed write. Its behaviour is pinned by golden files captured from the in-memory tree before anything changes. `GenealogyService` is the only writer: generic create, update and delete over a `GenEntity` catalogue, with validation and one `gen_change` row per row changed. `GenealogyHistoryService` merges people and undoes writes from the stored before-images. On top of these sit an admin REST controller, the MCP write tools, a lossless media crop endpoint, a GEDCOM 7 exporter, and SvelteKit read and edit views.

**Tech Stack:** Java 25 / Spring Boot 4.1, Spring `JdbcTemplate`, Flyway 12 (SQL plus one `BaseJavaMigration` bean), PostgreSQL 18 (`jsonb`, `gen_random_uuid()`), `javax.imageio`, Spring AI MCP annotations, JUnit 5 + Testcontainers (`pgvector/pgvector:pg18`) + Java `HttpClient`, SvelteKit + Svelte 5 runes, Verdant UI, vitest + @testing-library/svelte, bun.

**Spec:** `docs/superpowers/specs/2026-11-02-genealogy-tree-design.md`. Read it in full before starting. It is the binding authority. Where it was silent or the code disagreed, this plan's choices are listed under "Decisions this plan takes".

## Global Constraints

- Administrators only (`ROLE_ADMIN`) for every write, REST or MCP; every write takes a non-blank `reason`.
- Ids 1–348 are the legacy parse-order ids and are preserved; new people are numbered from 349.
- Never edit an applied migration. This plan adds `V21__genealogy.sql`, `V22__Import_legacy_genealogy` (Java) and `V23__genealogy_person_references.sql`; `V20__manual_correction.sql` is the latest applied.
- `docs/superpowers/plans/2026-10-30-multilang-pdf-export.md` also claims V21: whichever plan executes second renumbers its migrations to the next free numbers.
- Java is formatted by Spotless with Google Java Format: run `cd backend && ./gradlew spotlessApply` before every commit.
- Backend tests use Testcontainers (PostgreSQL) and Java `HttpClient`, never REST Assured.
- Use bun, never npm: `bun install`, `bun run`, `bunx`.
- Zero inline `style=""` attributes (and no `style:` directives), anywhere.
- Use Verdant design-system classes (`vui-*`, `--vui-*`) verbatim. Do not override, extend or duplicate them.
- Every new UI string has i18n keys in `en`, `de` and `cs`.
- Never write comments that explain what code does. Comments only for what the code cannot say.
- Commit messages carry no `Co-Authored-By` line and no Claude attribution of any kind.
- Person matching behaviour is unchanged (out of scope): `PersonMatchService` changes only its three reads of `person.name` to `person.matchName()`, and sees the same 346 people, legacy names, years, sections and birth-place tokens (two old-parser artefacts excepted; see decision 12). `PersonMatchServiceTest`'s assertions are unchanged.
- A family member's display name is given + " " + surname with surname Czernin (never doubled); that is the only intended difference from the golden files, apart from the ranking of queries containing "czernin".
- Media crops never rescale; a whole-image media item returns the stored original bytes untouched.
- Commit working code before any refactor. Touch only what the task names.
- Frontend gates are `cd frontend && bun run check` (0 errors) and `make test-frontend`. `make lint-frontend` fails before this work because the repo has no ESLint config; that is pre-existing and not a gate.

## Review Focus

These are the five failure modes most likely to slip past a straight reading of the tasks. Each has a test in the task that owns the code.

1. **Kinship, names or years drift from the pre-migration tree, or the surname leaks into matching.** The read model walks a graph that now has two birth parents per child and spouses as people, and every family member's display name gains Czernin; a lowest-common-ancestor tie, a lost legacy quirk, a doubled surname, or a "czernin" token reaching person matching would slip by. *Task 1 captures `people.tsv`, `kinship.tsv` (1,730 pairs) and `search.tsv` verbatim from the in-memory tree; Task 6 `FamilyTreeReadModelTest` asserts the database tree reproduces them with names mapped only by `expectedDisplay`, pins 76 → "Johann Rudolf Czernin", 322 → "Alexander Friedrich Josef Paul Maria Czernin", 1 → "Humprecht Czernin z Chudenic", and checks `matchName()` equals the legacy name for all 346.*
2. **Legacy lines with unusual syntax are lost or misparsed.** `*and +1664`, `+31.7./12.11.1787`, `(div)` without a year, `(annulled 1936)`, `same 2nd wife`, `(z Gutštejna?)`, `[same woman as G2's wife]`, three adoption spellings, `bur Radenín`, cause-of-death words. *Task 4 `LegacyLineParserTest` (verbatim lines); Task 5 `LegacyImportTest.everyLegacyIdHasItsOldName`, `jaromirHasFourCouplesThreeEndingInDivorceAndOneSpouseMarriedTwice`, `theAdoptionsBecomeAdoptedLinksOrAdoptionEvents`, `theReviewListNamesTheKnownConflicts`.*
3. **Undo after an intervening edit.** Undo must refuse when the row has moved since, must work again once the later change is undone, and must refuse a second undo. *Task 8 `GenealogyHistoryTest.undoIsRefusedOnceTheRowHasChangedAgain`, `undoingACreateRemovesItAndUndoingADeleteRestoresIt`.*
4. **A merge misses a link type or trips a unique key.** `page_person_match (page_id, person_id)`, `gen_parent_child (parent_id, child_id)` and `gen_media_person (media_id, person_id)` are unique, portraits are one per person, and people already merged into the gone person must follow. *Task 8 `aMergeRepointsEveryKindOfLink`, `aMergeWritesOneAuditRowPerRowItChanged`, `undoingAMergePutsEverythingBack`, `aMergeRefusesThingsThatCannotBeOnePerson`.*
5. **Existing `page_person_match` or `app_user` rows point at the dropped section-root ids (122, 227) or at nobody.** V23 must remap or drop them before adding the foreign keys, or the migration fails on production. *Task 5 `GenealogyReferencesMigrationTest` stops Flyway at V22, plants such rows, then runs V23.*

Also covered, though less likely to bite: a crop rectangle on or past the page edge (Task 7 `aCropMustLieInsideTheStoredImage`, Task 10 `aCropTouchingTheImageEdgeIsAllowedAndExact`, `aCropTheCurrentScanNoLongerContainsIsAConflictNotAnError`, Task 14 `cropFromPoints` tests), and the GEDCOM export of a person with no events (Task 12 `aPersonWithNoEventsIsStillAValidRecord`).

## Decisions this plan takes

The spec is silent on these, or the code contradicts it. Each is settled here so that tasks agree.

1. **Three legacy columns on `gen_person`.** These are `legacy_section`, `legacy_code` (the old `fullCode()`, e.g. `.A5.B2.C1`) and `legacy_depth`. The person JSON returns `section`, `code` and `depth`, and `PersonMatchService` boosts by section. None of these can be derived once the CZERNIN 2 and 3 roots are folded.
2. **The two section roots (122 → 76, 227 → 80) keep rows** with `merged_into` set, a primary name and `legacy_entry`. Every id 1–348 therefore exists. `getPerson(122)` resolves to 76, where it used to return null.
3. **Family members' primary names carry the surname Czernin.** For every person parsed from the file as a family member (ids 1–348, and so their birth descendants), `given` is the legacy forenames and `surname` is `Czernin`. Exceptions:
   - A legacy name already holding a Czernin form is split at it, never doubled: `Humprecht` / `Czernin z Chudenic`, `Philippine Theresia` / `Czernin-Morzin`, `Josef Maria Rudolf Ferdinand` / `Czernin-Kinsky` (the trailing `(adopted 1954)` is dropped from the name; it is an ADOP event), `Alexander Hannibal` / `Czernin Fishlock`. Title words before the Czernin form (`Graf` in the two folded roots) go to `prefix`.
   - The eight descendants through daughters whose legacy name ends in a surname of their father's line (277–278 Phillips, 289–294 Beckett) keep it as their surname. A word counts only if it is the parent's surname, the parent's spouse's surname or the parent's assumed surname, and never appears as a forename anywhere in the file.
   - `Rudolf Heřman Maria Josef, Graf Czernin von Chudenitz und Morzin` and the three others like it: the legacy name ends at the comma, so the name is `Rudolf Heřman Maria Josef` / `Czernin`, and `Graf Czernin von Chudenitz und Morzin` stays a `TITL` event.
   - Daughters keep Czernin as their birth surname; no `married` name is created, because the file states none.

   The display name is `given + " " + surname`, with null parts skipped and prefix and suffix not shown; it is what REST, MCP, kinship text, the web and the GEDCOM `NAME given /surname/` show. Person matching reads `Person.matchName()`, which for imported people is the legacy name exactly as before (decision 32).
4. **Spouse names** are split by heuristic. Title tokens (`Gf`, `Gfn`, `Graf`, `Frhr`, `Frn`, `Freiherr`, `Fst`, `Fürst`, `Pss`, `Pr`, `Prinz`, `Css`, `Hon.`, `Altgraf`, `Gräfin`) go to `prefix`. The surname starts at the first particle after the first word (`von zu zü z ze de Des di da del dell van auf`), or else is the last word. A `"nickname"` goes to `nickname`.
5. **The second birth parent.** When the parent's couple is determinable (one marriage, a `[Nm.]` prefix, a `--- Nm children: ---` header, or `all children by Nm.`), the child gets `couple_id` and a second `birth` link to the spouse. If a parent has two or more marriages and the child's is not stated, there is no second link, and the parent is flagged "which marriage each child is from is not recorded".
6. **Remarriage to the same spouse** (`3m: … same 2nd wife`) is the same couple with a second `MARR` (and `DIV`). Jaromír Egon Rudolf (141) therefore has 4 couples, 3 of them with `DIV`.
7. **An annulment is an `EVEN` event** with description `annulled`, because `ANUL` is not in the spec's tag list. A `(div)` with no year is a `DIV` event with no date.
8. **Descriptors** between a name and its dates (or after a spouse's name, after a comma) become `TITL` when they start with `cr`, `Graf`, `Reichsgraf`, `Altgraf`, `Freiherr`, `Fürst`, `Gf`, `Frhr` or `Fst`, and `OCCU` otherwise. A trailing date becomes the event's date.
9. **Cause-of-death words** (`young`, `k.a.`, `m.i.a.`, `beheaded`, `accident`, `car accident`, `committed suicide`, `Japanese prison`, `as Ursuline nun`, `on woundings after battle of Leipzig`) go to `description`. The date and place in the same text are still parsed.
10. **A date the converter cannot read** (`31.7./12.11.1787`, `1651/after 1660/1666`, `19/29.10.1871`, `28.4.1953/55`, `29.9.1703/29.7.1705`) keeps its first four-digit year as `date_text`. The raw text goes to `description`, and the person gets a `needs_review` line "date not understood: …".
11. **Tree members versus everyone, and search.** Search scores the forenames (`matchName()`) exactly as before; a query word that matches no forename may then match the surname exactly, so "czernin rudolf" ranks the Rudolfs at 1.0, and forename-only queries rank exactly as before. `getAllPeople()` and `search(q, n)` cover people with a legacy section plus their birth descendants, excluding merged people. That is exactly the 346 today, so matching and search are unchanged. Spouses are reachable by id and by `search(q, n, true)` (`/api/family-tree/search?all=true`), which the pickers use.
12. **`birthPlace` becomes the clean place name.** The old parser returned artefacts such as `Wien , +Oxford`. The golden comparison checks the token `PersonMatchService` actually uses (the text before the first `,` or `+`, normalised, four or more characters). Two documented exceptions were artefacts: 91 (`/after`) and 200 (`and +Klagenfurt`, now Klagenfurt).
13. **Answer values that change while shapes stay.** Every family member's `name` (and `personName`, `commonAncestorName`, `refPersonName`, `pathDescription`) now ends in Czernin. `spouses` holds the spouses' display names instead of fragments of the legacy text. `events` is the structured list, but each item keeps `type`, `text` and `year` and adds `id`, `tag`, `date`, `dateFrom`, `dateTo`, `place`, `placeId`, `description`, `coupleId`, `spouseId` and `citations`.
14. **Kinship over a graph.** The common ancestor is the one minimising `dA + dB`, then `dB`, then the lowest id; that is identical to the old walk on a tree. When there is no blood link, the order is: spouse (`spouse`); the person married to the reference's relative (`sibling-in-law`, `child-in-law`, `step-parent`, else `spouse of <label>`); the person related to the reference's spouse (`sibling-in-law`, `parent-in-law`, `step-child`, else `<label> of spouse`).
15. **What one undo restores.** A write is every `gen_change` row its transaction appended. They share `changed_at`, which is the transaction start, and `changed_by`. Undo restores all of them, last first. It is refused with a 409 if any row's current content, ignoring `updated_at`, no longer contains the stored `after`. That also refuses a second undo.
16. **Delete.** Rows owned by the deleted row are deleted with it, each audited: a person's names, events and citations; an event's or name's citations; a couple's events; a place's alternative names; a media item's person links. Rows that merely reference it block the delete with a 409: couples, parent links, record links, media links, `page_person_match`, `app_user`, people merged into it, events at a place, citations of a source.
17. **Merge.** If the keep person already has a link that is unique, the gone person's copy is deleted, its citations moved first. The merge is refused (409) if it would give anyone more than two birth parents, or if the two people are a couple or parent and child.
18. **`gen_change` has only `changed_at`**, not `created_at` and `updated_at`. An audit row is never updated. `gen_media_person` gets an `id` so that its audit rows have an `entity_id`.
19. **`gen_person_record.record_id` is `ON DELETE CASCADE`** (it is a required link). Optional links to `record` and `page` use `SET NULL`, and `gen_media` cascades from `page`, as the spec says.
20. **V22 is a `@Component`** in `place.icomb.archiver.genealogy.legacy`. `FlywayAutoConfiguration` collects `JavaMigration` beans, so Flyway's classpath scan of `db/migration` inside the nested Boot jar is not relied on. Its input file moves to `backend/src/main/resources/db/legacy/czernin-genealogy.txt`, because a migration's input must remain. The runtime reader and the `archiver.family-tree.file` property go.
21. **Startup no longer truncates `page_person_match`.** That was a side effect of `reload()`. V23 remaps rows instead: matches naming a folded root move to its twin, or are dropped if the twin already has one on that page. Matches naming nobody are dropped (a match is a cache). `app_user` references to nobody are nulled.
22. **Two existing tests change their setup, not their assertions.** `PersonMatchServiceTest`'s `@BeforeAll` builds the tree from `people.tsv` through `FamilyTreeService.forPeople`, because the no-arg constructor and `reload()` are gone. `FamilyTreeParseTest` is deleted and its three cases are ported to `LegacyLineParserTest`. `FamilyTreeControllerTest`'s two reload tests become "returns 404" tests.
23. **`living`.** The import sets it to true for people born in 1926 or later with no recorded death. The export defaults to `living=exclude`.
24. **`sex` is `U`** for every imported person, because the file does not say. GEDCOM `HUSB`/`WIFE`: the `F` partner is `WIFE`, otherwise `person1` is `HUSB`.
25. **GEDCOM details.** `_UID` follows the spec rather than 7.0's `UID`. Single-parent families are `@F_P<parentId>@`. `OBJE.FILE` is `https://archive.czernin.eu/api/files/{attachmentId}`, which is the stored original that `CROP` applies to. Citation `PAGE` carries the detail and "archive page N". There is no Java GEDCOM 7 validator library, so validity is checked by a structural validator in the test, plus exact-block assertions.
26. **The media crop does not reuse `OcrImageService.cropAll`**, which scales from the OCR engine's coordinate space and writes lossy JPEG. It reuses `AttachmentRepository` and `StorageService.getPath`. Crop bounds are checked against the stored image's header, not `page.width`/`height`. A crop outside the image is a 400 when written, and a 409 when read after the scan was replaced.
27. **Two additive conveniences, so that one UI action is one undo.** An event may give `placeName`, which is resolved to the place of that exact name or created in the same write. `POST /api/admin/genealogy/media` accepts `personId` and `isPortrait`, so the image and its link are one write.
28. **The browser cannot reach `/api/admin`**, because nginx clears `X-Auth-Email` there. All web edits therefore go through SvelteKit form actions. Pickers use `GET /api/family-tree/search?all=true` and `GET /api/records/{id}/pages`.
29. **MCP.** The write tools live in a new `GenealogyMcpTools` component. Each tool call is one transaction (`TransactionTemplate`), and the edit rules answer `{"error": …}`, as `delete_page` does. A caller without `ROLE_ADMIN` gets an `IllegalStateException`.
30. **REST** uses `PUT` for updates (SecurityConfig does not cover `PATCH`) and a JSON body `{"reason": …}` on `DELETE`. The statuses are: 400 for malformed input (missing reason, unknown field, wrong type, bad date, crop outside the image, two citation targets); 404 for a missing row or reference; 409 for state conflicts (cycle, third birth parent, referenced delete, primary name, merged person, changed since).
31. **Release check, spec step 5.** "Diana Hippoliti da Gazolo" is Diana Maria, wife of Humprecht Jan (E4 under D1 Jan Baptist Adam), whose line reads `Diana Maria (*Frassino, Mantua 2.9.1636, …)`. She is a spouse person (id above 348), imported as given `Diana`, surname `Maria` under decision 4; Task 15 finds her id in the GEDCOM export.
32. **`matchName()` for imported people is the legacy name re-derived from `legacy_entry`** (`LegacyTreeParser.legacyName`), not the stored given names. It is the only way to give `PersonMatchService` exactly the tokens it scored on before (including the Czernin forms some legacy names carried and the `(adopted 1954)` one did). An admin's later correction of a forename therefore does not reach matching; redesigning matching is the follow-up project.
33. **The golden comparisons after the move** compare names through `FamilyTreeGolden.expectedDisplay` (legacy name + " Czernin"; unchanged, less a trailing parenthetical, for names already holding Czernin and for the eight inherited surnames). Kinship path descriptions are checked by substituting the display names of the people the path names (person, reference, common ancestor and the two branch children). The "czernin" search query is excluded, because it ranks differently by design; a separate test pins "czernin jaromir".

## File Structure

All backend paths are under `backend/`, frontend paths under `frontend/`.

| File | Task | Responsibility |
|---|---|---|
| `src/test/java/place/icomb/archiver/service/FamilyTreeGolden.java` (create) | 1 | Writes the tree's behaviour as text (`people.tsv`, `kinship.tsv`, `search.tsv`), compares it, rebuilds the legacy people for `PersonMatchServiceTest` |
| `src/test/java/place/icomb/archiver/service/FamilyTreeGoldenTest.java` (create in 1, delete in 6) | 1 | Captures and pins the golden files from the in-memory tree |
| `src/test/resources/genealogy/golden/{people,kinship,search}.tsv` (create) | 1 | The golden files |
| `src/main/resources/db/migration/V21__genealogy.sql` (create) | 2 | The `gen_*` tables |
| `src/test/java/place/icomb/archiver/genealogy/GenealogyFixtures.java` (create) | 2 | Test records, pages, stored scans and sign-in for genealogy tests |
| `src/test/java/place/icomb/archiver/genealogy/GenealogySchemaTest.java` (create) | 2 | V21's constraints |
| `src/main/java/place/icomb/archiver/genealogy/GedcomDate.java` (create) | 3 | GEDCOM 7 date grammar and bounds |
| `src/main/java/place/icomb/archiver/genealogy/legacy/LegacyFacts.java` (create) | 4 | Records for what one legacy line says |
| `src/main/java/place/icomb/archiver/genealogy/legacy/LegacyLineParser.java` (create) | 4 | One legacy line into `LegacyFacts` |
| `src/main/java/place/icomb/archiver/genealogy/legacy/LegacyTreeParser.java` (create) | 5 | The old parse, verbatim: ids, tree, section-root folding, marriage hints |
| `src/main/java/place/icomb/archiver/genealogy/legacy/LegacyImport.java` (create) | 5 | Writes the parsed file into the tables |
| `src/main/java/place/icomb/archiver/genealogy/legacy/V22__Import_legacy_genealogy.java` (create) | 5 | The Java migration bean |
| `src/main/resources/db/legacy/czernin-genealogy.txt` (moved from `src/main/resources/`) | 5 | V22's input |
| `src/main/resources/db/migration/V23__genealogy_person_references.sql` (create) | 5 | Remap and foreign keys for `page_person_match`, `app_user` |
| `src/main/java/place/icomb/archiver/service/FamilyTreeService.java` (modify in 5, rewrite in 6) | 6 | Read model over the tables: search, kinship, people, life events |
| `src/main/java/place/icomb/archiver/genealogy/Rows.java` (create) | 6 | Rows as JSON: camelCase, ISO dates, jsonb as maps |
| `src/main/java/place/icomb/archiver/genealogy/GenealogyReadService.java` (create) | 6 | A person's names, couples, links, records, media, portrait, sources |
| `src/main/java/place/icomb/archiver/controller/FamilyTreeController.java` (modify) | 6, 10 | Read API; reload endpoints gone; media image endpoint |
| `src/main/java/place/icomb/archiver/genealogy/GenEntity.java` (create) | 7 | Catalogue of writable entities and fields |
| `src/main/java/place/icomb/archiver/genealogy/GenealogyException.java` (create) | 7 | Typed refusal (400/404/409) |
| `src/main/java/place/icomb/archiver/genealogy/MediaImages.java` (create in 7, extend in 10) | 7, 10 | Stored scan size; whole-image bytes or lossless crop |
| `src/main/java/place/icomb/archiver/genealogy/GenealogyService.java` (create) | 7 | The only writer: create, update, delete, validation, audit |
| `src/main/java/place/icomb/archiver/genealogy/GenealogyHistoryService.java` (create) | 8 | Merge and undo |
| `src/main/java/place/icomb/archiver/controller/AdminGenealogyController.java` (create in 9, extend in 12) | 9, 12 | `/api/admin/genealogy/**` |
| `src/main/java/place/icomb/archiver/mcp/GenealogyMcpTools.java` (create) | 11 | MCP write tools |
| `src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java` (modify) | 11 | `get_person` gains the stored facts |
| `src/main/java/place/icomb/archiver/genealogy/GedcomExporter.java` (create) | 12 | GEDCOM 7 file |
| `frontend/src/lib/genealogy.ts` (create in 13, extend in 14) | 13, 14 | Types, labels, crop maths, form-to-JSON |
| `frontend/src/lib/components/genealogy/PersonFacts.svelte` (create) | 13 | Read view of the stored facts |
| `frontend/src/lib/components/genealogy/{PersonEditor,PersonPicker,RecordPagePicker,CropSelector,ChangeHistory}.svelte` (create) | 14 | Admin edit mode |
| `frontend/src/routes/family-tree/+page.svelte`, `+page.server.ts` (modify) | 13, 14 | Person page; edit actions |
| `frontend/src/routes/family-tree/review/+page.svelte`, `+page.server.ts` (create) | 14 | The `needs_review` list |
| `frontend/src/lib/server/api.ts` (modify) | 14 | Admin genealogy calls |
| `frontend/src/lib/messages/{en,de,cs}.ts` (modify) | 13, 14 | i18n |
| `CLAUDE.md`, `.claude/skills/archiver-api/SKILL.md` (modify) | 15 | Docs |

---

### Task 1: Golden files of the in-memory tree

**Files:**
- Create: `backend/src/test/java/place/icomb/archiver/service/FamilyTreeGolden.java`
- Create: `backend/src/test/java/place/icomb/archiver/service/FamilyTreeGoldenTest.java`
- Create (generated): `backend/src/test/resources/genealogy/golden/people.tsv`, `kinship.tsv`, `search.tsv`

**Interfaces:**
- Consumes: the current `FamilyTreeService` (`new FamilyTreeService()`, `reload()`, `getAllPeople()`, `relate(int)`, `relate(int, int)`, `search(String, int)`), unchanged.
- Produces (used by Tasks 5 and 6):
  - `public final class FamilyTreeGolden` in `place.icomb.archiver.service` (test sources):
    - `static List<String> people(FamilyTreeService)`, one tab-separated row per person, by id: `id, parentId|-, section, code, depth, birthYear, deathYear, birthPlace, name, fullEntry, childIds`.
    - `static List<String> kinship(FamilyTreeService)`: for every person, `relate(p)` (ref `default`) and `relate(p, r)` for `r` in `REFERENCE_PEOPLE = [1, 76, 128, 324]`.
    - `static List<String> search(FamilyTreeService)`: the top 10 for each of `QUERIES`.
    - `public static List<String> read(String name)` and `static void write(String name, List<String> lines)`.
    - `static void assertPeopleMatch(List<String> actual, List<String> golden)`: exact match, except that column 7 (birth place) is compared through `placeKey`, and ids 91 and 200 are exempt.
    - `static String placeKey(String)`.
    - `static List<Person> legacyPeople()`, which rebuilds the in-memory people from `people.tsv`.
  - Golden files: `people.tsv` (346 rows), `kinship.tsv` (1,730 rows), `search.tsv` (91 rows).

- [ ] **Step 1: Write the golden helper**

Create `backend/src/test/java/place/icomb/archiver/service/FamilyTreeGolden.java`:

```java
package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import place.icomb.archiver.service.FamilyTreeService.Person;
import place.icomb.archiver.service.FamilyTreeService.RelationshipResult;

/**
 * The family tree's observable behaviour, written out as text so it can be compared before and
 * after the tree moved from the text file into the database.
 *
 * <p>Captured once from the in-memory tree (2026-11-02) and checked into {@code
 * src/test/resources/genealogy/golden}. Rewrite them only with {@code GOLDEN_WRITE=true}, and only
 * from the tree they are meant to describe.
 */
public final class FamilyTreeGolden {

  static final Path DIR = Path.of("src/test/resources/genealogy/golden");

  static final List<Integer> REFERENCE_PEOPLE = List.of(1, 76, 128, 324);

  static final List<String> QUERIES =
      List.of(
          "alexander",
          "czernin",
          "rudolf",
          "jaromir",
          "theobald",
          "wolfgang maria",
          "eugen",
          "diana",
          "humprecht",
          "dymokury",
          "Děpolt",
          "lobkowicz");

  /**
   * People whose legacy birth place was an artefact of the old parser rather than a place: 91's
   * "1651/after 1660/1666" left "/after", and 200's "*and +Klagenfurt" left "and +". The database
   * gives 91 no place and 200 Klagenfurt.
   */
  static final Map<Integer, String> BIRTH_PLACE_EXCEPTIONS =
      Map.of(91, "legacy '/after' artefact", 200, "legacy 'and +' artefact");

  private FamilyTreeGolden() {}

  static List<String> people(FamilyTreeService tree) {
    List<String> out = new ArrayList<>();
    for (Person p : sorted(tree.getAllPeople())) {
      List<String> childIds = new ArrayList<>();
      for (Person c : p.children) childIds.add(String.valueOf(c.id));
      out.add(
          String.join(
              "\t",
              String.valueOf(p.id),
              p.parent == null ? "-" : String.valueOf(p.parent.id),
              String.valueOf(p.section),
              p.fullCode(),
              String.valueOf(p.depth),
              String.valueOf(p.birthYear),
              String.valueOf(p.deathYear),
              String.valueOf(p.birthPlace),
              p.name,
              p.fullEntry,
              String.join(",", childIds)));
    }
    return out;
  }

  static List<String> kinship(FamilyTreeService tree) {
    List<String> out = new ArrayList<>();
    for (Person p : sorted(tree.getAllPeople())) {
      out.add(row(p.id, "default", tree.relate(p.id)));
      for (int ref : REFERENCE_PEOPLE) {
        out.add(row(p.id, String.valueOf(ref), tree.relate(p.id, ref)));
      }
    }
    return out;
  }

  static List<String> search(FamilyTreeService tree) {
    List<String> out = new ArrayList<>();
    for (String q : QUERIES) {
      int rank = 1;
      for (var r : tree.search(q, 10)) {
        out.add(
            String.join(
                "\t",
                q,
                String.valueOf(rank++),
                String.valueOf(r.personId()),
                r.name(),
                String.format(Locale.ROOT, "%.6f", r.score()),
                String.valueOf(r.birthYear()),
                String.valueOf(r.deathYear()),
                r.section(),
                r.code()));
      }
    }
    return out;
  }

  public static List<String> read(String name) throws IOException {
    try (InputStream in = FamilyTreeGolden.class.getResourceAsStream("/genealogy/golden/" + name)) {
      assertThat(in).as("golden file " + name).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
  }

  static void write(String name, List<String> lines) throws IOException {
    Files.createDirectories(DIR);
    Files.writeString(DIR.resolve(name), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
  }

  /**
   * Every column must match exactly except the birth place, which must match as {@code
   * PersonMatchService} reads it: the text before the first comma or plus, normalised, and only
   * when four or more characters long.
   */
  static void assertPeopleMatch(List<String> actual, List<String> golden) {
    assertThat(actual).hasSameSizeAs(golden);
    for (int i = 0; i < golden.size(); i++) {
      String[] want = golden.get(i).split("\t", -1);
      String[] got = actual.get(i).split("\t", -1);
      int id = Integer.parseInt(want[0]);
      for (int col = 0; col < want.length; col++) {
        if (col == 7) {
          if (!BIRTH_PLACE_EXCEPTIONS.containsKey(id)) {
            assertThat(placeKey(got[7])).as("birth place of " + id).isEqualTo(placeKey(want[7]));
          }
        } else {
          assertThat(got[col]).as("column " + col + " of person " + id).isEqualTo(want[col]);
        }
      }
    }
  }

  /**
   * The in-memory tree's people exactly as it held them, birth places included, rebuilt from {@code
   * people.tsv}: the input person matching was tuned on.
   */
  static List<Person> legacyPeople() throws IOException {
    Map<Integer, Person> byId = new java.util.LinkedHashMap<>();
    Map<Integer, Integer> parentOf = new java.util.HashMap<>();
    for (String line : read("people.tsv")) {
      String[] c = line.split("\t", -1);
      Person p = new Person();
      p.id = Integer.parseInt(c[0]);
      p.section = c[2];
      p.code = c[3].isEmpty() ? null : c[3];
      p.depth = Integer.parseInt(c[4]);
      p.birthYear = c[5].equals("null") ? null : Integer.valueOf(c[5]);
      p.deathYear = c[6].equals("null") ? null : Integer.valueOf(c[6]);
      p.birthPlace = c[7].equals("null") ? null : c[7];
      p.name = c[8];
      p.fullEntry = c[9];
      p.children = new ArrayList<>();
      p.spouses = new ArrayList<>();
      byId.put(p.id, p);
      if (!c[1].equals("-")) parentOf.put(p.id, Integer.parseInt(c[1]));
    }
    for (Person p : byId.values()) {
      Integer parent = parentOf.get(p.id);
      if (parent != null) {
        p.parent = byId.get(parent);
        p.parent.children.add(p);
      }
    }
    return List.copyOf(byId.values());
  }

  static String placeKey(String place) {
    if (place == null || place.equals("null") || place.isBlank()) return null;
    String first = place.split("[,+]")[0].trim();
    String norm =
        Normalizer.normalize(first, Normalizer.Form.NFD).replaceAll("\\p{Mn}", "").toLowerCase();
    return norm.length() >= 4 ? norm : null;
  }

  private static String row(int personId, String ref, RelationshipResult r) {
    if (r == null) return personId + "\t" + ref + "\tnull";
    return String.join(
        "\t",
        String.valueOf(personId),
        ref,
        r.kinshipLabel(),
        r.pathDescription(),
        String.valueOf(r.commonAncestorName()),
        String.valueOf(r.commonAncestorId()),
        String.valueOf(r.stepsFromPerson()),
        String.valueOf(r.stepsFromRef()),
        r.personName(),
        r.refPersonName());
  }

  private static List<Person> sorted(List<Person> people) {
    return people.stream().sorted(Comparator.comparingInt(p -> p.id)).toList();
  }
}
```

- [ ] **Step 2: Write the test that pins the tree to the golden files**

Create `backend/src/test/java/place/icomb/archiver/service/FamilyTreeGoldenTest.java`:

```java
package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The in-memory tree's behaviour, pinned before the tree moves into the database. Task 6 replaces
 * this with {@code FamilyTreeReadModelTest}, which checks the same golden files against the
 * database-backed tree.
 */
class FamilyTreeGoldenTest {

  static FamilyTreeService tree;

  @BeforeAll
  static void load() {
    tree = new FamilyTreeService();
    tree.reload();
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOLDEN_WRITE", matches = "true")
  void writeGoldenFiles() throws Exception {
    FamilyTreeGolden.write("people.tsv", FamilyTreeGolden.people(tree));
    FamilyTreeGolden.write("kinship.tsv", FamilyTreeGolden.kinship(tree));
    FamilyTreeGolden.write("search.tsv", FamilyTreeGolden.search(tree));
  }

  @Test
  void theTreeHas346People() {
    assertThat(tree.getAllPeople()).hasSize(346);
  }

  @Test
  @DisabledIfEnvironmentVariable(named = "GOLDEN_WRITE", matches = "true")
  void peopleMatchTheGoldenFile() throws Exception {
    FamilyTreeGolden.assertPeopleMatch(
        FamilyTreeGolden.people(tree), FamilyTreeGolden.read("people.tsv"));
  }

  @Test
  @DisabledIfEnvironmentVariable(named = "GOLDEN_WRITE", matches = "true")
  void kinshipMatchesTheGoldenFile() throws Exception {
    assertThat(FamilyTreeGolden.kinship(tree)).isEqualTo(FamilyTreeGolden.read("kinship.tsv"));
  }

  @Test
  @DisabledIfEnvironmentVariable(named = "GOLDEN_WRITE", matches = "true")
  void searchMatchesTheGoldenFile() throws Exception {
    assertThat(FamilyTreeGolden.search(tree)).isEqualTo(FamilyTreeGolden.read("search.tsv"));
  }
}
```

- [ ] **Step 3: Run it to see it fail**

Run: `cd backend && ./gradlew test --tests '*FamilyTreeGoldenTest'`

Expected: FAIL: `peopleMatchTheGoldenFile`, `kinshipMatchesTheGoldenFile` and `searchMatchesTheGoldenFile` fail with `[golden file people.tsv] Expecting actual not to be null` (and the same for kinship and search). `theTreeHas346People` passes.

- [ ] **Step 4: Capture the golden files from the in-memory tree**

This is the only time the files are written. They describe the tree as it is today, before any code changes.

Run: `cd backend && GOLDEN_WRITE=true ./gradlew test --tests '*FamilyTreeGoldenTest' --rerun`

Expected: BUILD SUCCESSFUL; the three files now exist

Run: `cd backend && wc -l src/test/resources/genealogy/golden/*.tsv && grep -P '^322\t324\t' src/test/resources/genealogy/golden/kinship.tsv | cut -f3`

Expected: `346 people.tsv`, `1730 kinship.tsv`, `91 search.tsv`, then `grandparent` (Alexander is the grandparent of Alexander Hannibal Czernin Fishlock)

- [ ] **Step 5: Run the test to see it pass**

Run: `cd backend && ./gradlew test --tests '*FamilyTreeGoldenTest'`

Expected: PASS (5 tests; `writeGoldenFiles` skipped)

- [ ] **Step 6: Format**

Run: `cd backend && ./gradlew spotlessApply`

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A backend/src/test/java/place/icomb/archiver/service/FamilyTreeGolden.java backend/src/test/java/place/icomb/archiver/service/FamilyTreeGoldenTest.java backend/src/test/resources/genealogy/golden
git commit -m "test: pin the in-memory family tree as golden files before the move to the database"
```

---

### Task 2: V21 schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V21__genealogy.sql`
- Create: `backend/src/test/java/place/icomb/archiver/genealogy/GenealogyFixtures.java`
- Test: `backend/src/test/java/place/icomb/archiver/genealogy/GenealogySchemaTest.java`

**Interfaces:**
- Consumes: `record`, `page`, `attachment`, `archive` from `V1__baseline.sql`; `StorageService.storePageImage(byte[])`.
- Produces:
  - Tables (V21): `gen_person`, `gen_name`, `gen_couple`, `gen_parent_child`, `gen_place`, `gen_place_name`, `gen_event`, `gen_source`, `gen_citation`, `gen_person_record`, `gen_media`, `gen_media_person`, `gen_change`, with the columns, checks and foreign keys in the SQL below. `gen_person.id` is `int`, defaulting to `nextval('gen_person_id_seq')`, which starts at 349. Every other id is `bigserial`. `gen_person.uid` defaults to `gen_random_uuid()`, and `sex` defaults to `'U'`.
  - `public final class GenealogyFixtures` (test sources, `place.icomb.archiver.genealogy`):
    - `static void signInAsAdmin()` (as `genealogy-admin@example.com`, ROLE_USER and ROLE_ADMIN) and `static void signInAsUser()`.
    - `static long record(JdbcTemplate)`.
    - `static long page(JdbcTemplate, StorageService, long recordId, int seq, byte[] bytes, String mime)`.
    - `static BufferedImage pattern(int width, int height)`, `static byte[] encode(BufferedImage, String format)`, `static byte[] png(int, int)`, `static byte[] jpeg(int, int)`.

- [ ] **Step 1: Write the fixtures and the failing constraint test**

Create `backend/src/test/java/place/icomb/archiver/genealogy/GenealogyFixtures.java`:

```java
package place.icomb.archiver.genealogy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import place.icomb.archiver.service.StorageService;

/** Records, pages and scans for genealogy tests, written straight to the test database. */
public final class GenealogyFixtures {

  private GenealogyFixtures() {}

  public static void signInAsAdmin() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "genealogy-admin@example.com",
                "n/a",
                List.of(
                    new SimpleGrantedAuthority("ROLE_USER"),
                    new SimpleGrantedAuthority("ROLE_ADMIN"))));
  }

  public static void signInAsUser() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "reader@example.com", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
  }

  public static long record(JdbcTemplate jdbc) {
    Long archive =
        jdbc.queryForObject(
            "INSERT INTO archive (name) VALUES (?) RETURNING id",
            Long.class,
            "genealogy test " + System.nanoTime());
    return jdbc.queryForObject(
        """
        INSERT INTO record (archive_id, source_system, source_record_id, title, status, lang,
                            metadata_lang)
        VALUES (?, 'test', ?, 'a letter', 'complete', 'de', 'de') RETURNING id
        """,
        Long.class,
        archive,
        "gen-" + System.nanoTime());
  }

  /** A page whose stored scan is {@code bytes}, labelled {@code mime}. */
  public static long page(
      JdbcTemplate jdbc,
      StorageService storage,
      long recordId,
      int seq,
      byte[] bytes,
      String mime) {
    String path = storage.storePageImage(bytes);
    Long attachment =
        jdbc.queryForObject(
            "INSERT INTO attachment (record_id, role, path, mime, bytes) VALUES (?, 'page_image', ?,"
                + " ?, ?) RETURNING id",
            Long.class,
            recordId,
            path,
            mime,
            (long) bytes.length);
    return jdbc.queryForObject(
        "INSERT INTO page (record_id, seq, attachment_id) VALUES (?, ?, ?) RETURNING id",
        Long.class,
        recordId,
        seq,
        attachment);
  }

  /** Every pixel different: (x, y) is coloured from its own coordinates. */
  public static BufferedImage pattern(int width, int height) {
    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        img.setRGB(x, y, ((x * 7) & 0xff) << 16 | ((y * 13) & 0xff) << 8 | ((x + y) & 0xff));
      }
    }
    return img;
  }

  public static byte[] encode(BufferedImage img, String format) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, format, out);
    return out.toByteArray();
  }

  public static byte[] png(int width, int height) throws Exception {
    return encode(pattern(width, height), "png");
  }

  public static byte[] jpeg(int width, int height) throws Exception {
    return encode(pattern(width, height), "jpg");
  }
}
```

Create `backend/src/test/java/place/icomb/archiver/genealogy/GenealogySchemaTest.java`:

```java
package place.icomb.archiver.genealogy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** What migration V21 holds the genealogy to, whatever writes it. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class GenealogySchemaTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private JdbcTemplate jdbc;

  private int a;
  private int b;

  @BeforeEach
  void twoPeople() {
    a = jdbc.queryForObject("INSERT INTO gen_person DEFAULT VALUES RETURNING id", Integer.class);
    b = jdbc.queryForObject("INSERT INTO gen_person DEFAULT VALUES RETURNING id", Integer.class);
  }

  private void refused(String sql, Object... args) {
    assertThatThrownBy(() -> jdbc.update(sql, args))
        .as(sql)
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void newPeopleAreNumberedAboveTheLegacyIds() {
    assertThat(a).isGreaterThan(348);
    assertThat(jdbc.queryForObject("SELECT sex FROM gen_person WHERE id = ?", String.class, a))
        .isEqualTo("U");
    assertThat(jdbc.queryForObject("SELECT uid FROM gen_person WHERE id = ?", String.class, a))
        .hasSize(36);
  }

  @Test
  void personRules() {
    refused("UPDATE gen_person SET sex = 'X' WHERE id = ?", a);
    refused("UPDATE gen_person SET merged_into = id WHERE id = ?", a);
  }

  @Test
  void aPersonHasAtMostOnePrimaryName() {
    jdbc.update("INSERT INTO gen_name (person_id, given, is_primary) VALUES (?, 'A', true)", a);
    refused("INSERT INTO gen_name (person_id, given, is_primary) VALUES (?, 'B', true)", a);
    refused("INSERT INTO gen_name (person_id, is_primary) VALUES (?, false)", a);
    refused("INSERT INTO gen_name (person_id, given, type) VALUES (?, 'C', 'pet')", a);
  }

  @Test
  void couplesAndParentLinks() {
    refused("INSERT INTO gen_couple (person1_id, person2_id) VALUES (?, ?)", a, a);
    refused("INSERT INTO gen_couple (person1_id, person2_id, type) VALUES (?, ?, 'x')", a, b);
    refused("INSERT INTO gen_parent_child (parent_id, child_id) VALUES (?, ?)", a, a);
    jdbc.update("INSERT INTO gen_parent_child (parent_id, child_id) VALUES (?, ?)", a, b);
    refused("INSERT INTO gen_parent_child (parent_id, child_id, type) VALUES (?, ?, 'step')", a, b);
    refused("INSERT INTO gen_parent_child (parent_id, child_id, type) VALUES (?, ?, 'x')", b, a);
  }

  @Test
  void anEventBelongsToExactlyOnePersonOrCouple() {
    long couple =
        jdbc.queryForObject(
            "INSERT INTO gen_couple (person1_id, person2_id) VALUES (?, ?) RETURNING id",
            Long.class,
            a,
            b);
    refused("INSERT INTO gen_event (type) VALUES ('BIRT')");
    refused("INSERT INTO gen_event (person_id, couple_id, type) VALUES (?, ?, 'MARR')", a, couple);
    refused("INSERT INTO gen_event (person_id, type) VALUES (?, 'BIRTH')", a);
    jdbc.update("INSERT INTO gen_event (couple_id, type) VALUES (?, 'MARR')", couple);
  }

  @Test
  void aCitationHasExactlyOneTargetAndAQuay() {
    long source =
        jdbc.queryForObject("INSERT INTO gen_source (title) VALUES ('s') RETURNING id", Long.class);
    refused("INSERT INTO gen_citation (source_id) VALUES (?)", source);
    refused(
        "INSERT INTO gen_citation (source_id, person_id, couple_id) VALUES (?, ?, 1)", source, a);
    refused("INSERT INTO gen_citation (source_id, person_id, quality) VALUES (?, ?, 4)", source, a);
    refused("INSERT INTO gen_source (title) VALUES (NULL)");
  }

  @Test
  void aCropIsAllOrNothing() {
    long record = GenealogyFixtures.record(jdbc);
    long attachment =
        jdbc.queryForObject(
            "INSERT INTO attachment (record_id, role, path, mime) VALUES (?, 'page_image', 'p.jpg',"
                + " 'image/jpeg') RETURNING id",
            Long.class,
            record);
    long page =
        jdbc.queryForObject(
            "INSERT INTO page (record_id, seq, attachment_id) VALUES (?, 1, ?) RETURNING id",
            Long.class,
            record,
            attachment);
    refused("INSERT INTO gen_media (page_id, crop_left) VALUES (?, 1)", page);
    refused(
        "INSERT INTO gen_media (page_id, crop_left, crop_top, crop_width, crop_height)"
            + " VALUES (?, 0, 0, 0, 5)",
        page);
    long media =
        jdbc.queryForObject(
            "INSERT INTO gen_media (page_id) VALUES (?) RETURNING id", Long.class, page);
    jdbc.update(
        "INSERT INTO gen_media_person (media_id, person_id, is_portrait) VALUES (?, ?, true)",
        media,
        a);
    long other =
        jdbc.queryForObject(
            "INSERT INTO gen_media (page_id) VALUES (?) RETURNING id", Long.class, page);
    refused(
        "INSERT INTO gen_media_person (media_id, person_id, is_portrait) VALUES (?, ?, true)",
        other,
        a);
  }

  @Test
  void deletingAPageOrRecordStaysPossible() {
    long record = GenealogyFixtures.record(jdbc);
    long attachment =
        jdbc.queryForObject(
            "INSERT INTO attachment (record_id, role, path, mime) VALUES (?, 'page_image', 'q.jpg',"
                + " 'image/jpeg') RETURNING id",
            Long.class,
            record);
    long page =
        jdbc.queryForObject(
            "INSERT INTO page (record_id, seq, attachment_id) VALUES (?, 1, ?) RETURNING id",
            Long.class,
            record,
            attachment);
    long source =
        jdbc.queryForObject(
            "INSERT INTO gen_source (title, record_id) VALUES ('s', ?) RETURNING id",
            Long.class,
            record);
    long citation =
        jdbc.queryForObject(
            "INSERT INTO gen_citation (source_id, page_id, person_id) VALUES (?, ?, ?) RETURNING id",
            Long.class,
            source,
            page,
            a);
    long media =
        jdbc.queryForObject(
            "INSERT INTO gen_media (page_id) VALUES (?) RETURNING id", Long.class, page);
    jdbc.update("INSERT INTO gen_media_person (media_id, person_id) VALUES (?, ?)", media, a);
    jdbc.update(
        "INSERT INTO gen_person_record (person_id, record_id, page_id) VALUES (?, ?, ?)",
        a,
        record,
        page);

    jdbc.update("DELETE FROM page WHERE id = ?", page);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_media WHERE id = ?", Integer.class, media))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT page_id FROM gen_citation WHERE id = ?", Long.class, citation))
        .isNull();

    jdbc.update("DELETE FROM attachment WHERE record_id = ?", record);
    jdbc.update("DELETE FROM record WHERE id = ?", record);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_person_record WHERE person_id = ?", Integer.class, a))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT record_id FROM gen_source WHERE id = ?", Long.class, source))
        .isNull();
  }

  @Test
  void anAuditRowNeedsAReason() {
    refused(
        "INSERT INTO gen_change (entity, entity_id, op, reason, changed_by)"
            + " VALUES ('person', 1, 'update', '  ', 'x')");
    refused(
        "INSERT INTO gen_change (entity, entity_id, op, reason, changed_by)"
            + " VALUES ('person', 1, 'rename', 'r', 'x')");
  }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd backend && ./gradlew test --tests '*GenealogySchemaTest'`

Expected: FAIL: every test errors with `BadSqlGrammarException ... relation "gen_person" does not exist`

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V21__genealogy.sql`:

```sql
-- The family tree, editable and evidenced. Modelled on the GEDCOM X conceptual model and the
-- Gramps schema, with GEDCOM 7 vocabulary for event tags, dates and QUAY. Ids 1-348 are the
-- parse-order ids the in-memory tree gave every person, so links made before this migration
-- still name the same people; new people are numbered from 349.
CREATE SEQUENCE gen_person_id_seq START WITH 349;

CREATE TABLE gen_person (
    id             int PRIMARY KEY DEFAULT nextval('gen_person_id_seq'),
    uid            uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    sex            char(1) NOT NULL DEFAULT 'U' CHECK (sex IN ('M', 'F', 'U')),
    living         boolean NOT NULL DEFAULT false,
    notes          text,
    legacy_entry   text,
    legacy_section text,
    legacy_code    text,
    legacy_depth   int,
    needs_review   text,
    merged_into    int REFERENCES gen_person (id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CHECK (merged_into IS NULL OR merged_into <> id)
);
ALTER SEQUENCE gen_person_id_seq OWNED BY gen_person.id;

CREATE TABLE gen_name (
    id          bigserial PRIMARY KEY,
    person_id   int NOT NULL REFERENCES gen_person (id),
    type        text NOT NULL DEFAULT 'birth'
        CHECK (type IN ('birth', 'married', 'alias', 'religious', 'title', 'other')),
    given       text,
    surname     text,
    prefix      text,
    suffix      text,
    nickname    text,
    is_primary  boolean NOT NULL DEFAULT false,
    sort_order  int NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (given IS NOT NULL OR surname IS NOT NULL)
);
CREATE UNIQUE INDEX gen_name_one_primary ON gen_name (person_id) WHERE is_primary;
CREATE INDEX idx_gen_name_person ON gen_name (person_id);

CREATE TABLE gen_couple (
    id          bigserial PRIMARY KEY,
    person1_id  int NOT NULL REFERENCES gen_person (id),
    person2_id  int NOT NULL REFERENCES gen_person (id),
    type        text NOT NULL DEFAULT 'marriage'
        CHECK (type IN ('marriage', 'partnership', 'unknown')),
    sort_order  int NOT NULL DEFAULT 1,
    notes       text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (person1_id <> person2_id)
);
CREATE INDEX idx_gen_couple_person1 ON gen_couple (person1_id);
CREATE INDEX idx_gen_couple_person2 ON gen_couple (person2_id);

CREATE TABLE gen_parent_child (
    id          bigserial PRIMARY KEY,
    parent_id   int NOT NULL REFERENCES gen_person (id),
    child_id    int NOT NULL REFERENCES gen_person (id),
    type        text NOT NULL DEFAULT 'birth'
        CHECK (type IN ('birth', 'adopted', 'step', 'foster', 'unknown')),
    couple_id   bigint REFERENCES gen_couple (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (parent_id, child_id),
    CHECK (parent_id <> child_id)
);
CREATE INDEX idx_gen_parent_child_child ON gen_parent_child (child_id);

CREATE TABLE gen_place (
    id          bigserial PRIMARY KEY,
    name        text NOT NULL,
    parent_id   bigint REFERENCES gen_place (id),
    lat         numeric(9, 6),
    lon         numeric(9, 6),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE TABLE gen_place_name (
    id          bigserial PRIMARY KEY,
    place_id    bigint NOT NULL REFERENCES gen_place (id),
    name        text NOT NULL,
    lang        char(2) CHECK (lang ~ '^[a-z]{2}$'),
    from_year   int,
    to_year     int,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (from_year IS NULL OR to_year IS NULL OR from_year <= to_year)
);
CREATE INDEX idx_gen_place_name_place ON gen_place_name (place_id);

CREATE TABLE gen_event (
    id           bigserial PRIMARY KEY,
    person_id    int REFERENCES gen_person (id),
    couple_id    bigint REFERENCES gen_couple (id),
    type         text NOT NULL
        CHECK (type IN ('BIRT', 'CHR', 'BAPM', 'DEAT', 'BURI', 'MARR', 'DIV', 'ADOP', 'OCCU',
                        'TITL', 'RESI', 'NATU', 'EMIG', 'IMMI', 'EDUC', 'EVEN')),
    date_text    text,
    date_from    date,
    date_to      date,
    place_id     bigint REFERENCES gen_place (id),
    description  text,
    sort_order   int NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CHECK (num_nonnulls(person_id, couple_id) = 1)
);
CREATE INDEX idx_gen_event_person ON gen_event (person_id);
CREATE INDEX idx_gen_event_couple ON gen_event (couple_id);

CREATE TABLE gen_source (
    id          bigserial PRIMARY KEY,
    title       text NOT NULL,
    author      text,
    record_id   bigint REFERENCES record (id) ON DELETE SET NULL,
    url         text,
    notes       text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE gen_citation (
    id               bigserial PRIMARY KEY,
    source_id        bigint NOT NULL REFERENCES gen_source (id),
    page_id          bigint REFERENCES page (id) ON DELETE SET NULL,
    detail           text,
    quality          smallint CHECK (quality BETWEEN 0 AND 3),
    event_id         bigint REFERENCES gen_event (id),
    name_id          bigint REFERENCES gen_name (id),
    couple_id        bigint REFERENCES gen_couple (id),
    parent_child_id  bigint REFERENCES gen_parent_child (id),
    person_id        int REFERENCES gen_person (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CHECK (num_nonnulls(event_id, name_id, couple_id, parent_child_id, person_id) = 1)
);
CREATE INDEX idx_gen_citation_source ON gen_citation (source_id);
CREATE INDEX idx_gen_citation_event ON gen_citation (event_id);
CREATE INDEX idx_gen_citation_name ON gen_citation (name_id);
CREATE INDEX idx_gen_citation_person ON gen_citation (person_id);

CREATE TABLE gen_person_record (
    id          bigserial PRIMARY KEY,
    person_id   int NOT NULL REFERENCES gen_person (id),
    record_id   bigint NOT NULL REFERENCES record (id) ON DELETE CASCADE,
    page_id     bigint REFERENCES page (id) ON DELETE SET NULL,
    role        text NOT NULL DEFAULT 'mentioned'
        CHECK (role IN ('subject', 'mentioned', 'author', 'signatory', 'witness', 'recipient',
                        'pictured', 'other')),
    note        text,
    quality     smallint CHECK (quality BETWEEN 0 AND 3),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_gen_person_record_person ON gen_person_record (person_id);
CREATE INDEX idx_gen_person_record_record ON gen_person_record (record_id);

CREATE TABLE gen_media (
    id           bigserial PRIMARY KEY,
    page_id      bigint NOT NULL REFERENCES page (id) ON DELETE CASCADE,
    crop_left    int CHECK (crop_left >= 0),
    crop_top     int CHECK (crop_top >= 0),
    crop_width   int CHECK (crop_width > 0),
    crop_height  int CHECK (crop_height > 0),
    caption      text,
    date_text    text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CHECK (num_nulls(crop_left, crop_top, crop_width, crop_height) IN (0, 4))
);
CREATE INDEX idx_gen_media_page ON gen_media (page_id);

CREATE TABLE gen_media_person (
    id           bigserial PRIMARY KEY,
    media_id     bigint NOT NULL REFERENCES gen_media (id) ON DELETE CASCADE,
    person_id    int NOT NULL REFERENCES gen_person (id),
    is_portrait  boolean NOT NULL DEFAULT false,
    sort_order   int NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (media_id, person_id)
);
CREATE UNIQUE INDEX gen_media_person_one_portrait ON gen_media_person (person_id) WHERE is_portrait;

-- One row per entity a write changed. before is null for a create, after for a delete.
CREATE TABLE gen_change (
    id          bigserial PRIMARY KEY,
    entity      text NOT NULL,
    entity_id   bigint NOT NULL,
    op          text NOT NULL CHECK (op IN ('create', 'update', 'delete', 'merge', 'undo')),
    before      jsonb,
    after       jsonb,
    reason      text NOT NULL CHECK (btrim(reason) <> ''),
    changed_by  text NOT NULL,
    changed_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_gen_change_entity ON gen_change (entity, entity_id);
```

- [ ] **Step 4: Run it to see it pass**

Run: `cd backend && ./gradlew test --tests '*GenealogySchemaTest'`

Expected: PASS (9 tests)

- [ ] **Step 5: Format**

Run: `cd backend && ./gradlew spotlessApply`

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A backend/src/main/resources/db/migration/V21__genealogy.sql backend/src/test/java/place/icomb/archiver/genealogy
git commit -m "feat: V21 genealogy tables"
```

---

### Task 3: GEDCOM 7 dates

**Files:**
- Create: `backend/src/main/java/place/icomb/archiver/genealogy/GedcomDate.java`
- Test: `backend/src/test/java/place/icomb/archiver/genealogy/GedcomDateTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `public record GedcomDate(String text, LocalDate from, LocalDate to)`, where `text` is the value with single spaces.
  - `static GedcomDate parse(String value)`: null for null or blank; throws `GedcomDate.InvalidDateException` (a `RuntimeException`) otherwise when the value is invalid.
  - `Integer year()`: the year of `from`, else of `to`.
  - Accepted: `[GREGORIAN|JULIAN] [[day] MON] year`, `ABT`/`CAL`/`EST date`, `BET date AND date`, `AFT date`, `BEF date`, `FROM date [TO date]`, `TO date`. Refused: lower-case keywords, other calendars, `BCE`, impossible days, and ranges that end before they start.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/place/icomb/archiver/genealogy/GedcomDateTest.java`:

```java
package place.icomb.archiver.genealogy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** GEDCOM 7.0 date values (FamilySearch GEDCOM 7.0, "Date") and the bounds stored for sorting. */
class GedcomDateTest {

  private static void bounds(String value, String from, String to) {
    GedcomDate d = GedcomDate.parse(value);
    assertThat(d.from()).as(value + " from").isEqualTo(from == null ? null : LocalDate.parse(from));
    assertThat(d.to()).as(value + " to").isEqualTo(to == null ? null : LocalDate.parse(to));
  }

  @Test
  void exactAndPartialDates() {
    bounds("30 APR 1913", "1913-04-30", "1913-04-30");
    bounds("APR 1570", "1570-04-01", "1570-04-30");
    bounds("FEB 1900", "1900-02-01", "1900-02-28");
    bounds("1447", "1447-01-01", "1447-12-31");
    bounds("JULIAN 5 MAR 1580", "1580-03-05", "1580-03-05");
    bounds("GREGORIAN 1 JAN 1600", "1600-01-01", "1600-01-01");
  }

  @Test
  void rangesPeriodsAndApproximations() {
    bounds("BET 1657 AND 1664", "1657-01-01", "1664-12-31");
    bounds("AFT 1501", "1501-01-01", null);
    bounds("BEF 1629", null, "1629-12-31");
    bounds("ABT 1599", "1599-01-01", "1599-12-31");
    bounds("CAL 1700", "1700-01-01", "1700-12-31");
    bounds("EST 1700", "1700-01-01", "1700-12-31");
    bounds("FROM 1938 TO 1945", "1938-01-01", "1945-12-31");
    bounds("FROM 12 MAY 1938", "1938-05-12", null);
    bounds("TO 1945", null, "1945-12-31");
  }

  @Test
  void theYearIsTheStartOfTheRangeOrElseItsEnd() {
    assertThat(GedcomDate.parse("BET 1657 AND 1664").year()).isEqualTo(1657);
    assertThat(GedcomDate.parse("BEF 1629").year()).isEqualTo(1629);
  }

  @Test
  void anEmptyValueIsAnUnknownDate() {
    assertThat(GedcomDate.parse(null)).isNull();
    assertThat(GedcomDate.parse("  ")).isNull();
  }

  @Test
  void spacingIsNormalised() {
    assertThat(GedcomDate.parse("  ABT   1890 ").text()).isEqualTo("ABT 1890");
  }

  @Test
  void anythingElseIsRefused() {
    for (String bad :
        new String[] {
          "30.4.1913", "30 April 1913", "31 FEB 1900", "0 JAN 1900", "1 JAN 0", "abt 1890",
          "BET 1900", "BET 1900 AND", "AFT", "BET 1910 AND 1900", "1 JAN 100 BCE",
              "FRENCH_R 1 VEND 1",
          "young", "1913?", "1657/64", "ABT"
        }) {
      assertThatThrownBy(() -> GedcomDate.parse(bad))
          .as(bad)
          .isInstanceOf(GedcomDate.InvalidDateException.class);
    }
  }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd backend && ./gradlew test --tests '*GedcomDateTest'`

Expected: FAIL: compilation error `cannot find symbol: class GedcomDate`

- [ ] **Step 3: Write the parser**

Create `backend/src/main/java/place/icomb/archiver/genealogy/GedcomDate.java`:

```java
package place.icomb.archiver.genealogy;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;

/**
 * A GEDCOM 7 date value (FamilySearch GEDCOM 7.0, section "Date"), with the calendar bounds it
 * covers.
 *
 * <p>The bounds are derived for sorting and searching only; {@link #text()} is the fact. Gregorian
 * and Julian calendars are accepted, with their months JAN to DEC; other calendars and BCE are
 * refused.
 */
public record GedcomDate(String text, LocalDate from, LocalDate to) {

  public static final class InvalidDateException extends RuntimeException {
    public InvalidDateException(String message) {
      super(message);
    }
  }

  private static final List<String> MONTHS =
      List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");

  private record Bounds(LocalDate from, LocalDate to) {}

  /** Null for a null or blank value, which GEDCOM 7 allows as an unknown date. */
  public static GedcomDate parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    List<String> t = Arrays.asList(value.trim().split("\\s+"));
    String text = String.join(" ", t);
    String head = t.get(0);
    switch (head) {
      case "FROM" -> {
        int to = t.indexOf("TO");
        if (to < 0) {
          return new GedcomDate(text, date(t.subList(1, t.size()), value).from(), null);
        }
        Bounds a = date(t.subList(1, to), value);
        Bounds b = date(t.subList(to + 1, t.size()), value);
        return ordered(text, a.from(), b.to(), value);
      }
      case "TO" -> {
        return new GedcomDate(text, null, date(t.subList(1, t.size()), value).to());
      }
      case "BET" -> {
        int and = t.indexOf("AND");
        if (and < 0) {
          throw new InvalidDateException("BET needs AND: " + value);
        }
        Bounds a = date(t.subList(1, and), value);
        Bounds b = date(t.subList(and + 1, t.size()), value);
        return ordered(text, a.from(), b.to(), value);
      }
      case "AFT" -> {
        return new GedcomDate(text, date(t.subList(1, t.size()), value).from(), null);
      }
      case "BEF" -> {
        return new GedcomDate(text, null, date(t.subList(1, t.size()), value).to());
      }
      case "ABT", "CAL", "EST" -> {
        Bounds b = date(t.subList(1, t.size()), value);
        return new GedcomDate(text, b.from(), b.to());
      }
      default -> {
        Bounds b = date(t, value);
        return new GedcomDate(text, b.from(), b.to());
      }
    }
  }

  /** The year this date is sorted under: the start of its range, else its end. */
  public Integer year() {
    if (from != null) {
      return from.getYear();
    }
    return to != null ? to.getYear() : null;
  }

  private static GedcomDate ordered(String text, LocalDate from, LocalDate to, String value) {
    if (from.isAfter(to)) {
      throw new InvalidDateException("the range ends before it starts: " + value);
    }
    return new GedcomDate(text, from, to);
  }

  private static Bounds date(List<String> tokens, String value) {
    List<String> t = tokens;
    if (!t.isEmpty() && (t.get(0).equals("GREGORIAN") || t.get(0).equals("JULIAN"))) {
      t = t.subList(1, t.size());
    }
    if (!t.isEmpty() && t.get(t.size() - 1).equals("BCE")) {
      throw new InvalidDateException("BCE dates are not supported: " + value);
    }
    if (t.isEmpty() || t.size() > 3) {
      throw new InvalidDateException("not a GEDCOM 7 date: " + value);
    }
    int year = number(t.get(t.size() - 1), value);
    if (year < 1 || year > 9999) {
      throw new InvalidDateException("year out of range: " + value);
    }
    if (t.size() == 1) {
      return new Bounds(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }
    int month = MONTHS.indexOf(t.get(t.size() - 2)) + 1;
    if (month == 0) {
      throw new InvalidDateException("unknown month in: " + value);
    }
    YearMonth ym = YearMonth.of(year, month);
    if (t.size() == 2) {
      return new Bounds(ym.atDay(1), ym.atEndOfMonth());
    }
    int day = number(t.get(0), value);
    try {
      LocalDate d = LocalDate.of(year, month, day);
      return new Bounds(d, d);
    } catch (DateTimeException e) {
      throw new InvalidDateException("no such day: " + value);
    }
  }

  private static int number(String token, String value) {
    if (!token.matches("\\d{1,4}")) {
      throw new InvalidDateException("not a GEDCOM 7 date: " + value);
    }
    return Integer.parseInt(token);
  }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `cd backend && ./gradlew test --tests '*GedcomDateTest'`

Expected: PASS (6 tests)

- [ ] **Step 5: Format**

Run: `cd backend && ./gradlew spotlessApply`

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A backend/src/main/java/place/icomb/archiver/genealogy/GedcomDate.java backend/src/test/java/place/icomb/archiver/genealogy/GedcomDateTest.java
git commit -m "feat: GEDCOM 7 date grammar and bounds"
```

---

### Task 4: Legacy line parser

**Files:**
- Create: `backend/src/main/java/place/icomb/archiver/genealogy/legacy/LegacyFacts.java`
- Create: `backend/src/main/java/place/icomb/archiver/genealogy/legacy/LegacyLineParser.java`
- Test: `backend/src/test/java/place/icomb/archiver/genealogy/LegacyLineParserTest.java`

**Interfaces:**
- Consumes: `GedcomDate.parse(String)` (Task 3).
- Produces (used by Task 5):
  - `public record LegacyFacts(String nickname, Event birth, Event death, Event burial, List<Descriptor> descriptors, List<Adoption> adoptions, List<String> otherSurnames, List<Marriage> marriages, Integer allChildrenBy, List<String> notes, List<String> review)`.
  - Its nested records:
    - `Event(String date, String place, String description)`, where `date` is a GEDCOM 7 value or null.
    - `Name(String prefix, String given, String surname, String nickname)`, with `display()`.
    - `Descriptor(String tag, String text, String date)`, where `tag` is `TITL` or `OCCU`.
    - `Adoption(AdoptionKind kind, String otherName, String date, String text)`, where `AdoptionKind` is `ADOPTED_BY`, `ADOPTED_CHILD` or `ADOPTED`.
    - `Spouse(Name name, Event birth, Event death, List<Descriptor> descriptors, List<String> review)`.
    - `Marriage(int order, Event wedding, String endTag, Event end, Integer sameAsOrder, Spouse spouse)`, where `endTag` is `DIV`, `EVEN` (annulment) or null.
  - `public static LegacyFacts LegacyLineParser.parse(String line)`.

- [ ] **Step 1: Write the failing test**

Every line in this test is copied verbatim from `czernin-genealogy.txt`. The test also replaces `FamilyTreeParseTest`'s three cases (spouse's death, own death, own birth place), which Task 6 deletes along with the old parser.

Create `backend/src/test/java/place/icomb/archiver/genealogy/LegacyLineParserTest.java`:

```java
package place.icomb.archiver.genealogy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import place.icomb.archiver.genealogy.legacy.LegacyFacts;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Adoption;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.AdoptionKind;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Descriptor;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Event;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Marriage;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Name;
import place.icomb.archiver.genealogy.legacy.LegacyLineParser;

/** Lines from czernin-genealogy.txt, read into facts. Every line here is verbatim from the file. */
class LegacyLineParserTest {

  private static LegacyFacts parse(String line) {
    return LegacyLineParser.parse(line);
  }

  @Test
  void aPersonsOwnDatesAreNotTheirSpouses() {
    LegacyFacts f =
        parse(
            "D6. Alexander Friedrich Josef Paul Maria, *Wien 30.4.1913, +Oxford 5.4.2002; m."
                + " Haywards Heath 9.7.1949 Diana Zannick Hutton/Hulton (*London 12.12.1922, +Iver"
                + " Heath, Bucks 30.8.1982)");
    assertThat(f.birth()).isEqualTo(new Event("30 APR 1913", "Wien", null));
    assertThat(f.death()).isEqualTo(new Event("5 APR 2002", "Oxford", null));
    Marriage m = f.marriages().get(0);
    assertThat(m.order()).isEqualTo(1);
    assertThat(m.wedding()).isEqualTo(new Event("9 JUL 1949", "Haywards Heath", null));
    assertThat(m.spouse().name()).isEqualTo(new Name(null, "Diana Zannick", "Hutton/Hulton", null));
    assertThat(m.spouse().birth()).isEqualTo(new Event("12 DEC 1922", "London", null));
    assertThat(m.spouse().death()).isEqualTo(new Event("30 AUG 1982", "Iver Heath, Bucks", null));
    assertThat(m.spouse().review()).containsExactly("surname uncertain: Hutton/Hulton");
  }

  @Test
  void aLineWithNoDeathHasNone() {
    LegacyFacts f =
        parse(
            "D6. Alexander Friedrich Josef Paul Maria, *Wien 30.4.1913; m. Haywards Heath 9.7.1949"
                + " Diana Zannick Hutton/Hulton (*London 12.12.1922, +Iver Heath, Bucks 30.8.1982)");
    assertThat(f.death()).isNull();
  }

  @Test
  void numberedMarriagesDivorcesAndASpouseMarriedTwice() {
    LegacyFacts f =
        parse(
            "E3. Jaromír Egon Rudolf, Graf Czernin von Chudenitz und Morzin, *Praha 30.1.1908,"
                + " +Munich 1.2.1966; 1m: Budapest 23.2.1929 (div 1938) Márta Gfn Széchényi"
                + " (*15.12.1905, +Wien 24.9.1977); 2m: Starnberg 7.5.1938 (div) Alix-May von"
                + " Frankenberg (*20.9.1907, +19.12.1979); 3m: Altaussee 27.11.1944 (div) same 2nd"
                + " wife; 4m: Zürich 6.11.1951 (div 1955) Gertrud Liebl (*2.9.1922, +26.10.1998);"
                + " 5m: Salzburg 5.5.1960 Margaritha Seyffert (*Riga 5.6.1911)");
    assertThat(f.descriptors())
        .containsExactly(new Descriptor("TITL", "Graf Czernin von Chudenitz und Morzin", null));
    List<Marriage> m = f.marriages();
    assertThat(m).extracting(Marriage::order).containsExactly(1, 2, 3, 4, 5);
    assertThat(m.get(0).endTag()).isEqualTo("DIV");
    assertThat(m.get(0).end()).isEqualTo(new Event("1938", null, null));
    assertThat(m.get(0).spouse().name()).isEqualTo(new Name("Gfn", "Márta", "Széchényi", null));
    assertThat(m.get(1).end()).isEqualTo(new Event(null, null, null));
    assertThat(m.get(2).sameAsOrder()).isEqualTo(2);
    assertThat(m.get(2).spouse()).isNull();
    assertThat(m.get(2).wedding()).isEqualTo(new Event("27 NOV 1944", "Altaussee", null));
    assertThat(m.get(4).endTag()).isNull();
  }

  @Test
  void anAnnulmentIsAnEventNotADivorce() {
    Marriage m =
        parse(
                "E1. Vera Aloysia, *Munich 4.6.1904, +Kirkwood, Missouri 18.9.1959; 1m: Wien"
                    + " 23.2.1924 (annulled 1936) Gf Leopold Heinrich Fugger von Babenhausen"
                    + " (*18.7.1893 +8.7.1966); 2m: Wien 1.6.1938 Kurt von Schuschnigg, Austrian"
                    + " Chancellor (*Riva 14.12.1897, +Mutters bei Innsbruck 18.11.1977)")
            .marriages()
            .get(0);
    assertThat(m.endTag()).isEqualTo("EVEN");
    assertThat(m.end()).isEqualTo(new Event("1936", null, "annulled"));
    assertThat(m.spouse().birth()).isEqualTo(new Event("18 JUL 1893", null, null));
    assertThat(m.spouse().death()).isEqualTo(new Event("8 JUL 1966", null, null));
  }

  @Test
  void aSpouseDescriptorAfterACommaIsKeptOffTheName() {
    Marriage m =
        parse(
                "E1. Vera Aloysia, *Munich 4.6.1904; 2m: Wien 1.6.1938 Kurt von Schuschnigg,"
                    + " Austrian Chancellor (*Riva 14.12.1897, +Mutters bei Innsbruck 18.11.1977)")
            .marriages()
            .get(0);
    assertThat(m.spouse().name()).isEqualTo(new Name(null, "Kurt", "von Schuschnigg", null));
    assertThat(m.spouse().descriptors())
        .containsExactly(new Descriptor("OCCU", "Austrian Chancellor", null));
  }

  @Test
  void legacyDateNotationBecomesGedcom7() {
    assertThat(parse("C2. Jan, +ca 1599; m. Lidmila Byšická z Byšic (+1601)").death().date())
        .isEqualTo("ABT 1599");
    assertThat(parse("Humprecht Czernin z Chudenic, *1447, +after 1501").death().date())
        .isEqualTo("AFT 1501");
    assertThat(parse("D4. Humprecht Diviš, +before 1629").death().date()).isEqualTo("BEF 1629");
    assertThat(parse("D2. Jan Maximilian, +1657/64").death().date()).isEqualTo("BET 1657 AND 1664");
    assertThat(parse("H4. Václav Jan, *5.1.1724, +1731/33").death().date())
        .isEqualTo("BET 1731 AND 1733");
    assertThat(
            parse(
                    "C3. Humprecht III, cr Graf in Bohemia 8.6.1627, *IV.1570, +22.5.1632, bur"
                        + " Radenín")
                .birth()
                .date())
        .isEqualTo("APR 1570");
    assertThat(
            parse(
                    "E3. Ferdinand Karel Ottokar Děpolt Maria, *Heřmanův Městec 25.11.1903, +New York XI.1965")
                .death())
        .isEqualTo(new Event("NOV 1965", "New York", null));
  }

  @Test
  void titlesAndBurialsBecomeTheirOwnEvents() {
    LegacyFacts f =
        parse(
            "C3. Humprecht III, cr Graf in Bohemia 8.6.1627, *IV.1570, +22.5.1632, bur Radenín; m."
                + " IV.1594 Eva Polyxena Woracziczky z Pabienitz (+2.6.1632)");
    assertThat(f.descriptors())
        .containsExactly(new Descriptor("TITL", "cr Graf in Bohemia", "8 JUN 1627"));
    assertThat(f.death()).isEqualTo(new Event("22 MAY 1632", null, null));
    assertThat(f.burial()).isEqualTo(new Event(null, "Radenín", null));
    assertThat(f.marriages().get(0).wedding()).isEqualTo(new Event("APR 1594", null, null));
  }

  @Test
  void wordsThatAreNotADateGoToTheDescription() {
    assertThat(parse("E1. Bedřich, +young").death()).isEqualTo(new Event(null, null, "young"));
    assertThat(parse("I8. Walpurga, *9.2.1765, +young ca 1765").death())
        .isEqualTo(new Event("ABT 1765", null, "young"));
    assertThat(
            parse(
                    "E2. Heřman Maria Rudolf Josef, *Lysá nad Labem 12.4.1915, +m.i.a. nr Kerstch,"
                        + " Crimea 28.2.1942")
                .death())
        .isEqualTo(new Event("28 FEB 1942", "nr Kerstch, Crimea", "m.i.a."));
    assertThat(
            parse(
                    "E5. Stanislav Jan Nepomuk, *Horní Maršov 31.10.1921, +k.a. Voroniezh, Russia"
                        + " 25.7.1942")
                .death())
        .isEqualTo(new Event("25 JUL 1942", "Voroniezh, Russia", "k.a."));
    assertThat(
            parse(
                    "D2. Jan Maximilian, +1657/64; m. Eliška Johanka Švihovská z Rýzmberka (+as"
                        + " Ursuline nun, Prague 1672)")
                .marriages()
                .get(0)
                .spouse()
                .death())
        .isEqualTo(new Event("1672", "Prague", "as Ursuline nun"));
    assertThat(
            parse(
                    "I12. Petr Vojtěch Jan Nepomuk, *17.2.1769, +on woundings after battle of"
                        + " Leipzig 1796")
                .death())
        .isEqualTo(new Event("1796", null, "on woundings after battle of Leipzig"));
  }

  @Test
  void bornAndDiedTogetherIsTwoEvents() {
    LegacyFacts f = parse("F1. Jan Václav, *and +1664");
    assertThat(f.birth()).isEqualTo(new Event("1664", null, null));
    assertThat(f.death()).isEqualTo(new Event("1664", null, null));
  }

  @Test
  void anUnreadableDateKeepsItsYearAndIsFlagged() {
    LegacyFacts f = parse("A1. Gabrielle Maria Anna, *15.12.1782, +31.7./12.11.1787");
    assertThat(f.death()).isEqualTo(new Event("1787", null, "31.7./12.11.1787"));
    assertThat(f.review()).containsExactly("date not understood: 31.7./12.11.1787");

    LegacyFacts t =
        parse(
            "F2. Tomáš Zaccharius, *1651/after 1660/1666, +14.2.1700; m. 28.3.1685 Gfn Zuzana"
                + " Renata Bořitová z Martinitz (*1670, +3.2.1717)");
    assertThat(t.birth()).isEqualTo(new Event("1651", null, "1651/after 1660/1666"));
    assertThat(t.review()).containsExactly("date not understood: 1651/after 1660/1666");
  }

  @Test
  void uncertaintyAndRemarksAreKeptForReview() {
    assertThat(parse("F2. [1m.] Jan Rudolf, *ca 1670, +1713?").review())
        .containsExactly("uncertain date: 1713?");
    LegacyFacts possibly =
        parse("F3. [2m.] Josef Rudolf, *ca 1673, +Heidelberg 1713 (possibly same as F2)");
    assertThat(possibly.death()).isEqualTo(new Event("1713", "Heidelberg", null));
    assertThat(possibly.notes()).containsExactly("possibly same as F2");
    assertThat(possibly.review()).containsExactly("legacy remark: possibly same as F2");

    LegacyFacts gutstejn =
        parse("E2. Jan Heřman, +1707; m. Eliška Saloména (z Gutštejna?) (+1699)");
    assertThat(gutstejn.marriages().get(0).spouse().name())
        .isEqualTo(new Name(null, "Eliška Saloména", "z Gutštejna", null));
    assertThat(gutstejn.marriages().get(0).spouse().review())
        .containsExactly("uncertain name: z Gutštejna?");

    LegacyFacts same =
        parse(
            "G4. [2m.] František Antonín, *30.6.1710, +20.12.1739; m. 31.5.1735 Marie Isabella"
                + " Johanna Css de Merode-Westerloo (*13.10.1703, +1.4.1780) [same woman as G2's"
                + " wife]");
    assertThat(same.review()).containsExactly("legacy remark: same woman as G2's wife");
    assertThat(same.marriages().get(0).spouse().name())
        .isEqualTo(new Name("Css", "Marie Isabella Johanna", "de Merode-Westerloo", null));
  }

  @Test
  void theThreeAdoptionSpellingsAndAdoptedSurnames() {
    assertThat(
            parse(
                    "C6. František Jaromír Evžen, *Wien 3.3.1857, +Jindřichův Hradec 9.4.1932;"
                        + " adopted Eugen František Czernin-Morzin 1927")
                .adoptions())
        .containsExactly(
            new Adoption(
                AdoptionKind.ADOPTED_CHILD,
                "Eugen František Czernin-Morzin",
                "1927",
                "adopted Eugen František Czernin-Morzin 1927"));
    assertThat(
            parse(
                    "D7. Eugen Alfonsus Maria Josef, adopted by František Czernin 1927, *Praha"
                        + " 3.1.1892, +Wien 16.6.1955")
                .adoptions())
        .containsExactly(
            new Adoption(
                AdoptionKind.ADOPTED_BY,
                "František Czernin",
                "1927",
                "adopted by František Czernin 1927"));
    assertThat(
            parse(
                    "E4. Josef Maria Rudolf Ferdinand Czernin-Kinsky (adopted 1954), *Horní Maršov"
                        + " 16.6.1920")
                .adoptions())
        .containsExactly(new Adoption(AdoptionKind.ADOPTED, null, "1954", "adopted 1954"));
    assertThat(parse("F3. Stanislas Carl Maria Josef, adopted 1964, *Linz 6.8.1961").adoptions())
        .containsExactly(new Adoption(AdoptionKind.ADOPTED, null, "1964", "adopted 1964"));
    assertThat(
            parse(
                    "B2. Heřman Zdenko, adopted surname Czernin von Chudenitz und Morzin, *Wien"
                        + " 20.2.1819, +Dresden 2.8.1892")
                .otherSurnames())
        .containsExactly("Czernin von Chudenitz und Morzin");
  }

  @Test
  void childrenByOneMarriageAndNicknames() {
    LegacyFacts f =
        parse(
            "C3. Jindřich, cr Freiherr 1629, hejtman plzeňského kraje, +1640; 1m: Kryzelda"
                + " Kokorzowská z Kokorzowa (+ca 1614); 2m: Veronika Šejková z Olbramovic; all"
                + " children by 1m.");
    assertThat(f.allChildrenBy()).isEqualTo(1);
    assertThat(f.descriptors())
        .containsExactly(
            new Descriptor("TITL", "cr Freiherr", "1629"),
            new Descriptor("OCCU", "hejtman plzeňského kraje", null));
    assertThat(
            parse(
                    "D1. Rudolf Děpolt Maria Josef Ludvík (\"Rudobald\"), *Dymokury 25.8.1904, +Wien"
                        + " 16.5.1984")
                .nickname())
        .isEqualTo("Rudobald");
    assertThat(
            parse(
                    "D2. Wolfgang Otto Paul Dominikus Maria, *Hlušice 4.8.1903, +Wien 10.10.1982;"
                        + " 2m: Wien 28.4.1975 Franziska \"Sissy\" Mayer von Gunthof (*Wien 18.12.1926,"
                        + " +Wien 20.3.1987)")
                .marriages()
                .get(0)
                .spouse()
                .name())
        .isEqualTo(new Name(null, "Franziska Mayer", "von Gunthof", "Sissy"));
  }

  @Test
  void anUnnamedSpouseIsFlagged() {
    assertThat(parse("F1. Nicholas Beckett, *1963; m. NN").marriages().get(0).spouse().review())
        .containsExactly("name not known: NN");
  }

  @Test
  void aMarriageDateItCannotReadIsKeptAndFlagged() {
    LegacyFacts f =
        parse(
            "B4. Marie Antonie, *3.1.1841, +6.1.1874; m. 19/29.10.1871 Gf Ferdinand von"
                + " Fünfkirchen (*20.9.1834, +18.12.1898)");
    assertThat(f.marriages().get(0).wedding()).isEqualTo(new Event("1871", null, "19/29.10.1871"));
    assertThat(f.review()).containsExactly("date not understood: 19/29.10.1871");
  }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd backend && ./gradlew test --tests '*LegacyLineParserTest'`

Expected: FAIL: compilation error `package place.icomb.archiver.genealogy.legacy does not exist`

- [ ] **Step 3: Write the facts and the parser**

Create `backend/src/main/java/place/icomb/archiver/genealogy/legacy/LegacyFacts.java`:

```java
package place.icomb.archiver.genealogy.legacy;

import java.util.List;

/** What one line of the legacy genealogy file says, as structured facts. */
public record LegacyFacts(
    String nickname,
    Event birth,
    Event death,
    Event burial,
    List<Descriptor> descriptors,
    List<Adoption> adoptions,
    List<String> otherSurnames,
    List<Marriage> marriages,
    Integer allChildrenBy,
    List<String> notes,
    List<String> review) {

  /** {@code date} is a GEDCOM 7 date value or null. */
  public record Event(String date, String place, String description) {}

  public record Name(String prefix, String given, String surname, String nickname) {
    public String display() {
      if (given == null) return surname;
      return surname == null ? given : given + " " + surname;
    }
  }

  /** {@code tag} is TITL or OCCU. */
  public record Descriptor(String tag, String text, String date) {}

  public enum AdoptionKind {
    ADOPTED_BY,
    ADOPTED_CHILD,
    ADOPTED
  }

  /** {@code otherName} names the adopter (ADOPTED_BY) or the adoptee (ADOPTED_CHILD). */
  public record Adoption(AdoptionKind kind, String otherName, String date, String text) {}

  public record Spouse(
      Name name, Event birth, Event death, List<Descriptor> descriptors, List<String> review) {}

  /**
   * One marriage, numbered as the line numbers it. {@code sameAsOrder} is set when the line says
   * the spouse is the one from an earlier marriage; {@code endTag} is DIV for a divorce and EVEN
   * for an annulment.
   */
  public record Marriage(
      int order, Event wedding, String endTag, Event end, Integer sameAsOrder, Spouse spouse) {}
}
```

Create `backend/src/main/java/place/icomb/archiver/genealogy/legacy/LegacyLineParser.java`:

```java
package place.icomb.archiver.genealogy.legacy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import place.icomb.archiver.genealogy.GedcomDate;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Adoption;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.AdoptionKind;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Descriptor;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Event;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Marriage;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Name;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Spouse;

/**
 * Reads one line of the legacy genealogy file (genealogy.euweb.cz notation) into structured facts.
 *
 * <p>Notation: {@code *} born, {@code +} died, {@code m.}/{@code 1m:} married, {@code (div YEAR)}
 * divorced, {@code bur} buried; a spouse's own dates follow their name in parentheses. Anything the
 * notation does not settle is kept as text and reported in {@link LegacyFacts#review()}.
 */
public final class LegacyLineParser {

  private static final Pattern CODE_PREFIX = Pattern.compile("^[A-Z]\\d+\\.\\s+");
  private static final Pattern HINT_PREFIX = Pattern.compile("^\\[\\d?m\\.?\\]\\s*");
  private static final Pattern BRACKET_REMARK = Pattern.compile("\\s*\\[([^\\]]*)\\]");
  private static final Pattern MARRIAGE = Pattern.compile("^(\\d)?m[.:]\\s*(.*)$");
  private static final Pattern ALL_CHILDREN = Pattern.compile("^all children by (\\d)m\\.?$");
  private static final Pattern ADOPTED_BY = Pattern.compile("^adopted by (.+?) (\\d{4})$");
  private static final Pattern ADOPTED = Pattern.compile("^adopted (\\d{4})$");
  private static final Pattern ADOPTED_CHILD = Pattern.compile("^adopted (\\p{L}.*?) (\\d{4})$");
  private static final Pattern OTHER_SURNAME =
      Pattern.compile("^(?:adopted|assumed) surname (.+)$");
  private static final Pattern ISSUE_SURNAME = Pattern.compile("^issue bore surname (.+)$");
  private static final Pattern SAME_SPOUSE =
      Pattern.compile("^same (\\d)(?:st|nd|rd|th) (?:wife|husband)$");
  private static final Pattern DIVORCE = Pattern.compile("^div(?:\\s+(.+))?$");
  private static final Pattern ANNULMENT = Pattern.compile("^annulled(?:\\s+(.+))?$");
  private static final Pattern NICKNAME = Pattern.compile("\"([^\"]+)\"");
  private static final Pattern DATE_TOKEN =
      Pattern.compile(
          "\\d{1,2}\\.\\d{1,2}\\.\\d{4}/\\d{1,2}\\.\\d{1,2}\\.\\d{4}"
              + "|\\d{1,2}/\\d{1,2}\\.\\d{1,2}\\.\\d{4}"
              + "|\\d{1,2}\\.\\d{1,2}\\.\\d{4}(?:/\\d{2,4})?"
              + "|[IVX]+\\.\\d{4}"
              + "|\\d{4}(?:/\\d{2,4})?");
  private static final Pattern DAY_MONTH_YEAR =
      Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})");
  private static final Pattern ROMAN_MONTH_YEAR = Pattern.compile("([IVX]+)\\.(\\d{4})");
  private static final Pattern YEAR_SPAN = Pattern.compile("(\\d{4})/(\\d{2,4})");
  private static final Pattern YEAR = Pattern.compile("\\d{4}");
  private static final Pattern TITLE_DESCRIPTOR =
      Pattern.compile(
          "^(?:cr |Graf\\b|Reichsgraf\\b|Altgraf\\b|Freiherr\\b|Fürst\\b|Gf\\b|Frhr\\b|Fst\\b)");

  private static final List<String> MONTHS =
      List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
  private static final List<String> ROMAN =
      List.of("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII");
  private static final Set<String> QUALIFIERS = Set.of("ca", "after", "before");
  private static final List<String> IDIOMS =
      List.of(
          "on woundings after battle of Leipzig",
          "as Ursuline nun",
          "committed suicide",
          "Japanese prison",
          "car accident",
          "accident",
          "beheaded",
          "young",
          "k.a.",
          "m.i.a.");
  private static final Set<String> TITLES =
      Set.of(
          "Gf",
          "Gfn",
          "Graf",
          "Gräfin",
          "Frhr",
          "Frn",
          "Freiherr",
          "Fst",
          "Fürst",
          "Pss",
          "Pr",
          "Prinz",
          "Css",
          "Hon.",
          "Altgraf");
  private static final Set<String> PARTICLES =
      Set.of("von", "zu", "zü", "z", "ze", "de", "Des", "di", "da", "del", "dell", "van", "auf");

  private LegacyLineParser() {}

  public static LegacyFacts parse(String line) {
    List<String> notes = new ArrayList<>();
    List<String> review = new ArrayList<>();
    String rest = CODE_PREFIX.matcher(line.trim()).replaceFirst("");
    rest = HINT_PREFIX.matcher(rest).replaceFirst("");
    int continues = rest.indexOf(">>>");
    if (continues >= 0) {
      rest = rest.substring(0, continues).trim();
    }
    Matcher remark = BRACKET_REMARK.matcher(rest);
    while (remark.find()) {
      notes.add(remark.group(1));
      review.add("legacy remark: " + remark.group(1));
    }
    rest = BRACKET_REMARK.matcher(rest).replaceAll("");

    List<String> segments = splitTopLevel(rest, ';');
    Person person = person(segments.get(0), notes, review);

    List<Marriage> marriages = new ArrayList<>();
    Integer allChildrenBy = null;
    for (String segment : segments.subList(1, segments.size())) {
      String s = segment.trim();
      Matcher m;
      if ((m = MARRIAGE.matcher(s)).matches()) {
        int order = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
        marriages.add(marriage(order, m.group(2), review));
      } else if ((m = ALL_CHILDREN.matcher(s)).matches()) {
        allChildrenBy = Integer.parseInt(m.group(1));
      } else if ((m = ADOPTED_CHILD.matcher(s)).matches()) {
        person.adoptions.add(new Adoption(AdoptionKind.ADOPTED_CHILD, m.group(1), m.group(2), s));
      } else if ((m = ISSUE_SURNAME.matcher(s)).matches()) {
        notes.add(s);
      } else if (!s.isEmpty()) {
        review.add("not understood: " + s);
      }
    }
    return new LegacyFacts(
        person.nickname,
        person.birth,
        person.death,
        person.burial,
        List.copyOf(person.descriptors),
        List.copyOf(person.adoptions),
        List.copyOf(person.otherSurnames),
        List.copyOf(marriages),
        allChildrenBy,
        List.copyOf(notes),
        List.copyOf(review));
  }

  private static final class Person {
    String nickname;
    Event birth;
    Event death;
    Event burial;
    final List<Descriptor> descriptors = new ArrayList<>();
    final List<Adoption> adoptions = new ArrayList<>();
    final List<String> otherSurnames = new ArrayList<>();
  }

  private static Person person(String segment, List<String> notes, List<String> review) {
    Person p = new Person();
    int nameEnd = segment.length();
    for (int i = 0; i < segment.length(); i++) {
      char c = segment.charAt(i);
      if (c == ',' || c == '*') {
        nameEnd = i;
        break;
      }
    }
    String name = segment.substring(0, nameEnd).trim();
    Matcher quoted = NICKNAME.matcher(name);
    if (quoted.find()) {
      p.nickname = quoted.group(1);
    }
    for (String group : parenGroups(name)) {
      String inner = group.replace("\"", "").trim();
      Matcher adopted = ADOPTED.matcher(inner);
      if (adopted.matches()) {
        p.adoptions.add(new Adoption(AdoptionKind.ADOPTED, null, adopted.group(1), inner));
      } else if (p.nickname == null) {
        p.nickname = inner;
      }
    }
    if (name.toLowerCase().startsWith("a daughter") || name.toLowerCase().startsWith("a son")) {
      review.add("no name recorded: " + name);
    }

    String after = segment.substring(nameEnd);
    for (String group : parenGroups(after)) {
      notes.add(group);
      review.add("legacy remark: " + group);
    }
    after = withoutParenGroups(after);

    int star = after.indexOf('*');
    int plus = after.indexOf('+');
    int firstMarker = star < 0 ? plus : (plus < 0 ? star : Math.min(star, plus));
    String described = firstMarker < 0 ? after : after.substring(0, firstMarker);
    for (String d : splitTopLevel(described, ',')) {
      String text = d.trim();
      if (!text.isEmpty()) {
        descriptor(text, p, review);
      }
    }
    String deathText = plus < 0 ? null : after.substring(plus + 1).trim();
    if (deathText != null) {
      int bur = deathText.indexOf(", bur ");
      if (bur >= 0) {
        p.burial = new Event(null, deathText.substring(bur + 6).trim(), null);
        deathText = deathText.substring(0, bur);
      }
      p.death = event(deathText, review);
    }
    if (star >= 0) {
      String birthText = after.substring(star + 1, plus > star ? plus : after.length()).trim();
      p.birth = birthText.equals("and") ? p.death : event(stripComma(birthText), review);
    }
    return p;
  }

  private static void descriptor(String text, Person p, List<String> review) {
    Matcher m;
    if ((m = OTHER_SURNAME.matcher(text)).matches()) {
      p.otherSurnames.add(m.group(1));
    } else if ((m = ADOPTED_BY.matcher(text)).matches()) {
      p.adoptions.add(new Adoption(AdoptionKind.ADOPTED_BY, m.group(1), m.group(2), text));
    } else if ((m = ADOPTED.matcher(text)).matches()) {
      p.adoptions.add(new Adoption(AdoptionKind.ADOPTED, null, m.group(1), text));
    } else {
      p.descriptors.add(describe(text, review));
    }
  }

  private static Descriptor describe(String text, List<String> review) {
    String tag = TITLE_DESCRIPTOR.matcher(text).find() ? "TITL" : "OCCU";
    List<String> tokens = Arrays.asList(text.split("\\s+"));
    String last = tokens.get(tokens.size() - 1);
    if (tokens.size() > 1 && DATE_TOKEN.matcher(last).matches()) {
      String date = gedcom(null, last);
      if (date == null) {
        review.add("date not understood: " + text);
        return new Descriptor(tag, text, null);
      }
      return new Descriptor(tag, String.join(" ", tokens.subList(0, tokens.size() - 1)), date);
    }
    return new Descriptor(tag, text, null);
  }

  private static Marriage marriage(int order, String body, List<String> review) {
    List<String> tokens = new ArrayList<>(Arrays.asList(body.trim().split("\\s+")));
    String place = null;
    Event wedding = null;
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      boolean qualified =
          QUALIFIERS.contains(token)
              && i + 1 < tokens.size()
              && DATE_TOKEN.matcher(tokens.get(i + 1)).matches();
      if (qualified || DATE_TOKEN.matcher(token).matches()) {
        place = i == 0 ? null : stripComma(String.join(" ", tokens.subList(0, i)));
        String raw = qualified ? token + " " + tokens.get(i + 1) : token;
        String date = qualified ? gedcom(token, tokens.get(i + 1)) : gedcom(null, token);
        if (date == null) {
          review.add("date not understood: " + raw);
          wedding = new Event(firstYear(raw), place, raw);
        } else {
          wedding = new Event(date, place, null);
        }
        tokens = tokens.subList(i + (qualified ? 2 : 1), tokens.size());
        break;
      }
      if (token.matches(".*\\d.*")) {
        break;
      }
    }
    if (wedding == null) {
      wedding = new Event(null, null, null);
    }
    String rest = String.join(" ", tokens).trim();

    String endTag = null;
    Event end = null;
    if (rest.startsWith("(")) {
      int close = rest.indexOf(')');
      String inner = rest.substring(1, close).trim();
      Matcher div = DIVORCE.matcher(inner);
      Matcher annul = ANNULMENT.matcher(inner);
      if (div.matches()) {
        endTag = "DIV";
        end = div.group(1) == null ? new Event(null, null, null) : event(div.group(1), review);
        rest = rest.substring(close + 1).trim();
      } else if (annul.matches()) {
        endTag = "EVEN";
        Event when = annul.group(1) == null ? null : event(annul.group(1), review);
        end = new Event(when == null ? null : when.date(), null, "annulled");
        rest = rest.substring(close + 1).trim();
      }
    }

    Matcher same = SAME_SPOUSE.matcher(rest);
    if (same.matches()) {
      return new Marriage(order, wedding, endTag, end, Integer.parseInt(same.group(1)), null);
    }
    return new Marriage(order, wedding, endTag, end, null, spouse(rest));
  }

  private static Spouse spouse(String text) {
    List<String> review = new ArrayList<>();
    int paren = indexOfTopLevel(text, '(');
    String namePart = paren < 0 ? text : text.substring(0, paren);
    List<String> parts = splitTopLevel(namePart, ',');
    List<Descriptor> descriptors = new ArrayList<>();
    for (String extra : parts.subList(1, parts.size())) {
      if (!extra.isBlank()) {
        descriptors.add(describe(extra.trim(), review));
      }
    }
    String uncertainSurname = null;
    Event birth = null;
    Event death = null;
    for (String group : parenGroups(paren < 0 ? "" : text.substring(paren))) {
      int star = group.indexOf('*');
      int plus = group.indexOf('+');
      if (star >= 0 || plus >= 0) {
        if (plus >= 0) {
          death = event(group.substring(plus + 1), review);
        }
        if (star >= 0) {
          String b = group.substring(star + 1, plus > star ? plus : group.length());
          birth = event(stripComma(b.trim()), review);
        }
      } else if (group.endsWith("?")) {
        uncertainSurname = group.substring(0, group.length() - 1).trim();
        review.add("uncertain name: " + group);
      } else {
        review.add("legacy remark: " + group);
      }
    }
    Name name = name(parts.get(0).trim(), uncertainSurname, review);
    return new Spouse(name, birth, death, List.copyOf(descriptors), List.copyOf(review));
  }

  static Name name(String text, String uncertainSurname, List<String> review) {
    String nickname = null;
    Matcher quoted = NICKNAME.matcher(text);
    if (quoted.find()) {
      nickname = quoted.group(1);
    }
    List<String> prefix = new ArrayList<>();
    List<String> words = new ArrayList<>();
    for (String token : NICKNAME.matcher(text).replaceAll(" ").trim().split("\\s+")) {
      if (token.isEmpty()) continue;
      if (TITLES.contains(token)) {
        prefix.add(token);
      } else {
        words.add(token);
      }
    }
    String given;
    String surname;
    int particle = -1;
    for (int i = 1; i < words.size(); i++) {
      if (PARTICLES.contains(words.get(i))) {
        particle = i;
        break;
      }
    }
    if (words.isEmpty()) {
      given = null;
      surname = null;
    } else if (particle > 0) {
      given = String.join(" ", words.subList(0, particle));
      surname = String.join(" ", words.subList(particle, words.size()));
    } else if (words.size() > 1) {
      given = String.join(" ", words.subList(0, words.size() - 1));
      surname = words.get(words.size() - 1);
    } else {
      given = words.get(0);
      surname = null;
    }
    if (uncertainSurname != null && particle < 0) {
      given = words.isEmpty() ? null : String.join(" ", words);
      surname = uncertainSurname;
    } else if (uncertainSurname != null) {
      surname = surname + " " + uncertainSurname;
    }
    if (surname != null && surname.contains("/")) {
      review.add("surname uncertain: " + surname);
    }
    if ("NN".equals(given) || "N".equals(surname) || "NN".equals(surname)) {
      review.add("name not known: " + text);
    }
    return new Name(prefix.isEmpty() ? null : String.join(" ", prefix), given, surname, nickname);
  }

  static Event event(String raw, List<String> review) {
    String t = stripComma(raw.trim());
    if (t.isEmpty()) {
      return null;
    }
    if (t.endsWith("?")) {
      review.add("uncertain date: " + raw.trim());
      t = t.substring(0, t.length() - 1).trim();
    }
    String description = null;
    for (String idiom : IDIOMS) {
      if (t.equals(idiom) || t.startsWith(idiom + " ") || t.startsWith(idiom + ",")) {
        description = idiom;
        t = t.substring(idiom.length()).replaceFirst("^[,\\s]+", "");
        break;
      }
    }
    List<String> tokens = t.isEmpty() ? List.of() : Arrays.asList(t.split("\\s+"));
    String date = null;
    int dateStart = tokens.size();
    if (!tokens.isEmpty() && DATE_TOKEN.matcher(tokens.get(tokens.size() - 1)).matches()) {
      String last = tokens.get(tokens.size() - 1);
      dateStart = tokens.size() - 1;
      String qualifier = null;
      if (dateStart > 0 && QUALIFIERS.contains(tokens.get(dateStart - 1))) {
        qualifier = tokens.get(dateStart - 1);
        dateStart--;
      }
      date = gedcom(qualifier, last);
      if (date == null) {
        return unparsed(raw, review);
      }
    }
    String prefix = stripComma(String.join(" ", tokens.subList(0, dateStart)));
    if (prefix.matches(".*\\d.*")) {
      return unparsed(raw, review);
    }
    return new Event(date, prefix.isEmpty() ? null : prefix, description);
  }

  private static Event unparsed(String raw, List<String> review) {
    String text = raw.trim();
    review.add("date not understood: " + text);
    return new Event(firstYear(text), null, text);
  }

  static String gedcom(String qualifier, String token) {
    String core = null;
    Matcher m;
    if ((m = DAY_MONTH_YEAR.matcher(token)).matches()) {
      int month = Integer.parseInt(m.group(2));
      if (month >= 1 && month <= 12) {
        core = Integer.parseInt(m.group(1)) + " " + MONTHS.get(month - 1) + " " + m.group(3);
      }
    } else if ((m = ROMAN_MONTH_YEAR.matcher(token)).matches()) {
      int month = ROMAN.indexOf(m.group(1));
      if (month >= 0) {
        core = MONTHS.get(month) + " " + m.group(2);
      }
    } else if (YEAR.matcher(token).matches()) {
      core = token;
    } else if ((m = YEAR_SPAN.matcher(token)).matches() && qualifier == null) {
      String from = m.group(1);
      String to = from.substring(0, 4 - m.group(2).length()) + m.group(2);
      if (Integer.parseInt(to) > Integer.parseInt(from)) {
        core = "BET " + from + " AND " + to;
      }
    }
    if (core == null) {
      return null;
    }
    String value =
        qualifier == null
            ? core
            : switch (qualifier) {
              case "ca" -> "ABT " + core;
              case "after" -> "AFT " + core;
              default -> "BEF " + core;
            };
    try {
      GedcomDate.parse(value);
      return value;
    } catch (GedcomDate.InvalidDateException e) {
      return null;
    }
  }

  private static String firstYear(String text) {
    Matcher m = YEAR.matcher(text);
    return m.find() ? m.group() : null;
  }

  private static String stripComma(String s) {
    return s.replaceAll("[,\\s]+$", "").trim();
  }

  static List<String> splitTopLevel(String s, char separator) {
    List<String> out = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '(') depth++;
      else if (c == ')') depth = Math.max(0, depth - 1);
      else if (c == separator && depth == 0) {
        out.add(s.substring(start, i));
        start = i + 1;
      }
    }
    out.add(s.substring(start));
    return out;
  }

  private static int indexOfTopLevel(String s, char target) {
    int depth = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == target && depth == 0) return i;
      if (c == '(') depth++;
      else if (c == ')') depth = Math.max(0, depth - 1);
    }
    return -1;
  }

  private static List<String> parenGroups(String s) {
    List<String> out = new ArrayList<>();
    int depth = 0;
    int start = -1;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '(') {
        if (depth == 0) start = i + 1;
        depth++;
      } else if (c == ')' && depth > 0) {
        depth--;
        if (depth == 0) out.add(s.substring(start, i).trim());
      }
    }
    return out;
  }

  private static String withoutParenGroups(String s) {
    StringBuilder out = new StringBuilder();
    int depth = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '(') depth++;
      else if (c == ')' && depth > 0) depth--;
      else if (depth == 0) out.append(c);
    }
    return out.toString().replaceAll("\\s+", " ").replaceAll("\\s+,", ",").trim();
  }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `cd backend && ./gradlew test --tests '*LegacyLineParserTest'`

Expected: PASS (15 tests)

- [ ] **Step 5: Format**

Run: `cd backend && ./gradlew spotlessApply`

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A backend/src/main/java/place/icomb/archiver/genealogy/legacy backend/src/test/java/place/icomb/archiver/genealogy/LegacyLineParserTest.java
git commit -m "feat: read legacy genealogy lines into structured facts"
```

---

### Task 5: Legacy import (V22) and person references (V23)

**Files:**
- Move: `backend/src/main/resources/czernin-genealogy.txt` → `backend/src/main/resources/db/legacy/czernin-genealogy.txt`
- Modify: `backend/src/main/java/place/icomb/archiver/service/FamilyTreeService.java:263` (resource path only)
- Create: `backend/src/main/java/place/icomb/archiver/genealogy/legacy/LegacyTreeParser.java`
- Create: `backend/src/main/java/place/icomb/archiver/genealogy/legacy/LegacyImport.java`
- Create: `backend/src/main/java/place/icomb/archiver/genealogy/legacy/V22__Import_legacy_genealogy.java`
- Create: `backend/src/main/resources/db/migration/V23__genealogy_person_references.sql`
- Modify (replace): `backend/src/test/java/place/icomb/archiver/service/FamilyTreeGolden.java` (adds `expectedDisplay` and the after-move comparisons)
- Test: `backend/src/test/java/place/icomb/archiver/genealogy/LegacyImportTest.java`, `backend/src/test/java/place/icomb/archiver/genealogy/GenealogyReferencesMigrationTest.java`

**Interfaces:**
- Consumes:
  - `LegacyLineParser.parse` and `LegacyFacts` (Task 4), `GedcomDate.parse` (Task 3), and the V21 tables (Task 2).
  - `FamilyTreeGolden.read("people.tsv")` (Task 1) and `GenealogyFixtures.record` (Task 2).
- Produces:
  - `LegacyTreeParser.parse(List<String> lines)` returns `LegacyTree(List<LegacyPerson> people, Map<Integer, Integer> mergedInto)`. `people` holds all 348 parsed lines, ids 1–348; `mergedInto` is `{122: 76, 227: 80}`.
  - `LegacyPerson` fields: `id`, `name`, `fullEntry`, `section`, `code`, `parentCode`, `depth`, `parent` (after folding), `children`, `marriageHint`, and `fullCode()`.
  - `new LegacyImport(JdbcTemplate).run(List<String> lines)`, with constants `SOURCE_TITLE = "genealogy.euweb.cz Czernin pages (legacy text file)"` and `SOURCE_URL`.
  - `@Component class V22__Import_legacy_genealogy extends BaseJavaMigration`. It reads `/db/legacy/czernin-genealogy.txt`; `FlywayAutoConfiguration` collects it as a `JavaMigration` bean.
  - Data:
    - 348 legacy people, the roots folded (`merged_into`), and spouses from 349 upward.
    - Primary names. A family member's surname is `Czernin`; a legacy name already holding a Czernin form is split at it (`Humprecht` / `Czernin z Chudenic`, `Josef Maria Rudolf Ferdinand` / `Czernin-Kinsky`, title words before it go to `prefix`); the eight descendants through daughters whose legacy name ends in their father's line's surname keep it (`Charlotte Sonia` / `Phillips`, the Becketts). No `married` names, because the file states none.
  - `LegacyTreeParser.legacyName(String line)`: the name the in-memory tree gave a line (used by the read model for `matchName`).
  - `FamilyTreeGolden`: `public static String expectedDisplay(int id, String legacyName)`, `INHERITED_SURNAME_IDS`, `assertPeopleMatchAfterMove`, `assertKinshipMatchAfterMove(actual, golden, people)`, `assertSearchMatchAfterMove`.
    - `BIRT`, `DEAT`, `BURI`, `TITL`, `OCCU`, `ADOP` and `EVEN` person events; `MARR`, `DIV` and `EVEN` couple events.
    - `birth` and `adopted` parent links, and couples.
    - One place per spelling.
    - One source, and a quality-1 citation for every name and event.
    - `needs_review` text, and `living`.
  - V23: `page_person_match_person_id_fkey` and `app_user_family_tree_person_id_fkey` (`ON DELETE SET NULL`).

- [ ] **Step 1: Write the failing tests**

The database shows every family member with the surname Czernin (decision 3), so the golden helper gains the one intended difference: `expectedDisplay`, plus comparisons that apply it. `assertPeopleMatch`, which `FamilyTreeGoldenTest` still uses against the in-memory tree, is unchanged.

Replace the whole of `backend/src/test/java/place/icomb/archiver/service/FamilyTreeGolden.java` with:

```java
package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import place.icomb.archiver.service.FamilyTreeService.Person;
import place.icomb.archiver.service.FamilyTreeService.RelationshipResult;

/**
 * The family tree's observable behaviour, written out as text so it can be compared before and
 * after the tree moved from the text file into the database.
 *
 * <p>Captured once from the in-memory tree (2026-11-02) and checked into {@code
 * src/test/resources/genealogy/golden}. Rewrite them only with {@code GOLDEN_WRITE=true}, and only
 * from the tree they are meant to describe.
 */
public final class FamilyTreeGolden {

  static final Path DIR = Path.of("src/test/resources/genealogy/golden");

  static final List<Integer> REFERENCE_PEOPLE = List.of(1, 76, 128, 324);

  static final int ALEXANDER = 322;

  static final List<String> QUERIES =
      List.of(
          "alexander",
          "czernin",
          "rudolf",
          "jaromir",
          "theobald",
          "wolfgang maria",
          "eugen",
          "diana",
          "humprecht",
          "dymokury",
          "Děpolt",
          "lobkowicz");

  /**
   * People whose legacy birth place was an artefact of the old parser rather than a place: 91's
   * "1651/after 1660/1666" left "/after", and 200's "*and +Klagenfurt" left "and +". The database
   * gives 91 no place and 200 Klagenfurt.
   */
  static final Map<Integer, String> BIRTH_PLACE_EXCEPTIONS =
      Map.of(91, "legacy '/after' artefact", 200, "legacy 'and +' artefact");

  /**
   * Descendants through a daughter whose legacy name already ends in their father's line's surname
   * (Phillips, Beckett): the database keeps that surname instead of adding Czernin.
   */
  static final Set<Integer> INHERITED_SURNAME_IDS = Set.of(277, 278, 289, 290, 291, 292, 293, 294);

  private FamilyTreeGolden() {}

  /**
   * The name the database shows for a legacy person: the legacy name plus the family surname
   * Czernin. A name already holding a Czernin form is shown as it was, less a trailing remark in
   * parentheses; so is a name already ending in an inherited surname. This is the only intended
   * difference between the in-memory tree and the database tree.
   */
  public static String expectedDisplay(int id, String legacyName) {
    if (legacyName.contains("Czernin") || INHERITED_SURNAME_IDS.contains(id)) {
      return legacyName.replaceAll("(\\s*\\([^)]*\\))+\\s*$", "").trim();
    }
    return legacyName + " Czernin";
  }

  /** {@link #assertPeopleMatch}, with the name column compared as {@link #expectedDisplay}. */
  static void assertPeopleMatchAfterMove(List<String> actual, List<String> golden) {
    List<String> expected = new ArrayList<>();
    for (String line : golden) {
      String[] c = line.split("\t", -1);
      c[8] = expectedDisplay(Integer.parseInt(c[0]), c[8]);
      expected.add(String.join("\t", c));
    }
    assertPeopleMatch(actual, expected);
  }

  /**
   * Labels, ids and steps exactly as captured; every name, including those inside the path
   * description, as {@link #expectedDisplay}.
   */
  static void assertKinshipMatchAfterMove(
      List<String> actual, List<String> golden, List<String> people) {
    Map<Integer, String> names = new java.util.HashMap<>();
    Map<Integer, Integer> parents = new java.util.HashMap<>();
    for (String line : people) {
      String[] c = line.split("\t", -1);
      names.put(Integer.parseInt(c[0]), c[8]);
      if (!c[1].equals("-")) parents.put(Integer.parseInt(c[0]), Integer.parseInt(c[1]));
    }
    assertThat(actual).hasSameSizeAs(golden);
    for (int i = 0; i < golden.size(); i++) {
      String[] want = golden.get(i).split("\t", -1);
      if (want.length == 3) {
        assertThat(actual.get(i)).isEqualTo(golden.get(i));
        continue;
      }
      String[] got = actual.get(i).split("\t", -1);
      int person = Integer.parseInt(want[0]);
      int ancestor = Integer.parseInt(want[5]);
      int ref = want[1].equals("default") ? ALEXANDER : Integer.parseInt(want[1]);
      String row = "kinship row " + golden.get(i);
      for (int col : new int[] {0, 1, 5, 6, 7}) {
        assertThat(got[col]).as(row).isEqualTo(want[col]);
      }
      String label = person == ref ? "This is " + expectedDisplay(ref, names.get(ref)) : want[2];
      assertThat(got[2]).as(row).isEqualTo(label);
      assertThat(got[8]).as(row).isEqualTo(expectedDisplay(person, names.get(person)));
      assertThat(got[9]).as(row).isEqualTo(expectedDisplay(ref, names.get(ref)));
      assertThat(got[4]).as(row).isEqualTo(expectedDisplay(ancestor, names.get(ancestor)));
      String path;
      if (person == ref) {
        path = ref == ALEXANDER ? want[3] : label;
      } else {
        List<Integer> involved = new ArrayList<>(List.of(person, ref, ancestor));
        for (int from : new int[] {person, ref}) {
          if (from == ancestor) continue;
          Integer cur = from;
          while (cur != null && parents.get(cur) != null && parents.get(cur) != ancestor) {
            cur = parents.get(cur);
          }
          if (cur != null && cur != ancestor) involved.add(cur);
        }
        path = renamed(want[3], involved, names);
      }
      assertThat(got[3]).as(row).isEqualTo(path);
    }
  }

  /** Each involved person's legacy name in the text, longest first, as its display name. */
  private static String renamed(String text, List<Integer> ids, Map<Integer, String> names) {
    List<Integer> order =
        ids.stream()
            .distinct()
            .sorted(Comparator.comparingInt((Integer id) -> names.get(id).length()).reversed())
            .toList();
    String out = text;
    for (int k = 0; k < order.size(); k++) {
      out = out.replace(names.get(order.get(k)), "\u0000" + k + "\u0001");
    }
    for (int k = 0; k < order.size(); k++) {
      int id = order.get(k);
      out = out.replace("\u0000" + k + "\u0001", expectedDisplay(id, names.get(id)));
    }
    return out;
  }

  /**
   * Search results as captured, names as {@link #expectedDisplay}. Queries containing "czernin" are
   * left out: every family member now carries the surname, so they rank differently by design.
   */
  static void assertSearchMatchAfterMove(List<String> actual, List<String> golden) {
    List<String> want = new ArrayList<>();
    for (String line : golden) {
      String[] c = line.split("\t", -1);
      if (c[0].contains("czernin")) continue;
      c[3] = expectedDisplay(Integer.parseInt(c[2]), c[3]);
      want.add(String.join("\t", c));
    }
    List<String> got =
        actual.stream().filter(l -> !l.split("\t", -1)[0].contains("czernin")).toList();
    assertThat(got).isEqualTo(want);
  }

  static List<String> people(FamilyTreeService tree) {
    List<String> out = new ArrayList<>();
    for (Person p : sorted(tree.getAllPeople())) {
      List<String> childIds = new ArrayList<>();
      for (Person c : p.children) childIds.add(String.valueOf(c.id));
      out.add(
          String.join(
              "\t",
              String.valueOf(p.id),
              p.parent == null ? "-" : String.valueOf(p.parent.id),
              String.valueOf(p.section),
              p.fullCode(),
              String.valueOf(p.depth),
              String.valueOf(p.birthYear),
              String.valueOf(p.deathYear),
              String.valueOf(p.birthPlace),
              p.name,
              p.fullEntry,
              String.join(",", childIds)));
    }
    return out;
  }

  static List<String> kinship(FamilyTreeService tree) {
    List<String> out = new ArrayList<>();
    for (Person p : sorted(tree.getAllPeople())) {
      out.add(row(p.id, "default", tree.relate(p.id)));
      for (int ref : REFERENCE_PEOPLE) {
        out.add(row(p.id, String.valueOf(ref), tree.relate(p.id, ref)));
      }
    }
    return out;
  }

  static List<String> search(FamilyTreeService tree) {
    List<String> out = new ArrayList<>();
    for (String q : QUERIES) {
      int rank = 1;
      for (var r : tree.search(q, 10)) {
        out.add(
            String.join(
                "\t",
                q,
                String.valueOf(rank++),
                String.valueOf(r.personId()),
                r.name(),
                String.format(Locale.ROOT, "%.6f", r.score()),
                String.valueOf(r.birthYear()),
                String.valueOf(r.deathYear()),
                r.section(),
                r.code()));
      }
    }
    return out;
  }

  public static List<String> read(String name) throws IOException {
    try (InputStream in = FamilyTreeGolden.class.getResourceAsStream("/genealogy/golden/" + name)) {
      assertThat(in).as("golden file " + name).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
  }

  static void write(String name, List<String> lines) throws IOException {
    Files.createDirectories(DIR);
    Files.writeString(DIR.resolve(name), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
  }

  /**
   * Every column must match exactly except the birth place, which must match as {@code
   * PersonMatchService} reads it: the text before the first comma or plus, normalised, and only
   * when four or more characters long.
   */
  static void assertPeopleMatch(List<String> actual, List<String> golden) {
    assertThat(actual).hasSameSizeAs(golden);
    for (int i = 0; i < golden.size(); i++) {
      String[] want = golden.get(i).split("\t", -1);
      String[] got = actual.get(i).split("\t", -1);
      int id = Integer.parseInt(want[0]);
      for (int col = 0; col < want.length; col++) {
        if (col == 7) {
          if (!BIRTH_PLACE_EXCEPTIONS.containsKey(id)) {
            assertThat(placeKey(got[7])).as("birth place of " + id).isEqualTo(placeKey(want[7]));
          }
        } else {
          assertThat(got[col]).as("column " + col + " of person " + id).isEqualTo(want[col]);
        }
      }
    }
  }

  /**
   * The in-memory tree's people exactly as it held them, birth places included, rebuilt from {@code
   * people.tsv}: the input person matching was tuned on.
   */
  static List<Person> legacyPeople() throws IOException {
    Map<Integer, Person> byId = new java.util.LinkedHashMap<>();
    Map<Integer, Integer> parentOf = new java.util.HashMap<>();
    for (String line : read("people.tsv")) {
      String[] c = line.split("\t", -1);
      Person p = new Person();
      p.id = Integer.parseInt(c[0]);
      p.section = c[2];
      p.code = c[3].isEmpty() ? null : c[3];
      p.depth = Integer.parseInt(c[4]);
      p.birthYear = c[5].equals("null") ? null : Integer.valueOf(c[5]);
      p.deathYear = c[6].equals("null") ? null : Integer.valueOf(c[6]);
      p.birthPlace = c[7].equals("null") ? null : c[7];
      p.name = c[8];
      p.fullEntry = c[9];
      p.children = new ArrayList<>();
      p.spouses = new ArrayList<>();
      byId.put(p.id, p);
      if (!c[1].equals("-")) parentOf.put(p.id, Integer.parseInt(c[1]));
    }
    for (Person p : byId.values()) {
      Integer parent = parentOf.get(p.id);
      if (parent != null) {
        p.parent = byId.get(parent);
        p.parent.children.add(p);
      }
    }
    return List.copyOf(byId.values());
  }

  static String placeKey(String place) {
    if (place == null || place.equals("null") || place.isBlank()) return null;
    String first = place.split("[,+]")[0].trim();
    String norm =
        Normalizer.normalize(first, Normalizer.Form.NFD).replaceAll("\\p{Mn}", "").toLowerCase();
    return norm.length() >= 4 ? norm : null;
  }

  private static String row(int personId, String ref, RelationshipResult r) {
    if (r == null) return personId + "\t" + ref + "\tnull";
    return String.join(
        "\t",
        String.valueOf(personId),
        ref,
        r.kinshipLabel(),
        r.pathDescription(),
        String.valueOf(r.commonAncestorName()),
        String.valueOf(r.commonAncestorId()),
        String.valueOf(r.stepsFromPerson()),
        String.valueOf(r.stepsFromRef()),
        r.personName(),
        r.refPersonName());
  }

  private static List<Person> sorted(List<Person> people) {
    return people.stream().sorted(Comparator.comparingInt(p -> p.id)).toList();
  }
}
```

`LegacyImportTest` reads the database V22 wrote. `GenealogyReferencesMigrationTest` starts the application at V22 (`spring.flyway.target=22`), plants the rows production may hold, then runs V23 itself.

Create `backend/src/test/java/place/icomb/archiver/genealogy/LegacyImportTest.java`:

```java
package place.icomb.archiver.genealogy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.service.FamilyTreeGolden;

/** What migration V22 made of the legacy genealogy file, read back from the database. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class LegacyImportTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private JdbcTemplate jdbc;

  private static final int ALEXANDER = 322;
  private static final int JAROMIR_EGON = 141;

  private String primaryName(int personId) {
    return jdbc.queryForObject(
        "SELECT concat_ws(' ', given, surname) FROM gen_name WHERE person_id = ? AND is_primary",
        String.class,
        personId);
  }

  private int personNamed(String given, String surname) {
    return jdbc.queryForObject(
        "SELECT person_id FROM gen_name WHERE given = ? AND surname = ? AND is_primary",
        Integer.class,
        given,
        surname);
  }

  @Test
  void everyLegacyIdHasItsOldNameWithTheFamilySurname() throws Exception {
    for (String line : FamilyTreeGolden.read("people.tsv")) {
      String[] c = line.split("\t", -1);
      int id = Integer.parseInt(c[0]);
      assertThat(primaryName(id))
          .as("person " + id)
          .isEqualTo(FamilyTreeGolden.expectedDisplay(id, c[8]));
    }
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM gen_person WHERE id <= 348", Integer.class))
        .isEqualTo(348);
  }

  @Test
  void aCzerninFormAlreadyInTheNameBecomesTheSurnameOnce() {
    assertThat(nameParts(76)).containsExactly(null, "Johann Rudolf", "Czernin");
    assertThat(nameParts(1)).containsExactly(null, "Humprecht", "Czernin z Chudenic");
    assertThat(nameParts(172))
        .containsExactly(null, "Josef Maria Rudolf Ferdinand", "Czernin-Kinsky");
    assertThat(nameParts(324)).containsExactly(null, "Alexander Hannibal", "Czernin Fishlock");
    assertThat(nameParts(277)).containsExactly(null, "Charlotte Sonia", "Phillips");
    assertThat(nameParts(138)).containsExactly(null, "Rudolf Heřman Maria Josef", "Czernin");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_name WHERE type = 'married'", Integer.class))
        .isZero();
  }

  private java.util.List<Object> nameParts(int personId) {
    Map<String, Object> n =
        jdbc.queryForMap(
            "SELECT prefix, given, surname FROM gen_name WHERE person_id = ? AND is_primary",
            personId);
    return java.util.Arrays.asList(n.get("prefix"), n.get("given"), n.get("surname"));
  }

  @Test
  void spousesAreNumberedFrom349() {
    assertThat(jdbc.queryForObject("SELECT min(id) FROM gen_person WHERE id > 348", Integer.class))
        .isEqualTo(349);
    int next = jdbc.queryForObject("SELECT nextval('gen_person_id_seq')", Integer.class);
    assertThat(next)
        .isGreaterThan(jdbc.queryForObject("SELECT max(id) FROM gen_person", Integer.class) - 1);
  }

  @Test
  void alexanderHasBirthDeathMarriageToDianaAndBothParents() {
    assertThat(
            jdbc.queryForList(
                "SELECT e.type, e.date_text, p.name AS place FROM gen_event e"
                    + " LEFT JOIN gen_place p ON p.id = e.place_id"
                    + " WHERE e.person_id = ? ORDER BY e.sort_order",
                ALEXANDER))
        .containsExactly(
            Map.of("type", "BIRT", "date_text", "30 APR 1913", "place", "Wien"),
            Map.of("type", "DEAT", "date_text", "5 APR 2002", "place", "Oxford"));
    int diana = personNamed("Diana Zannick", "Hutton/Hulton");
    Map<String, Object> marriage =
        jdbc.queryForMap(
            "SELECT e.date_text, p.name AS place FROM gen_couple c"
                + " JOIN gen_event e ON e.couple_id = c.id AND e.type = 'MARR'"
                + " LEFT JOIN gen_place p ON p.id = e.place_id"
                + " WHERE c.person1_id = ? AND c.person2_id = ?",
            ALEXANDER,
            diana);
    assertThat(marriage)
        .containsEntry("date_text", "9 JUL 1949")
        .containsEntry("place", "Haywards Heath");
    assertThat(
            jdbc.queryForList(
                "SELECT parent_id FROM gen_parent_child WHERE child_id = ? AND type = 'birth'"
                    + " ORDER BY parent_id",
                Integer.class,
                ALEXANDER))
        .containsExactly(296, personNamed("Maria Gabriele", "von Orsini-Rosenberg"));
    assertThat(
            jdbc.queryForList(
                "SELECT e.type, e.date_text FROM gen_event e WHERE e.person_id = ?"
                    + " ORDER BY e.sort_order",
                diana))
        .containsExactly(
            Map.of("type", "BIRT", "date_text", "12 DEC 1922"),
            Map.of("type", "DEAT", "date_text", "30 AUG 1982"));
  }

  @Test
  void jaromirHasFourCouplesThreeEndingInDivorceAndOneSpouseMarriedTwice() {
    List<Map<String, Object>> couples =
        jdbc.queryForList(
            """
            SELECT c.id, c.sort_order,
                   (SELECT count(*) FROM gen_event e WHERE e.couple_id = c.id AND e.type = 'MARR') AS marr,
                   (SELECT count(*) FROM gen_event e WHERE e.couple_id = c.id AND e.type = 'DIV') AS div
            FROM gen_couple c WHERE c.person1_id = ? ORDER BY c.sort_order
            """,
            JAROMIR_EGON);
    assertThat(couples).hasSize(4);
    assertThat(couples.stream().filter(c -> ((Number) c.get("div")).intValue() > 0)).hasSize(3);
    Map<String, Object> alixMay = couples.get(1);
    assertThat(((Number) alixMay.get("marr")).intValue()).isEqualTo(2);
    assertThat(((Number) alixMay.get("div")).intValue()).isEqualTo(2);
  }

  @Test
  void theAdoptionsBecomeAdoptedLinksOrAdoptionEvents() {
    assertThat(
            jdbc.queryForList(
                "SELECT parent_id, child_id FROM gen_parent_child WHERE type = 'adopted'"))
        .containsExactly(Map.of("parent_id", 134, "child_id", 202));
    assertThat(
            jdbc.queryForList(
                "SELECT person_id, date_text FROM gen_event WHERE type = 'ADOP' ORDER BY person_id"))
        .containsExactly(
            Map.of("person_id", 172, "date_text", "1954"),
            Map.of("person_id", 175, "date_text", "1964"),
            Map.of("person_id", 202, "date_text", "1927"));
  }

  @Test
  void legacyDatesBecomeGedcomDates() {
    assertThat(dateOf(70, "DEAT")).isEqualTo("BET 1731 AND 1733");
    assertThat(dateOf(1, "DEAT")).isEqualTo("AFT 1501");
    assertThat(dateOf(8, "DEAT")).isEqualTo("ABT 1599");
    assertThat(dateOf(58, "BIRT")).isEqualTo("APR 1570");
    assertThat(dateOf(54, "DEAT")).isEqualTo("BEF 1564");
    assertThat(
            jdbc.queryForMap(
                "SELECT date_text, description FROM gen_event WHERE person_id = 13 AND type = 'DEAT'"))
        .containsEntry("date_text", null)
        .containsEntry("description", "young");
  }

  private String dateOf(int personId, String type) {
    return jdbc.queryForObject(
        "SELECT date_text FROM gen_event WHERE person_id = ? AND type = ?",
        String.class,
        personId,
        type);
  }

  @Test
  void theReviewListNamesTheKnownConflicts() {
    List<String> review =
        jdbc.queryForList(
            "SELECT needs_review FROM gen_person WHERE needs_review IS NOT NULL", String.class);
    assertThat(review).isNotEmpty();
    int mariaAntonia = personNamed("Maria Antonia", "zu Salm-Neuburg");
    assertThat(
            jdbc.queryForObject(
                "SELECT needs_review FROM gen_person WHERE id = ?", String.class, mariaAntonia))
        .contains("conflicting birth dates: 16 APR 1766 vs 16 APR 1776");
    assertThat(review).anyMatch(r -> r.contains("uncertain name: z Gutštejna?"));
    assertThat(review).anyMatch(r -> r.contains("date not understood: 31.7./12.11.1787"));
  }

  @Test
  void everyImportedNameAndEventCitesTheLegacyFile() {
    long source =
        jdbc.queryForObject(
            "SELECT id FROM gen_source WHERE title = ?",
            Long.class,
            "genealogy.euweb.cz Czernin pages (legacy text file)");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_event e WHERE NOT EXISTS (SELECT 1 FROM gen_citation c"
                    + " WHERE c.event_id = e.id AND c.source_id = ? AND c.quality = 1)",
                Integer.class,
                source))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_name n WHERE NOT EXISTS (SELECT 1 FROM gen_citation c"
                    + " WHERE c.name_id = n.id AND c.source_id = ? AND c.quality = 1)",
                Integer.class,
                source))
        .isZero();
  }

  @Test
  void theLegacyLineIsKept() {
    assertThat(
            jdbc.queryForObject(
                "SELECT legacy_entry FROM gen_person WHERE id = ?", String.class, ALEXANDER))
        .startsWith("D6. Alexander Friedrich Josef Paul Maria, *Wien 30.4.1913");
  }

  @Test
  void nobodyBornBefore1926IsLivingAndTheLivingHaveNoDeath() {
    assertThat(jdbc.queryForObject("SELECT living FROM gen_person WHERE id = 324", Boolean.class))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT living FROM gen_person WHERE id = ?", Boolean.class, ALEXANDER))
        .isFalse();
  }

  @Test
  void personReferencesAreForeignKeys() {
    assertThat(
            jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conname IN"
                    + " ('page_person_match_person_id_fkey', 'app_user_family_tree_person_id_fkey')"
                    + " ORDER BY conname",
                String.class))
        .containsExactly("app_user_family_tree_person_id_fkey", "page_person_match_person_id_fkey");
  }
}
```

Create `backend/src/test/java/place/icomb/archiver/genealogy/GenealogyReferencesMigrationTest.java`:

```java
package place.icomb.archiver.genealogy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.genealogy.legacy.V22__Import_legacy_genealogy;

/**
 * V23 against the rows production may hold: matches and users naming a folded section root, or
 * nobody at all. The application starts at V22; the rows are planted; then V23 runs.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.flyway.target=22")
class GenealogyReferencesMigrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  private long page(long record, int seq) {
    long attachment =
        jdbc.queryForObject(
            "INSERT INTO attachment (record_id, role, path, mime) VALUES (?, 'page_image', ?,"
                + " 'image/jpeg') RETURNING id",
            Long.class,
            record,
            "v23-" + seq + ".jpg");
    return jdbc.queryForObject(
        "INSERT INTO page (record_id, seq, attachment_id) VALUES (?, ?, ?) RETURNING id",
        Long.class,
        record,
        seq,
        attachment);
  }

  private long user(String name, Integer personId) {
    return jdbc.queryForObject(
        "INSERT INTO app_user (display_name, role, family_tree_person_id) VALUES (?, 'user', ?)"
            + " RETURNING id",
        Long.class,
        name,
        personId);
  }

  @Test
  void referencesToTheFoldedRootsMoveAndReferencesToNobodyGo() {
    long record = GenealogyFixtures.record(jdbc);
    long first = page(record, 1);
    long second = page(record, 2);
    jdbc.update(
        "INSERT INTO page_person_match (page_id, person_id, person_name, score) VALUES"
            + " (?, 122, 'root', 0.5), (?, 76, 'twin', 0.6), (?, 227, 'root', 0.4),"
            + " (?, 999999, 'nobody', 0.1)",
        first,
        first,
        second,
        second);
    long toRoot = user("v23 root", 122);
    long toNobody = user("v23 nobody", 999999);
    long toAlexander = user("v23 alexander", 322);

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .javaMigrations(new V22__Import_legacy_genealogy())
        .load()
        .migrate();

    assertThat(
            jdbc.queryForList(
                "SELECT person_id, person_name FROM page_person_match WHERE page_id = ?", first))
        .containsExactly(Map.of("person_id", 76, "person_name", "twin"));
    assertThat(
            jdbc.queryForList(
                "SELECT person_id FROM page_person_match WHERE page_id = ?", Integer.class, second))
        .containsExactly(80);
    assertThat(familyTreePerson(toRoot)).isEqualTo(76);
    assertThat(familyTreePerson(toNobody)).isNull();
    assertThat(familyTreePerson(toAlexander)).isEqualTo(322);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO page_person_match (page_id, person_id, person_name, score)"
                        + " VALUES (?, 999999, 'nobody', 0.1)",
                    second))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private Integer familyTreePerson(long userId) {
    return jdbc.queryForObject(
        "SELECT family_tree_person_id FROM app_user WHERE id = ?", Integer.class, userId);
  }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd backend && ./gradlew test --tests '*LegacyImportTest' --tests '*GenealogyReferencesMigrationTest'`

Expected: FAIL: compilation error `cannot find symbol: class V22__Import_legacy_genealogy`

- [ ] **Step 3: Move the legacy file to where the migration reads it**

Run: `mkdir -p backend/src/main/resources/db/legacy && git mv backend/src/main/resources/czernin-genealogy.txt backend/src/main/resources/db/legacy/czernin-genealogy.txt`

`FamilyTreeService` still reads it until Task 6. Point it at the new path:

In `backend/src/main/java/place/icomb/archiver/service/FamilyTreeService.java`, replace:

```java
    var resource = new ClassPathResource("czernin-genealogy.txt");
```

with:

```java
    var resource = new ClassPathResource("db/legacy/czernin-genealogy.txt");
```

- [ ] **Step 4: Write the tree parser, the import, the migration bean and V23**

`LegacyTreeParser` is `FamilyTreeService.parse`, `isSkipLine`, `countLeadingSpaces`, the name part of `parsePerson` and `mergeCrossSections` (lines 267–509 of `FamilyTreeService.java`), moved unchanged so that ids come out exactly as before. Two things are added, neither of which affects numbering: it records the marriage a child is from (`[1m.]` prefixes and `--- 1m children: ---` headers), and it keeps the folded roots in `people` and reports them in `mergedInto` instead of deleting them.

Create `backend/src/main/java/place/icomb/archiver/genealogy/legacy/LegacyTreeParser.java`:

```java
package place.icomb.archiver.genealogy.legacy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parse that {@code FamilyTreeService} ran on every startup until the tree moved into the
 * database, kept so the one-time import numbers every person exactly as the in-memory tree did.
 *
 * <p>Ids are parse-order counters over every person line, the two section roots included; the roots
 * of CZERNIN 2 and 3 repeat people already in CZERNIN 1 (I5 and I9) and are folded into them.
 */
public final class LegacyTreeParser {

  public static final class LegacyPerson {
    public int id;
    public String name;
    public String fullEntry;
    public String section;
    public String code;
    public String parentCode;
    public int depth;
    public LegacyPerson parent;
    public final List<LegacyPerson> children = new ArrayList<>();
    public Integer marriageHint;

    public String fullCode() {
      if (parentCode != null && code != null) return parentCode + "." + code;
      if (code != null) return code;
      return "";
    }
  }

  /**
   * {@code people} holds every parsed line; {@code mergedInto} maps each folded root to its twin.
   */
  public record LegacyTree(List<LegacyPerson> people, Map<Integer, Integer> mergedInto) {}

  private static final Pattern CODE_PREFIX = Pattern.compile("^([A-Z]\\d+)\\.\\s+");
  private static final Pattern HINT_PREFIX = Pattern.compile("^\\[(\\d)m\\.?\\]");
  private static final Pattern CHILDREN_OF_MARRIAGE = Pattern.compile("^---\\s*(\\d)m children");

  private LegacyTreeParser() {}

  public static LegacyTree parse(List<String> lines) {
    List<LegacyPerson> people = new ArrayList<>();
    String section = null;
    Map<Integer, LegacyPerson> depthStack = new HashMap<>();
    Map<Integer, Integer> marriageOfChildrenAtDepth = new HashMap<>();
    int nextId = 1;

    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) continue;
      Matcher childrenOf = CHILDREN_OF_MARRIAGE.matcher(trimmed);
      if (childrenOf.find()) {
        marriageOfChildrenAtDepth.put(
            countLeadingSpaces(line) / 2, Integer.parseInt(childrenOf.group(1)));
        continue;
      }
      if (isSkipLine(trimmed)) continue;
      if (trimmed.startsWith("CZERNIN 1")) {
        section = "CZERNIN 1";
        depthStack.clear();
        continue;
      }
      if (trimmed.startsWith("CZERNIN 2")) {
        section = "CZERNIN 2";
        depthStack.clear();
        continue;
      }
      if (trimmed.startsWith("CZERNIN 3")) {
        section = "CZERNIN 3";
        depthStack.clear();
        continue;
      }
      if (section == null) continue;

      int depth = countLeadingSpaces(line) / 2;
      LegacyPerson p = parsePerson(trimmed, section, depth, nextId);
      if (p == null) continue;
      nextId++;
      if (p.marriageHint == null) {
        p.marriageHint = marriageOfChildrenAtDepth.get(depth);
      }

      if (depth > 0) {
        LegacyPerson parent = depthStack.get(depth - 1);
        if (parent != null) {
          p.parent = parent;
          parent.children.add(p);
          p.parentCode = parent.fullCode();
        }
      }
      depthStack.put(depth, p);
      depthStack.entrySet().removeIf(e -> e.getKey() > depth);
      marriageOfChildrenAtDepth.keySet().removeIf(d -> d > depth);
      people.add(p);
    }

    return new LegacyTree(List.copyOf(people), mergeCrossSections(people));
  }

  private static boolean isSkipLine(String trimmed) {
    return trimmed.startsWith("=")
        || trimmed.startsWith("Source:")
        || trimmed.startsWith("Notation:")
        || trimmed.startsWith("Indentation")
        || trimmed.startsWith("END OF FILE")
        || trimmed.startsWith("CZERNIN FAMILY")
        || trimmed.startsWith("---")
        || trimmed.contains("THIS IS ALEXANDER");
  }

  private static int countLeadingSpaces(String line) {
    int n = 0;
    for (int i = 0; i < line.length() && line.charAt(i) == ' '; i++) n++;
    return n;
  }

  private static LegacyPerson parsePerson(String line, String section, int depth, int id) {
    LegacyPerson p = new LegacyPerson();
    p.id = id;
    p.section = section;
    p.depth = depth;
    p.fullEntry = line;

    String rest = line;
    Matcher cm = CODE_PREFIX.matcher(rest);
    if (cm.find()) {
      p.code = cm.group(1);
      rest = rest.substring(cm.end());
    }
    if (rest.startsWith("[")) {
      Matcher hint = HINT_PREFIX.matcher(rest);
      if (hint.find()) {
        p.marriageHint = Integer.parseInt(hint.group(1));
      }
      int bracket = rest.indexOf(']');
      if (bracket > 0) {
        rest = rest.substring(bracket + 1).trim();
      }
    }

    p.name = legacyName(line);
    if (p.name.isEmpty()) return null;
    return p;
  }

  /**
   * The name the in-memory tree gave a line: after the code and any {@code [1m.]}, up to the first
   * comma, semicolon or star, without a {@code >>> CONTINUES} note.
   */
  public static String legacyName(String line) {
    String rest = CODE_PREFIX.matcher(line.trim()).replaceFirst("");
    if (rest.startsWith("[")) {
      int bracket = rest.indexOf(']');
      if (bracket > 0) {
        rest = rest.substring(bracket + 1).trim();
      }
    }
    int nameEnd = rest.length();
    for (int i = 0; i < rest.length(); i++) {
      char c = rest.charAt(i);
      if (c == ',' || c == ';' || c == '*') {
        nameEnd = i;
        break;
      }
    }
    String name = rest.substring(0, nameEnd).trim();
    int contIdx = name.indexOf(">>>");
    return contIdx >= 0 ? name.substring(0, contIdx).trim() : name;
  }

  private static Map<Integer, Integer> mergeCrossSections(List<LegacyPerson> people) {
    LegacyPerson i5 = null, i9 = null, c2root = null, c3root = null;
    for (LegacyPerson p : people) {
      if ("CZERNIN 1".equals(p.section)) {
        if (p.fullEntry.contains(">>> CONTINUES IN CZERNIN 2")) i5 = p;
        if (p.fullEntry.contains(">>> CONTINUES IN CZERNIN 3")) i9 = p;
      }
      if ("CZERNIN 2".equals(p.section) && p.depth == 0 && p.parent == null) c2root = p;
      if ("CZERNIN 3".equals(p.section) && p.depth == 0 && p.parent == null) c3root = p;
    }
    Map<Integer, Integer> merged = new LinkedHashMap<>();
    fold(c2root, i5, merged);
    fold(c3root, i9, merged);
    return merged;
  }

  private static void fold(LegacyPerson root, LegacyPerson twin, Map<Integer, Integer> merged) {
    if (root == null || twin == null) return;
    for (LegacyPerson child : root.children) {
      child.parent = twin;
      twin.children.add(child);
    }
    merged.put(root.id, twin.id);
  }
}
```

Create `backend/src/main/java/place/icomb/archiver/genealogy/legacy/LegacyImport.java`:

```java
package place.icomb.archiver.genealogy.legacy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import place.icomb.archiver.genealogy.GedcomDate;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Adoption;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.AdoptionKind;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Descriptor;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Event;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Marriage;
import place.icomb.archiver.genealogy.legacy.LegacyFacts.Spouse;
import place.icomb.archiver.genealogy.legacy.LegacyTreeParser.LegacyPerson;
import place.icomb.archiver.genealogy.legacy.LegacyTreeParser.LegacyTree;

/**
 * Writes the legacy genealogy file into the {@code gen_*} tables, once, as migration V22.
 *
 * <p>Ids 1-348 are the parse-order ids of {@link LegacyTreeParser}; spouses, who were text inside a
 * line, become people numbered from 349. Every name and event cites the legacy file at quality 1,
 * and whatever the notation leaves unsettled is written to {@code needs_review}.
 */
public final class LegacyImport {

  public static final String SOURCE_TITLE = "genealogy.euweb.cz Czernin pages (legacy text file)";
  public static final String SOURCE_URL = "https://genealogy.euweb.cz/bohemia/czernin1.html";

  /** Nobody born before 1926 is taken to be living; the file records no deaths after 2006. */
  static final int FIRST_LIVING_BIRTH_YEAR = 1926;

  public static final String FAMILY_SURNAME = "Czernin";
  private static final Set<String> FAMILY_TITLES =
      Set.of("Graf", "Gf", "Freiherr", "Frhr", "Fürst", "Fst", "Prinz", "Pr");

  private final JdbcTemplate jdbc;
  private final Map<String, Long> places = new HashMap<>();
  private final Map<Integer, List<String>> review = new LinkedHashMap<>();
  private final Map<Integer, Map<Integer, Long>> couplesByOrder = new HashMap<>();
  private final Map<Long, Integer> spouseOfCouple = new HashMap<>();
  private final Map<Integer, Map<Integer, Integer>> spouseByOrder = new HashMap<>();
  private final Map<String, Integer> eventOrder = new HashMap<>();
  private final Map<Integer, String> surnames = new HashMap<>();
  private final Map<Integer, List<String>> otherSurnames = new HashMap<>();
  private final Map<Integer, List<String>> spouseSurnames = new HashMap<>();
  private long sourceId;

  public LegacyImport(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void run(List<String> lines) {
    LegacyTree tree = LegacyTreeParser.parse(lines);
    sourceId =
        jdbc.queryForObject(
            "INSERT INTO gen_source (title, url, notes) VALUES (?, ?, ?) RETURNING id",
            Long.class,
            SOURCE_TITLE,
            SOURCE_URL,
            "Imported by migration V22 from czernin-genealogy.txt; each person's line is kept"
                + " in gen_person.legacy_entry.");

    Map<Integer, LegacyFacts> facts = new LinkedHashMap<>();
    Set<String> forenames = forenames(tree);
    for (LegacyPerson p : tree.people()) {
      LegacyFacts f = LegacyLineParser.parse(p.fullEntry);
      facts.put(p.id, f);
      String[] name = familyName(p, forenames);
      surnames.put(p.id, name[2]);
      otherSurnames.put(p.id, f.otherSurnames());
      jdbc.update(
          "INSERT INTO gen_person (id, legacy_entry, legacy_section, legacy_code, legacy_depth,"
              + " notes) VALUES (?, ?, ?, ?, ?, ?)",
          p.id,
          p.fullEntry,
          p.section,
          p.fullCode(),
          p.depth,
          f.notes().isEmpty() ? null : String.join("\n", f.notes()));
      long nameId =
          jdbc.queryForObject(
              "INSERT INTO gen_name (person_id, type, prefix, given, surname, nickname,"
                  + " is_primary) VALUES (?, 'birth', ?, ?, ?, ?, true) RETURNING id",
              Long.class,
              p.id,
              name[0],
              name[1],
              name[2],
              f.nickname());
      cite("name_id", nameId);
      if (tree.mergedInto().containsKey(p.id)) {
        continue;
      }
      review.computeIfAbsent(p.id, k -> new ArrayList<>()).addAll(f.review());
      for (String surname : f.otherSurnames()) {
        long other =
            jdbc.queryForObject(
                "INSERT INTO gen_name (person_id, type, given, surname, sort_order)"
                    + " VALUES (?, 'other', ?, ?, 1) RETURNING id",
                Long.class,
                p.id,
                p.name,
                surname);
        cite("name_id", other);
      }
      personEvents(p.id, f.birth(), f.death(), f.burial(), f.descriptors());
      for (Adoption a : f.adoptions()) {
        if (a.kind() == AdoptionKind.ADOPTED) {
          event(p.id, null, "ADOP", new Event(a.date(), null, a.text()));
        }
      }
      marriages(p.id, f.marriages());
    }

    for (LegacyPerson child : tree.people()) {
      if (tree.mergedInto().containsKey(child.id) || child.parent == null) continue;
      LegacyPerson parent = child.parent;
      Integer order =
          child.marriageHint != null ? child.marriageHint : facts.get(parent.id).allChildrenBy();
      Map<Integer, Long> couples = couplesByOrder.getOrDefault(parent.id, Map.of());
      Set<Long> distinct = new LinkedHashSet<>(couples.values());
      Long couple = null;
      if (order != null) {
        couple = couples.get(order);
      } else if (distinct.size() == 1) {
        couple = distinct.iterator().next();
      } else if (distinct.size() > 1) {
        flag(parent.id, "which marriage each child is from is not recorded");
      }
      link(parent.id, child.id, "birth", couple);
      if (couple != null) {
        link(spouseOfCouple.get(couple), child.id, "birth", couple);
      }
    }

    adoptions(tree, facts);
    conflicts(tree, facts);

    for (var e : review.entrySet()) {
      if (!e.getValue().isEmpty()) {
        jdbc.update(
            "UPDATE gen_person SET needs_review = ? WHERE id = ?",
            String.join("; ", new LinkedHashSet<>(e.getValue())),
            e.getKey());
      }
    }
    for (var e : tree.mergedInto().entrySet()) {
      jdbc.update("UPDATE gen_person SET merged_into = ? WHERE id = ?", e.getValue(), e.getKey());
    }
    jdbc.update(
        """
        UPDATE gen_person p SET living = true
        WHERE p.merged_into IS NULL
          AND NOT EXISTS (SELECT 1 FROM gen_event d WHERE d.person_id = p.id AND d.type = 'DEAT')
          AND EXISTS (SELECT 1 FROM gen_event b WHERE b.person_id = p.id AND b.type = 'BIRT'
                      AND b.date_from >= make_date(?, 1, 1))
        """,
        FIRST_LIVING_BIRTH_YEAR);
    jdbc.execute("SELECT setval('gen_person_id_seq', (SELECT max(id) FROM gen_person))");
  }

  /**
   * Prefix, given names and surname of a family member's primary name. The family surname is
   * Czernin: a legacy name that already holds a Czernin form ("Humprecht Czernin z Chudenic", "…
   * Czernin-Kinsky (adopted 1954)") is split at it, titles before it going to the prefix and a
   * trailing remark in parentheses dropped. A descendant through a daughter whose legacy name
   * already ends in the father's line's surname ("Charlotte Sonia Phillips") keeps that surname.
   * Every other legacy name is the given names, and the surname is Czernin.
   */
  private String[] familyName(LegacyPerson p, Set<String> forenames) {
    String name = p.name;
    int czernin = name.indexOf("Czernin");
    if (czernin >= 0) {
      String surname = name.substring(czernin).replaceAll("(\\s*\\([^)]*\\))+\\s*$", "").trim();
      List<String> prefix = new ArrayList<>();
      List<String> given = new ArrayList<>();
      for (String word : name.substring(0, czernin).trim().split("\\s+")) {
        if (word.isEmpty()) continue;
        (FAMILY_TITLES.contains(word) ? prefix : given).add(word);
      }
      return new String[] {
        prefix.isEmpty() ? null : String.join(" ", prefix),
        given.isEmpty() ? null : String.join(" ", given),
        surname
      };
    }
    String[] words = name.split("\\s+");
    String last = words[words.length - 1];
    if (words.length > 1 && !forenames.contains(last) && p.parent != null && inherits(p, last)) {
      return new String[] {null, name.substring(0, name.lastIndexOf(last)).trim(), last};
    }
    return new String[] {null, name, FAMILY_SURNAME};
  }

  private boolean inherits(LegacyPerson p, String surname) {
    int parent = p.parent.id;
    return (surname.equals(surnames.get(parent)) && !FAMILY_SURNAME.equals(surname))
        || spouseSurnames.getOrDefault(parent, List.of()).contains(surname)
        || otherSurnames.getOrDefault(parent, List.of()).contains(surname);
  }

  /** Every word used as a forename: all but the last of each legacy name, and one-word names. */
  private static Set<String> forenames(LegacyTree tree) {
    Set<String> out = new HashSet<>();
    for (LegacyPerson p : tree.people()) {
      String[] words = p.name.split("\\s+");
      out.addAll(Arrays.asList(words).subList(0, Math.max(1, words.length - 1)));
    }
    return out;
  }

  private void personEvents(
      int personId, Event birth, Event death, Event burial, List<Descriptor> descriptors) {
    event(personId, null, "BIRT", birth);
    event(personId, null, "DEAT", death);
    event(personId, null, "BURI", burial);
    for (Descriptor d : descriptors) {
      event(personId, null, d.tag(), new Event(d.date(), null, d.text()));
    }
  }

  private void marriages(int personId, List<Marriage> marriages) {
    Map<Integer, Long> couples = couplesByOrder.computeIfAbsent(personId, k -> new HashMap<>());
    Map<Integer, Integer> spouses = spouseByOrder.computeIfAbsent(personId, k -> new HashMap<>());
    for (Marriage m : marriages) {
      Long couple;
      if (m.sameAsOrder() != null && couples.containsKey(m.sameAsOrder())) {
        couple = couples.get(m.sameAsOrder());
        spouses.put(m.order(), spouses.get(m.sameAsOrder()));
      } else if (m.spouse() == null) {
        flag(personId, "marriage " + m.order() + " names no spouse");
        continue;
      } else {
        int spouseId = spouse(m.spouse());
        spouseSurnames
            .computeIfAbsent(personId, k -> new ArrayList<>())
            .add(m.spouse().name().surname());
        couple =
            jdbc.queryForObject(
                "INSERT INTO gen_couple (person1_id, person2_id, type, sort_order)"
                    + " VALUES (?, ?, 'marriage', ?) RETURNING id",
                Long.class,
                personId,
                spouseId,
                m.order());
        spouseOfCouple.put(couple, spouseId);
        spouses.put(m.order(), spouseId);
      }
      couples.put(m.order(), couple);
      event(null, couple, "MARR", m.wedding());
      if (m.endTag() != null) {
        event(null, couple, m.endTag(), m.end() == null ? new Event(null, null, null) : m.end());
      }
    }
  }

  private int spouse(Spouse s) {
    int id =
        jdbc.queryForObject("INSERT INTO gen_person DEFAULT VALUES RETURNING id", Integer.class);
    long nameId =
        jdbc.queryForObject(
            "INSERT INTO gen_name (person_id, type, prefix, given, surname, nickname, is_primary)"
                + " VALUES (?, 'birth', ?, ?, ?, ?, true) RETURNING id",
            Long.class,
            id,
            s.name().prefix(),
            s.name().given(),
            s.name().surname(),
            s.name().nickname());
    cite("name_id", nameId);
    personEvents(id, s.birth(), s.death(), null, s.descriptors());
    review.computeIfAbsent(id, k -> new ArrayList<>()).addAll(s.review());
    return id;
  }

  private void adoptions(LegacyTree tree, Map<Integer, LegacyFacts> facts) {
    Map<Integer, String> names = new HashMap<>();
    for (LegacyPerson p : tree.people()) names.put(p.id, p.name);
    List<Map.Entry<Integer, Adoption>> adopters = new ArrayList<>();
    List<Map.Entry<Integer, Adoption>> adoptees = new ArrayList<>();
    for (var e : facts.entrySet()) {
      if (tree.mergedInto().containsKey(e.getKey())) continue;
      for (Adoption a : e.getValue().adoptions()) {
        if (a.kind() == AdoptionKind.ADOPTED_CHILD) adopters.add(Map.entry(e.getKey(), a));
        if (a.kind() == AdoptionKind.ADOPTED_BY) adoptees.add(Map.entry(e.getKey(), a));
      }
    }
    for (var adopter : adopters) {
      Map.Entry<Integer, Adoption> match = null;
      for (var adoptee : adoptees) {
        boolean sameYear = Objects.equals(adopter.getValue().date(), adoptee.getValue().date());
        boolean namesAgree =
            firstWord(adopter.getValue().otherName()).equals(firstWord(names.get(adoptee.getKey())))
                && firstWord(adoptee.getValue().otherName())
                    .equals(firstWord(names.get(adopter.getKey())));
        if (sameYear && namesAgree) {
          match = adoptee;
          break;
        }
      }
      if (match != null) {
        adoptees.remove(match);
        link(adopter.getKey(), match.getKey(), "adopted", null);
        event(
            match.getKey(),
            null,
            "ADOP",
            new Event(match.getValue().date(), null, match.getValue().text()));
      } else {
        event(
            adopter.getKey(),
            null,
            "EVEN",
            new Event(adopter.getValue().date(), null, adopter.getValue().text()));
        flag(adopter.getKey(), "adopted child not found in the tree");
      }
    }
    for (var adoptee : adoptees) {
      event(
          adoptee.getKey(),
          null,
          "ADOP",
          new Event(adoptee.getValue().date(), null, adoptee.getValue().text()));
    }
  }

  /** The folded section roots repeat their twin's line; where the two disagree, say so. */
  private void conflicts(LegacyTree tree, Map<Integer, LegacyFacts> facts) {
    for (var e : tree.mergedInto().entrySet()) {
      LegacyFacts root = facts.get(e.getKey());
      LegacyFacts twin = facts.get(e.getValue());
      String where = "CZERNIN header line vs person " + e.getValue();
      compare(e.getValue(), "birth", root.birth(), twin.birth(), where);
      compare(e.getValue(), "death", root.death(), twin.death(), where);
      Map<Integer, Integer> spouses = spouseByOrder.getOrDefault(e.getValue(), Map.of());
      for (Marriage rm : root.marriages()) {
        for (Marriage tm : twin.marriages()) {
          if (rm.order() != tm.order() || rm.spouse() == null || tm.spouse() == null) continue;
          Integer spouseId = spouses.get(tm.order());
          compare(spouseId, "birth", rm.spouse().birth(), tm.spouse().birth(), where);
          compare(spouseId, "death", rm.spouse().death(), tm.spouse().death(), where);
        }
      }
    }
  }

  private void compare(Integer personId, String what, Event header, Event line, String where) {
    String a = header == null ? null : header.date();
    String b = line == null ? null : line.date();
    if (personId != null && !Objects.equals(a, b)) {
      flag(personId, "conflicting %s dates: %s vs %s (%s)".formatted(what, a, b, where));
    }
  }

  private void event(Integer personId, Long coupleId, String tag, Event e) {
    if (e == null) return;
    GedcomDate date = GedcomDate.parse(e.date());
    String key = personId != null ? "p" + personId : "c" + coupleId;
    int order = eventOrder.merge(key, 1, Integer::sum);
    long id =
        jdbc.queryForObject(
            "INSERT INTO gen_event (person_id, couple_id, type, date_text, date_from, date_to,"
                + " place_id, description, sort_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                + " RETURNING id",
            Long.class,
            personId,
            coupleId,
            tag,
            date == null ? null : date.text(),
            date == null ? null : date.from(),
            date == null ? null : date.to(),
            place(e.place()),
            e.description(),
            order);
    cite("event_id", id);
  }

  private Long place(String name) {
    if (name == null) return null;
    return places.computeIfAbsent(
        name,
        n ->
            jdbc.queryForObject(
                "INSERT INTO gen_place (name) VALUES (?) RETURNING id", Long.class, n));
  }

  private void link(int parentId, int childId, String type, Long coupleId) {
    jdbc.update(
        "INSERT INTO gen_parent_child (parent_id, child_id, type, couple_id) VALUES (?, ?, ?, ?)"
            + " ON CONFLICT (parent_id, child_id) DO NOTHING",
        parentId,
        childId,
        type,
        coupleId);
  }

  private void cite(String column, long id) {
    jdbc.update(
        "INSERT INTO gen_citation (source_id, quality, " + column + ") VALUES (?, 1, ?)",
        sourceId,
        id);
  }

  private void flag(int personId, String reason) {
    review.computeIfAbsent(personId, k -> new ArrayList<>()).add(reason);
  }

  private static String firstWord(String s) {
    return s == null ? "" : s.trim().split("\\s+")[0];
  }
}
```

Create `backend/src/main/java/place/icomb/archiver/genealogy/legacy/V22__Import_legacy_genealogy.java`:

```java
package place.icomb.archiver.genealogy.legacy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/**
 * Migration V22: the one-time import of the legacy genealogy file into the tables V21 made.
 *
 * <p>A Spring bean so that Spring Boot hands it to Flyway with the SQL migrations; Flyway's own
 * classpath scan is not relied on inside the packaged jar. The file is read from {@code
 * db/legacy/czernin-genealogy.txt} and nothing else reads it.
 */
@Component
public class V22__Import_legacy_genealogy extends BaseJavaMigration {

  static final String FILE = "/db/legacy/czernin-genealogy.txt";

  @Override
  public void migrate(Context context) throws Exception {
    List<String> lines;
    try (InputStream in = V22__Import_legacy_genealogy.class.getResourceAsStream(FILE)) {
      lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    }
    JdbcTemplate jdbc =
        new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true));
    new LegacyImport(jdbc).run(lines);
  }
}
```

Create `backend/src/main/resources/db/migration/V23__genealogy_person_references.sql`:

```sql
-- page_person_match and app_user named people by the in-memory tree's ids with nothing to hold
-- them. Rows naming a folded section root (or any person V22 merged) move to the person it was
-- merged into; rows naming nobody at all are dropped (a match is a cache) or cleared (a user's
-- "this is me"); then the references become foreign keys.
DELETE FROM page_person_match m
USING gen_person p
WHERE m.person_id = p.id AND p.merged_into IS NOT NULL
  AND EXISTS (SELECT 1 FROM page_person_match k
              WHERE k.page_id = m.page_id AND k.person_id = p.merged_into);

UPDATE page_person_match m SET person_id = p.merged_into
FROM gen_person p
WHERE m.person_id = p.id AND p.merged_into IS NOT NULL;

DELETE FROM page_person_match m
WHERE NOT EXISTS (SELECT 1 FROM gen_person p WHERE p.id = m.person_id);

UPDATE app_user u SET family_tree_person_id = p.merged_into
FROM gen_person p
WHERE u.family_tree_person_id = p.id AND p.merged_into IS NOT NULL;

UPDATE app_user SET family_tree_person_id = NULL
WHERE family_tree_person_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM gen_person p WHERE p.id = app_user.family_tree_person_id);

ALTER TABLE page_person_match
    ADD CONSTRAINT page_person_match_person_id_fkey
    FOREIGN KEY (person_id) REFERENCES gen_person (id);

ALTER TABLE app_user
    ADD CONSTRAINT app_user_family_tree_person_id_fkey
    FOREIGN KEY (family_tree_person_id) REFERENCES gen_person (id) ON DELETE SET NULL;
```

- [ ] **Step 5: Run them to see them pass, with the in-memory tree still green**

Run: `cd backend && ./gradlew test --tests '*LegacyImportTest' --tests '*GenealogyReferencesMigrationTest' --tests '*FamilyTreeGoldenTest' --tests '*GenealogySchemaTest'`

Expected: PASS (13 + 1 + 5 + 9 tests)

- [ ] **Step 6: Format**

Run: `cd backend && ./gradlew spotlessApply`

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A backend/src/main/resources/czernin-genealogy.txt backend/src/main/resources/db backend/src/main/java/place/icomb/archiver/genealogy/legacy backend/src/main/java/place/icomb/archiver/service/FamilyTreeService.java backend/src/test/java/place/icomb/archiver/genealogy/LegacyImportTest.java backend/src/test/java/place/icomb/archiver/genealogy/GenealogyReferencesMigrationTest.java backend/src/test/java/place/icomb/archiver/service/FamilyTreeGolden.java
git commit -m "feat: V22 imports the legacy genealogy file, V23 makes person references foreign keys"
```

---

### Task 6: FamilyTreeService becomes a read model over the tables

**Files:**
- Rewrite: `backend/src/main/java/place/icomb/archiver/service/FamilyTreeService.java`
- Create: `backend/src/main/java/place/icomb/archiver/genealogy/Rows.java`
- Create: `backend/src/main/java/place/icomb/archiver/genealogy/GenealogyReadService.java`
- Rewrite: `backend/src/main/java/place/icomb/archiver/controller/FamilyTreeController.java` (removes `/reload` and `/invalidate-matches`, lines 175–186; adds `all` to search and the stored facts to `/person/{id}`)
- Modify: `backend/src/test/java/place/icomb/archiver/controller/FamilyTreeControllerTest.java:271-end` (the two reload tests)
- Modify: `backend/src/main/java/place/icomb/archiver/service/PersonMatchService.java:230,498,526-527,531` (`person.name` → `person.matchName()`; nothing else)
- Modify: `backend/src/test/java/place/icomb/archiver/service/PersonMatchServiceTest.java:24-26` (setup only; no assertion changes)
- Create: `backend/src/test/java/place/icomb/archiver/service/FamilyTreeReadModelTest.java`
- Delete: `backend/src/test/java/place/icomb/archiver/service/FamilyTreeGoldenTest.java`, `backend/src/test/java/place/icomb/archiver/service/FamilyTreeParseTest.java`

**Interfaces:**
- Consumes: the tables and data from Tasks 2 and 5; `FamilyTreeGolden` (Task 1).
- Produces:
  - `FamilyTreeService`:
    - Construction: `FamilyTreeService(JdbcTemplate)`, and the package-private `static FamilyTreeService forPeople(List<Person> people)` for tests. `void invalidate()` drops the cached tree; the next read reloads it.
    - Unchanged: `search(String, int)`, `relate(int)`, `relate(int, int)`, `Person getPerson(int)` (now resolving merged ids), `List<Map<String, Object>> getLifeEvents(Person)`, `getAllPeople()`, `getPersonCount()`, and the package-private `normalize`, `tokenize`, `levenshtein`, `getTitleWords`.
    - New: `search(String, int, boolean everyone)`. Search scores forenames exactly as before; a query word matching no forename may match the surname exactly, so "czernin jaromir" finds the Jaromírs.
    - `Person.name` is the display name (given + " " + surname; prefix and suffix not shown), used everywhere: REST, MCP, kinship text, the web. New `Person.surname`, and `Person.matchName()`: the legacy name for imported people (their forenames, as matched before), the given names otherwise, falling back to `name` for people built by hand in tests.
    - The `Person`, `SearchResult` and `RelationshipResult` types are unchanged. The no-arg constructor, `reload()`, the parser and `archiver.family-tree.file` are gone.
  - Each `getLifeEvents` item: `type`, `text`, `year`, `id`, `tag`, `date`, `dateFrom`, `dateTo`, `place`, `placeId`, `description`, `coupleId`, `spouseId`, and `citations` (each `{id, sourceId, sourceTitle, pageId, detail, quality}`).
  - `Rows`: `static List<Map<String,Object>> camel(List<Map<String,Object>>)`, `static Map<String,Object> camel(Map<String,Object>)`, `static Map<String,Object> json(String)`, `static String toJson(Map<String,Object>)`.
  - `GenealogyReadService.personDetail(int personId)` returns a `Map` with keys `uid, sex, living, notes, needsReview, names, couples, parentLinks, recordLinks, media, portrait, sources`, or null.
  - REST:
    - `GET /api/family-tree/search?q=&limit=&all=true|false` (default false).
    - `GET /api/family-tree/person/{id}` returns the old keys plus the `personDetail` keys.
    - `POST /api/family-tree/reload` and `POST /api/family-tree/invalidate-matches` now answer 404.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/place/icomb/archiver/service/FamilyTreeReadModelTest.java`:

```java
package place.icomb.archiver.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The database-backed tree answers exactly as the text-file tree did: the golden files were
 * captured from the in-memory tree before the move (see {@link FamilyTreeGolden}).
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class FamilyTreeReadModelTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private FamilyTreeService tree;

  @Test
  void theTreeStillHas346People() {
    assertThat(tree.getAllPeople()).hasSize(346);
  }

  @Test
  void everyPersonMatchesTheGoldenFile() throws Exception {
    FamilyTreeGolden.assertPeopleMatchAfterMove(
        FamilyTreeGolden.people(tree), FamilyTreeGolden.read("people.tsv"));
  }

  @Test
  void kinshipMatchesTheGoldenFile() throws Exception {
    FamilyTreeGolden.assertKinshipMatchAfterMove(
        FamilyTreeGolden.kinship(tree),
        FamilyTreeGolden.read("kinship.tsv"),
        FamilyTreeGolden.read("people.tsv"));
  }

  @Test
  void searchMatchesTheGoldenFile() throws Exception {
    FamilyTreeGolden.assertSearchMatchAfterMove(
        FamilyTreeGolden.search(tree), FamilyTreeGolden.read("search.tsv"));
  }

  @Test
  void theFamilySurnameIsAddedOnceAndOnlyToTheDisplayName() {
    assertThat(tree.getPerson(76).name).isEqualTo("Johann Rudolf Czernin");
    assertThat(tree.getPerson(322).name).isEqualTo("Alexander Friedrich Josef Paul Maria Czernin");
    assertThat(tree.getPerson(1).name).isEqualTo("Humprecht Czernin z Chudenic");
    assertThat(tree.getPerson(76).matchName()).isEqualTo("Johann Rudolf");
    assertThat(tree.getPerson(322).matchName()).isEqualTo("Alexander Friedrich Josef Paul Maria");
    assertThat(tree.getPerson(1).matchName()).isEqualTo("Humprecht Czernin z Chudenic");
  }

  @Test
  void personMatchingSeesTheLegacyNamesExactly() throws Exception {
    java.util.Map<Integer, String> legacy = new java.util.HashMap<>();
    for (String line : FamilyTreeGolden.read("people.tsv")) {
      String[] c = line.split("\t", -1);
      legacy.put(Integer.parseInt(c[0]), c[8]);
    }
    for (var p : tree.getAllPeople()) {
      assertThat(p.matchName()).as("person " + p.id).isEqualTo(legacy.get(p.id));
    }
  }

  @Test
  void czerninPlusAForenameFindsThatForename() {
    var hits = tree.search("czernin jaromir", 5);
    assertThat(hits).isNotEmpty();
    assertThat(hits).allSatisfy(h -> assertThat(h.name()).contains("Jarom").endsWith("Czernin"));
    assertThat(hits.getFirst().score()).isEqualTo(1.0);
    assertThat(tree.search("jaromir", 5))
        .extracting(r -> r.personId())
        .containsExactlyElementsOf(hits.stream().map(r -> r.personId()).toList());
  }
}
```

In `FamilyTreeControllerTest.java`, replace the reload section at the end of the file:

In `backend/src/test/java/place/icomb/archiver/controller/FamilyTreeControllerTest.java`, replace:

```java
  // ── Reload ──

  @Test
  void reloadReturnsCount() throws Exception {
    var req = authedPost(base() + "/reload").build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("status")).isEqualTo("ok");
    assertThat(((Number) body.get("count")).intValue()).isGreaterThan(0);
  }

  @Test
  void invalidateMatchesReturnsOk() throws Exception {
    var req = authedPost(base() + "/invalidate-matches").build();
    var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body.get("status")).isEqualTo("ok");
  }
}
```

with:

```java
  // ── Reload is gone: the tree is the database, and edits go through the admin API ──

  @Test
  void reloadIsGone() throws Exception {
    var resp =
        http.send(authedPost(base() + "/reload").build(), HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(404);
  }

  @Test
  void invalidateMatchesIsGone() throws Exception {
    var resp =
        http.send(
            authedPost(base() + "/invalidate-matches").build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(404);
  }

  // ── Read model over the database ──

  @Test
  void aPersonCarriesTheStoredFacts() throws Exception {
    var resp =
        http.send(authedGet(base() + "/person/322").build(), HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
    assertThat(body)
        .containsKeys(
            "names",
            "events",
            "couples",
            "parentLinks",
            "recordLinks",
            "media",
            "portrait",
            "sources",
            "needsReview");
    assertThat(body.get("spouses")).isEqualTo(List.of("Diana Zannick Hutton/Hulton"));
    List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
    assertThat(events.get(0))
        .containsEntry("type", "birth")
        .containsEntry("tag", "BIRT")
        .containsEntry("date", "30 APR 1913")
        .containsEntry("year", 1913)
        .containsEntry("place", "Wien");
    assertThat(events)
        .anySatisfy(
            e ->
                assertThat(e)
                    .containsEntry("type", "marriage")
                    .containsEntry("date", "9 JUL 1949"));
    assertThat((List<?>) ((Map<String, Object>) events.get(0)).get("citations")).hasSize(1);
  }

  @Test
  void aFoldedSectionRootResolvesToItsTwin() throws Exception {
    var resp =
        http.send(authedGet(base() + "/person/122").build(), HttpResponse.BodyHandlers.ofString());

    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(mapper.readValue(resp.body(), Map.class).get("id")).isEqualTo(76);
  }

  @Test
  void spousesAreFoundOnlyWhenAskedFor() throws Exception {
    var tree =
        http.send(
            authedGet(base() + "/search?q=diana+zannick&limit=3").build(),
            HttpResponse.BodyHandlers.ofString());
    var everyone =
        http.send(
            authedGet(base() + "/search?q=diana+zannick&limit=3&all=true").build(),
            HttpResponse.BodyHandlers.ofString());

    List<Map<String, Object>> inTree =
        mapper.readValue(tree.body(), new TypeReference<List<Map<String, Object>>>() {});
    List<Map<String, Object>> all =
        mapper.readValue(everyone.body(), new TypeReference<List<Map<String, Object>>>() {});
    assertThat(inTree.getFirst().get("personId")).isEqualTo(322);
    assertThat(all.getFirst().get("name")).isEqualTo("Diana Zannick Hutton/Hulton");
  }

  @Test
  void aSpouseIsRelatedByMarriage() throws Exception {
    var search =
        http.send(
            authedGet(base() + "/search?q=diana+zannick&limit=1&all=true").build(),
            HttpResponse.BodyHandlers.ofString());
    int diana =
        ((Number)
                mapper
                    .readValue(search.body(), new TypeReference<List<Map<String, Object>>>() {})
                    .getFirst()
                    .get("personId"))
            .intValue();

    Map<String, Object> spouse =
        mapper.readValue(
            http.send(
                    authedGet(base() + "/relate?personId=" + diana + "&refId=322").build(),
                    HttpResponse.BodyHandlers.ofString())
                .body(),
            Map.class);
    Map<String, Object> inLaw =
        mapper.readValue(
            http.send(
                    authedGet(base() + "/relate?personId=" + diana + "&refId=296").build(),
                    HttpResponse.BodyHandlers.ofString())
                .body(),
            Map.class);
    Map<String, Object> grandmother =
        mapper.readValue(
            http.send(
                    authedGet(base() + "/relate?personId=" + diana + "&refId=324").build(),
                    HttpResponse.BodyHandlers.ofString())
                .body(),
            Map.class);

    assertThat(spouse.get("kinshipLabel")).isEqualTo("spouse");
    assertThat(inLaw.get("kinshipLabel")).isEqualTo("child-in-law");
    assertThat(grandmother.get("kinshipLabel")).isEqualTo("grandparent");
  }
}
```

In `PersonMatchServiceTest.java`, only the construction of the tree changes. It is built from the in-memory people exactly as they were, birth places included:

In `backend/src/test/java/place/icomb/archiver/service/PersonMatchServiceTest.java`, replace:

```java
  static void setup() {
    treeService = new FamilyTreeService();
    treeService.reload();
```

with:

```java
  static void setup() throws Exception {
    treeService = FamilyTreeService.forPeople(FamilyTreeGolden.legacyPeople());
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd backend && ./gradlew test --tests '*FamilyTreeReadModelTest' --tests '*FamilyTreeControllerTest' --tests '*PersonMatchServiceTest'`

Expected: FAIL: compilation error `cannot find symbol: method forPeople(List<Person>)`

- [ ] **Step 3: Rewrite FamilyTreeService as the read model**

The search scoring, kinship labels, ordinal and path-description wording are carried over from the old file character for character. Only the source of the people and the ancestor walk change (see decisions 11–14).

Replace the whole of `backend/src/main/java/place/icomb/archiver/service/FamilyTreeService.java` with:

```java
package place.icomb.archiver.service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import place.icomb.archiver.genealogy.legacy.LegacyTreeParser;

/**
 * The family tree as the rest of the archive reads it: search, kinship and people, over the
 * genealogy tables. {@code GenealogyService} is the only writer and calls {@link #invalidate()}
 * after each commit; the next read rebuilds the tree.
 *
 * <p>"Tree members" are the people the in-memory tree held (a legacy section) and anyone descended
 * from them by birth. Search and {@link #getAllPeople()} cover members only, so person matching and
 * search answer exactly as they did; spouses are reachable by id and by {@code search(q, n, true)}.
 */
@Service
public class FamilyTreeService {

  private static final Set<String> TITLE_WORDS =
      Set.of(
          "count",
          "graf",
          "gf",
          "grafin",
          "gfn",
          "freiherr",
          "frhr",
          "frn",
          "furst",
          "fst",
          "prinz",
          "pr",
          "princess",
          "pss",
          "hon",
          "hon.");

  private static final String ALEXANDER = "Alexander Friedrich Josef Paul Maria";
  private static final String FAMILY_SURNAME = "Czernin";

  private static final Map<String, String> EVENT_WORDS =
      Map.ofEntries(
          Map.entry("BIRT", "birth"),
          Map.entry("CHR", "christening"),
          Map.entry("BAPM", "baptism"),
          Map.entry("DEAT", "death"),
          Map.entry("BURI", "burial"),
          Map.entry("MARR", "marriage"),
          Map.entry("DIV", "divorce"),
          Map.entry("ADOP", "adoption"),
          Map.entry("OCCU", "occupation"),
          Map.entry("TITL", "title"),
          Map.entry("RESI", "residence"),
          Map.entry("NATU", "naturalisation"),
          Map.entry("EMIG", "emigration"),
          Map.entry("IMMI", "immigration"),
          Map.entry("EDUC", "education"),
          Map.entry("EVEN", "event"));

  private final JdbcTemplate jdbc;
  private volatile Snapshot snapshot;

  private record Snapshot(
      List<Person> members,
      Map<Integer, Person> byId,
      Map<Integer, Integer> mergedInto,
      Person alexander,
      Map<Integer, List<Integer>> birthParents,
      Map<Integer, List<Integer>> partners) {}

  @Autowired
  public FamilyTreeService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private FamilyTreeService(Snapshot fixed) {
    this.jdbc = null;
    this.snapshot = fixed;
  }

  /** A tree over a fixed list of people, for tests that need no database. */
  static FamilyTreeService forPeople(List<Person> people) {
    Map<Integer, Person> byId = new LinkedHashMap<>();
    Map<Integer, List<Integer>> parents = new HashMap<>();
    Person alexander = null;
    for (Person p : people) {
      byId.put(p.id, p);
      if (p.parent != null) parents.put(p.id, List.of(p.parent.id));
      if (alexander == null && p.fullEntry != null && p.fullEntry.contains(ALEXANDER)) {
        alexander = p;
      }
    }
    return new FamilyTreeService(
        new Snapshot(List.copyOf(people), byId, Map.of(), alexander, parents, Map.of()));
  }

  /** Drops the cached tree; the next read loads it again. */
  public void invalidate() {
    if (jdbc != null) {
      snapshot = null;
    }
  }

  private Snapshot tree() {
    Snapshot s = snapshot;
    if (s == null) {
      synchronized (this) {
        s = snapshot;
        if (s == null) {
          s = load();
          snapshot = s;
        }
      }
    }
    return s;
  }

  // --- Public API ---

  public List<SearchResult> search(String query, int limit) {
    return search(query, limit, false);
  }

  /** With {@code everyone}, spouses and other people outside the tree are searched too. */
  public List<SearchResult> search(String query, int limit, boolean everyone) {
    if (query == null || query.isBlank()) return List.of();
    List<String> qTokens = tokenize(normalize(query));
    if (qTokens.isEmpty()) return List.of();

    Snapshot t = tree();
    Collection<Person> pool = everyone ? t.byId().values() : t.members();
    List<SearchResult> results = new ArrayList<>();
    for (Person p : pool) {
      double score = score(qTokens, p);
      if (score > 0.1) {
        results.add(
            new SearchResult(
                p.id,
                p.name,
                p.fullEntry,
                p.section,
                p.fullCode(),
                score,
                p.birthYear,
                p.deathYear));
      }
    }
    results.sort(Comparator.comparingDouble(SearchResult::score).reversed());
    return results.size() > limit ? results.subList(0, limit) : results;
  }

  public RelationshipResult relate(int personId) {
    Person alexander = tree().alexander();
    if (alexander == null) return null;
    return relate(personId, alexander.id);
  }

  public RelationshipResult relate(int personId, int referencePersonId) {
    Snapshot t = tree();
    Person person = resolve(t, personId);
    Person ref = resolve(t, referencePersonId);
    if (person == null || ref == null) return null;
    if (person.id == ref.id) {
      String desc =
          ref.id == (t.alexander() != null ? t.alexander().id : -1)
              ? "Alexander Friedrich Josef Paul Maria Czernin, nicknamed Lucki/Papi"
              : "This is " + ref.name;
      return new RelationshipResult(
          person.id,
          person.name,
          "This is " + ref.name,
          desc,
          person.name,
          person.id,
          0,
          0,
          ref.name);
    }
    RelationshipResult blood = blood(t, person, ref);
    if (blood != null) return blood;
    return byMarriage(t, person, ref);
  }

  /** A person merged into another resolves to the one they were merged into. */
  public Person getPerson(int id) {
    return resolve(tree(), id);
  }

  /**
   * A person's events in date order, their couples' marriages and divorces included. Each keeps the
   * keys the text-file tree gave ({@code type}, {@code text}, {@code year}) and adds the stored
   * fact: {@code id}, {@code tag}, {@code date}, {@code dateFrom}, {@code dateTo}, {@code place},
   * {@code placeId}, {@code description}, {@code coupleId}, {@code spouseId}, {@code citations}.
   */
  public List<Map<String, Object>> getLifeEvents(Person p) {
    if (jdbc == null) return List.of();
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            SELECT e.id, e.type, e.date_text, e.date_from, e.date_to, e.place_id,
                   pl.name AS place, e.description, e.couple_id, e.sort_order,
                   CASE WHEN c.person1_id = ? THEN c.person2_id ELSE c.person1_id END AS spouse_id,
                   EXISTS (SELECT 1 FROM gen_event d
                           WHERE d.couple_id = e.couple_id AND d.type = 'DIV') AS divorced
            FROM gen_event e
            LEFT JOIN gen_place pl ON pl.id = e.place_id
            LEFT JOIN gen_couple c ON c.id = e.couple_id
            WHERE e.person_id = ? OR c.person1_id = ? OR c.person2_id = ?
            """,
            p.id,
            p.id,
            p.id,
            p.id);
    Map<Long, List<Map<String, Object>>> citations = citationsByEvent(rows);
    List<Map<String, Object>> events = new ArrayList<>();
    for (Map<String, Object> r : rows) {
      String tag = (String) r.get("type");
      Integer spouseId = r.get("couple_id") == null ? null : (Integer) r.get("spouse_id");
      Person spouse = spouseId == null ? null : getPerson(spouseId);
      String word = EVENT_WORDS.getOrDefault(tag, "event");
      if (tag.equals("MARR") && Boolean.TRUE.equals(r.get("divorced"))) {
        word = "marriage & divorce";
      }
      LocalDate from = toDate(r.get("date_from"));
      LocalDate to = toDate(r.get("date_to"));
      Integer year = yearOf(from, to);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("type", word);
      m.put("text", text(r, spouse));
      m.put("year", year);
      m.put("id", ((Number) r.get("id")).longValue());
      m.put("tag", tag);
      m.put("date", r.get("date_text"));
      m.put("dateFrom", from == null ? null : from.toString());
      m.put("dateTo", to == null ? null : to.toString());
      m.put("place", r.get("place"));
      m.put("placeId", r.get("place_id"));
      m.put("description", r.get("description"));
      m.put("coupleId", r.get("couple_id"));
      m.put("spouseId", spouseId);
      m.put("sortOrder", r.get("sort_order"));
      m.put("citations", citations.getOrDefault(((Number) r.get("id")).longValue(), List.of()));
      events.add(m);
    }
    events.sort(
        Comparator.<Map<String, Object>>comparingInt(
                e -> e.get("year") instanceof Integer y ? y : 9999)
            .thenComparingInt(e -> (Integer) e.get("sortOrder"))
            .thenComparingLong(e -> (Long) e.get("id")));
    events.forEach(e -> e.remove("sortOrder"));
    return events;
  }

  public List<Person> getAllPeople() {
    return tree().members();
  }

  Set<String> getTitleWords() {
    return TITLE_WORDS;
  }

  public int getPersonCount() {
    return tree().members().size();
  }

  // --- Loading ---

  private Snapshot load() {
    Map<Integer, Person> all = new LinkedHashMap<>();
    Map<Integer, Integer> mergedInto = new HashMap<>();
    jdbc.query(
        """
        SELECT p.id, p.legacy_entry, p.legacy_section, p.legacy_code, p.legacy_depth,
               p.merged_into, n.given, n.surname
        FROM gen_person p
        LEFT JOIN gen_name n ON n.person_id = p.id AND n.is_primary
        ORDER BY p.id
        """,
        rs -> {
          int id = rs.getInt("id");
          int into = rs.getInt("merged_into");
          if (!rs.wasNull()) {
            mergedInto.put(id, into);
            return;
          }
          Person p = new Person();
          p.id = id;
          String given = rs.getString("given");
          String surname = rs.getString("surname");
          p.name = displayName(given, surname);
          p.surname = surname;
          String entry = rs.getString("legacy_entry");
          p.matchingName =
              entry != null
                  ? LegacyTreeParser.legacyName(entry)
                  : FAMILY_SURNAME.equals(surname) ? displayName(given, null) : p.name;
          p.fullEntry = entry != null ? entry : p.name;
          p.section = rs.getString("legacy_section");
          p.code = rs.getString("legacy_code");
          p.depth = rs.getInt("legacy_depth");
          p.children = new ArrayList<>();
          p.spouses = new ArrayList<>();
          all.put(id, p);
        });

    Set<Integer> born = new HashSet<>();
    Set<Integer> died = new HashSet<>();
    jdbc.query(
        """
        SELECT e.person_id, e.type, e.date_from, e.date_to, pl.name AS place
        FROM gen_event e LEFT JOIN gen_place pl ON pl.id = e.place_id
        WHERE e.person_id IS NOT NULL AND e.type IN ('BIRT', 'DEAT')
        ORDER BY e.sort_order, e.id
        """,
        rs -> {
          Person p = all.get(rs.getInt("person_id"));
          if (p == null) return;
          Integer year =
              yearOf(
                  rs.getObject("date_from", LocalDate.class),
                  rs.getObject("date_to", LocalDate.class));
          if (rs.getString("type").equals("BIRT")) {
            if (born.add(p.id)) {
              p.birthYear = year;
              p.birthPlace = rs.getString("place");
            }
          } else if (died.add(p.id)) {
            p.deathYear = year;
          }
        });

    Map<Integer, List<Integer>> birthParents = new HashMap<>();
    jdbc.query(
        "SELECT parent_id, child_id FROM gen_parent_child WHERE type = 'birth'"
            + " ORDER BY child_id, parent_id",
        rs -> {
          int parent = rs.getInt("parent_id");
          int child = rs.getInt("child_id");
          if (all.containsKey(parent) && all.containsKey(child)) {
            birthParents.computeIfAbsent(child, k -> new ArrayList<>()).add(parent);
          }
        });
    for (var e : birthParents.entrySet()) {
      Person child = all.get(e.getKey());
      Person parent = all.get(e.getValue().get(0));
      child.parent = parent;
    }
    for (Person p : all.values()) {
      for (int parentId : birthParents.getOrDefault(p.id, List.of())) {
        all.get(parentId).children.add(p);
      }
    }

    Map<Integer, List<Integer>> partners = new HashMap<>();
    jdbc.query(
        "SELECT person1_id, person2_id FROM gen_couple ORDER BY sort_order, id",
        rs -> {
          int a = rs.getInt("person1_id");
          int b = rs.getInt("person2_id");
          if (all.containsKey(a) && all.containsKey(b)) {
            partners.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
            partners.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
            all.get(a).spouses.add(all.get(b).name);
            all.get(b).spouses.add(all.get(a).name);
          }
        });

    Set<Integer> members = new LinkedHashSet<>();
    Deque<Person> queue = new ArrayDeque<>();
    for (Person p : all.values()) {
      if (p.section != null) {
        members.add(p.id);
        queue.add(p);
      }
    }
    while (!queue.isEmpty()) {
      for (Person child : queue.poll().children) {
        if (members.add(child.id)) queue.add(child);
      }
    }
    List<Person> memberList = new ArrayList<>();
    Person alexander = null;
    for (Person p : all.values()) {
      if (members.contains(p.id)) memberList.add(p);
      if (alexander == null && p.fullEntry.contains(ALEXANDER)) alexander = p;
    }
    return new Snapshot(
        List.copyOf(memberList),
        Collections.unmodifiableMap(all),
        Map.copyOf(mergedInto),
        alexander,
        birthParents,
        partners);
  }

  private static String displayName(String given, String surname) {
    if (given == null) return surname == null ? "" : surname;
    return surname == null ? given : given + " " + surname;
  }

  private Map<Long, List<Map<String, Object>>> citationsByEvent(List<Map<String, Object>> events) {
    Map<Long, List<Map<String, Object>>> out = new HashMap<>();
    if (events.isEmpty()) return out;
    List<Long> ids = events.stream().map(e -> ((Number) e.get("id")).longValue()).toList();
    String in = String.join(",", Collections.nCopies(ids.size(), "?"));
    jdbc.query(
        "SELECT c.id, c.event_id, c.source_id, s.title, c.page_id, c.detail, c.quality"
            + " FROM gen_citation c JOIN gen_source s ON s.id = c.source_id"
            + " WHERE c.event_id IN ("
            + in
            + ") ORDER BY c.id",
        rs -> {
          Map<String, Object> c = new LinkedHashMap<>();
          c.put("id", rs.getLong("id"));
          c.put("sourceId", rs.getLong("source_id"));
          c.put("sourceTitle", rs.getString("title"));
          long page = rs.getLong("page_id");
          c.put("pageId", rs.wasNull() ? null : page);
          c.put("detail", rs.getString("detail"));
          int q = rs.getInt("quality");
          c.put("quality", rs.wasNull() ? null : q);
          out.computeIfAbsent(rs.getLong("event_id"), k -> new ArrayList<>()).add(c);
        },
        ids.toArray());
    return out;
  }

  private static String text(Map<String, Object> r, Person spouse) {
    List<String> parts = new ArrayList<>();
    for (String key : List.of("description", "date_text", "place")) {
      if (r.get(key) != null) parts.add((String) r.get(key));
    }
    if (spouse != null && "MARR".equals(r.get("type"))) parts.add(spouse.name);
    return String.join(", ", parts);
  }

  private static Integer yearOf(LocalDate from, LocalDate to) {
    if (from != null) return from.getYear();
    if (to != null) return to.getYear();
    return null;
  }

  private static LocalDate toDate(Object value) {
    if (value == null) return null;
    if (value instanceof java.sql.Date d) return d.toLocalDate();
    return (LocalDate) value;
  }

  private static Person resolve(Snapshot t, int id) {
    Integer into = t.mergedInto().get(id);
    for (int hops = 0; into != null && hops < 32; hops++) {
      id = into;
      into = t.mergedInto().get(id);
    }
    return t.byId().get(id);
  }

  // --- Fuzzy search ---

  /**
   * Forenames score as they always did; a query word that matches none of them may still match the
   * surname exactly, so "Czernin Rudolf" finds the Rudolfs.
   */
  private double score(List<String> queryTokens, Person person) {
    List<String> nameTokens = tokenize(normalize(person.matchName()));
    List<String> surnameTokens =
        person.surname == null ? List.of() : tokenize(normalize(person.surname));
    List<String> entryTokens = tokenize(normalize(person.fullEntry));

    double exactMatches = 0, substringMatches = 0, fuzzyMatches = 0;

    for (String qt : queryTokens) {
      boolean matched = false;
      for (String nt : nameTokens) {
        if (nt.equals(qt)) {
          exactMatches++;
          matched = true;
          break;
        }
      }
      if (matched) continue;

      for (String nt : nameTokens) {
        if (nt.contains(qt) || qt.contains(nt)) {
          substringMatches++;
          matched = true;
          break;
        }
      }
      if (matched) continue;

      for (String nt : nameTokens) {
        if (qt.length() >= 3 && nt.length() >= 3 && levenshtein(qt, nt) <= 2) {
          fuzzyMatches++;
          matched = true;
          break;
        }
      }
      if (matched) continue;

      for (String st : surnameTokens) {
        if (st.equals(qt)) {
          exactMatches++;
          matched = true;
          break;
        }
      }
      if (matched) continue;

      for (String et : entryTokens) {
        if (et.equals(qt)) {
          exactMatches += 0.3;
          break;
        }
      }
    }

    double raw =
        (exactMatches * 3.0 + substringMatches * 1.5 + fuzzyMatches * 0.5)
            / (queryTokens.size() * 3.0);
    return Math.min(1.0, raw);
  }

  String normalize(String s) {
    String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);
    return nfd.replaceAll("\\p{Mn}", "").toLowerCase();
  }

  List<String> tokenize(String s) {
    return Arrays.stream(s.split("[\\s,;.()/]+"))
        .filter(t -> !t.isEmpty() && t.length() > 1 && !TITLE_WORDS.contains(t))
        .toList();
  }

  int levenshtein(String a, String b) {
    int[][] dp = new int[a.length() + 1][b.length() + 1];
    for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
    for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
    for (int i = 1; i <= a.length(); i++) {
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        dp[i][j] = Math.min(dp[i - 1][j] + 1, Math.min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost));
      }
    }
    return dp[a.length()][b.length()];
  }

  // --- Kinship ---

  /** Distances from {@code from} up its birth parents, lowest parent id explored first. */
  private static Map<Integer, Integer> ancestors(Snapshot t, int from, Map<Integer, Integer> via) {
    Map<Integer, Integer> dist = new LinkedHashMap<>();
    Deque<Integer> queue = new ArrayDeque<>();
    dist.put(from, 0);
    queue.add(from);
    while (!queue.isEmpty()) {
      int cur = queue.poll();
      for (int parent : t.birthParents().getOrDefault(cur, List.of())) {
        if (!dist.containsKey(parent)) {
          dist.put(parent, dist.get(cur) + 1);
          via.put(parent, cur);
          queue.add(parent);
        }
      }
    }
    return dist;
  }

  private RelationshipResult blood(Snapshot t, Person person, Person ref) {
    Map<Integer, Integer> viaPerson = new HashMap<>();
    Map<Integer, Integer> viaRef = new HashMap<>();
    Map<Integer, Integer> up = ancestors(t, person.id, viaPerson);
    Map<Integer, Integer> refUp = ancestors(t, ref.id, viaRef);
    Integer best = null;
    for (var e : up.entrySet()) {
      Integer dB = refUp.get(e.getKey());
      if (dB == null) continue;
      if (best == null) {
        best = e.getKey();
        continue;
      }
      int dA = e.getValue();
      int bA = up.get(best);
      int bB = refUp.get(best);
      if (dA + dB < bA + bB
          || (dA + dB == bA + bB && dB < bB)
          || (dA + dB == bA + bB && dB == bB && e.getKey() < best)) {
        best = e.getKey();
      }
    }
    if (best == null) return null;
    Person lca = t.byId().get(best);
    int dA = up.get(best);
    int dB = refUp.get(best);
    return new RelationshipResult(
        person.id,
        person.name,
        kinshipLabel(dA, dB),
        buildPathDescription(
            person, ref, lca, dA, dB, path(t, viaPerson, best), path(t, viaRef, best)),
        lca.name,
        lca.id,
        dA,
        dB,
        ref.name);
  }

  /** The line from the ancestor back down to where the walk started, the ancestor first. */
  private static List<Person> path(Snapshot t, Map<Integer, Integer> via, int ancestor) {
    List<Person> down = new ArrayList<>();
    Integer cur = ancestor;
    while (cur != null) {
      down.add(t.byId().get(cur));
      cur = via.get(cur);
    }
    return down;
  }

  private RelationshipResult byMarriage(Snapshot t, Person person, Person ref) {
    List<Integer> personPartners = t.partners().getOrDefault(person.id, List.of());
    if (personPartners.contains(ref.id)) {
      return new RelationshipResult(
          person.id,
          person.name,
          "spouse",
          person.name + " is the spouse of " + ref.name,
          null,
          0,
          0,
          0,
          ref.name);
    }
    RelationshipResult best = null;
    String bestLabel = null;
    String bestPath = null;
    for (int x : personPartners) {
      RelationshipResult r = blood(t, t.byId().get(x), ref);
      if (r != null && better(r, best)) {
        best = r;
        bestLabel = spouseOf(r.kinshipLabel());
        bestPath =
            person.name + " is married to " + t.byId().get(x).name + "; " + r.pathDescription();
      }
    }
    for (int y : t.partners().getOrDefault(ref.id, List.of())) {
      RelationshipResult r = blood(t, person, t.byId().get(y));
      if (r != null && better(r, best)) {
        best = r;
        bestLabel = ofSpouse(r.kinshipLabel());
        bestPath = ref.name + " is married to " + t.byId().get(y).name + "; " + r.pathDescription();
      }
    }
    if (best == null) return null;
    return new RelationshipResult(
        person.id,
        person.name,
        bestLabel,
        bestPath,
        best.commonAncestorName(),
        best.commonAncestorId(),
        best.stepsFromPerson(),
        best.stepsFromRef(),
        ref.name);
  }

  private static boolean better(RelationshipResult r, RelationshipResult best) {
    if (best == null) return true;
    return r.stepsFromPerson() + r.stepsFromRef() < best.stepsFromPerson() + best.stepsFromRef();
  }

  /** The person is married to someone who is the reference's {@code label}. */
  static String spouseOf(String label) {
    return switch (label) {
      case "sibling" -> "sibling-in-law";
      case "child" -> "child-in-law";
      case "parent" -> "step-parent";
      default -> "spouse of " + label;
    };
  }

  /** The reference is married to someone whose {@code label} the person is. */
  static String ofSpouse(String label) {
    return switch (label) {
      case "sibling" -> "sibling-in-law";
      case "parent" -> "parent-in-law";
      case "child" -> "step-child";
      default -> label + " of spouse";
    };
  }

  private String kinshipLabel(int dA, int dB) {
    if (dA == 1 && dB == 1) return "sibling";
    if (dA == 0) return descend(dB);
    if (dB == 0) return ascend(dA);
    if (dA == 1 && dB == 2) return "uncle/aunt";
    if (dA == 2 && dB == 1) return "nephew/niece";
    if (dA == 1 && dB > 2) return "great-".repeat(dB - 2) + "uncle/aunt";
    if (dB == 1 && dA > 2) return "great-".repeat(dA - 2) + "nephew/niece";
    if (dA == dB) return ordinal(dA - 1) + " cousin";
    int cousinDeg = Math.min(dA, dB) - 1;
    int removed = Math.abs(dA - dB);
    return ordinal(cousinDeg)
        + " cousin, "
        + removed
        + (removed == 1 ? " time" : " times")
        + " removed";
  }

  private String ascend(int n) {
    if (n == 1) return "child";
    if (n == 2) return "grandchild";
    return "great-".repeat(n - 2) + "grandchild";
  }

  private String descend(int n) {
    if (n == 1) return "parent";
    if (n == 2) return "grandparent";
    return "great-".repeat(n - 2) + "grandparent";
  }

  private String ordinal(int n) {
    if (n <= 0) return String.valueOf(n);
    int mod100 = n % 100;
    int mod10 = n % 10;
    String suffix =
        (mod10 == 1 && mod100 != 11)
            ? "st"
            : (mod10 == 2 && mod100 != 12) ? "nd" : (mod10 == 3 && mod100 != 13) ? "rd" : "th";
    return n + suffix;
  }

  private String buildPathDescription(
      Person person,
      Person ref,
      Person lca,
      int dPerson,
      int dRef,
      List<Person> personLine,
      List<Person> refLine) {
    var sb = new StringBuilder();
    String refName = ref.name;

    if (dPerson == 1 && dRef == 1) {
      sb.append(person.name)
          .append(" and ")
          .append(refName)
          .append(" are siblings (children of ")
          .append(lca.name)
          .append(")");
    } else if (dPerson >= 2 && dRef >= 2) {
      Person personBranch = personLine.size() >= 2 ? personLine.get(1) : person;
      Person refBranch = refLine.size() >= 2 ? refLine.get(1) : ref;

      if (dPerson == 2 && dRef == 2) {
        sb.append(person.name)
            .append("'s father ")
            .append(personBranch.name)
            .append(" and ")
            .append(refName)
            .append("'s father ")
            .append(refBranch.name)
            .append(" were siblings (children of ")
            .append(lca.name)
            .append(")");
      } else {
        sb.append("Their lines diverge at ")
            .append(lca.name)
            .append(" — ")
            .append(person.name)
            .append(" descends through ")
            .append(personBranch.name)
            .append(", ")
            .append(refName)
            .append(" through ")
            .append(refBranch.name);
      }
    } else {
      sb.append(person.name)
          .append(" and ")
          .append(refName)
          .append(" share the common ancestor ")
          .append(lca.name);
      sb.append(" (").append(dPerson).append(" generations from ").append(person.name);
      sb.append(", ").append(dRef).append(" from ").append(refName).append(")");
    }

    return sb.toString();
  }

  // --- Inner types ---

  public static class Person {
    public int id;

    /** Given names and surname, as shown everywhere. */
    public String name;

    public String surname;

    /** What person matching scores on; see {@link #matchName()}. */
    public String matchingName;

    public String fullEntry;
    public Integer birthYear;
    public Integer deathYear;
    public String birthPlace;
    public String section;
    public String code;
    public String parentCode;
    public Person parent;
    public List<Person> children;
    public List<String> spouses;
    public int depth;

    /**
     * The name person matching scores on: the legacy name for people imported from the text file
     * (their forenames, never the Czernin surname the tree now adds), the given names otherwise,
     * and {@link #name} when nothing else is set, as for people built by hand in tests.
     */
    public String matchName() {
      return matchingName != null ? matchingName : name;
    }

    public String fullCode() {
      if (parentCode != null && code != null) return parentCode + "." + code;
      if (code != null) return code;
      return "";
    }
  }

  public record SearchResult(
      int personId,
      String name,
      String fullEntry,
      String section,
      String code,
      double score,
      Integer birthYear,
      Integer deathYear) {}

  public record RelationshipResult(
      int personId,
      String personName,
      String kinshipLabel,
      String pathDescription,
      String commonAncestorName,
      int commonAncestorId,
      int stepsFromPerson,
      int stepsFromRef,
      String refPersonName) {}
}
```

- [ ] **Step 4: Add the row helpers and the read service**

Create `backend/src/main/java/place/icomb/archiver/genealogy/Rows.java`:

```java
package place.icomb.archiver.genealogy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.postgresql.util.PGobject;

/** Database rows as the API shows them: camelCase keys, ISO dates, JSON as JSON. */
public final class Rows {

  private static final ObjectMapper JSON = new ObjectMapper();

  private Rows() {}

  public static List<Map<String, Object>> camel(List<Map<String, Object>> rows) {
    List<Map<String, Object>> out = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) out.add(camel(row));
    return out;
  }

  public static Map<String, Object> camel(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (var e : row.entrySet()) out.put(camelCase(e.getKey()), value(e.getValue()));
    return out;
  }

  /** A JSON object, as text, read into a map; null stays null. */
  public static Map<String, Object> json(String text) {
    if (text == null) return null;
    try {
      return JSON.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("not a JSON object: " + text, e);
    }
  }

  public static String toJson(Map<String, Object> value) {
    if (value == null) return null;
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String camelCase(String snake) {
    StringBuilder sb = new StringBuilder();
    boolean up = false;
    for (char c : snake.toCharArray()) {
      if (c == '_') {
        up = true;
      } else {
        sb.append(up ? Character.toUpperCase(c) : c);
        up = false;
      }
    }
    return sb.toString();
  }

  private static Object value(Object v) {
    if (v instanceof Timestamp t) return t.toInstant().toString();
    if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
    if (v instanceof UUID u) return u.toString();
    if (v instanceof PGobject o && o.getType() != null && o.getType().startsWith("json")) {
      return o.getValue() == null ? null : json(o.getValue());
    }
    if (v instanceof PGobject o) return o.getValue();
    return v;
  }
}
```

Create `backend/src/main/java/place/icomb/archiver/genealogy/GenealogyReadService.java`:

```java
package place.icomb.archiver.genealogy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The structured facts about one person that the person page and get_person show. */
@Service
public class GenealogyReadService {

  private final JdbcTemplate jdbc;

  public GenealogyReadService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * {@code names}, {@code couples} (with spouse id and name), {@code parentLinks} (with type),
   * {@code recordLinks}, {@code media}, {@code portrait}, {@code sources}, and the person's own
   * {@code sex}, {@code living}, {@code notes}, {@code needsReview} and {@code uid}. Null when no
   * such person.
   */
  public Map<String, Object> personDetail(int personId) {
    List<Map<String, Object>> person =
        jdbc.queryForList(
            "SELECT uid, sex, living, notes, needs_review FROM gen_person WHERE id = ?", personId);
    if (person.isEmpty()) return null;
    Map<String, Object> out = new LinkedHashMap<>(Rows.camel(person.get(0)));
    out.put(
        "names",
        Rows.camel(
            jdbc.queryForList(
                """
                SELECT id, type, given, surname, prefix, suffix, nickname, is_primary, sort_order
                FROM gen_name WHERE person_id = ? ORDER BY is_primary DESC, sort_order, id
                """,
                personId)));
    out.put(
        "couples",
        Rows.camel(
            jdbc.queryForList(
                """
                SELECT c.id, c.type, c.sort_order, c.notes, s.id AS spouse_id,
                       concat_ws(' ', n.given, n.surname) AS spouse_name
                FROM gen_couple c
                JOIN gen_person s ON s.id = CASE WHEN c.person1_id = ? THEN c.person2_id
                                                 ELSE c.person1_id END
                LEFT JOIN gen_name n ON n.person_id = s.id AND n.is_primary
                WHERE ? IN (c.person1_id, c.person2_id)
                ORDER BY c.sort_order, c.id
                """,
                personId,
                personId)));
    out.put(
        "parentLinks",
        Rows.camel(
            jdbc.queryForList(
                """
                SELECT l.id, l.type, l.couple_id, l.parent_id, l.child_id,
                       CASE WHEN l.child_id = ? THEN 'parent' ELSE 'child' END AS relation,
                       concat_ws(' ', n.given, n.surname) AS other_name
                FROM gen_parent_child l
                LEFT JOIN gen_name n ON n.is_primary
                     AND n.person_id = CASE WHEN l.child_id = ? THEN l.parent_id ELSE l.child_id END
                WHERE ? IN (l.parent_id, l.child_id)
                ORDER BY relation DESC, l.id
                """,
                personId,
                personId,
                personId)));
    out.put(
        "recordLinks",
        Rows.camel(
            jdbc.queryForList(
                """
                SELECT pr.id, pr.record_id, pr.page_id, pg.seq AS page_seq, pr.role, pr.note,
                       pr.quality, r.title, r.title_en, r.reference_code
                FROM gen_person_record pr
                JOIN record r ON r.id = pr.record_id
                LEFT JOIN page pg ON pg.id = pr.page_id
                WHERE pr.person_id = ?
                ORDER BY pr.record_id, pg.seq NULLS FIRST, pr.id
                """,
                personId)));
    List<Map<String, Object>> media =
        Rows.camel(
            jdbc.queryForList(
                """
                SELECT m.id, m.page_id, pg.record_id, pg.seq AS page_seq, m.crop_left, m.crop_top,
                       m.crop_width, m.crop_height, m.caption, m.date_text, mp.is_portrait,
                       '/api/family-tree/media/' || m.id || '/image' AS image_url
                FROM gen_media_person mp
                JOIN gen_media m ON m.id = mp.media_id
                JOIN page pg ON pg.id = m.page_id
                WHERE mp.person_id = ?
                ORDER BY mp.is_portrait DESC, mp.sort_order, m.id
                """,
                personId));
    out.put("media", media);
    out.put(
        "portrait",
        media.stream()
            .filter(m -> Boolean.TRUE.equals(m.get("isPortrait")))
            .findFirst()
            .orElse(null));
    out.put(
        "sources",
        Rows.camel(
            jdbc.queryForList(
                """
                SELECT DISTINCT s.id, s.title, s.author, s.record_id, s.url
                FROM gen_source s
                JOIN gen_citation c ON c.source_id = s.id
                WHERE c.person_id = ?
                   OR c.name_id IN (SELECT id FROM gen_name WHERE person_id = ?)
                   OR c.event_id IN (SELECT e.id FROM gen_event e
                                     LEFT JOIN gen_couple k ON k.id = e.couple_id
                                     WHERE e.person_id = ? OR ? IN (k.person1_id, k.person2_id))
                   OR c.couple_id IN (SELECT id FROM gen_couple
                                      WHERE ? IN (person1_id, person2_id))
                   OR c.parent_child_id IN (SELECT id FROM gen_parent_child WHERE child_id = ?)
                ORDER BY s.id
                """,
                personId,
                personId,
                personId,
                personId,
                personId,
                personId)));
    return out;
  }
}
```

- [ ] **Step 5: Rewrite FamilyTreeController**

Replace the whole of `backend/src/main/java/place/icomb/archiver/controller/FamilyTreeController.java` with:

```java
package place.icomb.archiver.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.genealogy.GenealogyReadService;
import place.icomb.archiver.model.PagePersonMatch;
import place.icomb.archiver.service.FamilyTreeService;
import place.icomb.archiver.service.FamilyTreeService.Person;
import place.icomb.archiver.service.PersonMatchService;
import place.icomb.archiver.service.PersonMatchService.RecordPersonMatch;

@RestController
@RequestMapping("/api/family-tree")
public class FamilyTreeController {

  private final FamilyTreeService familyTreeService;
  private final PersonMatchService personMatchService;
  private final GenealogyReadService genealogy;

  public FamilyTreeController(
      FamilyTreeService familyTreeService,
      PersonMatchService personMatchService,
      GenealogyReadService genealogy) {
    this.familyTreeService = familyTreeService;
    this.personMatchService = personMatchService;
    this.genealogy = genealogy;
  }

  @GetMapping("/search")
  public ResponseEntity<List<Map<String, Object>>> search(
      @RequestParam String q,
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(defaultValue = "false") boolean all) {
    var results = familyTreeService.search(q, limit, all);
    List<Map<String, Object>> response = new ArrayList<>();
    for (var r : results) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("personId", r.personId());
      m.put("name", r.name());
      m.put("fullEntry", r.fullEntry());
      m.put("section", r.section());
      m.put("code", r.code());
      m.put("score", r.score());
      m.put("birthYear", r.birthYear());
      m.put("deathYear", r.deathYear());
      response.add(m);
    }
    return ResponseEntity.ok(response);
  }

  @GetMapping("/relate")
  public ResponseEntity<Map<String, Object>> relate(
      @RequestParam int personId, @RequestParam(required = false) Integer refId) {
    var result =
        refId != null
            ? familyTreeService.relate(personId, refId)
            : familyTreeService.relate(personId);
    if (result == null) return ResponseEntity.notFound().build();

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("personId", result.personId());
    m.put("personName", result.personName());
    m.put("kinshipLabel", result.kinshipLabel());
    m.put("pathDescription", result.pathDescription());
    m.put("commonAncestorName", result.commonAncestorName());
    m.put("commonAncestorId", result.commonAncestorId());
    m.put("stepsFromPerson", result.stepsFromPerson());
    m.put("stepsFromRef", result.stepsFromRef());
    m.put("refPersonName", result.refPersonName());
    return ResponseEntity.ok(m);
  }

  @GetMapping("/person/{id}")
  public ResponseEntity<Map<String, Object>> getPerson(@PathVariable int id) {
    Person p = familyTreeService.getPerson(id);
    if (p == null) return ResponseEntity.notFound().build();

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", p.id);
    m.put("name", p.name);
    m.put("fullEntry", p.fullEntry);
    m.put("birthYear", p.birthYear);
    m.put("deathYear", p.deathYear);
    m.put("birthPlace", p.birthPlace);
    m.put("section", p.section);
    m.put("code", p.fullCode());
    m.put("depth", p.depth);

    if (p.parent != null) {
      m.put("parent", personRef(p.parent));
    } else {
      m.put("parent", null);
    }

    List<Map<String, Object>> children = new ArrayList<>();
    for (Person child : p.children) {
      children.add(personRef(child));
    }
    m.put("children", children);

    // Siblings = parent's other children (excluding self)
    List<Map<String, Object>> siblings = new ArrayList<>();
    if (p.parent != null) {
      for (Person sibling : p.parent.children) {
        if (sibling.id != p.id) {
          siblings.add(personRef(sibling));
        }
      }
    }
    m.put("siblings", siblings);
    m.put("spouses", p.spouses);
    m.put("events", familyTreeService.getLifeEvents(p));
    Map<String, Object> detail = genealogy.personDetail(p.id);
    if (detail != null) {
      m.putAll(detail);
    }

    return ResponseEntity.ok(m);
  }

  private static Map<String, Object> personRef(Person p) {
    Map<String, Object> ref = new LinkedHashMap<>();
    ref.put("id", p.id);
    ref.put("name", p.name);
    ref.put("birthYear", p.birthYear);
    ref.put("deathYear", p.deathYear);
    return ref;
  }

  @GetMapping("/page-matches/{pageId}")
  public ResponseEntity<List<Map<String, Object>>> pageMatches(@PathVariable Long pageId) {
    var matches = personMatchService.getPageMatches(pageId);
    List<Map<String, Object>> response = new ArrayList<>();
    for (PagePersonMatch m : matches) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("personId", m.getPersonId());
      map.put("personName", m.getPersonName());
      map.put("score", m.getScore());
      map.put("context", m.getContext());
      // Enrich with family tree data
      Person p = familyTreeService.getPerson(m.getPersonId());
      if (p != null) {
        map.put("section", p.section);
        map.put("code", p.fullCode());
        map.put("birthYear", p.birthYear);
        map.put("deathYear", p.deathYear);
      }
      response.add(map);
    }
    return ResponseEntity.ok(response);
  }

  @GetMapping("/record-matches/{recordId}")
  public ResponseEntity<List<Map<String, Object>>> recordMatches(@PathVariable Long recordId) {
    var matches = personMatchService.getRecordMatches(recordId);
    List<Map<String, Object>> response = new ArrayList<>();
    for (RecordPersonMatch m : matches) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("personId", m.personId());
      map.put("personName", m.personName());
      map.put("maxScore", m.maxScore());
      map.put("pageCount", m.pageSeqs().size());
      map.put("pageSeqs", m.pageSeqs());
      // Enrich with family tree data
      Person p = familyTreeService.getPerson(m.personId());
      if (p != null) {
        map.put("section", p.section);
        map.put("code", p.fullCode());
        map.put("birthYear", p.birthYear);
        map.put("deathYear", p.deathYear);
      }
      response.add(map);
    }
    return ResponseEntity.ok(response);
  }
}
```

- [ ] **Step 6: Point person matching at the legacy names**

`Person.name` is now the display name with the surname Czernin. Person matching must keep scoring on exactly the names it scored on before, or every candidate gains a "czernin" token. Its three reads of the name become `matchName()`; nothing else in the class changes:

In `backend/src/main/java/place/icomb/archiver/service/PersonMatchService.java`, replace:

```java
                    mc.person.name,
```

with:

```java
                    mc.person.matchName(),
```

In `backend/src/main/java/place/icomb/archiver/service/PersonMatchService.java`, replace:

```java
  private List<String> getPersonNameTokens(Person person, Set<String> titleWords) {
    List<String> tokens = new ArrayList<>();
    String normalized = familyTreeService.normalize(person.name);
```

with:

```java
  private List<String> getPersonNameTokens(Person person, Set<String> titleWords) {
    List<String> tokens = new ArrayList<>();
    String normalized = familyTreeService.normalize(person.matchName());
```

In `backend/src/main/java/place/icomb/archiver/service/PersonMatchService.java`, replace:

```java
   * Returns the given names from person.name in their original order, excluding title words. Used
   * for name-position bonus scoring.
```

with:

```java
   * Returns the given names from person.matchName() in their original order, excluding title words.
   * Used for name-position bonus scoring.
```

In `backend/src/main/java/place/icomb/archiver/service/PersonMatchService.java`, replace:

```java
    List<String> givenNames = new ArrayList<>();
    String normalized = familyTreeService.normalize(person.name);
```

with:

```java
    List<String> givenNames = new ArrayList<>();
    String normalized = familyTreeService.normalize(person.matchName());
```

- [ ] **Step 7: Delete the in-memory tree's tests**

Their cases now live in `FamilyTreeReadModelTest` (golden files) and `LegacyLineParserTest` (line parsing).

Run: `git rm backend/src/test/java/place/icomb/archiver/service/FamilyTreeGoldenTest.java`

Run: `git rm backend/src/test/java/place/icomb/archiver/service/FamilyTreeParseTest.java`

- [ ] **Step 8: Run them to see them pass, with the regression suites that read the tree**

Run: `cd backend && ./gradlew test --tests '*FamilyTreeReadModelTest' --tests '*FamilyTreeControllerTest' --tests '*PersonMatchServiceTest' --tests '*ProfileControllerTest' --tests '*AuthControllerTest' --tests '*PageMoveTest' --tests '*LegacyImportTest'`

Expected: PASS. `FamilyTreeReadModelTest` (7 tests) reproduces all 346 people and 1,730 kinship rows from the golden files with names as `expectedDisplay`, the search rows except the "czernin" query, and the legacy names as `matchName()` exactly; the other suites are unchanged.

- [ ] **Step 9: Format**

Run: `cd backend && ./gradlew spotlessApply`

Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add -A backend/src/main/java/place/icomb/archiver/service/FamilyTreeService.java backend/src/main/java/place/icomb/archiver/genealogy/Rows.java backend/src/main/java/place/icomb/archiver/genealogy/GenealogyReadService.java backend/src/main/java/place/icomb/archiver/controller/FamilyTreeController.java backend/src/main/java/place/icomb/archiver/service/PersonMatchService.java backend/src/test/java/place/icomb/archiver/controller/FamilyTreeControllerTest.java backend/src/test/java/place/icomb/archiver/service
git commit -m "feat: family tree reads the genealogy tables; reload endpoints removed"
```

---

### Task 7: GenealogyService: validated, audited writes

**Files:**
- Create: `backend/src/main/java/place/icomb/archiver/genealogy/GenEntity.java`
- Create: `backend/src/main/java/place/icomb/archiver/genealogy/GenealogyException.java`
- Create: `backend/src/main/java/place/icomb/archiver/genealogy/MediaImages.java`
- Create: `backend/src/main/java/place/icomb/archiver/genealogy/GenealogyService.java`
- Test: `backend/src/test/java/place/icomb/archiver/genealogy/GenealogyServiceTest.java`

**Interfaces:**
- Consumes: `FamilyTreeService.invalidate()` (Task 6), `Rows` (Task 6), `GedcomDate` (Task 3), `GenealogyFixtures` (Task 2), `AttachmentRepository.findById`, `StorageService.getPath(Attachment)`.
- Produces (used by Tasks 8–12):
  - `enum GenEntity`. Each constant has a `key()` (the value of `gen_change.entity`) and a `table()`:

    | Constant | key | table |
    |---|---|---|
    | `PERSON` | `person` | `gen_person` |
    | `NAME` | `name` | `gen_name` |
    | `COUPLE` | `couple` | `gen_couple` |
    | `PARENT_LINK` | `parent_link` | `gen_parent_child` |
    | `PLACE` | `place` | `gen_place` |
    | `PLACE_NAME` | `place_name` | `gen_place_name` |
    | `EVENT` | `event` | `gen_event` |
    | `SOURCE` | `source` | `gen_source` |
    | `CITATION` | `citation` | `gen_citation` |
    | `RECORD_LINK` | `record_link` | `gen_person_record` |
    | `MEDIA` | `media` | `gen_media` |
    | `MEDIA_PERSON` | `media_person` | `gen_media_person` |
    | `PAGE_PERSON_MATCH` | `page_person_match` | `page_person_match` |
    | `APP_USER` | `app_user` | `app_user` |

    It also has `writable()` (false for the last two), `fields()` (camelCase JSON name, snake_case column, `Type`), and `static GenEntity ofKey(String)`.
  - `GenealogyException(Kind kind, String message)`, where `Kind` is `NOT_FOUND`, `BAD_REQUEST` or `CONFLICT`, with factories `notFound`, `badRequest` and `conflict`.
  - `MediaImages`: `record Dimensions(int width, int height)` and `Dimensions dimensions(long pageId)`, read from the stored image's header (404 for no page, 409 if it cannot be read).
  - `GenealogyService`:
    - Writes, each `@Transactional`, each returning the row as a camelCase map:
      - `Map<String,Object> createPerson(Map<String,Object> body, String reason)`: person fields plus primary-name fields; returns the person row plus `primaryNameId`.
      - `create(GenEntity, Map<String,Object> body, String reason)`.
      - `update(GenEntity, long id, Map<String,Object> body, String reason)`.
      - `delete(GenEntity, long id, String reason)` returns `{entity, id, deleted}`.
      - `createMedia(Map<String,Object> body, int personId, boolean portrait, String reason)`.
    - Reads: `changes(String entity, Long entityId, int limit)`, `personChanges(int personId, int limit)`, `review()`.
    - Package-private: `audit(GenEntity, long id, String op, Map before, Map after, String reason)` and `changed()`, which invalidates the tree after commit. Public: `static void requireReason(String)`.
    - Event bodies may carry `placeName` instead of `placeId`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/place/icomb/archiver/genealogy/GenealogyServiceTest.java`:

```java
package place.icomb.archiver.genealogy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.genealogy.GenealogyException.Kind;
import place.icomb.archiver.service.FamilyTreeService;
import place.icomb.archiver.service.StorageService;

/** The edit rules of the genealogy, and the audit row every write leaves. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class GenealogyServiceTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private GenealogyService genealogy;
  @Autowired private FamilyTreeService tree;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StorageService storage;

  private static final int ALEXANDER = 322;
  private static final int ALEXANDER_HANNIBAL = 324;
  private static final int HUMPRECHT = 1;

  @BeforeEach
  void signIn() {
    GenealogyFixtures.signInAsAdmin();
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  private long audits() {
    return jdbc.queryForObject("SELECT count(*) FROM gen_change", Long.class);
  }

  private static Map<String, Object> body(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  private static Kind kindOf(Runnable r) {
    try {
      r.run();
    } catch (GenealogyException e) {
      return e.kind();
    }
    throw new AssertionError("expected a GenealogyException");
  }

  private int newPerson(String given, String surname) {
    return ((Number)
            genealogy
                .createPerson(body("given", given, "surname", surname, "sex", "F"), "test person")
                .get("id"))
        .intValue();
  }

  @Test
  void everyWriteNeedsAReason() {
    long before = audits();
    for (String reason : new String[] {null, "", "   "}) {
      assertThat(kindOf(() -> genealogy.createPerson(body("given", "Anna"), reason)))
          .isEqualTo(Kind.BAD_REQUEST);
      assertThat(
              kindOf(
                  () -> genealogy.update(GenEntity.PERSON, ALEXANDER, body("notes", "x"), reason)))
          .isEqualTo(Kind.BAD_REQUEST);
      assertThat(kindOf(() -> genealogy.delete(GenEntity.EVENT, 1, reason)))
          .isEqualTo(Kind.BAD_REQUEST);
    }
    assertThat(audits()).isEqualTo(before);
  }

  @Test
  void aNewPersonGetsANumberAbove348AndTwoAuditRows() {
    long before = audits();
    Map<String, Object> out =
        genealogy.createPerson(body("given", "Anna", "surname", "Novák", "sex", "F"), "letter 12");
    int id = ((Number) out.get("id")).intValue();
    assertThat(id).isGreaterThan(348);
    assertThat(out.get("sex")).isEqualTo("F");
    assertThat(out.get("primaryNameId")).isNotNull();
    assertThat(audits()).isEqualTo(before + 2);
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT op, before, after::text AS after, reason, changed_by FROM gen_change"
                + " WHERE entity = 'person' AND entity_id = ?",
            id);
    assertThat(audit.get("op")).isEqualTo("create");
    assertThat(audit.get("before")).isNull();
    assertThat((String) audit.get("after")).contains("\"id\": " + id);
    assertThat(audit.get("reason")).isEqualTo("letter 12");
    assertThat(audit.get("changed_by")).isEqualTo("genealogy-admin@example.com");
  }

  @Test
  void anUpdateKeepsWhatTheRowWasAndWhatItBecame() {
    int id = newPerson("Berta", "Musterfrau");
    long before = audits();
    genealogy.update(GenEntity.PERSON, id, body("notes", "born in Vienna"), "baptism register");
    assertThat(audits()).isEqualTo(before + 1);
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT before ->> 'notes' AS was, after ->> 'notes' AS now FROM gen_change"
                + " WHERE entity = 'person' AND entity_id = ? AND op = 'update'",
            id);
    assertThat(audit.get("was")).isNull();
    assertThat(audit.get("now")).isEqualTo("born in Vienna");
  }

  @Test
  void anEventDateMustBeGedcom7() {
    int id = newPerson("Carla", "Beispiel");
    for (String bad : new String[] {"31 FEB 1900", "sometime in 1900", "30.4.1913", "BET 1900"}) {
      assertThat(
              kindOf(
                  () ->
                      genealogy.create(
                          GenEntity.EVENT,
                          body("personId", id, "type", "BIRT", "dateText", bad),
                          "register")))
          .as(bad)
          .isEqualTo(Kind.BAD_REQUEST);
    }
    Map<String, Object> ok =
        genealogy.create(
            GenEntity.EVENT,
            body("personId", id, "type", "BIRT", "dateText", "ABT 1900"),
            "register");
    assertThat(ok.get("dateFrom")).isEqualTo("1900-01-01");
    assertThat(ok.get("dateTo")).isEqualTo("1900-12-31");
  }

  @Test
  void aThirdBirthParentIsRefused() {
    int third = newPerson("Dora", "Dritte");
    assertThat(
            kindOf(
                () ->
                    genealogy.create(
                        GenEntity.PARENT_LINK,
                        body("parentId", third, "childId", ALEXANDER_HANNIBAL, "type", "birth"),
                        "a mistake")))
        .isEqualTo(Kind.CONFLICT);
    genealogy.create(
        GenEntity.PARENT_LINK,
        body("parentId", third, "childId", ALEXANDER_HANNIBAL, "type", "foster"),
        "fostered");
  }

  @Test
  void aParentLinkThatMakesACycleIsRefused() {
    assertThat(
            kindOf(
                () ->
                    genealogy.create(
                        GenEntity.PARENT_LINK,
                        body(
                            "parentId",
                            ALEXANDER_HANNIBAL,
                            "childId",
                            HUMPRECHT,
                            "type",
                            "adopted"),
                        "a mistake")))
        .isEqualTo(Kind.CONFLICT);
  }

  @Test
  void aPersonWhoIsStillReferencedCannotBeDeleted() {
    long before = audits();
    GenealogyException e =
        (GenealogyException)
            org.assertj.core.api.Assertions.catchThrowable(
                () -> genealogy.delete(GenEntity.PERSON, ALEXANDER, "cleanup"));
    assertThat(e.kind()).isEqualTo(Kind.CONFLICT);
    assertThat(e.getMessage()).contains("merge");
    assertThat(audits()).isEqualTo(before);
  }

  @Test
  void deletingAnUnlinkedPersonTakesTheirNamesEventsAndCitationsEachAudited() {
    int id = newPerson("Emma", "Einzeln");
    Map<String, Object> event =
        genealogy.create(
            GenEntity.EVENT, body("personId", id, "type", "BIRT", "dateText", "1900"), "x");
    long source = jdbc.queryForObject("SELECT id FROM gen_source ORDER BY id LIMIT 1", Long.class);
    genealogy.create(
        GenEntity.CITATION,
        body("sourceId", source, "eventId", event.get("id"), "quality", 2),
        "x");
    long before = audits();
    Map<String, Object> out = genealogy.delete(GenEntity.PERSON, id, "duplicate entry");
    assertThat(out.get("deleted")).isEqualTo(4);
    assertThat(audits()).isEqualTo(before + 4);
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM gen_person WHERE id = ?", Integer.class, id))
        .isZero();
  }

  @Test
  void aCitationProvesExactlyOneFact() {
    long source = jdbc.queryForObject("SELECT id FROM gen_source ORDER BY id LIMIT 1", Long.class);
    assertThat(kindOf(() -> genealogy.create(GenEntity.CITATION, body("sourceId", source), "x")))
        .isEqualTo(Kind.BAD_REQUEST);
    assertThat(
            kindOf(
                () ->
                    genealogy.create(
                        GenEntity.CITATION,
                        body("sourceId", source, "personId", ALEXANDER, "nameId", 1),
                        "x")))
        .isEqualTo(Kind.BAD_REQUEST);
    assertThat(
            kindOf(
                () ->
                    genealogy.create(
                        GenEntity.CITATION,
                        body("sourceId", source, "personId", ALEXANDER, "quality", 4),
                        "x")))
        .isEqualTo(Kind.BAD_REQUEST);
  }

  @Test
  void makingANamePrimaryDemotesTheOldOneInTheSameWrite() {
    int id = newPerson("Franziska", "Erst");
    long before = audits();
    Map<String, Object> alias =
        genealogy.create(
            GenEntity.NAME,
            body(
                "personId",
                id,
                "type",
                "married",
                "given",
                "Franziska",
                "surname",
                "Zweit",
                "isPrimary",
                true),
            "marriage record");
    assertThat(audits()).isEqualTo(before + 2);
    assertThat(
            jdbc.queryForList(
                "SELECT id FROM gen_name WHERE person_id = ? AND is_primary", Long.class, id))
        .containsExactly(((Number) alias.get("id")).longValue());
    assertThat(
            kindOf(
                () ->
                    genealogy.update(
                        GenEntity.NAME,
                        ((Number) alias.get("id")).longValue(),
                        body("isPrimary", false),
                        "x")))
        .isEqualTo(Kind.CONFLICT);
    assertThat(
            kindOf(
                () ->
                    genealogy.delete(GenEntity.NAME, ((Number) alias.get("id")).longValue(), "x")))
        .isEqualTo(Kind.CONFLICT);
  }

  @Test
  void theTreeShowsAnEditOnceItIsCommitted() {
    long name =
        jdbc.queryForObject(
            "SELECT id FROM gen_name WHERE person_id = ? AND is_primary",
            Long.class,
            ALEXANDER_HANNIBAL);
    assertThat(tree.getPerson(ALEXANDER_HANNIBAL).name)
        .isEqualTo("Alexander Hannibal Czernin Fishlock");
    genealogy.update(GenEntity.NAME, name, body("surname", "Fishlock"), "split the surname");
    genealogy.update(GenEntity.NAME, name, body("given", "Alexander Hannibal Czernin"), "same");
    assertThat(tree.getPerson(ALEXANDER_HANNIBAL).name)
        .isEqualTo("Alexander Hannibal Czernin Fishlock");
    genealogy.update(GenEntity.NAME, name, body("given", "Alexander Hannibal"), "same");
    assertThat(tree.getPerson(ALEXANDER_HANNIBAL).name).isEqualTo("Alexander Hannibal Fishlock");
  }

  @Test
  void unknownFieldsAndWrongTypesAreBadRequests() {
    assertThat(
            kindOf(() -> genealogy.update(GenEntity.PERSON, ALEXANDER, body("colour", "red"), "x")))
        .isEqualTo(Kind.BAD_REQUEST);
    assertThat(
            kindOf(() -> genealogy.update(GenEntity.PERSON, ALEXANDER, body("living", "yes"), "x")))
        .isEqualTo(Kind.BAD_REQUEST);
    assertThat(kindOf(() -> genealogy.update(GenEntity.PERSON, ALEXANDER, body("sex", "X"), "x")))
        .isEqualTo(Kind.BAD_REQUEST);
    assertThat(kindOf(() -> genealogy.update(GenEntity.PERSON, 999999, body("notes", "x"), "x")))
        .isEqualTo(Kind.NOT_FOUND);
  }

  @Test
  void aMergedPersonCannotBeGivenNewFacts() {
    assertThat(
            kindOf(
                () ->
                    genealogy.create(GenEntity.EVENT, body("personId", 122, "type", "RESI"), "x")))
        .isEqualTo(Kind.CONFLICT);
  }

  @Test
  void aRecordLinkPageMustBeInThatRecord() {
    long recordA = GenealogyFixtures.record(jdbc);
    long recordB = GenealogyFixtures.record(jdbc);
    long pageB = GenealogyFixtures.page(jdbc, storage, recordB, 1, "x".getBytes(), "image/jpeg");
    assertThat(
            kindOf(
                () ->
                    genealogy.create(
                        GenEntity.RECORD_LINK,
                        body(
                            "personId",
                            ALEXANDER,
                            "recordId",
                            recordA,
                            "pageId",
                            pageB,
                            "role",
                            "subject"),
                        "x")))
        .isEqualTo(Kind.BAD_REQUEST);
    genealogy.create(
        GenEntity.RECORD_LINK,
        body("personId", ALEXANDER, "recordId", recordB, "pageId", pageB, "role", "subject"),
        "x");
  }

  @Test
  void aCropMustLieInsideTheStoredImage() throws Exception {
    long record = GenealogyFixtures.record(jdbc);
    long page =
        GenealogyFixtures.page(
            jdbc,
            storage,
            record,
            1,
            GenealogyFixtures.encode(GenealogyFixtures.pattern(40, 30), "png"),
            "image/png");
    for (int[] crop : new int[][] {{30, 0, 11, 10}, {0, 25, 10, 6}, {-1, 0, 5, 5}, {0, 0, 0, 5}}) {
      assertThat(
              kindOf(
                  () ->
                      genealogy.create(
                          GenEntity.MEDIA,
                          body(
                              "pageId",
                              page,
                              "cropLeft",
                              crop[0],
                              "cropTop",
                              crop[1],
                              "cropWidth",
                              crop[2],
                              "cropHeight",
                              crop[3]),
                          "x")))
          .as(java.util.Arrays.toString(crop))
          .isEqualTo(Kind.BAD_REQUEST);
    }
    assertThat(
            kindOf(
                () ->
                    genealogy.create(
                        GenEntity.MEDIA, body("pageId", page, "cropLeft", 0, "cropTop", 0), "x")))
        .isEqualTo(Kind.BAD_REQUEST);
    genealogy.create(
        GenEntity.MEDIA,
        body("pageId", page, "cropLeft", 30, "cropTop", 20, "cropWidth", 10, "cropHeight", 10),
        "exactly the bottom-right corner");
    genealogy.create(GenEntity.MEDIA, body("pageId", page), "the whole page");
  }

  @Test
  void aSecondPortraitReplacesTheFirst() throws Exception {
    long record = GenealogyFixtures.record(jdbc);
    long page =
        GenealogyFixtures.page(
            jdbc,
            storage,
            record,
            1,
            GenealogyFixtures.encode(GenealogyFixtures.pattern(4, 4), "png"),
            "image/png");
    long m1 =
        ((Number) genealogy.create(GenEntity.MEDIA, body("pageId", page), "x").get("id"))
            .longValue();
    long m2 =
        ((Number) genealogy.create(GenEntity.MEDIA, body("pageId", page), "x").get("id"))
            .longValue();
    genealogy.create(
        GenEntity.MEDIA_PERSON,
        body("mediaId", m1, "personId", ALEXANDER, "isPortrait", true),
        "x");
    genealogy.create(
        GenEntity.MEDIA_PERSON,
        body("mediaId", m2, "personId", ALEXANDER, "isPortrait", true),
        "x");
    assertThat(
            jdbc.queryForList(
                "SELECT media_id FROM gen_media_person WHERE person_id = ? AND is_portrait",
                Long.class,
                ALEXANDER))
        .containsExactly(m2);
  }

  @Test
  void anEventPlaceGivenByNameIsReusedOrMadeInTheSameWrite() {
    int id = newPerson("Greta", "Ort");
    long before = audits();
    Map<String, Object> first =
        genealogy.create(
            GenEntity.EVENT,
            body("personId", id, "type", "RESI", "placeName", " Nirgendwo-Test "),
            "census");
    assertThat(audits()).isEqualTo(before + 2);
    Map<String, Object> second =
        genealogy.create(
            GenEntity.EVENT,
            body("personId", id, "type", "RESI", "placeName", "Nirgendwo-Test"),
            "census");
    assertThat(second.get("placeId")).isEqualTo(first.get("placeId"));
    assertThat(audits()).isEqualTo(before + 3);
    Map<String, Object> cleared =
        genealogy.update(
            GenEntity.EVENT, ((Number) second.get("id")).longValue(), body("placeName", ""), "x");
    assertThat(cleared.get("placeId")).isNull();
  }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd backend && ./gradlew test --tests '*GenealogyServiceTest'`

Expected: FAIL: compilation error `cannot find symbol: class GenealogyService`

- [ ] **Step 3: Write the entity catalogue, the exception and the image size reader**

Create `backend/src/main/java/place/icomb/archiver/genealogy/GenEntity.java`:

```java
package place.icomb.archiver.genealogy;

import java.util.Arrays;
import java.util.List;

/**
 * Every kind of row a genealogy write can change, with the fields a caller may set.
 *
 * <p>{@link #key()} is what {@code gen_change.entity} records. {@code page_person_match} and {@code
 * app_user} are here only because a merge re-points them; they cannot be written directly.
 */
public enum GenEntity {
  PERSON(
      "person",
      "gen_person",
      true,
      f("sex", Type.TEXT),
      f("living", Type.BOOL),
      f("notes", Type.TEXT),
      f("needsReview", Type.TEXT)),
  NAME(
      "name",
      "gen_name",
      true,
      f("personId", Type.INT),
      f("type", Type.TEXT),
      f("given", Type.TEXT),
      f("surname", Type.TEXT),
      f("prefix", Type.TEXT),
      f("suffix", Type.TEXT),
      f("nickname", Type.TEXT),
      f("isPrimary", Type.BOOL),
      f("sortOrder", Type.INT)),
  COUPLE(
      "couple",
      "gen_couple",
      true,
      f("person1Id", Type.INT),
      f("person2Id", Type.INT),
      f("type", Type.TEXT),
      f("sortOrder", Type.INT),
      f("notes", Type.TEXT)),
  PARENT_LINK(
      "parent_link",
      "gen_parent_child",
      true,
      f("parentId", Type.INT),
      f("childId", Type.INT),
      f("type", Type.TEXT),
      f("coupleId", Type.LONG)),
  PLACE(
      "place",
      "gen_place",
      true,
      f("name", Type.TEXT),
      f("parentId", Type.LONG),
      f("lat", Type.DECIMAL),
      f("lon", Type.DECIMAL)),
  PLACE_NAME(
      "place_name",
      "gen_place_name",
      true,
      f("placeId", Type.LONG),
      f("name", Type.TEXT),
      f("lang", Type.TEXT),
      f("fromYear", Type.INT),
      f("toYear", Type.INT)),
  EVENT(
      "event",
      "gen_event",
      true,
      f("personId", Type.INT),
      f("coupleId", Type.LONG),
      f("type", Type.TEXT),
      f("dateText", Type.TEXT),
      f("placeId", Type.LONG),
      f("description", Type.TEXT),
      f("sortOrder", Type.INT)),
  SOURCE(
      "source",
      "gen_source",
      true,
      f("title", Type.TEXT),
      f("author", Type.TEXT),
      f("recordId", Type.LONG),
      f("url", Type.TEXT),
      f("notes", Type.TEXT)),
  CITATION(
      "citation",
      "gen_citation",
      true,
      f("sourceId", Type.LONG),
      f("pageId", Type.LONG),
      f("detail", Type.TEXT),
      f("quality", Type.INT),
      f("eventId", Type.LONG),
      f("nameId", Type.LONG),
      f("coupleId", Type.LONG),
      f("parentChildId", Type.LONG),
      f("personId", Type.INT)),
  RECORD_LINK(
      "record_link",
      "gen_person_record",
      true,
      f("personId", Type.INT),
      f("recordId", Type.LONG),
      f("pageId", Type.LONG),
      f("role", Type.TEXT),
      f("note", Type.TEXT),
      f("quality", Type.INT)),
  MEDIA(
      "media",
      "gen_media",
      true,
      f("pageId", Type.LONG),
      f("cropLeft", Type.INT),
      f("cropTop", Type.INT),
      f("cropWidth", Type.INT),
      f("cropHeight", Type.INT),
      f("caption", Type.TEXT),
      f("dateText", Type.TEXT)),
  MEDIA_PERSON(
      "media_person",
      "gen_media_person",
      true,
      f("mediaId", Type.LONG),
      f("personId", Type.INT),
      f("isPortrait", Type.BOOL),
      f("sortOrder", Type.INT)),
  PAGE_PERSON_MATCH("page_person_match", "page_person_match", false),
  APP_USER("app_user", "app_user", false);

  public enum Type {
    INT,
    LONG,
    TEXT,
    BOOL,
    DECIMAL
  }

  public record Field(String json, String column, Type type) {}

  private final String key;
  private final String table;
  private final boolean writable;
  private final List<Field> fields;

  GenEntity(String key, String table, boolean writable, Field... fields) {
    this.key = key;
    this.table = table;
    this.writable = writable;
    this.fields = List.of(fields);
  }

  private static Field f(String json, Type type) {
    return new Field(json, snake(json), type);
  }

  public String key() {
    return key;
  }

  public String table() {
    return table;
  }

  /** False for the two tables outside the genealogy that only a merge touches. */
  public boolean writable() {
    return writable;
  }

  public List<Field> fields() {
    return fields;
  }

  public static GenEntity ofKey(String key) {
    return Arrays.stream(values())
        .filter(e -> e.key.equals(key))
        .findFirst()
        .orElseThrow(() -> GenealogyException.badRequest("unknown entity '%s'".formatted(key)));
  }

  static String snake(String camel) {
    StringBuilder sb = new StringBuilder();
    for (char c : camel.toCharArray()) {
      if (Character.isUpperCase(c)) {
        sb.append('_').append(Character.toLowerCase(c));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
```

Create `backend/src/main/java/place/icomb/archiver/genealogy/GenealogyException.java`:

```java
package place.icomb.archiver.genealogy;

/** A refused genealogy write: the controller answers 400, 404 or 409 with {"error": message}. */
public class GenealogyException extends RuntimeException {

  public enum Kind {
    NOT_FOUND,
    BAD_REQUEST,
    CONFLICT
  }

  private final Kind kind;

  public GenealogyException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  public static GenealogyException notFound(String message) {
    return new GenealogyException(Kind.NOT_FOUND, message);
  }

  public static GenealogyException badRequest(String message) {
    return new GenealogyException(Kind.BAD_REQUEST, message);
  }

  public static GenealogyException conflict(String message) {
    return new GenealogyException(Kind.CONFLICT, message);
  }
}
```

Create `backend/src/main/java/place/icomb/archiver/genealogy/MediaImages.java`:

```java
package place.icomb.archiver.genealogy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.service.StorageService;

/** The stored scan behind a page, read for its pixel size. */
@Service
public class MediaImages {

  public record Dimensions(int width, int height) {}

  private final JdbcTemplate jdbc;
  private final AttachmentRepository attachments;
  private final StorageService storage;

  public MediaImages(JdbcTemplate jdbc, AttachmentRepository attachments, StorageService storage) {
    this.jdbc = jdbc;
    this.attachments = attachments;
    this.storage = storage;
  }

  /** The pixel size of the stored original, read from its header without decoding it. */
  public Dimensions dimensions(long pageId) {
    Path file = original(pageId);
    try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
      Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
      if (readers == null || !readers.hasNext()) {
        throw GenealogyException.conflict("the image of page %d cannot be read".formatted(pageId));
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(in);
        return new Dimensions(reader.getWidth(0), reader.getHeight(0));
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw GenealogyException.conflict("the image of page %d cannot be read".formatted(pageId));
    }
  }

  Attachment attachment(long pageId) {
    List<Map<String, Object>> page =
        jdbc.queryForList("SELECT attachment_id FROM page WHERE id = ?", pageId);
    if (page.isEmpty()) {
      throw GenealogyException.notFound("page %d not found".formatted(pageId));
    }
    long attachmentId = ((Number) page.get(0).get("attachment_id")).longValue();
    return attachments
        .findById(attachmentId)
        .orElseThrow(
            () -> GenealogyException.notFound("page %d has no stored image".formatted(pageId)));
  }

  Path original(long pageId) {
    return storage.getPath(attachment(pageId));
  }
}
```

- [ ] **Step 4: Write the service**

Create `backend/src/main/java/place/icomb/archiver/genealogy/GenealogyService.java`:

```java
package place.icomb.archiver.genealogy;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import place.icomb.archiver.genealogy.GenEntity.Field;
import place.icomb.archiver.service.FamilyTreeService;

/**
 * The only writer of the genealogy. Every write takes a reason, is validated, and appends one
 * {@code gen_change} row per row it changed, holding the row before and after; that is what {@link
 * #undo} restores from.
 *
 * <p>Rows that belong to another (a person's names and events, an event's citations) are deleted
 * with it, each audited. Rows that merely point at it (a couple, a parent link, a match, a user's
 * "this is me") block the delete with a 409; merging is how a duplicate person goes.
 */
@Service
public class GenealogyService {

  static final Set<String> SEXES = Set.of("M", "F", "U");
  static final Set<String> NAME_TYPES =
      Set.of("birth", "married", "alias", "religious", "title", "other");
  static final Set<String> COUPLE_TYPES = Set.of("marriage", "partnership", "unknown");
  static final Set<String> LINK_TYPES = Set.of("birth", "adopted", "step", "foster", "unknown");
  static final Set<String> EVENT_TYPES =
      Set.of(
          "BIRT", "CHR", "BAPM", "DEAT", "BURI", "MARR", "DIV", "ADOP", "OCCU", "TITL", "RESI",
          "NATU", "EMIG", "IMMI", "EDUC", "EVEN");
  static final Set<String> ROLES =
      Set.of(
          "subject",
          "mentioned",
          "author",
          "signatory",
          "witness",
          "recipient",
          "pictured",
          "other");

  private record Ref(GenEntity entity, String column) {}

  /** Rows deleted along with the row they belong to. */
  private static final Map<GenEntity, List<Ref>> OWNED =
      Map.of(
          GenEntity.PERSON,
          List.of(
              new Ref(GenEntity.CITATION, "person_id"),
              new Ref(GenEntity.EVENT, "person_id"),
              new Ref(GenEntity.NAME, "person_id")),
          GenEntity.NAME,
          List.of(new Ref(GenEntity.CITATION, "name_id")),
          GenEntity.EVENT,
          List.of(new Ref(GenEntity.CITATION, "event_id")),
          GenEntity.COUPLE,
          List.of(new Ref(GenEntity.CITATION, "couple_id"), new Ref(GenEntity.EVENT, "couple_id")),
          GenEntity.PARENT_LINK,
          List.of(new Ref(GenEntity.CITATION, "parent_child_id")),
          GenEntity.PLACE,
          List.of(new Ref(GenEntity.PLACE_NAME, "place_id")),
          GenEntity.MEDIA,
          List.of(new Ref(GenEntity.MEDIA_PERSON, "media_id")));

  /** Rows that point at a row and so forbid deleting it. */
  private static final Map<GenEntity, List<Ref>> BLOCKING =
      Map.of(
          GenEntity.PERSON,
          List.of(
              new Ref(GenEntity.COUPLE, "person1_id"),
              new Ref(GenEntity.COUPLE, "person2_id"),
              new Ref(GenEntity.PARENT_LINK, "parent_id"),
              new Ref(GenEntity.PARENT_LINK, "child_id"),
              new Ref(GenEntity.RECORD_LINK, "person_id"),
              new Ref(GenEntity.MEDIA_PERSON, "person_id"),
              new Ref(GenEntity.PAGE_PERSON_MATCH, "person_id"),
              new Ref(GenEntity.APP_USER, "family_tree_person_id"),
              new Ref(GenEntity.PERSON, "merged_into")),
          GenEntity.COUPLE,
          List.of(new Ref(GenEntity.PARENT_LINK, "couple_id")),
          GenEntity.PLACE,
          List.of(new Ref(GenEntity.EVENT, "place_id"), new Ref(GenEntity.PLACE, "parent_id")),
          GenEntity.SOURCE,
          List.of(new Ref(GenEntity.CITATION, "source_id")));

  private final JdbcTemplate jdbc;
  private final FamilyTreeService tree;
  private final MediaImages images;

  public GenealogyService(JdbcTemplate jdbc, FamilyTreeService tree, MediaImages images) {
    this.jdbc = jdbc;
    this.tree = tree;
    this.images = images;
  }

  /** A new person with their primary name ({@code given}, {@code surname}, ...). */
  @Transactional
  public Map<String, Object> createPerson(Map<String, Object> body, String reason) {
    requireReason(reason);
    Map<String, Object> personBody = new LinkedHashMap<>();
    Map<String, Object> nameBody = new LinkedHashMap<>();
    Set<String> personFields = jsonNames(GenEntity.PERSON);
    for (var e : body.entrySet()) {
      if (personFields.contains(e.getKey())) {
        personBody.put(e.getKey(), e.getValue());
      } else {
        nameBody.put(e.getKey(), e.getValue());
      }
    }
    Map<String, Object> person = create(GenEntity.PERSON, personBody, reason);
    nameBody.put("personId", person.get("id"));
    nameBody.put("isPrimary", true);
    nameBody.putIfAbsent("type", "birth");
    Map<String, Object> name = create(GenEntity.NAME, nameBody, reason);
    Map<String, Object> out = new LinkedHashMap<>(person);
    out.put("primaryNameId", name.get("id"));
    return out;
  }

  @Transactional
  public Map<String, Object> create(GenEntity entity, Map<String, Object> body, String reason) {
    requireReason(reason);
    requireWritable(entity);
    Map<String, Object> values = coerce(entity, withPlace(entity, body, reason));
    validate(entity, null, values, reason);
    derive(entity, values);
    List<String> columns = new ArrayList<>(values.keySet());
    String sql =
        columns.isEmpty()
            ? "INSERT INTO %s AS t DEFAULT VALUES RETURNING to_jsonb(t.*)::text"
                .formatted(entity.table())
            : "INSERT INTO %s AS t (%s) VALUES (%s) RETURNING to_jsonb(t.*)::text"
                .formatted(
                    entity.table(),
                    String.join(", ", columns),
                    String.join(", ", Collections.nCopies(columns.size(), "?")));
    Map<String, Object> after =
        Rows.json(jdbc.queryForObject(sql, String.class, values.values().toArray()));
    audit(entity, id(after), "create", null, after, reason);
    changed();
    return Rows.camel(after);
  }

  @Transactional
  public Map<String, Object> update(
      GenEntity entity, long id, Map<String, Object> body, String reason) {
    requireReason(reason);
    requireWritable(entity);
    Map<String, Object> current = lock(entity, id);
    Map<String, Object> values = coerce(entity, withPlace(entity, body, reason));
    if (values.isEmpty()) {
      throw GenealogyException.badRequest("nothing to change");
    }
    Map<String, Object> merged = new LinkedHashMap<>(current);
    merged.putAll(values);
    validate(entity, current, merged, reason);
    derive(entity, merged);
    if (values.containsKey("date_text")) {
      for (String k :
          entity == GenEntity.EVENT
              ? List.of("date_text", "date_from", "date_to")
              : List.of("date_text")) {
        values.put(k, merged.get(k));
      }
    }
    Map<String, Object> after = write(entity, id, values);
    audit(entity, id, "update", current, after, reason);
    changed();
    return Rows.camel(after);
  }

  @Transactional
  public Map<String, Object> delete(GenEntity entity, long id, String reason) {
    requireReason(reason);
    requireWritable(entity);
    Map<String, Object> current = lock(entity, id);
    if (entity == GenEntity.NAME && Boolean.TRUE.equals(current.get("is_primary"))) {
      throw GenealogyException.conflict(
          "name %d is the primary name; make another name primary first".formatted(id));
    }
    List<String> blockers = blockers(entity, id);
    if (!blockers.isEmpty()) {
      throw GenealogyException.conflict(
          "%s %d is still referenced by %s%s"
              .formatted(
                  entity.key(),
                  id,
                  String.join(", ", blockers),
                  entity == GenEntity.PERSON ? "; merge the person instead" : ""));
    }
    int removed = deleteWithOwned(entity, id, "delete", reason);
    changed();
    return Map.of("entity", entity.key(), "id", id, "deleted", removed);
  }

  /**
   * A media item made from a page and attached to one person, as one write; with {@code portrait}
   * it becomes their portrait.
   */
  @Transactional
  public Map<String, Object> createMedia(
      Map<String, Object> body, int personId, boolean portrait, String reason) {
    Map<String, Object> media = create(GenEntity.MEDIA, body, reason);
    Map<String, Object> link = new LinkedHashMap<>();
    link.put("mediaId", media.get("id"));
    link.put("personId", personId);
    link.put("isPortrait", portrait);
    create(GenEntity.MEDIA_PERSON, link, reason);
    return media;
  }

  /**
   * An event may name its place as {@code placeName}: the place of that exact name, made (and
   * audited) in the same write when there is none.
   */
  private Map<String, Object> withPlace(GenEntity entity, Map<String, Object> body, String reason) {
    if (entity != GenEntity.EVENT || !body.containsKey("placeName")) {
      return body;
    }
    Map<String, Object> out = new LinkedHashMap<>(body);
    Object name = out.remove("placeName");
    if (name != null && !(name instanceof String)) {
      throw GenealogyException.badRequest("placeName must be a string");
    }
    if (name == null || ((String) name).isBlank()) {
      out.put("placeId", null);
      return out;
    }
    String trimmed = ((String) name).trim();
    List<Long> found =
        jdbc.queryForList(
            "SELECT id FROM gen_place WHERE name = ? ORDER BY id LIMIT 1", Long.class, trimmed);
    Object placeId =
        found.isEmpty()
            ? create(GenEntity.PLACE, Map.of("name", trimmed), reason).get("id")
            : found.get(0);
    out.put("placeId", placeId);
    return out;
  }

  /** The audit, newest first, optionally only one entity kind or one row. */
  public List<Map<String, Object>> changes(String entity, Long entityId, int limit) {
    StringBuilder sql = new StringBuilder("SELECT * FROM gen_change WHERE true");
    List<Object> args = new ArrayList<>();
    if (entity != null) {
      sql.append(" AND entity = ?");
      args.add(GenEntity.ofKey(entity).key());
    }
    if (entityId != null) {
      sql.append(" AND entity_id = ?");
      args.add(entityId);
    }
    sql.append(" ORDER BY id DESC LIMIT ?");
    args.add(Math.max(1, Math.min(limit, 500)));
    return Rows.camel(jdbc.queryForList(sql.toString(), args.toArray()));
  }

  /** Every change that touched one person, their names, events, couples and links. */
  public List<Map<String, Object>> personChanges(int personId, int limit) {
    return Rows.camel(
        jdbc.queryForList(
            """
            SELECT * FROM gen_change
            WHERE (entity = 'person' AND entity_id = ?)
               OR (entity IN ('name', 'event', 'citation', 'record_link', 'media_person')
                   AND ((before ->> 'person_id')::int = ? OR (after ->> 'person_id')::int = ?))
               OR (entity = 'couple'
                   AND ? IN ((before ->> 'person1_id')::int, (before ->> 'person2_id')::int,
                             (after ->> 'person1_id')::int, (after ->> 'person2_id')::int))
               OR (entity = 'parent_link'
                   AND ? IN ((before ->> 'parent_id')::int, (before ->> 'child_id')::int,
                             (after ->> 'parent_id')::int, (after ->> 'child_id')::int))
            ORDER BY id DESC LIMIT ?
            """,
            personId,
            personId,
            personId,
            personId,
            personId,
            Math.max(1, Math.min(limit, 500))));
  }

  /** People whose import or editing left something to check, with the reason. */
  public List<Map<String, Object>> review() {
    return Rows.camel(
        jdbc.queryForList(
            """
            SELECT p.id, concat_ws(' ', n.given, n.surname) AS name, p.needs_review,
                   p.legacy_entry
            FROM gen_person p
            LEFT JOIN gen_name n ON n.person_id = p.id AND n.is_primary
            WHERE p.needs_review IS NOT NULL AND p.merged_into IS NULL
            ORDER BY p.id
            """));
  }

  private void validate(
      GenEntity entity, Map<String, Object> current, Map<String, Object> row, String reason) {
    Long self = current == null ? null : id(current);
    switch (entity) {
      case PERSON -> {
        if (current != null && current.get("merged_into") != null) {
          throw GenealogyException.conflict(
              "person %d was merged into %s".formatted(self, current.get("merged_into")));
        }
        oneOf("sex", row.get("sex"), SEXES, false);
      }
      case NAME -> {
        livePerson(row.get("person_id"), "personId", true);
        oneOf("type", row.get("type"), NAME_TYPES, false);
        if (blank(row.get("given")) && blank(row.get("surname"))) {
          throw GenealogyException.badRequest("a name needs a given name or a surname");
        }
        boolean primary = Boolean.TRUE.equals(row.get("is_primary"));
        if (current != null && Boolean.TRUE.equals(current.get("is_primary")) && !primary) {
          throw GenealogyException.conflict(
              "name %d is the primary name; make another name primary instead".formatted(self));
        }
        if (current != null
            && !row.get("person_id").equals(current.get("person_id"))
            && Boolean.TRUE.equals(current.get("is_primary"))) {
          throw GenealogyException.conflict("a primary name cannot move to another person");
        }
        if (primary) {
          demoteOther(
              GenEntity.NAME, "is_primary", "person_id", row.get("person_id"), self, reason);
        }
      }
      case COUPLE -> {
        livePerson(row.get("person1_id"), "person1Id", true);
        livePerson(row.get("person2_id"), "person2Id", true);
        if (row.get("person1_id").equals(row.get("person2_id"))) {
          throw GenealogyException.badRequest("a couple needs two different people");
        }
        oneOf("type", row.get("type"), COUPLE_TYPES, false);
      }
      case PARENT_LINK -> validateParentLink(self, row);
      case PLACE -> {
        if (blank(row.get("name"))) throw GenealogyException.badRequest("name is required");
        exists(GenEntity.PLACE, row.get("parent_id"), "parentId");
        if (self != null && row.get("parent_id") != null) {
          Long cur = ((Number) row.get("parent_id")).longValue();
          Set<Long> seen = new HashSet<>();
          while (cur != null && seen.add(cur)) {
            if (cur.equals(self)) {
              throw GenealogyException.conflict("a place cannot be inside itself");
            }
            cur =
                jdbc.queryForObject(
                    "SELECT parent_id FROM gen_place WHERE id = ?", Long.class, cur);
          }
        }
        range("lat", row.get("lat"), -90, 90);
        range("lon", row.get("lon"), -180, 180);
      }
      case PLACE_NAME -> {
        requireRef(GenEntity.PLACE, row.get("place_id"), "placeId");
        if (blank(row.get("name"))) throw GenealogyException.badRequest("name is required");
        Object lang = row.get("lang");
        if (lang != null && !lang.toString().matches("[a-z]{2}")) {
          throw GenealogyException.badRequest("lang must be a two-letter ISO 639-1 code");
        }
        Object from = row.get("from_year");
        Object to = row.get("to_year");
        if (from != null && to != null && ((Number) from).intValue() > ((Number) to).intValue()) {
          throw GenealogyException.badRequest("fromYear is after toYear");
        }
      }
      case EVENT -> {
        if ((row.get("person_id") == null) == (row.get("couple_id") == null)) {
          throw GenealogyException.badRequest("an event belongs to exactly one person or couple");
        }
        livePerson(row.get("person_id"), "personId", false);
        exists(GenEntity.COUPLE, row.get("couple_id"), "coupleId");
        oneOf("type", row.get("type"), EVENT_TYPES, true);
        date(row.get("date_text"));
        exists(GenEntity.PLACE, row.get("place_id"), "placeId");
      }
      case SOURCE -> {
        if (blank(row.get("title"))) throw GenealogyException.badRequest("title is required");
        existsIn("record", row.get("record_id"), "recordId");
      }
      case CITATION -> {
        requireRef(GenEntity.SOURCE, row.get("source_id"), "sourceId");
        existsIn("page", row.get("page_id"), "pageId");
        quality(row.get("quality"));
        int targets = 0;
        for (Ref target :
            List.of(
                new Ref(GenEntity.EVENT, "event_id"),
                new Ref(GenEntity.NAME, "name_id"),
                new Ref(GenEntity.COUPLE, "couple_id"),
                new Ref(GenEntity.PARENT_LINK, "parent_child_id"),
                new Ref(GenEntity.PERSON, "person_id"))) {
          if (row.get(target.column()) != null) {
            targets++;
            exists(target.entity(), row.get(target.column()), target.column());
          }
        }
        if (targets != 1) {
          throw GenealogyException.badRequest(
              "a citation proves exactly one fact: give one of eventId, nameId, coupleId,"
                  + " parentChildId, personId");
        }
      }
      case RECORD_LINK -> {
        livePerson(row.get("person_id"), "personId", true);
        existsIn("record", row.get("record_id"), "recordId");
        if (row.get("record_id") == null) {
          throw GenealogyException.badRequest("recordId is required");
        }
        if (row.get("page_id") != null) {
          List<Long> owner =
              jdbc.queryForList(
                  "SELECT record_id FROM page WHERE id = ?", Long.class, row.get("page_id"));
          if (owner.isEmpty()) {
            throw GenealogyException.notFound("page %s not found".formatted(row.get("page_id")));
          }
          if (owner.get(0) != ((Number) row.get("record_id")).longValue()) {
            throw GenealogyException.badRequest(
                "page %s is not in record %s".formatted(row.get("page_id"), row.get("record_id")));
          }
        }
        oneOf("role", row.get("role"), ROLES, false);
        quality(row.get("quality"));
      }
      case MEDIA -> validateMedia(row);
      case MEDIA_PERSON -> {
        requireRef(GenEntity.MEDIA, row.get("media_id"), "mediaId");
        livePerson(row.get("person_id"), "personId", true);
        Integer clash =
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_media_person WHERE media_id = ? AND person_id = ?"
                    + " AND id IS DISTINCT FROM ?::bigint",
                Integer.class,
                row.get("media_id"),
                row.get("person_id"),
                self);
        if (clash > 0) {
          throw GenealogyException.conflict("that person is already linked to that image");
        }
        if (Boolean.TRUE.equals(row.get("is_portrait"))) {
          demoteOther(
              GenEntity.MEDIA_PERSON,
              "is_portrait",
              "person_id",
              row.get("person_id"),
              self,
              reason);
        }
      }
      default -> throw GenealogyException.badRequest(entity.key() + " cannot be written");
    }
  }

  private void validateParentLink(Long self, Map<String, Object> row) {
    livePerson(row.get("parent_id"), "parentId", true);
    livePerson(row.get("child_id"), "childId", true);
    int parent = ((Number) row.get("parent_id")).intValue();
    int child = ((Number) row.get("child_id")).intValue();
    if (parent == child) {
      throw GenealogyException.badRequest("a person cannot be their own parent");
    }
    oneOf("type", row.get("type"), LINK_TYPES, false);
    Integer duplicate =
        jdbc.queryForObject(
            "SELECT count(*) FROM gen_parent_child WHERE parent_id = ? AND child_id = ?"
                + " AND id IS DISTINCT FROM ?::bigint",
            Integer.class,
            parent,
            child,
            self);
    if (duplicate > 0) {
      throw GenealogyException.conflict(
          "person %d is already a parent of %d".formatted(parent, child));
    }
    if ("birth".equals(row.getOrDefault("type", "birth"))) {
      Integer birthParents =
          jdbc.queryForObject(
              "SELECT count(*) FROM gen_parent_child WHERE child_id = ? AND type = 'birth'"
                  + " AND id IS DISTINCT FROM ?::bigint",
              Integer.class,
              child,
              self);
      if (birthParents >= 2) {
        throw GenealogyException.conflict(
            "person %d already has two birth parents".formatted(child));
      }
    }
    if (isAncestor(child, parent, self)) {
      throw GenealogyException.conflict(
          "person %d is an ancestor of %d; the link would make a cycle".formatted(child, parent));
    }
    Object couple = row.get("couple_id");
    if (couple != null) {
      List<Map<String, Object>> c =
          jdbc.queryForList("SELECT person1_id, person2_id FROM gen_couple WHERE id = ?", couple);
      if (c.isEmpty()) throw GenealogyException.notFound("couple %s not found".formatted(couple));
      if (!List.of(c.get(0).get("person1_id"), c.get(0).get("person2_id")).contains(parent)) {
        throw GenealogyException.badRequest(
            "couple %s does not include parent %d".formatted(couple, parent));
      }
    }
  }

  /** True when {@code ancestor} is {@code person} or above them through any parent link. */
  private boolean isAncestor(int ancestor, int person, Long ignoringLink) {
    Deque<Integer> queue = new ArrayDeque<>(List.of(person));
    Set<Integer> seen = new HashSet<>();
    while (!queue.isEmpty()) {
      int cur = queue.poll();
      if (cur == ancestor) return true;
      if (!seen.add(cur)) continue;
      queue.addAll(
          jdbc.queryForList(
              "SELECT parent_id FROM gen_parent_child WHERE child_id = ? AND id IS DISTINCT FROM ?::bigint",
              Integer.class,
              cur,
              ignoringLink));
    }
    return false;
  }

  private void validateMedia(Map<String, Object> row) {
    Object page = row.get("page_id");
    if (page == null) throw GenealogyException.badRequest("pageId is required");
    existsIn("page", page, "pageId");
    date(row.get("date_text"));
    List<String> crop = List.of("crop_left", "crop_top", "crop_width", "crop_height");
    long given = crop.stream().filter(c -> row.get(c) != null).count();
    if (given == 0) return;
    if (given != 4) {
      throw GenealogyException.badRequest(
          "give all of cropLeft, cropTop, cropWidth and cropHeight, or none for the whole page");
    }
    int left = ((Number) row.get("crop_left")).intValue();
    int top = ((Number) row.get("crop_top")).intValue();
    int width = ((Number) row.get("crop_width")).intValue();
    int height = ((Number) row.get("crop_height")).intValue();
    if (left < 0 || top < 0 || width <= 0 || height <= 0) {
      throw GenealogyException.badRequest("the crop must have a positive size inside the image");
    }
    MediaImages.Dimensions size = images.dimensions(((Number) page).longValue());
    if ((long) left + width > size.width() || (long) top + height > size.height()) {
      throw GenealogyException.badRequest(
          "the crop %d,%d %dx%d lies outside the %dx%d image"
              .formatted(left, top, width, height, size.width(), size.height()));
    }
  }

  private Map<String, Object> coerce(GenEntity entity, Map<String, Object> body) {
    Map<String, Field> byJson = new LinkedHashMap<>();
    for (Field f : entity.fields()) byJson.put(f.json(), f);
    Map<String, Object> out = new LinkedHashMap<>();
    for (var e : body.entrySet()) {
      Field f = byJson.get(e.getKey());
      if (f == null) {
        throw GenealogyException.badRequest(
            "%s has no field '%s'; it has %s".formatted(entity.key(), e.getKey(), byJson.keySet()));
      }
      out.put(f.column(), value(f, e.getValue()));
    }
    return out;
  }

  private static Object value(Field f, Object v) {
    if (v == null) return null;
    String bad = "%s must be %s".formatted(f.json(), f.type().name().toLowerCase());
    return switch (f.type()) {
      case TEXT -> {
        if (!(v instanceof String s)) throw GenealogyException.badRequest(bad);
        yield s.isBlank() ? null : s.trim();
      }
      case BOOL -> {
        if (!(v instanceof Boolean b)) throw GenealogyException.badRequest(bad);
        yield b;
      }
      case INT -> {
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
          throw GenealogyException.badRequest(bad);
        }
        yield n.intValue();
      }
      case LONG -> {
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
          throw GenealogyException.badRequest(bad);
        }
        yield n.longValue();
      }
      case DECIMAL -> {
        if (!(v instanceof Number n)) throw GenealogyException.badRequest(bad);
        yield new BigDecimal(n.toString());
      }
    };
  }

  private static void derive(GenEntity entity, Map<String, Object> row) {
    if (entity == GenEntity.EVENT && row.containsKey("date_text")) {
      GedcomDate d = GedcomDate.parse((String) row.get("date_text"));
      row.put("date_text", d == null ? null : d.text());
      row.put("date_from", d == null ? null : d.from());
      row.put("date_to", d == null ? null : d.to());
    }
    if (entity == GenEntity.MEDIA && row.get("date_text") != null) {
      row.put("date_text", GedcomDate.parse((String) row.get("date_text")).text());
    }
  }

  private Map<String, Object> lock(GenEntity entity, long id) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT to_jsonb(t.*)::text FROM %s t WHERE id = ? FOR UPDATE"
                .formatted(entity.table()),
            String.class,
            id);
    if (rows.isEmpty()) {
      throw GenealogyException.notFound("%s %d not found".formatted(entity.key(), id));
    }
    return Rows.json(rows.get(0));
  }

  private Map<String, Object> write(GenEntity entity, long id, Map<String, Object> values) {
    List<String> sets = new ArrayList<>();
    for (String column : values.keySet()) sets.add(column + " = ?");
    if (entity.writable()) sets.add("updated_at = now()");
    List<Object> args = new ArrayList<>(values.values());
    args.add(id);
    return Rows.json(
        jdbc.queryForObject(
            "UPDATE %s AS t SET %s WHERE id = ? RETURNING to_jsonb(t.*)::text"
                .formatted(entity.table(), String.join(", ", sets)),
            String.class,
            args.toArray()));
  }

  /** Deletes a row and every row that belongs to it, deepest first, one audit row each. */
  private int deleteWithOwned(GenEntity entity, long id, String op, String reason) {
    int removed = 0;
    for (Ref owned : OWNED.getOrDefault(entity, List.of())) {
      for (Long child :
          jdbc.queryForList(
              "SELECT id FROM %s WHERE %s = ? ORDER BY id"
                  .formatted(owned.entity().table(), owned.column()),
              Long.class,
              id)) {
        removed += deleteWithOwned(owned.entity(), child, op, reason);
      }
    }
    Map<String, Object> before = lock(entity, id);
    jdbc.update("DELETE FROM %s WHERE id = ?".formatted(entity.table()), id);
    audit(entity, id, op, before, null, reason);
    return removed + 1;
  }

  private List<String> blockers(GenEntity entity, long id) {
    List<String> out = new ArrayList<>();
    for (Ref ref : BLOCKING.getOrDefault(entity, List.of())) {
      Integer n =
          jdbc.queryForObject(
              "SELECT count(*) FROM %s WHERE %s = ?".formatted(ref.entity().table(), ref.column()),
              Integer.class,
              id);
      if (n > 0) out.add("%d %s (%s)".formatted(n, ref.entity().key(), ref.column()));
    }
    return out;
  }

  /** Clears a one-per-person flag (primary name, portrait) on the person's other row. */
  private void demoteOther(
      GenEntity entity, String flag, String owner, Object ownerId, Long self, String reason) {
    for (Long other :
        jdbc.queryForList(
            "SELECT id FROM %s WHERE %s = ? AND %s AND id IS DISTINCT FROM ?::bigint"
                .formatted(entity.table(), owner, flag),
            Long.class,
            ownerId,
            self)) {
      Map<String, Object> before = lock(entity, other);
      Map<String, Object> after = write(entity, other, Map.of(flag, false));
      audit(entity, other, "update", before, after, reason);
    }
  }

  void audit(
      GenEntity entity,
      long id,
      String op,
      Map<String, Object> before,
      Map<String, Object> after,
      String reason) {
    jdbc.update(
        "INSERT INTO gen_change (entity, entity_id, op, before, after, reason, changed_by)"
            + " VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)",
        entity.key(),
        id,
        op,
        Rows.toJson(before),
        Rows.toJson(after),
        reason.trim(),
        who());
  }

  /** Rebuilds the read model once the transaction has committed. */
  void changed() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              tree.invalidate();
            }
          });
    } else {
      tree.invalidate();
    }
  }

  static long id(Map<String, Object> row) {
    return ((Number) row.get("id")).longValue();
  }

  static String who() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null ? auth.getName() : "unknown";
  }

  private static Set<String> jsonNames(GenEntity entity) {
    Set<String> out = new HashSet<>();
    for (Field f : entity.fields()) out.add(f.json());
    return out;
  }

  public static void requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw GenealogyException.badRequest("reason is required");
    }
  }

  private static void requireWritable(GenEntity entity) {
    if (!entity.writable()) {
      throw GenealogyException.badRequest(entity.key() + " cannot be written directly");
    }
  }

  private void livePerson(Object id, String field, boolean required) {
    if (id == null) {
      if (required) throw GenealogyException.badRequest(field + " is required");
      return;
    }
    List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT merged_into FROM gen_person WHERE id = ?", id);
    if (rows.isEmpty()) throw GenealogyException.notFound("person %s not found".formatted(id));
    Object into = rows.get(0).get("merged_into");
    if (into != null) {
      throw GenealogyException.conflict(
          "person %s was merged into %s; use %s".formatted(id, into, into));
    }
  }

  private void requireRef(GenEntity entity, Object id, String field) {
    if (id == null) throw GenealogyException.badRequest(field + " is required");
    exists(entity, id, field);
  }

  private void exists(GenEntity entity, Object id, String field) {
    existsIn(entity.table(), id, field);
  }

  private void existsIn(String table, Object id, String field) {
    if (id == null) return;
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM %s WHERE id = ?".formatted(table), Integer.class, id);
    if (n == 0) throw GenealogyException.notFound("%s %s not found".formatted(field, id));
  }

  private static void oneOf(String field, Object value, Set<String> allowed, boolean required) {
    if (value == null) {
      if (required) throw GenealogyException.badRequest(field + " is required");
      return;
    }
    if (!allowed.contains(value.toString())) {
      throw GenealogyException.badRequest(
          "%s must be one of %s".formatted(field, allowed.stream().sorted().toList()));
    }
  }

  private static void date(Object text) {
    try {
      GedcomDate.parse((String) text);
    } catch (GedcomDate.InvalidDateException e) {
      throw GenealogyException.badRequest(e.getMessage());
    }
  }

  private static void quality(Object q) {
    if (q != null && (((Number) q).intValue() < 0 || ((Number) q).intValue() > 3)) {
      throw GenealogyException.badRequest("quality is a GEDCOM QUAY from 0 to 3");
    }
  }

  private static void range(String field, Object v, double min, double max) {
    if (v != null && (((Number) v).doubleValue() < min || ((Number) v).doubleValue() > max)) {
      throw GenealogyException.badRequest(
          "%s must be between %s and %s".formatted(field, min, max));
    }
  }

  private static boolean blank(Object v) {
    return v == null || v.toString().isBlank();
  }
}
```

- [ ] **Step 5: Run it to see it pass**

Run: `cd backend && ./gradlew test --tests '*GenealogyServiceTest'`

Expected: PASS (17 tests)

- [ ] **Step 6: Format**

Run: `cd backend && ./gradlew spotlessApply`

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A backend/src/main/java/place/icomb/archiver/genealogy/GenEntity.java backend/src/main/java/place/icomb/archiver/genealogy/GenealogyException.java backend/src/main/java/place/icomb/archiver/genealogy/MediaImages.java backend/src/main/java/place/icomb/archiver/genealogy/GenealogyService.java backend/src/test/java/place/icomb/archiver/genealogy/GenealogyServiceTest.java
git commit -m "feat: GenealogyService writes the tree with validation and one audit row per row"
```

---

### Task 8: Merge and undo

**Files:**
- Create: `backend/src/main/java/place/icomb/archiver/genealogy/GenealogyHistoryService.java`
- Test: `backend/src/test/java/place/icomb/archiver/genealogy/GenealogyHistoryTest.java`

**Interfaces:**
- Consumes: `GenealogyService.audit(...)`, `changed()`, `requireReason(String)`, `create`, `update`, `delete`, `createPerson` (Task 7); `GenEntity` (Task 7).
- Produces (used by Tasks 9 and 11):
  - `Map<String,Object> mergePersons(int keepId, int goneId, String reason)` returns `{keep, gone, changed}`, where `changed` is the number of audit rows written.
  - `Map<String,Object> undo(long changeId, String reason)` returns `{undone: [changeIds]}`. It restores every row of the write that `changeId` belongs to, and refuses with CONFLICT if any of them has changed since.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/place/icomb/archiver/genealogy/GenealogyHistoryTest.java`:

```java
package place.icomb.archiver.genealogy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.genealogy.GenealogyException.Kind;
import place.icomb.archiver.service.FamilyTreeService;
import place.icomb.archiver.service.StorageService;

/** Merging two records of one person, and undoing writes. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class GenealogyHistoryTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private GenealogyService genealogy;
  @Autowired private GenealogyHistoryService history;
  @Autowired private FamilyTreeService tree;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StorageService storage;

  @BeforeEach
  void signIn() {
    GenealogyFixtures.signInAsAdmin();
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  private static Map<String, Object> body(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  private static Kind kindOf(Runnable r) {
    try {
      r.run();
    } catch (GenealogyException e) {
      return e.kind();
    }
    throw new AssertionError("expected a GenealogyException");
  }

  private int person(String given) {
    return ((Number)
            genealogy.createPerson(body("given", given, "surname", "Test"), "setup").get("id"))
        .intValue();
  }

  private long id(Map<String, Object> row) {
    return ((Number) row.get("id")).longValue();
  }

  private long lastChange() {
    return jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM gen_change", Long.class);
  }

  /** Every kind of link a person can have, all on {@code gone}, plus a few keep already shares. */
  private record World(
      int keep,
      int gone,
      int partner,
      int child,
      int parent,
      int alias,
      long page,
      long sharedPage,
      long media,
      long user) {}

  private World world() throws Exception {
    int keep = person("Keep");
    int gone = person("Gone");
    int partner = person("Partner");
    int child = person("Child");
    int parent = person("Parent");
    int alias = person("Alias");
    genealogy.create(
        GenEntity.EVENT, body("personId", gone, "type", "RESI", "dateText", "1930"), "setup");
    genealogy.create(GenEntity.COUPLE, body("person1Id", gone, "person2Id", partner), "setup");
    genealogy.create(GenEntity.PARENT_LINK, body("parentId", gone, "childId", child), "setup");
    genealogy.create(GenEntity.PARENT_LINK, body("parentId", parent, "childId", gone), "setup");
    genealogy.create(GenEntity.PARENT_LINK, body("parentId", parent, "childId", keep), "setup");
    long source = jdbc.queryForObject("SELECT id FROM gen_source ORDER BY id LIMIT 1", Long.class);
    genealogy.create(GenEntity.CITATION, body("sourceId", source, "personId", gone), "setup");
    long record = GenealogyFixtures.record(jdbc);
    byte[] png = GenealogyFixtures.encode(GenealogyFixtures.pattern(8, 8), "png");
    long page = GenealogyFixtures.page(jdbc, storage, record, 1, png, "image/png");
    long sharedPage = GenealogyFixtures.page(jdbc, storage, record, 2, png, "image/png");
    genealogy.create(
        GenEntity.RECORD_LINK,
        body("personId", gone, "recordId", record, "role", "mentioned"),
        "setup");
    long media = id(genealogy.create(GenEntity.MEDIA, body("pageId", page), "setup"));
    genealogy.create(
        GenEntity.MEDIA_PERSON,
        body("mediaId", media, "personId", gone, "isPortrait", true),
        "setup");
    jdbc.update(
        "INSERT INTO page_person_match (page_id, person_id, person_name, score) VALUES (?, ?, 'Gone', 0.9)",
        page,
        gone);
    jdbc.update(
        "INSERT INTO page_person_match (page_id, person_id, person_name, score) VALUES (?, ?, 'Gone', 0.5),"
            + " (?, ?, 'Keep', 0.7)",
        sharedPage,
        gone,
        sharedPage,
        keep);
    long user =
        jdbc.queryForObject(
            "INSERT INTO app_user (display_name, role, family_tree_person_id) VALUES ('Merge', 'user', ?)"
                + " RETURNING id",
            Long.class,
            gone);
    jdbc.update("UPDATE gen_person SET merged_into = ? WHERE id = ?", gone, alias);
    return new World(keep, gone, partner, child, parent, alias, page, sharedPage, media, user);
  }

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  @Test
  void aMergeRepointsEveryKindOfLink() throws Exception {
    World w = world();
    history.mergePersons(w.keep(), w.gone(), "same man, two entries");

    int g = w.gone();
    assertThat(count("SELECT merged_into FROM gen_person WHERE id = ?", g)).isEqualTo(w.keep());
    assertThat(count("SELECT count(*) FROM gen_name WHERE person_id = ?", g)).isZero();
    assertThat(count("SELECT count(*) FROM gen_name WHERE person_id = ? AND is_primary", w.keep()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM gen_name WHERE person_id = ?", w.keep())).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM gen_event WHERE person_id = ?", g)).isZero();
    assertThat(count("SELECT count(*) FROM gen_couple WHERE ? IN (person1_id, person2_id)", g))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM gen_couple WHERE person1_id = ? AND person2_id = ?",
                w.keep(),
                w.partner()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM gen_parent_child WHERE ? IN (parent_id, child_id)", g))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM gen_parent_child WHERE parent_id = ? AND child_id = ?",
                w.keep(),
                w.child()))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM gen_parent_child WHERE parent_id = ? AND child_id = ?",
                w.parent(),
                w.keep()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM gen_citation WHERE person_id = ?", g)).isZero();
    assertThat(count("SELECT count(*) FROM gen_person_record WHERE person_id = ?", g)).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM gen_media_person WHERE person_id = ? AND is_portrait",
                w.keep()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM page_person_match WHERE person_id = ?", g)).isZero();
    assertThat(count("SELECT count(*) FROM page_person_match WHERE person_id = ?", w.keep()))
        .isEqualTo(2);
    assertThat(count("SELECT family_tree_person_id FROM app_user WHERE id = ?", w.user()))
        .isEqualTo(w.keep());
    assertThat(count("SELECT merged_into FROM gen_person WHERE id = ?", w.alias()))
        .isEqualTo(w.keep());
    assertThat(tree.getPerson(g).id).isEqualTo(w.keep());
  }

  @Test
  void aMergeWritesOneAuditRowPerRowItChanged() throws Exception {
    World w = world();
    long before = lastChange();
    Map<String, Object> out = history.mergePersons(w.keep(), w.gone(), "duplicate");
    assertThat(count("SELECT count(*) FROM gen_change WHERE id > ?", before))
        .isEqualTo(((Number) out.get("changed")).intValue());
    assertThat(
            count(
                "SELECT count(*) FROM gen_change WHERE id > ? AND entity = 'page_person_match'",
                before))
        .isEqualTo(2);
    assertThat(
            count("SELECT count(*) FROM gen_change WHERE id > ? AND entity = 'app_user'", before))
        .isEqualTo(1);
  }

  @Test
  void undoingAMergePutsEverythingBack() throws Exception {
    World w = world();
    history.mergePersons(w.keep(), w.gone(), "duplicate");
    history.undo(lastChange(), "they were two people after all");

    int g = w.gone();
    assertThat(
            jdbc.queryForObject(
                "SELECT merged_into FROM gen_person WHERE id = ?", Integer.class, g))
        .isNull();
    assertThat(count("SELECT count(*) FROM gen_name WHERE person_id = ? AND is_primary", g))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM gen_parent_child WHERE parent_id = ? AND child_id = ?",
                w.parent(),
                g))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM page_person_match WHERE person_id = ?", g)).isEqualTo(2);
    assertThat(count("SELECT family_tree_person_id FROM app_user WHERE id = ?", w.user()))
        .isEqualTo(g);
    assertThat(
            count("SELECT count(*) FROM gen_media_person WHERE person_id = ? AND is_portrait", g))
        .isEqualTo(1);
    assertThat(count("SELECT merged_into FROM gen_person WHERE id = ?", w.alias())).isEqualTo(g);
    assertThat(tree.getPerson(g).id).isEqualTo(g);
  }

  @Test
  void undoIsRefusedOnceTheRowHasChangedAgain() {
    int p = person("Hedwig");
    genealogy.update(GenEntity.PERSON, p, body("notes", "first"), "one");
    long first = lastChange();
    genealogy.update(GenEntity.PERSON, p, body("notes", "second"), "two");
    long second = lastChange();

    assertThat(kindOf(() -> history.undo(first, "wrong order"))).isEqualTo(Kind.CONFLICT);
    assertThat(jdbc.queryForObject("SELECT notes FROM gen_person WHERE id = ?", String.class, p))
        .isEqualTo("second");

    history.undo(second, "back one");
    assertThat(jdbc.queryForObject("SELECT notes FROM gen_person WHERE id = ?", String.class, p))
        .isEqualTo("first");
    history.undo(first, "back two");
    assertThat(jdbc.queryForObject("SELECT notes FROM gen_person WHERE id = ?", String.class, p))
        .isNull();
    assertThat(kindOf(() -> history.undo(first, "again"))).isEqualTo(Kind.CONFLICT);
  }

  @Test
  void undoingACreateRemovesItAndUndoingADeleteRestoresIt() {
    int p = person("Irma");
    long created = lastChange();
    genealogy.create(GenEntity.EVENT, body("personId", p, "type", "BIRT", "dateText", "1901"), "x");
    assertThat(kindOf(() -> history.undo(created, "created by mistake"))).isEqualTo(Kind.CONFLICT);

    genealogy.delete(GenEntity.PERSON, p, "duplicate");
    history.undo(lastChange(), "not a duplicate");
    assertThat(count("SELECT count(*) FROM gen_person WHERE id = ?", p)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM gen_name WHERE person_id = ? AND is_primary", p))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM gen_event WHERE person_id = ?", p)).isEqualTo(1);

    int q = person("Jana");
    history.undo(lastChange(), "created by mistake");
    assertThat(count("SELECT count(*) FROM gen_person WHERE id = ?", q)).isZero();
  }

  @Test
  void undoNeedsAReasonAndAChangeThatExists() {
    assertThat(kindOf(() -> history.undo(lastChange(), " "))).isEqualTo(Kind.BAD_REQUEST);
    assertThat(kindOf(() -> history.undo(99999999L, "x"))).isEqualTo(Kind.NOT_FOUND);
  }

  @Test
  void aMergeRefusesThingsThatCannotBeOnePerson() {
    int a = person("Karl");
    int b = person("Karla");
    genealogy.create(GenEntity.COUPLE, body("person1Id", a, "person2Id", b), "x");
    assertThat(kindOf(() -> history.mergePersons(a, b, "x"))).isEqualTo(Kind.CONFLICT);
    assertThat(kindOf(() -> history.mergePersons(a, a, "x"))).isEqualTo(Kind.BAD_REQUEST);
    assertThat(kindOf(() -> history.mergePersons(a, 122, "x"))).isEqualTo(Kind.CONFLICT);
    assertThat(kindOf(() -> history.mergePersons(a, 999999, "x"))).isEqualTo(Kind.NOT_FOUND);

    int c = person("Child");
    int p1 = person("P1");
    int p2 = person("P2");
    int p3 = person("P3");
    int twin = person("Twin");
    genealogy.create(GenEntity.PARENT_LINK, body("parentId", p1, "childId", c), "x");
    genealogy.create(GenEntity.PARENT_LINK, body("parentId", p2, "childId", c), "x");
    genealogy.create(GenEntity.PARENT_LINK, body("parentId", p3, "childId", twin), "x");
    assertThat(kindOf(() -> history.mergePersons(c, twin, "x"))).isEqualTo(Kind.CONFLICT);
    assertThat(count("SELECT count(*) FROM gen_parent_child WHERE child_id = ?", twin))
        .isEqualTo(1);
  }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd backend && ./gradlew test --tests '*GenealogyHistoryTest'`

Expected: FAIL: compilation error `cannot find symbol: class GenealogyHistoryService`

- [ ] **Step 3: Write merge and undo**

Create `backend/src/main/java/place/icomb/archiver/genealogy/GenealogyHistoryService.java`:

```java
package place.icomb.archiver.genealogy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merging two records of one person, and undoing a change.
 *
 * <p>A write is every {@code gen_change} row its transaction appended: they share {@code
 * changed_at} (the transaction's start) and {@code changed_by}. Undo restores the whole write, last
 * row first, and only when every row it touched still looks exactly as the write left it.
 */
@Service
public class GenealogyHistoryService {

  private final JdbcTemplate jdbc;
  private final GenealogyService genealogy;

  public GenealogyHistoryService(JdbcTemplate jdbc, GenealogyService genealogy) {
    this.jdbc = jdbc;
    this.genealogy = genealogy;
  }

  /**
   * Re-points every link of {@code goneId} to {@code keepId} and marks {@code goneId} merged into
   * it. Links {@code keepId} already has (the same parent, the same page match, the same image) are
   * not duplicated: the gone person's copy is deleted, its citations moved first.
   */
  @Transactional
  public Map<String, Object> mergePersons(int keepId, int goneId, String reason) {
    GenealogyService.requireReason(reason);
    if (keepId == goneId) {
      throw GenealogyException.badRequest("a person cannot be merged into themselves");
    }
    for (int id : List.of(keepId, goneId)) {
      List<Map<String, Object>> rows =
          jdbc.queryForList("SELECT merged_into FROM gen_person WHERE id = ? FOR UPDATE", id);
      if (rows.isEmpty()) throw GenealogyException.notFound("person %d not found".formatted(id));
      if (rows.get(0).get("merged_into") != null) {
        throw GenealogyException.conflict(
            "person %d was already merged into %s".formatted(id, rows.get(0).get("merged_into")));
      }
    }
    Integer between =
        jdbc.queryForObject(
            "SELECT (SELECT count(*) FROM gen_couple WHERE (person1_id, person2_id) IN ((?, ?), (?, ?)))"
                + " + (SELECT count(*) FROM gen_parent_child"
                + "    WHERE (parent_id, child_id) IN ((?, ?), (?, ?)))",
            Integer.class,
            keepId,
            goneId,
            goneId,
            keepId,
            keepId,
            goneId,
            goneId,
            keepId);
    if (between > 0) {
      throw GenealogyException.conflict(
          "persons %d and %d are linked to each other; remove that link first"
              .formatted(keepId, goneId));
    }

    int changed = 0;
    for (long name : ids("SELECT id FROM gen_name WHERE person_id = ?", goneId)) {
      changed +=
          repoint(GenEntity.NAME, name, Map.of("person_id", keepId, "is_primary", false), reason);
    }
    changed += repointAll(GenEntity.EVENT, "person_id", keepId, goneId, reason);
    changed += repointAll(GenEntity.COUPLE, "person1_id", keepId, goneId, reason);
    changed += repointAll(GenEntity.COUPLE, "person2_id", keepId, goneId, reason);
    changed += moveParentLinks("parent_id", "child_id", keepId, goneId, reason);
    changed += moveParentLinks("child_id", "parent_id", keepId, goneId, reason);
    changed += repointAll(GenEntity.CITATION, "person_id", keepId, goneId, reason);
    changed += repointAll(GenEntity.RECORD_LINK, "person_id", keepId, goneId, reason);

    boolean keepHasPortrait =
        jdbc.queryForObject(
                "SELECT count(*) FROM gen_media_person WHERE person_id = ? AND is_portrait",
                Integer.class,
                keepId)
            > 0;
    for (Map<String, Object> mp :
        jdbc.queryForList(
            "SELECT id, media_id, is_portrait FROM gen_media_person WHERE person_id = ?", goneId)) {
      long id = ((Number) mp.get("id")).longValue();
      boolean duplicate =
          jdbc.queryForObject(
                  "SELECT count(*) FROM gen_media_person WHERE media_id = ? AND person_id = ?",
                  Integer.class,
                  mp.get("media_id"),
                  keepId)
              > 0;
      if (duplicate) {
        changed += remove(GenEntity.MEDIA_PERSON, id, reason);
      } else {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("person_id", keepId);
        if (keepHasPortrait) set.put("is_portrait", false);
        changed += repoint(GenEntity.MEDIA_PERSON, id, set, reason);
      }
    }

    for (Map<String, Object> m :
        jdbc.queryForList(
            "SELECT id, page_id FROM page_person_match WHERE person_id = ?", goneId)) {
      long id = ((Number) m.get("id")).longValue();
      boolean duplicate =
          jdbc.queryForObject(
                  "SELECT count(*) FROM page_person_match WHERE page_id = ? AND person_id = ?",
                  Integer.class,
                  m.get("page_id"),
                  keepId)
              > 0;
      changed +=
          duplicate
              ? remove(GenEntity.PAGE_PERSON_MATCH, id, reason)
              : partial(GenEntity.PAGE_PERSON_MATCH, id, "person_id", goneId, keepId, reason);
    }
    for (long user : ids("SELECT id FROM app_user WHERE family_tree_person_id = ?", goneId)) {
      changed += partial(GenEntity.APP_USER, user, "family_tree_person_id", goneId, keepId, reason);
    }
    changed += repointAll(GenEntity.PERSON, "merged_into", keepId, goneId, reason);

    Integer birthParents =
        jdbc.queryForObject(
            "SELECT count(*) FROM gen_parent_child WHERE child_id = ? AND type = 'birth'",
            Integer.class,
            keepId);
    if (birthParents > 2) {
      throw GenealogyException.conflict(
          "merging would give person %d %d birth parents".formatted(keepId, birthParents));
    }

    changed += repoint(GenEntity.PERSON, goneId, Map.of("merged_into", keepId), reason);
    genealogy.changed();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("keep", keepId);
    out.put("gone", goneId);
    out.put("changed", changed);
    return out;
  }

  /** Restores what the write containing {@code changeId} replaced, if nothing has moved since. */
  @Transactional
  public Map<String, Object> undo(long changeId, String reason) {
    GenealogyService.requireReason(reason);
    List<Map<String, Object>> write =
        jdbc.queryForList(
            "SELECT id, entity, entity_id, before::text AS before, after::text AS after"
                + " FROM gen_change WHERE (changed_at, changed_by) ="
                + " (SELECT changed_at, changed_by FROM gen_change WHERE id = ?)"
                + " ORDER BY id DESC",
            changeId);
    if (write.isEmpty()) {
      throw GenealogyException.notFound("change %d not found".formatted(changeId));
    }

    for (Map<String, Object> row : write) {
      GenEntity entity = GenEntity.ofKey((String) row.get("entity"));
      long id = ((Number) row.get("entity_id")).longValue();
      String after = (String) row.get("after");
      boolean present = exists(entity, id);
      boolean unchanged =
          after == null
              ? !present
              : present
                  && Boolean.TRUE.equals(
                      jdbc.queryForObject(
                          "SELECT (to_jsonb(t.*) - 'updated_at') @> (?::jsonb - 'updated_at')"
                              + " FROM %s t WHERE id = ?".formatted(entity.table()),
                          Boolean.class,
                          after,
                          id));
      if (!unchanged) {
        throw GenealogyException.conflict(
            "%s %d has changed since change %d; undo the later change first"
                .formatted(entity.key(), id, ((Number) row.get("id")).longValue()));
      }
    }

    List<Long> undone = new ArrayList<>();
    try {
      for (Map<String, Object> row : write) {
        GenEntity entity = GenEntity.ofKey((String) row.get("entity"));
        long id = ((Number) row.get("entity_id")).longValue();
        Map<String, Object> before = Rows.json((String) row.get("before"));
        Map<String, Object> current = current(entity, id);
        if (before == null) {
          jdbc.update("DELETE FROM %s WHERE id = ?".formatted(entity.table()), id);
          genealogy.audit(entity, id, "undo", current, null, reason);
        } else if (current == null) {
          jdbc.update(
              "INSERT INTO %s SELECT * FROM jsonb_populate_record(NULL::%s, ?::jsonb)"
                  .formatted(entity.table(), entity.table()),
              Rows.toJson(before));
          genealogy.audit(entity, id, "undo", null, current(entity, id), reason);
        } else {
          List<String> columns = new ArrayList<>(before.keySet());
          columns.removeAll(List.of("id", "created_at", "updated_at"));
          String list = String.join(", ", columns);
          jdbc.update(
              "UPDATE %s t SET (%s) = (SELECT %s FROM jsonb_populate_record(NULL::%s, ?::jsonb))%s"
                      .formatted(
                          entity.table(),
                          list,
                          list,
                          entity.table(),
                          entity.writable() ? ", updated_at = now()" : "")
                  + " WHERE t.id = ?",
              Rows.toJson(before),
              id);
          Map<String, Object> now = current(entity, id);
          if (!entity.writable()) {
            now.keySet().retainAll(before.keySet());
            current.keySet().retainAll(before.keySet());
          }
          genealogy.audit(entity, id, "undo", current, now, reason);
        }
        undone.add(((Number) row.get("id")).longValue());
      }
    } catch (DataIntegrityViolationException e) {
      throw GenealogyException.conflict(
          "change %d cannot be undone: the data it restored would conflict with what is there now"
              .formatted(changeId));
    }
    genealogy.changed();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("undone", undone);
    return out;
  }

  private int repointAll(GenEntity entity, String column, int keepId, int goneId, String reason) {
    int n = 0;
    for (long id :
        ids("SELECT id FROM %s WHERE %s = ?".formatted(entity.table(), column), goneId)) {
      n += repoint(entity, id, Map.of(column, keepId), reason);
    }
    return n;
  }

  /** Moves the gone person's side of each parent link, dropping links keep already has. */
  private int moveParentLinks(
      String goneSide, String otherSide, int keepId, int goneId, String reason) {
    int n = 0;
    for (Map<String, Object> link :
        jdbc.queryForList(
            "SELECT id, %s AS other FROM gen_parent_child WHERE %s = ?"
                .formatted(otherSide, goneSide),
            goneId)) {
      long id = ((Number) link.get("id")).longValue();
      List<Long> existing =
          jdbc.queryForList(
              "SELECT id FROM gen_parent_child WHERE %s = ? AND %s = ?"
                  .formatted(goneSide, otherSide),
              Long.class,
              keepId,
              link.get("other"));
      if (existing.isEmpty()) {
        n += repoint(GenEntity.PARENT_LINK, id, Map.of(goneSide, keepId), reason);
      } else {
        for (long citation : ids("SELECT id FROM gen_citation WHERE parent_child_id = ?", id)) {
          n +=
              repoint(
                  GenEntity.CITATION, citation, Map.of("parent_child_id", existing.get(0)), reason);
        }
        n += remove(GenEntity.PARENT_LINK, id, reason);
      }
    }
    return n;
  }

  private int repoint(GenEntity entity, long id, Map<String, Object> set, String reason) {
    Map<String, Object> before = current(entity, id);
    List<String> sets = new ArrayList<>();
    for (String column : set.keySet()) sets.add(column + " = ?");
    sets.add("updated_at = now()");
    List<Object> args = new ArrayList<>(set.values());
    args.add(id);
    jdbc.update(
        "UPDATE %s SET %s WHERE id = ?".formatted(entity.table(), String.join(", ", sets)),
        args.toArray());
    genealogy.audit(entity, id, "merge", before, current(entity, id), reason);
    return 1;
  }

  private int partial(
      GenEntity entity, long id, String column, Object from, Object to, String reason) {
    jdbc.update("UPDATE %s SET %s = ? WHERE id = ?".formatted(entity.table(), column), to, id);
    Map<String, Object> before = new LinkedHashMap<>();
    before.put(column, from);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put(column, to);
    genealogy.audit(entity, id, "merge", before, after, reason);
    return 1;
  }

  private int remove(GenEntity entity, long id, String reason) {
    Map<String, Object> before = current(entity, id);
    jdbc.update("DELETE FROM %s WHERE id = ?".formatted(entity.table()), id);
    genealogy.audit(entity, id, "delete", before, null, reason);
    return 1;
  }

  private Map<String, Object> current(GenEntity entity, long id) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT to_jsonb(t.*)::text FROM %s t WHERE id = ?".formatted(entity.table()),
            String.class,
            id);
    return rows.isEmpty() ? null : Rows.json(rows.get(0));
  }

  private boolean exists(GenEntity entity, long id) {
    return jdbc.queryForObject(
            "SELECT count(*) FROM %s WHERE id = ?".formatted(entity.table()), Integer.class, id)
        > 0;
  }

  private List<Long> ids(String sql, Object arg) {
    return jdbc.queryForList(sql, Long.class, arg);
  }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `cd backend && ./gradlew test --tests '*GenealogyHistoryTest' --tests '*GenealogyServiceTest'`

Expected: PASS (7 + 17 tests)

- [ ] **Step 5: Format**

Run: `cd backend && ./gradlew spotlessApply`

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A backend/src/main/java/place/icomb/archiver/genealogy/GenealogyHistoryService.java backend/src/test/java/place/icomb/archiver/genealogy/GenealogyHistoryTest.java
git commit -m "feat: merge people and undo genealogy writes"
```

---

### Task 9: Admin REST API

**Files:**
- Create: `backend/src/main/java/place/icomb/archiver/controller/AdminGenealogyController.java`
- Test: `backend/src/test/java/place/icomb/archiver/controller/AdminGenealogyControllerTest.java`

**Interfaces:**
- Consumes: `GenealogyService`, `GenealogyHistoryService`, `GenealogyException` (Tasks 7–8), `GenealogyFixtures` (Task 2). `SecurityConfig` already requires ROLE_ADMIN for GET, POST, PUT and DELETE under `/api/admin/**`, so it is not modified.
- Produces (used by Task 14). All endpoints are under `/api/admin/genealogy`, and every write body carries `"reason"`:
  - `POST /{kind}` (201). `kind` is one of `persons`, `names`, `events`, `couples`, `parent-links`, `places`, `place-names`, `sources`, `citations`, `record-links`, `media`, `media-people`. `persons` takes person and name fields; `media` also takes `personId` and `isPortrait`.
  - `POST /persons/{id}/names` (201).
  - `PUT /{kind}/{id}` and `DELETE /{kind}/{id}`.
  - `POST /persons/{keep}/merge/{gone}`.
  - `GET /changes?entity=&entityId=&personId=&limit=` and `POST /changes/{id}/undo`.
  - `GET /review`.
  - `GET /places?q=&limit=` and `GET /sources?q=&limit=` (limit at most 1000).
  - Refusals are `{"error": message}` with 400, 404 or 409.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/place/icomb/archiver/controller/AdminGenealogyControllerTest.java`:

```java
package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static place.icomb.archiver.TestAuth.PROCESSOR_AUTH_HEADER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.genealogy.GenealogyFixtures;

/** The genealogy admin API over HTTP: who may call it, and the statuses it answers with. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminGenealogyControllerTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
  @Autowired private place.icomb.archiver.service.StorageService storage;

  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  private static final String ADMIN = "genealogy-admin@example.com";
  private static final String USER = "genealogy-reader@example.com";

  @BeforeEach
  void users() {
    for (String[] u : new String[][] {{ADMIN, "admin", "GenAdmin"}, {USER, "user", "GenReader"}}) {
      jdbc.sql("DELETE FROM app_user_email WHERE email = :e").param("e", u[0]).update();
      jdbc.sql("DELETE FROM app_user WHERE display_name = :n").param("n", u[2]).update();
      Long id =
          jdbc.sql("INSERT INTO app_user (display_name, role) VALUES (:n, :r) RETURNING id")
              .param("n", u[2])
              .param("r", u[1])
              .query(Long.class)
              .single();
      jdbc.sql("INSERT INTO app_user_email (user_id, email) VALUES (:id, :e)")
          .param("id", id)
          .param("e", u[0])
          .update();
    }
  }

  private String url(String path) {
    return "http://localhost:" + port + "/api" + path;
  }

  private HttpResponse<String> send(String method, String path, String body, String... headers)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(url(path)))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> admin(String method, String path, Object body) throws Exception {
    return send(
        method, path, body == null ? null : json.writeValueAsString(body), "X-Auth-Email", ADMIN);
  }

  private JsonNode ok(HttpResponse<String> r, int status) throws Exception {
    assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
    return json.readTree(r.body());
  }

  @Test
  void anAdministratorCreatesEditsAndDeletesAPerson() throws Exception {
    JsonNode created =
        ok(
            admin(
                "POST",
                "/admin/genealogy/persons",
                Map.of("given", "Anna", "surname", "Novák", "sex", "F", "reason", "letter")),
            201);
    long id = created.get("id").asLong();
    assertThat(id).isGreaterThan(348);

    ok(
        admin(
            "POST",
            "/admin/genealogy/persons/" + id + "/names",
            Map.of(
                "type", "married", "given", "Anna", "surname", "Svobodová", "reason", "marriage")),
        201);
    JsonNode event =
        ok(
            admin(
                "POST",
                "/admin/genealogy/events",
                Map.of(
                    "personId",
                    id,
                    "type",
                    "BIRT",
                    "dateText",
                    "12 MAR 1890",
                    "reason",
                    "register")),
            201);
    assertThat(event.get("dateFrom").asText()).isEqualTo("1890-03-12");
    ok(
        admin(
            "PUT",
            "/admin/genealogy/events/" + event.get("id").asLong(),
            Map.of("dateText", "ABT 1890", "reason", "the register is unsure")),
        200);

    JsonNode person = ok(send("GET", "/family-tree/person/" + id, null, "X-Auth-Email", USER), 200);
    assertThat(person.get("names")).hasSize(2);
    assertThat(person.get("events").get(0).get("date").asText()).isEqualTo("ABT 1890");

    JsonNode deleted =
        ok(admin("DELETE", "/admin/genealogy/persons/" + id, Map.of("reason", "test over")), 200);
    assertThat(deleted.get("deleted").asInt()).isEqualTo(4);
  }

  @Test
  void refusalsAreFourHundredsWithAnError() throws Exception {
    assertThat(admin("POST", "/admin/genealogy/persons", Map.of("given", "x")).statusCode())
        .isEqualTo(400);
    assertThat(
            admin("POST", "/admin/genealogy/persons", Map.of("given", "x", "reason", 5))
                .statusCode())
        .isEqualTo(400);
    HttpResponse<String> badDate =
        admin(
            "POST",
            "/admin/genealogy/events",
            Map.of("personId", 322, "type", "BIRT", "dateText", "30.4.1913", "reason", "x"));
    assertThat(badDate.statusCode()).isEqualTo(400);
    assertThat(json.readTree(badDate.body()).get("error").asText()).contains("30.4.1913");
    assertThat(admin("DELETE", "/admin/genealogy/persons/322", Map.of("reason", "x")).statusCode())
        .isEqualTo(409);
    assertThat(
            admin("PUT", "/admin/genealogy/persons/999999", Map.of("notes", "x", "reason", "x"))
                .statusCode())
        .isEqualTo(404);
    assertThat(admin("POST", "/admin/genealogy/widgets", Map.of("reason", "x")).statusCode())
        .isEqualTo(404);
  }

  @Test
  void aChangeCanBeListedAndUndone() throws Exception {
    long name =
        jdbc.sql("SELECT id FROM gen_name WHERE person_id = 324 AND is_primary")
            .query(Long.class)
            .single();
    ok(
        admin(
            "PUT",
            "/admin/genealogy/names/" + name,
            Map.of("nickname", "Sandy", "reason", "family usage")),
        200);
    JsonNode changes = ok(admin("GET", "/admin/genealogy/changes?personId=324", null), 200);
    assertThat(changes.get(0).get("entity").asText()).isEqualTo("name");
    assertThat(changes.get(0).get("after").get("nickname").asText()).isEqualTo("Sandy");
    long change = changes.get(0).get("id").asLong();

    ok(
        admin("POST", "/admin/genealogy/changes/" + change + "/undo", Map.of("reason", "wrong")),
        200);
    assertThat(
            jdbc.sql("SELECT nickname FROM gen_name WHERE id = :id")
                .param("id", name)
                .query(String.class)
                .optional())
        .isEmpty();
    assertThat(
            admin("POST", "/admin/genealogy/changes/" + change + "/undo", Map.of("reason", "again"))
                .statusCode())
        .isEqualTo(409);
  }

  @Test
  void theReviewListShowsTheImportsQuestions() throws Exception {
    JsonNode review = ok(admin("GET", "/admin/genealogy/review", null), 200);
    assertThat(review.size()).isGreaterThan(5);
    assertThat(review.toString()).contains("conflicting birth dates");
  }

  @Test
  void mergeOverHttp() throws Exception {
    long a =
        ok(admin("POST", "/admin/genealogy/persons", Map.of("given", "A", "reason", "x")), 201)
            .get("id")
            .asLong();
    long b =
        ok(admin("POST", "/admin/genealogy/persons", Map.of("given", "B", "reason", "x")), 201)
            .get("id")
            .asLong();
    JsonNode out =
        ok(
            admin(
                "POST", "/admin/genealogy/persons/" + a + "/merge/" + b, Map.of("reason", "same")),
            200);
    assertThat(out.get("keep").asLong()).isEqualTo(a);
    assertThat(
            ok(send("GET", "/family-tree/person/" + b, null, "X-Auth-Email", USER), 200)
                .get("id")
                .asLong())
        .isEqualTo(a);
  }

  @Test
  void everyAdminEndpointRefusesUsersAndProcessors() throws Exception {
    List<String[]> calls =
        List.of(
            new String[] {"POST", "/admin/genealogy/persons"},
            new String[] {"POST", "/admin/genealogy/persons/322/names"},
            new String[] {"PUT", "/admin/genealogy/persons/322"},
            new String[] {"DELETE", "/admin/genealogy/events/1"},
            new String[] {"POST", "/admin/genealogy/persons/322/merge/323"},
            new String[] {"GET", "/admin/genealogy/changes"},
            new String[] {"POST", "/admin/genealogy/changes/1/undo"},
            new String[] {"GET", "/admin/genealogy/review"},
            new String[] {"GET", "/admin/genealogy/places"},
            new String[] {"GET", "/admin/genealogy/sources"},
            new String[] {"GET", "/admin/genealogy/export.ged"});
    String body = "{\"reason\":\"not allowed\",\"notes\":\"x\"}";
    long before = jdbc.sql("SELECT count(*) FROM gen_change").query(Long.class).single();
    for (String[] c : calls) {
      assertThat(send(c[0], c[1], body, "X-Auth-Email", USER).statusCode())
          .as("user " + c[0] + " " + c[1])
          .isIn(401, 403);
      assertThat(send(c[0], c[1], body, "Authorization", PROCESSOR_AUTH_HEADER).statusCode())
          .as("processor " + c[0] + " " + c[1])
          .isIn(401, 403);
      assertThat(send(c[0], c[1], body).statusCode())
          .as("anonymous " + c[0] + " " + c[1])
          .isIn(401, 403);
    }
    assertThat(jdbc.sql("SELECT count(*) FROM gen_change").query(Long.class).single())
        .isEqualTo(before);
  }

  @Test
  void anImageCanBeMadeSomeonesPortraitInOneWrite() throws Exception {
    long record = GenealogyFixtures.record(jdbcTemplate);
    long page =
        GenealogyFixtures.page(
            jdbcTemplate, storage, record, 1, GenealogyFixtures.png(40, 30), "image/png");
    long before = jdbc.sql("SELECT count(*) FROM gen_change").query(Long.class).single();
    JsonNode media =
        ok(
            admin(
                "POST",
                "/admin/genealogy/media",
                Map.of(
                    "pageId",
                    page,
                    "cropLeft",
                    0,
                    "cropTop",
                    0,
                    "cropWidth",
                    20,
                    "cropHeight",
                    20,
                    "personId",
                    322,
                    "isPortrait",
                    true,
                    "reason",
                    "wedding photograph")),
            201);
    assertThat(jdbc.sql("SELECT count(*) FROM gen_change").query(Long.class).single())
        .isEqualTo(before + 2);
    JsonNode person = ok(send("GET", "/family-tree/person/322", null, "X-Auth-Email", USER), 200);
    assertThat(person.get("portrait").get("id").asLong()).isEqualTo(media.get("id").asLong());
    assertThat(person.get("portrait").get("imageUrl").asText())
        .isEqualTo("/api/family-tree/media/" + media.get("id").asLong() + "/image");
  }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd backend && ./gradlew test --tests '*AdminGenealogyControllerTest'`

Expected: FAIL: the admin calls answer 404 (no handler); `everyAdminEndpointRefusesUsersAndProcessors` already passes because SecurityConfig refuses before routing

- [ ] **Step 3: Write the controller**

Create `backend/src/main/java/place/icomb/archiver/controller/AdminGenealogyController.java`:

```java
package place.icomb.archiver.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.genealogy.GenEntity;
import place.icomb.archiver.genealogy.GenealogyException;
import place.icomb.archiver.genealogy.GenealogyHistoryService;
import place.icomb.archiver.genealogy.GenealogyService;
import place.icomb.archiver.genealogy.Rows;

/**
 * Editing the genealogy. Administrators only (SecurityConfig requires ROLE_ADMIN for every method
 * under {@code /api/admin}); every write carries {@code "reason"} in its JSON body and leaves one
 * {@code gen_change} row per row it changed.
 */
@RestController
@RequestMapping("/api/admin/genealogy")
public class AdminGenealogyController {

  private static final Map<String, GenEntity> KINDS =
      Map.ofEntries(
          Map.entry("persons", GenEntity.PERSON),
          Map.entry("names", GenEntity.NAME),
          Map.entry("events", GenEntity.EVENT),
          Map.entry("couples", GenEntity.COUPLE),
          Map.entry("parent-links", GenEntity.PARENT_LINK),
          Map.entry("places", GenEntity.PLACE),
          Map.entry("place-names", GenEntity.PLACE_NAME),
          Map.entry("sources", GenEntity.SOURCE),
          Map.entry("citations", GenEntity.CITATION),
          Map.entry("record-links", GenEntity.RECORD_LINK),
          Map.entry("media", GenEntity.MEDIA),
          Map.entry("media-people", GenEntity.MEDIA_PERSON));

  private final GenealogyService genealogy;
  private final GenealogyHistoryService history;
  private final JdbcTemplate jdbc;

  public AdminGenealogyController(
      GenealogyService genealogy, GenealogyHistoryService history, JdbcTemplate jdbc) {
    this.genealogy = genealogy;
    this.history = history;
    this.jdbc = jdbc;
  }

  @PostMapping("/{kind}")
  public ResponseEntity<Map<String, Object>> create(
      @PathVariable String kind, @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> in = fields(body);
    String reason = reason(in);
    GenEntity entity = kind(kind);
    Map<String, Object> out;
    if (entity == GenEntity.PERSON) {
      out = genealogy.createPerson(in, reason);
    } else if (entity == GenEntity.MEDIA && in.containsKey("personId")) {
      Object person = in.remove("personId");
      Object portrait = in.remove("isPortrait");
      if (!(person instanceof Number n) || (portrait != null && !(portrait instanceof Boolean))) {
        throw GenealogyException.badRequest(
            "personId must be a number and isPortrait true or false");
      }
      out = genealogy.createMedia(in, n.intValue(), Boolean.TRUE.equals(portrait), reason);
    } else {
      out = genealogy.create(entity, in, reason);
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(out);
  }

  @PostMapping("/persons/{personId}/names")
  public ResponseEntity<Map<String, Object>> addName(
      @PathVariable int personId, @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> in = fields(body);
    String reason = reason(in);
    in.put("personId", personId);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(genealogy.create(GenEntity.NAME, in, reason));
  }

  @PutMapping("/{kind}/{id}")
  public Map<String, Object> update(
      @PathVariable String kind,
      @PathVariable long id,
      @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> in = fields(body);
    String reason = reason(in);
    return genealogy.update(kind(kind), id, in, reason);
  }

  @DeleteMapping("/{kind}/{id}")
  public Map<String, Object> delete(
      @PathVariable String kind,
      @PathVariable long id,
      @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> in = fields(body);
    return genealogy.delete(kind(kind), id, reason(in));
  }

  @PostMapping("/persons/{keepId}/merge/{goneId}")
  public Map<String, Object> merge(
      @PathVariable int keepId,
      @PathVariable int goneId,
      @RequestBody(required = false) Map<String, Object> body) {
    return history.mergePersons(keepId, goneId, reason(fields(body)));
  }

  @GetMapping("/changes")
  public List<Map<String, Object>> changes(
      @RequestParam(required = false) String entity,
      @RequestParam(required = false) Long entityId,
      @RequestParam(required = false) Integer personId,
      @RequestParam(defaultValue = "100") int limit) {
    if (personId != null) {
      return genealogy.personChanges(personId, limit);
    }
    return genealogy.changes(entity, entityId, limit);
  }

  @PostMapping("/changes/{changeId}/undo")
  public Map<String, Object> undo(
      @PathVariable long changeId, @RequestBody(required = false) Map<String, Object> body) {
    return history.undo(changeId, reason(fields(body)));
  }

  @GetMapping("/review")
  public List<Map<String, Object>> review() {
    return genealogy.review();
  }

  /** Places by name, for the place picker. */
  @GetMapping("/places")
  public List<Map<String, Object>> places(
      @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "50") int limit) {
    return Rows.camel(
        jdbc.queryForList(
            "SELECT id, name, parent_id, lat, lon FROM gen_place"
                + " WHERE name ILIKE '%' || ? || '%' ORDER BY name, id LIMIT ?",
            q, Math.max(1, Math.min(limit, 1000))));
  }

  /** Sources by title, for the citation picker. */
  @GetMapping("/sources")
  public List<Map<String, Object>> sources(
      @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "50") int limit) {
    return Rows.camel(
        jdbc.queryForList(
            "SELECT id, title, author, record_id, url FROM gen_source"
                + " WHERE title ILIKE '%' || ? || '%' ORDER BY title, id LIMIT ?",
            q, Math.max(1, Math.min(limit, 1000))));
  }

  @ExceptionHandler(GenealogyException.class)
  public ResponseEntity<Map<String, Object>> refuse(GenealogyException e) {
    HttpStatus status =
        switch (e.kind()) {
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
          case CONFLICT -> HttpStatus.CONFLICT;
        };
    return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
  }

  private static GenEntity kind(String kind) {
    GenEntity entity = KINDS.get(kind);
    if (entity == null) {
      throw GenealogyException.notFound("no genealogy collection '%s'".formatted(kind));
    }
    return entity;
  }

  private static Map<String, Object> fields(Map<String, Object> body) {
    return body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
  }

  /** Takes {@code reason} out of the body, so the rest is only the entity's fields. */
  private static String reason(Map<String, Object> in) {
    Object reason = in.remove("reason");
    if (reason != null && !(reason instanceof String)) {
      throw GenealogyException.badRequest("reason must be a string");
    }
    GenealogyService.requireReason((String) reason);
    return (String) reason;
  }
}
```

- [ ] **Step 4: Run it to see it pass**

Run: `cd backend && ./gradlew test --tests '*AdminGenealogyControllerTest'`

Expected: PASS (7 tests)

- [ ] **Step 5: Format**

Run: `cd backend && ./gradlew spotlessApply`

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A backend/src/main/java/place/icomb/archiver/controller/AdminGenealogyController.java backend/src/test/java/place/icomb/archiver/controller/AdminGenealogyControllerTest.java
git commit -m "feat: admin REST API for the genealogy"
```

---

### Task 10: Media image endpoint (lossless crop)

**Files:**
- Modify (replace): `backend/src/main/java/place/icomb/archiver/genealogy/MediaImages.java` (adds `Image` and `image(long)`)
- Modify (replace): `backend/src/main/java/place/icomb/archiver/controller/FamilyTreeController.java` (adds `GET /media/{mediaId}/image`)
- Test: `backend/src/test/java/place/icomb/archiver/controller/MediaImageTest.java`

**Interfaces:**
- Consumes: `MediaImages.attachment(long)` and `dimensions` (Task 7), and `GenealogyService.create(GenEntity.MEDIA, …)` (Task 7).
- Produces (used by Tasks 13 and 14):
  - `MediaImages.Image(byte[] bytes, String contentType)` and `Image image(long mediaId)`. A whole-page item returns the stored file's bytes with the attachment's mime. A cropped item returns the region copied from the decoded original, without scaling, as `image/png`.
  - `GET /api/family-tree/media/{mediaId}/image` answers 200, 404 (`{"error"}`), or 409 when the crop no longer fits the current scan.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/place/icomb/archiver/controller/MediaImageTest.java`:

```java
package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.genealogy.GenEntity;
import place.icomb.archiver.genealogy.GenealogyFixtures;
import place.icomb.archiver.genealogy.GenealogyService;
import place.icomb.archiver.service.StorageService;

/** A media item's image is the original's own pixels: never rescaled, never re-encoded lossily. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaImageTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StorageService storage;
  @Autowired private GenealogyService genealogy;

  private final HttpClient http = HttpClient.newHttpClient();
  private static final String USER = "media-reader@example.com";

  @BeforeEach
  void setUp() {
    GenealogyFixtures.signInAsAdmin();
    jdbc.update("DELETE FROM app_user_email WHERE email = ?", USER);
    jdbc.update("DELETE FROM app_user WHERE display_name = 'MediaReader'");
    Long id =
        jdbc.queryForObject(
            "INSERT INTO app_user (display_name, role) VALUES ('MediaReader', 'user') RETURNING id",
            Long.class);
    jdbc.update("INSERT INTO app_user_email (user_id, email) VALUES (?, ?)", id, USER);
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  private HttpResponse<byte[]> image(long mediaId) throws Exception {
    return http.send(
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:" + port + "/api/family-tree/media/" + mediaId + "/image"))
            .header("X-Auth-Email", USER)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private long media(long pageId, Integer... crop) {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("pageId", pageId);
    if (crop.length == 4) {
      body.put("cropLeft", crop[0]);
      body.put("cropTop", crop[1]);
      body.put("cropWidth", crop[2]);
      body.put("cropHeight", crop[3]);
    }
    return ((Number) genealogy.create(GenEntity.MEDIA, body, "photo").get("id")).longValue();
  }

  private static void assertSamePixels(
      BufferedImage crop, BufferedImage original, int left, int top) {
    for (int y = 0; y < crop.getHeight(); y++) {
      for (int x = 0; x < crop.getWidth(); x++) {
        assertThat(crop.getRGB(x, y))
            .as("pixel %d,%d", x, y)
            .isEqualTo(original.getRGB(left + x, top + y));
      }
    }
  }

  @Test
  void aWholePageItemIsTheStoredFileByteForByte() throws Exception {
    byte[] original = GenealogyFixtures.png(40, 30);
    long record = GenealogyFixtures.record(jdbc);
    long page = GenealogyFixtures.page(jdbc, storage, record, 1, original, "image/png");

    HttpResponse<byte[]> r = image(media(page));

    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Content-Type")).contains("image/png");
    assertThat(r.body()).isEqualTo(original);
  }

  @Test
  void aCropIsTheRegionsExactPixelsAsPng() throws Exception {
    byte[] original = GenealogyFixtures.png(40, 30);
    long record = GenealogyFixtures.record(jdbc);
    long page = GenealogyFixtures.page(jdbc, storage, record, 1, original, "image/png");

    HttpResponse<byte[]> r = image(media(page, 5, 6, 10, 8));

    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Content-Type")).contains("image/png");
    BufferedImage crop = ImageIO.read(new ByteArrayInputStream(r.body()));
    assertThat(crop.getWidth()).isEqualTo(10);
    assertThat(crop.getHeight()).isEqualTo(8);
    assertSamePixels(crop, ImageIO.read(new ByteArrayInputStream(original)), 5, 6);
  }

  @Test
  void aCropTouchingTheImageEdgeIsAllowedAndExact() throws Exception {
    byte[] original = GenealogyFixtures.png(40, 30);
    long record = GenealogyFixtures.record(jdbc);
    long page = GenealogyFixtures.page(jdbc, storage, record, 1, original, "image/png");

    BufferedImage crop =
        ImageIO.read(new ByteArrayInputStream(image(media(page, 30, 20, 10, 10)).body()));

    assertSamePixels(crop, ImageIO.read(new ByteArrayInputStream(original)), 30, 20);
  }

  @Test
  void aCropOfAJpegScanCopiesTheDecodedPixelsWithoutRecompressing() throws Exception {
    byte[] jpeg = GenealogyFixtures.jpeg(64, 48);
    long record = GenealogyFixtures.record(jdbc);
    long page = GenealogyFixtures.page(jdbc, storage, record, 1, jpeg, "image/jpeg");

    HttpResponse<byte[]> r = image(media(page, 8, 8, 16, 16));

    assertThat(r.headers().firstValue("Content-Type")).contains("image/png");
    assertSamePixels(
        ImageIO.read(new ByteArrayInputStream(r.body())),
        ImageIO.read(new ByteArrayInputStream(jpeg)),
        8,
        8);
    assertThat(image(media(page)).body()).isEqualTo(jpeg);
  }

  @Test
  void aCropTheCurrentScanNoLongerContainsIsAConflictNotAnError() throws Exception {
    long record = GenealogyFixtures.record(jdbc);
    long page =
        GenealogyFixtures.page(
            jdbc, storage, record, 1, GenealogyFixtures.png(40, 30), "image/png");
    long media = media(page, 30, 20, 10, 10);
    String smaller = storage.storePageImage(GenealogyFixtures.png(20, 20));
    jdbc.update(
        "UPDATE attachment SET path = ? WHERE id = (SELECT attachment_id FROM page WHERE id = ?)",
        smaller,
        page);

    assertThat(image(media).statusCode()).isEqualTo(409);
    assertThat(image(99999999L).statusCode()).isEqualTo(404);
  }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd backend && ./gradlew test --tests '*MediaImageTest'`

Expected: FAIL: the image requests answer 404 (`No static resource api/family-tree/media/…`)

- [ ] **Step 3: Add the image to MediaImages**

OcrImageService is not reused: it scales boxes from the OCR engine's coordinate space and writes JPEG (decision 26). This class reads the stored original through the same `AttachmentRepository` and `StorageService.getPath`.

Replace the whole of `backend/src/main/java/place/icomb/archiver/genealogy/MediaImages.java` with:

```java
package place.icomb.archiver.genealogy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import place.icomb.archiver.model.Attachment;
import place.icomb.archiver.repository.AttachmentRepository;
import place.icomb.archiver.service.StorageService;

/**
 * The stored scan behind a page: its pixel size, and the image a media item shows.
 *
 * <p>Nothing derived is stored and nothing is rescaled. A whole-page item is the stored file's own
 * bytes; a cropped one is the region's pixels copied out of the decoded original and encoded as
 * lossless PNG.
 */
@Service
public class MediaImages {

  public record Dimensions(int width, int height) {}

  public record Image(byte[] bytes, String contentType) {}

  private final JdbcTemplate jdbc;
  private final AttachmentRepository attachments;
  private final StorageService storage;

  public MediaImages(JdbcTemplate jdbc, AttachmentRepository attachments, StorageService storage) {
    this.jdbc = jdbc;
    this.attachments = attachments;
    this.storage = storage;
  }

  /** The pixel size of the stored original, read from its header without decoding it. */
  public Dimensions dimensions(long pageId) {
    Path file = original(pageId);
    try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
      Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
      if (readers == null || !readers.hasNext()) {
        throw GenealogyException.conflict("the image of page %d cannot be read".formatted(pageId));
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(in);
        return new Dimensions(reader.getWidth(0), reader.getHeight(0));
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw GenealogyException.conflict("the image of page %d cannot be read".formatted(pageId));
    }
  }

  public Image image(long mediaId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT page_id, crop_left, crop_top, crop_width, crop_height FROM gen_media"
                + " WHERE id = ?",
            mediaId);
    if (rows.isEmpty()) {
      throw GenealogyException.notFound("media %d not found".formatted(mediaId));
    }
    Map<String, Object> m = rows.get(0);
    long pageId = ((Number) m.get("page_id")).longValue();
    Attachment attachment = attachment(pageId);
    Path file = storage.getPath(attachment);
    try {
      if (m.get("crop_left") == null) {
        String mime =
            attachment.getMime() != null ? attachment.getMime() : "application/octet-stream";
        return new Image(Files.readAllBytes(file), mime);
      }
      BufferedImage full = ImageIO.read(file.toFile());
      if (full == null) {
        throw GenealogyException.conflict("the image of page %d cannot be read".formatted(pageId));
      }
      int left = (Integer) m.get("crop_left");
      int top = (Integer) m.get("crop_top");
      int width = (Integer) m.get("crop_width");
      int height = (Integer) m.get("crop_height");
      if (left + width > full.getWidth() || top + height > full.getHeight()) {
        throw GenealogyException.conflict(
            "media %d's crop lies outside page %d's current %dx%d image"
                .formatted(mediaId, pageId, full.getWidth(), full.getHeight()));
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(full.getSubimage(left, top, width, height), "png", out);
      return new Image(out.toByteArray(), "image/png");
    } catch (IOException e) {
      throw GenealogyException.conflict("the image of page %d cannot be read".formatted(pageId));
    }
  }

  Attachment attachment(long pageId) {
    List<Map<String, Object>> page =
        jdbc.queryForList("SELECT attachment_id FROM page WHERE id = ?", pageId);
    if (page.isEmpty()) {
      throw GenealogyException.notFound("page %d not found".formatted(pageId));
    }
    long attachmentId = ((Number) page.get(0).get("attachment_id")).longValue();
    return attachments
        .findById(attachmentId)
        .orElseThrow(
            () -> GenealogyException.notFound("page %d has no stored image".formatted(pageId)));
  }

  Path original(long pageId) {
    return storage.getPath(attachment(pageId));
  }
}
```

- [ ] **Step 4: Add the endpoint**

Replace the whole of `backend/src/main/java/place/icomb/archiver/controller/FamilyTreeController.java` with:

```java
package place.icomb.archiver.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.genealogy.GenealogyException;
import place.icomb.archiver.genealogy.GenealogyReadService;
import place.icomb.archiver.genealogy.MediaImages;
import place.icomb.archiver.model.PagePersonMatch;
import place.icomb.archiver.service.FamilyTreeService;
import place.icomb.archiver.service.FamilyTreeService.Person;
import place.icomb.archiver.service.PersonMatchService;
import place.icomb.archiver.service.PersonMatchService.RecordPersonMatch;

@RestController
@RequestMapping("/api/family-tree")
public class FamilyTreeController {

  private final FamilyTreeService familyTreeService;
  private final PersonMatchService personMatchService;
  private final GenealogyReadService genealogy;
  private final MediaImages images;

  public FamilyTreeController(
      FamilyTreeService familyTreeService,
      PersonMatchService personMatchService,
      GenealogyReadService genealogy,
      MediaImages images) {
    this.familyTreeService = familyTreeService;
    this.personMatchService = personMatchService;
    this.genealogy = genealogy;
    this.images = images;
  }

  /** A media item's image: the stored original, or its crop as lossless PNG. */
  @GetMapping("/media/{mediaId}/image")
  public ResponseEntity<?> mediaImage(@PathVariable long mediaId) {
    try {
      MediaImages.Image image = images.image(mediaId);
      return ResponseEntity.ok()
          .contentType(org.springframework.http.MediaType.parseMediaType(image.contentType()))
          .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline")
          .body(image.bytes());
    } catch (GenealogyException e) {
      return ResponseEntity.status(
              e.kind() == GenealogyException.Kind.NOT_FOUND
                  ? org.springframework.http.HttpStatus.NOT_FOUND
                  : org.springframework.http.HttpStatus.CONFLICT)
          .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .body(Map.of("error", e.getMessage()));
    }
  }

  @GetMapping("/search")
  public ResponseEntity<List<Map<String, Object>>> search(
      @RequestParam String q,
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(defaultValue = "false") boolean all) {
    var results = familyTreeService.search(q, limit, all);
    List<Map<String, Object>> response = new ArrayList<>();
    for (var r : results) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("personId", r.personId());
      m.put("name", r.name());
      m.put("fullEntry", r.fullEntry());
      m.put("section", r.section());
      m.put("code", r.code());
      m.put("score", r.score());
      m.put("birthYear", r.birthYear());
      m.put("deathYear", r.deathYear());
      response.add(m);
    }
    return ResponseEntity.ok(response);
  }

  @GetMapping("/relate")
  public ResponseEntity<Map<String, Object>> relate(
      @RequestParam int personId, @RequestParam(required = false) Integer refId) {
    var result =
        refId != null
            ? familyTreeService.relate(personId, refId)
            : familyTreeService.relate(personId);
    if (result == null) return ResponseEntity.notFound().build();

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("personId", result.personId());
    m.put("personName", result.personName());
    m.put("kinshipLabel", result.kinshipLabel());
    m.put("pathDescription", result.pathDescription());
    m.put("commonAncestorName", result.commonAncestorName());
    m.put("commonAncestorId", result.commonAncestorId());
    m.put("stepsFromPerson", result.stepsFromPerson());
    m.put("stepsFromRef", result.stepsFromRef());
    m.put("refPersonName", result.refPersonName());
    return ResponseEntity.ok(m);
  }

  @GetMapping("/person/{id}")
  public ResponseEntity<Map<String, Object>> getPerson(@PathVariable int id) {
    Person p = familyTreeService.getPerson(id);
    if (p == null) return ResponseEntity.notFound().build();

    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", p.id);
    m.put("name", p.name);
    m.put("fullEntry", p.fullEntry);
    m.put("birthYear", p.birthYear);
    m.put("deathYear", p.deathYear);
    m.put("birthPlace", p.birthPlace);
    m.put("section", p.section);
    m.put("code", p.fullCode());
    m.put("depth", p.depth);

    if (p.parent != null) {
      m.put("parent", personRef(p.parent));
    } else {
      m.put("parent", null);
    }

    List<Map<String, Object>> children = new ArrayList<>();
    for (Person child : p.children) {
      children.add(personRef(child));
    }
    m.put("children", children);

    // Siblings = parent's other children (excluding self)
    List<Map<String, Object>> siblings = new ArrayList<>();
    if (p.parent != null) {
      for (Person sibling : p.parent.children) {
        if (sibling.id != p.id) {
          siblings.add(personRef(sibling));
        }
      }
    }
    m.put("siblings", siblings);
    m.put("spouses", p.spouses);
    m.put("events", familyTreeService.getLifeEvents(p));
    Map<String, Object> detail = genealogy.personDetail(p.id);
    if (detail != null) {
      m.putAll(detail);
    }

    return ResponseEntity.ok(m);
  }

  private static Map<String, Object> personRef(Person p) {
    Map<String, Object> ref = new LinkedHashMap<>();
    ref.put("id", p.id);
    ref.put("name", p.name);
    ref.put("birthYear", p.birthYear);
    ref.put("deathYear", p.deathYear);
    return ref;
  }

  @GetMapping("/page-matches/{pageId}")
  public ResponseEntity<List<Map<String, Object>>> pageMatches(@PathVariable Long pageId) {
    var matches = personMatchService.getPageMatches(pageId);
    List<Map<String, Object>> response = new ArrayList<>();
    for (PagePersonMatch m : matches) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("personId", m.getPersonId());
      map.put("personName", m.getPersonName());
      map.put("score", m.getScore());
      map.put("context", m.getContext());
      // Enrich with family tree data
      Person p = familyTreeService.getPerson(m.getPersonId());
      if (p != null) {
        map.put("section", p.section);
        map.put("code", p.fullCode());
        map.put("birthYear", p.birthYear);
        map.put("deathYear", p.deathYear);
      }
      response.add(map);
    }
    return ResponseEntity.ok(response);
  }

  @GetMapping("/record-matches/{recordId}")
  public ResponseEntity<List<Map<String, Object>>> recordMatches(@PathVariable Long recordId) {
    var matches = personMatchService.getRecordMatches(recordId);
    List<Map<String, Object>> response = new ArrayList<>();
    for (RecordPersonMatch m : matches) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("personId", m.personId());
      map.put("personName", m.personName());
      map.put("maxScore", m.maxScore());
      map.put("pageCount", m.pageSeqs().size());
      map.put("pageSeqs", m.pageSeqs());
      // Enrich with family tree data
      Person p = familyTreeService.getPerson(m.personId());
      if (p != null) {
        map.put("section", p.section);
        map.put("code", p.fullCode());
        map.put("birthYear", p.birthYear);
        map.put("deathYear", p.deathYear);
      }
      response.add(map);
    }
    return ResponseEntity.ok(response);
  }
}
```

- [ ] **Step 5: Run it to see it pass**

Run: `cd backend && ./gradlew test --tests '*MediaImageTest' --tests '*FamilyTreeControllerTest'`

Expected: PASS (5 + 19 tests)

- [ ] **Step 6: Format**

Run: `cd backend && ./gradlew spotlessApply`

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A backend/src/main/java/place/icomb/archiver/genealogy/MediaImages.java backend/src/main/java/place/icomb/archiver/controller/FamilyTreeController.java backend/src/test/java/place/icomb/archiver/controller/MediaImageTest.java
git commit -m "feat: media images: the stored original, or a lossless crop of it"
```

---

### Task 11: MCP write tools and the extended get_person

**Files:**
- Create: `backend/src/main/java/place/icomb/archiver/mcp/GenealogyMcpTools.java`
- Modify: `backend/src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java:28` (field), `:46` (constructor parameter), `:57` (assignment), `:496-498` (description), `:542` (stored facts)
- Test: `backend/src/test/java/place/icomb/archiver/mcp/McpGenealogyToolsTest.java`

**Interfaces:**
- Consumes: `GenealogyService` and `GenealogyHistoryService` (Tasks 7–8), `GenealogyReadService.personDetail` (Task 6), and `PlatformTransactionManager`. The Spring AI annotation scanner registers `@McpTool` methods on any `@Component`.
- Produces:
  - Tools, each with `reason` required (the last parameter), administrators only, failing closed with `IllegalStateException("this tool is available to administrators only")`:
    - `add_person(given, surname, prefix, sex, notes, reason)`
    - `update_person(personId, sex, living, notes, needsReview, reason)`
    - `add_name(personId, type, given, surname, prefix, suffix, nickname, isPrimary, reason)`
    - `add_event(personId, coupleId, type, date, place, description, reason)`
    - `update_event(eventId, type, date, place, description, reason)`
    - `link_parent(parentId, childId, type, coupleId, reason)`
    - `add_couple(person1Id, person2Id, type, sortOrder, reason)`
    - `cite(sourceId, recordId, seq, pageId, detail, quality, eventId, nameId, coupleId, parentChildId, personId, reason)`
    - `link_record(personId, recordId, seq, role, note, quality, reason)`
    - `add_media(recordId, seq, cropLeft, cropTop, cropWidth, cropHeight, caption, date, personIds, portraitPersonId, reason)`
    - `merge_persons(keepId, goneId, reason)`
    - `undo_change(changeId, reason)`
    - `tree_changes(personId, entity, entityId, limit)`
  - Edit-rule refusals come back as `{"error": message}` with nothing written. Each call is one transaction, and therefore one undo.
  - `get_person` adds `uid, sex, living, notes, needsReview, names, couples, parentLinks, recordLinks, media, portrait, sources`, and its `lifeEvents` are the structured events.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/place/icomb/archiver/mcp/McpGenealogyToolsTest.java`:

```java
package place.icomb.archiver.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The tree-editing MCP tools: administrators only, a reason every time, and undoable. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class McpGenealogyToolsTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired private GenealogyMcpTools tools;
  @Autowired private ArchiverMcpTools readTools;
  @Autowired private JdbcTemplate jdbc;

  private static final int ALEXANDER = 322;

  private void signIn(String... roles) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "mcp-genealogy@example.com",
                "n/a",
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
  }

  @BeforeEach
  void admin() {
    signIn("ROLE_USER", "ROLE_ADMIN");
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  private long lastChange() {
    return jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM gen_change", Long.class);
  }

  @Test
  void anAdministratorAddsAPersonWithABirthPlaceAndCanUndoIt() {
    Map<String, Object> person =
        tools.addPerson("Anna", "Novák", null, "F", null, "letter of 1938");
    int id = ((Number) person.get("id")).intValue();
    Map<String, Object> birth =
        tools.addEvent(id, null, "BIRT", "12 MAR 1890", "Dymokury-Nové", null, "parish register");
    assertThat(birth).doesNotContainKey("error");
    assertThat(
            jdbc.queryForObject(
                "SELECT p.name FROM gen_event e JOIN gen_place p ON p.id = e.place_id WHERE e.id = ?",
                String.class,
                birth.get("id")))
        .isEqualTo("Dymokury-Nové");

    Map<String, Object> undone = tools.undoChange(lastChange(), "wrong person");
    assertThat(undone).doesNotContainKey("error");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_event WHERE id = ?", Integer.class, birth.get("id")))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_place WHERE name = 'Dymokury-Nové'", Integer.class))
        .isZero();
  }

  @Test
  void aCitationOfARecordPageMakesTheRecordsSourceOnce() {
    long archive =
        jdbc.queryForObject(
            "INSERT INTO archive (name) VALUES ('mcp cite') RETURNING id", Long.class);
    long record =
        jdbc.queryForObject(
            "INSERT INTO record (archive_id, source_system, source_record_id, title, status, lang,"
                + " metadata_lang) VALUES (?, 'test', ?, 'Heiratsurkunde', 'complete', 'de', 'de')"
                + " RETURNING id",
            Long.class,
            archive,
            "cite-" + System.nanoTime());
    long attachment =
        jdbc.queryForObject(
            "INSERT INTO attachment (record_id, role, path, mime) VALUES (?, 'page_image', 'x.jpg',"
                + " 'image/jpeg') RETURNING id",
            Long.class,
            record);
    long page =
        jdbc.queryForObject(
            "INSERT INTO page (record_id, seq, attachment_id) VALUES (?, 1, ?) RETURNING id",
            Long.class,
            record,
            attachment);
    long name =
        jdbc.queryForObject(
            "SELECT id FROM gen_name WHERE person_id = ? AND is_primary", Long.class, ALEXANDER);

    Map<String, Object> first =
        tools.cite(
            null, record, 1, null, "full name", 3, null, name, null, null, null, "certificate");
    Map<String, Object> second =
        tools.cite(
            null,
            record,
            1,
            null,
            "same page",
            3,
            null,
            null,
            null,
            null,
            ALEXANDER,
            "certificate");

    assertThat(first.get("sourceId")).isEqualTo(second.get("sourceId"));
    assertThat(((Number) first.get("pageId")).longValue()).isEqualTo(page);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM gen_source WHERE record_id = ?", Integer.class, record))
        .isEqualTo(1);
  }

  @Test
  void refusalsComeBackAsErrorsAndChangeNothing() {
    long before = lastChange();
    assertThat(tools.addPerson("X", null, null, null, null, " ")).containsKey("error");
    assertThat(tools.addEvent(ALEXANDER, null, "BIRT", "30.4.1913", null, null, "x"))
        .containsKey("error");
    assertThat(tools.linkParent(324, 1, "birth", null, "x")).containsKey("error");
    assertThat(
            tools.cite(null, null, null, null, null, null, null, null, null, null, ALEXANDER, "x"))
        .containsKey("error");
    assertThat(tools.addMedia(99999999L, 1, null, null, null, null, null, null, null, null, "x"))
        .containsKey("error");
    assertThat(lastChange()).isEqualTo(before);
  }

  @Test
  void getPersonCarriesTheStoredFacts() {
    Map<String, Object> p = readTools.getPerson(ALEXANDER);
    assertThat(p)
        .containsKeys(
            "names", "couples", "parentLinks", "recordLinks", "media", "portrait", "sources");
    assertThat((List<?>) p.get("couples")).hasSize(1);
    assertThat(((List<?>) p.get("sources")).toString()).contains("legacy text file");
    assertThat(((List<?>) p.get("lifeEvents")).toString()).contains("30 APR 1913");
  }

  @Test
  void everyWriteToolRefusesAnyoneButAnAdministrator() {
    List<Consumer<GenealogyMcpTools>> calls =
        List.of(
            t -> t.addPerson("X", null, null, null, null, "r"),
            t -> t.updatePerson(ALEXANDER, null, null, "x", null, "r"),
            t -> t.addName(ALEXANDER, "alias", "X", null, null, null, null, null, "r"),
            t -> t.addEvent(ALEXANDER, null, "RESI", null, null, null, "r"),
            t -> t.updateEvent(1L, null, null, null, "x", "r"),
            t -> t.linkParent(1, 2, null, null, "r"),
            t -> t.addCouple(1, 2, null, null, "r"),
            t -> t.cite(1L, null, null, null, null, null, null, null, null, null, ALEXANDER, "r"),
            t -> t.linkRecord(ALEXANDER, 1L, null, null, null, null, "r"),
            t -> t.addMedia(1L, 1, null, null, null, null, null, null, null, null, "r"),
            t -> t.mergePersons(1, 2, "r"),
            t -> t.undoChange(1L, "r"),
            t -> t.treeChanges(ALEXANDER, null, null, null));
    long before = lastChange();
    for (String[] who : new String[][] {{"ROLE_USER"}, {"ROLE_PROCESSOR"}, {}}) {
      signIn(who);
      for (Consumer<GenealogyMcpTools> call : calls) {
        assertThatThrownBy(() -> call.accept(tools))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("administrators only");
      }
    }
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> tools.addPerson("X", null, null, null, null, "r"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(lastChange()).isEqualTo(before);
  }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd backend && ./gradlew test --tests '*McpGenealogyToolsTest'`

Expected: FAIL: compilation error `cannot find symbol: class GenealogyMcpTools`

- [ ] **Step 3: Write the tools**

Create `backend/src/main/java/place/icomb/archiver/mcp/GenealogyMcpTools.java`:

```java
package place.icomb.archiver.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import place.icomb.archiver.genealogy.GenEntity;
import place.icomb.archiver.genealogy.GenealogyException;
import place.icomb.archiver.genealogy.GenealogyHistoryService;
import place.icomb.archiver.genealogy.GenealogyService;

/**
 * Editing the family tree over MCP. Administrators only, failing closed like {@code delete_page}:
 * with no provable {@code ROLE_ADMIN} every tool throws. Every write needs a reason and is undoable
 * with {@code undo_change}.
 */
@Component
public class GenealogyMcpTools {

  private final GenealogyService genealogy;
  private final GenealogyHistoryService history;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public GenealogyMcpTools(
      GenealogyService genealogy,
      GenealogyHistoryService history,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactions) {
    this.genealogy = genealogy;
    this.history = history;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactions);
  }

  private void requireAdmin() {
    var auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    boolean admin =
        auth != null
            && auth.isAuthenticated()
            && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    if (!admin) {
      throw new IllegalStateException("this tool is available to administrators only");
    }
  }

  /**
   * One tool call is one write: a place or source it has to make goes in the same transaction, and
   * so into the same undo.
   */
  private Map<String, Object> run(Supplier<Map<String, Object>> write) {
    requireAdmin();
    return tx.execute(
        status -> {
          try {
            return write.get();
          } catch (GenealogyException e) {
            status.setRollbackOnly();
            return Map.of("error", String.valueOf(e.getMessage()));
          }
        });
  }

  private static Map<String, Object> body(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  @McpTool(
      name = "add_person",
      title = "Add Person to the Family Tree",
      description =
          "Create a person with their primary name. sex is M, F or U. Returns the new person's"
              + " id. Give a reason (the evidence). Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> addPerson(
      @McpToolParam(description = "Given names", required = false) String given,
      @McpToolParam(description = "Surname", required = false) String surname,
      @McpToolParam(description = "Title before the name, e.g. Graf", required = false)
          String prefix,
      @McpToolParam(description = "M, F or U", required = false) String sex,
      @McpToolParam(description = "Free-text notes", required = false) String notes,
      @McpToolParam(description = "Why: the document or reasoning") String reason) {
    return run(
        () ->
            genealogy.createPerson(
                body(
                    "given", given, "surname", surname, "prefix", prefix, "sex", sex, "notes",
                    notes),
                reason));
  }

  @McpTool(
      name = "update_person",
      title = "Update Person",
      description =
          "Change a person's sex, living flag, notes or review note. Only the fields given change."
              + " Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> updatePerson(
      @McpToolParam(description = "Person id") int personId,
      @McpToolParam(description = "M, F or U", required = false) String sex,
      @McpToolParam(description = "Still living", required = false) Boolean living,
      @McpToolParam(description = "Notes", required = false) String notes,
      @McpToolParam(description = "What still needs checking", required = false) String needsReview,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () ->
            genealogy.update(
                GenEntity.PERSON,
                personId,
                body("sex", sex, "living", living, "notes", notes, "needsReview", needsReview),
                reason));
  }

  @McpTool(
      name = "add_name",
      title = "Add Name",
      description =
          "Add a name to a person: type birth, married, alias, religious, title or other. With"
              + " isPrimary true it becomes the name the tree shows. Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> addName(
      @McpToolParam(description = "Person id") int personId,
      @McpToolParam(description = "birth, married, alias, religious, title, other") String type,
      @McpToolParam(description = "Given names", required = false) String given,
      @McpToolParam(description = "Surname", required = false) String surname,
      @McpToolParam(description = "Title before the name", required = false) String prefix,
      @McpToolParam(description = "Suffix", required = false) String suffix,
      @McpToolParam(description = "Nickname", required = false) String nickname,
      @McpToolParam(description = "Make this the primary name", required = false) Boolean isPrimary,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () ->
            genealogy.create(
                GenEntity.NAME,
                body(
                    "personId",
                    personId,
                    "type",
                    type,
                    "given",
                    given,
                    "surname",
                    surname,
                    "prefix",
                    prefix,
                    "suffix",
                    suffix,
                    "nickname",
                    nickname,
                    "isPrimary",
                    isPrimary),
                reason));
  }

  @McpTool(
      name = "add_event",
      title = "Add Event",
      description =
          "Add a GEDCOM 7 event (BIRT, CHR, BAPM, DEAT, BURI, MARR, DIV, ADOP, OCCU, TITL, RESI,"
              + " NATU, EMIG, IMMI, EDUC, EVEN) to a person, or MARR/DIV to a couple. date is a"
              + " GEDCOM 7 date: '12 MAR 1890', 'ABT 1890', 'BET 1657 AND 1664', 'AFT 1501'."
              + " place is a place name, matched exactly or created. Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> addEvent(
      @McpToolParam(description = "Person id, or give coupleId", required = false) Integer personId,
      @McpToolParam(description = "Couple id, for MARR and DIV", required = false) Long coupleId,
      @McpToolParam(description = "GEDCOM 7 event tag") String type,
      @McpToolParam(description = "GEDCOM 7 date", required = false) String date,
      @McpToolParam(description = "Place name", required = false) String place,
      @McpToolParam(description = "Description", required = false) String description,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () ->
            genealogy.create(
                GenEntity.EVENT,
                body(
                    "personId",
                    personId,
                    "coupleId",
                    coupleId,
                    "type",
                    type,
                    "dateText",
                    date,
                    "placeName",
                    place,
                    "description",
                    description),
                reason));
  }

  @McpTool(
      name = "update_event",
      title = "Update Event",
      description =
          "Change an event's type, date, place or description. Only the fields given change."
              + " Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> updateEvent(
      @McpToolParam(description = "Event id") long eventId,
      @McpToolParam(description = "GEDCOM 7 event tag", required = false) String type,
      @McpToolParam(description = "GEDCOM 7 date", required = false) String date,
      @McpToolParam(description = "Place name", required = false) String place,
      @McpToolParam(description = "Description", required = false) String description,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () ->
            genealogy.update(
                GenEntity.EVENT,
                eventId,
                body(
                    "type", type, "dateText", date, "placeName", place, "description", description),
                reason));
  }

  @McpTool(
      name = "link_parent",
      title = "Link Parent and Child",
      description =
          "Record that parentId is a parent of childId: type birth, adopted, step, foster or"
              + " unknown; coupleId names the parents' couple. A child has at most two birth"
              + " parents, and no one can be their own ancestor. Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> linkParent(
      @McpToolParam(description = "Parent's person id") int parentId,
      @McpToolParam(description = "Child's person id") int childId,
      @McpToolParam(description = "birth, adopted, step, foster, unknown", required = false)
          String type,
      @McpToolParam(description = "The parents' couple id", required = false) Long coupleId,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () ->
            genealogy.create(
                GenEntity.PARENT_LINK,
                body("parentId", parentId, "childId", childId, "type", type, "coupleId", coupleId),
                reason));
  }

  @McpTool(
      name = "add_couple",
      title = "Add Couple",
      description =
          "Record a couple: type marriage, partnership or unknown; sortOrder is 1 for a first"
              + " marriage, 2 for a second. Add the wedding with add_event (MARR, coupleId)."
              + " Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> addCouple(
      @McpToolParam(description = "One partner's person id") int person1Id,
      @McpToolParam(description = "The other partner's person id") int person2Id,
      @McpToolParam(description = "marriage, partnership, unknown", required = false) String type,
      @McpToolParam(description = "1 for the first marriage", required = false) Integer sortOrder,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () ->
            genealogy.create(
                GenEntity.COUPLE,
                body(
                    "person1Id",
                    person1Id,
                    "person2Id",
                    person2Id,
                    "type",
                    type,
                    "sortOrder",
                    sortOrder),
                reason));
  }

  @McpTool(
      name = "cite",
      title = "Cite Evidence",
      description =
          "Attach evidence to exactly one fact: give one of eventId, nameId, coupleId,"
              + " parentChildId, personId. The source is sourceId, or the archive record"
              + " recordId (its source is made on first use); seq or pageId names the page."
              + " quality is GEDCOM QUAY: 0 unreliable, 1 questionable, 2 secondary, 3 primary."
              + " Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> cite(
      @McpToolParam(description = "Source id", required = false) Long sourceId,
      @McpToolParam(description = "Archive record id, instead of sourceId", required = false)
          Long recordId,
      @McpToolParam(description = "Page sequence in the record", required = false) Integer seq,
      @McpToolParam(description = "Page id, instead of seq", required = false) Long pageId,
      @McpToolParam(description = "Where in the source, or what it says", required = false)
          String detail,
      @McpToolParam(description = "QUAY 0-3", required = false) Integer quality,
      @McpToolParam(description = "Event id", required = false) Long eventId,
      @McpToolParam(description = "Name id", required = false) Long nameId,
      @McpToolParam(description = "Couple id", required = false) Long coupleId,
      @McpToolParam(description = "Parent link id", required = false) Long parentChildId,
      @McpToolParam(description = "Person id", required = false) Integer personId,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () -> {
          Long source = sourceId != null ? sourceId : recordSource(recordId, reason);
          Long page = pageId != null ? pageId : page(recordId, seq);
          return genealogy.create(
              GenEntity.CITATION,
              body(
                  "sourceId",
                  source,
                  "pageId",
                  page,
                  "detail",
                  detail,
                  "quality",
                  quality,
                  "eventId",
                  eventId,
                  "nameId",
                  nameId,
                  "coupleId",
                  coupleId,
                  "parentChildId",
                  parentChildId,
                  "personId",
                  personId),
              reason);
        });
  }

  @McpTool(
      name = "link_record",
      title = "Link Person to Document",
      description =
          "Record that a person appears in an archive record (optionally on one page): role"
              + " subject, mentioned, author, signatory, witness, recipient, pictured or other."
              + " It does not prove a fact; use cite for that. Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> linkRecord(
      @McpToolParam(description = "Person id") int personId,
      @McpToolParam(description = "Record id") long recordId,
      @McpToolParam(description = "Page sequence", required = false) Integer seq,
      @McpToolParam(description = "Role", required = false) String role,
      @McpToolParam(description = "Note", required = false) String note,
      @McpToolParam(description = "QUAY 0-3", required = false) Integer quality,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () ->
            genealogy.create(
                GenEntity.RECORD_LINK,
                body(
                    "personId",
                    personId,
                    "recordId",
                    recordId,
                    "pageId",
                    page(recordId, seq),
                    "role",
                    role,
                    "note",
                    note,
                    "quality",
                    quality),
                reason));
  }

  @McpTool(
      name = "add_media",
      title = "Add Photo or Image",
      description =
          "Make an image from a page scan, whole or cropped (cropLeft, cropTop, cropWidth,"
              + " cropHeight in pixels of the stored original), and attach it to people;"
              + " portraitPersonId makes it that person's portrait. Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> addMedia(
      @McpToolParam(description = "Record id") long recordId,
      @McpToolParam(description = "Page sequence") int seq,
      @McpToolParam(description = "Crop left", required = false) Integer cropLeft,
      @McpToolParam(description = "Crop top", required = false) Integer cropTop,
      @McpToolParam(description = "Crop width", required = false) Integer cropWidth,
      @McpToolParam(description = "Crop height", required = false) Integer cropHeight,
      @McpToolParam(description = "Caption", required = false) String caption,
      @McpToolParam(description = "GEDCOM 7 date of the image", required = false) String date,
      @McpToolParam(description = "People shown", required = false) List<Integer> personIds,
      @McpToolParam(description = "Whose portrait this is", required = false)
          Integer portraitPersonId,
      @McpToolParam(description = "Why") String reason) {
    return run(
        () -> {
          Map<String, Object> media =
              genealogy.create(
                  GenEntity.MEDIA,
                  body(
                      "pageId",
                      page(recordId, seq),
                      "cropLeft",
                      cropLeft,
                      "cropTop",
                      cropTop,
                      "cropWidth",
                      cropWidth,
                      "cropHeight",
                      cropHeight,
                      "caption",
                      caption,
                      "dateText",
                      date),
                  reason);
          java.util.LinkedHashSet<Integer> people = new java.util.LinkedHashSet<>();
          if (personIds != null) people.addAll(personIds);
          if (portraitPersonId != null) people.add(portraitPersonId);
          for (int person : people) {
            genealogy.create(
                GenEntity.MEDIA_PERSON,
                body(
                    "mediaId",
                    media.get("id"),
                    "personId",
                    person,
                    "isPortrait",
                    portraitPersonId != null && portraitPersonId == person),
                reason);
          }
          Map<String, Object> out = new LinkedHashMap<>(media);
          out.put("people", List.copyOf(people));
          out.put("imageUrl", "/api/family-tree/media/" + media.get("id") + "/image");
          return out;
        });
  }

  @McpTool(
      name = "merge_persons",
      title = "Merge Two People",
      description =
          "goneId is a duplicate of keepId: every name, event, couple, parent link, citation,"
              + " document link, image, page match and user link moves to keepId, and goneId"
              + " points at keepId from then on. Undoable with undo_change. Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true))
  public Map<String, Object> mergePersons(
      @McpToolParam(description = "The person who stays") int keepId,
      @McpToolParam(description = "The duplicate") int goneId,
      @McpToolParam(description = "Why they are one person") String reason) {
    return run(() -> history.mergePersons(keepId, goneId, reason));
  }

  @McpTool(
      name = "undo_change",
      title = "Undo a Tree Change",
      description =
          "Undo the write that change id belongs to (find it with tree_changes). Refused if what"
              + " it changed has been changed again since. Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false))
  public Map<String, Object> undoChange(
      @McpToolParam(description = "gen_change id") long changeId,
      @McpToolParam(description = "Why") String reason) {
    return run(() -> history.undo(changeId, reason));
  }

  @McpTool(
      name = "tree_changes",
      title = "Family Tree Change History",
      description =
          "The audit of tree edits, newest first: one person's (personId), or one kind of row"
              + " (entity: person, name, event, couple, parent_link, place, place_name, source,"
              + " citation, record_link, media, media_person). Administrators only.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
  public Map<String, Object> treeChanges(
      @McpToolParam(description = "Person id", required = false) Integer personId,
      @McpToolParam(description = "Entity kind", required = false) String entity,
      @McpToolParam(description = "Row id within the entity kind", required = false) Long entityId,
      @McpToolParam(description = "How many. Default 50.", required = false) Integer limit) {
    return run(
        () ->
            Map.of(
                "changes",
                personId != null
                    ? genealogy.personChanges(personId, limit != null ? limit : 50)
                    : genealogy.changes(entity, entityId, limit != null ? limit : 50)));
  }

  /** The source standing for an archive record, made on first use. */
  private Long recordSource(Long recordId, String reason) {
    if (recordId == null) {
      throw GenealogyException.badRequest("give sourceId or recordId");
    }
    List<Long> found =
        jdbc.queryForList(
            "SELECT id FROM gen_source WHERE record_id = ? ORDER BY id LIMIT 1",
            Long.class,
            recordId);
    if (!found.isEmpty()) return found.get(0);
    List<Map<String, Object>> record =
        jdbc.queryForList(
            "SELECT coalesce(title_en, title, 'Record ' || id) AS title, reference_code"
                + " FROM record WHERE id = ?",
            recordId);
    if (record.isEmpty()) {
      throw GenealogyException.notFound("record %d not found".formatted(recordId));
    }
    return ((Number)
            genealogy
                .create(
                    GenEntity.SOURCE,
                    body(
                        "title", record.get(0).get("title"),
                        "recordId", recordId,
                        "notes", record.get(0).get("reference_code")),
                    reason)
                .get("id"))
        .longValue();
  }

  private Long page(Long recordId, Integer seq) {
    if (recordId == null || seq == null) return null;
    List<Long> found =
        jdbc.queryForList(
            "SELECT id FROM page WHERE record_id = ? AND seq = ?", Long.class, recordId, seq);
    if (found.isEmpty()) {
      throw GenealogyException.notFound("no page %d in record %d".formatted(seq, recordId));
    }
    return found.get(0);
  }
}
```

- [ ] **Step 4: Give get_person the stored facts**

In `backend/src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java`, replace:

```java
  private final place.icomb.archiver.service.IngestService ingestService;

  /**
```

with:

```java
  private final place.icomb.archiver.service.IngestService ingestService;
  private final place.icomb.archiver.genealogy.GenealogyReadService genealogyRead;

  /**
```

In `backend/src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java`, replace:

```java
      place.icomb.archiver.service.IngestService ingestService,
      @org.springframework.beans.factory.annotation.Value(
```

with:

```java
      place.icomb.archiver.service.IngestService ingestService,
      place.icomb.archiver.genealogy.GenealogyReadService genealogyRead,
      @org.springframework.beans.factory.annotation.Value(
```

In `backend/src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java`, replace:

```java
    this.ingestService = ingestService;
```

with:

```java
    this.ingestService = ingestService;
    this.genealogyRead = genealogyRead;
```

In `backend/src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java`, replace:

```java
              + " birth/death info, life events (birth, death, marriages), children, spouses, and"
              + " kinship relationship to Alexander Friedrich Josef Czernin.",
```

with:

```java
              + " birth/death info, life events with their citations, children, spouses, and"
              + " kinship relationship to Alexander Friedrich Josef Czernin; plus the stored"
              + " facts: names, couples (with spouse id), parentLinks (with type), recordLinks,"
              + " media, portrait, sources and needsReview. Ids in these are what the tree"
              + " editing tools take.",
```

In `backend/src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java`, replace:

```java
    result.put("lifeEvents", familyTreeService.getLifeEvents(person));
```

with:

```java
    result.put("lifeEvents", familyTreeService.getLifeEvents(person));
    Map<String, Object> detail = genealogyRead.personDetail(person.id);
    if (detail != null) {
      result.putAll(detail);
    }
```

- [ ] **Step 5: Run it to see it pass**

Run: `cd backend && ./gradlew test --tests '*McpGenealogyToolsTest' --tests '*McpDeletePageTest' --tests '*McpContentToolsTest'`

Expected: PASS (5 tests, plus the existing MCP suites)

- [ ] **Step 6: Format**

Run: `cd backend && ./gradlew spotlessApply`

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A backend/src/main/java/place/icomb/archiver/mcp/GenealogyMcpTools.java backend/src/main/java/place/icomb/archiver/mcp/ArchiverMcpTools.java backend/src/test/java/place/icomb/archiver/mcp/McpGenealogyToolsTest.java
git commit -m "feat: MCP tools to edit the family tree; get_person carries the stored facts"
```

---

### Task 12: GEDCOM 7 export

**Files:**
- Create: `backend/src/main/java/place/icomb/archiver/genealogy/GedcomExporter.java`
- Modify (replace): `backend/src/main/java/place/icomb/archiver/controller/AdminGenealogyController.java` (adds `GET /export.ged`)
- Test: `backend/src/test/java/place/icomb/archiver/controller/GedcomExportTest.java`

**Interfaces:**
- Consumes: the tables, `GedcomDate` (Task 3, in the test), `GenealogyService.create` and `GenealogyFixtures` (Tasks 2 and 7).
- Produces:
  - `String GedcomExporter.export(boolean includeLiving)`.
  - `GET /api/admin/genealogy/export.ged?living=exclude|include` (default `exclude`; anything else is 400) answers `text/vnd.familysearch.gedcom;charset=UTF-8` with `Content-Disposition: attachment; filename="czernin.ged"`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/place/icomb/archiver/controller/GedcomExportTest.java`:

```java
package place.icomb.archiver.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import place.icomb.archiver.genealogy.GedcomDate;
import place.icomb.archiver.genealogy.GenEntity;
import place.icomb.archiver.genealogy.GenealogyFixtures;
import place.icomb.archiver.genealogy.GenealogyService;
import place.icomb.archiver.service.StorageService;

/** The GEDCOM 7 export: well-formed, complete, and private about the living when asked. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GedcomExportTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("pgvector/pgvector:pg18")
          .withDatabaseName("archiver_test")
          .withUsername("postgres")
          .withPassword("postgres")
          .withCommand("postgres", "-c", "max_connections=50");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&stringtype=unspecified");
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StorageService storage;
  @Autowired private GenealogyService genealogy;

  private final HttpClient http = HttpClient.newHttpClient();
  private static final String ADMIN = "gedcom-admin@example.com";
  private static final Pattern LINE =
      Pattern.compile("^(0|[1-9][0-9]*) (?:(@[A-Z0-9_]+@) )?([A-Z_][A-Z0-9_]*)(?: (.*))?$");
  private static final Pattern POINTER = Pattern.compile("^@[A-Z0-9_]+@$");

  @BeforeEach
  void setUp() {
    GenealogyFixtures.signInAsAdmin();
    jdbc.update("DELETE FROM app_user_email WHERE email = ?", ADMIN);
    jdbc.update("DELETE FROM app_user WHERE display_name = 'GedcomAdmin'");
    Long id =
        jdbc.queryForObject(
            "INSERT INTO app_user (display_name, role) VALUES ('GedcomAdmin', 'admin') RETURNING id",
            Long.class);
    jdbc.update("INSERT INTO app_user_email (user_id, email) VALUES (?, ?)", id, ADMIN);
  }

  @AfterEach
  void signOut() {
    SecurityContextHolder.clearContext();
  }

  private String export(String living) throws Exception {
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/api/admin/genealogy/export.ged?living="
                            + living))
                .header("X-Auth-Email", ADMIN)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    assertThat(r.headers().firstValue("Content-Type"))
        .hasValueSatisfying(t -> assertThat(t).startsWith("text/vnd.familysearch.gedcom"));
    return r.body();
  }

  /** Line grammar, level nesting, one definition per xref, every pointer defined, dates parse. */
  private static void assertWellFormed(String ged) {
    assertThat(ged).startsWith("0 HEAD\n1 GEDC\n2 VERS 7.0\n").endsWith("0 TRLR\n");
    Set<String> defined = new HashSet<>();
    List<String> pointed = new ArrayList<>();
    int previous = -1;
    for (String line : ged.split("\n")) {
      Matcher m = LINE.matcher(line);
      assertThat(m.matches()).as(line).isTrue();
      int level = Integer.parseInt(m.group(1));
      assertThat(level).as(line).isLessThanOrEqualTo(previous + 1);
      previous = level;
      if (m.group(2) != null) {
        assertThat(level).as(line).isZero();
        assertThat(defined.add(m.group(2))).as("defined twice: " + line).isTrue();
      }
      String payload = m.group(4);
      if (payload != null && POINTER.matcher(payload).matches()) pointed.add(payload);
      if (m.group(3).equals("DATE")) GedcomDate.parse(payload);
    }
    assertThat(defined).containsAll(pointed);
  }

  private static String record(String ged, String xref) {
    int start = ged.indexOf("0 " + xref + " ");
    assertThat(start).as(xref).isNotNegative();
    int end = ged.indexOf("\n0 ", start + 1);
    return ged.substring(start, end + 1);
  }

  @Test
  void theExportIsWellFormedGedcom7() throws Exception {
    String ged = export("include");
    assertWellFormed(ged);
    assertWellFormed(export("exclude"));
  }

  @Test
  void alexanderHasHisNameEventsCitationsAndFamilies() throws Exception {
    String ged = export("include");
    String alexander = record(ged, "@I322@");
    assertThat(alexander)
        .contains(
            "1 NAME Alexander Friedrich Josef Paul Maria /Czernin/\n2 TYPE BIRTH\n"
                + "2 GIVN Alexander Friedrich Josef Paul Maria\n2 SURN Czernin\n")
        .containsPattern("1 BIRT\n2 DATE 30 APR 1913\n2 PLAC Wien\n2 SOUR @S\\d+@\n3 QUAY 1\n")
        .containsPattern("1 DEAT\n2 DATE 5 APR 2002\n2 PLAC Oxford\n")
        .contains("1 FAMC @F")
        .containsPattern("1 _UID [0-9a-f-]{36}\n");
    Matcher fams = Pattern.compile("1 FAMS (@F\\d+@)").matcher(alexander);
    assertThat(fams.find()).isTrue();
    String family = record(ged, fams.group(1));
    assertThat(family)
        .contains("1 HUSB @I322@")
        .containsPattern("1 WIFE @I\\d+@")
        .contains("1 CHIL @I323@");
    assertThat(family).containsPattern("1 MARR\n2 DATE 9 JUL 1949\n2 PLAC Haywards Heath\n");
    assertThat(record(ged, "@I323@")).contains("2 PEDI BIRTH");
    assertThat(ged).contains("1 TITL genealogy.euweb.cz Czernin pages (legacy text file)");
  }

  @Test
  void anImageCarriesItsFileAndCrop() throws Exception {
    long record = GenealogyFixtures.record(jdbc);
    long page =
        GenealogyFixtures.page(
            jdbc, storage, record, 1, GenealogyFixtures.png(40, 30), "image/png");
    Map<String, Object> media =
        genealogy.create(
            GenEntity.MEDIA,
            Map.of(
                "pageId",
                page,
                "cropLeft",
                5,
                "cropTop",
                6,
                "cropWidth",
                10,
                "cropHeight",
                8,
                "caption",
                "Wedding, 1949"),
            "photo");
    genealogy.create(
        GenEntity.MEDIA_PERSON,
        Map.of("mediaId", media.get("id"), "personId", 322, "isPortrait", true),
        "photo");
    long attachment =
        jdbc.queryForObject("SELECT attachment_id FROM page WHERE id = ?", Long.class, page);

    String ged = export("include");
    assertWellFormed(ged);
    String xref = "@O" + media.get("id") + "@";
    assertThat(record(ged, "@I322@"))
        .contains(
            "1 OBJE "
                + xref
                + "\n2 CROP\n3 TOP 6\n3 LEFT 5\n3 HEIGHT 8\n3 WIDTH 10\n2 TITL Wedding, 1949\n");
    assertThat(record(ged, xref))
        .isEqualTo(
            "0 "
                + xref
                + " OBJE\n1 FILE https://archive.czernin.eu/api/files/"
                + attachment
                + "\n2 FORM image/png\n");
  }

  @Test
  void aPersonWithNoEventsIsStillAValidRecord() throws Exception {
    int id =
        ((Number)
                genealogy
                    .createPerson(
                        Map.of("given", "Nobody", "surname", "Known"), "a name in a letter")
                    .get("id"))
            .intValue();
    String ged = export("include");
    assertWellFormed(ged);
    assertThat(record(ged, "@I" + id + "@"))
        .matches(
            "0 @I"
                + id
                + "@ INDI\n1 NAME Nobody /Known/\n2 TYPE BIRTH\n2 GIVN Nobody\n2 SURN Known\n"
                + "1 SEX U\n1 _UID [0-9a-f-]{36}\n");
  }

  @Test
  void livingPeopleAndFamiliesOfOnlyTheLivingCanBeLeftOut() throws Exception {
    int a =
        ((Number) genealogy.createPerson(Map.of("given", "Living", "living", true), "x").get("id"))
            .intValue();
    int b =
        ((Number) genealogy.createPerson(Map.of("given", "Alive", "living", true), "x").get("id"))
            .intValue();
    long couple =
        ((Number)
                genealogy
                    .create(GenEntity.COUPLE, Map.of("person1Id", a, "person2Id", b), "x")
                    .get("id"))
            .longValue();

    String included = export("include");
    assertThat(included)
        .contains("0 @I324@ INDI")
        .contains("0 @I" + a + "@ INDI")
        .contains("0 @F" + couple + "@ FAM");

    String excluded = export("exclude");
    assertWellFormed(excluded);
    assertThat(excluded)
        .doesNotContain("@I324@")
        .doesNotContain("@I" + a + "@")
        .doesNotContain("@F" + couple + "@")
        .contains("0 @I322@ INDI");
  }

  @Test
  void livingMustBeExcludeOrInclude() throws Exception {
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/api/admin/genealogy/export.ged?living=maybe"))
                .header("X-Auth-Email", ADMIN)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(400);
  }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd backend && ./gradlew test --tests '*GedcomExportTest'`

Expected: FAIL: `export.ged` answers 404

- [ ] **Step 3: Write the exporter**

Create `backend/src/main/java/place/icomb/archiver/genealogy/GedcomExporter.java`:

```java
package place.icomb.archiver.genealogy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The genealogy as a GEDCOM 7.0 file (FamilySearch GEDCOM 7.0 specification).
 *
 * <p>People are {@code @I<id>@}, couples {@code @F<id>@}, sources {@code @S<id>@}, images
 * {@code @O<id>@}. A child whose parents form no couple gets a family of its one parent,
 * {@code @F_P<id>@}. Images point at the archive's stored original and carry their crop.
 */
@Service
public class GedcomExporter {

  static final String FILE_BASE = "https://archive.czernin.eu/api/files/";

  private static final Set<String> ATTRIBUTES = Set.of("OCCU", "TITL", "EDUC", "RESI");
  private static final Map<String, String> NAME_TYPES =
      Map.of(
          "birth", "BIRTH",
          "married", "MARRIED",
          "alias", "AKA",
          "religious", "OTHER",
          "title", "OTHER",
          "other", "OTHER");
  private static final Map<String, String> PEDIGREE =
      Map.of("birth", "BIRTH", "adopted", "ADOPTED", "foster", "FOSTER");

  private final JdbcTemplate jdbc;

  public GedcomExporter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private record Family(String xref, Integer husband, Integer wife, Long coupleId) {}

  public String export(boolean includeLiving) {
    Map<Integer, Map<String, Object>> people = new LinkedHashMap<>();
    for (Map<String, Object> p :
        jdbc.queryForList(
            "SELECT id, uid::text AS uid, sex, living, notes FROM gen_person"
                + " WHERE merged_into IS NULL ORDER BY id")) {
      if (includeLiving || !Boolean.TRUE.equals(p.get("living"))) {
        people.put((Integer) p.get("id"), p);
      }
    }
    Map<Long, String> places = placeNames();
    Map<String, List<Map<String, Object>>> citations = citations();

    List<Family> families = new ArrayList<>();
    Map<Long, Family> byCouple = new HashMap<>();
    for (Map<String, Object> c :
        jdbc.queryForList("SELECT id, person1_id, person2_id FROM gen_couple ORDER BY id")) {
      Integer a = keep(people, (Integer) c.get("person1_id"));
      Integer b = keep(people, (Integer) c.get("person2_id"));
      boolean aFemale = a != null && "F".equals(people.get(a).get("sex"));
      Integer husband = aFemale ? b : a;
      Integer wife = aFemale ? a : b;
      Family f = new Family("F" + c.get("id"), husband, wife, ((Number) c.get("id")).longValue());
      byCouple.put(f.coupleId(), f);
      families.add(f);
    }
    Map<Family, List<Object[]>> children = new LinkedHashMap<>();
    Map<Integer, Family> singleParent = new HashMap<>();
    for (Map<String, Object> l :
        jdbc.queryForList(
            "SELECT parent_id, child_id, type, couple_id FROM gen_parent_child ORDER BY child_id, id")) {
      Integer child = keep(people, (Integer) l.get("child_id"));
      if (child == null) continue;
      Family f;
      if (l.get("couple_id") != null) {
        f = byCouple.get(((Number) l.get("couple_id")).longValue());
      } else {
        Integer parent = keep(people, (Integer) l.get("parent_id"));
        if (parent == null) continue;
        f =
            singleParent.computeIfAbsent(
                parent,
                p -> {
                  boolean female = "F".equals(people.get(p).get("sex"));
                  Family made = new Family("F_P" + p, female ? null : p, female ? p : null, null);
                  families.add(made);
                  return made;
                });
      }
      List<Object[]> kids = children.computeIfAbsent(f, k -> new ArrayList<>());
      if (kids.stream().noneMatch(k -> k[0].equals(child))) {
        kids.add(new Object[] {child, l.get("type")});
      }
    }
    families.removeIf(
        f ->
            f.husband() == null
                && f.wife() == null
                && children.getOrDefault(f, List.of()).isEmpty());

    Map<Integer, List<String>> famc = new HashMap<>();
    Map<Integer, List<String>> pedigree = new HashMap<>();
    Map<Integer, List<String>> fams = new HashMap<>();
    for (Family f : families) {
      for (Integer partner : new Integer[] {f.husband(), f.wife()}) {
        if (partner != null) fams.computeIfAbsent(partner, k -> new ArrayList<>()).add(f.xref());
      }
      for (Object[] kid : children.getOrDefault(f, List.of())) {
        famc.computeIfAbsent((Integer) kid[0], k -> new ArrayList<>()).add(f.xref());
        pedigree.computeIfAbsent((Integer) kid[0], k -> new ArrayList<>()).add((String) kid[1]);
      }
    }

    Gedcom g = new Gedcom();
    g.tag(0, "HEAD");
    g.tag(1, "GEDC");
    g.value(2, "VERS", "7.0");
    g.value(1, "SOUR", "CZERNIN_ARCHIVE");
    g.value(2, "NAME", "Czernin family archive");

    Map<Integer, List<Map<String, Object>>> names =
        byPerson("SELECT * FROM gen_name ORDER BY person_id, is_primary DESC, sort_order, id");
    Map<Integer, List<Map<String, Object>>> events =
        byPerson(
            "SELECT * FROM gen_event WHERE person_id IS NOT NULL ORDER BY person_id, sort_order, id");
    Map<Integer, List<Map<String, Object>>> media =
        byPerson(
            "SELECT mp.person_id, m.* FROM gen_media_person mp JOIN gen_media m ON m.id = mp.media_id"
                + " ORDER BY mp.person_id, mp.is_portrait DESC, mp.sort_order, m.id");
    Set<Long> usedSources = new HashSet<>();
    Set<Long> usedMedia = new HashSet<>();

    for (Map<String, Object> p : people.values()) {
      int id = (Integer) p.get("id");
      g.record("I" + id, "INDI");
      for (Map<String, Object> n : names.getOrDefault(id, List.of())) {
        String given = (String) n.get("given");
        String surname = (String) n.get("surname");
        String full =
            ((given == null ? "" : given) + " /" + (surname == null ? "" : surname) + "/").trim();
        g.value(1, "NAME", full);
        g.value(2, "TYPE", NAME_TYPES.get((String) n.get("type")));
        if (List.of("religious", "title").contains(n.get("type"))) {
          g.value(3, "PHRASE", (String) n.get("type"));
        }
        g.value(2, "NPFX", (String) n.get("prefix"));
        g.value(2, "GIVN", given);
        g.value(2, "NICK", (String) n.get("nickname"));
        g.value(2, "SURN", surname);
        g.value(2, "NSFX", (String) n.get("suffix"));
        cite(g, 2, citations.get("name_id:" + n.get("id")), usedSources);
      }
      g.value(1, "SEX", (String) p.get("sex"));
      for (Map<String, Object> e : events.getOrDefault(id, List.of())) {
        event(g, e, places, citations, usedSources);
      }
      List<String> parents = famc.getOrDefault(id, List.of());
      for (int i = 0; i < parents.size(); i++) {
        g.pointer(1, "FAMC", parents.get(i));
        String type = pedigree.get(id).get(i);
        g.value(2, "PEDI", PEDIGREE.getOrDefault(type, "OTHER"));
        if (!PEDIGREE.containsKey(type)) g.value(3, "PHRASE", type);
      }
      for (String f : fams.getOrDefault(id, List.of())) g.pointer(1, "FAMS", f);
      for (Map<String, Object> m : media.getOrDefault(id, List.of())) {
        long mediaId = ((Number) m.get("id")).longValue();
        usedMedia.add(mediaId);
        g.pointer(1, "OBJE", "O" + mediaId);
        if (m.get("crop_left") != null) {
          g.tag(2, "CROP");
          g.value(3, "TOP", String.valueOf(m.get("crop_top")));
          g.value(3, "LEFT", String.valueOf(m.get("crop_left")));
          g.value(3, "HEIGHT", String.valueOf(m.get("crop_height")));
          g.value(3, "WIDTH", String.valueOf(m.get("crop_width")));
        }
        g.value(2, "TITL", (String) m.get("caption"));
      }
      cite(g, 1, citations.get("person_id:" + id), usedSources);
      g.value(1, "NOTE", (String) p.get("notes"));
      g.value(1, "_UID", (String) p.get("uid"));
    }

    Map<Long, List<Map<String, Object>>> coupleEvents = new HashMap<>();
    for (Map<String, Object> e :
        jdbc.queryForList(
            "SELECT * FROM gen_event WHERE couple_id IS NOT NULL ORDER BY couple_id, sort_order, id")) {
      coupleEvents
          .computeIfAbsent(((Number) e.get("couple_id")).longValue(), k -> new ArrayList<>())
          .add(e);
    }
    for (Family f : families) {
      List<Object[]> kids = children.getOrDefault(f, List.of());
      boolean anyone = f.husband() != null || f.wife() != null || !kids.isEmpty();
      if (!anyone) continue;
      g.record(f.xref(), "FAM");
      if (f.husband() != null) g.pointer(1, "HUSB", "I" + f.husband());
      if (f.wife() != null) g.pointer(1, "WIFE", "I" + f.wife());
      for (Object[] kid : kids) g.pointer(1, "CHIL", "I" + kid[0]);
      if (f.coupleId() != null) {
        for (Map<String, Object> e : coupleEvents.getOrDefault(f.coupleId(), List.of())) {
          event(g, e, places, citations, usedSources);
        }
        cite(g, 1, citations.get("couple_id:" + f.coupleId()), usedSources);
      }
    }

    for (Map<String, Object> s : jdbc.queryForList("SELECT * FROM gen_source ORDER BY id")) {
      long id = ((Number) s.get("id")).longValue();
      if (!usedSources.contains(id)) continue;
      g.record("S" + id, "SOUR");
      g.value(1, "TITL", (String) s.get("title"));
      g.value(1, "AUTH", (String) s.get("author"));
      String note = String.join("\n", nonNull((String) s.get("url"), (String) s.get("notes")));
      g.value(1, "NOTE", note.isEmpty() ? null : note);
    }
    for (Map<String, Object> m :
        jdbc.queryForList(
            "SELECT m.id, a.id AS attachment_id, a.mime FROM gen_media m"
                + " JOIN page p ON p.id = m.page_id JOIN attachment a ON a.id = p.attachment_id"
                + " ORDER BY m.id")) {
      long id = ((Number) m.get("id")).longValue();
      if (!usedMedia.contains(id)) continue;
      g.record("O" + id, "OBJE");
      g.value(1, "FILE", FILE_BASE + m.get("attachment_id"));
      g.value(2, "FORM", m.get("mime") == null ? "image/jpeg" : (String) m.get("mime"));
    }
    g.tag(0, "TRLR");
    return g.toString();
  }

  private void event(
      Gedcom g,
      Map<String, Object> e,
      Map<Long, String> places,
      Map<String, List<Map<String, Object>>> citations,
      Set<Long> usedSources) {
    String tag = (String) e.get("type");
    String description = (String) e.get("description");
    String date = (String) e.get("date_text");
    Long place = e.get("place_id") == null ? null : ((Number) e.get("place_id")).longValue();
    if (ATTRIBUTES.contains(tag)) {
      if (description == null) {
        g.tag(1, tag);
      } else {
        g.value(1, tag, description);
      }
    } else if (tag.equals("EVEN")) {
      g.tag(1, "EVEN");
      g.value(2, "TYPE", description == null ? "event" : description);
    } else if (date == null && place == null && description == null) {
      g.value(1, tag, "Y");
    } else {
      g.tag(1, tag);
    }
    g.value(2, "DATE", date);
    g.value(2, "PLAC", place == null ? null : places.get(place));
    if (!ATTRIBUTES.contains(tag) && !tag.equals("EVEN")) g.value(2, "NOTE", description);
    cite(g, 2, citations.get("event_id:" + e.get("id")), usedSources);
  }

  private static void cite(
      Gedcom g, int level, List<Map<String, Object>> list, Set<Long> usedSources) {
    if (list == null) return;
    for (Map<String, Object> c : list) {
      long source = ((Number) c.get("source_id")).longValue();
      usedSources.add(source);
      g.pointer(level, "SOUR", "S" + source);
      String page = (String) c.get("detail");
      if (c.get("page_id") != null) {
        page = (page == null ? "" : page + "; ") + "archive page " + c.get("page_id");
      }
      g.value(level + 1, "PAGE", page);
      g.value(
          level + 1, "QUAY", c.get("quality") == null ? null : String.valueOf(c.get("quality")));
    }
  }

  private Map<Long, String> placeNames() {
    Map<Long, String> name = new HashMap<>();
    Map<Long, Long> parent = new HashMap<>();
    for (Map<String, Object> p : jdbc.queryForList("SELECT id, name, parent_id FROM gen_place")) {
      long id = ((Number) p.get("id")).longValue();
      name.put(id, (String) p.get("name"));
      if (p.get("parent_id") != null) parent.put(id, ((Number) p.get("parent_id")).longValue());
    }
    Map<Long, String> out = new HashMap<>();
    for (long id : name.keySet()) {
      List<String> parts = new ArrayList<>();
      Set<Long> seen = new HashSet<>();
      for (Long cur = id; cur != null && seen.add(cur); cur = parent.get(cur)) {
        parts.add(name.get(cur));
      }
      out.put(id, String.join(", ", parts));
    }
    return out;
  }

  private Map<String, List<Map<String, Object>>> citations() {
    Map<String, List<Map<String, Object>>> out = new HashMap<>();
    for (Map<String, Object> c : jdbc.queryForList("SELECT * FROM gen_citation ORDER BY id")) {
      for (String target : List.of("event_id", "name_id", "couple_id", "person_id")) {
        if (c.get(target) != null) {
          out.computeIfAbsent(target + ":" + c.get(target), k -> new ArrayList<>()).add(c);
        }
      }
    }
    return out;
  }

  private Map<Integer, List<Map<String, Object>>> byPerson(String sql) {
    Map<Integer, List<Map<String, Object>>> out = new HashMap<>();
    for (Map<String, Object> row : jdbc.queryForList(sql)) {
      out.computeIfAbsent((Integer) row.get("person_id"), k -> new ArrayList<>()).add(row);
    }
    return out;
  }

  private static Integer keep(Map<Integer, Map<String, Object>> people, Integer id) {
    return id != null && people.containsKey(id) ? id : null;
  }

  private static List<String> nonNull(String... values) {
    List<String> out = new ArrayList<>();
    for (String v : values) if (v != null && !v.isBlank()) out.add(v);
    return out;
  }

  /** GEDCOM 7 lines. A value line with no payload is left out; a newline continues with CONT. */
  private static final class Gedcom {
    private final StringBuilder out = new StringBuilder();

    void record(String xref, String tag) {
      out.append("0 @").append(xref).append("@ ").append(tag).append('\n');
    }

    void tag(int level, String tag) {
      emit(level, tag, null);
    }

    void pointer(int level, String tag, String xref) {
      out.append(level).append(' ').append(tag).append(" @").append(xref).append("@\n");
    }

    void value(int level, String tag, String payload) {
      if (payload == null || payload.isEmpty()) return;
      String[] parts = payload.split("\n", -1);
      emit(level, tag, parts[0]);
      for (int i = 1; i < parts.length; i++) emit(level + 1, "CONT", parts[i]);
    }

    private void emit(int level, String tag, String payload) {
      out.append(level).append(' ').append(tag);
      if (payload != null && !payload.isEmpty()) {
        out.append(' ').append(payload.startsWith("@") ? "@" + payload : payload);
      }
      out.append('\n');
    }

    @Override
    public String toString() {
      return out.toString();
    }
  }
}
```

- [ ] **Step 4: Add the endpoint**

Replace the whole of `backend/src/main/java/place/icomb/archiver/controller/AdminGenealogyController.java` with:

```java
package place.icomb.archiver.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import place.icomb.archiver.genealogy.GedcomExporter;
import place.icomb.archiver.genealogy.GenEntity;
import place.icomb.archiver.genealogy.GenealogyException;
import place.icomb.archiver.genealogy.GenealogyHistoryService;
import place.icomb.archiver.genealogy.GenealogyService;
import place.icomb.archiver.genealogy.Rows;

/**
 * Editing the genealogy. Administrators only (SecurityConfig requires ROLE_ADMIN for every method
 * under {@code /api/admin}); every write carries {@code "reason"} in its JSON body and leaves one
 * {@code gen_change} row per row it changed.
 */
@RestController
@RequestMapping("/api/admin/genealogy")
public class AdminGenealogyController {

  private static final Map<String, GenEntity> KINDS =
      Map.ofEntries(
          Map.entry("persons", GenEntity.PERSON),
          Map.entry("names", GenEntity.NAME),
          Map.entry("events", GenEntity.EVENT),
          Map.entry("couples", GenEntity.COUPLE),
          Map.entry("parent-links", GenEntity.PARENT_LINK),
          Map.entry("places", GenEntity.PLACE),
          Map.entry("place-names", GenEntity.PLACE_NAME),
          Map.entry("sources", GenEntity.SOURCE),
          Map.entry("citations", GenEntity.CITATION),
          Map.entry("record-links", GenEntity.RECORD_LINK),
          Map.entry("media", GenEntity.MEDIA),
          Map.entry("media-people", GenEntity.MEDIA_PERSON));

  private final GenealogyService genealogy;
  private final GenealogyHistoryService history;
  private final JdbcTemplate jdbc;
  private final GedcomExporter gedcom;

  public AdminGenealogyController(
      GenealogyService genealogy,
      GenealogyHistoryService history,
      JdbcTemplate jdbc,
      GedcomExporter gedcom) {
    this.genealogy = genealogy;
    this.history = history;
    this.jdbc = jdbc;
    this.gedcom = gedcom;
  }

  /** The whole tree as GEDCOM 7; living people are left out unless {@code living=include}. */
  @GetMapping("/export.ged")
  public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "exclude") String living) {
    if (!living.equals("exclude") && !living.equals("include")) {
      throw GenealogyException.badRequest("living must be exclude or include");
    }
    return ResponseEntity.ok()
        .contentType(
            org.springframework.http.MediaType.parseMediaType(
                "text/vnd.familysearch.gedcom;charset=UTF-8"))
        .header(
            org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"czernin.ged\"")
        .body(
            gedcom
                .export(living.equals("include"))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @PostMapping("/{kind}")
  public ResponseEntity<Map<String, Object>> create(
      @PathVariable String kind, @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> in = fields(body);
    String reason = reason(in);
    GenEntity entity = kind(kind);
    Map<String, Object> out;
    if (entity == GenEntity.PERSON) {
      out = genealogy.createPerson(in, reason);
    } else if (entity == GenEntity.MEDIA && in.containsKey("personId")) {
      Object person = in.remove("personId");
      Object portrait = in.remove("isPortrait");
      if (!(person instanceof Number n) || (portrait != null && !(portrait instanceof Boolean))) {
        throw GenealogyException.badRequest(
            "personId must be a number and isPortrait true or false");
      }
      out = genealogy.createMedia(in, n.intValue(), Boolean.TRUE.equals(portrait), reason);
    } else {
      out = genealogy.create(entity, in, reason);
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(out);
  }

  @PostMapping("/persons/{personId}/names")
  public ResponseEntity<Map<String, Object>> addName(
      @PathVariable int personId, @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> in = fields(body);
    String reason = reason(in);
    in.put("personId", personId);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(genealogy.create(GenEntity.NAME, in, reason));
  }

  @PutMapping("/{kind}/{id}")
  public Map<String, Object> update(
      @PathVariable String kind,
      @PathVariable long id,
      @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> in = fields(body);
    String reason = reason(in);
    return genealogy.update(kind(kind), id, in, reason);
  }

  @DeleteMapping("/{kind}/{id}")
  public Map<String, Object> delete(
      @PathVariable String kind,
      @PathVariable long id,
      @RequestBody(required = false) Map<String, Object> body) {
    Map<String, Object> in = fields(body);
    return genealogy.delete(kind(kind), id, reason(in));
  }

  @PostMapping("/persons/{keepId}/merge/{goneId}")
  public Map<String, Object> merge(
      @PathVariable int keepId,
      @PathVariable int goneId,
      @RequestBody(required = false) Map<String, Object> body) {
    return history.mergePersons(keepId, goneId, reason(fields(body)));
  }

  @GetMapping("/changes")
  public List<Map<String, Object>> changes(
      @RequestParam(required = false) String entity,
      @RequestParam(required = false) Long entityId,
      @RequestParam(required = false) Integer personId,
      @RequestParam(defaultValue = "100") int limit) {
    if (personId != null) {
      return genealogy.personChanges(personId, limit);
    }
    return genealogy.changes(entity, entityId, limit);
  }

  @PostMapping("/changes/{changeId}/undo")
  public Map<String, Object> undo(
      @PathVariable long changeId, @RequestBody(required = false) Map<String, Object> body) {
    return history.undo(changeId, reason(fields(body)));
  }

  @GetMapping("/review")
  public List<Map<String, Object>> review() {
    return genealogy.review();
  }

  /** Places by name, for the place picker. */
  @GetMapping("/places")
  public List<Map<String, Object>> places(
      @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "50") int limit) {
    return Rows.camel(
        jdbc.queryForList(
            "SELECT id, name, parent_id, lat, lon FROM gen_place"
                + " WHERE name ILIKE '%' || ? || '%' ORDER BY name, id LIMIT ?",
            q, Math.max(1, Math.min(limit, 1000))));
  }

  /** Sources by title, for the citation picker. */
  @GetMapping("/sources")
  public List<Map<String, Object>> sources(
      @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "50") int limit) {
    return Rows.camel(
        jdbc.queryForList(
            "SELECT id, title, author, record_id, url FROM gen_source"
                + " WHERE title ILIKE '%' || ? || '%' ORDER BY title, id LIMIT ?",
            q, Math.max(1, Math.min(limit, 1000))));
  }

  @ExceptionHandler(GenealogyException.class)
  public ResponseEntity<Map<String, Object>> refuse(GenealogyException e) {
    HttpStatus status =
        switch (e.kind()) {
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
          case CONFLICT -> HttpStatus.CONFLICT;
        };
    return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
  }

  private static GenEntity kind(String kind) {
    GenEntity entity = KINDS.get(kind);
    if (entity == null) {
      throw GenealogyException.notFound("no genealogy collection '%s'".formatted(kind));
    }
    return entity;
  }

  private static Map<String, Object> fields(Map<String, Object> body) {
    return body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
  }

  /** Takes {@code reason} out of the body, so the rest is only the entity's fields. */
  private static String reason(Map<String, Object> in) {
    Object reason = in.remove("reason");
    if (reason != null && !(reason instanceof String)) {
      throw GenealogyException.badRequest("reason must be a string");
    }
    GenealogyService.requireReason((String) reason);
    return (String) reason;
  }
}
```

- [ ] **Step 5: Run it to see it pass**

Run: `cd backend && ./gradlew test --tests '*GedcomExportTest' --tests '*AdminGenealogyControllerTest'`

Expected: PASS (6 + 7 tests)

- [ ] **Step 6: Format**

Run: `cd backend && ./gradlew spotlessApply`

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A backend/src/main/java/place/icomb/archiver/genealogy/GedcomExporter.java backend/src/main/java/place/icomb/archiver/controller/AdminGenealogyController.java backend/src/test/java/place/icomb/archiver/controller/GedcomExportTest.java
git commit -m "feat: GEDCOM 7 export of the family tree"
```

---

### Task 13: Person page: the stored facts

**Files:**
- Create: `frontend/src/lib/genealogy.ts`
- Create: `frontend/src/lib/components/genealogy/PersonFacts.svelte`
- Modify: `frontend/src/routes/family-tree/+page.svelte:4-5` (imports), `:34-48` (event colours), `:101-130` (timeline, replaced), `:413-483` (timeline CSS, removed; this also removes the page's one `style:` directive)
- Modify: `frontend/src/lib/messages/en.ts:113`, `de.ts:115`, `cs.ts:115` (new keys after `family.thisIsYou`)
- Test: `frontend/tests/genealogy.test.ts`, `frontend/tests/PersonFacts.test.ts`

**Interfaces:**
- Consumes: `GET /api/family-tree/person/{id}` with its stored-fact keys (Task 6), the media `imageUrl` (Task 10), and `$lib/i18n` `t`.
- Produces (used by Task 14):
  - `$lib/genealogy`:
    - Tuples: `EVENT_TAGS`, `NAME_TYPES`, `LINK_TYPES`, `COUPLE_TYPES`, `ROLES`.
    - Label maps: `EVENT_LABELS`, `NAME_TYPE_LABELS`, `LINK_TYPE_LABELS`, `COUPLE_TYPE_LABELS`, `ROLE_LABELS`, `QUALITY_LABELS`.
    - Functions: `eventLabel(tag)`, `qualityLabel(q)`, `fullName(name)`.
    - Interfaces: `Citation`, `TreeEvent`, `PersonName`, `Couple`, `ParentLink`, `RecordLink`, `MediaItem`, `Source`, `PersonDetail`.
  - `<PersonFacts person={PersonDetail} onNavigate={(id: number) => void} />`.

- [ ] **Step 1: Write the failing tests**

Create `frontend/tests/genealogy.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { EVENT_TAGS, eventLabel, qualityLabel } from '../src/lib/genealogy';
import { en } from '../src/lib/messages/en';
import { de } from '../src/lib/messages/de';
import { cs } from '../src/lib/messages/cs';

describe('labels', () => {
	it('names every GEDCOM event and quality in all three languages', () => {
		for (const tag of EVENT_TAGS) {
			for (const messages of [en, de, cs]) {
				expect(messages[eventLabel(tag)]).toBeTruthy();
			}
		}
		for (const q of [0, 1, 2, 3, null]) {
			expect(en[qualityLabel(q)]).toBeTruthy();
		}
	});
});
```

Create `frontend/tests/PersonFacts.test.ts`:

```ts
// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import PersonFacts from '../src/lib/components/genealogy/PersonFacts.svelte';
import type { PersonDetail } from '../src/lib/genealogy';

const alexander: PersonDetail = {
	id: 322,
	name: 'Alexander Friedrich Josef Paul Maria Czernin',
	sex: 'U',
	living: false,
	notes: null,
	needsReview: null,
	names: [
		{ id: 1, type: 'birth', given: 'Alexander Friedrich Josef Paul Maria', surname: 'Czernin', prefix: null, suffix: null, nickname: null, isPrimary: true, sortOrder: 0 },
		{ id: 2, type: 'alias', given: 'Lucki', surname: 'Czernin', prefix: null, suffix: null, nickname: null, isPrimary: false, sortOrder: 1 }
	],
	events: [
		{
			id: 10, type: 'birth', tag: 'BIRT', date: '30 APR 1913', dateFrom: '1913-04-30', dateTo: '1913-04-30',
			place: 'Wien', placeId: 3, description: null, coupleId: null, spouseId: null, text: '', year: 1913,
			citations: [{ id: 5, sourceId: 1, sourceTitle: 'Legacy file', pageId: null, detail: null, quality: 1 }]
		},
		{
			id: 11, type: 'marriage', tag: 'MARR', date: '9 JUL 1949', dateFrom: null, dateTo: null,
			place: 'Haywards Heath', placeId: 4, description: null, coupleId: 7, spouseId: 400, text: '', year: 1949,
			citations: []
		}
	],
	couples: [{ id: 7, type: 'marriage', sortOrder: 1, notes: null, spouseId: 400, spouseName: 'Diana Zannick Hutton/Hulton' }],
	parentLinks: [],
	recordLinks: [
		{ id: 3, recordId: 4082, pageId: 9, pageSeq: 1, role: 'subject', note: null, quality: 3, title: 'Heiratsurkunde', titleEn: 'Marriage certificate', referenceCode: null }
	],
	media: [
		{ id: 8, pageId: 9, recordId: 4082, pageSeq: 1, cropLeft: 1, cropTop: 2, cropWidth: 3, cropHeight: 4, caption: 'Wedding', dateText: null, isPortrait: true, imageUrl: '/api/family-tree/media/8/image' }
	],
	portrait: { id: 8, pageId: 9, recordId: 4082, pageSeq: 1, cropLeft: 1, cropTop: 2, cropWidth: 3, cropHeight: 4, caption: 'Wedding', dateText: null, isPortrait: true, imageUrl: '/api/family-tree/media/8/image' },
	sources: [{ id: 1, title: 'Legacy file', author: null, recordId: null, url: null }]
};

describe('PersonFacts', () => {
	afterEach(() => cleanup());

	it('shows the portrait, other names, cited events, spouse and linked document', async () => {
		const onNavigate = vi.fn();
		render(PersonFacts, { person: alexander, onNavigate });

		expect(screen.getByRole('img', { name: 'Wedding' }).getAttribute('src')).toBe(
			'/api/family-tree/media/8/image'
		);
		expect(screen.getByText(/Lucki/)).toBeTruthy();
		expect(screen.getByText('30 APR 1913')).toBeTruthy();
		expect(screen.getByText(/Legacy file · questionable/)).toBeTruthy();
		expect(screen.getByRole('link', { name: 'Marriage certificate' }).getAttribute('href')).toBe(
			'/records/4082/pages/1'
		);
		await fireEvent.click(screen.getAllByRole('button', { name: 'Diana Zannick Hutton/Hulton' })[0]);
		expect(onNavigate).toHaveBeenCalledWith(400);
	});

	it('says so when nothing is recorded', () => {
		render(PersonFacts, {
			person: { ...alexander, names: [alexander.names[0]], events: [], couples: [], recordLinks: [], media: [], portrait: null, sources: [] },
			onNavigate: vi.fn()
		});
		expect(screen.getByText('No events recorded')).toBeTruthy();
		expect(screen.queryByRole('img')).toBeNull();
	});
});
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd frontend && bun run test`

Expected: FAIL: `Failed to resolve import "../src/lib/genealogy"` and `../src/lib/components/genealogy/PersonFacts.svelte`

- [ ] **Step 3: Add the types, labels and the facts component**

Create `frontend/src/lib/genealogy.ts`:

```ts
import type { MessageKey } from '$lib/i18n';

export const EVENT_TAGS = [
	'BIRT',
	'CHR',
	'BAPM',
	'DEAT',
	'BURI',
	'MARR',
	'DIV',
	'ADOP',
	'OCCU',
	'TITL',
	'RESI',
	'NATU',
	'EMIG',
	'IMMI',
	'EDUC',
	'EVEN'
] as const;
export type EventTag = (typeof EVENT_TAGS)[number];

export const NAME_TYPES = ['birth', 'married', 'alias', 'religious', 'title', 'other'] as const;
export const LINK_TYPES = ['birth', 'adopted', 'step', 'foster', 'unknown'] as const;
export const COUPLE_TYPES = ['marriage', 'partnership', 'unknown'] as const;
export const ROLES = [
	'subject',
	'mentioned',
	'author',
	'signatory',
	'witness',
	'recipient',
	'pictured',
	'other'
] as const;

export const EVENT_LABELS: Record<EventTag, MessageKey> = {
	BIRT: 'family.event.BIRT',
	CHR: 'family.event.CHR',
	BAPM: 'family.event.BAPM',
	DEAT: 'family.event.DEAT',
	BURI: 'family.event.BURI',
	MARR: 'family.event.MARR',
	DIV: 'family.event.DIV',
	ADOP: 'family.event.ADOP',
	OCCU: 'family.event.OCCU',
	TITL: 'family.event.TITL',
	RESI: 'family.event.RESI',
	NATU: 'family.event.NATU',
	EMIG: 'family.event.EMIG',
	IMMI: 'family.event.IMMI',
	EDUC: 'family.event.EDUC',
	EVEN: 'family.event.EVEN'
};

export const NAME_TYPE_LABELS: Record<(typeof NAME_TYPES)[number], MessageKey> = {
	birth: 'family.nameType.birth',
	married: 'family.nameType.married',
	alias: 'family.nameType.alias',
	religious: 'family.nameType.religious',
	title: 'family.nameType.title',
	other: 'family.nameType.other'
};

export const LINK_TYPE_LABELS: Record<(typeof LINK_TYPES)[number], MessageKey> = {
	birth: 'family.linkType.birth',
	adopted: 'family.linkType.adopted',
	step: 'family.linkType.step',
	foster: 'family.linkType.foster',
	unknown: 'family.linkType.unknown'
};

export const COUPLE_TYPE_LABELS: Record<(typeof COUPLE_TYPES)[number], MessageKey> = {
	marriage: 'family.coupleType.marriage',
	partnership: 'family.coupleType.partnership',
	unknown: 'family.coupleType.unknown'
};

export const ROLE_LABELS: Record<(typeof ROLES)[number], MessageKey> = {
	subject: 'family.role.subject',
	mentioned: 'family.role.mentioned',
	author: 'family.role.author',
	signatory: 'family.role.signatory',
	witness: 'family.role.witness',
	recipient: 'family.role.recipient',
	pictured: 'family.role.pictured',
	other: 'family.role.other'
};

/** GEDCOM QUAY 0-3; null when the citation does not say. */
export const QUALITY_LABELS: MessageKey[] = [
	'family.quality.0',
	'family.quality.1',
	'family.quality.2',
	'family.quality.3'
];

export function eventLabel(tag: string): MessageKey {
	return EVENT_LABELS[tag as EventTag] ?? 'family.event.EVEN';
}

export function qualityLabel(quality: number | null): MessageKey {
	return quality == null ? 'family.quality.none' : (QUALITY_LABELS[quality] ?? 'family.quality.none');
}

export interface Citation {
	id: number;
	sourceId: number;
	sourceTitle: string;
	pageId: number | null;
	detail: string | null;
	quality: number | null;
}

export interface TreeEvent {
	id: number;
	type: string;
	tag: string;
	date: string | null;
	dateFrom: string | null;
	dateTo: string | null;
	place: string | null;
	placeId: number | null;
	description: string | null;
	coupleId: number | null;
	spouseId: number | null;
	text: string;
	year: number | null;
	citations: Citation[];
}

export interface PersonName {
	id: number;
	type: string;
	given: string | null;
	surname: string | null;
	prefix: string | null;
	suffix: string | null;
	nickname: string | null;
	isPrimary: boolean;
	sortOrder: number;
}

export interface Couple {
	id: number;
	type: string;
	sortOrder: number;
	notes: string | null;
	spouseId: number;
	spouseName: string;
}

export interface ParentLink {
	id: number;
	type: string;
	coupleId: number | null;
	parentId: number;
	childId: number;
	relation: 'parent' | 'child';
	otherName: string;
}

export interface RecordLink {
	id: number;
	recordId: number;
	pageId: number | null;
	pageSeq: number | null;
	role: string;
	note: string | null;
	quality: number | null;
	title: string | null;
	titleEn: string | null;
	referenceCode: string | null;
}

export interface MediaItem {
	id: number;
	pageId: number;
	recordId: number;
	pageSeq: number;
	cropLeft: number | null;
	cropTop: number | null;
	cropWidth: number | null;
	cropHeight: number | null;
	caption: string | null;
	dateText: string | null;
	isPortrait: boolean;
	imageUrl: string;
}

export interface Source {
	id: number;
	title: string;
	author: string | null;
	recordId: number | null;
	url: string | null;
}

export interface PersonDetail {
	id: number;
	name: string;
	sex: string;
	living: boolean;
	notes: string | null;
	needsReview: string | null;
	names: PersonName[];
	events: TreeEvent[];
	couples: Couple[];
	parentLinks: ParentLink[];
	recordLinks: RecordLink[];
	media: MediaItem[];
	portrait: MediaItem | null;
	sources: Source[];
}

export function fullName(n: Pick<PersonName, 'prefix' | 'given' | 'surname' | 'suffix'>): string {
	return [n.prefix, n.given, n.surname, n.suffix].filter((p) => p && p.trim()).join(' ');
}
```

Create `frontend/src/lib/components/genealogy/PersonFacts.svelte`:

```svelte
<script lang="ts">
	import { t } from '$lib/i18n';
	import {
		COUPLE_TYPE_LABELS,
		LINK_TYPE_LABELS,
		NAME_TYPE_LABELS,
		ROLE_LABELS,
		eventLabel,
		fullName,
		qualityLabel,
		type PersonDetail
	} from '$lib/genealogy';

	let { person, onNavigate }: { person: PersonDetail; onNavigate: (id: number) => void } =
		$props();

	const otherNames = $derived(person.names.filter((n) => !n.isPrimary));
	const parents = $derived(
		person.parentLinks.filter((l) => l.relation === 'parent' && l.type !== 'birth')
	);
	const gallery = $derived(person.media.filter((m) => !m.isPortrait));
</script>

<div class="facts">
	{#if person.portrait}
		<figure class="portrait">
			<img src={person.portrait.imageUrl} alt={person.portrait.caption ?? person.name} />
			{#if person.portrait.caption}<figcaption>{person.portrait.caption}</figcaption>{/if}
		</figure>
	{/if}

	{#if otherNames.length > 0}
		<section>
			<h3 class="vui-section-header">{$t('family.otherNames')}</h3>
			<ul class="plain">
				{#each otherNames as n (n.id)}
					<li>
						{fullName(n)}
						{#if n.nickname}<span class="muted">“{n.nickname}”</span>{/if}
						<span class="vui-badge">{$t(NAME_TYPE_LABELS[n.type as keyof typeof NAME_TYPE_LABELS])}</span>
					</li>
				{/each}
			</ul>
		</section>
	{/if}

	<section>
		<h3 class="vui-section-header">{$t('family.events')}</h3>
		{#if person.events.length === 0}
			<p class="muted">{$t('family.noEvents')}</p>
		{:else}
			<ol class="plain events">
				{#each person.events as ev (ev.id)}
					<li class="event">
						<span class="event-tag">{$t(eventLabel(ev.tag))}</span>
						{#if ev.date}<span class="event-date">{ev.date}</span>{/if}
						{#if ev.place}<span>{ev.place}</span>{/if}
						{#if ev.description}<span class="muted">{ev.description}</span>{/if}
						{#if ev.spouseId}
							<button class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => onNavigate(ev.spouseId!)}>
								{person.couples.find((c) => c.spouseId === ev.spouseId)?.spouseName}
							</button>
						{/if}
						{#each ev.citations as c (c.id)}
							<span class="vui-badge vui-badge-info">{c.sourceTitle}{c.detail ? ` – ${c.detail}` : ''} · {$t(qualityLabel(c.quality))}</span>
						{/each}
					</li>
				{/each}
			</ol>
		{/if}
	</section>

	{#if person.couples.length > 0}
		<section>
			<h3 class="vui-section-header">{$t('family.spouses')}</h3>
			<ul class="plain">
				{#each person.couples as c (c.id)}
					<li>
						<button class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => onNavigate(c.spouseId)}>{c.spouseName}</button>
						<span class="vui-badge">{$t(COUPLE_TYPE_LABELS[c.type as keyof typeof COUPLE_TYPE_LABELS])}</span>
					</li>
				{/each}
			</ul>
		</section>
	{/if}

	{#if parents.length > 0}
		<section>
			<h3 class="vui-section-header">{$t('family.otherParents')}</h3>
			<ul class="plain">
				{#each parents as l (l.id)}
					<li>
						<button class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => onNavigate(l.parentId)}>{l.otherName}</button>
						<span class="vui-badge">{$t(LINK_TYPE_LABELS[l.type as keyof typeof LINK_TYPE_LABELS])}</span>
					</li>
				{/each}
			</ul>
		</section>
	{/if}

	{#if person.recordLinks.length > 0}
		<section>
			<h3 class="vui-section-header">{$t('family.records')}</h3>
			<ul class="plain">
				{#each person.recordLinks as r (r.id)}
					<li>
						<a href={r.pageSeq ? `/records/${r.recordId}/pages/${r.pageSeq}` : `/records/${r.recordId}`}>
							{r.titleEn ?? r.title ?? r.referenceCode ?? `#${r.recordId}`}
						</a>
						<span class="vui-badge">{$t(ROLE_LABELS[r.role as keyof typeof ROLE_LABELS])}</span>
						{#if r.note}<span class="muted">{r.note}</span>{/if}
					</li>
				{/each}
			</ul>
		</section>
	{/if}

	{#if gallery.length > 0}
		<section>
			<h3 class="vui-section-header">{$t('family.media')}</h3>
			<div class="gallery">
				{#each gallery as m (m.id)}
					<figure>
						<img src={m.imageUrl} alt={m.caption ?? ''} loading="lazy" />
						{#if m.caption}<figcaption>{m.caption}</figcaption>{/if}
					</figure>
				{/each}
			</div>
		</section>
	{/if}

	{#if person.sources.length > 0}
		<section>
			<h3 class="vui-section-header">{$t('family.sources')}</h3>
			<ul class="plain">
				{#each person.sources as s (s.id)}
					<li>
						{#if s.recordId}<a href={`/records/${s.recordId}`}>{s.title}</a>{:else}{s.title}{/if}
						{#if s.author}<span class="muted">{s.author}</span>{/if}
					</li>
				{/each}
			</ul>
		</section>
	{/if}
</div>

<style>
	.facts {
		display: flex;
		flex-direction: column;
		gap: 16px;
		margin-bottom: 16px;
	}
	.portrait {
		margin: 0;
		max-width: 220px;
	}
	.portrait img,
	.gallery img {
		max-width: 100%;
		border-radius: var(--vui-radius-md);
		border: 1px solid var(--vui-border);
	}
	figcaption {
		font-size: 12px;
		color: var(--vui-text-muted);
	}
	.plain {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.event {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: 8px;
	}
	.event-tag {
		font-size: 11px;
		font-weight: 700;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--vui-accent);
		min-width: 90px;
	}
	.event-date {
		font-weight: 600;
	}
	.muted {
		color: var(--vui-text-muted);
	}
	.gallery {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
		gap: 8px;
	}
	.gallery figure {
		margin: 0;
	}
</style>
```

- [ ] **Step 4: Add the i18n keys in all three languages**

In `frontend/src/lib/messages/en.ts`, replace:

```ts
	'family.thisIsYou': 'This is you',
```

with:

```ts
	'family.thisIsYou': 'This is you',
	'family.otherNames': 'Other names',
	'family.events': 'Life events',
	'family.noEvents': 'No events recorded',
	'family.spouses': 'Spouses',
	'family.otherParents': 'Adoptive and other parents',
	'family.records': 'Documents',
	'family.media': 'Images',
	'family.sources': 'Sources',
	'family.nameType.birth': 'birth name',
	'family.nameType.married': 'married name',
	'family.nameType.alias': 'also known as',
	'family.nameType.religious': 'religious name',
	'family.nameType.title': 'title',
	'family.nameType.other': 'other',
	'family.event.BIRT': 'Birth',
	'family.event.CHR': 'Christening',
	'family.event.BAPM': 'Baptism',
	'family.event.DEAT': 'Death',
	'family.event.BURI': 'Burial',
	'family.event.MARR': 'Marriage',
	'family.event.DIV': 'Divorce',
	'family.event.ADOP': 'Adoption',
	'family.event.OCCU': 'Occupation',
	'family.event.TITL': 'Title',
	'family.event.RESI': 'Residence',
	'family.event.NATU': 'Naturalisation',
	'family.event.EMIG': 'Emigration',
	'family.event.IMMI': 'Immigration',
	'family.event.EDUC': 'Education',
	'family.event.EVEN': 'Event',
	'family.quality.0': 'unreliable',
	'family.quality.1': 'questionable',
	'family.quality.2': 'secondary evidence',
	'family.quality.3': 'primary evidence',
	'family.quality.none': 'quality not assessed',
	'family.coupleType.marriage': 'marriage',
	'family.coupleType.partnership': 'partnership',
	'family.coupleType.unknown': 'unknown',
	'family.linkType.birth': 'birth',
	'family.linkType.adopted': 'adopted',
	'family.linkType.step': 'step',
	'family.linkType.foster': 'foster',
	'family.linkType.unknown': 'unknown',
	'family.role.subject': 'subject',
	'family.role.mentioned': 'mentioned',
	'family.role.author': 'author',
	'family.role.signatory': 'signatory',
	'family.role.witness': 'witness',
	'family.role.recipient': 'recipient',
	'family.role.pictured': 'pictured',
	'family.role.other': 'other',
```

In `frontend/src/lib/messages/de.ts`, replace:

```ts
	'family.thisIsYou': 'Das bist du',
```

with:

```ts
	'family.thisIsYou': 'Das bist du',
	'family.otherNames': 'Weitere Namen',
	'family.events': 'Lebensereignisse',
	'family.noEvents': 'Keine Ereignisse erfasst',
	'family.spouses': 'Ehepartner',
	'family.otherParents': 'Adoptiv- und andere Eltern',
	'family.records': 'Dokumente',
	'family.media': 'Bilder',
	'family.sources': 'Quellen',
	'family.nameType.birth': 'Geburtsname',
	'family.nameType.married': 'Ehename',
	'family.nameType.alias': 'auch bekannt als',
	'family.nameType.religious': 'Ordensname',
	'family.nameType.title': 'Titel',
	'family.nameType.other': 'andere',
	'family.event.BIRT': 'Geburt',
	'family.event.CHR': 'Taufe',
	'family.event.BAPM': 'Taufe (Erwachsene)',
	'family.event.DEAT': 'Tod',
	'family.event.BURI': 'Begräbnis',
	'family.event.MARR': 'Heirat',
	'family.event.DIV': 'Scheidung',
	'family.event.ADOP': 'Adoption',
	'family.event.OCCU': 'Beruf',
	'family.event.TITL': 'Titel',
	'family.event.RESI': 'Wohnort',
	'family.event.NATU': 'Einbürgerung',
	'family.event.EMIG': 'Auswanderung',
	'family.event.IMMI': 'Einwanderung',
	'family.event.EDUC': 'Ausbildung',
	'family.event.EVEN': 'Ereignis',
	'family.quality.0': 'unzuverlässig',
	'family.quality.1': 'fraglich',
	'family.quality.2': 'Sekundärquelle',
	'family.quality.3': 'Primärquelle',
	'family.quality.none': 'Qualität nicht bewertet',
	'family.coupleType.marriage': 'Ehe',
	'family.coupleType.partnership': 'Partnerschaft',
	'family.coupleType.unknown': 'unbekannt',
	'family.linkType.birth': 'leiblich',
	'family.linkType.adopted': 'adoptiert',
	'family.linkType.step': 'Stief-',
	'family.linkType.foster': 'Pflege-',
	'family.linkType.unknown': 'unbekannt',
	'family.role.subject': 'Hauptperson',
	'family.role.mentioned': 'erwähnt',
	'family.role.author': 'Verfasser',
	'family.role.signatory': 'Unterzeichner',
	'family.role.witness': 'Zeuge',
	'family.role.recipient': 'Empfänger',
	'family.role.pictured': 'abgebildet',
	'family.role.other': 'andere',
```

In `frontend/src/lib/messages/cs.ts`, replace:

```ts
	'family.thisIsYou': 'To jste vy',
```

with:

```ts
	'family.thisIsYou': 'To jste vy',
	'family.otherNames': 'Další jména',
	'family.events': 'Životní události',
	'family.noEvents': 'Žádné zaznamenané události',
	'family.spouses': 'Manželé',
	'family.otherParents': 'Adoptivní a jiní rodiče',
	'family.records': 'Dokumenty',
	'family.media': 'Obrázky',
	'family.sources': 'Prameny',
	'family.nameType.birth': 'rodné jméno',
	'family.nameType.married': 'jméno po sňatku',
	'family.nameType.alias': 'také známý jako',
	'family.nameType.religious': 'řeholní jméno',
	'family.nameType.title': 'titul',
	'family.nameType.other': 'jiné',
	'family.event.BIRT': 'Narození',
	'family.event.CHR': 'Křest',
	'family.event.BAPM': 'Křest (dospělý)',
	'family.event.DEAT': 'Úmrtí',
	'family.event.BURI': 'Pohřeb',
	'family.event.MARR': 'Sňatek',
	'family.event.DIV': 'Rozvod',
	'family.event.ADOP': 'Adopce',
	'family.event.OCCU': 'Povolání',
	'family.event.TITL': 'Titul',
	'family.event.RESI': 'Bydliště',
	'family.event.NATU': 'Naturalizace',
	'family.event.EMIG': 'Emigrace',
	'family.event.IMMI': 'Imigrace',
	'family.event.EDUC': 'Vzdělání',
	'family.event.EVEN': 'Událost',
	'family.quality.0': 'nespolehlivé',
	'family.quality.1': 'sporné',
	'family.quality.2': 'druhotný doklad',
	'family.quality.3': 'prvotní doklad',
	'family.quality.none': 'kvalita neurčena',
	'family.coupleType.marriage': 'manželství',
	'family.coupleType.partnership': 'partnerství',
	'family.coupleType.unknown': 'neznámé',
	'family.linkType.birth': 'vlastní',
	'family.linkType.adopted': 'adoptivní',
	'family.linkType.step': 'nevlastní',
	'family.linkType.foster': 'pěstounské',
	'family.linkType.unknown': 'neznámé',
	'family.role.subject': 'hlavní osoba',
	'family.role.mentioned': 'zmíněn',
	'family.role.author': 'autor',
	'family.role.signatory': 'signatář',
	'family.role.witness': 'svědek',
	'family.role.recipient': 'adresát',
	'family.role.pictured': 'zobrazen',
	'family.role.other': 'jiné',
```

- [ ] **Step 5: Show the facts on the person page**

The facts list replaces the timeline, which read the old event shape and coloured its dots through a `style:` directive. Replace the imports:

In `frontend/src/routes/family-tree/+page.svelte`, replace:

```svelte
	import { Search, ChevronRight, Baby, X, HandHeart, UserCheck } from 'lucide-svelte';
	import { language, t } from '$lib/i18n';
```

with:

```svelte
	import { Search, ChevronRight, UserCheck } from 'lucide-svelte';
	import { t } from '$lib/i18n';
	import PersonFacts from '$lib/components/genealogy/PersonFacts.svelte';
```

Delete the event colours and icons (`eventColor`, `eventIconComponent`):

In `frontend/src/routes/family-tree/+page.svelte`, replace:

```svelte
	let settingMe = $state(false);

	const eventColor: Record<string, string> = {
		birth: 'var(--vui-accent)',
		death: '#f87171',
		marriage: '#a78bfa',
		'marriage & divorce': '#fbbf24'
	};

	const eventIconComponent: Record<string, typeof Baby> = {
		birth: Baby,
		death: X,
		marriage: HandHeart,
		'marriage & divorce': HandHeart
	};
```

with:

```svelte
	let settingMe = $state(false);
```

Replace the timeline block:

In `frontend/src/routes/family-tree/+page.svelte`, replace:

```svelte
				<!-- Timeline -->
				{#if p.events && p.events.length > 0}
					<div class="timeline">
						{#each p.events as ev, i}
							<div class="tl-row" style:--event-color={eventColor[ev.type] ?? 'var(--vui-text-muted)'}>
								<div class="tl-dot-col">
									<span class="tl-dot">
										{#if eventIconComponent[ev.type]}
											{@const EvIcon = eventIconComponent[ev.type]}
											<EvIcon size={14} strokeWidth={2.5} />
										{/if}
									</span>
									{#if i < p.events.length - 1}
										<span class="tl-line"></span>
									{/if}
								</div>
								<div class="tl-content">
									<span class="tl-type">
										{ev.type === 'marriage & divorce' ? 'Marriage (divorced)' : ev.type.charAt(0).toUpperCase() + ev.type.slice(1)}
									</span>
									{#if ev.year}
										<span class="tl-year">{ev.year}</span>
									{/if}
									<div class="tl-text">{ev.text}</div>
								</div>
							</div>
						{/each}
					</div>
				{/if}
```

with:

```svelte
				<PersonFacts person={p} onNavigate={navigatePerson} />
```

Delete the timeline CSS:

In `frontend/src/routes/family-tree/+page.svelte`, delete this text:

```css
	/* ── Timeline ── */
	.timeline {
		display: flex;
		flex-direction: column;
		margin-bottom: 16px;
	}

	.tl-row {
		display: flex;
		gap: 14px;
		min-height: 48px;
	}

	.tl-dot-col {
		display: flex;
		flex-direction: column;
		align-items: center;
		width: 28px;
		flex-shrink: 0;
	}

	.tl-dot {
		background: var(--event-color, var(--vui-text-muted));
		display: flex;
		align-items: center;
		justify-content: center;
		width: 28px;
		height: 28px;
		border-radius: 50%;
		font-size: 14px;
		font-weight: 700;
		color: var(--vui-bg-deep);
		flex-shrink: 0;
	}

	.tl-line {
		flex: 1;
		width: 2px;
		background: color-mix(in srgb, var(--vui-border) 80%, transparent);
		min-height: 12px;
	}

	.tl-content {
		padding-bottom: 14px;
		flex: 1;
		min-width: 0;
	}

	.tl-type {
		color: var(--event-color, var(--vui-text-muted));
		font-size: 11px;
		font-weight: 700;
		text-transform: uppercase;
		letter-spacing: 0.05em;
	}

	.tl-year {
		font-size: 11px;
		font-weight: 600;
		color: var(--vui-text);
		margin-left: 8px;
	}

	.tl-text {
		font-size: 14px;
		color: var(--vui-text);
		margin-top: 2px;
		line-height: 1.5;
		word-break: break-word;
	}
```

- [ ] **Step 6: Run the tests, the type check and the i18n check**

Run: `cd frontend && bun run test`

Expected: PASS (all files, including `genealogy.test.ts` 1 test and `PersonFacts.test.ts` 2 tests)

Run: `cd frontend && bun run check`

Expected: `0 ERRORS` (the 3 existing warnings in `translate/+page.svelte` remain)

Run: `make test-frontend`

Expected: vitest PASS, then the i18n completeness check exits 0

- [ ] **Step 7: Commit**

```bash
git add -A frontend/src/lib/genealogy.ts frontend/src/lib/components/genealogy/PersonFacts.svelte frontend/src/routes/family-tree/+page.svelte frontend/src/lib/messages frontend/tests/genealogy.test.ts frontend/tests/PersonFacts.test.ts
git commit -m "feat: person page shows names, cited events, spouses, documents, images and sources"
```

---

### Task 14: Admin edit mode, cropper, history and review list

**Files:**
- Modify (replace): `frontend/src/lib/genealogy.ts` (adds `SEXES`, `SEX_LABELS`, `Change`, `changeSummary`, `Crop`, `cropFromPoints`, `formToBody`)
- Create: `frontend/src/lib/components/genealogy/{PersonEditor,PersonPicker,RecordPagePicker,CropSelector,ChangeHistory}.svelte`
- Modify: `frontend/src/lib/server/api.ts:483` (admin helpers, before `PagePersonMatch`)
- Modify (replace): `frontend/src/routes/family-tree/+page.server.ts` (edit-mode load, `edit` and `merge` actions)
- Modify: `frontend/src/routes/family-tree/+page.svelte` (admin bar, editor and history)
- Create: `frontend/src/routes/family-tree/review/+page.server.ts`, `+page.svelte`
- Modify: `frontend/src/lib/messages/{en,de,cs}.ts` (new keys after `family.role.other`)
- Test: `frontend/tests/genealogy.test.ts` (replace), `frontend/tests/CropSelector.test.ts`

**Interfaces:**
- Consumes:
  - The admin REST API (Task 9), including `placeName` on events and `personId`/`isPortrait` on media (Task 7). The browser cannot call it (decision 28), so every write goes through SvelteKit actions.
  - `GET /api/family-tree/search?all=true` (Task 6), `GET /api/records/{id}/pages` and `GET /api/files/{attachmentId}` (existing).
  - `PersonFacts` and the labels (Task 13).
- Produces:
  - `formToBody(FormData)` returns `{method: 'POST'|'PUT'|'DELETE', path, body}`. Paths are limited to the genealogy collections, the field types are known, and an empty field is omitted on create and nulled on update.
  - `cropFromPoints(a, b, width, height)` returns `Crop | null`: clamped to the image, in whole pixels. `changeSummary(Change)` returns a string.
  - `genealogyWrite(email, method, path, body)` returns `{ok, status, data}`, and `fetchGenealogyAdmin<T>(email, path)`.
  - Page actions `?/edit` (`_method`, `_path`, `reason`, fields) and `?/merge` (`keep`, `gone`, `reason`).
  - Route `/family-tree?personId=N&edit=1` (admins) and `/family-tree/review`.

- [ ] **Step 1: Write the failing tests**

Replace the whole of `frontend/tests/genealogy.test.ts` with:

```ts
import { describe, expect, it } from 'vitest';
import {
	EVENT_TAGS,
	changeSummary,
	cropFromPoints,
	eventLabel,
	formToBody,
	qualityLabel
} from '../src/lib/genealogy';
import { en } from '../src/lib/messages/en';
import { de } from '../src/lib/messages/de';
import { cs } from '../src/lib/messages/cs';

function form(pairs: [string, string][]): FormData {
	const f = new FormData();
	for (const [k, v] of pairs) f.append(k, v);
	return f;
}

describe('cropFromPoints', () => {
	it('orders the corners and rounds to whole pixels', () => {
		expect(cropFromPoints({ x: 30.4, y: 20.6 }, { x: 10.2, y: 5 }, 100, 80)).toEqual({
			left: 10,
			top: 5,
			width: 20,
			height: 16
		});
	});

	it('keeps a drag that leaves the image inside it', () => {
		expect(cropFromPoints({ x: 90, y: 70 }, { x: 130, y: -20 }, 100, 80)).toEqual({
			left: 90,
			top: 0,
			width: 10,
			height: 70
		});
	});

	it('is nothing when the drag has no area', () => {
		expect(cropFromPoints({ x: 5, y: 5 }, { x: 5, y: 60 }, 100, 80)).toBeNull();
		expect(cropFromPoints({ x: 120, y: 5 }, { x: 150, y: 60 }, 100, 80)).toBeNull();
	});
});

describe('formToBody', () => {
	it('types numbers and booleans and drops empty fields from a create', () => {
		const out = formToBody(
			form([
				['_method', 'POST'],
				['_path', 'events'],
				['personId', '322'],
				['type', 'BIRT'],
				['dateText', '30 APR 1913'],
				['description', ''],
				['reason', 'register']
			])
		);
		expect(out).toEqual({
			method: 'POST',
			path: 'events',
			body: { personId: 322, type: 'BIRT', dateText: '30 APR 1913', reason: 'register' }
		});
	});

	it('clears an emptied field on update and takes a checked box over its hidden false', () => {
		const out = formToBody(
			form([
				['_method', 'PUT'],
				['_path', 'persons/12'],
				['notes', ''],
				['living', 'false'],
				['living', 'true'],
				['reason', 'x']
			])
		);
		expect(out.body).toEqual({ notes: null, living: true, reason: 'x' });
		expect(formToBody(form([['_method', 'PUT'], ['_path', 'persons/12'], ['living', 'false']])).body)
			.toEqual({ living: false });
	});

	it('refuses paths and methods outside the genealogy API', () => {
		expect(() => formToBody(form([['_method', 'POST'], ['_path', '../records/1']]))).toThrow();
		expect(() => formToBody(form([['_method', 'PATCH'], ['_path', 'events/1']]))).toThrow();
		expect(() => formToBody(form([['_method', 'POST'], ['_path', 'events'], ['personId', 'x']]))).toThrow();
		expect(formToBody(form([['_method', 'POST'], ['_path', 'changes/9/undo']])).path).toBe(
			'changes/9/undo'
		);
		expect(formToBody(form([['_method', 'POST'], ['_path', 'persons/4/names']])).path).toBe(
			'persons/4/names'
		);
	});
});

describe('labels', () => {
	it('names every GEDCOM event and quality in all three languages', () => {
		for (const tag of EVENT_TAGS) {
			for (const messages of [en, de, cs]) {
				expect(messages[eventLabel(tag)]).toBeTruthy();
			}
		}
		for (const q of [0, 1, 2, 3, null]) {
			expect(en[qualityLabel(q)]).toBeTruthy();
		}
	});
});

describe('changeSummary', () => {
	it('lists only the fields that changed', () => {
		expect(
			changeSummary({
				id: 1,
				entity: 'name',
				entityId: 4,
				op: 'update',
				before: { id: 4, given: 'Anna', surname: null, updated_at: 'a' },
				after: { id: 4, given: 'Anna', surname: 'Novák', updated_at: 'b' },
				reason: 'r',
				changedBy: 'x',
				changedAt: '2026-11-02T10:00:00Z'
			})
		).toBe('surname: – → Novák');
	});
});
```

Create `frontend/tests/CropSelector.test.ts`:

```ts
// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/svelte';
import { tick } from 'svelte';
import CropSelector from '../src/lib/components/genealogy/CropSelector.svelte';

function value(container: HTMLElement, name: string): string {
	return (container.querySelector(`input[name="${name}"]`) as HTMLInputElement).value;
}

describe('CropSelector', () => {
	afterEach(() => cleanup());

	it('turns a drag on the shown image into pixels of the stored original', async () => {
		const { container } = render(CropSelector, { src: '/api/files/7' });
		const img = container.querySelector('img') as HTMLImageElement;
		Object.defineProperty(img, 'naturalWidth', { value: 2000 });
		Object.defineProperty(img, 'naturalHeight', { value: 1000 });
		await fireEvent.load(img);
		await tick();

		const svg = container.querySelector('svg') as SVGSVGElement;
		svg.getBoundingClientRect = () =>
			({ left: 0, top: 0, width: 400, height: 200, right: 400, bottom: 200, x: 0, y: 0 }) as DOMRect;
		await fireEvent.pointerDown(svg, { clientX: 40, clientY: 20, pointerId: 1 });
		await fireEvent.pointerMove(svg, { clientX: 140, clientY: 120, pointerId: 1 });
		await fireEvent.pointerUp(svg, { clientX: 440, clientY: 120, pointerId: 1 });

		expect(value(container, 'cropLeft')).toBe('200');
		expect(value(container, 'cropTop')).toBe('100');
		expect(value(container, 'cropWidth')).toBe('1800');
		expect(value(container, 'cropHeight')).toBe('500');
	});

	it('sends no crop for the whole page', () => {
		const { container } = render(CropSelector, { src: '/api/files/7' });
		expect(value(container, 'cropLeft')).toBe('');
		expect(value(container, 'cropHeight')).toBe('');
	});
});
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd frontend && bun run test`

Expected: FAIL: `CropSelector.test.ts` cannot resolve `CropSelector.svelte`, and the new `genealogy.test.ts` cases fail with `cropFromPoints is not a function` (likewise `formToBody` and `changeSummary`)

- [ ] **Step 3: Extend the genealogy module**

Replace the whole of `frontend/src/lib/genealogy.ts` with:

```ts
import type { MessageKey } from '$lib/i18n';

export const EVENT_TAGS = [
	'BIRT',
	'CHR',
	'BAPM',
	'DEAT',
	'BURI',
	'MARR',
	'DIV',
	'ADOP',
	'OCCU',
	'TITL',
	'RESI',
	'NATU',
	'EMIG',
	'IMMI',
	'EDUC',
	'EVEN'
] as const;
export type EventTag = (typeof EVENT_TAGS)[number];

export const NAME_TYPES = ['birth', 'married', 'alias', 'religious', 'title', 'other'] as const;
export const LINK_TYPES = ['birth', 'adopted', 'step', 'foster', 'unknown'] as const;
export const COUPLE_TYPES = ['marriage', 'partnership', 'unknown'] as const;
export const ROLES = [
	'subject',
	'mentioned',
	'author',
	'signatory',
	'witness',
	'recipient',
	'pictured',
	'other'
] as const;
export const SEXES = ['M', 'F', 'U'] as const;

export const EVENT_LABELS: Record<EventTag, MessageKey> = {
	BIRT: 'family.event.BIRT',
	CHR: 'family.event.CHR',
	BAPM: 'family.event.BAPM',
	DEAT: 'family.event.DEAT',
	BURI: 'family.event.BURI',
	MARR: 'family.event.MARR',
	DIV: 'family.event.DIV',
	ADOP: 'family.event.ADOP',
	OCCU: 'family.event.OCCU',
	TITL: 'family.event.TITL',
	RESI: 'family.event.RESI',
	NATU: 'family.event.NATU',
	EMIG: 'family.event.EMIG',
	IMMI: 'family.event.IMMI',
	EDUC: 'family.event.EDUC',
	EVEN: 'family.event.EVEN'
};

export const NAME_TYPE_LABELS: Record<(typeof NAME_TYPES)[number], MessageKey> = {
	birth: 'family.nameType.birth',
	married: 'family.nameType.married',
	alias: 'family.nameType.alias',
	religious: 'family.nameType.religious',
	title: 'family.nameType.title',
	other: 'family.nameType.other'
};

export const LINK_TYPE_LABELS: Record<(typeof LINK_TYPES)[number], MessageKey> = {
	birth: 'family.linkType.birth',
	adopted: 'family.linkType.adopted',
	step: 'family.linkType.step',
	foster: 'family.linkType.foster',
	unknown: 'family.linkType.unknown'
};

export const COUPLE_TYPE_LABELS: Record<(typeof COUPLE_TYPES)[number], MessageKey> = {
	marriage: 'family.coupleType.marriage',
	partnership: 'family.coupleType.partnership',
	unknown: 'family.coupleType.unknown'
};

export const ROLE_LABELS: Record<(typeof ROLES)[number], MessageKey> = {
	subject: 'family.role.subject',
	mentioned: 'family.role.mentioned',
	author: 'family.role.author',
	signatory: 'family.role.signatory',
	witness: 'family.role.witness',
	recipient: 'family.role.recipient',
	pictured: 'family.role.pictured',
	other: 'family.role.other'
};

export const SEX_LABELS: Record<(typeof SEXES)[number], MessageKey> = {
	M: 'family.sex.M',
	F: 'family.sex.F',
	U: 'family.sex.U'
};

/** GEDCOM QUAY 0-3; null when the citation does not say. */
export const QUALITY_LABELS: MessageKey[] = [
	'family.quality.0',
	'family.quality.1',
	'family.quality.2',
	'family.quality.3'
];

export function eventLabel(tag: string): MessageKey {
	return EVENT_LABELS[tag as EventTag] ?? 'family.event.EVEN';
}

export function qualityLabel(quality: number | null): MessageKey {
	return quality == null ? 'family.quality.none' : (QUALITY_LABELS[quality] ?? 'family.quality.none');
}

export interface Citation {
	id: number;
	sourceId: number;
	sourceTitle: string;
	pageId: number | null;
	detail: string | null;
	quality: number | null;
}

export interface TreeEvent {
	id: number;
	type: string;
	tag: string;
	date: string | null;
	dateFrom: string | null;
	dateTo: string | null;
	place: string | null;
	placeId: number | null;
	description: string | null;
	coupleId: number | null;
	spouseId: number | null;
	text: string;
	year: number | null;
	citations: Citation[];
}

export interface PersonName {
	id: number;
	type: string;
	given: string | null;
	surname: string | null;
	prefix: string | null;
	suffix: string | null;
	nickname: string | null;
	isPrimary: boolean;
	sortOrder: number;
}

export interface Couple {
	id: number;
	type: string;
	sortOrder: number;
	notes: string | null;
	spouseId: number;
	spouseName: string;
}

export interface ParentLink {
	id: number;
	type: string;
	coupleId: number | null;
	parentId: number;
	childId: number;
	relation: 'parent' | 'child';
	otherName: string;
}

export interface RecordLink {
	id: number;
	recordId: number;
	pageId: number | null;
	pageSeq: number | null;
	role: string;
	note: string | null;
	quality: number | null;
	title: string | null;
	titleEn: string | null;
	referenceCode: string | null;
}

export interface MediaItem {
	id: number;
	pageId: number;
	recordId: number;
	pageSeq: number;
	cropLeft: number | null;
	cropTop: number | null;
	cropWidth: number | null;
	cropHeight: number | null;
	caption: string | null;
	dateText: string | null;
	isPortrait: boolean;
	imageUrl: string;
}

export interface Source {
	id: number;
	title: string;
	author: string | null;
	recordId: number | null;
	url: string | null;
}

export interface PersonDetail {
	id: number;
	name: string;
	sex: string;
	living: boolean;
	notes: string | null;
	needsReview: string | null;
	names: PersonName[];
	events: TreeEvent[];
	couples: Couple[];
	parentLinks: ParentLink[];
	recordLinks: RecordLink[];
	media: MediaItem[];
	portrait: MediaItem | null;
	sources: Source[];
}

export interface Change {
	id: number;
	entity: string;
	entityId: number;
	op: string;
	before: Record<string, unknown> | null;
	after: Record<string, unknown> | null;
	reason: string;
	changedBy: string;
	changedAt: string;
}

export function fullName(n: Pick<PersonName, 'prefix' | 'given' | 'surname' | 'suffix'>): string {
	return [n.prefix, n.given, n.surname, n.suffix].filter((p) => p && p.trim()).join(' ');
}

/** The fields a change altered, as "field: old → new". */
export function changeSummary(c: Change): string {
	const before = c.before ?? {};
	const after = c.after ?? {};
	const keys = Object.keys({ ...before, ...after }).filter(
		(k) => !['id', 'created_at', 'updated_at', 'uid'].includes(k)
	);
	const changed = keys.filter((k) => JSON.stringify(before[k]) !== JSON.stringify(after[k]));
	return changed
		.map((k) => `${k}: ${show(before[k])} → ${show(after[k])}`)
		.join('; ');
}

function show(v: unknown): string {
	return v == null ? '–' : String(v);
}

export interface Crop {
	left: number;
	top: number;
	width: number;
	height: number;
}

/**
 * The rectangle between two corner points, in whole pixels of the stored image, kept inside it.
 * Null when nothing is left to crop.
 */
export function cropFromPoints(
	a: { x: number; y: number },
	b: { x: number; y: number },
	width: number,
	height: number
): Crop | null {
	const cx = (v: number) => Math.min(width, Math.max(0, Math.round(v)));
	const cy = (v: number) => Math.min(height, Math.max(0, Math.round(v)));
	const left = Math.min(cx(a.x), cx(b.x));
	const right = Math.max(cx(a.x), cx(b.x));
	const top = Math.min(cy(a.y), cy(b.y));
	const bottom = Math.max(cy(a.y), cy(b.y));
	if (right - left < 1 || bottom - top < 1) return null;
	return { left, top, width: right - left, height: bottom - top };
}

const NUMBER_FIELDS = new Set([
	'personId',
	'person1Id',
	'person2Id',
	'parentId',
	'childId',
	'coupleId',
	'placeId',
	'sourceId',
	'pageId',
	'recordId',
	'eventId',
	'nameId',
	'parentChildId',
	'mediaId',
	'quality',
	'sortOrder',
	'fromYear',
	'toYear',
	'lat',
	'lon',
	'cropLeft',
	'cropTop',
	'cropWidth',
	'cropHeight'
]);
const BOOLEAN_FIELDS = new Set(['living', 'isPrimary', 'isPortrait']);
const PATH =
	/^(persons|names|events|couples|parent-links|places|place-names|sources|citations|record-links|media|media-people|changes)(\/\d+)?(\/(names|undo|merge\/\d+))?$/;
const METHODS = ['POST', 'PUT', 'DELETE'] as const;
export type WriteMethod = (typeof METHODS)[number];

/**
 * An edit form as the admin API wants it. `_method` and `_path` say where it goes; the rest are
 * fields. An empty field is left out of a create and cleared by an update. A checkbox is preceded
 * by a hidden "false" of the same name, so the last value wins.
 */
export function formToBody(form: FormData): {
	method: WriteMethod;
	path: string;
	body: Record<string, unknown>;
} {
	const method = String(form.get('_method') ?? 'POST') as WriteMethod;
	const path = String(form.get('_path') ?? '');
	if (!METHODS.includes(method)) throw new Error(`unsupported method ${method}`);
	if (!PATH.test(path)) throw new Error(`unsupported path ${path}`);
	const body: Record<string, unknown> = {};
	for (const key of new Set(form.keys())) {
		if (key === '_method' || key === '_path') continue;
		const values = form.getAll(key).map(String);
		const value = values[values.length - 1];
		if (BOOLEAN_FIELDS.has(key)) {
			body[key] = value === 'true' || value === 'on';
		} else if (value.trim() === '') {
			if (method === 'PUT') body[key] = null;
		} else if (NUMBER_FIELDS.has(key)) {
			const n = Number(value);
			if (Number.isNaN(n)) throw new Error(`${key} must be a number`);
			body[key] = n;
		} else {
			body[key] = value;
		}
	}
	return { method, path, body };
}
```

- [ ] **Step 4: Write the edit components**

Create `frontend/src/lib/components/genealogy/CropSelector.svelte`:

```svelte
<script lang="ts">
	import { t } from '$lib/i18n';
	import { cropFromPoints, type Crop } from '$lib/genealogy';

	let { src, crop = $bindable(null) }: { src: string; crop?: Crop | null } = $props();

	let natural = $state({ width: 0, height: 0 });
	let start: { x: number; y: number } | null = null;

	function loaded(e: Event) {
		const img = e.currentTarget as HTMLImageElement;
		natural = { width: img.naturalWidth, height: img.naturalHeight };
	}

	function point(e: PointerEvent) {
		const r = (e.currentTarget as Element).getBoundingClientRect();
		return {
			x: ((e.clientX - r.left) / r.width) * natural.width,
			y: ((e.clientY - r.top) / r.height) * natural.height
		};
	}

	function down(e: PointerEvent) {
		start = point(e);
		(e.currentTarget as Element).setPointerCapture?.(e.pointerId);
	}

	function move(e: PointerEvent) {
		if (start) crop = cropFromPoints(start, point(e), natural.width, natural.height);
	}

	function up(e: PointerEvent) {
		move(e);
		start = null;
	}
</script>

<div class="cropper">
	<img {src} alt={$t('family.cropImage')} onload={loaded} />
	{#if natural.width > 0}
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<svg
			class="overlay"
			viewBox="0 0 {natural.width} {natural.height}"
			preserveAspectRatio="none"
			onpointerdown={down}
			onpointermove={move}
			onpointerup={up}
		>
			{#if crop}
				<rect class="selection" x={crop.left} y={crop.top} width={crop.width} height={crop.height} />
			{/if}
		</svg>
	{/if}
</div>
<div class="crop-status">
	<span>
		{crop
			? `${crop.left}, ${crop.top} · ${crop.width} × ${crop.height} px`
			: $t('family.wholePage')}
	</span>
	{#if crop}
		<button type="button" class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => (crop = null)}>
			{$t('family.wholePage')}
		</button>
	{/if}
</div>
<input type="hidden" name="cropLeft" value={crop?.left ?? ''} />
<input type="hidden" name="cropTop" value={crop?.top ?? ''} />
<input type="hidden" name="cropWidth" value={crop?.width ?? ''} />
<input type="hidden" name="cropHeight" value={crop?.height ?? ''} />

<style>
	.cropper {
		position: relative;
		display: inline-block;
		max-width: 100%;
	}
	.cropper img {
		display: block;
		max-width: 100%;
		user-select: none;
	}
	.overlay {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		cursor: crosshair;
		touch-action: none;
	}
	.selection {
		fill: color-mix(in srgb, var(--vui-accent) 25%, transparent);
		stroke: var(--vui-accent);
		stroke-width: 2;
		vector-effect: non-scaling-stroke;
	}
	.crop-status {
		display: flex;
		align-items: center;
		gap: 8px;
		font-size: 12px;
		color: var(--vui-text-muted);
	}
</style>
```

Create `frontend/src/lib/components/genealogy/PersonPicker.svelte`:

```svelte
<script lang="ts">
	import { t } from '$lib/i18n';

	interface Hit {
		personId: number;
		name: string;
		birthYear: number | null;
		deathYear: number | null;
	}

	let { name, label }: { name: string; label: string } = $props();

	let query = $state('');
	let hits: Hit[] = $state([]);
	let chosen: { id: number; name: string } | null = $state(null);
	let timer: ReturnType<typeof setTimeout> | undefined;

	function search() {
		clearTimeout(timer);
		timer = setTimeout(async () => {
			if (query.trim().length < 2) {
				hits = [];
				return;
			}
			const params = new URLSearchParams({ q: query, limit: '8', all: 'true' });
			const res = await fetch(`/api/family-tree/search?${params}`);
			hits = res.ok ? await res.json() : [];
		}, 250);
	}
</script>

<div class="picker">
	<label class="field">
		<span>{label}</span>
		{#if chosen}
			<span class="chosen">
				{chosen.name} #{chosen.id}
				<button type="button" class="vui-btn vui-btn-ghost vui-btn-sm" onclick={() => (chosen = null)}>
					{$t('family.change')}
				</button>
			</span>
		{:else}
			<input
				class="vui-input"
				type="search"
				bind:value={query}
				oninput={search}
				placeholder={$t('family.searchPerson')}
			/>
		{/if}
	</label>
	{#if !chosen && hits.length > 0}
		<ul class="hits">
			{#each hits as h (h.personId)}
				<li>
					<button
						type="button"
						class="vui-btn vui-btn-ghost vui-btn-sm"
						onclick={() => {
							chosen = { id: h.personId, name: h.name };
							hits = [];
						}}
					>
						{h.name}
						{#if h.birthYear || h.deathYear}({h.birthYear ?? '?'}–{h.deathYear ?? '?'}){/if}
						#{h.personId}
					</button>
				</li>
			{/each}
		</ul>
	{/if}
	<input type="hidden" {name} value={chosen?.id ?? ''} />
</div>

<style>
	.picker {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.hits {
		list-style: none;
		margin: 0;
		padding: 0;
	}
	.chosen {
		display: flex;
		align-items: center;
		gap: 8px;
	}
</style>
```

Create `frontend/src/lib/components/genealogy/RecordPagePicker.svelte`:

```svelte
<script lang="ts">
	import { t } from '$lib/i18n';
	import type { PageResponse } from '$lib/types';

	let {
		page = $bindable(null),
		requirePage = false,
		sendRecord = true
	}: { page?: PageResponse | null; requirePage?: boolean; sendRecord?: boolean } = $props();

	let recordId = $state('');
	let seq = $state('');
	let pages: PageResponse[] = $state([]);

	async function load() {
		pages = [];
		seq = '';
		page = null;
		if (!/^\d+$/.test(recordId)) return;
		const res = await fetch(`/api/records/${recordId}/pages`);
		pages = res.ok ? await res.json() : [];
	}

	function choose() {
		page = pages.find((p) => String(p.seq) === seq) ?? null;
	}
</script>

<div class="record-page">
	<label class="field">
		<span>{$t('family.record')}</span>
		<input class="vui-input" inputmode="numeric" bind:value={recordId} onchange={load} />
	</label>
	<label class="field">
		<span>{$t('family.page')}</span>
		<select class="vui-input" bind:value={seq} onchange={choose} required={requirePage}>
			<option value="">{requirePage ? $t('family.choosePage') : $t('family.wholeRecord')}</option>
			{#each pages as p (p.id)}
				<option value={String(p.seq)}>{p.seq}{p.pageLabel ? ` (${p.pageLabel})` : ''}</option>
			{/each}
		</select>
	</label>
	{#if sendRecord}<input type="hidden" name="recordId" value={recordId} />{/if}
	<input type="hidden" name="pageId" value={page?.id ?? ''} />
</div>

<style>
	.record-page {
		display: flex;
		gap: 8px;
		flex-wrap: wrap;
	}
</style>
```

Create `frontend/src/lib/components/genealogy/ChangeHistory.svelte`:

```svelte
<script lang="ts">
	import { enhance } from '$app/forms';
	import { t } from '$lib/i18n';
	import { changeSummary, type Change } from '$lib/genealogy';

	let { changes }: { changes: Change[] } = $props();
</script>

<section>
	<h3 class="vui-section-header">{$t('family.history')}</h3>
	{#if changes.length === 0}
		<p class="muted">{$t('family.noHistory')}</p>
	{:else}
		<ol class="history">
			{#each changes as c (c.id)}
				<li class="vui-card change">
					<div class="change-head">
						<span class="vui-badge">{c.op}</span>
						<span>{c.entity} #{c.entityId}</span>
						<span class="muted">{c.changedBy} · {new Date(c.changedAt).toLocaleString()}</span>
					</div>
					<div class="muted">{c.reason}</div>
					<div class="summary">{changeSummary(c)}</div>
					<form method="POST" action="?/edit" use:enhance class="undo">
						<input type="hidden" name="_method" value="POST" />
						<input type="hidden" name="_path" value={`changes/${c.id}/undo`} />
						<input class="vui-input" name="reason" required placeholder={$t('family.reason')} />
						<button class="vui-btn vui-btn-secondary vui-btn-sm">{$t('family.undo')}</button>
					</form>
				</li>
			{/each}
		</ol>
	{/if}
</section>

<style>
	.history {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.change-head,
	.undo {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 8px;
	}
	.summary {
		font-family: var(--vui-font-mono);
		font-size: 12px;
		word-break: break-word;
	}
	.muted {
		color: var(--vui-text-muted);
	}
</style>
```

Create `frontend/src/lib/components/genealogy/PersonEditor.svelte`:

```svelte
<script lang="ts">
	import { enhance } from '$app/forms';
	import { t } from '$lib/i18n';
	import {
		COUPLE_TYPES,
		COUPLE_TYPE_LABELS,
		EVENT_TAGS,
		LINK_TYPES,
		LINK_TYPE_LABELS,
		NAME_TYPES,
		NAME_TYPE_LABELS,
		QUALITY_LABELS,
		ROLES,
		ROLE_LABELS,
		SEXES,
		SEX_LABELS,
		eventLabel,
		fullName,
		type Crop,
		type PersonDetail,
		type Source
	} from '$lib/genealogy';
	import type { PageResponse } from '$lib/types';
	import CropSelector from './CropSelector.svelte';
	import PersonPicker from './PersonPicker.svelte';
	import RecordPagePicker from './RecordPagePicker.svelte';

	let {
		person,
		places,
		sources
	}: { person: PersonDetail; places: { id: number; name: string }[]; sources: Source[] } = $props();

	let mediaPage: PageResponse | null = $state(null);
	let crop: Crop | null = $state(null);
</script>

{#snippet reason()}
	<input class="vui-input" name="reason" required placeholder={$t('family.reason')} />
{/snippet}

{#snippet target(method: string, path: string)}
	<input type="hidden" name="_method" value={method} />
	<input type="hidden" name="_path" value={path} />
{/snippet}

<datalist id="gen-places">
	{#each places as p (p.id)}<option value={p.name}></option>{/each}
</datalist>

<div class="editor">
	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.person')}</h3>
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('PUT', `persons/${person.id}`)}
			<label class="field">
				<span>{$t('family.sex')}</span>
				<select class="vui-input" name="sex" value={person.sex}>
					{#each SEXES as s (s)}<option value={s}>{$t(SEX_LABELS[s])}</option>{/each}
				</select>
			</label>
			<label class="field checkbox">
				<input type="hidden" name="living" value="false" />
				<input class="vui-checkbox" type="checkbox" name="living" value="true" checked={person.living} />
				<span>{$t('family.living')}</span>
			</label>
			<label class="field wide">
				<span>{$t('family.notes')}</span>
				<textarea class="vui-input" name="notes">{person.notes ?? ''}</textarea>
			</label>
			<label class="field wide">
				<span>{$t('family.needsReview')}</span>
				<textarea class="vui-input" name="needsReview">{person.needsReview ?? ''}</textarea>
			</label>
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.save')}</button>
		</form>
	</section>

	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.names')}</h3>
		{#each person.names as n (n.id)}
			<form method="POST" action="?/edit" use:enhance class="grid">
				{@render target('PUT', `names/${n.id}`)}
				<select class="vui-input" name="type" value={n.type}>
					{#each NAME_TYPES as nt (nt)}<option value={nt}>{$t(NAME_TYPE_LABELS[nt])}</option>{/each}
				</select>
				<input class="vui-input" name="prefix" value={n.prefix ?? ''} placeholder={$t('family.prefix')} />
				<input class="vui-input" name="given" value={n.given ?? ''} placeholder={$t('family.given')} />
				<input class="vui-input" name="surname" value={n.surname ?? ''} placeholder={$t('family.surname')} />
				<input class="vui-input" name="suffix" value={n.suffix ?? ''} placeholder={$t('family.suffix')} />
				<input class="vui-input" name="nickname" value={n.nickname ?? ''} placeholder={$t('family.nickname')} />
				<label class="field checkbox">
					<input type="hidden" name="isPrimary" value="false" />
					<input class="vui-checkbox" type="checkbox" name="isPrimary" value="true" checked={n.isPrimary} />
					<span>{$t('family.primary')}</span>
				</label>
				{@render reason()}
				<button class="vui-btn vui-btn-secondary vui-btn-sm">{$t('family.save')}</button>
			</form>
			{#if !n.isPrimary}
				<form method="POST" action="?/edit" use:enhance class="inline">
					{@render target('DELETE', `names/${n.id}`)}
					{@render reason()}
					<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.delete')} {fullName(n)}</button>
				</form>
			{/if}
		{/each}
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', `persons/${person.id}/names`)}
			<select class="vui-input" name="type">
				{#each NAME_TYPES as nt (nt)}<option value={nt}>{$t(NAME_TYPE_LABELS[nt])}</option>{/each}
			</select>
			<input class="vui-input" name="given" placeholder={$t('family.given')} />
			<input class="vui-input" name="surname" placeholder={$t('family.surname')} />
			<input class="vui-input" name="nickname" placeholder={$t('family.nickname')} />
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.addName')}</button>
		</form>
	</section>

	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.events')}</h3>
		{#each person.events as ev (ev.id)}
			<div class="event-edit">
				<form method="POST" action="?/edit" use:enhance class="grid">
					{@render target('PUT', `events/${ev.id}`)}
					<select class="vui-input" name="type" value={ev.tag}>
						{#each EVENT_TAGS as tag (tag)}<option value={tag}>{$t(eventLabel(tag))}</option>{/each}
					</select>
					<input class="vui-input" name="dateText" value={ev.date ?? ''} placeholder={$t('family.dateHelp')} />
					<input class="vui-input" name="placeName" list="gen-places" value={ev.place ?? ''} placeholder={$t('family.place')} />
					<input class="vui-input" name="description" value={ev.description ?? ''} placeholder={$t('family.description')} />
					{@render reason()}
					<button class="vui-btn vui-btn-secondary vui-btn-sm">{$t('family.save')}</button>
				</form>
				<form method="POST" action="?/edit" use:enhance class="grid">
					{@render target('POST', 'citations')}
					<input type="hidden" name="eventId" value={ev.id} />
					<select class="vui-input" name="sourceId" required>
						<option value="">{$t('family.source')}</option>
						{#each sources as s (s.id)}<option value={s.id}>{s.title}</option>{/each}
					</select>
					<RecordPagePicker sendRecord={false} />
					<input class="vui-input" name="detail" placeholder={$t('family.detail')} />
					<select class="vui-input" name="quality">
						{#each QUALITY_LABELS as q, i (q)}<option value={i}>{$t(q)}</option>{/each}
					</select>
					{@render reason()}
					<button class="vui-btn vui-btn-secondary vui-btn-sm">{$t('family.cite')}</button>
				</form>
				<form method="POST" action="?/edit" use:enhance class="inline">
					{@render target('DELETE', `events/${ev.id}`)}
					{@render reason()}
					<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.delete')}</button>
				</form>
			</div>
		{/each}
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', 'events')}
			<select class="vui-input" name="type">
				{#each EVENT_TAGS as tag (tag)}<option value={tag}>{$t(eventLabel(tag))}</option>{/each}
			</select>
			<input type="hidden" name="personId" value={person.id} />
			<input class="vui-input" name="dateText" placeholder={$t('family.dateHelp')} />
			<input class="vui-input" name="placeName" list="gen-places" placeholder={$t('family.place')} />
			<input class="vui-input" name="description" placeholder={$t('family.description')} />
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.addEvent')}</button>
		</form>
	</section>

	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.relations')}</h3>
		{#each person.couples as c (c.id)}
			<form method="POST" action="?/edit" use:enhance class="inline">
				{@render target('DELETE', `couples/${c.id}`)}
				<span>{c.spouseName}</span>
				{@render reason()}
				<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.delete')}</button>
			</form>
			<form method="POST" action="?/edit" use:enhance class="grid">
				{@render target('POST', 'events')}
				<input type="hidden" name="coupleId" value={c.id} />
				<select class="vui-input" name="type">
					{#each ['MARR', 'DIV', 'EVEN'] as tag (tag)}<option value={tag}>{$t(eventLabel(tag))}</option>{/each}
				</select>
				<input class="vui-input" name="dateText" placeholder={$t('family.dateHelp')} />
				<input class="vui-input" name="placeName" list="gen-places" placeholder={$t('family.place')} />
				{@render reason()}
				<button class="vui-btn vui-btn-secondary vui-btn-sm">{$t('family.addEvent')}</button>
			</form>
		{/each}
		{#each person.parentLinks as l (l.id)}
			<form method="POST" action="?/edit" use:enhance class="inline">
				{@render target('DELETE', `parent-links/${l.id}`)}
				<span>{l.relation === 'parent' ? $t('family.parent') : $t('family.child')}: {l.otherName} ({$t(LINK_TYPE_LABELS[l.type as keyof typeof LINK_TYPE_LABELS])})</span>
				{@render reason()}
				<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.delete')}</button>
			</form>
		{/each}
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', 'couples')}
			<input type="hidden" name="person1Id" value={person.id} />
			<PersonPicker name="person2Id" label={$t('family.spouse')} />
			<select class="vui-input" name="type">
				{#each COUPLE_TYPES as ct (ct)}<option value={ct}>{$t(COUPLE_TYPE_LABELS[ct])}</option>{/each}
			</select>
			<input class="vui-input" name="sortOrder" inputmode="numeric" placeholder={$t('family.marriageNumber')} />
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.addCouple')}</button>
		</form>
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', 'parent-links')}
			<input type="hidden" name="childId" value={person.id} />
			<PersonPicker name="parentId" label={$t('family.parent')} />
			<select class="vui-input" name="type">
				{#each LINK_TYPES as lt (lt)}<option value={lt}>{$t(LINK_TYPE_LABELS[lt])}</option>{/each}
			</select>
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.addParent')}</button>
		</form>
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', 'parent-links')}
			<input type="hidden" name="parentId" value={person.id} />
			<PersonPicker name="childId" label={$t('family.child')} />
			<select class="vui-input" name="type">
				{#each LINK_TYPES as lt (lt)}<option value={lt}>{$t(LINK_TYPE_LABELS[lt])}</option>{/each}
			</select>
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.addChild')}</button>
		</form>
	</section>

	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.records')}</h3>
		{#each person.recordLinks as r (r.id)}
			<form method="POST" action="?/edit" use:enhance class="inline">
				{@render target('DELETE', `record-links/${r.id}`)}
				<span>{r.titleEn ?? r.title ?? `#${r.recordId}`} ({$t(ROLE_LABELS[r.role as keyof typeof ROLE_LABELS])})</span>
				{@render reason()}
				<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.delete')}</button>
			</form>
		{/each}
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', 'record-links')}
			<input type="hidden" name="personId" value={person.id} />
			<RecordPagePicker />
			<select class="vui-input" name="role">
				{#each ROLES as role (role)}<option value={role}>{$t(ROLE_LABELS[role])}</option>{/each}
			</select>
			<input class="vui-input" name="note" placeholder={$t('family.note')} />
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.linkRecord')}</button>
		</form>
	</section>

	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.media')}</h3>
		{#each person.media as m (m.id)}
			<form method="POST" action="?/edit" use:enhance class="inline">
				{@render target('DELETE', `media/${m.id}`)}
				<img class="thumb" src={m.imageUrl} alt={m.caption ?? ''} />
				{@render reason()}
				<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.delete')}</button>
			</form>
		{/each}
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', 'media')}
			<input type="hidden" name="personId" value={person.id} />
			<RecordPagePicker bind:page={mediaPage} requirePage sendRecord={false} />
			{#if mediaPage?.attachmentId}
				<div class="wide">
					<CropSelector src={`/api/files/${mediaPage.attachmentId}`} bind:crop />
				</div>
			{/if}
			<input class="vui-input" name="caption" placeholder={$t('family.caption')} />
			<input class="vui-input" name="dateText" placeholder={$t('family.dateHelp')} />
			<label class="field checkbox">
				<input type="hidden" name="isPortrait" value="false" />
				<input class="vui-checkbox" type="checkbox" name="isPortrait" value="true" />
				<span>{$t('family.portrait')}</span>
			</label>
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.addMedia')}</button>
		</form>
	</section>

	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.sources')}</h3>
		<form method="POST" action="?/edit" use:enhance class="grid">
			{@render target('POST', 'sources')}
			<input class="vui-input" name="title" required placeholder={$t('family.sourceTitle')} />
			<input class="vui-input" name="author" placeholder={$t('family.author')} />
			<input class="vui-input" name="recordId" inputmode="numeric" placeholder={$t('family.record')} />
			<input class="vui-input" name="url" placeholder="https://" />
			{@render reason()}
			<button class="vui-btn vui-btn-primary vui-btn-sm">{$t('family.addSource')}</button>
		</form>
	</section>

	<section class="vui-card">
		<h3 class="vui-section-header">{$t('family.merge')}</h3>
		<p class="muted">{$t('family.mergeHelp')}</p>
		<form method="POST" action="?/merge" use:enhance class="grid">
			<input type="hidden" name="keep" value={person.id} />
			<PersonPicker name="gone" label={$t('family.duplicate')} />
			{@render reason()}
			<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.merge')}</button>
		</form>
		<form method="POST" action="?/edit" use:enhance class="inline">
			{@render target('DELETE', `persons/${person.id}`)}
			{@render reason()}
			<button class="vui-btn vui-btn-danger vui-btn-sm">{$t('family.deletePerson')}</button>
		</form>
	</section>
</div>

<style>
	.editor {
		display: flex;
		flex-direction: column;
		gap: 12px;
		margin-bottom: 16px;
	}
	.grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
		gap: 8px;
		align-items: end;
		margin-bottom: 8px;
	}
	.inline {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 8px;
		margin-bottom: 8px;
	}
	.wide {
		grid-column: 1 / -1;
	}
	.field {
		display: flex;
		flex-direction: column;
		gap: 4px;
		font-size: 12px;
		color: var(--vui-text-muted);
	}
	.checkbox {
		flex-direction: row;
		align-items: center;
	}
	.event-edit {
		border-bottom: 1px solid var(--vui-border);
		padding-bottom: 8px;
		margin-bottom: 8px;
	}
	.thumb {
		max-height: 48px;
		border-radius: var(--vui-radius-sm);
	}
	.muted {
		color: var(--vui-text-muted);
	}
</style>
```

- [ ] **Step 5: Add the admin API helpers**

In `frontend/src/lib/server/api.ts`, replace:

```ts
export interface PagePersonMatch {
```

with:

```ts
// ── Genealogy editing (admin; the browser cannot reach /api/admin) ──

export interface GenealogyWriteResult {
  ok: boolean;
  status: number;
  data: Record<string, unknown>;
}

export async function genealogyWrite(
  email: string,
  method: "POST" | "PUT" | "DELETE",
  path: string,
  body: Record<string, unknown>,
): Promise<GenealogyWriteResult> {
  const res = await fetch(`${backendUrl()}/api/admin/genealogy/${path}`, {
    method,
    headers: { "Content-Type": "application/json", ...authHeaders(email) },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  return { ok: res.ok, status: res.status, data };
}

export async function fetchGenealogyAdmin<T>(email: string, path: string): Promise<T> {
  const res = await fetch(`${backendUrl()}/api/admin/genealogy/${path}`, {
    headers: authHeaders(email),
  });
  if (!res.ok) throw new Error(`Backend error: ${res.status}`);
  return res.json();
}

export interface PagePersonMatch {
```

- [ ] **Step 6: Load edit data and add the actions**

Replace the whole of `frontend/src/routes/family-tree/+page.server.ts` with:

```ts
import type { PageServerLoad } from './$types';
import type { RequestEvent } from '@sveltejs/kit';
import { fail } from '@sveltejs/kit';
import {
	searchFamilyTree,
	relatePerson,
	fetchFamilyPerson,
	updateProfile,
	genealogyWrite,
	fetchGenealogyAdmin
} from '$lib/server/api';
import { formToBody, type Change, type Source } from '$lib/genealogy';

export const load: PageServerLoad = async ({ url, parent, locals }) => {
	const q = url.searchParams.get('q') || '';
	const personId = url.searchParams.get('personId');

	const { user } = await parent();
	const refId = (user as any)?.familyTreePersonId ?? undefined;

	let results: any[] = [];
	let relationship: any = null;
	let person: any = null;

	if (q) {
		results = await searchFamilyTree(locals.userEmail, q, 10);
	}

	if (personId) {
		const pid = parseInt(personId);
		[relationship, person] = await Promise.all([
			relatePerson(locals.userEmail, pid, refId),
			fetchFamilyPerson(locals.userEmail, pid)
		]);
	}

	const editing =
		(user as any)?.role === 'admin' && url.searchParams.get('edit') === '1' && !!person;
	let history: Change[] = [];
	let places: { id: number; name: string }[] = [];
	let sources: Source[] = [];
	if (editing && locals.userEmail) {
		[history, places, sources] = await Promise.all([
			fetchGenealogyAdmin<Change[]>(locals.userEmail, `changes?personId=${person.id}&limit=50`),
			fetchGenealogyAdmin<{ id: number; name: string }[]>(locals.userEmail, 'places?limit=1000'),
			fetchGenealogyAdmin<Source[]>(locals.userEmail, 'sources?limit=1000')
		]);
	}

	return {
		q,
		results,
		relationship,
		person,
		personId: personId ? parseInt(personId) : null,
		editing,
		history,
		places,
		sources
	};
};

export const actions = {
	setFamilyTreePerson: async ({ request, locals }: RequestEvent) => {
		if (!locals.userEmail) return { error: 'Not authenticated' };
		const form = await request.formData();
		const personId = form.get('personId');
		await updateProfile(locals.userEmail, {
			familyTreePersonId: personId ? Number(personId) : null,
		} as any);
		return { success: true };
	},
	edit: async ({ request, locals }: RequestEvent) => {
		if (!locals.userEmail) return fail(401, { error: 'Not authenticated' });
		let write;
		try {
			write = formToBody(await request.formData());
		} catch (e) {
			return fail(400, { error: (e as Error).message });
		}
		const r = await genealogyWrite(locals.userEmail, write.method, write.path, write.body);
		if (!r.ok) return fail(r.status, { error: String(r.data.error ?? `Backend error: ${r.status}`) });
		return { success: true };
	},
	merge: async ({ request, locals }: RequestEvent) => {
		if (!locals.userEmail) return fail(401, { error: 'Not authenticated' });
		const form = await request.formData();
		const keep = Number(form.get('keep'));
		const gone = Number(form.get('gone'));
		if (!Number.isInteger(keep) || !Number.isInteger(gone) || gone <= 0) {
			return fail(400, { error: 'Choose the duplicate' });
		}
		const r = await genealogyWrite(locals.userEmail, 'POST', `persons/${keep}/merge/${gone}`, {
			reason: String(form.get('reason') ?? '')
		});
		if (!r.ok) return fail(r.status, { error: String(r.data.error ?? `Backend error: ${r.status}`) });
		return { success: true };
	}
};
```

- [ ] **Step 7: Show edit mode to administrators**

In `frontend/src/routes/family-tree/+page.svelte`, replace:

```svelte
	import PersonFacts from '$lib/components/genealogy/PersonFacts.svelte';

	let { data } = $props();
```

with:

```svelte
	import ChangeHistory from '$lib/components/genealogy/ChangeHistory.svelte';
	import PersonEditor from '$lib/components/genealogy/PersonEditor.svelte';
	import PersonFacts from '$lib/components/genealogy/PersonFacts.svelte';

	let { data, form } = $props();
```

In `frontend/src/routes/family-tree/+page.svelte`, replace:

```svelte
	const hasRefPerson = $derived(!!user?.familyTreePersonId);
```

with:

```svelte
	const hasRefPerson = $derived(!!user?.familyTreePersonId);
	const isAdmin = $derived(user?.role === 'admin');
```

In `frontend/src/routes/family-tree/+page.svelte`, replace:

```svelte
						</button>
					</form>
				{/if}
			</div>
		{/if}
```

with:

```svelte
						</button>
					</form>
				{/if}

				{#if isAdmin}
					<div class="admin-bar">
						<a
							class="vui-btn vui-btn-secondary vui-btn-sm"
							href={`/family-tree?q=${encodeURIComponent(data.q)}&personId=${p.id}${data.editing ? '' : '&edit=1'}`}
						>
							{data.editing ? $t('family.doneEditing') : $t('family.edit')}
						</a>
						<a class="vui-btn vui-btn-ghost vui-btn-sm" href="/family-tree/review">{$t('family.review')}</a>
					</div>
				{/if}
			</div>

			{#if data.editing}
				{#if form?.error}
					<div class="vui-alert vui-alert-danger">{form.error}</div>
				{:else if form?.success}
					<div class="vui-alert vui-alert-success">{$t('family.saved')}</div>
				{/if}
				<PersonEditor person={p} places={data.places} sources={data.sources} />
				<ChangeHistory changes={data.history} />
			{/if}
		{/if}
```

In `frontend/src/routes/family-tree/+page.svelte`, replace:

```css
	.empty {
		margin-top: 32px;
```

with:

```css
	.admin-bar {
		display: flex;
		gap: 8px;
		margin-top: 14px;
	}

	.empty {
		margin-top: 32px;
```

- [ ] **Step 8: Add the review list**

Create `frontend/src/routes/family-tree/review/+page.server.ts`:

```ts
import { error } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';
import { fetchGenealogyAdmin } from '$lib/server/api';

export interface ReviewItem {
	id: number;
	name: string;
	needsReview: string;
	legacyEntry: string | null;
}

export const load: PageServerLoad = async ({ parent, locals }) => {
	const { user } = await parent();
	if ((user as any)?.role !== 'admin' || !locals.userEmail) error(403, 'Administrators only');
	return { items: await fetchGenealogyAdmin<ReviewItem[]>(locals.userEmail, 'review') };
};
```

Create `frontend/src/routes/family-tree/review/+page.svelte`:

```svelte
<script lang="ts">
	import { t } from '$lib/i18n';

	let { data } = $props();
</script>

<svelte:head>
	<title>{$t('family.reviewTitle')}</title>
</svelte:head>

<h1 class="vui-page-title">{$t('family.reviewTitle')}</h1>
{#if data.items.length === 0}
	<p>{$t('family.reviewEmpty')}</p>
{:else}
	<ul class="review">
		{#each data.items as item (item.id)}
			<li class="vui-card">
				<a href={`/family-tree?personId=${item.id}&edit=1`}>{item.name} #{item.id}</a>
				<p class="reason">{item.needsReview}</p>
				{#if item.legacyEntry}<p class="legacy">{item.legacyEntry}</p>{/if}
			</li>
		{/each}
	</ul>
{/if}

<style>
	.review {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.reason {
		margin: 4px 0 0;
	}
	.legacy {
		margin: 4px 0 0;
		font-family: var(--vui-font-mono);
		font-size: 12px;
		color: var(--vui-text-muted);
	}
</style>
```

- [ ] **Step 9: Add the i18n keys in all three languages**

In `frontend/src/lib/messages/en.ts`, replace:

```ts
	'family.role.other': 'other',
```

with:

```ts
	'family.role.other': 'other',
	'family.sex.M': 'male',
	'family.sex.F': 'female',
	'family.sex.U': 'unknown',
	'family.cropImage': 'Page scan; drag to select a region',
	'family.wholePage': 'Whole page',
	'family.change': 'Change',
	'family.searchPerson': 'Search the tree…',
	'family.record': 'Record',
	'family.page': 'Page',
	'family.choosePage': 'Choose a page',
	'family.wholeRecord': 'Whole record',
	'family.history': 'History',
	'family.noHistory': 'No changes yet',
	'family.reason': 'Reason (evidence)',
	'family.undo': 'Undo',
	'family.person': 'Person',
	'family.sex': 'Sex',
	'family.living': 'Living',
	'family.notes': 'Notes',
	'family.needsReview': 'Needs review',
	'family.save': 'Save',
	'family.names': 'Names',
	'family.prefix': 'Title',
	'family.given': 'Given names',
	'family.surname': 'Surname',
	'family.suffix': 'Suffix',
	'family.nickname': 'Nickname',
	'family.primary': 'Primary',
	'family.delete': 'Delete',
	'family.addName': 'Add name',
	'family.dateHelp': 'GEDCOM date, e.g. 12 MAR 1890, ABT 1890',
	'family.place': 'Place',
	'family.description': 'Description',
	'family.source': 'Source',
	'family.detail': 'Where in the source',
	'family.cite': 'Cite',
	'family.addEvent': 'Add event',
	'family.relations': 'Relations',
	'family.child': 'Child',
	'family.spouse': 'Spouse',
	'family.marriageNumber': 'Marriage no.',
	'family.addCouple': 'Add spouse',
	'family.addParent': 'Add parent',
	'family.addChild': 'Add child',
	'family.note': 'Note',
	'family.linkRecord': 'Link document',
	'family.caption': 'Caption',
	'family.portrait': 'Portrait',
	'family.addMedia': 'Add image',
	'family.sourceTitle': 'Source title',
	'family.author': 'Author',
	'family.addSource': 'Add source',
	'family.merge': 'Merge duplicate',
	'family.mergeHelp': 'Moves every name, event, link and document of the duplicate to this person. It can be undone from the history.',
	'family.duplicate': 'Duplicate',
	'family.deletePerson': 'Delete person',
	'family.edit': 'Edit',
	'family.doneEditing': 'Done',
	'family.review': 'Review list',
	'family.saved': 'Saved',
	'family.reviewTitle': 'People to review',
	'family.reviewEmpty': 'Nothing to review',
```

In `frontend/src/lib/messages/de.ts`, replace:

```ts
	'family.role.other': 'andere',
```

with:

```ts
	'family.role.other': 'andere',
	'family.sex.M': 'männlich',
	'family.sex.F': 'weiblich',
	'family.sex.U': 'unbekannt',
	'family.cropImage': 'Seitenscan; zum Auswählen ziehen',
	'family.wholePage': 'Ganze Seite',
	'family.change': 'Ändern',
	'family.searchPerson': 'Im Stammbaum suchen…',
	'family.record': 'Akte',
	'family.page': 'Seite',
	'family.choosePage': 'Seite wählen',
	'family.wholeRecord': 'Ganze Akte',
	'family.history': 'Verlauf',
	'family.noHistory': 'Noch keine Änderungen',
	'family.reason': 'Begründung (Beleg)',
	'family.undo': 'Rückgängig',
	'family.person': 'Person',
	'family.sex': 'Geschlecht',
	'family.living': 'Lebend',
	'family.notes': 'Notizen',
	'family.needsReview': 'Zu prüfen',
	'family.save': 'Speichern',
	'family.names': 'Namen',
	'family.prefix': 'Titel',
	'family.given': 'Vornamen',
	'family.surname': 'Nachname',
	'family.suffix': 'Namenszusatz',
	'family.nickname': 'Rufname',
	'family.primary': 'Hauptname',
	'family.delete': 'Löschen',
	'family.addName': 'Namen hinzufügen',
	'family.dateHelp': 'GEDCOM-Datum, z. B. 12 MAR 1890, ABT 1890',
	'family.place': 'Ort',
	'family.description': 'Beschreibung',
	'family.source': 'Quelle',
	'family.detail': 'Fundstelle',
	'family.cite': 'Belegen',
	'family.addEvent': 'Ereignis hinzufügen',
	'family.relations': 'Beziehungen',
	'family.child': 'Kind',
	'family.spouse': 'Ehepartner',
	'family.marriageNumber': 'Ehe Nr.',
	'family.addCouple': 'Ehepartner hinzufügen',
	'family.addParent': 'Elternteil hinzufügen',
	'family.addChild': 'Kind hinzufügen',
	'family.note': 'Notiz',
	'family.linkRecord': 'Dokument verknüpfen',
	'family.caption': 'Bildunterschrift',
	'family.portrait': 'Porträt',
	'family.addMedia': 'Bild hinzufügen',
	'family.sourceTitle': 'Quellentitel',
	'family.author': 'Verfasser',
	'family.addSource': 'Quelle hinzufügen',
	'family.merge': 'Duplikat zusammenführen',
	'family.mergeHelp': 'Verschiebt alle Namen, Ereignisse, Verknüpfungen und Dokumente des Duplikats auf diese Person. Im Verlauf rückgängig zu machen.',
	'family.duplicate': 'Duplikat',
	'family.deletePerson': 'Person löschen',
	'family.edit': 'Bearbeiten',
	'family.doneEditing': 'Fertig',
	'family.review': 'Prüfliste',
	'family.saved': 'Gespeichert',
	'family.reviewTitle': 'Zu prüfende Personen',
	'family.reviewEmpty': 'Nichts zu prüfen',
```

In `frontend/src/lib/messages/cs.ts`, replace:

```ts
	'family.role.other': 'jiné',
```

with:

```ts
	'family.role.other': 'jiné',
	'family.sex.M': 'muž',
	'family.sex.F': 'žena',
	'family.sex.U': 'neznámé',
	'family.cropImage': 'Sken stránky; tažením vyberte oblast',
	'family.wholePage': 'Celá stránka',
	'family.change': 'Změnit',
	'family.searchPerson': 'Hledat v rodokmenu…',
	'family.record': 'Záznam',
	'family.page': 'Strana',
	'family.choosePage': 'Vyberte stranu',
	'family.wholeRecord': 'Celý záznam',
	'family.history': 'Historie',
	'family.noHistory': 'Zatím žádné změny',
	'family.reason': 'Důvod (doklad)',
	'family.undo': 'Vrátit',
	'family.person': 'Osoba',
	'family.sex': 'Pohlaví',
	'family.living': 'Žijící',
	'family.notes': 'Poznámky',
	'family.needsReview': 'Ke kontrole',
	'family.save': 'Uložit',
	'family.names': 'Jména',
	'family.prefix': 'Titul',
	'family.given': 'Křestní jména',
	'family.surname': 'Příjmení',
	'family.suffix': 'Přípona',
	'family.nickname': 'Přezdívka',
	'family.primary': 'Hlavní',
	'family.delete': 'Smazat',
	'family.addName': 'Přidat jméno',
	'family.dateHelp': 'Datum GEDCOM, např. 12 MAR 1890, ABT 1890',
	'family.place': 'Místo',
	'family.description': 'Popis',
	'family.source': 'Pramen',
	'family.detail': 'Místo v prameni',
	'family.cite': 'Doložit',
	'family.addEvent': 'Přidat událost',
	'family.relations': 'Vztahy',
	'family.child': 'Dítě',
	'family.spouse': 'Manžel/ka',
	'family.marriageNumber': 'Sňatek č.',
	'family.addCouple': 'Přidat manžela/ku',
	'family.addParent': 'Přidat rodiče',
	'family.addChild': 'Přidat dítě',
	'family.note': 'Poznámka',
	'family.linkRecord': 'Propojit dokument',
	'family.caption': 'Popisek',
	'family.portrait': 'Portrét',
	'family.addMedia': 'Přidat obrázek',
	'family.sourceTitle': 'Název pramene',
	'family.author': 'Autor',
	'family.addSource': 'Přidat pramen',
	'family.merge': 'Sloučit duplikát',
	'family.mergeHelp': 'Přesune všechna jména, události, vazby a dokumenty duplikátu k této osobě. Lze vrátit v historii.',
	'family.duplicate': 'Duplikát',
	'family.deletePerson': 'Smazat osobu',
	'family.edit': 'Upravit',
	'family.doneEditing': 'Hotovo',
	'family.review': 'Seznam ke kontrole',
	'family.saved': 'Uloženo',
	'family.reviewTitle': 'Osoby ke kontrole',
	'family.reviewEmpty': 'Nic ke kontrole',
```

- [ ] **Step 10: Run the tests, the type check and the i18n check**

Run: `cd frontend && bun run test`

Expected: PASS (including `genealogy.test.ts` 8 tests and `CropSelector.test.ts` 2 tests)

Run: `cd frontend && bun run check`

Expected: `0 ERRORS` (the same 3 existing warnings, no new ones)

Run: `make test-frontend`

Expected: vitest PASS, then the i18n completeness check exits 0

Run: `grep -rnE '(^|[^-a-z])style(=|:[-a-z])' frontend/src/lib/components/genealogy frontend/src/routes/family-tree`

Expected: no output (no `style="…"` attribute, no `style:` directive)

- [ ] **Step 11: Commit**

```bash
git add -A frontend/src/lib frontend/src/routes/family-tree frontend/tests/genealogy.test.ts frontend/tests/CropSelector.test.ts
git commit -m "feat: admin edit mode for the family tree: forms, pickers, crop selector, history with undo, review list"
```

---

### Task 15: Docs, full verification and release checks

**Files:**
- Modify: `CLAUDE.md:123` (new `### Genealogy` before `## Common Commands`), `:167` (package list), `:171` (Flyway line), `:176-185` (controllers), `:187-196` (key service files)
- Modify: `.claude/skills/archiver-api/SKILL.md:22` (MCP table, after `delete_page`), `:26` (admin-only note), `:264` (new section before `## PDFs`)

**Interfaces:**
- Consumes: everything above.
- Produces: the docs, plus a verified branch that is ready for the user to tag.

- [ ] **Step 1: Document the genealogy in CLAUDE.md**

In `CLAUDE.md`, replace:

```markdown
## Common Commands
```

with:

```markdown
### Genealogy

The family tree lives in the `gen_*` tables (V21). It follows the GEDCOM X conceptual model and
the Gramps schema, in GEDCOM 7 vocabulary. The tables hold:

- people, names, couples, and parent links (birth, adopted, step, foster);
- places, and events with GEDCOM tags. `date_text` is a GEDCOM 7 date; `date_from` and `date_to`
  are derived from it for sorting;
- sources, and citations (`QUAY` 0–3, each proving exactly one fact);
- record links, and media (a page scan, whole or cropped).

- Person ids 1–348 are the parse-order ids of the old text file. Migration V22, a Java migration
  in `genealogy/legacy/`, imported them once. 122 and 227 are the folded section roots, kept with
  `merged_into`. Never renumber them: `page_person_match` and `app_user.family_tree_person_id`
  point at them, as foreign keys since V23.
- `GenealogyService` is the only writer. Every write needs a reason and appends one `gen_change`
  row per row it changed, holding the row before and after. `GenealogyHistoryService` merges
  duplicates and undoes a whole write, refusing if the row has changed since.
- `FamilyTreeService` is a cached read model over the tables (search, kinship, people), rebuilt
  after each commit. Its answers are pinned to golden files captured from the old in-memory tree
  (`backend/src/test/resources/genealogy/golden`), and `FamilyTreeReadModelTest` guards them. Do
  not regenerate the files to make a change pass.
- Edit through `/api/admin/genealogy/**`, the MCP tree tools, or the admin edit mode on
  `/family-tree`. Read through `/api/family-tree/**`. `GET /api/admin/genealogy/export.ged` is
  the GEDCOM 7 export. Media crops are copied from the stored original pixel for pixel, never
  scaled.

## Common Commands
```

In `CLAUDE.md`, replace:

```markdown
- **Package**: `place.icomb.archiver` — `controller/`, `service/`, `model/`, `dto/`, `repository/`, `config/`
```

with:

```markdown
- **Package**: `place.icomb.archiver` — `controller/`, `service/`, `genealogy/`, `mcp/`, `model/`, `dto/`, `repository/`, `config/`
```

In `CLAUDE.md`, replace:

```markdown
**Never edit an applied migration** — add a new one.
```

with:

```markdown
**Never edit an applied migration** — add a new one. V22 is a Java migration
  (`genealogy/legacy/V22__Import_legacy_genealogy.java`), a Spring bean that Spring Boot hands to Flyway.
```

In `CLAUDE.md`, replace:

```markdown
| `SemanticSearchController` | `/api/semantic-search` | Vector similarity search |
```

with:

```markdown
| `SemanticSearchController` | `/api/semantic-search` | Vector similarity search |
| `FamilyTreeController` | `/api/family-tree` | Tree search, kinship, a person's stored facts, media images |
| `AdminGenealogyController` | `/api/admin/genealogy` | Tree edits, merge, history and undo, review list, GEDCOM export |
```

In `CLAUDE.md`, replace:

```markdown
- `service/OcrImageService.java` — crops the figures Mistral found (signatures, stamps) out of the scan
```

with:

```markdown
- `service/OcrImageService.java` — crops the figures Mistral found (signatures, stamps) out of the scan
- `service/FamilyTreeService.java` — read model of the family tree: search, kinship, people
- `genealogy/GenealogyService.java` — the only writer of the genealogy: validation and the `gen_change` audit
- `genealogy/GenealogyHistoryService.java` — merges duplicate people, undoes a write
- `genealogy/GedcomExporter.java` — GEDCOM 7 export
- `genealogy/MediaImages.java` — a media item's image: the stored original or its lossless crop
```

- [ ] **Step 2: Document the tools and endpoints in the archiver-api skill**

In `.claude/skills/archiver-api/SKILL.md`, replace:

```markdown
| `delete_page` | remove one page (a blank or duplicate sheet) by `recordId` and `seq`; the pages after it close the gap. Cannot be undone; a reason is required |
```

with:

```markdown
| `delete_page` | remove one page (a blank or duplicate sheet) by `recordId` and `seq`; the pages after it close the gap. Cannot be undone; a reason is required |
| `add_person`, `update_person`, `add_name`, `add_event`, `update_event`, `link_parent`, `add_couple`, `cite`, `link_record`, `add_media`, `merge_persons`, `undo_change`, `tree_changes` | edit the family tree. Every call needs a reason and is one undoable write; `get_person` shows the ids they take |
```

In `.claude/skills/archiver-api/SKILL.md`, replace:

```markdown
The last three are administrators only and fail closed: no provable
```

with:

```markdown
The last three, and every tree-editing tool, are administrators only and fail closed: no provable
```

In `.claude/skills/archiver-api/SKILL.md`, replace:

```markdown
## PDFs
```

with:

```markdown
## Editing the family tree

The tree is in the database; the text file is only V22's input. Prefer the MCP tools. The REST
API under `/api/admin/genealogy` (admin token) does the same. Every write carries `"reason"` in
its JSON body (on `DELETE` too).

| Call | Does |
|---|---|
| `POST /persons` `{"given":…,"surname":…,"sex":"M"\|"F"\|"U","reason":…}` | a new person (ids from 349) with their primary name |
| `POST /persons/{id}/names` | another name (birth, married, alias, religious, title, other) |
| `POST`, `PUT /{id}`, `DELETE /{id}` on `names`, `events`, `couples`, `parent-links`, `places`, `place-names`, `sources`, `citations`, `record-links`, `media`, `media-people` | create, change, delete. Events take a GEDCOM 7 `dateText` (`12 MAR 1890`, `ABT 1890`, `BET 1657 AND 1664`) and `placeName` or `placeId`. `media` takes `pageId`, an optional crop in pixels of the stored scan, and `personId`/`isPortrait` |
| `POST /persons/{keep}/merge/{gone}` | moves every link of the duplicate to `keep` |
| `GET /changes?personId=…` · `POST /changes/{id}/undo` | the audit; undo the whole write that a change belongs to (409 if anything it touched has changed since) |
| `GET /review` | what the import or later editing left to check (`needs_review`) |
| `GET /export.ged?living=exclude\|include` | GEDCOM 7 (living people left out by default) |

Refusals are `{"error":…}`: 400 for bad input, 404 when something is missing, 409 for a conflict
(a cycle, a third birth parent, deleting someone still linked: merge instead).

## PDFs
```

- [ ] **Step 3: Run every local check**

Run: `make test-backend`

Expected: BUILD SUCCESSFUL: every backend test passes, including the unchanged ProfileControllerTest, AuthControllerTest, PageMoveTest, PipelineStateMachineTest, PipelineAuditTest and PersonMatchServiceTest assertions

Run: `make lint-backend`

Expected: BUILD SUCCESSFUL (spotlessCheck)

Run: `cd frontend && bun run check && cd .. && make test-frontend`

Expected: `0 ERRORS`; vitest PASS; the i18n check exits 0

`make lint-frontend` fails before and after this work, because the repo has no ESLint config. That is the known pre-existing gap; do not add a config in this branch.

- [ ] **Step 4: Commit**

```bash
git add -A CLAUDE.md .claude/skills/archiver-api/SKILL.md
git commit -m "docs: the genealogy tables, edit API and MCP tree tools"
```

- [ ] **Step 5: Rehearse the migrations on the test stack (no production writes)**

V21–V23 are this release's risk, and V23 rewrites production rows (`page_person_match`, `app_user`). Ask the user before pushing anything. Then rehearse:

1. Refresh the test database from production with the full catalogue, so that V23 meets production's real match rows: `cd deploy && ./seed-test-db.sh`. Its defaults point at the LAN hosts, and it refuses to restore into production.
2. With the user's go-ahead, push to `main` untagged. Jenkins builds the `:test` images, which only the test stack runs. Wait on the build with `jk run ls archiver | head -2`, then `jk run view archiver <n> --wait`; never `jk run start`, never `docker push`.
3. Check the test backend's log for Flyway applying `21`, `22 - Import legacy genealogy` and `23` without error: `ssh zelkova 'docker logs archiver-test-backend-test-1 2>&1 | grep -E "Migrating schema|Successfully applied"'`. ssh is the only path to the test stack's container.

- [ ] **Step 6: Record what to compare before the release**

Using the archive MCP (`claude_ai_Cz_Archives`), run `find_people_in_document` on records 3796, 4082 and 4083, and `get_person` on 324. Save the output. These are the "before" for the checks after the release.

- [ ] **Step 7: Release (the user tags; CI builds)**

Only the user decides to release. When they do:

1. This release migrates, so take a production backup first, from a file (see the pitfall in the backup notes): `scp deploy/backup.sh zelkova:/tmp/ && ssh zelkova 'bash /tmp/backup.sh'`. Then prove it restorable: `scp deploy/restore.sh zelkova:/tmp/ && ssh zelkova 'bash /tmp/restore.sh /mnt/ash-backups/archiver/latest --verify'`.
2. The user tags the release on its own new commit (see "After a release" in the archiver-api skill). Wait for the Jenkins build to report SUCCESS with `jk run view archiver <n> --wait`.
3. Redeploy with `curl -X POST https://docker.icomb.place/api/stacks/webhooks/b7e3a1d2-5f4c-4e8a-9b1d-3c6f8a2e4d71`. Wait 20 seconds. `curl -s -o /dev/null -w "%{http_code}" https://archive.czernin.eu/api/version` must print `200`, and `ssh zelkova 'docker ps --filter name=archiver --format "{{.Names}}\t{{.Status}}"'` must show no container restarting. If either check fails, read the backend log before anything else.

- [ ] **Step 8: Verify on production through the public API (never psql)**

Use the admin token over the public domain, as in the archiver-api skill ("Auth"): `ADMIN_TOKEN` from the Portainer stack env (stack 183), read with your Portainer API key. First check that every one of the 346 legacy people has their old name with the family surname, by the same rule as `FamilyTreeGolden.expectedDisplay`. The export with `living=include` holds every person, primary name first:

```bash
curl -s -H "Authorization: Bearer $atok" \
  "https://archive.czernin.eu/api/admin/genealogy/export.ged?living=include" -o /tmp/czernin-all.ged
python3 - <<'EOF'
import re
names, cur = {}, None
for line in open('/tmp/czernin-all.ged', encoding='utf-8'):
    line = line.rstrip('\n')
    m = re.match(r'0 @I(\d+)@ INDI$', line)
    if m:
        cur = int(m.group(1))
        continue
    if line.startswith('0 '):
        cur = None
    m = re.match(r'1 NAME (.*?) ?/(.*)/$', line)
    if cur is not None and m and cur not in names:
        names[cur] = ' '.join(p for p in (m.group(1), m.group(2)) if p)
inherited = {277, 278, 289, 290, 291, 292, 293, 294}
bad = 0
for row in open('backend/src/test/resources/genealogy/golden/people.tsv', encoding='utf-8'):
    c = row.rstrip('\n').split('\t')
    legacy = c[8]
    if 'Czernin' in legacy or int(c[0]) in inherited:
        want = re.sub(r'(\s*\([^)]*\))+\s*$', '', legacy).strip()
    else:
        want = legacy + ' Czernin'
    if names.get(int(c[0])) != want:
        bad += 1
        print('MISMATCH', c[0], repr(want), repr(names.get(int(c[0]))))
print('mismatches:', bad)
EOF
```

Expected: `mismatches: 0`. Then:

1. The applicant's "this is me" still names the same person. Signed in as the applicant, `/family-tree?personId=324` shows "This is you", and `get_person` 324 matches the saved output.
2. Page matches still point at the right people: `find_people_in_document` on 3796, 4082 and 4083 gives the same people and names as the saved output.
3. Make one real edit through MCP, check its audit row, and undo it. Find Diana Maria ("Diana Hippoliti da Gazolo", the wife of Humprecht Jan). She was imported as given `Diana`, surname `Maria` (decision 4), and her id is above 348: `awk '/^0 @I[0-9]+@ INDI/{id=$2} /^1 NAME Diana \/Maria\//{print id}' /tmp/czernin-all.ged`. Then:
   - `add_name` her full name (`type` `birth`, `given` `Diana Maria`, `surname` `Hippoliti da Gazolo`, `isPrimary` true), with a reason.
   - `cite` that name (`nameId` from the result) with `recordId` 4082, `seq` 1, `quality` 3.
   - `tree_changes` with her `personId` must show the new name, the demoted old primary name, the source made for record 4082 and the citation.
   - `undo_change` the citation's change id, then the name's change id, each with a reason. `get_person` must show her name exactly as before.
4. Export and check: `curl -s -H "Authorization: Bearer $atok" "https://archive.czernin.eu/api/admin/genealogy/export.ged?living=exclude" -o /tmp/czernin.ged`. `head -3 /tmp/czernin.ged` must show `0 HEAD`, `1 GEDC`, `2 VERS 7.0`; `tail -1 /tmp/czernin.ged` must be `0 TRLR`; and the file must load in a GEDCOM 7 validator (for example FamilySearch's GEDCOM 7 validator at gedcom.io) without structural errors.

---

## Spec coverage

| Spec requirement | Task |
|---|---|
| `gen_*` tables, constraints, FK delete rules, sequence above 348 | 2 |
| GEDCOM 7 date grammar, derived bounds | 3, 7 |
| Legacy import: ids 1–348, roots folded, spouses from 349, couples, `1m:`/`2m:` order, `(div)`, `[1m.]` and "all children by 1m.", titles, date conversion, places per spelling, three adoption spellings, one source and quality-1 citations, `legacy_entry`, `needs_review` | 4, 5 |
| V23 remap and foreign keys | 5 |
| `FamilyTreeService` read model, same methods and shapes; text file and reload removed; reload/invalidate endpoints 404 | 6 |
| Kinship over birth links plus spouse and in-law labels; golden file | 1, 6 |
| `GenealogyService` CRUD for every entity, reason, validation (cycles, two birth parents, date grammar, one citation target, crop inside image, referenced delete 409), one audit row per entity | 7 |
| `mergePersons` re-pointing every link incl. `page_person_match` and `app_user`; `undo` refusing after a change | 8 |
| Admin REST under `/api/admin/genealogy/` incl. `changes`, `changes/{id}/undo`, `review`, `export.ged` | 9, 12 |
| Read additions on `/api/family-tree/person/{id}`; `GET /media/{id}/image` crop (lossless PNG, original bytes for whole) | 6, 10 |
| MCP write tools (13), fail closed, reason; `get_person` read fields | 11 |
| GEDCOM 7 export: HEAD/GEDC 7.0, INDI with `_UID`, NAME parts, events, FAMC/FAMS, FAM with CHIL/PEDI, SOUR/PAGE/QUAY, OBJE/FILE/CROP, `living=exclude` | 12 |
| Web: portrait, names, structured events with sources, spouses, linked records, media; admin edit mode, pickers, crop selector, history with undo, review list; Verdant, no inline styles, en/de/cs | 13, 14 |
| Security tests: USER and PROCESSOR refused on every admin endpoint and MCP tool | 9, 11 |
| Regression: profile, auth, page-move, pipeline, person-match | 6, 15 |
| Release and verification steps | 15 |
