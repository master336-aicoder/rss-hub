-- M3.5+ 变更公告（FR-1.6）：站内文章每一次创建、编辑、状态流转、删除的历史，公开成 /feed/changelog 出口。
-- 必须与 ../postgresql/V5__changelog_schema.sql 逐列同序对齐（MigrationDialectAlignmentTest 按表名 + 列名比对）。
-- 方言口径沿用 V1–V4：uuid 默认 RANDOM_UUID()（PG 用 gen_random_uuid()）、时间列 TIMESTAMP WITH TIME ZONE。

CREATE TABLE content_change (
  -- 故意不给 post_id 加外键：文章删掉之后"它存在过、它的标题是什么、它是什么时候没的"仍然要说得清，
  -- 而删掉历史等于把变更公告里最重要的一类条目（retraction）一起删了。口径同 V4 的 repack_source_audit。
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  public_id   UUID          NOT NULL DEFAULT RANDOM_UUID() UNIQUE,
  post_id     BIGINT        NOT NULL,
  post_slug   VARCHAR(255)  NOT NULL,
  post_title  VARCHAR(512)  NOT NULL,
  -- URL 是写入时的快照而不是读时再拼：出口里的 link 要能在文章消失之后仍然指向它曾经的位置。
  post_url    VARCHAR(2048) NOT NULL,
  action      VARCHAR(16)   NOT NULL
              CHECK (action IN ('created', 'updated', 'status', 'deleted')),
  from_status VARCHAR(16),
  to_status   VARCHAR(16),
  changed_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 出口和变更面板都只问"最近的、按文章过滤的"，两个都从后往前扫。
CREATE INDEX content_change_recent_idx ON content_change (id DESC);
CREATE INDEX content_change_post_idx ON content_change (post_id, id DESC);
