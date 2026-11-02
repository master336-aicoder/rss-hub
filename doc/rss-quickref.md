# RSS 速查页

> 配套全文：[`doc/rss-guide.md`](./rss-guide.md)（原理、场景、故障排查、代码骨架）
> 本页只放"写代码时想查一下就一眼能看到"的内容：必填项、取值、URI、响应头、命令。
> 所有条目已于 2026-10-17 对照规范原文与厂商文档核对；来源见文末，勘误记录见全文第 21 节。

---

## 1. 一分钟选型

| 目标 | 选它 |
|---|---|
| 最老客户端也不能漏、公开博客/新闻 | RSS 2.0 |
| 新项目、要精确"更新时间"语义 | Atom 1.0（RFC 4287） |
| 主要给脚本/AI/前端消费 | JSON Feed 1.1 |
| 播客 | **只能 RSS 2.0 + `itunes:`**（Apple 要求 feed 符合 RSS 2.0） |
| 都要 | 同一数据输出三 URL + 三个 `rel=alternate`，成本几乎为零 |

---

## 2. 必填项对照

| | RSS 2.0 | Atom 1.0 | JSON Feed 1.1 |
|---|---|---|---|
| 根 | `<rss version="2.0">`（`version` 必填） | `<feed xmlns="http://www.w3.org/2005/Atom">` | 对象 |
| 频道/源必填 | `title`、`link`、`description` | `id`、`title`、`updated`（各恰好一个） | `version`、`title`、`items` |
| 条目必填 | item 子元素皆可选，但 **`title` 与 `description` 至少其一** | `id`、`title`、`updated`（各恰好一个） | `id`（`title`/`content_*` 可省，但两个 content 至少其一） |
| 唯一 ID | `guid`（`isPermaLink` 默认 `true`） | `id` | `id`（数字须转字符串） |
| 日期 | RFC 822 语法，四位年份优先 | RFC 3339（无偏移时用大写 `Z`，分隔用大写 `T`） | RFC 3339 |
| 一致性约束 | URL 须以已注册 scheme 开头（http/https/news/mailto/ftp） | 无 `content` 的 entry 必须有 `rel="alternate"` link；`content` 带 `src` 或为 Base64 非 text/XML 时必须有 `summary` | `attachments[].url` 与 `mime_type` 必填；`next_url` 不得重复 |
| 多媒体 | `enclosure`（url/length/type；规范未明文数量，意图是一个） | `link rel="enclosure"`，可多条 | `attachments[]`，可多条 |
| 作者 | `author` = `email (name)`（无 `contributor`，用 `dc:contributor`） | `author`（`name` 必填）：feed 必须至少一个，除非每个 entry 各自有一个 | `authors[].name`（`author` 已废弃） |

---

## 3. RSS 2.0 元素速查

```
channel 必填：title | link | description
channel 可选：language | copyright | managingEditor | webMaster | pubDate | lastBuildDate
             | category(可重复, domain) | generator | docs | ttl(分钟)
             | image(url+title+link 必填, width≤144 默认88, height≤400 默认31, description)
             | skipHours(GMT 0-23) + skipDays     ← 没有 skipMinutes
             | textInput                          ← Profile 明确不鼓励，别写
item   ：title | link | description | author | category | comments
             | enclosure(url,length,type) | guid(isPermaLink) | pubDate
             | source(url, 内容为源频道 title)
docs 常见值：https://www.rssboard.org/rss-specification
atom:link rel="self" 的推荐出处是 RSS Profile §5.1（核心规范只允许命名空间扩展）
```

---

## 4. Atom 元素速查

```
feed  ：(必填) id, title, updated
        (各至多一个) subtitle, icon, logo, rights, generator
        (可重复) link, category, author, contributor
        link 属性：href(必填) rel type hreflang title length   ← 没有 mediaTypes
entry ：(必填恰好一个) id, title, updated
        (可选) author, link, category, contributor, source, published, summary, content, rights
content@type：text(默认) | html | xhtml(内联 XHTML 命名空间的 div) | 其他 MIME(不得为复合类型)
        type="text/html" 时元素不得有子元素；有 src 时元素本身须为空
同文档内重复 id 视为同一条目（updated 应不同）；feed 应(rel=self) 声明自身地址
id 取值：规范只要求 IRI + 自证唯一 + 迁移不改；tag:/urn:uuid 属惯例（Tag URI = RFC 4151）
```

