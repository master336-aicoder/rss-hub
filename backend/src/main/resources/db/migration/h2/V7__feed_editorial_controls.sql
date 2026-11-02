-- F10（订阅管理可编辑）：两个只属于操作者的列。
--
-- custom_title：之前操作者改标题只能改 feed.title，而 feed.title 每次抓取都会被 recordMetadata 的
-- coalesce(?, title) 覆盖回站点自己报的名字——改一次活一轮就没了。发布方拥有的列继续由抓取写，操作者的
-- 覆盖另存一列，读取侧用 coalesce(custom_title, title)。
--
-- fetch_interval_minutes：同理，"轮询间隔"原先写在 feed.ttl_minutes 里，而那一列也是 RSS <ttl> 的落点，
-- 操作者设的 15 分钟会在下一次带 ttl 的抓取后被站点值顶掉。现在 feed.ttl_minutes 只表示"站点说的"，
-- feed.fetch_interval_minutes 只表示"操作者要的"，两列都不再互相覆盖。
--
-- 必须与 ../postgresql/V7__feed_editorial_controls.sql 同步：本文件不建表，MigrationDialectAlignmentTest
-- 对它是两边空集相等；把它列进 MIGRATIONS 的价值在于漏改一个方言时"missing migration"会立刻炸。

ALTER TABLE feed ADD COLUMN custom_title VARCHAR(512);
ALTER TABLE feed ADD COLUMN fetch_interval_minutes INTEGER;
