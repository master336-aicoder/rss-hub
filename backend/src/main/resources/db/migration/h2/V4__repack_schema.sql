-- M5 聚合再分发（FR-7.1/7.2）：转发集合、集合成员（逐源全文开关）、开关审计。
-- 必须与 ../postgresql/V4__repack_schema.sql 逐列同序对齐（MigrationDialectAlignmentTest 按表名 + 列名比对）。
-- 方言口径沿用 V1–V3：BOOLEAN 两边同形、时间列 TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP、本表无 JSON 列。

CREATE TABLE repack (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug        VARCHAR(255) NOT NULL UNIQUE,
  title       VARCHAR(512) NOT NULL,
  description VARCHAR(2048),
  created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE repack_source (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  repack_id    BIGINT    NOT NULL REFERENCES repack (id) ON DELETE CASCADE,
  feed_id      BIGINT    NOT NULL REFERENCES feed (id) ON DELETE CASCADE,
  -- FR-7.2：全文再分发是按站点逐个显式开启的，FALSE（只转发摘要）就是默认值本身。
  full_content BOOLEAN   NOT NULL DEFAULT FALSE,
  created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_repack_source UNIQUE (repack_id, feed_id)
);

CREATE TABLE repack_source_audit (
  -- 故意不给 repack_id / feed_id 加外键：删掉集合或退订某个源之后，"当时开过全文"仍然要说得清（04 §M5 门禁）。
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  repack_id    BIGINT      NOT NULL,
  feed_id      BIGINT      NOT NULL,
  action       VARCHAR(32) NOT NULL
               CHECK (action IN ('add', 'remove', 'full_content_on', 'full_content_off')),
  full_content BOOLEAN     NOT NULL DEFAULT FALSE,
  created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX repack_audit_repack_idx ON repack_source_audit (repack_id, id DESC);
