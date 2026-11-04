# 02 · 架构设计

> 定位：个人自用、单操作员、自托管。目标是"一个进程 + 一个数据库 + 一个对象存储桶"能跑完全部需求。
> 关联：[01 需求分析](./01-requirements.md) · [03 技术设计](./03-technical-design.md) · [04 产研计划](./04-roadmap.md) · 规范依据 [`doc/rss-guide.md`](../rss-guide.md)

---

## 1. 一句话架构

**模块化单体（Modular Monolith）**：单个 Spring Boot 可部署单元内按领域分模块，抓取靠"数据库任务表 + 应用内调度器"驱动，不引入 MQ/微服务；对外同时扮演 **Feed 消费者**（抓取）与 **Feed 生产者**（渲染）两种角色，中间由一套规范化的条目模型（Normalized Entry Model）隔开。

这个结构的关键收益：解析侧与产出侧互不污染，且**再分发（F7）、事件源（F3）、播客（F6）全都只是"另一个 feed 生产者"**，不需要新增子系统。

---

## 2. 上下文图

```
                       ┌──────────────────────────────────────────────┐
  外部站点 RSS/Atom    │                 rss-hub (单体)                │
  ┌──────────┐  HTTP   │  ┌────────────┐   ┌──────────────┐           │
  │ 博客/公告 │◀────────┼──│ fetch 模块  │──▶│ ingest 模块   │           │
  │ Release  │  条件请求 │  │ (轮询/退避) │   │ (解析/规范化)  │           │
  └──────────┘         │  └────────────┘   └──────┬───────┘           │
                       │        │                 │ 写 entry          │
  我的脚本/CI ──POST──▶ │  ┌─────▼─────┐    ┌──────▼───────┐           │
  (事件源 F3)          │  │ 调度/任务  │    │  存储层 PG    │           │
                       │  │ job 表     │    │ feed/entry/  │           │
  Vue3 管理端 ──REST──▶ │  └─────┬─────┘    │ post/episode│            │
                       │        │           └──┬───────┬──┘           │
  订阅者(匿名) ──GET──▶ │  ┌─────▼──────┐       │       │              │
  /feed/rss|atom|json  │  │ render 模块 │◀──────┘       │              │
                       │  │ (三格式输出)│               │              │
                       │  └────────────┘        ┌───────▼────────┐     │
                       │  ┌──────────┐ ┌──────┐ │ MinIO/本地磁盘  │     │
                       │  │ rule     │▶│notify│ │ 原始 XML / 音频  │     │
                       │  │ 引擎     │ │ 通道 │ └────────────────┘     │
                       │  └──────────┘ └──────┘                        │
                       │  ┌──────────┐                                │
                       │  │ ai 模块   │→ LLM API (OpenAI 兼容)          │
                       │  └──────────┘                                │
                       └──────────────────────────────────────────────┘
```

---

## 3. 模块划分与职责边界