---

## 5. JSON Feed 字段速查

```
顶层必填：version("https://jsonfeed.org/version/1.1"), title, items[]
顶层应填：home_page_url, feed_url（规范称"公开发布应视为必填"）
顶层可选：description, icon, favicon, language, expired, hubs[], authors[]
item    ：id(必填) | url | external_url | title | content_text | content_html
          | date_published | date_modified | authors[] | tags[]
          | attachments[] | _extensions（扩展字段名须以 "_" + 字母开头）
content_html 是唯一允许出现 HTML 的字段；两者至少一个
1.0 → 1.1 真实差异：author → authors(废弃)、新增 language、id 数字强制转字符串
                       （expired / size_in_bytes / duration_in_seconds / hubs 在 1.0 就有）
建议单文件 < 100KB
```

---

## 6. Content-Type 与 IANA 注册状态

| 值 | 用途 | IANA 注册？ |
|---|---|---|
| `application/rss+xml` | RSS 2.0 | ❌ 未注册（事实标准值） |
| `application/atom+xml` | Atom | ✅ RFC 4287 + RFC 5023 |
| `application/rdf+xml` | RSS 1.0 / RDF | ✅ RFC 3870 |
| `application/feed+json` | JSON Feed（规范要求用它服务） | ❌ 未注册 |
| `application/json` | JSON Feed 回退 | ✅ RFC 8259 |
| `application/xml` | 通用可接受 | ✅ RFC 7303 |
| `text/html` / `application/octet-stream` | 错：会被当网页渲染 / 被迫下载 | — |

---

## 7. HTTP 头与状态码

**发布方必备**

```
Content-Type: application/rss+xml; charset=utf-8
Cache-Control: public, max-age=60, must-revalidate   # 别用 no-store
ETag / Last-Modified -> 支持 304
Content-Encoding: gzip|br    # 播客 enclosure length 仍写原始字节数
ttl(RSS, 分钟) / sy:updatePeriod+updateFrequency（支持率有限）
Link: <https://websub.example/hub>; rel="hub"   # WebSub 首选 HTTP 头，其次 XML/HTML 里的 rel=hub
```

**抓取方按状态码动作**

| 码 | 动作 | 出处 |
|---|---|---|
| 200 | 解析 + 更新 ETag/Last-Modified | RFC 9110 |
| 301 | **改订阅地址** | RFC 9110 |
| 302/307 | 跟随但不改地址 | RFC 9110 |
| 304 | 无新增，保留旧条目 | RFC 9110 §15.4.5 |
| 404 | 保留订阅 + 退避重试，长期失败才判失效 | RFC 9110 |
| 410 | **立即停抓**，提示取消订阅 | RFC 9110 §15.5.11 |
| 429 | 遵守 `Retry-After`（§10.2.3），勿重试风暴 | **RFC 6585** 定义 429 |
| 5xx | 指数退避（5 分钟起 → 小时级封顶） | RFC 9110 |
| 401/403 | 提示补凭证，勿匿名硬试 | RFC 9110 |

抓取端另需：超时 10–30s、响应体上限（5–20MB）、并发限制、UA 带可联系 URL/邮箱。

---

## 8. GUID / ID 规则（最常踩）

- 全局唯一、**永不改变**；条目被修订也不换。
- 不要用：自增数字、时间戳、随机数 → 重新生成会让全部条目变"新"。
- 推荐：永久 URL（RSS `guid isPermaLink="true"` / Atom `id` = 永久 IRI / JSON Feed `id`）。
- 同一文章在**所有源**（全站源、分类源、Atom 镜像）里 ID 必须完全一致，否则重复推送。
- 首次订阅建议只建基线不报新，避免一次性推上百条历史。

---

