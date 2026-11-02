-- PostgreSQL (production) baseline schema. Must stay column-for-column in sync with
-- ../h2/V1__baseline_schema.sql, which exists only so the service runs without Docker.
-- Deviations here are intentional and are the point of keeping two files:
--   jsonb + partial indexes + gen_random_uuid() + generated tsvector are Postgres features.

CREATE TABLE app_user (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  username      TEXT         NOT NULL UNIQUE,
  password_hash TEXT         NOT NULL,
  totp_secret   TEXT,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE api_token (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name         TEXT        NOT NULL,
  scope        TEXT        NOT NULL CHECK (scope IN ('ingest', 'read', 'admin')),
  token_hash   TEXT        NOT NULL UNIQUE,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_used_at TIMESTAMPTZ,
  revoked_at   TIMESTAMPTZ
);

CREATE TABLE settings (
  setting_key   TEXT        PRIMARY KEY,
  setting_value JSONB       NOT NULL,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE feed (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id         UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  canonical_url     TEXT         NOT NULL UNIQUE,
  requested_url     TEXT         NOT NULL,
  self_url          TEXT,
  format            TEXT,
  title             TEXT,
  site_url          TEXT,
  description       TEXT,
  language          TEXT,
  image_url         TEXT,
  etag              TEXT,
  last_modified_hdr TEXT,
  ttl_minutes       INT,
  auth_ref          TEXT,
  state             TEXT NOT NULL DEFAULT 'active'
                    CHECK (state IN ('active', 'muted', 'error', 'gone', 'paused')),
  fail_count        INT  NOT NULL DEFAULT 0,
  notfound_streak   INT  NOT NULL DEFAULT 0,
  last_http_status  INT,
  last_error        TEXT,
  last_fetched_at   TIMESTAMPTZ,
  last_change_at    TIMESTAMPTZ,
  avg_fetch_ms      INT,
  group_name        TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX feed_state_fetched_idx ON feed (state, last_fetched_at);

CREATE TABLE scheduled_task (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kind         TEXT    NOT NULL CHECK (kind IN
               ('fetch', 'ai_summary', 'ai_dedupe', 'deliver', 'media_probe')),
  ref_id       BIGINT  NOT NULL,
  dedupe_key   TEXT    NOT NULL,
  state        TEXT    NOT NULL DEFAULT 'pending'
               CHECK (state IN ('pending', 'running', 'success', 'failed', 'cancelled')),
  fire_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  attempts     INT     NOT NULL DEFAULT 0,
  max_attempts INT     NOT NULL DEFAULT 5,
  claimed_at   TIMESTAMPTZ,
  locked_by    TEXT,
  last_error   TEXT,
  payload      JSONB   NOT NULL DEFAULT '{}',
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_task_kind_dedupe UNIQUE (kind, dedupe_key)
);
CREATE INDEX task_due_idx ON scheduled_task (fire_at, id) WHERE state = 'pending';

CREATE TABLE entry (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id      UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  feed_id        BIGINT NOT NULL REFERENCES feed (id) ON DELETE CASCADE,
  guid           TEXT         NOT NULL,
  url            TEXT,
  title          TEXT,
  summary        TEXT,
  content_html   TEXT,
  content_hash   TEXT,
  authors        JSONB        NOT NULL DEFAULT '[]',
  categories     JSONB        NOT NULL DEFAULT '[]',
  enclosures     JSONB        NOT NULL DEFAULT '[]',
  published_at   TIMESTAMPTZ,
  updated_at_src TIMESTAMPTZ,
  fetched_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  revision       INT          NOT NULL DEFAULT 1,
  read_state     TEXT         NOT NULL DEFAULT 'unread'
                 CHECK (read_state IN ('unread', 'read', 'dismissed')),
  starred        BOOLEAN      NOT NULL DEFAULT FALSE,
  archived       BOOLEAN      NOT NULL DEFAULT FALSE,
  baseline       BOOLEAN      NOT NULL DEFAULT FALSE,
  lang           TEXT,
  meta           JSONB        NOT NULL DEFAULT '{}',
  search_ts      TSVECTOR GENERATED ALWAYS AS
                 (to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(summary, ''))) STORED,
  CONSTRAINT uq_entry_feed_guid UNIQUE (feed_id, guid)
);
CREATE INDEX entry_inbox_idx  ON entry (feed_id, published_at DESC NULLS LAST, id DESC);
CREATE INDEX entry_unread_idx ON entry (read_state, published_at DESC) WHERE read_state = 'unread';
CREATE INDEX entry_search_idx ON entry USING gin (search_ts);

CREATE TABLE entry_revision (
  entry_id       BIGINT NOT NULL REFERENCES entry (id) ON DELETE CASCADE,
  revision       INT    NOT NULL,
  title          TEXT,
  content_hash   TEXT,
  updated_at_src TIMESTAMPTZ,
  -- raw holds the body HTML of that revision, so TEXT and not JSONB: the app writes markup into it
  -- (feedstore.EntryRepository.recordRevision) and jsonb would reject it on the first corrected article.
  raw            TEXT,
  recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (entry_id, revision)
);

CREATE TABLE raw_response (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  feed_id      BIGINT NOT NULL REFERENCES feed (id) ON DELETE CASCADE,
  fetched_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  http_status  INT    NOT NULL,
  content_type TEXT,
  byte_size    INT    NOT NULL,
  sha256       TEXT   NOT NULL,
  storage_key  TEXT   NOT NULL,
  entry_count  INT
);
CREATE INDEX raw_time_idx ON raw_response (fetched_at);

CREATE TABLE media_asset (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kind             TEXT    NOT NULL CHECK (kind IN ('cover', 'audio', 'image', 'other')),
  storage_key      TEXT    NOT NULL UNIQUE,
  filename         TEXT,
  content_type     TEXT,
  byte_size        BIGINT,
  sha256           TEXT,
  width_px         INT,
  height_px        INT,
  duration_seconds NUMERIC(12, 3),
  probe_json       JSONB,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
