-- F10（订阅管理可编辑）：与 ../h2/V7__feed_editorial_controls.sql 同一口径，两边差异只在类型写法
-- （VARCHAR(512)→TEXT、INTEGER→INT），这是 V1–V5 就定下的对应关系，不改变语义。
--
-- custom_title 是操作者的标题覆盖，feed.title 继续由抓取按站点报的值写；fetch_interval_minutes 是操作者的
-- 轮询间隔，feed.ttl_minutes 继续只表示站点 <ttl> 说的值。两列分开是这次改动的全部理由：同一列被两个所有者
-- 写，后写的那个总是赢，而操作者总是在下一次抓取时输。
--
-- 真正的门禁是这个文件名进了 MIGRATIONS 清单——缺任一侧的文件，MigrationDialectAlignmentTest 读资源时就抛
-- "missing migration"。

ALTER TABLE feed ADD COLUMN custom_title TEXT;
ALTER TABLE feed ADD COLUMN fetch_interval_minutes INT;