## 9. 扩展命名空间（复制即用）

```xml
xmlns:atom="http://www.w3.org/2005/Atom"
xmlns:dc="http://purl.org/dc/elements/1.1/"
xmlns:content="http://purl.org/rss/1.0/modules/content/"
xmlns:sy="http://purl.org/rss/1.0/modules/syndication/"
xmlns:slash="http://purl.org/rss/1.0/modules/slash/"
xmlns:media="http://search.yahoo.com/mrss/"
xmlns:georss="http://www.georss.org/georss"
xmlns:cc="http://backend.userland.com/creativeCommonsRssModule"
xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
xmlns:podcast="https://podcastindex.org/namespace/1.0"
```

常用元素：`dc:creator`、`content:encoded`（全文）、`sy:updatePeriod`(hourly/daily/weekly/monthly/yearly)/`updateFrequency`/`retention`、`slash:comments`、`media:content`/`media:thumbnail`/`media:description`、`georss:point`/`line`/`polygon`/`box`/`where`、`cc:license`、`atom:link rel="hub"`。

注意：`georss` 无尾斜杠；`cc` 另有并存的 `http://web.resource.org/rss/1.0/modules/cc/`；Trackback 的 `http://madskills.com/public/xml/rss/module/trackback/` 属惯例值；**消费端必须忽略不认识的命名空间**而不是报错。

---

## 10. 播客速查（Apple 口径）

**必填**：至少一集 · 封面 · `title` · `description` · `language` · `itunes:explicit` · `itunes:category` · `enclosure`(url/length/type) · 每集唯一不变 `guid`
**非必填但实践必写**：`itunes:author`、`itunes:owner`（邮箱用于认领与通知）

| 项 | 值 |
|---|---|
| 封面 | 正方形，1400×1400–3000×3000（首选 3000），PNG 或 JPG，**无 alpha/透明通道**，RGB；服务器允许 HTTP HEAD + byte-range；**官方未公布文件大小上限**（流传 512KB 来自旧 iTunes/托管商） |
| `itunes:explicit` | 仅 `true` / `false`（`yes`/`no`/`clean` 已废弃） |
| `itunes:type` | `episodic`（默认，倒序）/ `serial`（正序，需整数集号；无 season 落 Unknown Season） |
| `itunes:duration` | 秒数（推荐）或 `hh:mm:ss` |
| 描述/notes 长度 | 4000 字符 |
| 换 feed | 老地址 **301** + 新 feed 写 `<itunes:new-feed-url>`，**两者同时保留 ≥4 周**，GUID 不变；不要指向 APC Mirror URL |
| 音频 | 服务器须支持 byte-range（决定能否拖动进度）；重复的 enclosure URL 会被忽略 |
| 条数/体积 | Apple **未公布**上限；"8MB / 125 条 / 300 条"来自旧闻或个别托管商 |
| 分页 | `podcast:paging` **不存在**（Podcasting 2.0 与 PSP-1 均无此标签） |
| Podcasting 2.0 | `podcast:locked`(yes/no + `owner` 邮箱)、`podcast:transcript`、`podcast:funding`、`podcast:value`(含 `value:recipient`)、`podcast:person` |

---

## 11. WebSub 速查

```
发现： Link: <hub>; rel="hub"（首选）或 <atom:link rel="hub" href="..."/>
订阅： POST hub.mode=subscribe & hub.topic & hub.callback & hub.secret & hub.verify=sync|async
验证： hub 发 GET?hub.mode=subscribe&hub.topic&hub.challenge → 2xx 且响应体原样回显 challenge
       2026 版另要求安全 Media Type + X-Content-Type-Options: nosniff
通告： POST 到 callback，签名在请求头 X-Hub-Signature: sha1=…|sha256=…（不是 hub.* 参数）
       规范要求"应至少用 SHA-256"，但多数 hub 仍用 SHA-1 兼容老订阅者
租约： hub.lease_seconds 随验证响应返回；到期必须失效；续订 = 到期前重发一次 subscribe
       没有 hub.mode=renew 这种东西
注意： callback 必须 HTTPS 且无 fragment；收到通告后仍要回源拉 feed（除非 verified feeds）
       公共 hub 可用性会变，生产建议自建或用现役服务；WebSub 是加速，轮询仍要兜底
```

