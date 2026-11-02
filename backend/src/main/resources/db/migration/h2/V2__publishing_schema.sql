-- M2 发布侧（03 §3.6）：站内文章、标签、分类。
-- 必须与 ../postgresql/V2__publishing_schema.sql 逐列同序对齐（MigrationDialectAlignmentTest 按表名 + 列名比对）。
-- 沿用的方言口径：uuid 默认 RANDOM_UUID()（PG 用 gen_random_uuid()）、大字块 CLOB（PG 用 TEXT）、
-- 时间列写 TIMESTAMP WITH TIME ZONE（PG 写 TIMESTAMPTZ）。

CREATE TABLE post (
  id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id      UUID          NOT NULL DEFAULT RANDOM_UUID() UNIQUE,
  slug           VARCHAR(255)  NOT NULL UNIQUE,
  title          VARCHAR(512)  NOT NULL,
  summary_md     CLOB,
  body_md        CLOB          NOT NULL,
  body_html      CLOB          NOT NULL,
  status         VARCHAR(16)   NOT NULL DEFAULT 'draft'
                 CHECK (status IN ('draft', 'scheduled', 'published', 'unlisted', 'archived')),
  lang           VARCHAR(32),
  published_at   TIMESTAMP WITH TIME ZONE,
  updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  cover_asset_id BIGINT REFERENCES media_asset (id),
  canonical_url  VARCHAR(2048)
);
-- The feed query is "published, newest first", and H2 has no partial index; PG does the same thing with
-- post_feed_idx WHERE status = 'published'.
CREATE INDEX post_feed_idx ON post (status, published_at);

CREATE TABLE tag (
  id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug  VARCHAR(255) NOT NULL UNIQUE,
  title VARCHAR(512) NOT NULL
);

CREATE TABLE post_tag (
  post_id BIGINT NOT NULL REFERENCES post (id) ON DELETE CASCADE,
  tag_id  BIGINT NOT NULL REFERENCES tag (id) ON DELETE CASCADE,
  PRIMARY KEY (post_id, tag_id)
);

CREATE TABLE category (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug         VARCHAR(255) NOT NULL UNIQUE,
  title        VARCHAR(512) NOT NULL,
  feed_enabled BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE post_category (
  post_id     BIGINT NOT NULL REFERENCES post (id) ON DELETE CASCADE,
  category_id BIGINT NOT NULL REFERENCES category (id) ON DELETE CASCADE,
  PRIMARY KEY (post_id, category_id)
);