| 模块 | 职责 | 明确不做 | 依赖 |
|---|---|---|---|
| `feedfetch` | 轮询调度、条件请求、退避、状态码语义、SSRF 防护、原始响应留存；F10 之后还接住"操作员要求立刻抓"——管理面把该源的 `fetch` 任务 `fire_at` 置为现在，并在**事务提交后**调 `feedfetch/FetchWakeup.java` 唤醒一轮（`adminapi/SubscriptionService.java:332-338` 的 `refresh` + `:341-363` 的 `armNow`/`wakeAfterCommit`），逐源节奏存 `feed.fetch_interval_minutes` 列（0=跟随站点） | 解析、业务规则 | PG、HttpClient |
| `feedparse` | 把 RSS 2.0/Atom/RDF/JSON Feed 归一成 `NormalizedFeed/NormalizedEntry`；容错修复 | 网络、存储 | jsoup/SAX、`feed` 无关 |
| `feedstore` | 幂等落库、条目修订、已读/星标/归档三态、收件箱的六个过滤（F2.4：`state/feed/group/q/category/alerted` + 游标） | 渲染 | PG；**"只看命中"在 SQL 里读 `notification_log` 这张 rules 的表**（`EntryFinder.java:336-342` 的 EXISTS 子查询），**没有 `feedstore → rules` 的 Java 依赖**，写入权仍归 rules，所以 ArchUnit 的七条与 `no_package_cycles` 都不受影响 |
| `feedrender` | 从任意 `FeedSource` 产出三格式 XML/JSON + ETag/304/ttl；分类源；HTML 发现标签 | 抓取 | 模板/DOM 构建 |
| `editorial` | 站内文章（Post）CRUD、Markdown、发布状态、媒体；每次**公开可见**的写都在提交前把变更交给 `changelog` 记一笔 | — | feedstore、feedrender、changelog |
| `events` | 外部 ingest（Bearer）、dedupeKey、事件流 | — | feedstore |
| `podcast` | Show/Episode、音频上传与 Range 服务、`itunes:` 渲染、目录合规自检 | — | feedrender、objectstorage |
| `rules` | 规则匹配引擎、抑制与静默、试跑 | 发送 | feedstore |
| `notify` | 钉钉/企微/邮件/Webhook 适配器、重试、投递日志 | 规则判定 | 外部 IM |
| `ai` | 摘要/翻译/标签/近似重复；按 content_hash 缓存；预算护栏 | 阻塞主流程 | LLM provider |
| `repack` | 聚合再分发（F7）：集合与成员、把已订阅条目投影成 `/repack/{slug}.{format}` 出口、逐源全文开关与审计 | 抓取、解析、 writer 细节（都委托给 `feedrender`） | feedrender、common（`JdbcClient` 直读 `entry`/`feed`，**不依赖 feedstore**） |
| `changelog` | 变更公告（F1.6）：`content_change` 表的读写 + 把变更行投影成 `/feed/changelog.{format}` 出口；"哪些变更算公开事件"这条规则只住在这里（写入前的可见性判定），不靠查询时补 join | 抓取、解析、writer 细节（都委托给 `feedrender`）、判定状态机合法性（那是 `editorial` 的迁移表） | feedrender、common（`JdbcClient` 直写自己的表，**不依赖 editorial**，所以删掉文章也删不掉记录） |
| `admin-api` | 给 Vue3 的 REST（订阅管理、收件箱、发文、规则、健康度、大屏、设置与系统状态、聚合再分发、变更公告只读）。F10 让它在订阅面多三件事：`PATCH /api/feeds/{id}` 只认 `title/group/state/intervalMinutes` 四键（地址不可改，未知键 400 `unknown_field`）、`POST /api/feeds/{id}/refresh` 立刻排队、`GET /api/feeds/policy` 把默认与下限告知前端；F11 让大屏从"卡片列表"变成"一次请求给出十个标量 + 24 小时桶 + 六个区块（桶/分组/卡片墙/告警/发布/变更）"，但它**只做编排不写 SQL**（见边界规则 4）；F16 加批量订阅两个端点（`POST /api/feeds/batch/plan` 预览、`POST /api/feeds/batch` 写入），拆分器 `adminapi/PastedSubscriptions.java` 是**纯文本**工具类——不归一、不校验 SSRF、不碰库，判决与写入都归 `SubscriptionService.classify/addBatch`。批量**逐行不发网络请求**（与 OPML 导入同口径），所以这个叶子包仍然没有触网，边界规则 2 不破 | — | 全部 |
| `security` | 单用户登录、TOTP、session、CSRF、API token | — | Spring Security |
| `settings` | 运行时参数的键值存取与校验（`settings` 表）：大屏条数/刷新间隔、收件箱与投递日志每页条数。**收件箱那一项是真实读取路径**——SPA 不发 `limit`，缺省值由这里决定（2026-11-01 修正：此前前端硬发 `limit=50`，设置页等于摆设） | 抓取节奏、投递超时（部署级配置，改了要重启，不入库） | common |
| `web`（控制台，不在这个 Maven 模块里） | Vue 3 单文件组件 + vue-router + Pinia，15 个 `.vue` = 12 视图 + 2 组件（`ReaderPane`/`RuleEditor`）+ `App.vue` 外壳；F13 起 UI 骨架交给 Bootstrap 5.3 的 **CSS 类**（`navbar`/`card`/`table`/`form-control`/`badge`/`alert`/`progress`），本项目自己的样式只剩一个品牌层文件（`web/src/styles/theme.css`：令牌 + 把 `--bs-*` 指向令牌）加各视图的少量 scoped 规则 | 业务规则（一律留在后端）；**Bootstrap 的 JavaScript**（下拉/折叠/toast 用 Vue 状态 + CSS 类实现，运行时不依赖 Popper） | `admin-api`；`bootstrap` 的编译后 CSS 与图标字体 |

