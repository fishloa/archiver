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
