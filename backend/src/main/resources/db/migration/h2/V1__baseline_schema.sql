-- H2 (dev, no Docker) baseline schema.
-- Postgres is the production dialect; this file keeps the same tables/columns/constraints
-- with H2-compatible types, so both paths must stay in sync.
-- jsonb -> CLOB (app writes JSON text), partial indexes -> plain indexes,
-- gen_random_uuid() -> RANDOM_UUID(), tsvector generated column -> omitted (FTS via LIKE/ILIKE in dev).
-- settings.key/value are renamed setting_key/setting_value in BOTH dialects: KEY and VALUE are
-- reserved words in H2, and the two files must stay column-for-column identical.

CREATE TABLE app_user (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  totp_secret   VARCHAR(255),
  created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE api_token (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name         VARCHAR(128) NOT NULL,
  scope        VARCHAR(16)  NOT NULL CHECK (scope IN ('ingest', 'read', 'admin')),
  token_hash   VARCHAR(255) NOT NULL UNIQUE,
  created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_used_at TIMESTAMP WITH TIME ZONE,
  revoked_at   TIMESTAMP WITH TIME ZONE
);

CREATE TABLE settings (
  setting_key   VARCHAR(128) PRIMARY KEY,
  setting_value CLOB         NOT NULL,
  updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE feed (
  id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id         UUID         NOT NULL DEFAULT RANDOM_UUID() UNIQUE,
  canonical_url     VARCHAR(2048) NOT NULL UNIQUE,
  requested_url     VARCHAR(2048) NOT NULL,
  self_url          VARCHAR(2048),
  format            VARCHAR(16),
  title             VARCHAR(512),
  site_url          VARCHAR(2048),
  description       VARCHAR(2048),
  language          VARCHAR(32),
  image_url         VARCHAR(2048),
  etag              VARCHAR(256),
  last_modified_hdr VARCHAR(256),
  ttl_minutes       INTEGER,
  auth_ref          VARCHAR(256),
  state             VARCHAR(16) NOT NULL DEFAULT 'active'
                    CHECK (state IN ('active', 'muted', 'error', 'gone', 'paused')),
  fail_count        INTEGER NOT NULL DEFAULT 0,
  notfound_streak   INTEGER NOT NULL DEFAULT 0,
  last_http_status  INTEGER,
  last_error        VARCHAR(1024),
  last_fetched_at   TIMESTAMP WITH TIME ZONE,
  last_change_at    TIMESTAMP WITH TIME ZONE,
  avg_fetch_ms      INTEGER,
  group_name        VARCHAR(128),
  created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX feed_state_fetched_idx ON feed (state, last_fetched_at);

CREATE TABLE scheduled_task (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kind         VARCHAR(32) NOT NULL
               CHECK (kind IN ('fetch', 'ai_summary', 'ai_dedupe', 'deliver', 'media_probe')),
  ref_id       BIGINT      NOT NULL,
  dedupe_key   VARCHAR(256) NOT NULL,
  state        VARCHAR(16) NOT NULL DEFAULT 'pending'
               CHECK (state IN ('pending', 'running', 'success', 'failed', 'cancelled')),
  fire_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  attempts     INTEGER NOT NULL DEFAULT 0,
  max_attempts INTEGER NOT NULL DEFAULT 5,
  claimed_at   TIMESTAMP WITH TIME ZONE,
  locked_by    VARCHAR(64),
  last_error   VARCHAR(1024),
  payload      CLOB    NOT NULL DEFAULT '{}',
  created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_task_kind_dedupe UNIQUE (kind, dedupe_key)
);
CREATE INDEX task_due_idx ON scheduled_task (state, fire_at, id);

CREATE TABLE entry (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id      UUID         NOT NULL DEFAULT RANDOM_UUID() UNIQUE,
  feed_id        BIGINT       NOT NULL REFERENCES feed (id) ON DELETE CASCADE,
  guid           VARCHAR(2048) NOT NULL,
  url            VARCHAR(2048),
  title          VARCHAR(1024),
  summary        CLOB,
  content_html   CLOB,
  content_hash   VARCHAR(64),
  authors        CLOB         NOT NULL DEFAULT '[]',
  categories     CLOB         NOT NULL DEFAULT '[]',
  enclosures     CLOB         NOT NULL DEFAULT '[]',
  published_at   TIMESTAMP WITH TIME ZONE,
  updated_at_src TIMESTAMP WITH TIME ZONE,
  fetched_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revision       INTEGER      NOT NULL DEFAULT 1,
  read_state     VARCHAR(16)  NOT NULL DEFAULT 'unread'
                 CHECK (read_state IN ('unread', 'read', 'dismissed')),
  starred        BOOLEAN      NOT NULL DEFAULT FALSE,
  archived       BOOLEAN      NOT NULL DEFAULT FALSE,
  baseline       BOOLEAN      NOT NULL DEFAULT FALSE,
  lang           VARCHAR(32),
  meta           CLOB         NOT NULL DEFAULT '{}',
  CONSTRAINT uq_entry_feed_guid UNIQUE (feed_id, guid)
);
CREATE INDEX entry_inbox_idx ON entry (feed_id, published_at DESC, id DESC);
CREATE INDEX entry_unread_idx ON entry (read_state, published_at DESC);

CREATE TABLE entry_revision (
  entry_id       BIGINT      NOT NULL REFERENCES entry (id) ON DELETE CASCADE,
  revision       INTEGER     NOT NULL,
  title          VARCHAR(1024),
  content_hash   VARCHAR(64),
  updated_at_src TIMESTAMP WITH TIME ZONE,
  raw            CLOB,
  recorded_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (entry_id, revision)
);

CREATE TABLE raw_response (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  feed_id      BIGINT      NOT NULL REFERENCES feed (id) ON DELETE CASCADE,
  fetched_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  http_status  INTEGER     NOT NULL,
  content_type VARCHAR(256),
  byte_size    INTEGER     NOT NULL,
  sha256       VARCHAR(64) NOT NULL,
  storage_key  VARCHAR(512) NOT NULL,
  entry_count  INTEGER
);
CREATE INDEX raw_fetched_idx ON raw_response (fetched_at);

CREATE TABLE media_asset (
  id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kind             VARCHAR(16) NOT NULL CHECK (kind IN ('cover', 'audio', 'image', 'other')),
  storage_key      VARCHAR(512) NOT NULL UNIQUE,
  filename         VARCHAR(256),
  content_type     VARCHAR(128),
  byte_size        BIGINT,
  sha256           VARCHAR(64),
  width_px         INTEGER,
  height_px        INTEGER,
  duration_seconds NUMERIC(12, 3),
  probe_json       CLOB,
  created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