边界规则（写死在代码评审里）：
1. `feedparse` 与 `feedrender` **不共用任何模型类**——解析产出物是 `NormalizedEntry`，渲染输入是 `FeedSource`（接口），避免"内部模型泄漏成对外格式"。
2. 任何模块不得直接发 HTTP 抓外部 URL，只能通过 `feedfetch` 的抓取入口（`FeedFetchService` → `HttpFeedFetcher`，集中 SSRF 与限速；设计期草案里这个类叫 `FetchService`，实际不存在同名类）。
3. 渲染层只读，绝不写库（防止 feed 请求触发副作用）。
4. **聚合 SQL 归归属包**（2026-11-02 随 F11 写死）。大屏是这套代码里最容易长出"跨包读表"的地方：一屏要同时问条目、投递日志、订阅、文章、变更五家，而 `admin-api` 是叶子包、按规则不能拥有任何一张表。所以 F11 的口径是——`DashboardController` 里**一条 SQL 都没有**，它只调用各归属包给出的聚合入口（`EntryFinder.latestPerFeed/arrivedSince/arrivalsByHour/counters`、`NotificationRepository.countsSince/hitsByHour`、`FeedRepository.listCards`、`PostRepository.countPublished`），再把它们折成 24 个恒定桶、十个标量与三条轨道。补进这条规则的理由很实际：前三条是 ArchUnit 能自动拦的，这条不是——它约束的是"SQL 写在哪 one层"，机器看不出 `feedstore` 里多出来的那条 `group by` 其实是为谁写的，只能靠评审。同理 F10 的每源覆盖列写在 `feedstore/FeedRepository`（`update(...)` 的 `nullif(?, 0)`）而 `admin-api` 只传参，也是这条规则的适用面。

前**三条**不是口头约定：它们连同四条补充规则一起写在 `backend/src/test/java/com/rsshub/ArchitectureTest.java` 里，实测 7 条 `ArchRule` 全绿——补充的是 `common_is_the_bottom_layer`、`admin_api_is_a_leaf`、`no_package_cycles` 与"只有指定包能触网"的具体化 `only_designated_packages_reach_the_network`（逐条名称与实现见 [03 §13](./03-technical-design.md)）。第 4 条目前只有两处机器证据：`admin_api_is_a_leaf` 让任何"往 `admin-api` 里塞 Repository/SQL"的写法直接红，以及 `smoke.sh:436-487` 的 F11 段把屏上每个数字都对回了它声称汇总的那个页面——**数字对得上，说明聚合是在数据那一侧做的，不是在响应里凑出来的**。

---

## 4. 核心数据流：抓取管道（6 步）

```
1 调度   scheduled_task 表按 fire_at 到期取一批 → 认领（条件更新 pending→running，见下）
2 请求   FeedFetchService/HttpFeedFetcher: SSRF 校验 → 带 If-None-Match/If-Modified-Since → 超时/体积上限
3 判定   304→结束(重置 fail) | 301→改 canonical_url | 410→停抓 | 429→按 Retry-After | 5xx→指数退避
4 留存   原始响应字节落盘（30 天）+ content-hash，供重放与二次解析
5 归一   FeedParser 探测格式 → NormalizedFeed（容错：裸 &、坏日期、混合 ns）
6 落库+分发 幂等 upsert entry → 发布 EntryNewEvent → [rules→notify] [ai 入队] [F7 再分发索引]
```

