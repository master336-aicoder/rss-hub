-- F2.4（FR-2.4 的"只看关键词命中"）：行尾那个红标升级为 WHERE 条件之后，翻页的每一行都要按 entry_id 回查
-- notification_log。V3 留下的两个索引答的是"这条规则发过什么"（notif_rule_idx）和"这条是不是重复"
-- （notif_dedupe_idx），都对不上 entry_id 这个方向。
-- 必须与 ../postgresql/V6__alert_filter_index.sql 同步：本文件不建表，MigrationDialectAlignmentTest 对它是两边
-- 空集相等；把它列进 MIGRATIONS 的价值只在"漏改一个方言时文件缺失会立刻炸"。

CREATE INDEX notif_entry_idx ON notification_log (entry_id);
