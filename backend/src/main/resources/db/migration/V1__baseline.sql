-- Baseline schema — the squash of V1..V26.
--
-- Generated from production's live schema on 2026-09-08 (pg_dump --schema-only), not from
-- the migrations, after verifying the two were identical apart from one hand-created index.
-- That verification is the point of the exercise: a baseline freezes whichever version it is
-- taken from, so taking it from an unverified source would silently canonise drift.
--
-- The one difference found was idx_text_chunk_embedding, the HNSW vector index, created by
-- hand against production and never written as a migration. It is included below, so CI and
-- every fresh environment now build it too — previously they had no vector index at all and
-- silently fell back to sequential scans.
--
-- flyway_schema_history is deliberately absent: Flyway creates and owns that table.
--
-- Existing databases are re-baselined (flyway baseline -baselineVersion=1) rather than having
-- this run against them. V2..V26 remain in git history; nothing is lost.

--
-- PostgreSQL database dump
--

-- Dumped from database version 18.6 (Debian 18.6-1.pgdg12+2)
-- Dumped by pg_dump version 18.4 (Debian 18.4-1.pgdg12+1)

--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_trgm IS 'text similarity measurement and index searching based on trigrams';

--
-- Name: unaccent; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;

--
-- Name: EXTENSION unaccent; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION unaccent IS 'text search dictionary that removes accents';

--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';