**第 1/4/6 步的实现口径（2026-10-31 回读代码）**：表名是 `scheduled_task`（`V1__baseline_schema.sql:64`，早期草案写的"job 表"不存在）；**认领不是 `FOR UPDATE SKIP LOCKED`**——那是设计期的 PG 措辞，H2 不实现它（[03 §14 O9](./03-technical-design.md)），实际是一条条件更新，靠"影响行数=1"判定抢到，两种方言语义一致（`TaskQueue.java:21-26`，代价是每轮每任务多一条语句）；并发正确性有单测 `TaskQueueTest#twoWorkersNeverGetTheSameRow`。第 4 步已落地：`RawResponseStore` + `rsshub.storage.raw-dir`（默认 `data/raw`）+ `raw-retention: 30d`（`application.yml:112-113`）。第 6 步的 `EntryNewEvent` 确实由 `EntryIngestService.java:60,94` 发布，`rules→notify` 靠它入队投递；**但方括号里的后两项没有实现**：`ai 入队` 属 M6（无 `ai` 包），"F7 再分发索引"**从来没有入队这一步**——M5 的再分发是出口渲染时按 `repack_source` 现查（`RepackRepository.java:51` 起的 `ITEM_SELECT`），条目落库不感知集合，代价是每次请求多一次 join，收益是加/移成员立即反映在出口里、不需要重建索引。

要点：第 6 步的**事件内部分发用 Spring 应用事件 + 任务表**，不用 MQ。AI 与推送失败绝不能回滚抓取事务（各自独立事务/任务）。

首次订阅的特殊处理：`baseline=true` → 只写条目并标记已读，不推未读、不触发告警，避免"一订阅就推 200 条历史"。

---

## 5. 发布侧数据流（同一渲染层，五种源）

```
Post(文章)   ┐
EventItem   ┼─▶ FeedSource 适配器 (items + 元数据 + self URL + ttl) ─▶ feedrender ─▶ /feed/*.xml|atom|json
Episode     │        · RSS 2.0 / Atom 1.0 / JSON Feed 1.1 三套 writer
Repack(转发) │        · ETag = 内容指纹；If-None-Match → 304
ChangeLog   ┘        · 分类/标签子源 = 同一适配器加 where 条件
                     · 一站一条的出口（site / changelog）不吃 slug
```

`Repack`（F7）适配器输出的条目保留 `source` 指向原站、`link` 指向原文，默认 `description` 摘要而非全文——版权义务见 [01 §2](./01-requirements.md)。**M5（2026-10-31）即按此句实现**：`repack/RepackFeedSource` 是第四个 `FeedSource`，出口三格式、ETag/304、`<source>` 与 `external_url` 全部复用上面这套渲染层，实现口径见 [03 §8.6](./03-technical-design.md)。

`ChangeLog`（F1.6，2026-11-01）是第五个适配器，也是第一个条目实体不是"能订阅的内容"而是"内容的一次变化"的：一行 `content_change` 就是一条 item，`<link>` 将读者送回那篇文章，而**条目 ID 用记录自己的 URN**（`urn:rsshub:change:{public_id}`）——一篇文章在这个出口里天然有多条记录，永久 URL 在这里不唯一，直接套用会违反 FR-1.5 的"同一条目 ID 稳定"而非它的"用 URL 当 ID"。写侧只有一条判据：**变更前或变更后任一侧公开，就记**（`published`/`unlisted`，或时间已到的 `scheduled`）；草稿期的增删改因此一条都不写，撤下与删除却照样公告。它不给渲染层加任何新 writer、也不动安全配置（`/feed/*` 与 `/api/**` 的既有规则恰好覆盖），`changelog → common, feedrender` 是唯一出边（实测 `grep "^import com.rsshub" changelog/*.java`），`editorial` 反过来依赖它，所以没有环。实现口径见 [03 §8.7](./03-technical-design.md)。

