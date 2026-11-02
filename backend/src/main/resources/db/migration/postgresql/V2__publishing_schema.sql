-- PostgreSQL (production) publishing schema. Must stay column-for-column in sync with
-- ../h2/V2__publishing_schema.sql; the intentional dialect deviations are the same ones V1 documents
-- (TEXT over VARCHAR/CLOB, TIMESTAMPTZ, gen_random_uuid(), partial indexes).

CREATE TABLE post (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id      UUID     NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  slug           TEXT     NOT NULL UNIQUE,
  title          TEXT     NOT NULL,
  summary_md     TEXT,
  body_md        TEXT     NOT NULL,
  body_html      TEXT     NOT NULL,
  status         TEXT     NOT NULL DEFAULT 'draft'
                 CHECK (status IN ('draft', 'scheduled', 'published', 'unlisted', 'archived')),
  lang           TEXT,
  published_at   TIMESTAMPTZ,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  cover_asset_id BIGINT REFERENCES media_asset (id),
  canonical_url  TEXT
);
-- The list and the feed both ask for "published, newest first", and nothing else is ever read from this table.
CREATE INDEX post_feed_idx ON post (published_at DESC NULLS LAST, id DESC) WHERE status = 'published';

CREATE TABLE tag (
  id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug  TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL
);

CREATE TABLE post_tag (
  post_id BIGINT NOT NULL REFERENCES post (id) ON DELETE CASCADE,
  tag_id  BIGINT NOT NULL REFERENCES tag (id) ON DELETE CASCADE,
  PRIMARY KEY (post_id, tag_id)
);

CREATE TABLE category (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug         TEXT    NOT NULL UNIQUE,
  title        TEXT    NOT NULL,
  feed_enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE post_category (
  post_id     BIGINT NOT NULL REFERENCES post (id) ON DELETE CASCADE,
  category_id BIGINT NOT NULL REFERENCES category (id) ON DELETE CASCADE,
  PRIMARY KEY (post_id, category_id)
);
