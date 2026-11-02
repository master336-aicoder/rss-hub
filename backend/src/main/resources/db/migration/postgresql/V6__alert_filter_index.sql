-- F2.4（FR-2.4 的"只看关键词命中"）：与 ../h2/V6__alert_filter_index.sql 同一口径，两边都是同一条没有方言差异的
-- CREATE INDEX（V1–V5 的方言差异全在列类型与默认值上，索引不涉及）。
-- 索引名两边一致：MigrationDialectAlignmentTest 比的是表名与列名，比不到索引，所以名字的对齐靠写法。真正的门禁是
-- 这个文件名进了 MIGRATIONS 清单——缺任一侧的文件，测试读资源时就抛 "missing migration"。

CREATE INDEX notif_entry_idx ON notification_log (entry_id);
