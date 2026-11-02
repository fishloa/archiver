# Editable genealogy tree in the database

Status: approved in conversation 2026-11-01/02; this document awaits written review.

## Goal

Replace the read-only family tree parsed at startup from `backend/src/main/resources/czernin-genealogy.txt`
with a genealogy stored in PostgreSQL, editable by administrators through the web UI and MCP tools,
evidence-backed where it matters (the applicant's direct line and the persecuted cousins), and
exportable as GEDCOM 7.

## Decisions taken

| Question | Decision |
|---|---|
| Purpose | Both a family-history tree and an evidence-backed genealogy; sources are optional in general and expected on the direct line and the persecuted cousins |
| Who edits | Administrators only (`ROLE_ADMIN`) |
| Interchange | GEDCOM 7 **export** only, plus a one-time import of the legacy text file. No general GEDCOM import; the archive is the master copy |
| Data model | Relational, after the GEDCOM X conceptual model and the Gramps schema, using GEDCOM 7 vocabulary (event tags, date grammar, `QUAY`, `OBJE`/`CROP`, `_UID`) |
| Editing surfaces | Web UI and MCP tools, both over one service |
| Person matching | Out of scope. It keeps working as it does today, reading people from the database; redesigning it is a separate follow-up project |

## Public references

- GEDCOM 7.0 specification (FamilySearch): https://gedcom.io/specifications/FamilySearchGEDCOMv7.html.
  It is the source for event tags, the date grammar (`ABT`, `BEF`, `AFT`, `BET … AND …`, `FROM … TO …`,
  calendar months), `QUAY` 0–3, `OBJE` with `CROP` (`TOP`, `LEFT`, `HEIGHT`, `WIDTH` in pixels), `_UID`,
  and `FAM` with `HUSB`/`WIFE`/`CHIL` and `PEDI` (birth, adopted, foster, sealing, other).
- GEDCOM X conceptual model: https://github.com/FamilySearch/gedcomx/blob/master/specifications/conceptual-model-specification.md.
  It supplies Person, Name and NameForm, Fact, the Couple and ParentChild relationships,
  SourceDescription with SourceReference, and the separation of evidence from conclusion.
- Gramps data model: https://gramps-project.org/wiki/index.php/Gramps_Data_Model.
  It contributes hierarchical places with names valid per date range, citations attached to any
  object, and media references with a rectangle on the image.

## Current state (what is replaced)

- `FamilyTreeService` parses the text file into memory on startup. Person ids are parse-order
  counters, so editing the file renumbers everyone. Spouses are text inside a line. Which marriage a
  child came from is dropped, and adoptions are written three different ways.
- Stored references to person ids: `page_person_match.person_id` and `app_user.family_tree_person_id`.
  Neither has a foreign key, and only the first is cleaned up on reload (by truncating it).
- `POST /api/family-tree/reload` and `/invalidate-matches` are open to any signed-in user. That is a
  security defect, and this work removes both endpoints.
- The consumers are `FamilyTreeController` (`/api/family-tree/search`, `/relate`, `/person/{id}`,
  `/page-matches/{pageId}`, `/record-matches/{recordId}`), the MCP tools `search_family_tree`,
  `get_person` and `find_people_in_document`, `PersonMatchService`, `ProfileController`,
  `AuthController`, and the frontend route `/family-tree` and record pages.

## Data model (migration V21)

All tables are prefixed `gen_`. Every table has `created_at` and `updated_at`.

| Table | Columns | Rules |
|---|---|---|
| `gen_person` | `id int PK`, `uid uuid UNIQUE NOT NULL`, `sex char(1) CHECK (M,F,U)`, `living bool NOT NULL DEFAULT false`, `notes text`, `legacy_entry text`, `needs_review text`, `merged_into int REFERENCES gen_person` | Ids 1–348 are the legacy parse-order ids. The sequence starts above the maximum. A merged person keeps its row with `merged_into` set, so old links resolve |
| `gen_name` | `id`, `person_id FK`, `type CHECK (birth, married, alias, religious, title, other)`, `given`, `surname`, `prefix`, `suffix`, `nickname`, `is_primary bool`, `sort_order int` | Exactly one primary name per person, enforced with a partial unique index |
| `gen_couple` | `id`, `person1_id FK`, `person2_id FK`, `type CHECK (marriage, partnership, unknown)`, `sort_order int` (1m, 2m…), `notes` | `person1_id <> person2_id` |
| `gen_parent_child` | `id`, `parent_id FK`, `child_id FK`, `type CHECK (birth, adopted, step, foster, unknown)`, `couple_id FK NULL` | Unique (parent, child). A child has at most two `birth` parents. No cycles (checked by the service) |
| `gen_place` | `id`, `name`, `parent_id FK NULL`, `lat`, `lon` | Hierarchy, e.g. Dymokury → okres Nymburk → Bohemia |
| `gen_place_name` | `id`, `place_id FK`, `name`, `lang char(2)`, `from_year`, `to_year` | Alternative names such as Wien/Vienna and Hlušice/Bluschitz |
| `gen_event` | `id`, `person_id FK NULL`, `couple_id FK NULL`, `type` (GEDCOM 7 tag: BIRT, CHR, BAPM, DEAT, BURI, MARR, DIV, ADOP, OCCU, TITL, RESI, NATU, EMIG, IMMI, EDUC, EVEN), `date_text`, `date_from date NULL`, `date_to date NULL`, `place_id FK NULL`, `description`, `sort_order` | Exactly one of person or couple. `date_text` must parse as a GEDCOM 7 date. `date_from`/`date_to` are derived bounds for sorting and search |
| `gen_source` | `id`, `title NOT NULL`, `author`, `record_id FK record NULL`, `url`, `notes` | |
| `gen_citation` | `id`, `source_id FK`, `page_id FK page NULL`, `detail`, `quality smallint CHECK 0–3`, `event_id`/`name_id`/`couple_id`/`parent_child_id`/`person_id` | Exactly one target, enforced by CHECK. A citation proves a fact |
| `gen_person_record` | `id`, `person_id FK`, `record_id FK record`, `page_id FK page NULL`, `role CHECK (subject, mentioned, author, signatory, witness, recipient, pictured, other)`, `note`, `quality smallint 0–3` | "This person appears in this document". It does not prove a specific fact |
| `gen_media` | `id`, `page_id FK page NOT NULL`, `crop_left`, `crop_top`, `crop_width`, `crop_height` (int pixels of the stored original, all NULL means the whole page), `caption`, `date_text` | GEDCOM 7 `OBJE` with `CROP` |
| `gen_media_person` | `media_id FK`, `person_id FK`, `is_portrait bool`, `sort_order` | One photo can show several people. At most one portrait per person |
| `gen_change` | `id bigserial`, `entity text`, `entity_id bigint`, `op CHECK (create, update, delete, merge, undo)`, `before jsonb`, `after jsonb`, `reason text NOT NULL`, `changed_by text NOT NULL`, `changed_at timestamptz` | Every write writes exactly one row per entity changed |

Foreign keys onto `record` and `page` use `ON DELETE SET NULL` for optional links and `CASCADE` for
`gen_media`, so deleting a page through the existing page tools stays possible. The `gen_change` row
survives.

## Legacy import (V22, Java Flyway migration)

- It reuses the existing parser so that ids are reproduced exactly. A test asserts that every id from
  1 to 348 has the same name as the in-memory tree did, and records the two dropped section-root ids.
- Indentation becomes `gen_parent_child` of type `birth`. The `>>> CONTINUES IN` cross-section
  duplicates (Johann Rudolf, Wolfgang Maria) collapse to one person, as today.
- Spouse text becomes a new person (from 349 upward), a `gen_couple`, a `MARR` event (with place and
  date when present) and the spouse's own `BIRT`/`DEAT` from the parentheses. `1m:`/`2m:` give couple
  order, `(div YEAR)` gives a `DIV` event, and `[1m.]` or "all children by 1m." set `couple_id` on the
  children.
- Titles (`Graf`, `Gfn`, `Pss`, `Frn`, `Freiherr`, `cr Graf …`) go into `prefix` or into `TITL`
  events.
- Dates convert to GEDCOM 7: `ca` becomes `ABT`, `after` becomes `AFT`, `before` becomes `BEF`,
  `1657/64` becomes `BET 1657 AND 1664`, Roman-numeral months become `APR` and so on, and
  `d.m.yyyy` becomes `D MMM YYYY`. Text that cannot be read as a date (`young`, `k.a. …`, `m.i.a. …`,
  `as Ursuline nun …`) goes into `description` with `date_text` set to null or the year part.
- Places get one `gen_place` per distinct spelling, with no automatic merging.
- The three adoption spellings become `gen_parent_child` type `adopted`, linked to the adopter when
  the adopter is in the tree. Otherwise they are recorded as an `ADOP` event with the text.
- Provenance: one `gen_source` titled "genealogy.euweb.cz Czernin pages (legacy text file)". Every
  imported event and name cites it at quality 1. `legacy_entry` keeps the original line.
- Anything not parsed cleanly is imported as text with `needs_review` set to the reason, for example
  conflicting dates (Maria Antonia 1766 vs 1776) and uncertain names ("z Gutštejna?").

## Constraints on existing tables (V23)

- `page_person_match.person_id` gets `REFERENCES gen_person(id)`.
- `app_user.family_tree_person_id` gets `REFERENCES gen_person(id) ON DELETE SET NULL`.
- Rows pointing at the two dropped section-root ids, if any, are remapped to the surviving person
  before the constraint is added.

## Services and interfaces

- **`GenealogyService`** is the only writer. Each operation is `@Transactional`, takes a non-blank
  reason, validates, writes, and appends `gen_change`. Errors use the existing style: a typed
  exception mapped to 400, 404 or 409 with `{"error": …}`.
- Write operations:
  - Create, update and delete for every `gen_*` entity.
  - `mergePersons(keepId, goneId, reason)`: re-points every link (names, events, couples, parent
    links, citations, record links, media, `page_person_match`, `app_user`), then sets `merged_into`.
  - `undo(changeId, reason)`: applies the stored `before` state, refusing if the entity has changed
    since.
- Validation:
  - No cycles in parent links.
  - At most two birth parents.
  - GEDCOM 7 date grammar.
  - Exactly one citation target.
  - Crop rectangle inside the page image's pixel dimensions.
  - Deleting a person who is still referenced is refused (409); use merge.
- **`FamilyTreeService`** becomes a read model over the tables. It keeps its public methods and
  response shapes, so `FamilyTreeController`, the MCP read tools, `ProfileController` and
  `PersonMatchService` do not change behaviour. The text file and the reload code are deleted.
- **Kinship** uses the same algorithm, labels and response fields as today, computed over
  `gen_parent_child` type `birth`. It adds spouse and in-law labels where the reference and the
  person are joined by a couple.
- **REST**, admin only, under `/api/admin/genealogy/`:
  - `persons`, `persons/{id}/names`, `events`, `couples`, `parent-links`, `places`, `sources`,
    `citations`, `record-links`, `media`.
  - `persons/{keep}/merge/{gone}`.
  - `changes` (list, with filter by entity), `changes/{id}/undo`.
  - `review` (the `needs_review` list).
  - `export.ged?living=exclude|include`.
- **Read additions** for any signed-in user:
  - `/api/family-tree/person/{id}` gains `names[]`, `events[]` (structured), `couples[]` (with
    spouse id), `parentLinks[]` (with type), `recordLinks[]`, `media[]`, `portrait`, `sources[]`.
  - `GET /api/family-tree/media/{id}/image` returns the crop.
- **Crop**: a whole-image media item (no crop) returns the stored original bytes untouched. A
  cropped region is decoded from the stored original, its pixels are copied without any scaling, and
  it is encoded as lossless PNG. Nothing derived is stored. Reuse `OcrImageService`'s cropping where
  it fits.
- **MCP write tools**, administrators only, failing closed like `delete_page`, with `reason`
  required on each:
  - `add_person`, `update_person`, `add_name`, `add_event`, `update_event`, `link_parent`,
    `add_couple`, `cite`, `link_record`, `add_media`, `merge_persons`, `undo_change`, `tree_changes`.
  - `get_person` gains the new read fields.
- **Web**:
  - The `/family-tree` person page shows the portrait, names, structured events with sources,
    spouses, linked records and media.
  - Admins see an Edit mode: inline forms, person, record and page pickers, a crop selector drawn on
    the page image, a per-person history with undo, and a review list.
  - Verdant design-system classes, no inline styles, and i18n keys in en, de and cs.
- **GEDCOM 7 export**:
  - Header `GEDC VERS 7.0`.
  - `INDI` with `_UID`, `NAME` and its parts, events with `DATE`/`PLAC`, `FAMC`/`FAMS`.
  - `FAM` from couples, with `CHIL` and `PEDI`.
  - `SOUR` and citations with `PAGE` and `QUAY`.
  - `OBJE` with `FILE` (the archive image URL) and `CROP`.
  - With `living=exclude`, living people are omitted along with any family that contains only
    living people.

## Out of scope

- Person matching improvements. Matching keeps its current behaviour over the new read model. A
  separate project follows once the tree data is clean.
- GEDCOM import.
- Non-admin editing.
- Automatic place merging.

## Testing

All tests use Testcontainers with Java HttpClient and are written test-first.

1. **Import**
   - Ids 1–348 have the same names as before.
   - Alexander gets birth, death, marriage to Diana, and parents.
   - Jaromír has three couples with DIV events.
   - The adoptions become `adopted`.
   - `ABT`, `BET` and Roman-month dates convert.
   - The `needs_review` list is non-empty and names the known conflicts.
   - Every imported fact has a legacy citation.
2. **Kinship** gives labels identical to the pre-migration output for a fixed set of pairs (captured
   before the change as a golden file), plus the new spouse and in-law labels.
3. **Edit rules**
   - A reason is required.
   - A cycle, a third birth parent, an invalid date, a crop outside the image, or a delete of a
     referenced person is refused with the correct status.
   - Merge re-points every link type, including `page_person_match` and `app_user`.
   - Undo restores the prior state and is refused after an intervening change.
   - Exactly one audit row per entity written.
4. **Security**: every admin REST endpoint and MCP write tool refuses USER and PROCESSOR. The
   `/api/family-tree/reload` and `/invalidate-matches` endpoints return 404.
5. **Export** is structurally valid GEDCOM 7, verified with a validator library or a checked-in
   golden file. Crop, citation and `QUAY` are present. Living people are excluded when asked.
6. **Media**: the crop endpoint returns exactly the original pixels of the region, compared byte for
   byte to a fixture. A whole-image request returns the stored original bytes unchanged.
7. **Regression**: the existing profile, auth, page-move, pipeline and person-match tests pass
   unchanged.

## Release and verification

1. Run the full local checks first: `make test-backend`, `make lint`, `bun run check` and
   `make test-frontend`. Frontend ESLint currently fails because the repo has no config; that is a
   known pre-existing gap.
2. Before release, take a production backup with `deploy/backup.sh`, because this release contains
   migrations.
3. Release by tag, then check the site returns 200 and the containers are healthy.
4. On production, verify:
   - 348 legacy ids resolve with the right names.
   - The applicant's `family_tree_person_id` resolves to the same person.
   - `page_person_match` rows still point at the right people.
5. Make one real edit through MCP (Diana Hippoliti da Gazolo's full name, citing record 4082 p. 1),
   check its audit row, and undo it.
6. Export GEDCOM and validate the file.
