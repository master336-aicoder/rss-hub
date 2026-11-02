-- PostgreSQL (production) M3 schema. Must stay column-for-column in sync with ../h2/V3__events_rules_schema.sql.
-- Deviations are the ones V1/V2 already establish: jsonb, gen_random_uuid(), TIMESTAMPTZ, TEXT.

CREATE TABLE event_stream (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug        TEXT         NOT NULL UNIQUE,
  title       TEXT         NOT NULL,
  description TEXT,
  visibility  TEXT         NOT NULL DEFAULT 'internal'
              CHECK (visibility IN ('internal', 'virtual_feed')),
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE event_item (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  stream_id    BIGINT      NOT NULL REFERENCES event_stream (id) ON DELETE CASCADE,
  public_id    UUID        NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  dedupe_key   TEXT        NOT NULL,
  title        TEXT        NOT NULL,
  url          TEXT,
  summary      TEXT,
  tags         JSONB       NOT NULL DEFAULT '[]',
  published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  content_hash TEXT        NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_event_item_stream_dedupe UNIQUE (stream_id, dedupe_key)
);
CREATE INDEX event_item_stream_time_idx ON event_item (stream_id, published_at DESC, id DESC);

CREATE TABLE rule (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name            TEXT    NOT NULL,
  enabled         BOOLEAN NOT NULL DEFAULT TRUE,
  match           JSONB   NOT NULL,
  actions         JSONB   NOT NULL,
  suppression     JSONB   NOT NULL DEFAULT '{"key":"perFeed","hours":6}',
  quiet_hours     JSONB,
  hit_count       INTEGER NOT NULL DEFAULT 0,
  disabled_reason TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX rule_enabled_idx ON rule (enabled);

CREATE TABLE notification_log (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  rule_id     BIGINT REFERENCES rule (id) ON DELETE SET NULL,
  entry_id    BIGINT REFERENCES entry (id) ON DELETE SET NULL,
  channel     TEXT        NOT NULL,
  dedupe_key  TEXT        NOT NULL,
  state       TEXT        NOT NULL DEFAULT 'pending'
              CHECK (state IN ('pending', 'sent', 'suppressed', 'failed', 'skipped_quiet')),
  http_status INTEGER,
  error       TEXT,
  attempts    INTEGER     NOT NULL DEFAULT 0,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX notif_dedupe_idx ON notification_log (dedupe_key, created_at DESC);
CREATE INDEX notif_rule_idx ON notification_log (rule_id, id DESC);