---

## 12. 发现与迁移

```html
<link rel="alternate" type="application/rss+xml"    title="全站" href="https://example.com/feed.xml">
<link rel="alternate" type="application/atom+xml"   title="Atom" href="https://example.com/atom.xml">
<link rel="alternate" type="application/feed+json"  title="JSON" href="https://example.com/feed.json">
```

- 页面 URL ≠ feed URL；`type` 写错会被发现器忽略。
- 常见路径：`/feed`、`/feed.xml`、`/rss.xml`、`/atom.xml`、`/index.xml`(Hugo)、`/feed.json`、`/feed?format=rss`（约定不是标准，稳定比选择重要）。
- 迁移：老地址永久 301 + `atom:link rel="self"` 指向新地址。
- 分页标准：**RFC 5005**（`first`/`last`/`next-archive`/`prev-archive` + `fh:complete`），不是 `rel="next"`。
- 付费源：`rel="payment"`（RFC 8288 注册表内，未被废弃，但识别者少）；"partial feeds / no feed" 不是 IETF 标准。

---

## 13. 发布前 10 秒自检

1. `version="2.0"` 在，`<rss>` 上声明了用到的所有 xmlns。
2. URL query 里的 `&` 全写成 `&amp;`（第一大致命错误）。
3. 每条都有 GUID，且与上次构建**逐字节相同**（diff 一下）。
4. 日期带时区偏移；RSS 用 `+0800`，Atom 用 `+08:00`/`Z`。
5. XML 声明在文件起始，实际编码 UTF-8。
6. `Content-Type` 正确且带 `charset=utf-8`。
7. 有 `ETag`，且内容变了指纹就变。
8. 有 `ttl`（分钟）+ `Cache-Control`。
9. 跑 `https://validator.w3.org/feed/`，0 error。
10. 全站源 / 分类源 / 多格式镜像里同一文章 ID 一致。

---

## 14. 一行命令

```bash
curl -sI -H 'User-Agent: my-reader/1.0 (+https://example.com/about)' https://example.com/feed.xml
curl -s -D- -H 'If-None-Match: "<etag>"' https://example.com/feed.xml | head -1     # 期望 304
xmllint --noout feed.xml && echo "XML 良构"
python -c "import xml.etree.ElementTree as ET;ET.parse('feed.xml');print('XML 良构')"
# 规范级校验：把公网可访问的 feed URL 交给 https://validator.w3.org/feed/
```

> 本机环境备注：`xmllint` 与 `python` 在当前 Windows 主机不一定可用（本次 `python`/`pip` 即不可用），Node 侧可用 `require('rss-parser').parseString()` 先做良构性检查（注意它不接 JSON Feed）。

---

## 15. 库速查（含已核实的坑）

| 语言 | 库 | 已知事实 |
|---|---|---|
| Node | `feed@6.0.0` | 只导出 `Feed` 类；方法 `rss2()/atom1()/json1()`，**`json1()` 是 JSON Feed 1.0**；`ttl`/`feedLinks`/`hub` 为扁平字段；已实跑验证输出 |
| Node | `rss-parser` | 解析 RSS/Atom XML；**JSON Feed 会抛 `Non-whitespace before first tag.`**；`customFields` 可加 `content:encoded` 别名；Atom `updated` → `lastBuildDate` |
| Python | `feedgen`（lkiesow/python-feedgen, 1.0.0） | `FeedGenerator.id/title/link(href,rel)/description/language/lastBuildDate/author`；`add_entry()` → `fe.id/title/link/content(content,src,type)/author/published/updated/category`；输出 `rss_str()`/`atom_str()` |
| Python | `feedparser` | 容错最成熟；传字节时 `status` 不可靠，状态码从 `requests` 的 `r.status_code` 取 |
| Go | `github.com/mmcdole/gofeed` | 用户名是 **mmcdole** |
| Java | `rometools/rome` | 2.1.0（2023-03）后**低频维护但未归档** |
| .NET | `System.ServiceModel.Syndication` | 内建 SyndicationFeed |
| 静态站 | Hugo `/index.xml`；`jekyll-feed` 默认 `/feed.xml`（**内容是 Atom**）；`@11ty/eleventy-plugin-rss` 只提供 helper，模板自写 | — |
| 自建阅读器 | Miniflux（Go）官方支持每源 `username`/`password`；FreshRSS、tt-rss 的源级认证请复核当前文档 | — |
| 校验 | `https://validator.w3.org/feed/`（`validator.feedreader.com` 已无法解析）；FeedReaderChecklist 用于评客户端实现完整度（站点地址本次未直证，按名称检索确认） | — |

