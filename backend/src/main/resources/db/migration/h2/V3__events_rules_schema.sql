-- M3 事件源与规则告警（03 §3.7、§3.10）：事件流、事件条目、规则、投递日志。
-- 必须与 ../postgresql/V3__events_rules_schema.sql 逐列同序对齐（MigrationDialectAlignmentTest 按表名 + 列名比对）。
-- 方言口径沿用 V1/V2：jsonb -> CLOB（应用自己写 JSON 文本）、uuid 默认 RANDOM_UUID()、
-- 时间列 TIMESTAMP WITH TIME ZONE、partial index -> plain index。

CREATE TABLE event_stream (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slug        VARCHAR(255)  NOT NULL UNIQUE,
  title       VARCHAR(512)  NOT NULL,
  description VARCHAR(2048),
  -- FR-3.3：internal 只作输出 feed；virtual_feed 额外把条目写进收件箱（靠一条 paused 的 feed 行承载）。
  visibility  VARCHAR(16)   NOT NULL DEFAULT 'internal'
              CHECK (visibility IN ('internal', 'virtual_feed')),
  created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE event_item (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  stream_id    BIGINT       NOT NULL REFERENCES event_stream (id) ON DELETE CASCADE,
  -- 收件箱那一侧的 entry 行靠它回指，删除事件条目时才知道该清哪条 entry。
  public_id    UUID         NOT NULL DEFAULT RANDOM_UUID() UNIQUE,
  dedupe_key   VARCHAR(256) NOT NULL,
  title        VARCHAR(1024) NOT NULL,
  url          VARCHAR(2048),
  summary      CLOB,
  tags         CLOB         NOT NULL DEFAULT '[]',
  published_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  content_hash VARCHAR(64)  NOT NULL,
  created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- FR-3.2：同 key 更新而非新增，这条约束就是它的实现，不是索引优化。
  CONSTRAINT uq_event_item_stream_dedupe UNIQUE (stream_id, dedupe_key)
);
CREATE INDEX event_item_stream_time_idx ON event_item (stream_id, published_at DESC, id DESC);

CREATE TABLE rule (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name            VARCHAR(255) NOT NULL,
  enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
  -- 03 §8.1 的判定输入：{includeAny:[],includeAll:[],excludeAny:[],regex:[],wholeWord:bool,
  --                    feedIds:[],groups:[],tags:[],windowMinutes:int}
  match           CLOB         NOT NULL,
  -- 03 §8.3 的通道列表：[{channel:'webhook',target:'https://…',secretRef:'…'}] | [{channel:'markOnly'}]
  actions         CLOB         NOT NULL,
  -- 03 §8.2：{"key":"perFeed|perContentHash|perTitleNorm","hours":6}
  suppression     CLOB         NOT NULL DEFAULT '{"key":"perFeed","hours":6}',
  -- 03 §8.2：{"from":"23:00","to":"07:00","tz":"Asia/Shanghai"}；null = 不静默
  quiet_hours     CLOB,
  hit_count       INTEGER      NOT NULL DEFAULT 0,
  -- FR-5.3：连续投递失败自动停用时写这里，HealthView 的红色提示读它。
  disabled_reason VARCHAR(512),
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX rule_enabled_idx ON rule (enabled);

CREATE TABLE notification_log (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  rule_id    BIGINT REFERENCES rule (id) ON DELETE SET NULL,
  entry_id   BIGINT REFERENCES entry (id) ON DELETE SET NULL,
  channel    VARCHAR(32)  NOT NULL,
  -- 抑制窗口的键：rule:{ruleId}:{命中键}（03 §8.2）。
  dedupe_key VARCHAR(512) NOT NULL,
  -- 'pending' 是 03 §8.1 的"同事务只写命中事实"，投递在独立任务里把它推到终态。
  state      VARCHAR(16)  NOT NULL DEFAULT 'pending'
             CHECK (state IN ('pending', 'sent', 'suppressed', 'failed', 'skipped_quiet')),
  http_status INTEGER,
  error       VARCHAR(1024),
  attempts    INTEGER     NOT NULL DEFAULT 0,
  created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX notif_dedupe_idx ON notification_log (dedupe_key, created_at DESC);
CREATE INDEX notif_rule_idx ON notification_log (rule_id, id DESC);