--
-- Name: immutable_to_tsvector(regconfig, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.immutable_to_tsvector(regconfig, text) RETURNS tsvector
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $_$
  SELECT to_tsvector($1, $2)
$_$;

--
-- Name: immutable_unaccent(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.immutable_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $_$
  SELECT unaccent($1)
$_$;

--
-- Name: page_text_close_history(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.page_text_close_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    UPDATE page_ocr_history
       SET superseded_at = now()
     WHERE page_id = OLD.page_id
       AND superseded_at IS NULL;
    RETURN OLD;
END;
$$;

--
-- Name: page_text_record_history(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.page_text_record_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    UPDATE page_ocr_history
       SET superseded_at = now()
     WHERE page_id = NEW.page_id
       AND superseded_at IS NULL;

    INSERT INTO page_ocr_history
           (page_id, engine, confidence, content_type, chars, ocr_at, superseded_at)
    VALUES (NEW.page_id, NEW.engine, NEW.confidence, NEW.content_type,
            length(NEW.text_raw), NEW.created_at, NULL);

    RETURN NEW;
END;
$$;

--
-- Name: app_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_user (
    id bigint NOT NULL,
    display_name text,
    role text DEFAULT 'user'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    lang character varying(2) DEFAULT 'en'::character varying,
    family_tree_person_id integer,
    CONSTRAINT app_user_lang_check CHECK (((lang)::text = ANY ((ARRAY['en'::character varying, 'de'::character varying])::text[]))),
    CONSTRAINT app_user_role_check CHECK ((role = ANY (ARRAY['user'::text, 'admin'::text])))
);

--
-- Name: app_user_email; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_user_email (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    email text NOT NULL
);

--
-- Name: app_user_email_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.app_user_email_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: app_user_email_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.app_user_email_id_seq OWNED BY public.app_user_email.id;

--
-- Name: app_user_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.app_user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: app_user_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.app_user_id_seq OWNED BY public.app_user.id;

--
-- Name: archive; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.archive (
    id bigint NOT NULL,
    name text NOT NULL,
    country text,
    citation_template text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: archive_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.archive_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: archive_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.archive_id_seq OWNED BY public.archive.id;

--
-- Name: attachment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.attachment (
    id bigint NOT NULL,
    record_id bigint NOT NULL,
    role text NOT NULL,
    path text NOT NULL,
    sha256 text,
    mime text,
    bytes bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: attachment_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.attachment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: attachment_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.attachment_id_seq OWNED BY public.attachment.id;

--
-- Name: collection; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.collection (
    id bigint NOT NULL,
    archive_id bigint NOT NULL,
    name text NOT NULL,
    code text,
    raw_source_metadata jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: collection_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.collection_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: collection_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.collection_id_seq OWNED BY public.collection.id;

--
-- Name: entity_hit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.entity_hit (
    id bigint NOT NULL,
    page_id bigint NOT NULL,
    entity_type text NOT NULL,
    value text NOT NULL,
    confidence real,
    start_offset integer,
    end_offset integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: entity_hit_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.entity_hit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: entity_hit_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.entity_hit_id_seq OWNED BY public.entity_hit.id;

--
-- Name: evidence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.evidence (
    id bigint NOT NULL,
    entity_hit_id bigint NOT NULL,
    page_id bigint NOT NULL,
    snippet text,
    bounding_box jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: evidence_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.evidence_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: evidence_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.evidence_id_seq OWNED BY public.evidence.id;

--
-- Name: job; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.job (
    id bigint NOT NULL,
    kind text NOT NULL,
    record_id bigint,
    page_id bigint,
    payload jsonb,
    status text DEFAULT 'pending'::text NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    CONSTRAINT job_kind_check CHECK ((kind = ANY (ARRAY['ocr_page_paddle'::text, 'ocr_page_abbyy'::text, 'ocr_page_qwen3vl'::text, 'ocr_page_claude'::text, 'ocr_page_mistral'::text, 'build_searchable_pdf'::text, 'extract_entities'::text, 'generate_thumbs'::text, 'translate_page'::text, 'translate_record'::text, 'embed_record'::text, 'match_persons'::text]))),
    CONSTRAINT job_status_check CHECK ((status = ANY (ARRAY['pending'::text, 'claimed'::text, 'completed'::text, 'failed'::text])))
);

--
-- Name: job_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.job_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: job_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.job_id_seq OWNED BY public.job.id;

--
-- Name: oauth2_authorization; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oauth2_authorization (
    id character varying(100) NOT NULL,
    registered_client_id character varying(100) NOT NULL,
    principal_name character varying(200) NOT NULL,
    authorization_grant_type character varying(100) NOT NULL,
    authorized_scopes character varying(1000) DEFAULT NULL::character varying,
    attributes text,
    state character varying(500) DEFAULT NULL::character varying,
    authorization_code_value text,
    authorization_code_issued_at timestamp with time zone,
    authorization_code_expires_at timestamp with time zone,
    authorization_code_metadata text,
    access_token_value text,
    access_token_issued_at timestamp with time zone,
    access_token_expires_at timestamp with time zone,
    access_token_metadata text,
    access_token_type character varying(100) DEFAULT NULL::character varying,
    access_token_scopes character varying(1000) DEFAULT NULL::character varying,
    oidc_id_token_value text,
    oidc_id_token_issued_at timestamp with time zone,
    oidc_id_token_expires_at timestamp with time zone,
    oidc_id_token_metadata text,
    refresh_token_value text,
    refresh_token_issued_at timestamp with time zone,
    refresh_token_expires_at timestamp with time zone,
    refresh_token_metadata text,
    user_code_value text,
    user_code_issued_at timestamp with time zone,
    user_code_expires_at timestamp with time zone,
    user_code_metadata text,
    device_code_value text,
    device_code_issued_at timestamp with time zone,
    device_code_expires_at timestamp with time zone,
    device_code_metadata text
);

--
-- Name: oauth2_authorization_consent; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oauth2_authorization_consent (
    registered_client_id character varying(100) NOT NULL,
    principal_name character varying(200) NOT NULL,
    authorities character varying(1000) NOT NULL
);

--
-- Name: oauth2_registered_client; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oauth2_registered_client (
    id character varying(100) NOT NULL,
    client_id character varying(100) NOT NULL,
    client_id_issued_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret character varying(200) DEFAULT NULL::character varying,
    client_secret_expires_at timestamp with time zone,
    client_name character varying(200) NOT NULL,
    client_authentication_methods character varying(1000) NOT NULL,
    authorization_grant_types character varying(1000) NOT NULL,
    redirect_uris character varying(1000) DEFAULT NULL::character varying,
    post_logout_redirect_uris character varying(1000) DEFAULT NULL::character varying,
    scopes character varying(1000) NOT NULL,
    client_settings character varying(2000) NOT NULL,
    token_settings character varying(2000) NOT NULL
);

--
-- Name: page; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.page (
    id bigint NOT NULL,
    record_id bigint NOT NULL,
    seq integer NOT NULL,
    attachment_id bigint NOT NULL,
    page_label text,
    width integer,
    height integer,
    source_url text
);

--
-- Name: page_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.page_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: page_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.page_id_seq OWNED BY public.page.id;

--
-- Name: page_ocr_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.page_ocr_history (
    id bigint NOT NULL,
    page_id bigint NOT NULL,
    engine text NOT NULL,
    confidence real,
    content_type text NOT NULL,
    chars integer NOT NULL,
    ocr_at timestamp with time zone NOT NULL,
    superseded_at timestamp with time zone
);

--
-- Name: TABLE page_ocr_history; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.page_ocr_history IS 'One row per OCR run, including runs whose text has since been replaced. Carries no text — page_text holds the current transcription. superseded_at IS NULL identifies the run that produced it.';

--
-- Name: page_ocr_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.page_ocr_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: page_ocr_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.page_ocr_history_id_seq OWNED BY public.page_ocr_history.id;

--
-- Name: page_person_match; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.page_person_match (
    id bigint NOT NULL,
    page_id bigint NOT NULL,
    person_id integer NOT NULL,
    person_name text NOT NULL,
    score real NOT NULL,
    context text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: page_person_match_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.page_person_match_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: page_person_match_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.page_person_match_id_seq OWNED BY public.page_person_match.id;

--
-- Name: page_search; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.page_search (
    id bigint NOT NULL,
    page_id bigint NOT NULL,
    best_engine text NOT NULL,
    best_text_norm text NOT NULL,
    tsv tsvector GENERATED ALWAYS AS (public.immutable_to_tsvector('simple'::regconfig, best_text_norm)) STORED
);

--
-- Name: page_search_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.page_search_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: page_search_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.page_search_id_seq OWNED BY public.page_search.id;

--
-- Name: page_text; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.page_text (
    id bigint NOT NULL,
    page_id bigint NOT NULL,
    engine text NOT NULL,
    confidence real,
    text_raw text NOT NULL,
    text_norm text GENERATED ALWAYS AS (public.immutable_unaccent(lower(text_raw))) STORED,
    hocr text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    text_en text,
    content_type text DEFAULT 'text/plain'::text NOT NULL,
    raw_response jsonb,
    CONSTRAINT page_text_content_type_check CHECK ((content_type ~ '^[a-z]+/[a-z0-9.+-]+$'::text))
);

--
-- Name: COLUMN page_text.content_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.page_text.content_type IS 'IANA media type of text_raw, e.g. text/plain or text/markdown. Set by the producing OCR worker; consumers must not infer it from the content.';

--
-- Name: COLUMN page_text.raw_response; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.page_text.raw_response IS 'Verbatim JSON response from the OCR engine, where the engine returns structure beyond the text (Mistral OCR: blocks with bounding boxes, dimensions, tables). Null for engines that return only text.';

--
-- Name: page_text_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.page_text_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: page_text_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.page_text_id_seq OWNED BY public.page_text.id;

--
-- Name: pipeline_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pipeline_event (
    id bigint NOT NULL,
    record_id bigint NOT NULL,
    stage text NOT NULL,
    event text NOT NULL,
    detail text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pipeline_event_event_check CHECK ((event = ANY (ARRAY['started'::text, 'completed'::text, 'failed'::text, 'admin_reset'::text, 'replace_started'::text, 'repair_started'::text])))
);

--
-- Name: pipeline_event_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pipeline_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: pipeline_event_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.pipeline_event_id_seq OWNED BY public.pipeline_event.id;

--
-- Name: processing_run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.processing_run (
    id bigint NOT NULL,
    job_id bigint NOT NULL,
    worker_id text,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    status text DEFAULT 'running'::text NOT NULL,
    error text,
    metrics jsonb,
    CONSTRAINT processing_run_status_check CHECK ((status = ANY (ARRAY['running'::text, 'completed'::text, 'failed'::text])))
);

--
-- Name: processing_run_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.processing_run_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: processing_run_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.processing_run_id_seq OWNED BY public.processing_run.id;

--
-- Name: record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.record (
    id bigint NOT NULL,
    archive_id bigint NOT NULL,
    collection_id bigint,
    source_system text NOT NULL,
    source_record_id text NOT NULL,
    title text,
    description text,
    date_range_text text,
    date_start_year integer,
    date_end_year integer,
    reference_code text,
    inventory_number text,
    call_number text,
    container_type text,
    container_number text,
    finding_aid_number text,
    index_terms jsonb,
    raw_source_metadata jsonb,
    pdf_attachment_id bigint,
    attachment_count integer DEFAULT 0 NOT NULL,
    page_count integer DEFAULT 0 NOT NULL,
    status text DEFAULT 'ingesting'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    lang text,
    title_en text,
    description_en text,
    metadata_lang text,
    source_url text,
    CONSTRAINT record_lang_iso CHECK (((lang IS NULL) OR (char_length(lang) = 2))),
    CONSTRAINT record_metadata_lang_iso CHECK (((metadata_lang IS NULL) OR (char_length(metadata_lang) = 2))),
    CONSTRAINT record_status_check CHECK ((status = ANY (ARRAY['ingesting'::text, 'ingested'::text, 'ocr_pending'::text, 'ocr_in_progress'::text, 'ocr_done'::text, 'pdf_pending'::text, 'pdf_done'::text, 'translating'::text, 'embedding'::text, 'matching'::text, 'entities_pending'::text, 'entities_done'::text, 'complete'::text, 'error'::text])))
);

--
-- Name: record_attribute; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.record_attribute (
    id bigint NOT NULL,
    record_id bigint NOT NULL,
    key text NOT NULL,
    value text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: record_attribute_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.record_attribute_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: record_attribute_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.record_attribute_id_seq OWNED BY public.record_attribute.id;

--
-- Name: record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.record_id_seq OWNED BY public.record.id;

--
-- Name: text_chunk; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.text_chunk (
    id bigint NOT NULL,
    record_id bigint NOT NULL,
    page_id bigint,
    chunk_index integer NOT NULL,
    content text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    embedding public.halfvec(1024),
    heading text DEFAULT ''::text NOT NULL,
    content_type text DEFAULT 'text/plain'::text NOT NULL,
    CONSTRAINT text_chunk_content_type_check CHECK ((content_type ~ '^[a-z]+/[a-z0-9.+-]+$'::text))
);

--
-- Name: COLUMN text_chunk.heading; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.text_chunk.heading IS 'Markdown heading path of the section this chunk came from, empty for plain text.';

--
-- Name: text_chunk_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.text_chunk_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: text_chunk_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.text_chunk_id_seq OWNED BY public.text_chunk.id;

--
-- Name: app_user id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user ALTER COLUMN id SET DEFAULT nextval('public.app_user_id_seq'::regclass);

--
-- Name: app_user_email id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user_email ALTER COLUMN id SET DEFAULT nextval('public.app_user_email_id_seq'::regclass);

--
-- Name: archive id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.archive ALTER COLUMN id SET DEFAULT nextval('public.archive_id_seq'::regclass);

--
-- Name: attachment id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.attachment ALTER COLUMN id SET DEFAULT nextval('public.attachment_id_seq'::regclass);

--
-- Name: collection id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.collection ALTER COLUMN id SET DEFAULT nextval('public.collection_id_seq'::regclass);

--
-- Name: entity_hit id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.entity_hit ALTER COLUMN id SET DEFAULT nextval('public.entity_hit_id_seq'::regclass);

--
-- Name: evidence id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evidence ALTER COLUMN id SET DEFAULT nextval('public.evidence_id_seq'::regclass);

--
-- Name: job id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.job ALTER COLUMN id SET DEFAULT nextval('public.job_id_seq'::regclass);

--
-- Name: page id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page ALTER COLUMN id SET DEFAULT nextval('public.page_id_seq'::regclass);

--
-- Name: page_ocr_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_ocr_history ALTER COLUMN id SET DEFAULT nextval('public.page_ocr_history_id_seq'::regclass);

--
-- Name: page_person_match id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_person_match ALTER COLUMN id SET DEFAULT nextval('public.page_person_match_id_seq'::regclass);

--
-- Name: page_search id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_search ALTER COLUMN id SET DEFAULT nextval('public.page_search_id_seq'::regclass);

--
-- Name: page_text id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_text ALTER COLUMN id SET DEFAULT nextval('public.page_text_id_seq'::regclass);

--
-- Name: pipeline_event id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pipeline_event ALTER COLUMN id SET DEFAULT nextval('public.pipeline_event_id_seq'::regclass);

--
-- Name: processing_run id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.processing_run ALTER COLUMN id SET DEFAULT nextval('public.processing_run_id_seq'::regclass);

--
-- Name: record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.record ALTER COLUMN id SET DEFAULT nextval('public.record_id_seq'::regclass);

--
-- Name: record_attribute id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.record_attribute ALTER COLUMN id SET DEFAULT nextval('public.record_attribute_id_seq'::regclass);

--
-- Name: text_chunk id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.text_chunk ALTER COLUMN id SET DEFAULT nextval('public.text_chunk_id_seq'::regclass);

--
-- Name: app_user_email app_user_email_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user_email
    ADD CONSTRAINT app_user_email_email_key UNIQUE (email);

--
-- Name: app_user_email app_user_email_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user_email
    ADD CONSTRAINT app_user_email_pkey PRIMARY KEY (id);

--
-- Name: app_user app_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);

--
-- Name: archive archive_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.archive
    ADD CONSTRAINT archive_pkey PRIMARY KEY (id);

--
-- Name: attachment attachment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.attachment
    ADD CONSTRAINT attachment_pkey PRIMARY KEY (id);

--
-- Name: collection collection_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.collection
    ADD CONSTRAINT collection_pkey PRIMARY KEY (id);

--
-- Name: entity_hit entity_hit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.entity_hit
    ADD CONSTRAINT entity_hit_pkey PRIMARY KEY (id);

--
-- Name: evidence evidence_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evidence
    ADD CONSTRAINT evidence_pkey PRIMARY KEY (id);

--
-- Name: job job_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.job
    ADD CONSTRAINT job_pkey PRIMARY KEY (id);

--
-- Name: oauth2_authorization_consent oauth2_authorization_consent_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth2_authorization_consent
    ADD CONSTRAINT oauth2_authorization_consent_pkey PRIMARY KEY (registered_client_id, principal_name);

--
-- Name: oauth2_authorization oauth2_authorization_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth2_authorization
    ADD CONSTRAINT oauth2_authorization_pkey PRIMARY KEY (id);

--
-- Name: oauth2_registered_client oauth2_registered_client_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oauth2_registered_client
    ADD CONSTRAINT oauth2_registered_client_pkey PRIMARY KEY (id);

--
-- Name: page_ocr_history page_ocr_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_ocr_history
    ADD CONSTRAINT page_ocr_history_pkey PRIMARY KEY (id);

--
-- Name: page_person_match page_person_match_page_id_person_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_person_match
    ADD CONSTRAINT page_person_match_page_id_person_id_key UNIQUE (page_id, person_id);

--
-- Name: page_person_match page_person_match_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_person_match
    ADD CONSTRAINT page_person_match_pkey PRIMARY KEY (id);

--
-- Name: page page_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page
    ADD CONSTRAINT page_pkey PRIMARY KEY (id);

--
-- Name: page_search page_search_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_search
    ADD CONSTRAINT page_search_pkey PRIMARY KEY (id);

--
-- Name: page_text page_text_page_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_text
    ADD CONSTRAINT page_text_page_id_key UNIQUE (page_id);

--
-- Name: page_text page_text_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_text
    ADD CONSTRAINT page_text_pkey PRIMARY KEY (id);

--
-- Name: pipeline_event pipeline_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pipeline_event
    ADD CONSTRAINT pipeline_event_pkey PRIMARY KEY (id);

--
-- Name: processing_run processing_run_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.processing_run
    ADD CONSTRAINT processing_run_pkey PRIMARY KEY (id);

--
-- Name: record_attribute record_attribute_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.record_attribute
    ADD CONSTRAINT record_attribute_pkey PRIMARY KEY (id);

--
-- Name: record record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.record
    ADD CONSTRAINT record_pkey PRIMARY KEY (id);

--
-- Name: text_chunk text_chunk_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.text_chunk
    ADD CONSTRAINT text_chunk_pkey PRIMARY KEY (id);

--
-- Name: idx_app_user_email_email; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_user_email_email ON public.app_user_email USING btree (email);

--
-- Name: idx_app_user_email_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_app_user_email_user ON public.app_user_email USING btree (user_id);

--
-- Name: idx_attachment_record_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_attachment_record_id ON public.attachment USING btree (record_id);

--
-- Name: idx_collection_archive_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_collection_archive_id ON public.collection USING btree (archive_id);

--
-- Name: idx_entity_hit_page_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_entity_hit_page_id ON public.entity_hit USING btree (page_id);

--
-- Name: idx_entity_hit_type_value; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_entity_hit_type_value ON public.entity_hit USING btree (entity_type, value);

--
-- Name: idx_entity_hit_value_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_entity_hit_value_trgm ON public.entity_hit USING gin (value public.gin_trgm_ops);

--
-- Name: idx_evidence_entity_hit_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_evidence_entity_hit_id ON public.evidence USING btree (entity_hit_id);

--
-- Name: idx_evidence_page_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_evidence_page_id ON public.evidence USING btree (page_id);

--
-- Name: idx_job_page_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_job_page_id ON public.job USING btree (page_id);

--
-- Name: idx_job_record_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_job_record_id ON public.job USING btree (record_id);

--
-- Name: idx_job_status_kind; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_job_status_kind ON public.job USING btree (status, kind);

--
-- Name: idx_page_attachment_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_page_attachment_id ON public.page USING btree (attachment_id);

--
-- Name: idx_page_ocr_history_engine; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_page_ocr_history_engine ON public.page_ocr_history USING btree (engine);

--
-- Name: idx_page_ocr_history_page; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_page_ocr_history_page ON public.page_ocr_history USING btree (page_id);

--
-- Name: idx_page_person_match_page; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_page_person_match_page ON public.page_person_match USING btree (page_id);

--
-- Name: idx_page_person_match_person; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_page_person_match_person ON public.page_person_match USING btree (person_id);

--
-- Name: idx_page_record_seq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_page_record_seq ON public.page USING btree (record_id, seq);

--
-- Name: idx_page_search_page_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_page_search_page_id ON public.page_search USING btree (page_id);

--
-- Name: idx_page_search_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_page_search_trgm ON public.page_search USING gin (best_text_norm public.gin_trgm_ops);

--
-- Name: idx_page_search_tsv; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_page_search_tsv ON public.page_search USING gin (tsv);

--
-- Name: idx_page_text_page_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_page_text_page_id ON public.page_text USING btree (page_id);

--
-- Name: idx_page_text_text_norm_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_page_text_text_norm_trgm ON public.page_text USING gin (text_norm public.gin_trgm_ops);

--
-- Name: idx_pipeline_event_record_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_pipeline_event_record_id ON public.pipeline_event USING btree (record_id);

--
-- Name: idx_processing_run_job_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_processing_run_job_id ON public.processing_run USING btree (job_id);

--
-- Name: idx_record_archive_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_record_archive_id ON public.record USING btree (archive_id);

--
-- Name: idx_record_attribute_record_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_record_attribute_record_id ON public.record_attribute USING btree (record_id);

--
-- Name: idx_record_collection_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_record_collection_id ON public.record USING btree (collection_id);

--
-- Name: idx_record_pdf_att; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_record_pdf_att ON public.record USING btree (pdf_attachment_id);

--
-- Name: idx_record_source; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_record_source ON public.record USING btree (source_system, source_record_id);

--
-- Name: idx_record_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_record_status ON public.record USING btree (status);

--
-- Name: idx_text_chunk_content_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_text_chunk_content_trgm ON public.text_chunk USING gin (lower(content) public.gin_trgm_ops);

--
-- Name: idx_text_chunk_embedding; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_text_chunk_embedding ON public.text_chunk USING hnsw (embedding public.halfvec_cosine_ops);

--
-- Name: idx_text_chunk_page; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_text_chunk_page ON public.text_chunk USING btree (page_id);

--
-- Name: idx_text_chunk_record; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_text_chunk_record ON public.text_chunk USING btree (record_id);

--
-- Name: page_text page_text_history; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER page_text_history AFTER INSERT ON public.page_text FOR EACH ROW EXECUTE FUNCTION public.page_text_record_history();

--
-- Name: page_text page_text_history_close; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER page_text_history_close AFTER DELETE ON public.page_text FOR EACH ROW EXECUTE FUNCTION public.page_text_close_history();

--
-- Name: app_user_email app_user_email_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user_email
    ADD CONSTRAINT app_user_email_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.app_user(id) ON DELETE CASCADE;

--
-- Name: attachment attachment_record_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.attachment
    ADD CONSTRAINT attachment_record_id_fkey FOREIGN KEY (record_id) REFERENCES public.record(id) ON DELETE CASCADE;

--
-- Name: collection collection_archive_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.collection
    ADD CONSTRAINT collection_archive_id_fkey FOREIGN KEY (archive_id) REFERENCES public.archive(id);

--
-- Name: entity_hit entity_hit_page_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.entity_hit
    ADD CONSTRAINT entity_hit_page_id_fkey FOREIGN KEY (page_id) REFERENCES public.page(id) ON DELETE CASCADE;

--
-- Name: evidence evidence_entity_hit_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evidence
    ADD CONSTRAINT evidence_entity_hit_id_fkey FOREIGN KEY (entity_hit_id) REFERENCES public.entity_hit(id) ON DELETE CASCADE;

--
-- Name: evidence evidence_page_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.evidence
    ADD CONSTRAINT evidence_page_id_fkey FOREIGN KEY (page_id) REFERENCES public.page(id) ON DELETE CASCADE;

--
-- Name: record fk_record_pdf_attachment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.record
    ADD CONSTRAINT fk_record_pdf_attachment FOREIGN KEY (pdf_attachment_id) REFERENCES public.attachment(id);

--
-- Name: job job_page_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.job
    ADD CONSTRAINT job_page_id_fkey FOREIGN KEY (page_id) REFERENCES public.page(id) ON DELETE CASCADE;

--
-- Name: job job_record_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.job
    ADD CONSTRAINT job_record_id_fkey FOREIGN KEY (record_id) REFERENCES public.record(id) ON DELETE CASCADE;

--
-- Name: page page_attachment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page
    ADD CONSTRAINT page_attachment_id_fkey FOREIGN KEY (attachment_id) REFERENCES public.attachment(id) ON DELETE CASCADE;

--
-- Name: page_ocr_history page_ocr_history_page_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_ocr_history
    ADD CONSTRAINT page_ocr_history_page_id_fkey FOREIGN KEY (page_id) REFERENCES public.page(id) ON DELETE CASCADE;

--
-- Name: page_person_match page_person_match_page_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_person_match
    ADD CONSTRAINT page_person_match_page_id_fkey FOREIGN KEY (page_id) REFERENCES public.page(id) ON DELETE CASCADE;

--
-- Name: page page_record_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page
    ADD CONSTRAINT page_record_id_fkey FOREIGN KEY (record_id) REFERENCES public.record(id) ON DELETE CASCADE;

--
-- Name: page_search page_search_page_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_search
    ADD CONSTRAINT page_search_page_id_fkey FOREIGN KEY (page_id) REFERENCES public.page(id) ON DELETE CASCADE;

--
-- Name: page_text page_text_page_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.page_text
    ADD CONSTRAINT page_text_page_id_fkey FOREIGN KEY (page_id) REFERENCES public.page(id) ON DELETE CASCADE;

--
-- Name: pipeline_event pipeline_event_record_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pipeline_event
    ADD CONSTRAINT pipeline_event_record_id_fkey FOREIGN KEY (record_id) REFERENCES public.record(id) ON DELETE CASCADE;

--
-- Name: processing_run processing_run_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.processing_run
    ADD CONSTRAINT processing_run_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.job(id) ON DELETE CASCADE;

--
-- Name: record record_archive_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.record
    ADD CONSTRAINT record_archive_id_fkey FOREIGN KEY (archive_id) REFERENCES public.archive(id);

--
-- Name: record_attribute record_attribute_record_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.record_attribute
    ADD CONSTRAINT record_attribute_record_id_fkey FOREIGN KEY (record_id) REFERENCES public.record(id) ON DELETE CASCADE;

--
-- Name: record record_collection_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.record
    ADD CONSTRAINT record_collection_id_fkey FOREIGN KEY (collection_id) REFERENCES public.collection(id);

--
-- Name: text_chunk text_chunk_page_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.text_chunk
    ADD CONSTRAINT text_chunk_page_id_fkey FOREIGN KEY (page_id) REFERENCES public.page(id) ON DELETE CASCADE;

--
-- Name: text_chunk text_chunk_record_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.text_chunk
    ADD CONSTRAINT text_chunk_record_id_fkey FOREIGN KEY (record_id) REFERENCES public.record(id) ON DELETE CASCADE;

--
-- PostgreSQL database dump complete
--