---

## 16. 来源

RSS 2.0 Specification `rssboard.org/rss-specification` · The RSS Profile `rssboard.org/rss-profile` · RSS 1.0 `web.resource.org/rss/1.0/spec` · [RFC 4287](https://www.rfc-editor.org/rfc/rfc4287.txt)（Atom）· [RFC 5023](https://www.rfc-editor.org/rfc/rfc5023.txt)（AtomPub）· [RFC 4946](https://www.rfc-editor.org/rfc/rfc4946.txt)（License）· [RFC 4685](https://www.rfc-editor.org/rfc/rfc4685.txt)（Threading）· [RFC 5005](https://www.rfc-editor.org/rfc/rfc5005.txt)（Paging/Archiving）· [RFC 8288](https://www.rfc-editor.org/rfc/rfc8288.txt) + [IANA Link Relations](https://www.iana.org/assignments/link-relations/link-relations.xhtml) · [RFC 4151](https://www.rfc-editor.org/rfc/rfc4151.txt)（Tag URI）· [JSON Feed 1.0](https://jsonfeed.org/version/1) 与 [1.1](https://jsonfeed.org/version/1.1) · [W3C WebSub](https://www.w3.org/TR/websub/)（2026-06-02 Recommendation）· [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.txt) / [9111](https://www.rfc-editor.org/rfc/rfc9111.txt) / [6585](https://www.rfc-editor.org/rfc/rfc6585.txt) / [6749](https://www.rfc-editor.org/rfc/rfc6749.txt) / [6750](https://www.rfc-editor.org/rfc/rfc6750.txt) / [8252](https://www.rfc-editor.org/rfc/rfc8252.txt) / [6819](https://www.rfc-editor.org/rfc/rfc6819.txt) · Apple Podcasts Connect 支持文档（URL 必须带 slug，纯数字 ID 会 404）：823、829、837、5514、902、3143、825、5440、1236 · [PSP-1](https://github.com/Podcast-Standards-Project/PSP-1-Podcast-RSS-Specification) 与 [podcast-namespace 1.0](https://podcasting2.org/docs/podcast-namespace/1.0) · [feed@6.0.0 README](https://unpkg.com/feed@6.0.0/README.md)、[rss-parser README](https://unpkg.com/rss-parser/README.md)（两者已本地实跑）· python-feedgen 手册页 · [W3C Feed Validator](https://validator.w3.org/feed/) · [mmcdole/gofeed](https://github.com/mmcdole/gofeed)。

链接可达性（2026-10-17，逐个 HTTP 复核）：绝大多数返回 200。三处例外——Apple 支持文档必须带 slug（纯数字 ID 会 404）；Eleventy 用 `https://11ty.dev/docs/plugins/rss/`（带 `www.` 连接失败）；GeoRSS 站点本次只有 `http://www.georss.org/georss/`（带尾斜杠）可取回，命名空间标识本身仍写作无尾斜杠。`github.com/Podcast-Standards-Project/PSP-1-*` 与 `github.com/Podcastindex-org/podcast-namespace` 在本机连接层失败（非 404），请以 `podcasting2.org/docs/podcast-namespace/1.0` 为准并二次确认。

完整清单与逐条勘误见 [rss-guide.md 第 20、21 节](./rss-guide.md)。