规范落点（避免实现走样）：必填项与 GUID 规则 [rss-guide.md:175](../rss-guide.md#L175)、Atom 一致性约束 [rss-guide.md:267](../rss-guide.md#L267)、JSON Feed `id` 必填 [rss-guide.md:327](../rss-guide.md#L327)、Content-Type 与缓存 [rss-guide.md:485](../rss-guide.md#L485)、发现标签 [rss-guide.md:532](../rss-guide.md#L532)。

---

## 6. 部署拓扑

```
                    Caddy (TLS 终结 / gzip / 静态资源 / 反代)
                              │
        ┌─────────────────────┼──────────────────────┐
        │                     │                      │
   /api/** , /feed/**    /assets/** (音频 presign 重定向)   管理端静态文件
        │                     │
   Spring Boot 容器 ×1     MinIO 容器（或本地卷 / 云对象存储）
        │
   PostgreSQL 容器（+ 可选 pgvector）
        │
   每日：pg_dump + 原始 XML 目录 → 对象存储副本（restic/rclone）
```

- **单容器应用**：`--spring.profiles.active=docker`；抓取调度与应用同进程（自用无需独立 worker）。规模上限内不做水平扩展；若要多实例，只需把调度器认领改为 `SKIP LOCKED`（已按此设计，天然支持多实例）。
- **音频不经过应用进程**：管理端上传 → 直传对象存储（presigned PUT）；播放用 presigned GET 或 CDN 域名，保证 byte-range 由存储层负责（依据 [rss-guide.md:412](../rss-guide.md#L412) 的播客硬性要求）。
- 环境：`dev`（本机 + docker PG）、`prod`（家里/云主机单机）。无预发布环境，但每个里程碑结束做一次"备份恢复演练"代替。
- **两个界面不能共用一个 origin（F15 写部署文档时实测出来的约束）**：公开站由 Java 服务端渲染且占了 `/`（[PublicViewController.java:46](../../backend/src/main/java/com/rsshub/editorial/PublicViewController.java#L46) 映射 `{"/", "/blog"}`，[SecurityConfig.java:78-79](../../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L78) 把 `/` 放进 permitAll）；管理控制台是 Vue SPA，也用 `createWebHistory()` 且收件箱路由同为 `/`（[web/src/router/index.ts:17-19](../../web/src/router/index.ts#L17)）。开发态不暴露这条冲突，因为控制台在 :5173、公开站在 :8080。出路要么分域（`blog.example.com` → Java，`admin.example.com` → 静态 SPA），要么给 SPA 配 `base` 前缀（`vite.config.ts` 的 `base` + `createWebHistory("/app/")`，**尚未实现**）。完整的部署面——反代两版配置、环境变量、systemd、备份恢复、升级回滚、常见故障——在 [doc/deploy.md](../deploy.md)。

---

## 7. 关键决策记录（ADR 摘要）

| # | 决策 | 备选 | 选择理由 | 何时重新评估 |
|---|---|---|---|---|
| A1 | 模块化单体 | 微服务 / 单体+独立 worker | 单用户，网络与部署成本远高于业务复杂度；模块边界已保证可拆分 | 抓取延迟成瓶颈、或要多实例时 |
| A2 | PG 任务表当队列 | RabbitMQ/Kafka/Redis Stream | 需要持久化重试与可查询状态；`SKIP LOCKED` 足够；少一个中间件 | 任务 > 每秒百级或需跨语言 worker |
| A3 | PostgreSQL（非 MySQL） | MySQL | `jsonb` 存原始条目字段、FTS、`pgvector`、`generated column`、部分索引做任务队列 | 已定，不重评 |
| A4 | 自建解析层（不用 Rome 的 HTTP fetcher） | Rome 全家桶 | 需要条件请求、退避、SSRF、体积上限——这些必须自己控；Rome 只做解析 | Rome 解析缺陷暴露时替换 |
| A5 | 输出三格式而非一种 | 只出 RSS 2.0 | 成本几乎为零（同一 `FeedSource`），换来 AI/脚本消费友好 | 不重评 |
| A6 | 不做 WebSub（本期） | 接 W3C WebSub | 本次选项未勾选；轮询 + 60 分钟 ttl 已满足自用时效 | 若"分钟级延迟"成为硬需求（依据 [rss-guide.md:564](../rss-guide.md#L564) 可直接实现） |
| A7 | AI 异步 + 预算硬阈值 | 同步调用 | 同步会把抓取时延绑到 LLM 上；预算失控是自用服务的典型翻车点 | 每月复核 |
| A8 | 播客音频不做站内代理 | 应用转发 | 带宽与内存风险；Range 语义交给存储/CDN 更可靠 | 不重评 |
| A9 | 单用户鉴权 + TOTP，不做 RBAC | Spring Security 完整权限模型 | 自用；把复杂度花在 SSRF/HTML 清洗这类真实风险上 | 若开放给第二个人 |
| A10 | 公开文章页用 Thymeleaf 服务端渲染，Vue 只做登录后管理端 | 全站 SPA（SSR 或纯静态） | 发布侧的硬要求是 HTML `<head>` 里能被发现的 `rel=alternate` 与可抓取正文（依据 [rss-guide.md:532](../rss-guide.md#L532)）；SPA 首屏 HTML 里没有这些标签，等于自断发现链路，还要额外搭 SSR 基建 | 若公开页改版为纯静态生成器 |
| A11 | 单 Maven 模块 + 按领域分包，用 ArchUnit 锁边界 | 多模块 Maven / Spring Modulith | 单人项目里多模块的构建与循环依赖调试成本高于收益；模块边界靠测试强制执行比靠目录更可靠 | 若要拆独立 fetch worker（见第 9 节） |
| A12 | 控制台的 UI 底座用 Bootstrap 5.3 的 CSS 层，**不引它的 JS**（2026-11-02，F13） | 继续手写 F12 的 `fluent.css` 令牌层 / Naive UI / Element Plus / shadcn-vue | 控制台一百多个控件里“表格 + 表单 + 徽标 + 分页 + 折叠”是纯结构件，自研那一层等于长期维护一个小型组件库；而组件库形态的替代品会把 UI 依赖和 Vue 主次版本绑死。Bootstrap 只取 CSS 时是**类名 + 自定义属性**，状态仍由 Vue 掌管，因此不需要 Popper、没有 imperative 生命周期，也不会和键盘流（`useInboxKeys`）抢焦点；换肤仍然只改一个文件（`theme.css` 把 `--bs-primary`、`--bs-btn-bg` 这类指向品牌令牌） | 若要深色模式 / RTL / 虚拟滚动这类框架级能力，或 CSS 体积（编译后 322.72 kB，gzip 48.02 kB；F13 立此决定时是 321.03/47.71，F14 的淡蓝品牌层 +1.69 kB）对自用部署成为问题 |

---

## 8. 容量与性能估算（按个人自用上限档）

| 维度 | 估算 | 结论 |
|---|---|---|
| 源数 | 1500 | 轮询周期 60 分钟 → 平均 25 请求/分钟；并发 4 足够 |
| 每日新条目 | 15 万（含噪音） | 落库单条约 2–6KB（正文截断/外链）→ 日增约 0.3–0.9GB；保留 6 个月 ≈ 60–160GB，需归档策略 |
| 条目读取 QPS | < 5（就我一个人） | 无需缓存层；收件箱查询靠 `(feed_id, published_at desc)` 索引。**F2.4 补了 `notif_entry_idx`（`notification_log(entry_id)`，V6 双方言）**：红标从"页级一次批量查"变成 WHERE 条件后，翻页的每一行都要按 `entry_id` 回查这张表，而 V3 留下的两个索引答的都是另一个方向（`notif_rule_idx`/`notif_dedupe_idx`） |
| `/feed/*` 输出 | 渲染 50 条 < 20ms；ETag 命中 304 零渲染成本 | 单机可承受误配阅读器高频轮询 |
| 音频 | 3000×3000 封面 ~2MB、单集 MP3 ~40MB | 对象存储成本主导，不在应用侧 |
| AI | 15 万条/日全量摘要不可行 | 必须只对**规则命中或星标**的条目加工 → 约 300–2000 条/日，成本可控 |

> 第 8 行的约束直接进需求：**AI 不是全量流水线，而是按需触发**。

---

## 9. 演进路径（不提前实现，但留出接缝）

| 触发信号 | 演进动作 | 已预留的接缝 |
|---|---|---|
| 抓取延迟 > 可接受范围 | 拆出独立 fetch worker（多实例） | 认领已是"一条条件更新 + 影响行数判定"，跨方言等价且天然互斥（`TaskQueue.java:21-26`），再加实例不需要改协议；抓取侧 `FeedFetchService`/`FetchDispatcher` 无状态 |
| 分钟级时效需求 | 加 WebSub（hub + verifier + 签名校验） | `feedrender` 已可输出 `rel=hub`；`admin-api` 预留 `/api/websub/*` 命名空间 |
| 条目表过大 | 按月分区 + 冷数据归档到对象存储的 JSONL | 主键为 `bigint`+`published_at`，天然可分区 |
| 想开放给他人 | 引入租户与配额 | 现在不加 `owner_id` 列（见 [03 §3 约定](./03-technical-design.md)）：届时用一次带默认值的迁移比每表留空列干净 |
| AI 成本失控 | 本地小模型跑摘要 | provider 接口已抽象，切换不改调用点 |

---

## 10. 架构层面的验收清单

- [ ] 关掉 AI/notify 任一模块，抓取与阅读不受影响（故障隔离）。
- [ ] 应用重启后未完成任务能继续（任务状态全在库里，不在内存）。
- [ ] 手工把某源 `next_fetch_at` 改为过去时间，能被下一轮调度捡起。
- [ ] 三个输出 feed 的 URL 与 GUID 在数据库迁移后仍不变（导出/导入测试）。
- [x] 抓取任意 URL 时无法访问内网/元数据地址（有自动化用例）——`SsrfGuardTest` 25 项 + 真 HTTP 负例在 `backend/smoke.sh`；另有 `only_designated_packages_reach_the_network` 兜住"第二个未受保护的出口"。
- [ ] 音频播放请求**不经过**应用 JVM（观察 GC/带宽与访问日志）。

**清单里前四行的现状（2026-10-31 回读）**：第 1 行——`fetchEnabled/notifyEnabled` 两个开关存在且与 `@ConditionalOnProperty` 同源（[01 §7 DoD FR-9.1/9.2 行](./01-requirements.md) 有断言），但"关掉一个、另一个仍能跑"是**组合行为**，没有对应的自动化用例，也未人工演练；`ai` 包不存在，所以"关 AI"这半句当前无从演练。第 2 行的**一半**有证据：任务状态确实全在库里（`scheduled_task`），`TaskQueueTest#reapStaleRunningFreesOnlyTheRowsOlderThanTheBudget` 覆盖"上次进程死掉留下的 running 行会被回收"；重启后继续跑的完整演练未做。第 3 行措辞已过时——`feed` 表**没有** `next_fetch_at` 列，到期时间在 `scheduled_task.fire_at`（`V1__baseline_schema.sql:72`），语义等价的操作是"把某条 fetch 任务的 `fire_at` 改到过去"；`TaskQueueTest#claimTakesTheEarliestDueRowAndMarksItRunning` 断言了认领按 `fire_at` 到期顺序，但"手工 UPDATE 一次再等下一轮"仍是人工动作。第 4 行是导出/导入演练，属 BLK-12 那类没自动化的人工门禁（迁移脚本双方言一致性有 `MigrationDialectAlignmentTest`，但它证明的是 schema 形状，不是 feed URL/GUID 在迁移后不变）。
- [x] 再分发出口的每条 ID/`link`/`source` 都指向原站，正文默认缺席（摘要 + 回链），全文只能由逐源显式开启且留审计——M5 已按此实现并有断言（`RepackApiTest` 的 `membersShipTheirSourceAndOriginalLink`/`fullContentIsPerMemberAndAudited` + `smoke.sh` 的 M5 段）；`repack` 的依赖是实测清单（`grep "^import com.rsshub" repack/*.java`）——只到 `common` 与 `feedrender`，出口类 `RepackFeedSource` 全程只读，写操作只在管理面 `RepackService` 里发生。
- [x] 变更公告出口只说"读者能观测到的变化"：草稿的新建/编辑/删除不产生任何公开记录，而删除一条已发布的文章照样公告——F1.6 已按此实现并有断言（`ChangeLogApiTest` 的 `draftsAreWrittenSilently`/`unpublishingAndDeletingBothAnnounceThemselves` + `smoke.sh` 的 F1.6 段，含"删掉从未公开的文章后记录数仍是 0"这条负例）；`changelog` 的依赖同样是实测清单，只到 `common` 与 `feedrender`，`editorial` 单向依赖它，`content_change.post_id` 有意**不加外键**（与 `repack_source_audit` 同一先例），所以文章消失不会带走它的变更史。
