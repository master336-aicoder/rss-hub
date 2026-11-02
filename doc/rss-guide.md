# RSS 订阅与发布：功能、场景与技术规范

> 文档位置：`doc/rss-guide.md`
> 适用读者：需要**发布**订阅源的内容站/服务作者，需要**消费**订阅源的聚合、监控、AI 加工类开发者。
> 最后更新：2026-10-17（已按规范原文与厂商官方文档联网复核，全部修正记录见[第 21 节](#21-联网核对记录2026-10-17)）
> 速查页：[`doc/rss-quickref.md`](./rss-quickref.md)

---

## 目录

1. [本质与定位](#1-本质与定位)
2. [功能全景](#2-功能全景)
3. [常用场景](#3-常用场景)
4. [三种主流格式与选型](#4-三种主流格式与选型)
5. [RSS 2.0 规范详解](#5-rss-20-规范详解)
6. [Atom 规范详解](#6-atom-规范详解)
7. [JSON Feed 规范详解](#7-json-feed-规范详解)
8. [扩展命名空间](#8-扩展命名空间)
9. [播客（Podcast）专项规范](#9-播客podcast专项规范)
10. [HTTP 层：内容类型、缓存与状态码](#10-http-层内容类型缓存与状态码)
11. [发现、订阅与迁移](#11-发现订阅与迁移)
12. [实时推送：WebSub](#12-实时推送websub)
13. [鉴权与私有源](#13-鉴权与私有源)
14. [编码与合法性硬性要求](#14-编码与合法性硬性要求)
15. [生产端示例代码](#15-生产端示例代码)
16. [消费端示例代码](#16-消费端示例代码)
17. [发布方最佳实践清单](#17-发布方最佳实践清单)
18. [常见故障与排查](#18-常见故障与排查)
19. [生态与工具索引](#19-生态与工具索引)
20. [规范来源](#20-规范来源)
21. [联网核对记录](#21-联网核对记录2026-10-17)

> 需要一眼查字段/取值？配套速查页：[`doc/rss-quickref.md`](./rss-quickref.md)

---

## 1. 本质与定位

RSS 是一个**基于 XML 的轻量内容分发格式**。发布方按固定结构输出一份可公开访问的文件（feed，订阅源），其中是一组条目（item / entry）的列表；消费方（阅读器、脚本、搜索引擎、AI 流水线）定期拉取该文件，通过对比条目 ID 判断哪些是新增内容。

它解决的是**信息可达性的方向问题**：

| 模式 | 谁付出成本 | 结果 |
|---|---|---|
| 无订阅机制（人访问站） | 用户 | 用户需要记住站点、主动回访，受首页改版和算法排序影响 |
| 平台关注 / 推送 | 平台 | 关系与数据锁在平台内，算法决定可见性 |
| RSS 订阅 | 发布方产出文件 + 阅读器负责拉取 | 用户掌握信息流；内容与平台解耦 |

关键属性：

- **只读、单向**：RSS 本身不含回信、点赞、评论等社交语义（Atom Publishing Protocol 补充了写入，见第 6.4 节）。
- **去算法化**：条目顺序由发布方的 `pubDate` / `updated` 决定，客户端不会插入广告、热搜或推荐位。
- **结构化**：输出是机器可读的数据，天然适合过滤、转推、存档、摘要、向量入库。
- **无身份绑定**：订阅一个 feed 通常不需要登录，因此不产生关注关系、不留平台画像。
- **可移植**：站点换域名、改前端不影响订阅者——只要 feed URL 通过 301 或迁移声明保持可达。

---

## 2. 功能全景

### 2.1 订阅侧（消费）能做什么

| 能力 | 说明 |
|---|---|
| 聚合阅读 | 一个界面读完几十上百个来源，无需逐个访问 |
| 增量识别 | 靠 `guid` / `atom:id` 判断"未读"，跨设备同步状态 |
| 关键词过滤与规则 | 命中特定词才进入收件箱，用于监控告警 |
| 全文/摘要切换 | 阅读器可渲染 `content:encoded` 全文，也可只读 `description` 摘要 |
| 离线与同步 | 阅读器抓取的条目副本可离线读，多端同步已读状态 |
| 二次加工 | 转邮件、转 IM 消息（钉钉/企微/Telegram/Slack）、转摘要、转语音、转 AI 问答语料 |
| 存档 | 自建 Miniflux/FreshRSS 保留历史，原站删除后仍有副本 |
| 检索 | 自建实例可全文搜索历史条目 |

### 2.2 发布侧（供给）能做什么

| 能力 | 说明 |
|---|---|
| 被第三方收录 | 阅读器、播客目录、聚合站、日报工具只认 feed URL |
| 低成本分发 | 一个静态 XML 文件即可，无需数据库、后端、账号体系 |
| 绕过平台限流 | 内容自主可控，不受算法降权和封禁影响 |
| 机器友好 | 搜索引擎、监控脚本、爬虫替代方案可稳定解析，比抓 HTML 便宜可靠 |
| 多粒度输出 | 全站源 + 分类源 + 标签源，让订阅者自选信息密度 |
| 变更公告 | 版本发布、changelog、安全公告的标准分发方式 |
| 数据回流 | 通过 feed 拉取量、WebSub 回调数可粗略估计"真实订阅"规模 |

---

## 3. 常用场景

| 场景 | 典型用法 | 关键点 |
|---|---|---|
| 博客 / 独立站更新 | 新文章即时推送 | 提供分类源、稳定 GUID、`<link rel=alternate>` 发现标签 |
| 新闻 / 资讯媒体 | 按栏目拆分源 | 条目量大，需分页或限制数量，摘要即可 |
| 播客 | RSS enclosure 承载音频 | 必须 RSS 2.0 + `itunes:` 命名空间，见第 9 节 |
| 软件版本与 changelog | Release 页输出源，订阅后自动告警 | `guid` 用 release ID；正文含 breaking changes |
| 安全公告 / CVE | 官方 NVD、厂商 advisory 提供源 | 关键词过滤（CVE、CVSS 阈值）后接工单系统 |
| 竞品 / 行业监控 | 订阅对手博客、定价页、招聘页、行业媒体 | 命中关键词→IM 通知；配合 diff 检测正文变化 |
| 科研跟进 | arXiv API 返回 Atom，按作者/分类订阅 | 拉取后做 LLM 摘要、去重、入库 |
| 内部信息聚合 | 团队 Wiki、公告板、CI 结果、运维事件输出源 | 需鉴权（第 13 节），或用私有 token |
| 无 API 站点的源化 | RSSHub 等把社交/视频/电商页面转成 feed | 注意源稳定性与被封风险，宜自建副本 |
| 个人数字花园 / 静态站 | Hugo、Jekyll、Eleventy 一键生成源 | 静态托管，天然可扩展 |
| 内容再分发 | 抓源→翻译/摘要/改写→推送或再发布 | 遵守版权与 `terms_of_use`，保留原链 |
| AI 语料与 RAG | feed 作为增量、结构化、带时间戳的数据源 | 时间戳 + 稳定 ID 便于增量索引与溯源 |
| 全站公告降级通知 | 邮件列表失效、平台不可用时的备用通道 | 保持 feed URL 十年不变是承诺 |

---

## 4. 三种主流格式与选型

| 维度 | RSS 2.0 | Atom 1.0 | JSON Feed 1.1 |
|---|---|---|---|
| 载体 | XML | XML | JSON |
| 规范 | UserLand RSS 2.0 规范 + RSS Profile（事实标准，非 IETF） | **RFC 4287** | 社区规范（jsonfeed.org） |
| 顶层元素 | `<rss version="2.0">`（`version` 属性必填） | `<feed xmlns="...">` | 对象，必填 `version` / `title` / `items` |
| 条目 | `<item>` | `<entry>` | `items[]` |
| 日期格式 | RFC 822 日期语法（四位年份优先），如 `Sat, 17 Oct 2026 10:00:00 GMT` | **RFC 3339**，如 `2026-10-17T10:00:00Z` | RFC 3339 字符串 |
| 时间戳字段 | `pubDate` / `lastBuildDate` | `updated`（必填）/ `published`（可选） | `date_published` / `date_modified` |
| 唯一标识 | `guid`（规范上可选，实际必备） | `id`（**必填，且每条目恰好一个**） | `id`（**必填**）；`url` 可选但应提供 |
| 修改语义 | 无标准"更新时间" | `updated` 明确区分发布与修改 | `date_modified` |
| 多媒体 | `enclosure`（规范未明文数量，实践每 item 一个） | `link rel="enclosure"`（可多条） | `attachments[]`（可多条） |
| 全文/摘要分离 | `description` + `content:encoded` | `summary` + `content`（有 `type`） | `content_text` / `content_html` |
| 作者字段 | `author`（`email (name)` 约定，邮箱格式不强制） | `author/name`（结构化，`name` 必填） | `authors[].name` |
| 播客目录支持 | ✅ 唯一选择 | ❌ | ❌ |
| 阅读器兼容性 | 极广 | 极广 | 主流阅读器基本支持，长尾客户端可能不支持 |
| 解析成本 | 需 XML 解析 + 命名空间处理 | 同 RSS，但必填项严格、更易校验 | 几乎为零，字段扁平 |
| 书写友好度 | 需转义、易踩 XML 坑 | 同左 | 前端/脚本作者最友好 |

**选型建议**

- 面向**公众博客/新闻**：RSS 2.0 与 Atom 二选一即可；要同时照顾最老的客户端，用 RSS 2.0；新项目、需要精确"更新"语义，用 Atom。
- **播客**：必须 RSS 2.0 + `itunes:`。
- **主要给脚本/AI 消费，或团队以前端/Node 为主**：JSON Feed 最省事；稳妥做法是 JSON Feed + 一个 RSS 2.0 镜像源（同一数据两处输出）。
- 兼容并蓄：同一份内容同时输出 `.rss`、`.atom`、`feed.json` 三个 URL，并在 HTML 中放三个 `rel=alternate` 标签，成本极低。

> 历史注：还有 **RSS 0.9x / RSS 1.0**（RDF Site Summary，基于 RDF 三元组 + Dublin Core）。RSS 1.0 与 2.0 是**不同 lineage**，元素名相似但结构不同，别混用。今天除少数老牌站点外基本不必再生产 RSS 1.0，只需能解析。

---

## 5. RSS 2.0 规范详解

结构：`rss(version="2.0") > channel > (title, link, description, item*)`

> 两条容易忽略的硬性约束：`<rss>` 的 `version` 属性必须是 `2.0`；`channel/link`、`item/link`、`guid`（isPermaLink 为 true 时）、`image/url` 等 URL 值必须以 IANA 注册的 URI scheme 开头（`http`、`https`、`news`、`mailto`、`ftp`）。

### 5.1 `channel` 元素

**必填**

| 元素 | 说明 |
|---|---|
| `title` | 频道名 |
| `link` | 站点主页 URL（绝对地址） |
| `description` | 频道简介，会展示在阅读器目录里 |

**可选**

| 元素 | 说明 |
|---|---|
| `language` | `zh-CN`、`en-us` 等（RFC 5646 / 旧 ISO 639 形式） |
| `copyright` | 版权声明 |
| `managingEditor` | 内容负责人邮箱，如 `someone@example.com (张三)` |
| `webMaster` | 技术负责人邮箱 |
| `pubDate` | 频道上次发布时间 |
| `lastBuildDate` | 频道内容上次变更时间（格式同 `pubDate`） |
| `category` | 分类，可重复；可嵌套 `<subclassification>`；`domain` 属性指向分类体系 |
| `generator` | 生成软件标识 |
| `docs` | 规范链接；常见值 `https://www.rssboard.org/rss-specification`，生成库也常写校验器版本页 `https://validator.w3.org/feed/docs/rss2.html` |
| `cloud` | 已近乎废弃的 RPC 通知机制（`domain`/`port`/`path`/`registerProcedure`/`protocol` 属性），用于让服务器通知阅读器有新内容；今天由 WebSub 取代，不建议实现 |
| `ttl` | 建议缓存分钟数（见第 10 节） |
| `image` | `url` + `title` + `link` 必填，`width`(≤144,默认88)/`height`(≤400,默认31)/`description` 可选 |
| `textInput` | 早期搜索框约定；RSS Profile 明确"不鼓励使用"（实际采用率 <1%），不建议再写 |
| `skipHours` / `skipDays` | 建议抓取方避开的时段：小时为 GMT 0–23（最多 24 个 `<hour>`），天为 `<day>` 英文名 |

### 5.2 `item` 元素

规范原文：item 的所有子元素都是可选的，但 **`title` 与 `description` 至少有一个必须存在**。

| 元素 | 说明 | 建议 |
|---|---|---|
| `title` | 条目标题 | 建议保留；纯摘要型条目可靠 `description` 顶上 |
| `link` | 条目永久链接 | 建议保留；必须绝对地址 |
| `description` | 摘要或正文，允许内嵌 HTML（**必须转义**或用 CDATA） | 无 `title` 时必须有它 |
| `author` | `email (name)` 形式；邮箱格式规范未强制，多数解析器容错 | 建议用 `dc:creator` 补充（RSS 2.0 无 `contributor`，需要贡献者请用 `dc:contributor`） |
| `category` | 标签/分类，可重复，支持 `domain` | 强烈建议 |
| `comments` | 评论区页面 URL | 可选 |
| `enclosure` | `url` + `length`(字节) + `type`(MIME)；规范未明文"只能一个"，作者意图是一个 | 播客/音频必填 |
| `guid` | 全局唯一、**永不变化**的标识；`isPermaLink` 默认 `true`，此时阅读器可假设它就是可打开的永久链接 | **必填级推荐** |
| `pubDate` | 发布时间，RFC 822 日期语法（四位年份优先） | 强烈建议 |
| `source` | 转载来源，元素内容为源频道标题，`url` 属性为源地址 | 转载场景建议 |

### 5.3 完整示例

```xml
<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"
     xmlns:atom="http://www.w3.org/2005/Atom"
     xmlns:content="http://purl.org/rss/1.0/modules/content/"
     xmlns:dc="http://purl.org/dc/elements/1.1/">
  <channel>
    <title>示例技术周报</title>
    <link>https://example.com/</link>
    <description>关于分布式系统与可观测性的每周更新</description>
    <language>zh-CN</language>
    <copyright>Copyright 2026 Example</copyright>
    <managingEditor>editor@example.com (值班编辑)</managingEditor>
    <webMaster>ops@example.com (运维)</webMaster>
    <lastBuildDate>Sat, 17 Oct 2026 09:30:00 +0800</lastBuildDate>
    <ttl>60</ttl>
    <generator>example-cms 3.2</generator>
    <atom:link href="https://example.com/feed.xml" rel="self"
               type="application/rss+xml"/>

    <image>
      <url>https://example.com/logo-88x31.png</url>
      <title>示例技术周报</title>
      <link>https://example.com/</link>
      <width>88</width>
      <height>31</height>
    </image>

    <item>
      <title>如何设计一个不会挂的订阅抓取器</title>
      <link>https://example.com/posts/robust-feed-fetcher</link>
      <guid isPermaLink="true">https://example.com/posts/robust-feed-fetcher</guid>
      <pubDate>Sat, 17 Oct 2026 09:00:00 +0800</pubDate>
      <dc:creator>li@example.com (李工)</dc:creator>
      <category domain="https://example.com/tags/">architecture</category>
      <comments>https://example.com/posts/robust-feed-fetcher#disqus</comments>
      <description><![CDATA[
        <p>条件请求、退避重试、<strong>稳定 ID</strong> 三件事。</p>
      ]]></description>
      <content:encoded><![CDATA[
        <p>全文正文……</p>
      ]]></content:encoded>
    </item>
  </channel>
</rss>
```

要点解释：

- `<atom:link rel="self">` 出现在 RSS 2.0 里是**合法且推荐**的：RSS 2.0 核心规范只规定"允许命名空间扩展"，`atom:link rel="self"` 的明确许可与"每个源都应携带"的要求写在 **RSS Profile §5.1**，用于声明"这个源自身的规范 URL"，方便迁移检测。
- `description` 与 `content:encoded` 分离：前者给阅读器列表页显示摘要，后者给支持全文渲染的客户端显示正文。只写 `description` 会导致全文党抱怨，只写 `content:encoded` 会让老客户端空白。
- `guid` 若用 URL，必须与 `link` 一样是绝对地址；否则用 `isPermaLink="false"` + UUID。

---

## 6. Atom 规范详解

Atom Syndication Format 是 IETF 标准（**RFC 4287**），字段约束比 RSS 更严格，因而校验更容易、错误更少。写入侧的 **Atom Publishing Protocol（RFC 5023）** 是另一份独立标准，并未修订或取代 RFC 4287。

### 6.1 `feed` 元素

| 元素/属性 | 必填 | 说明 |
|---|---|---|
| `xmlns` | ✅ | 固定 `http://www.w3.org/2005/Atom` |
| `id` | ✅（恰好一个） | 源的永久 IRI 级标识；规范只要求"自证唯一 + 迁移/再发布不得改变"，`tag:`/`urn:uuid` 属社区惯例（Tag URI 定义见 RFC 4151） |
| `title` | ✅（恰好一个） | 源标题 |
| `updated` | ✅（恰好一个） | 源内任一内容最近变更时间（RFC 3339；无数字偏移时用大写 `Z`，日期与时间分隔用大写 `T`） |
| `author` | 条件必填 | 原文规则：**feed 必须含至少一个 author，除非所有子 entry 各自至少有一个 author**；`name` 必填，`uri`/`email` 可选 |
| `link` | ❌（但 `rel="self"` 是 SHOULD 级推荐） | 属性确切为 `href`（必填）、`rel`、`type`、`hreflang`、`title`、`length`；**没有 `mediaTypes`**（那是 Atom 0.3 草案的字段名） |
| `subtitle` / `icon` / `logo` / `rights` / `generator` | ❌（各至多一个） | 简介 / 图标 URI / Logo URI / 版权许可说明 / 生成器（`uri`、`version` 属性） |
| `category` | ❌ | `term` 必填，`scheme`、`label` 可选 |
| `contributor` | ❌ | 贡献者 |

### 6.2 `entry` 元素

entry 必填：**`id`、`title`、`updated` 各恰好一个**（title 无"用 content 替代"的豁免）。两条一致性约束：

- 没有 `content` 的 entry，**必须**至少有一个 `rel="alternate"` 的 `link`。
- 当 `content` 带 `src` 属性、或 `content` 是 Base64 编码的非 text/非 XML 媒体类型时，**必须**有 `summary`。

| 元素 | 必填 | 说明 |
|---|---|---|
| `id` | ✅ | 永久唯一，同一文档内重复 `id` 视为同一条目（此时 `updated` 应不同）；**不得因内容修改而变** |
| `title` | ✅ | 可为 text/html/xhtml |
| `updated` | ✅ | 本次修改时间 |
| `published` | ❌（推荐） | 首次发布时间；区分"发布"与"修订"的关键 |
| `author` | ❌（feed 有 author 时可继承，否则必填） | `name` 必填，`uri`/`email` 可选 |
| `link` | ❌ | `rel="alternate"` 提供原文地址；缺失时必须有 `content` |
| `content` | ❌（至多一个） | `type` MAY 为 `text`/`html`/`xhtml`，否则 MUST 是 MIME 且不得是复合类型；缺省视为 `text`。`xhtml` 须内联 XHTML 命名空间的 `div`；`text/html` 时不得有子元素；有 `src` 时元素本身须为空 |
| `summary` | ❌（见上两条约束） | 摘要 |
| `category`, `contributor`, `source`, `rights` | ❌ | 同 feed 语义 |

### 6.3 示例

```xml
<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <id>urn:uuid:6e8bc430-9c3a-11d9-a7e7-00ff098f2a08</id>
  <title>示例技术周报</title>
  <subtitle>分布式系统与可观测性</subtitle>
  <link rel="self" type="application/atom+xml"
        href="https://example.com/atom.xml"/>
  <link rel="alternate" type="text/html" href="https://example.com/"/>
  <updated>2026-10-17T09:30:00+08:00</updated>
  <author><name>Example 编辑部</name><email>editor@example.com</email></author>
  <rights>© 2026 Example, CC BY 4.0</rights>
  <generator uri="https://example.com/cms" version="3.2">example-cms</generator>

  <entry>
    <id>https://example.com/posts/robust-feed-fetcher</id>
    <title>如何设计一个不会挂的订阅抓取器</title>
    <link rel="alternate" type="text/html"
          href="https://example.com/posts/robust-feed-fetcher"/>
    <published>2026-10-17T09:00:00+08:00</published>
    <updated>2026-10-17T11:02:00+08:00</updated>
    <author><name>李工</name></author>
    <category term="architecture" label="架构"/>
    <summary>条件请求、退避重试、稳定 ID。</summary>
    <content type="html">&lt;p&gt;全文正文……&lt;/p&gt;</content>
  </entry>
</feed>
```

### 6.4 相关协议与链接关系注册

- **Atom Publishing Protocol（AtomPub / APP，RFC 5023）**：在 Atom 之上定义 POST/PUT/DELETE 的写入协议，可用于远程投稿、CMS 写入、评论服务端。今天多被 REST/JSON API 取代，但仍是"以 Atom 为数据模型的写作接口"的参考；`application/atom+xml` 的 IANA 注册也同时引用 RFC 4287 与 RFC 5023。
- **RFC 4946（Atom License Extension）**：给 feed/entry 增加 `atom:link rel="license"`。
- **RFC 4685（Atom Threading Extensions）**：线程/回复扩展（`thr:in-reply-to`），**不是**分类扩展——`atom:category` 本就在 RFC 4287 核心里。
- **RFC 5005（Feed Paging and Archiving）**：定义 `rel="first"`、`rel="last"`、`next-archive`、`prev-archive`、`fh:complete` 元素，用于"分页 + 归档"的大源。注意：坊间常说的"partial feeds / no feed / 付费墙降级源"**从未成为 IETF 标准**（相关早期草案已过期），只能算厂商约定。
- **`rel="payment"`** 并非废弃字段，它随 RFC 8288 的 Link Relations 注册表保留下来，但实际识别它的阅读器很少。

---

## 7. JSON Feed 规范详解

面向脚本与现代前端的替代格式：字段扁平、无 XML 转义地狱、直接 `JSON.parse`。

```json
{
  "version": "https://jsonfeed.org/version/1.1",
  "title": "示例技术周报",
  "home_page_url": "https://example.com/",
  "feed_url": "https://example.com/feed.json",
  "description": "分布式系统与可观测性的每周更新",
  "icon": "https://example.com/logo.png",
  "favicon": "https://example.com/favicon.ico",
  "language": "zh-CN",
  "expired": false,
  "authors": [{ "name": "Example 编辑部", "url": "https://example.com/team" }],
  "items": [
    {
      "id": "https://example.com/posts/robust-feed-fetcher",
      "url": "https://example.com/posts/robust-feed-fetcher",
      "title": "如何设计一个不会挂的订阅抓取器",
      "external_url": null,
      "date_published": "2026-10-17T09:00:00+08:00",
      "date_modified": "2026-10-17T11:02:00+08:00",
      "authors": [{ "name": "李工" }],
      "tags": ["架构", "运维"],
      "summary": "条件请求、退避重试、稳定 ID。",
      "content_text": "纯文本正文……",
      "content_html": "<p>全文正文……</p>",
      "attachments": [
        {
          "url": "https://cdn.example.com/audio/ep12.mp3",
          "mime_type": "audio/mpeg",
          "duration_in_seconds": 1830,
          "size_in_bytes": 44236800
        }
      ]
    }
  ]
}
```

规范要点：

- 顶层**必填**：`version`（规范 URL）、`title`、`items`（数组）。`home_page_url` 与 `feed_url` 名义可选但规范写明"公开发布的源应视为必填"。
- `items[].id` **必填**（1.0 与 1.1 都是），且数字必须转成字符串；`url` 可选但规范要求"除特殊情形（如纯状态更新）外应始终存在"。`title` 可省略（微搏类常见）。
- `content_text` 与 `content_html` 至少提供一个；**HTML 只允许出现在 `content_html`**。
- `attachments[]` 中 `url` 与 `mime_type` 必填；同一资源的不同格式用相同 `title` 表达。
- 分页：`next_url` 不得等于 `feed_url`，也不得等于此前任何一次的 `next_url`。
- 扩展字段名必须以 `_` + 字母开头。
- 所有时间戳 RFC 3339。
- 空 `items` 数组表示"源仍然有效但暂无内容"；"源已废弃"用 `expired: true` 表达。
- 建议单个 JSON Feed 保持在 100KB 以内（规范无硬上限，但条目过多应分页）。

1.0 → 1.1 的实际差异：唯一被列入 Deprecation 的是 `author`（顶层与 item）→ 改用 `authors` 数组，阅读器须优先读 `authors`；1.1 新增 `language`（顶层与 item）、item 级 `authors`、以及"id 数字须强制转字符串"。**`expired`、`attachments[].size_in_bytes`/`duration_in_seconds`、`hubs` 在 1.0 就已存在**，不属于 1.1 新增。

Content-Type：规范要求用 `application/feed+json` 提供服务，客户端应优先接受它并回退接受 `application/json`；注意 `application/feed+json` **未在 IANA 注册**，`application/json` 才是（RFC 8259）。

---

## 8. 扩展命名空间

这些是"在 RSS/Atom 里合法借用外部词汇"的事实标准模块，按需声明。

| 模块 | 命名空间 | 用途 | 常用元素 |
|---|---|---|---|
| Dublin Core | `http://purl.org/dc/elements/1.1/` | 补作者、语言、贡献者 | `dc:creator`、`dc:contributor`、`dc:date`、`dc:language`、`dc:subject` |
| Content | `http://purl.org/rss/1.0/modules/content/` | RSS 2.0 的全文正文 | `content:encoded` |
| Syndication | `http://purl.org/rss/1.0/modules/syndication/` | 更新频率与保留策略 | `sy:updatePeriod`（hourly/daily/weekly/monthly/yearly）、`sy:updateFrequency`、`sy:retention` |
| Slash | `http://purl.org/rss/1.0/modules/slash/` | 评论数等计数（源自 Slashdot） | `slash:comments` |
| Media RSS | `http://search.yahoo.com/mrss/` | 多段音视频、缩略图、描述、评分 | `media:content`（可嵌套）、`media:thumbnail`、`media:description`、`media:credit`、`media:category`、`media:rating` |
| GeoRSS | `http://www.georss.org/georss`（**无尾斜杠**） | 条目地理信息 | `georss:point`、`georss:line`、`georss:polygon`、`georss:box`、`georss:where`（GML） |
| Creative Commons | `http://backend.userland.com/creativeCommonsRssModule`（另有并存的 `http://web.resource.org/rss/1.0/modules/cc/`） | 许可 | `cc:license` |
| WebSub | 无需自定义命名空间 | 推送发现 | `<atom:link rel="hub" href="https://hub.example/"/>`（RSS 2.0 里也这么写；规范首选 HTTP `Link:` 响应头，其次 XML/HTML 内嵌 link） |
| Trackback | `http://madskills.com/public/xml/rss/module/trackback/`（惯例值，宜按目标客户端复核） | 反向引用 | `trackback:ping` |

选择原则：

- 需要**全文** → `content:encoded`（RSS 2.0）或 `atom:content`（Atom）。
- 需要**多格式媒体/缩略图/时长** → `media:content`，比 `enclosure` 灵活（可一条挂多份文件与多张缩略图；RSS 2.0 的 `enclosure` 虽未明文禁止多个，但作者意图与主流实现都是一个）。
- 需要**地图/位置类内容** → GeoRSS，部分阅读器有原生支持。
- 别自造命名空间前缀；不认识的扩展，消费端必须**忽略而不报错**（XML 处理的健壮性要求）。

---

## 9. 播客（Podcast）专项规范

播客是 RSS 至今仍不可替代的最大场景：Apple Podcasts 官方要求"feed URL 必须符合 RSS 2.0 规范"，节目与剧集都以 RSS 2.0 + `itunes:` 命名空间为载体，音频文件通过 `enclosure` 引用（Atom 不被接受）。

Apple 现行**必填**清单（缺任一即校验失败）：至少一集、节目封面（artwork）、`title`、`description`、`language`、`itunes:explicit`、`itunes:category`、`enclosure` 三要素（url/length/type）、每集唯一且不变的 `guid`。`itunes:author` 与 `itunes:owner` **不在必填清单内**，但 owner 邮箱用于节目认领与通知，实践中必写。

必需与强烈推荐元素：

```xml
<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
     xmlns:podcast="https://podcastindex.org/namespace/1.0"
     version="2.0">
  <channel>
    <title>示例播客</title>
    <link>https://example.com/podcast/</link>
    <language>zh-cn</language>
    <description>节目整体介绍，不超过 4000 字符</description>
    <itunes:author>主播名 / 出品方</itunes:author>
    <itunes:owner>
      <itunes:name>技术负责人</itunes:name>
      <itunes:email>tech@example.com</itunes:email>
    </itunes:owner>
    <itunes:image href="https://cdn.example.com/artwork-3000x3000.jpg"/>
    <image><url>https://cdn.example.com/artwork-1400x1400.jpg</url>
           <title>示例播客</title><link>https://example.com/podcast/</link></image>
    <itunes:explicit>false</itunes:explicit>
    <itunes:category text="Technology"/>
    <itunes:category text="Business">
      <itunes:category text="Entrepreneurship"/>
    </itunes:category>
    <itunes:type>episodic</itunes:type>
    <podcast:locked owner="tech@example.com">yes</podcast:locked>

    <item>
      <title>#12 订阅系统的可靠性</title>
      <itunes:title>订阅系统的可靠性</itunes:title>
      <link>https://example.com/podcast/12</link>
      <guid isPermaLink="false">https://example.com/podcast/12</guid>
      <pubDate>Sat, 17 Oct 2026 08:00:00 +0800</pubDate>
      <itunes:episodeType>full</itunes:episodeType>
      <itunes:episode>12</itunes:episode>
      <itunes:season>2</itunes:season>
      <itunes:duration>1905</itunes:duration>
      <itunes:explicit>false</itunes:explicit>
      <!-- itunes:duration 支持秒数或 hh:mm:ss，官方/规范推荐秒数 -->
      <itunes:summary>本期讨论条件请求与稳定 ID。</itunes:summary>
      <description>本期讨论条件请求与稳定 ID。</description>
      <enclosure url="https://cdn.example.com/audio/ep12.mp3"
                 length="44236800" type="audio/mpeg"/>
    </item>
  </channel>
</rss>
```

硬性注意事项（大量 podcast feed 死在这几条上）：

1. **`enclosure` 三要素**：`url` 指向"可用且受支持的媒体文件"、`length` 为字节数、`type` 为准确 MIME（`audio/mpeg` / `audio/mp4`）。官方文档的措辞是"必须开启 HTTP HEAD 请求与 byte-range 请求支持"；"length 必须与真实字节完全一致、URL 不得重定向、不得做 Referer 校验"属于校验器与托管商层面的经验规则，但确实是最常见的拒收原因。同一 feed 里重复的 enclosure URL 会被忽略。
2. **byte-range 支持决定播放器能否拖动进度**：CDN/存储需响应 `Range: bytes=` 并返回 206。
3. **`guid` 永不变**：官方原文"所有剧集必须携带全局唯一、永不改变的 GUID"。改 GUID 会造成重复剧集、Analytics 失真；同 URL 换音频文件是允许的，换 GUID 不是。
4. 封面图：正方形，**1400×1400 至 3000×3000（首选 3000×3000）**，**PNG 或 JPG**（GIF 实际不被支持），**不得含透明/alpha 通道**，RGB 色彩空间；图片服务器需允许 HTTP HEAD 与 byte-range。注：Apple 现行文档**未公布文件大小上限**，流传的"≤512KB"来自旧 iTunes 措辞与托管商文档，宜以最新文档复核。
5. feed 必须是**合法 XML**，且 Apple 明确要求 XML 声明位于文件**开头**并带 `encoding="UTF-8"`；"禁止 BOM"未见于 Apple 文档，属校验器/最佳实践层面的规则。裸 `&`、未闭合标签都会导致目录直接判定"feed 无效"。
6. **换 feed 地址的官方流程**：老地址返回 **301**，并在新 feed 里写 `<itunes:new-feed-url>`，**两者至少同时保留 4 周**，GUID 保持不变；不要把迁移指向 Apple Podcasts Connect 的 Mirror URL。用第三方托管时通常只做 redirect 即可。
7. `<itunes:type>` 取 `episodic`（倒序，最新在上）或 `serial`（按集序正序，第一集在上）；**默认分类为 episodic**。`serial` 必须有整数集号（不支持小数），缺 season 号会落入"Unknown Season"并影响自动下载。
8. `<itunes:explicit>` 现行只接受 **`true` / `false`**；历史遗留的 `yes` / `no` / `clean` 应视为已废弃写法。`itunes:episode`、`itunes:season`、`itunes:episodeType`（`full`/`trailer`/`bonus`）均为官方元素。节目与剧集描述上限 **4000 字符**。
9. 播客扩展命名空间（`podcast:`，URI `https://podcastindex.org/namespace/1.0`，由 Podcasting 2.0 / PSP-1 定义）：`podcast:locked`（值 `yes`/`no`，`owner` 属性为邮箱，用于导入时验证所有权）、`podcast:transcript`、`podcast:funding`、`podcast:value`（含 `value:recipient`，Value4Value 打赏）、`podcast:person` 等确实存在；主流目录支持度不一，未知元素对老客户端应是无害的。
10. **条目数量与体积**：Apple **未公布** feed 文档大小上限，也未限制剧集条数（只要求"至少一集"）；所谓 8MB、125/300 条上限来自旧闻或个别托管商（如可配置 1–2000）。Podcasting 2.0 命名空间**不存在** `podcast:paging` 标签，PSP-1 也未定义分页标签。因此"精简条目、控制体积"只能作为工程经验（源越大，抓取与校验越慢），不要把它当成合规要求。

---

## 10. HTTP 层：内容类型、缓存与状态码

一个 feed 的行为一半由文件内容决定，一半由 HTTP 头决定。

### 10.1 Content-Type

| 格式 | 正确值 |
|---|---|
| RSS 2.0 | `application/rss+xml`（`text/xml` 兼容，`text/rss` 不规范） |
| Atom | `application/atom+xml` |
| RDF/RSS 1.0 | `application/rdf+xml` |
| JSON Feed | `application/feed+json`（`application/json` 也被广泛接受） |
| 通用 | `application/xml`（可接受） |

IANA 注册情况需分清，别在文档里称"已注册"：`application/atom+xml`（注册于 RFC 4287 / RFC 5023）、`application/rdf+xml`（RFC 3870）、`application/json`（RFC 8259）、`application/xml`（RFC 7303）在 IANA 注册表内；**`application/rss+xml` 与 `application/feed+json` 均未见 IANA 注册**，前者是事实标准值（由 RSS 2.0 / 早期草案社区使用），后者由 JSON Feed 规范建议。

发成 `text/html` 会导致浏览器把 XML 当网页渲染、阅读器收到后无法识别；发成 `application/octet-stream` 会被迫下载。XML 声明里的 `encoding` 与实际字节编码必须一致。

### 10.2 缓存与条件请求（发布方必做，消费方必用）

- **`ETag` / `If-None-Match`**：内容不变返回 `304 Not Modified`，零流量。
- **`Last-Modified` / `If-Modified-Since`**：同上，基于时间。
- **`Cache-Control`**：feed 应有短期缓存（如 `public, max-age=60, must-revalidate`），CDN 层可再拉长；不要用 `no-store`。
- **`ttl`（RSS 2.0）**：单位**分钟**，是"建议刷新间隔"，消费端把它当最小轮询间隔参考，不应更短。
- **`sy:updatePeriod`/`updateFrequency`**：粗粒度更新频率提示，实际支持率有限。
- **`Expires`**：可与 CDN 配合，但要与 `Cache-Control` 语义一致（后者优先）。
- **压缩**：`Content-Encoding: gzip/br` 对大 feed 效果显著；确认解压后字节校验（播客 `length` 是原始文件字节，别把压缩后的值写进去）。
- **消费端**：务必实现条件请求，否则你就是一个"每秒全量拉取几百个源"的 DDoS 制造者，很容易被 WAF 拉黑。

### 10.3 状态码语义（消费端处理策略）

| 状态码 | 含义 | 抓取端应做 |
|---|---|---|
| `200` | 正常 | 解析并更新缓存 |
| `301` | 永久迁移 | **更新订阅 URL**，别再请求老地址 |
| `302/307` | 临时跳转 | 跟随但不改订阅地址；长期 302 可能是滥用，记日志 |
| `304` | 未变更 | 保留旧条目，不报新内容 |
| `404` | 源已消失 | 保留原订阅，退避重试；连续多次（如 30 天）后标记失效 |
| `410` | Gone，显式永久删除 | **立即停止抓取**，提示用户取消订阅 |
| `429` | 限流 | 遵守 `Retry-After`，指数退避；不要重试风暴 |
| `5xx` | 服务端故障 | 指数退避重试（首次 5 分钟起，逐步到小时级） |
| `401/403` | 需要鉴权 | 提示补充凭证，别反复匿名硬试 |

抓取端还应具备：**超时（建议 10–30s 连接 + 读取上限）**、**响应体大小上限**（防"XML 炸弹"与超大 feed 打爆内存，建议解析前限制 5–20MB）、**并发限制**、**UA 标识 + `From:` 或文档页可联系**（便于站方白名单而非封禁）。

规范出处备查：304 定义于 RFC 9110 §15.4.5，410 Gone 定义于 RFC 9110 §15.5.11，`Retry-After` 定义于 RFC 9110 §10.2.3；但 **429 Too Many Requests 由 RFC 6585 补充**（不在 9110 正文的状态码清单里）。

---

## 11. 发现、订阅与迁移

### 11.1 Feed 自动发现

在 HTML `<head>` 放链接标签，阅读器和订阅按钮据此自动识别：

```html
<link rel="alternate" type="application/rss+xml"
      title="示例技术周报（全站）" href="https://example.com/feed.xml">
<link rel="alternate" type="application/atom+xml"
      title="示例技术周报（Atom）" href="https://example.com/atom.xml">
<link rel="alternate" type="application/feed+json"
      title="示例技术周报（JSON）" href="https://example.com/feed.json">
<link rel="alternate" type="application/rss+xml"
      title="分类：可观测性" href="https://example.com/tags/observability/rss">
```

- 每个源都要能被 `<link rel=alternate>` 发现，包括分类源。
- 页面 URL 与 feed URL **不要相同**，否则发现逻辑失效。
- `type` 写错会让发现器忽略。

### 11.2 常见 URL 约定

`/feed`、`/feed.xml`、`/rss`、`/rss.xml`、`/atom.xml`、`/index.xml`（Hugo）、`/feed.json`、`/feed?format=rss`。**约定不是标准**，但保持稳定比选哪个更重要。多语言站可 `/zh/feed.xml`、`/en/feed.xml` 或 `?lang=zh`。

### 11.3 迁移与"换地址"

- 老地址 301 → 新地址，保留**至少一年，理想永久**。
- Atom / RSS 里放 `atom:link rel="self"`（Atom 里是 SHOULD 级推荐），阅读器用它对比"我订阅的地址 vs 源自称的地址"，可自动纠正订阅。
- 播客按 Apple 官方流程：老地址 301 + 新 feed 内 `<itunes:new-feed-url>`，**至少同时保留 4 周**，GUID 不变；不要指向 APC 的 Mirror URL。
- 若源内容被他人转发，转载方应使用 `<source url="...">` 保留原始出处，避免用户误以为内容是转载站产的。

---

## 12. 实时推送：WebSub

RSS 的天生短板是**轮询延迟**：`ttl=60` 意味着新文章平均晚 30 分钟才被看到。WebSub（前身 Google PubSubHubbub，现为 W3C 推荐标准 `w3.org/TR/websub/`，最新一版为 2026-06-02 Recommendation）把"拉"变成"推"：

1. 发布源声明 hub：HTTP `Link:` 响应头优先，其次 feed/HTML 内的 `<atom:link rel="hub" href="https://websub.example/hub"/>`（后者在 RSS 2.0 里属事实惯例）。
2. 订阅方向 hub 发起订阅（POST `hub.mode=subscribe`），携带 `topic`（feed URL）、`callback`（自己的 URL）、可选 `secret`、`hub.verify`（`sync` 或 `async`）。
3. hub 向 callback 发一次**挑战**（GET，带 `hub.mode`、`hub.topic`、`hub.challenge`），订阅方须在 2xx 响应里**原样回显 challenge**，以证明对 callback 的控制权。2026 版推荐标准还要求回显时携带安全 Media Type 与 `X-Content-Type-Options: nosniff`，防反射型 XSS。
4. 发布方一有新内容就向 hub 发通知；hub 用 `secret` 计算 HMAC，通过 **`X-Hub-Signature: sha256=...`** 请求头（**不是** `hub.signature` 参数）向 callback POST 变更通告。
5. 订阅方收到通告后**仍要回源拉取 feed**（通告只说"变了"），或直接采用 hub 传输的 verified feeds 模式取完整内容。

要点：

- 必须校验 `X-Hub-Signature`，否则任何能猜到 topic 的人都能伪造推送。算法由订阅时的 `hub.secret` + hub 选择的 `sha1`/`sha256`/`sha384`/`sha512` 决定；规范原文的态度是"多数 hub 仍用 SHA-1 以兼容老订阅者，但由于 SHA-1 已被证明可被攻破，**应（SHOULD）至少使用 SHA-256**"——是强推荐，不是"SHA-1 已强制弃用"。
- callback 必须是 HTTPS（且不得含 fragment；公共 hub 强制）。
- 租约机制：hub 在验证响应里返回 `hub.lease_seconds`，**到期必须失效，不允许永久租约**；续订方式是**到期前重发一次同样的 subscribe 请求**（同 topic+callback 覆盖旧状态），规范里**没有 `hub.mode=renew` 这种提醒模式**。
- 与轮询并用：WebSub 是"加速"，不是替代——推送可能丢，轮询兜底。
- 公共 hub 可用性会变化（历史上最常用的 `pubsubhubbub.appspot.com` 目前无稳定运营证据），生产环境建议自建 hub 或选现役商用服务。
- 更"重"的替代方案是 ActivityPub（Fediverse 协议）与 RSS 桥（如把 Mastodon 账号转 feed）。

---

## 13. 鉴权与私有源

RSS 天生假设公开，但内部聚合、付费订阅场景需要私有源。常见做法按"侵入性从低到高"：

| 方案 | 做法 | 适用 | 缺点 |
|---|---|---|---|
| URL 内嵌 token | `/feed.xml?token=xxxx`（每用户/每客户端唯一） | 付费墙、临时分享 | 链接泄露即失守；会出现在访问日志/Referer；URL 变更导致用户重新订阅 |
| HTTP Basic | `Authorization: Basic`，阅读器多数原生支持 | 内部小团队 | 凭证明文过链路（必须 HTTPS）；部分阅读器已移除支持 |
| Bearer / OAuth | `Authorization: Bearer`，或用 OAuth 2.0 框架（RFC 6749）取令牌；Bearer 令牌的 HTTP 认证用法见 **RFC 6750**，桌面/移动客户端见 RFC 8252，威胁模型参考 RFC 6819 | 与 SSO 打通的企业内部源 | 阅读器基本不支持，只能自家客户端 |
| 客户端证书 / mTLS | 网络层控制 | 高安全要求 | 部署复杂 |
| IP / 内网限制 | 只在内网可达 | 内部聚合 | 移动办公、VPN 外失效 |
| 邮件兜底 | 源变更后私发摘要邮件 | 非技术用户 | 失去阅读器价值 |

实践建议：

- **内部源 + 自建阅读器**通常是最省事的组合：Miniflux 官方支持为每个源配置 `username`/`password`（HTTP 认证），由实例统一持凭证，用户侧无需配置；FreshRSS、Tiny Tiny RSS 的源级认证能力本次未能从官方文档直证，接入前请复核其当前设置项。
- 无论哪种方案，token 都要**可撤销、可轮换、按订阅者隔离**（便于发现泄露时精确定位）。
- 付费场景可用"`rel="payment"`（该链接关系在 RFC 8288 的 IANA 注册表里，并未废弃，但识别它的阅读器很少）+ 自家扩展"，或直接用两个源：公开源只给 N 条摘要，付费源给全量。注意"partial feeds / no feed"**不是 IETF 标准**（RFC 5005 讲的是 Feed Paging and Archiving，不是付费墙），早期相关草案已过期，只能算厂商约定。

---

## 14. 编码与合法性硬性要求

这些是最常见的"看起来正常、目录/阅读器却拒绝"的原因：

1. **XML 声明**：`<?xml version="1.0" encoding="UTF-8"?>` 必须**从文件起始处开始**，前面不能有空行、注释，Apple 校验文档也明确要求声明位于 feed 开头并带 `encoding="UTF-8"`。带 BOM 的 UTF-8 会让部分严格解析器报错（"禁止 BOM"属校验器/最佳实践层面规则，非 RSS 2.0 规范条款）。
2. **必须 UTF-8**（或声明与实际一致的编码）。中文站最常见的错误是文件实为 GBK 却声明 UTF-8。
3. **转义**：文本节点里 `<` → `&lt;`，`&` → `&amp;`。URL 里 query 参数的 `&` 是裸 `&`，**必须写成 `&amp;`**——这是第一大致命错误（如 `?a=1&amp;b=2`）。
4. **CDATA 使用**：仅在需要内嵌 HTML 时用 `<![CDATA[ ... ]]>`；CDATA 内部不能再有 `]]>`；不要把整段带 HTML 的内容既放 CDATA 又做转义（双重编码，用户看到 `&lt;p&gt;`）。
5. **绝对 URL**：`link`、`guid`、`enclosure.url`、`atom:link.href` 一律绝对地址。相对地址在阅读器聚合视图下会解析到阅读器自己的域名。
6. **日期格式**：RSS 2.0 规范原文是"所有日期时间遵循 **RFC 822** 的 Date and Time Specification，但年份可用两位或四位（**四位优先**）"——实践上等价于 RFC 1123 语法（`Sun, 17 Oct 2026 09:00:00 +0800`）；GMT/UTC 也可，但别用 `-0500 EST` 之类的缩写时区。Atom/JSON Feed 用 RFC 3339（`2026-10-17T09:00:00+08:00`，**带时区偏移**；无数字偏移时 Atom 要求大写 `Z`，日期与时间之间要求大写 `T`）。别用本地无偏移格式，会被各自解读成不同时刻。
7. **GUID 唯一且稳定**：同一 feed 内不得重复；条目被更新/重发布也不得改变 GUID。用自增数字/时间戳作 GUID 是错的——数据库迁移或重新生成会全员"变成新文章"。
8. **命名空间声明**：用到 `itunes:`、`content:`、`dc:` 等前缀，必须在根元素或合适祖先上声明 xmlns；前缀拼写与规范 URL 一字不差。
9. **条目顺序**：新在前（`episodic`、绝大多数新闻/博客）；不要混排。
10. **feed 只含 XML**：不要输出 HTML 错误页、调试注释、`<!-- generated in 3s -->`（无害但泄露内部信息）；服务端异常时宁返回 5xx，也不要 200 + HTML。
11. **大小控制**：只放最近 N 条（20–50 常规站点；播客通常放全量，Apple 未公布条数与文档大小上限，但源越大抓取与校验越慢）；JSON Feed 规范建议单文件控制在 100KB 以内。`content:encoded` 里的图片建议外链而非 base64 内联，否则几 MB 一个源。
12. **不要复用 `description` 放 HTML 又放纯文本**：明确 `description`=摘要、`content:encoded`=全文，或反之，保持一致。

---

## 15. 生产端示例代码

### 15.1 静态文件（最快落地）

手工维护一个 `feed.xml`，用 CI 生成并推送到 CDN/对象存储：内容即数据，无后端、几乎不可被攻破。适合个人站、changelog、公告。

```bash
# 典型流程：Markdown -> 构建 -> 生成 feed -> 上传
hugo && aws s3 sync public/ s3://example-site/ --cache-control "max-age=60, must-revalidate"
```

静态站点生成器普遍内建 feed：Hugo（默认 `/index.xml`）、Jekyll（`jekyll-feed` 插件，默认输出 `/feed.xml`，**内容是 Atom 而非 RSS 2.0**，可用 `path:` 改文件名）、Eleventy（官方 `@11ty/eleventy-plugin-rss` **只提供格式化工具函数**，模板需自己写，示例输出常为 `/feed.xml`）、Next.js（`feed` 库 + Route Handler）。

### 15.2 Node.js：动态生成 RSS + Atom + JSON Feed

> 按 `jpmonette/feed` **v6.0.0** 的真实 API 编写：它只导出单个 `Feed` 类（**不存在** `RSSFeed`/`AtomFeed`/`JSONFeed`），三个渲染方法是实例上的 `rss2()` / `atom1()` / `json1()`，其中 `json1()` 输出的是 **JSON Feed 1.0**（非 1.1）。`ttl`、`feedLinks`、`hub` 都是 `Feed` 构造参数的**扁平字段**，不是嵌套的 `feed:{}`。

```js
// 依赖：npm i feed
import { createHash } from "node:crypto";
import { Feed } from "feed";

const posts = [
  {
    url: "https://example.com/posts/robust-feed-fetcher",
    title: "如何设计一个不会挂的订阅抓取器",
    description: "条件请求、退避重试、稳定 ID。",
    content: "<p>全文正文……</p>",
    author: { name: "李工", email: "li@example.com", link: "https://example.com/li" },
    category: [{ name: "架构" }, { name: "运维" }],
    published: new Date("2026-10-17T09:00:00+08:00"),
    updated: new Date("2026-10-17T11:02:00+08:00"),
  },
];

const feed = new Feed({
  title: "示例技术周报",
  description: "分布式系统与可观测性的每周更新",
  id: "https://example.com/",          // Atom 必填
  link: "https://example.com/",
  language: "zh-CN",                   // 仅 RSS 2.0 输出中使用
  image: "https://example.com/logo.png",
  favicon: "https://example.com/favicon.ico",
  copyright: "© 2026 Example",
  updated: posts[0].updated,
  generator: "example-cms 3.2",
  feed: "https://example.com/feed.xml",              // rel=self
  feedLinks: {
    rss: "https://example.com/feed.xml",
    atom: "https://example.com/atom.xml",
    json: "https://example.com/feed.json",
  },
  hub: "https://websub.example/hub",   // 自动写 rel=hub
  ttl: 60,                             // 分钟
  author: { name: "Example 编辑部", email: "editor@example.com" },
});

for (const p of posts) {
  feed.addItem({
    guid: p.url,                       // RSS 专用 GUID
    id: p.url,                         // Atom / JSON Feed 的唯一 ID
    link: p.url,
    title: p.title,
    description: p.description,
    content: p.content,
    author: [p.author],
    category: p.category,
    published: p.published,            // 首次发布时间
    date: p.updated,                   // v6 里 date 语义是"最后修改时间"
  });
}

export const outputs = {
  "application/rss+xml": feed.rss2(),
  "application/atom+xml": feed.atom1(),
  "application/feed+json": feed.json1(),
};

// 内容指纹用于 ETag：三格式各自算，别复用同一个
export const etagOf = s => `"${createHash("sha256").update(s).digest("hex").slice(0, 32)}"`;
```

音频条目用 `audio` 或通用 `enclosure`（`{ url, type, length, duration, title }`）；播客扩展通过 `new Feed({ podcast: true, category: "Technology" })` 打开 `itunes:` 输出。

HTTP 层配套（Express 示例，含条件请求）：

```js
// 接上一段，Express 路由片段（app 为已创建的 Express 实例）
app.get("/feed.xml", (req, res) => {
  const body = outputs["application/rss+xml"];
  const etag = etagOf(body);

  res.setHeader("Content-Type", "application/rss+xml; charset=utf-8");
  res.setHeader("Cache-Control", "public, max-age=60, must-revalidate");
  res.setHeader("ETag", etag);
  if (req.headers["if-none-match"] === etag) return res.status(304).end();
  res.send(body);
});
```

### 15.3 Python：`feedgen` 生成 RSS

> 方法名已对照 python-feedgen（`lkiesow/python-feedgen`，PyPI 包名 `feedgen`，当前 1.0.0）的 API 文档核实：`FeedGenerator.id/title/link(href, rel)/description/language/lastBuildDate/author`，`add_entry()` 得到 `FeedEntry`，其 `id/title/link/content(content, src, type)/author/published/updated/category` 均接受关键字参数，输出用 `rss_str(pretty=False, extensions=True, encoding='UTF-8', xml_declaration=True)` 或 `atom_str(...)`。本主机无 Python 环境，该片段**未实跑**。

```python
# 依赖：pip install feedgen
from feedgen.feed import FeedGenerator
from datetime import datetime, timezone

fg = FeedGenerator()
fg.id("https://example.com/feed.xml")
fg.title("示例技术周报")
fg.link(href="https://example.com/", rel="alternate")
fg.link(href="https://example.com/feed.xml", rel="self")
fg.description("分布式系统与可观测性")
fg.language("zh-CN")
fg.lastBuildDate(datetime.now(timezone.utc))

for p in posts:
    fe = fg.add_entry()
    fe.id(p["url"])                    # 稳定 GUID
    fe.title(p["title"])
    fe.link(href=p["url"])
    fe.description(p["excerpt"])
    fe.content(p["html"], type="html")
    fe.author(name=p["author"], email=p["email"])
    fe.published(p["published"])
    fe.updated(p["updated"])
    fe.category(term=p["tag"])

xml_bytes = fg.rss_str(pretty=True)   # 或 fg.atom_str()
```

### 15.4 数据库驱动的增量源

高频变更场景（监控公告、事件流）建议：

- 源查询只 `ORDER BY published_at DESC LIMIT 50`。
- 加覆盖索引 `(published_at DESC)`，避免每次请求扫全表。
- 生成结果缓存 30–120 秒（Redis / CDN），源不是 OLTP 入口。
- 分类源用同一模板 + 不同 `WHERE`，别复制多份代码。

---

## 16. 消费端示例代码

### 16.1 Node.js：`rss-parser`（RSS 2.0 与 Atom；**不解析 JSON Feed**）

> 输出映射要点（README 明列）：`feed.entries` 统一叫 `feed.items`；`dc:date`/`pubDate` 归一成 `isoDate`；有 `author` 无 `dc:creator` 时填 `creator`；**Atom 的 `updated` 映射成 `lastBuildDate`**；未内建返回的字段通过 `customFields` 暴露。
> 实测补充（rss-parser 当前版本，本地跑样本 feed 验证）：JSON Feed 输入会直接抛 `Non-whitespace before first tag.` ——**它不解析 JSON Feed**，那部分要自己 `fetch` + `JSON.parse`；`dc:creator` 会同时出现在 `creator` 和保留前缀的字段上，README"会去掉 dc: 前缀"的说法与实现并不完全一致。

```js
// 依赖：npm i rss-parser
import Parser from "rss-parser";

const parser = new Parser({
  timeout: 15000,
  headers: {
    "User-Agent": "example-aggregator/1.0 (+https://example.com/about-crawler)",
    "Accept": "application/rss+xml, application/atom+xml, application/xml;q=0.8",
  },
  customFields: {
    item: [["content:encoded", "full"], ["itunes:duration", "duration"]],
    feed: ["ttl"],
  },
});

const feed = await parser.parseURL("https://example.com/feed.xml");
console.log(feed.title, feed.items.length, feed.ttl);

for (const it of feed.items) {
  console.log({
    id: it.guid ?? it.id ?? it.link,   // 去重键的优先级
    title: it.title,
    iso: it.isoDate,
    body: it.full ?? it.content ?? it.contentSnippet,
  });
}
```

**去重与"新内容"判定**：正确做法是持久化 `id`（`guid`/`atom:id`/`url`）集合，首次抓取可选择"只建立基线、不报新"，避免第一次订阅就推送上百条历史文章。

### 16.2 带条件请求与退避的抓取循环

```js
import { request } from "node:https";

const state = new Map(); // feedUrl -> { etag, lastModified, fail, next }

async function fetchOnce(url) {
  const s = state.get(url) ?? { fail: 0 };
  state.set(url, s);
  const t0 = Date.now();
  const res = await new Promise((resolve, reject) => {
    const req = request(url, {
      headers: {
        "User-Agent": "example-aggregator/1.0 (+https://example.com/about-crawler)",
        ...(s.etag ? { "If-None-Match": s.etag } : {}),
        ...(s.lastModified ? { "If-Modified-Since": s.lastModified } : {}),
      },
      timeout: 15000,
    }, resolve);
    req.on("error", reject);
    req.end();
  });

  if (res.statusCode === 304) { s.fail = 0; res.resume(); return { changed: false }; }
  if (res.statusCode === 410) return { gone: true };
  if (res.statusCode === 429) {
    const ra = Number(res.headers["retry-after"]) || 300;
    s.next = Date.now() + ra * 1000;
    res.resume();
    return { throttled: true };
  }
  if (res.statusCode >= 500) {
    s.fail++; s.next = Date.now() + Math.min(2 ** Math.min(s.fail, 8) * 60000, 6 * 3600_000);
    res.resume();
    return { error: res.statusCode };
  }

  let raw = "";
  res.setEncoding("utf8");
  for await (const chunk of res) {
    raw += chunk;
    if (raw.length > 20 * 1024 * 1024) { res.destroy(); throw new Error("feed too large"); }
  }
  s.etag = res.headers["etag"]; s.lastModified = res.headers["last-modified"]; s.fail = 0;
  return { changed: true, body: raw, ms: Date.now() - t0 };
}
```

### 16.3 Python：`feedparser`（最健壮的解析库，容错性好）

```python
# 依赖：pip install feedparser requests
import requests, feedparser

r = requests.get(url, timeout=15, headers={"User-Agent": "example/1.0"}, allow_redirects=True)
r.raise_for_status()
d = feedparser.parse(r.content)          # 传 bytes：feedparser 自己不发请求
print(d.version, d.bozo, len(d.entries)) # 版本号 / 是否有解析问题 / 条目数
if d.entries:
    e = d.entries[0]
    print(e.get("id"), e.get("published_parsed"), e.title)
    body = e.content[0]["value"] if "content" in e else e.get("summary")
```

> 注意 `status`（HTTP 状态码）只有在**让 feedparser 自己去抓**（`feedparser.parse(url)`）时才可靠；把 `requests` 拿到的字节交给它时，状态码要从 `r.status_code` 读，别指望 `d.status`。同理，条件请求与退避要在自己的 requests 会话里实现。

`feedparser` 会吸收大量不规范输入（错误时区、裸 `&`、非法日期、混合命名空间），适合做"喂什么都能吐出大致结构"的第一层。若需严格校验合规性，走第 19 节的验证器，而不是靠解析器容错掩盖问题。

### 16.4 浏览器端注意

浏览器 `fetch` 一个第三方 feed 会被 CORS 拦住——**绝大多数站点不会给 feed 发 `Access-Control-Allow-Origin`**。所以纯前端阅读器需要：自建代理、用 CORS 代理服务、走 WebExtension 权限、或用 Service Worker。这也是"自托管后端 + 前端展示"架构在阅读器领域长期存在的原因。

---

## 17. 发布方最佳实践清单

按此逐条打勾，能覆盖 95% 的兼容性问题：

**结构与标识**
- [ ] `guid`/`atom:id` 全局唯一、永不改变（推荐永久 URL 或 UUID v4）。
- [ ] 条目按发布时间倒序，只保留最近 20–50 条（播客/存档类例外）。
- [ ] 所有 URL 绝对化；`atom:link rel="self"` 存在且正确。
- [ ] 同站多源：全站 + 每个主要分类/标签各一个，`title` 标明范围。

**内容**
- [ ] `description` 给像样的摘要（不是截断到乱码的前 200 字符），全文放 `content:encoded`/`content`。
- [ ] 标题不含 HTML；正文里的 HTML 结构干净（不要带站点导航、脚本、跟踪像素）。
- [ ] 图片使用绝对 URL 与稳定域名；重要图片有 alt 文本（源里也会体现）。
- [ ] 转载内容用 `<source>` 保留原始出处。

**时间与缓存**
- [ ] 时间戳带时区偏移；`pubDate` 是"发布时刻"而非"生成时刻"。
- [ ] 实现 `ETag` + `Last-Modified`，支持 304。
- [ ] `Cache-Control` 明确（60s 起），CDN 可缓存；别 `no-store`。
- [ ] RSS 2.0 写 `ttl`（分钟）。

**传输与合规**
- [ ] `Content-Type` 用 `application/rss+xml` / `application/atom+xml` / `application/feed+json`。
- [ ] UTF-8、XML 声明位于文件起始并带 `encoding="UTF-8"`（BOM 建议去掉）；通过 W3C Feed Validator（`validator.w3.org/feed/`）零错误。
- [ ] 支持 HTTPS，且不从 HTTPS 页指向 HTTP 源。
- [ ] 老 URL 永久 301；迁移时保留 `rel=self` 与新地址一致。
- [ ] 大流量源考虑：单独域名/CDN、限制条目、分页与归档（RFC 5005 的 `rel="first"`/`last`/`next-archive`/`prev-archive` + `fh:complete`）、WebSub 降低轮询压力。

**可发现与协作**
- [ ] HTML `<head>` 有 `rel=alternate`；页面提供"订阅"入口与 OPML 导出。
- [ ] 说明许可（`<itunes:...>` 之外的 `rights`/`cc:license`），写清是否允许全文再分发。
- [ ] 提供 `humans.txt`/抓取政策与联系邮箱，UA 允许站方联系而非直接封禁。
- [ ] 监控源可用性与校验错误（把 feed 校验纳入 CI，比等用户投诉快）。

---

## 18. 常见故障与排查

| 现象 | 高概率原因 | 排查/修复 |
|---|---|---|
| 阅读器报"无效 XML" | 裸 `&`（URL 参数）、未转义 `<`、BOM、`>` 前多字符、非法实体（`&nbsp;`） | 用校验器定位行；把 URL 里的 `&` 改成 `&amp;`；HTML 实体放 CDATA 或转成合法实体 |
| 每次刷新全部标为新 | GUID 用了时间戳/自增 ID/含随机数 | 改为永久 URL 或稳定 UUID；一次性手工修正映射表 |
| 新文章一直不出现 | CDN 长缓存、`ETag` 恒定不更新、抓取端 304 逻辑 bug | 校验 `ETag` 随内容变化；发布后主动清缓存或走 WebSub |
| 中文乱码 | 声明 UTF-8 实际 GBK；HTTP `charset` 与 XML 声明不一致 | 统一 UTF-8；响应头带 `; charset=utf-8`；文件保存不带 BOM |
| 图片在阅读器里不显示 | 相对路径、防盗链 Referer、私有 bucket | 绝对 URL + 无 Referer 校验 + 公共读；或 `media:thumbnail` 提供备选 |
| 播客目录"审核通过但无剧集" | `guid` 重复/变更、`enclosure.length` 与真实字节不符、MIME 错误、音频 302 | 逐条对齐真实字节数、直链无跳转、支持 Range、`type` 用 `audio/mpeg` |
| 播放器无法拖动进度 | 服务端不支持 Range 请求 | 换支持 `Accept-Ranges: bytes` 的存储/CDN |
| 浏览器打开 feed 变下载或渲染成网页 | Content-Type 发成 `text/html`/`octet-stream` | 改成规范 MIME |
| 自建实例抓取超时 | 源站慢（DB 未加索引）、条目过多、图片 base64 内联 | 查询加索引 + 结果缓存 60s；限制条数；图片外链 |
| 被 WAF 封禁 | 无条件请求、高频轮询、并发拉全站、UA 无标识 | 实现条件请求 + 遵守 `ttl`/`Retry-After` + 带可联系 UA |
| 内嵌 HTML 显示成标签文字 | CDATA 内容又被二次转义；或 `description` 里放了转义 HTML 而客户端按纯文本渲染 | 全文用 `content:encoded` + CDATA；摘要给纯文本 |
| 同一源有时 200 有时 500 且 HTML 错误页 | 异常时输出 HTML 而非 5xx | 异常处理里保持"要么合法 XML 要么状态码错误" |
| 分类源与全站源条目反复出现 | 客户端按标题去重、或两个源 GUID 生成规则不同 | 保证同一文章在所有源里 GUID 完全一致 |
| 时间显示差 8 小时 | 无时区偏移的时间戳，被按 UTC 解析 | RSS 用 `+0800`，Atom 用 `+08:00`，别只写 `2026-10-17 09:00:00` |

---

## 19. 生态与工具索引

**阅读器 / 服务端**

- 托管：Feedly、Inoreader、The Old Reader、Follow 等。
- 桌面/移动原生：NetNewsWire（Apple 平台，开源）、Reeder、Fiery Feeds、Outliner。
- 命令行：Newsboat、newspaper。
- 自托管：Miniflux（Go，单二进制，极简；官方支持为每个源配置 `username`/`password` 即 HTTP 认证）、FreshRSS（PHP，功能全）、Tiny Tiny RSS（插件生态大）、yarr、Stringer。
- OPML 迁移：主流自托管实例与托管服务普遍支持导入导出，跨服务搬家用它（具体字段兼容度以各实例文档为准）。

**校验与规范工具**

- W3C Feed Validator：**当前地址 `https://validator.w3.org/feed/`**（`validator.feedreader.com` 已无法解析，别再引用）。发布前必跑，错误与警告分开。
- FeedReaderChecklist：评估阅读器实现完整度（视频、分页、WebSub、全文等）。
- `xmllint --noout feed.xml` / `python -c "import xml.etree.ElementTree as ET; ET.parse('feed.xml')"`：CI 里做第一道语法门。

**生成与框架支持**

- Node：`feed`（v6 只导出单个 `Feed` 类，渲染方法 `rss2()`/`atom1()`/`json1()`，其中 `json1()` 输出 **JSON Feed 1.0**）、`rss`。
- Python：`feedgen`（`lkiesow/python-feedgen`）。Rails：`RSS::Maker`。Django：内建 syndication framework。Laravel：社区 feeds 包。
- 静态站：Hugo、Jekyll（`jekyll-feed`）、Eleventy（`@11ty/eleventy-plugin-rss`）、Astro、Next.js Route Handler。

**解析**

- Python `feedparser`（容错最成熟）、Node `rss-parser`、Go `github.com/mmcdole/gofeed`（注意作者用户名是 `mmcdole`）、Java Rome（`rometools/rome`，2.1.0 发布于 2023-03、维护低频但**未归档**）、.NET `System.ServiceModel.Syndication`。

**"给没有 feed 的站点造 feed"**

- RSSHub（把大量无源站点转成 feed）：公共实例存在速率限制与稳定性风险，生产建议自建实例——这是社区通行做法，但官方文档并未把"被滥用"写成硬性理由，接入前以其当前文档为准。
- 自写 scraper → 规范化输出 feed 的轻量服务：可控性最好，但需自行处理目标站改版与访问条款。

**公开可订阅的典型源（可直接拿来验证客户端）**

- GitHub：`https://github.com/<owner>/<repo>/releases.atom`（本次实测仍返回 200 + `application/atom+xml`）
- arXiv API：返回 Atom，按 `search_query` 订阅分类/作者
- NVD / 厂商安全公告：多为 Atom
- 各主流技术媒体与独立博客：`/feed`、`/rss.xml`、`/index.xml`

> 上述 URL 结构会随服务方改版变化，接入前实际请求验证，别把第三方源当基础设施依赖。

---

## 20. 规范来源

| 主题 | 出处 |
|---|---|
| RSS 2.0 规范 | RSS Advisory Board / UserLand：`rssboard.org/rss-specification`（事实标准，非 IETF） |
| RSS Profile（含 `atom:link rel="self"` 的推荐、item 子元素约束） | `rssboard.org/rss-profile` |
| RSS 1.0（RDF Site Summary，ns `http://purl.org/rss/1.0/`） | `web.resource.org/rss/1.0/spec`（与 RSS 2.0 是不同 lineage） |
| Atom Syndication Format | **RFC 4287**（IETF） |
| Atom Publishing Protocol | **RFC 5023**（独立标准，未修订/取代 RFC 4287） |
| Atom License Extension（`rel="license"`） | **RFC 4946** |
| Atom Threading Extensions（`thr:in-reply-to`） | **RFC 4685**（不是分类扩展） |
| Feed Paging and Archiving（`first`/`last`/`next-archive`/`prev-archive`/`fh:complete`） | **RFC 5005** |
| Link Relations 注册表（含 `payment`） | **RFC 8288** + IANA 注册表 |
| Tag URI Scheme | RFC 4151 |
| JSON Feed | `jsonfeed.org/version/1`（1.0）与 `jsonfeed.org/version/1.1` |
| Dublin Core | `purl.org/dc/elements/1.1/` |
| Content / Syndication / Slash 模块 | `purl.org/rss/1.0/modules/…` |
| Media RSS | `search.yahoo.com/mrss/` |
| GeoRSS | `georss.org`（命名空间标识为 `http://www.georss.org/georss`，无尾斜杠；站点可取回形式为 `http://www.georss.org/georss/`） |
| WebSub | W3C WebSub 推荐标准 `w3.org/TR/websub/`（前身 Google PubSubHubbub） |
| 播客 feed 要求 / 校验 / 迁移 / 封面 / explicit / 集序 | Apple Podcasts Connect 支持文档（链接需带 slug，纯数字 ID 会 404）：[823 feed 要求](https://podcasters.apple.com/support/823-podcast-requirements)、[829 校验](https://podcasters.apple.com/support/829-validate-your-podcast)、[837 换 feed 地址](https://podcasters.apple.com/support/837-change-the-rss-feed-url)、[5514 封面模板](https://podcasters.apple.com/support/5514-show-cover-template)、[902 封面故障](https://podcasters.apple.com/support/902-troubleshooting-artwork-issues)、[3143 集序](https://podcasters.apple.com/support/3143-how-to-set-the-order-of-podcast-episodes)、[825 建集](https://podcasters.apple.com/support/825-how-to-create-an-episode)、[5440 explicit](https://podcasters.apple.com/support/5440-explicit-content)、[1236 订阅最佳实践](https://podcasters.apple.com/support/1236-subscription-best-practices) |
| 播客 RSS 标准与扩展命名空间 | PSP-1（Podcast Standards Project）、`podcasting2.org/docs/podcast-namespace/1.0`、`github.com/Podcastindex-org/podcast-namespace` |
| HTTP 语义 / 缓存 | RFC 9110（Semantics，含 304/410、`Retry-After`）、RFC 9111（Caching）、RFC 6585（429） |
| OAuth 2.0 / Bearer / 原生应用 / 威胁模型 | RFC 6749 / RFC 6750 / RFC 8252 / RFC 6819 |
| 日期格式 | RSS：RFC 822 日期语法（四位年份优先）；Atom / JSON Feed：RFC 3339 |
| 媒体类型 | 已注册：`application/atom+xml`（RFC 4287+5023）、`application/rdf+xml`（RFC 3870）、`application/json`（RFC 8259）、`application/xml`（RFC 7303）。**未注册**：`application/rss+xml`、`application/feed+json` |
| 校验器 | `validator.w3.org/feed/` |

---

## 21. 联网核对记录（2026-10-17）

本文档已按权威原文逐条复核，下表是本轮**发现并已修正**的事实点，便于后续再核对时不重复踩坑。

| # | 原文错误说法 | 已改为 | 权威出处 |
|---|---|---|---|
| 1 | RSS 日期"RFC 1123" | 规范原文是 **RFC 822 日期语法，年份四位优先** | [RSS 2.0 Specification](https://www.rssboard.org/rss-specification) |
| 2 | channel 有 `skipMinutes` | 实际元素是 **`skipHours` + `skipDays`** | 同上 |
| 3 | `image` height 默认 312 | 默认 **31**（上限 400） | 同上 |
| 4 | item 有 `contributor` | RSS 2.0 未定义该元素，需贡献者请用 `dc:contributor` | [The RSS Profile](https://www.rssboard.org/rss-profile) |
| 5 | "每个 item 最多一个 enclosure" | 规范未明文，Profile 写"作者意图是一个" | 同上 |
| 6 | "RSS 2.0 显式允许 atom:link" | 核心规范只允许命名空间扩展；`rel="self"` 的推荐写在 **Profile §5.1** | 同上 |
| 7 | `atom:link` 属性含 `mediaTypes` | 属性为 `href/rel/type/hreflang/title/length`，**无 mediaTypes** | [RFC 4287 §4.2.7](https://www.rfc-editor.org/rfc/rfc4287.txt) |
| 8 | entry 的 `title` 可用 `content` 替代 | `id`/`title`/`updated` **各恰好一个**，title 无豁免 | RFC 4287 §4.1.2 |
| 9 | "无 content 且无 alternate link 时必须 summary" | 两条约束实为：无 content → 必须有 `rel=alternate` link；content 带 `src` 或为 Base64 非 text/XML → 必须有 summary | RFC 4287 §4.1.2.1 |
| 10 | RFC 4331 = Atom License | **RFC 4946** 才是 Atom License Extension（4331 是 WebDAV 配额） | [RFC 4946](https://www.rfc-editor.org/rfc/rfc4946.txt) |
| 11 | RFC 4685 = Atom 分类扩展 | 4685 是 **Threading** 扩展；`atom:category` 本就在 4287 核心 | [RFC 4685](https://www.rfc-editor.org/rfc/rfc4685.txt) |
| 12 | RFC 5005 = partial feeds / no feed | 5005 是 **Feed Paging and Archiving**；"partial feeds/no feed"从未成为 IETF 标准 | [RFC 5005](https://www.rfc-editor.org/rfc/rfc5005.txt) |
| 13 | `rel="payment"` 已废弃 | 该关系在 **RFC 8288** 的 IANA 注册表中，只是识别者少 | [Link Relations registry](https://www.iana.org/assignments/link-relations/link-relations.xhtml) |
| 14 | JSON Feed "`id` 或 `url` 至少一个" | **`id` 必填**（1.0/1.1 皆是），`url` 可选但应存在；顶层必填为 `version`/`title`/`items` | [JSON Feed 1.1](https://jsonfeed.org/version/1.1) |
| 15 | 1.1 新增 `expired` | `expired`、`attachments.size_in_bytes/duration_in_seconds`、`hubs` **1.0 就有**；1.1 变的是 `author`→`authors`（废弃）、`language`、id 强制字符串 | [JSON Feed 1.0](https://jsonfeed.org/version/1) |
| 16 | GeoRSS ns 带尾斜杠 | 命名空间标识是 `http://www.georss.org/georss`（无尾斜杠），另有 `georss:box` | [georss 站点](http://www.georss.org/georss/) |
| 17 | WebSub 签名在 `hub.signature`，"HMAC-SHA256 强制" | 签名在请求头 **`X-Hub-Signature: sha1=…`/`sha256=…`**；规范是"应至少用 SHA-256"，多数 hub 仍兼容 SHA-1 | [W3C WebSub](https://www.w3.org/TR/websub/) |
| 18 | "hub 用 `hub.mode=renew` 提醒续订" | 无此模式；租约到期必须失效，续订＝到期前**重发 subscribe** | 同上 |
| 19 | 播客封面"≤512KB"、feed"8MB 上限"、"125/300 条上限" | Apple 现行文档**未公布**这三项数字；封面只规定正方形 1400–3000、PNG/JPG、无 alpha 通道 | [823](https://podcasters.apple.com/support/823-podcast-requirements) / [5514](https://podcasters.apple.com/support/5514-show-cover-template) |
| 20 | `itunes:explicit` 允许 `yes/no/clean` | 现行只接受 **`true`/`false`** | [5440](https://podcasters.apple.com/support/5440-explicit-content) |
| 21 | Apple 分页用 `podcast:paging` | Podcasting 2.0 命名空间**不存在** `podcast:paging`，PSP-1 也未定义分页标签 | [podcast-namespace 1.0](https://podcasting2.org/docs/podcast-namespace/1.0) |
| 22 | `itunes:author`/`owner` 属必填 | 不在 Apple 必填清单内（owner 邮箱用于认领与通知，实践必写） | [1236](https://podcasters.apple.com/support/1236-subscription-best-practices) |
| 23 | `application/rss+xml` 已在 IANA 注册 | **未注册**（`application/feed+json` 同样未注册）；已注册的是 `atom+xml`/`rdf+xml`/`json` | [IANA Media Types](https://www.iana.org/assignments/media-types/media-types.xhtml) |
| 24 | RFC 9457 用于 OAuth 授权流程 | 9457 是 Problem Details for HTTP APIs；应为 RFC 6749 + **RFC 6750**（Bearer）+ 8252，威胁模型 6819 | [RFC 6750](https://www.rfc-editor.org/rfc/rfc6750.txt) |
| 25 | Feed Validator 地址 `validator.feedreader.com` | 现行为 **`https://validator.w3.org/feed/`** | [W3C Feed Validator](https://validator.w3.org/feed/) |
| 26 | npm `feed` 导出 `RSSFeed/AtomFeed/JSONFeed` | v6.0.0 只导出 **`Feed`**，方法 `rss2()/atom1()/json1()`，`json1()` 输出 JSON Feed **1.0**；`ttl`/`feedLinks`/`hub` 为扁平字段（已实跑验证输出含 `<ttl>60</ttl>`、`rel="self"`、`rel="hub"`） | [feed 6.0.0 README](https://unpkg.com/feed@6.0.0/README.md) |
| 27 | Go 解析库 `gofeed` 归属不明 | 仓库为 **`github.com/mmcdole/gofeed`** | [GitHub](https://github.com/mmcdole/gofeed) |
| 28 | `eleventy-plugin-rss` 无 scope | 官方包名 **`@11ty/eleventy-plugin-rss`**，且只提供 helper、需自写模板 | [Eleventy 文档](https://11ty.dev/docs/plugins/rss/) |
| 29 | "Miniflux / FreshRSS 支持 Basic、部分支持 header 凭证" | Miniflux 官方确认源级 `username/password`；FreshRSS、tt-rss 的源级认证**未能直证**，已降级为"接入前复核" | [Miniflux docs](https://miniflux.app/docs/) |
| 30 | `rss-parser` 可"RSS + Atom + JSON 通吃" | 只吃 XML：JSON Feed 输入实测抛 `Non-whitespace before first tag.`；另 README 的"去掉 `dc:` 前缀"与实现不完全一致 | 本地实跑 + [rss-parser README](https://unpkg.com/rss-parser/README.md) |
| 31 | `feedparser.parse(bytes)` 后有 `d.status` | 传字节时状态码不可靠，应从 `r.status_code` 读；`d.status` 只在 feedparser 自抓时有意义 | [feedparser 文档](https://feedparser.readthedocs.io/) |
| 32 | `jekyll-feed` 输出 RSS 2.0 | 默认输出 `/feed.xml`，**内容是 Atom**；要 RSS 2.0 需换插件或自写模板 | [jekyll-feed](https://github.com/jekyll/jekyll-feed) |
| 33 | 分页用 `rel="next"/"prev"` | 源分页标准是 **RFC 5005**：`first`/`last`/`next-archive`/`prev-archive` + `fh:complete` | [RFC 5005](https://www.rfc-editor.org/rfc/rfc5005.txt) |
| 34 | §5.1 出现 `textInput` 之外的"跳过分钟"能力 | RSS 2.0 无 `skipMinutes`（见第 2 行），且 `textInput` 被 Profile 明确不鼓励 | [The RSS Profile](https://www.rssboard.org/rss-profile) |

**本次已本地实跑验证的代码**（Node 24，`/tmp/feedtest`）：`feed@6.0.0` 生成三格式并确认输出含 `<ttl>60</ttl>`、`atom:link rel="self"`、`atom:link rel="hub"`、`<guid>`；`rss-parser` 的 `customFields` 别名映射与"JSON Feed 输入直接报错"。

**本文三个 XML 示例均已实测可解析**：第 5.3 节 RSS 2.0 示例、第 6.3 节 Atom 示例、第 9 节播客示例经 `rss-parser` 解析均返回 1 个条目且频道标题正确；第 7 节 JSON Feed 示例经 `JSON.parse` 通过，`version` 为 `https://jsonfeed.org/version/1.1`。注意这只代表"良构且字段映射正常"，等值的规范级校验请用 `https://validator.w3.org/feed/` 对公网可访问的 URL 跑一次。

**未实跑、仅按文档编写的代码**：§15.3 Python `feedgen`、§16.3 Python `feedparser`（当前主机无 Python 环境，`pip`/`python` 均不可用）。落地前请自行执行一次。

**未能直证、已标注为经验/惯例的条目**：Trackback 命名空间 URI、"禁止 BOM"、`application/feed+json` 之外的客户端兼容细节、公共 hub 现状、`jekyll-feed` 之外生成器的默认文件名细节。

**本次未复核的外部数字**：各商用目录（Spotify / 小宇宙 / Pocket Casts）自身的封面与大小阈值——只核对了 Apple 官方口径，其它目录请按其文档单独复核。

**链接可达性**：本轮引用的外部出处已逐个用 HTTP 请求复核（2026-10-17）。

- 返回 200：rssboard（规范 + Profile）、rfc-editor 全部引用编号（4151/4287/5023/4946/4685/5005/8288/3870/8259/9110/9111/6585/6749/6750/8252/6819/7303）、jsonfeed 1.0 与 1.1、W3C WebSub、IANA Link Relations 与 Media Types、Apple 播客 9 篇支持文档、`validator.w3.org/feed/`、`web.resource.org/rss/1.0/spec`、`podcasting2.org/docs/podcast-namespace/1.0`、unpkg 上的 `feed@6.0.0` 与 `rss-parser` README、`github.com/mmcdole/gofeed`、`github.com/jekyll/jekyll-feed`、`feedparser.readthedocs.io`、`miniflux.app/docs/`、Eleventy 的 RSS 插件文档页。
- 需注意的取回细节：Apple 的 `podcasters.apple.com/support/<数字>` 省略 slug 会 404，文档里已写完整链接；Eleventy 文档用 `https://11ty.dev/docs/plugins/rss/` 可达（带 `www.` 前缀本次连接失败）；GeoRSS 的命名空间标识 `http://www.georss.org/georss` 只是 URI 常量，其站点本次仅 `http://www.georss.org/georss/`（带尾斜杠）能取回，HTTPS 与无尾斜杠形式均连接失败。
- **本机网络取不到、但由检索确证过的链接**：`github.com/Podcast-Standards-Project/PSP-1-Podcast-RSS-Specification`、`github.com/Podcastindex-org/podcast-namespace`（连接层失败，非 404）。引用这两个来源时请在可联网环境二次确认。

**逐字复核过的规范原文**（抓取 RSS 2.0 规范正文后检索确认）：日期"遵循 RFC 822，年份两位或四位（四位优先）"；`image` 的"width 最大 144 默认 88，height 最大 400 **默认 31**"；"item 的所有元素都可省略，但 `title` 与 `description` 至少有一个必须存在"；`skipHours`（0–23，午夜为零点）与 `skipDays`（最多七个 `<day>`）并列存在，规范中**没有** `skipMinutes`；`enclosure` 的 `url` 必须是 http url。

**Atom 侧同样逐字复核**（抓取 RFC 4287 正文确认）：`atom:link` 的属性恰为 `href`(必填)、`rel`、`type`、`hreflang`、`title`、`length`；feed 与 entry 均"**MUST contain exactly one** atom:id / atom:title / atom:updated"；"不含 `atom:content` 的 entry **MUST** 至少有一个 `rel="alternate"` 的 link"；"必须有 `atom:summary` 的两种情形"分别是 `content` 带 `src`（因此自身为空）、以及 `content` 为 Base64 编码且 `type` 是非 XML、非 `text/` 开头的 MIME 媒体类型；无数字时区偏移时**必须**用大写 `Z`。

---

### 附：三分钟上手路径

1. **只想让博客被订阅**：静态站开 feed → HTML 里放 `rel=alternate` → 跑一次 Feed Validator → 检查 GUID 稳定性。
2. **想做一个内部聚合器**：Miniflux/FreshRSS 自建 → 导入 OPML → 关键词过滤器 → 把命中结果推到 IM。
3. **想做监控/AI 流水线**：条件请求 + 持久化 GUID 集合 + 指数退避（第 16.2 节骨架）→ 新条目落库 → LLM 摘要/分类 → 告警通道。
4. **想发播客**：RSS 2.0 + `itunes:` 全量必填项 → `enclosure` 直链 + Range 支持 → GUID 永不改 → 目录后台提交并用 `new-feed-url` 兜底。
