-- PostgreSQL (production) M5 schema. Must stay column-for-column in sync with ../h2/V4__repack_schema.sql.
-- Deviations are the ones V1–V3 already establish: TEXT, BIGINT GENERATED ALWAYS AS IDENTITY, and no jsonb
-- columns here — this milestone stores no documents, only flags.

CREATE TABLE repack (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug        TEXT        NOT NULL UNIQUE,
  title       TEXT        NOT NULL,
  description TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE repack_source (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  repack_id    BIGINT    NOT NULL REFERENCES repack (id) ON DELETE CASCADE,
  feed_id      BIGINT    NOT NULL REFERENCES feed (id) ON DELETE CASCADE,
  -- FR-7.2: full-text republication is an explicit per-site decision; FALSE (summaries only) is the default itself.
  full_content BOOLEAN   NOT NULL DEFAULT FALSE,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_repack_source UNIQUE (repack_id, feed_id)
);

-- No foreign keys on purpose: after a collection is deleted or a feed unsubscribed, the audit still has to say
-- whether full content was ever turned on (04 §M5's gate).
CREATE TABLE repack_source_audit (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  repack_id    BIGINT      NOT NULL,
  feed_id      BIGINT      NOT NULL,
  action       VARCHAR(32) NOT NULL
               CHECK (action IN ('add', 'remove', 'full_content_on', 'full_content_off')),
  full_content BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX repack_audit_repack_idx ON repack_source_audit (repack_id, id DESC);
