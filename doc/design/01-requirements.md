# 01 · 需求分析

> 项目：RSS 订阅与发布服务（Spring Boot + Vue3）
> 定位：**个人自用**（单用户、自托管、1–2 台服务器）
> 文档状态：v1（2026-10-17）→ v2（2026-10-18 追加 F8/F9，即 M3.5）→ v3（2026-10-31：M5 交付，F7 从"后置"改为"已交付"，§4 F7 加实况、§7 DoD 加一行）→ v4（2026-11-01：FR-1.6 变更公告交付，关闭 BLK-02；URL 口径按后缀式修正，§4 F1、§7 DoD 各一处）→ **v5（2026-11-01：FR-2.4 收件箱过滤/键盘/桌面通知交付，关闭 BLK-01；§4 F2 加实况、§7 DoD 加 FR-2.4 一行；同时修正 FR-9.2 的一处实现欠账——收件箱的前端请求不再硬发 `limit=50`，缺省页大小改由设置项真正决定）**
> 关联：[02 架构设计](./02-architecture.md) · [03 技术设计](./03-technical-design.md) · [04 产研计划](./04-roadmap.md) · 规范依据 [`doc/rss-guide.md`](../rss-guide.md)

---

## 1. 背景与问题陈述

现状：信息来源分散在博客、新闻站、Release 页、安全公告、内部系统；靠人工回访或平台算法聚合，导致三类成本：

1. **发现成本**：不知道谁更新了，回访即浪费或漏看。
2. **筛选成本**：更新里 80% 与当前关注无关，需要人工划。
3. **发布成本**：自己写的文章/系统事件没有稳定出口，换平台或换前端就要重做。

目标：用一个自托管服务，把「拉平所有来源 + 规则/AI 帮我筛 + 我自己也对外发布 feed」三件事收在一处，并保证数据是可迁移的（OPML / SQL 导出 / 原始 XML 留存）。

**成功判据（自用可验证）**：
- 打开收件箱能在 3 分钟内看完当天有效更新；不相关的噪音 < 20%。
- 自己的文章发出后 ≤ 1 分钟在自家 feed 可见，且三个格式（RSS 2.0 / Atom / JSON Feed）都能过校验。
- 关键来源（安全公告、竞品博客）变更能在轮询周期内触发钉钉/邮件告警。
- 任意时刻能从 OPML 导出并在别的阅读器还原全部订阅。

---

## 2. 范围决策与一处需要点名的张力

四个发布形态全选 + 播客托管，但定位是"个人自用"。这两件事的**成本结构完全不同**：播客托管引入音频存储、带宽与 byte-range 服务，聚合再分发引入版权与源标注义务。处理方式不是砍需求，而是**分层开关**：

| 形态 | 是否进 MVP | 决策与理由 |
|---|---|---|
| F1 站内内容发布（CMS 出 feed） | ✅ MVP | 纯结构化输出，复用同一套渲染层即可，成本最低、收益最高 |
| F2 订阅抓取 + 阅读 | ✅ MVP | 项目本体 |
| F3 系统/机器事件源 | ✅ MVP（薄实现） | 复用 F1 的渲染层：`event_item → post 视图`，只是一张写入表 + 一个专用 feed 出口 |
| F4 AI 摘要/翻译/去重 | ✅ MVP（只接摘要+去重） | 有明确成本上限才放开；检索（向量）放二期 |
| F5 关键词规则 + 告警推送 | ✅ MVP | 与抓取同期做，否则抓取完还得回阅读器看，价值折半 |
| F6 播客托管（音频 + `itunes:`） | ⛔ 后置 M4 | 需要对象存储、Range 服务、封面校验、目录提交流程；个人自用可先用"外链音频 + 只做 feed"过渡 |
| F7 聚合再分发（外部 feed 规范化后再发布） | ✅ **M5 已交付（2026-10-31）**，且默认只转发**摘要 + 原文回链** | 全文再分发有版权与源站流量义务；实现上是 F2 条目 → F1 渲染层的映射。范围只含"已订阅源的再分发"，外部**投稿进来 + 人工审核**没做（见 §4 F7） |

> 依据：播客专项约束（`enclosure` 三要素、byte-range、GUID 永不变、Apple 必填清单）见 [rss-guide.md:412](../rss-guide.md#L412)；再分发时的 `<source>` 出处标注见 [rss-guide.md:560](../rss-guide.md#L560)（"若源内容被他人转发，转载方应使用 `<source url="...">` 保留原始出处"），该元素在 item 元素表里的位置见 [rss-guide.md:175](../rss-guide.md#L175)。

---

## 3. 用户画像

单人多角色，因此**不做租户体系**，只做"操作员"一个角色 + 一个匿名可读域。

| 角色 | 描述 | 关键诉求 |
|---|---|---|
| 我自己（操作员） | 唯一登录用户。既是订阅者也是发布者 | 收件箱清爽、规则可配、发文快、feed 别出错 |
| 我的订阅者（匿名） | 少量朋友/机器 | 拿到稳定 URL 的合法 feed，不要求界面 |
| 外部系统 | 我的脚本、CI、告警器 | 能 POST 事件、能拉事件源、能拿到 webhook 推送 |

非目标（明确不做）：多用户注册、付费墙、社交/评论、移动端原生 App、全文搜索索引服务（用 PG FTS 够了）、公共 hub 运营。

---

## 4. 功能需求

### F1 站内发布

- FR-1.1 文章（Post）：标题、slug、摘要、Markdown 正文、标签、分类、发布时间、更新时间、草稿/发布状态、封面图。
- FR-1.2 自动产出三个公开出口：`/feed.rss`、`/feed.atom`、`/feed.json`（后缀式，口径见 [03 §6.2](./03-technical-design.md)），以及**每个分类/标签一个源**（`/tag/{slug}.{format}`、`/category/{slug}.{format}`）。
- FR-1.3 HTML 页面 `<head>` 自动注入 `<link rel="alternate">`（多格式 + 多粒度）。
- FR-1.4 每个出口具备 `ETag`（内容指纹）+ `Last-Modified` + `Cache-Control` + `ttl`。
- FR-1.5 GUID 稳定性约束：`guid`/`atom:id`/JSON `id` **一律用永久 URL**，改标题/改正文不得改变 ID；同一文章在所有源里 ID 相同。
- FR-1.6 变更公告输出：`/feed/changelog.xml`（把 Release/版本记录当条目发）。**v4 口径修正，且已于 2026-11-01 交付**：实际出口按本仓统一的**后缀式三格式** `/feed/changelog.{rss,atom,json}`（原文的单 `.xml` 与 D-01 同源——需求写"一个文件"，实现按格式协商，见 [03 §6.2](./03-technical-design.md)）；条目是"站点对文章做过的公开变更"（新建 / 编辑 / 状态移动 / 删除四类），**草稿期的改动一条都不记**，撤下与删除也照样公告（记录表与被记文章之间没有外键，条目比文章本身活得久）。

### F2 订阅与阅读

- FR-2.1 订阅源管理：按 URL 添加（自动嗅探 HTML 里的 `rel=alternate`、RSS/Atom/RDF/JSON Feed 自动识别）、分组、标签、静音、归档。**2026-11-02 交付（F10）**：① 解析（`POST /api/feeds/discover`）**之前先清空上一次的解析结果**，避免把旧候选当成刚解析出来的东西；② 解析成功后可**直接把解析出的地址添加为订阅**——单候选自动选中并给"添加"按钮，多候选逐个"添加"；③ 订阅列表**就地可改**标题、分组、状态、抓取间隔（`PATCH /api/feeds/{id}` 的白名单只有这四个键，[SubscriptionService.java:51](../../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L51)），**URL 不可改**——补丁里出现任何别的键（含 `url`）得 400 `unknown_field`，因为换地址等于换了一个源，条目与队列行都挂在旧行上；④ 操作者要的标题与节奏**各存一列**（`feed.custom_title`、`feed.fetch_interval_minutes`，V7 迁移），不再被下一轮抓取覆盖：原先改的是 `feed.title`/`feed.ttl_minutes`，而那两列是站点自报名与 RSS `<ttl>` 的落点，"改一次活一轮"；留空（`""`）与 0 是"交还站点说了算"的写法。详见 [03 §10](./03-technical-design.md)。
- FR-2.2 抓取：按 `ttl` + 抖动轮询；条件请求（`If-None-Match`/`If-Modified-Since`）；状态码语义处理（301 改地址、410 停抓、429 遵守 `Retry-After`、5xx 指数退避）。**2026-11-02 交付（F10）**：`POST /api/feeds/{id}/refresh`（"立即抓取"）把该订阅的队列行 `fire_at` 提前到当下，并在**事务提交后**唤醒调度器——唤醒若在提交前，worker 扫到的还是旧队列，这一枪就白开（[SubscriptionService.java:202](../../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L202)）；跑完这一轮之后回到策略节奏。缺省节奏 `rsshub.fetch.interval=60m`（**一小时一轮**），每源用 `intervalMinutes` 手动覆盖，下限 `min-interval=5m`；两个数由 `GET /api/feeds/policy` 交给界面做夹取提示。`paused`/`gone` 的订阅点"立即抓取"得 409 `not_schedulable`，不是静默无响应。
- FR-2.3 条目落库：按 `(feed_id, guid)` 幂等去重；`updated` 变体存为条目修订记录，不新增条目；首次订阅**只建基线不报未读**。
- FR-2.4 收件箱：全部/未读/星标/分组/标签/关键词命中过滤；已读、星标、归档三态；键盘操作（j/k/n/s/m）；桌面通知（可选）。**2026-11-01 交付（F2.4，关闭 [test-plan §12](../test-plan.md) 的 BLK-01）**：六个过滤全部可用——前四个（全部/未读/星标/分组）走 `GET /api/entries` 已有的 `state` 与 `group`，本轮新增 `?alerted=`（三态：只看关键词命中 / 只看未命中 / 不窄化）与 `?category=`（匹配 `categories` JSON 数组的**整个元素**，"Smoke" 命中不了 "SmokeTag"）；"关键词命中"的过滤与列表行的红标读的是同一张 `notification_log`，**不会出现"标了红却筛不出来"**。键盘 j/k/n/s/m 与"下一条未读"在 `useInboxKeys.ts`，动作复用同一个 store；桌面通知 + 60 秒未读轮询在 `useUnreadAlerts.ts`，授权只在用户点"开启通知"时申请。**未复核项**：三处都只有 `[自]`/`[C]` 断言，键盘焦点、通知权限弹窗与桌面横幅的观感属 `[B]`，未在浏览器走查。
- FR-2.5 原文渲染：优先用 feed 内正文，缺全文时用 `summary + 原链`；正文 HTML 必须清洗（去脚本、去跟踪像素、域名相对化）。
- FR-2.6 OPML 导入/导出。
- FR-2.7 抓取健康度视图：失败源、连续失败次数、平均耗时、上次 HTTP 状态、下次重试时间。

依据：状态码处理表 [rss-guide.md:510](../rss-guide.md#L510)、条件请求与缓存 [rss-guide.md:499](../rss-guide.md#L499)、GUID 规则 [rss-guide.md:175](../rss-guide.md#L175)、编码硬性要求 [rss-guide.md:606](../rss-guide.md#L606)。

### F3 系统事件源

- FR-3.1 `POST /api/ingest/events`（Bearer token）：写入 `title/link/summary/tags/publishedAt/dedupeKey`。
- FR-3.2 `dedupeKey` 相同则更新而非新增；无 `dedupeKey` 时用内容哈希。
- FR-3.3 事件流可选"仅内部可见"（不进收件箱、只作输出 feed）或"当作一个虚拟源的条目"进收件箱。
- FR-3.4 公开出口 `/feed/events.xml`：把内部事件发给外部订阅者（如 CI 状态、监控公告）。

### F4 AI 加工

- FR-4.1 对新条目异步生成：中文摘要（≤120 字）、语言检测、主题标签、近似重复判定。
- FR-4.2 结果按 `content_hash` 缓存，相同内容不二次计费。
- FR-4.3 成本护栏：每日 token 上限、每月预算、单条最大输入长度、超限自动降级为"只存不加工"。
- FR-4.4 所有 AI 产出必须**标注为机器生成**且可一键关闭，原始 `title/summary/content` 永不被覆盖。
- FR-4.5（二期）向量检索：`pgvector` + 语义搜索"过去三个月关于 X 的更新"。

### F5 规则与告警

- FR-5.1 规则条件：关键词包含/排除、正则、标签、指定源或全源、时间窗。
- FR-5.2 动作：钉钉机器人 / 企业微信 / 邮件 / 通用 Webhook / 仅标记不打扰。
- FR-5.3 抑制策略：同一规则同一来源 N 小时内只推一次；全局静默时段；连续失败自动停用规则并在界面告警。
- FR-5.4 规则试跑：用最近 200 条历史条目预览命中结果，不真正推送。

### F6 播客托管（M4）

- FR-6.1 节目（Show）+ 剧集（Episode）管理：`itunes:` 全量必填项、封面上传校验、音频上传。
- FR-6.2 音频服务：支持 Range/206、无重定向、无 Referer 限制、`length` 与真实字节一致、MIME 准确。
- FR-6.3 剧集 GUID 永不变；换 feed 地址支持 301 + `itunes:new-feed-url`。
- FR-6.4 feed 校验器前置检查：必填项、`explicit` 取值、封面尺寸/格式、音频可达性探测，给出"Apple 会不会拒收"的清单式结论。

### F7 聚合再分发（M5）

- FR-7.1 选择若干已订阅源，映射为一个"转发 feed"：条目保留 `<source>` 原出处、`link` 指向原文。
- FR-7.2 默认只转发摘要；全文转发需按站点逐个显式开启（并在 UI 提示版权义务）。

> **M5 交付实况（2026-10-31）**：FR-7.1 落进 `GET /repack/{slug}.{format}`（rss/atom/json 三格式），条目沿用**原 guid 作为 ID**（不是 URL 才退到原文 permalink，再退到本站铸造的 URN），`<link>`/`external_url`/`atom:source` 全部指向原站，`dismissed` 的条目不参与再分发。FR-7.2 的"逐个显式开启"是 `PATCH /api/repacks/{slug}/feeds/{feedId}` 上的 `fullContent` 布尔，库里缺省 FALSE、插入成员时根本不接收该参数，UI 侧开全文前有一道确认框；每次开/关都写 `repack_source_audit`（`add`/`remove`/`full_content_on`/`full_content_off`，无外键，所以退订与删集合都删不掉历史）。**明确没做**：外部投稿入口与人工审核队列（需求文本只承诺"已订阅源"）。实现细节见 [03 §8.6](./03-technical-design.md)。

### F8 运营增强（M3.5，2026-10-18 用户追加）

- FR-8.1 大屏：一屏卡片墙，每个订阅源一张卡片，显示该源最新 N 条（默认 20，可在设置中改为 1–50）；卡片带未读角标（口径与收件箱一致：排除已读与存量基线），已读条目变淡；刷新间隔可配置（0–600 秒，0 表示仅手动刷新）。**2026-11-02 丰富（F11）**：一屏不再只有卡片墙，`GET /api/dashboard` 一次带回**五块内容**——① **KPI 条 7 项**（未读 / 24 小时到达 / 24 小时命中 / 24 小时投递失败 / 问题订阅 / 安静订阅 / 站内文章）；② **24 小时直方图**，锚到整点的 **24 个桶**（到达与命中两条），桶和**恰好等于** KPI 的 `arrivedToday`/`hitsToday`，柱子与头条数字不可能各说各话；③ **分组轨道**（每组一张：源数 / 未读 / 24 小时到达）；④ **三条侧栏**：最近的规则命中（规则已删仍显示"（规则已删除）"、条目已删仍留规则名）、本站已发布文章（跟随发布可见性，`unlisted` 与草稿刻意不进）、最近的变更公告；⑤ 卡片本身补上连续失败次数、最后一次抓取错误、上次变化时间与**下次抓取时间**（队列里没活时该字段缺席）。所有聚合仍由**归属包自己的读方法**给出（`feedstore`/`rules`/`editorial`/`changelog`），`adminapi` 不写 SQL，ArchUnit 七条不变。
- FR-9.1 系统状态：设置页展示系统（OS/Java/进程启动时间/运行时长）、JVM（堆使用/已提交/上限、线程数、已加载类）、数据库（可用性、产品名、订阅/条目/规则/通知计数）、服务（抓取与投递调度是否运行、任务队列与投递日志按状态分布、最后抓到条目的时间）。数据来自 JDK MXBean 与 SQL 计数，不开放 actuator 敏感端点。
- FR-9.2 系统参数：可在线修改大屏每源条数、大屏刷新间隔、收件箱每页条数（10–200）、投递日志每页条数（10–500），保存即生效、无需重启；参数值与真实读取路径一致（收件箱/投递列表的缺省 limit 直接取自这里），设置页不可能"改了但没生效"。抓取节奏与投递超时属于部署级配置（application.yml/环境变量），刻意不入库。

---

## 5. 非功能需求

| 类别 | 要求 | 说明 |
|---|---|---|
| 规模 | 300–2000 个源、每天 5–20 万条目落库、保留 6–12 个月 | 单机 PostgreSQL + 本地/对象存储可承载；超出则走归档策略 |
| 时延 | 新文章从源站发布到本库 ≤ 1 个轮询周期（默认 60 分钟）；自己的发布到 feed 可见 ≤ 60s | 不引入 WebSub（本次未选），轮询是延迟上限 |
| 吞吐 | 抓取并发 4–8，全局礼貌限速（同域名串行、间隔 ≥ `ttl` 与 30s 取大者） | 避免被对端 WAF 封禁 |
| 可靠 | 抓取失败不影响阅读历史；任务可重放；PG 每日备份 + 原始 XML 留存 ≥ 30 天 | 出问题时能用原始字节重新解析 |
| 可观测 | 每个源的 `http_status/耗时/条目增量/失败原因` 可查；AI 调用量与花费可查；规则推送成功率可查 | 自建服务最怕"静默坏掉" |
| 安全 | 后台仅 1 个操作员账号 + MFA（TOTP）；抓取侧 **SSRF 防护**（禁止解析到私网/回环/元数据地址，禁跳转跟随到私网）；正文 HTML 白名单清洗；私有源凭证只走 HTTPS；密钥全部来自环境变量 | 抓取任意 URL = 天然的 SSRF 面，这是本项目第一安全风险 |
| 兼容 | 输出必须过 W3C Feed Validator 0 error；能解析不规范输入（错误时区、裸 `&`、混合命名空间） | 解析侧要宽容，产出侧要严格 |
| 可迁移 | OPML 进出；条目可导出 JSONL；feed URL 设计成"十年不变"的稳定形态 | 自托管的核心价值就是退出成本可控 |
| 界面（F12 立，F13 迁 Bootstrap 5.3，F14 改淡蓝色调，2026-11-02） | 控制台以 Bootstrap 5.3 的组件为骨架（`navbar`/`card`/`table`/`form-control`/`badge`/`alert`/`progress`），Win11 观感留在品牌层：**淡蓝** mica 画布（`--canvas: #e9f1f9`）上叠白色表面（卡片/输入框/弹层 `--surface: #ffffff`）——这句在 F13 只是意图，F14 才真的成立，因为 Bootstrap 的 `.card`/`.form-control`/`.dropdown-menu` 都从 `--bs-body-bg` 取底色，画布一旦挂在那个变量上就整片同色；发丝级蓝灰描边、控件 4px 圆角 / 卡片 8px / pill 999px、单一强调色 `#0067c0` 只用于"这是主操作"（F14 保留，用户所选）、控件悬停有反馈、键盘焦点有可见环（`:focus-visible` 2px 环）；密度由根字号 14px 决定，Bootstrap 全 rem，所以改一个数就整体变密 | 组件由框架给，**颜色与几何只写在一个文件**里（`web/src/styles/theme.css`：品牌令牌 + 把 `--bs-*` 指向它们的桥接块），15 个 `.vue`（12 视图 + 2 组件 + `App.vue`）不再出现硬编码色值——换肤改一个文件，不改十五个；但"改一个文件"不等于"只改 `:root`"：Bootstrap 有五条规则把灰阶写成**字面量**（`.btn-outline-secondary`/`.btn-secondary`/`.btn-outline-dark` 与 disabled 的 `.btn-link`），令牌碰不到，只能按组件逐键覆盖（F14 实测，口径见 [03 §11](./03-technical-design.md)）；**这个"一个文件"有两条已知的框架外色位**：`.vue` 里的色值（现为 0 处）和 `web/index.html:6` 的 `<meta name="theme-color">`（F14 实测时它没跟上调，已改为与 `--canvas` 同值）；且**不加载 Bootstrap 的 JavaScript**，下拉/折叠/toast 都是 Vue 状态加 CSS 类，运行时不需要 Popper。**这些数值是本项目的自测近似值，不是微软公开的令牌**（见 [03 §11](./03-technical-design.md)） |
| 成本 | AI 月预算上限（默认 ¥50，可改）；音频存储/带宽走廉价对象存储或直接外链 | 明确阈值，超限降级而非静默超支 |
| 部署 | 一条 `docker compose up -d` 起全栈；升级不需要手工 SQL（Flyway）；单机内存 ≤ 1GB（不含 PG） | 自用服务不能变成运维负担 |

---

## 6. 用户故事（按优先级）

Must
- 我贴一个博客地址，它自动找到 feed 并开始抓取；新文章进收件箱，读过的不再出现。
- 我写一篇文章点发布，1 分钟内在 `/feed/rss` 能看到，且三个格式都合法。
- 我给"CVE/安全公告"这个分组配一条规则：命中 `CVSS >= 7` 或关键词 `0day` 就推钉钉。
- 我导出现在阅读器的 OPML，两边订阅与分组一致。
- 某个源挂了 3 天，我在健康度页一眼看到，恢复后自动追上（不重复推旧文）。

Should
- 新条目自动生成中文摘要；我不想看的主题用规则静音。
- 内部 CI/监控把事件 POST 进来，形成一个可对外订阅的事件源。
- 手机上能用（响应式，不做原生 App）。
- 浏览器投上墙就是大屏：各源最新条目一屏看全；设置页能看到 JVM/服务状态并调每页条数。

Could
- 播客节目托管与目录提交流程。
- 语义搜索历史更新。
- 阅读时长统计与"本周信息摄入"报告。

Won't（本期明确不做）
- 多用户/权限体系、付费墙、公共注册开放、推荐算法、站内评论。

---

## 7. 验收标准（DoD）

| 需求 | 验收方式 |
|---|---|
| FR-2.2 抓取 | 单测 + 集成测试覆盖：200/304/301/410/429/5xx/超时/非法 XML 八种响应，断言 `next_fetch_at`（实现里落在 `scheduled_task.fire_at`，`feed` 表没有这个列——见 [02 §10](./02-architecture.md) 的口径说明）、`fail_count`、订阅 URL 变更行为 |
| FR-2.3 幂等 | 同一 feed 重复抓取 3 次，条目数不变；改 `pubDate` 不改 GUID → 仍为同一条目且新增一条修订记录 |
| FR-2.4 收件箱 | MockMvc + 真 HTTP 双层断言：六个过滤各自生效且能叠加（`state`×`group`×`q`×`alerted`×`category`）；`alerted` 是**三态**——缺省不窄化、`true` 只回命中行、`false` 只回未命中行，且命中/未命中两半之和等于全量；`category` 匹配 JSON 数组的**整个元素**（前缀不命中）；红标与"只看命中"读同一张 `notification_log`，所以**不存在"看得见红标却被过滤藏掉"**的行；`alerted=maybe` 得 400 `bad_alerted`（`urn:rsshub:` 前缀）；徽标计数不跟随过滤。键盘与桌面通知只有 `[B]` 走查（见 [03 §14 O23](./03-technical-design.md)） |
| FR-1.4/1.5 输出 | 三个出口返回 `ETag` 且内容变则变；`curl -H 'If-None-Match'` 得到 304；改动文章标题后 GUID 不变；Validator 0 error |
| FR-5.3 抑制 | 同规则连续 3 次命中只推 1 次；静默时段内不推 |
| FR-8.1 大屏 | MockMvc 断言：每源一张卡片、卡内按发布时间倒序、`dashboard.entriesPerFeed` 截断生效、未读角标排除已读与基线、暂停源仍显示。**F11 增补**：直方图**恒 24 桶**且首末桶相差整 24 小时、到达桶之和 = `arrivedToday`、命中桶之和 = `hitsToday`（柱与头条同源，不是两次查询的巧合）；`stats.entries`/`stats.unread` 与收件箱同一张表的同一口径计数一致；三条侧栏行数与来源表一致，且**删掉规则、删掉条目、删掉文章后轨道仍出得去**（`notification_log` 的两个 FK 是 `ON DELETE SET NULL`，变更公告本就没有 FK）；`unlisted` 与草稿不进发布轨道；卡片带 `failCount`/`nextFetchAt`，队列无活时该字段**缺席而不是 null** |
| FR-2.1/2.2 订阅编辑与立即抓取（F10） | MockMvc + 真 HTTP 双层断言：`PATCH /api/feeds/{id}` 白名单外的键（含 `url`）400 `unknown_field` 且不落库；空标题回落站点名、站点名也空则回落地址；`intervalMinutes` 的 0=交还站点、1–43200 生效、-1 与 43201 得 400 `bad_interval`；`paused`→`armNow`、`active` 回读 `nextFetchAt`；`POST /{id}/refresh` 对 `paused`/`gone` 得 409 `not_schedulable`，对活动订阅把队列行 `fire_at` 提到当下**并在提交后唤醒调度器**（`FetchNowServiceTest`/`FetchWakeupTest` 钉这两半）；真 HTTP 侧连抓两次验证"立即抓取"不等下一轮 |
| 界面（F12 立，F13 迁移后重测，F14 改淡蓝后重测） | 自动化能到的部分：`npm run build` 的 `vue-tsc --noEmit` 0 错误、94 modules 编译、产物 CSS 322.72 kB（gzip 48.02）里含 Bootstrap 全量 + 图标字体表 + 品牌层；`grep -rniE '#[0-9a-f]{3,8}\b' --include=*.vue web/src` **零命中**（F14 复测仍 0 处，`.vue` 里没有色值；全项目另一处色位是 `web/index.html:6` 的 `theme-color`，F14 起与 `--canvas` 同值 `#e9f1f9`，且已随 `npm run build` 落到 `dist/index.html`）；顶栏页签是数据驱动，11 个 `tab.name` 与路由表 `router/index.ts:19-29` 的 11 条管理路由一一对应（第 12 条 `login` 按设计不进页签；脚本比对，见 [测试计划 24.3 TC-NAV-01](../test-plan.md)）；`grep -rn ":hover" --include=*.vue web/src` 只剩收件箱条目行 1 处，其余交给 Bootstrap。**观感不在这条 DoD 里**：控件高度不再是 F12 那个 `--control-height: 32px` 而是 Bootstrap 的 padding × 根字号（实测口径见 [03 §11](./03-technical-design.md)），悬停是否克制、mica 半透明在低配机上的表现、登录后每一屏的排版都属 `[B]`，**未在浏览器逐页走查** |
| FR-9.1/9.2 设置与状态 | MockMvc 断言：未知键/非整数/越界分别 400 且不落库；收件箱与投递列表缺省 limit 取自设置；`fetchEnabled/notifyEnabled` 与 `@ConditionalOnProperty` 同源；四个计数与库内行数一致 |
| FR-7.1/7.2 聚合再分发 | MockMvc + 真 HTTP 双层断言：出口每条带 `<source url>`（Atom 是 `<source><id>`、JSON 是 `external_url`）且 `<link>`/ID 指向原站；未开启全文时 outlet 里**没有** `content:encoded`，开启后有；`full_content` 缺省 FALSE、重复 PATCH 不追加审计行、成员与集合删除后审计行仍在；错误面 409 `repack_exists`/`already_repacked`、404 `feed_not_found`/`not_a_member`、400 `invalid_slug`；出口匿名可读且不建会话、ETag 变则 304 |
| FR-4.3 护栏 | 触达日预算后任务降级为"不加工"，并在界面标记"未加工原因：预算" |
| FR-1.6 变更公告 | MockMvc + 真 HTTP 双层断言：草稿的新建/编辑/删除**一条都不写**（草稿标题不得出现在任何一份公开文档里）；发布/编辑/状态移动/删除各写一行且 `<category>` 带动作名；条目 ID 是变更自己的 URN（`urn:rsshub:change:{public_id}`），所以同一文章的多条变更互不覆盖、改名也不改写历史条目的 ID 与 URL；同状态 PATCH 与内容未变的 PUT 静默，409 非法迁移静默；`GET /api/changelog` 只读（POST 405）、匿名 401、`postId`/`limit` 生效且 `limit` 被夹在 1–500；出口匿名可读、不建会话、ETag 与站点源互不相同、`If-None-Match` 得 304；三格式与空库都合法；条数上限沿用 `item-limit` |
| 安全 | SSRF 用例：`http://127.0.0.1`、`http://169.254.169.254`、内网域名、重定向到私网 → 全部拒绝且记录 |
| 备份 | 执行备份脚本后，能在干净容器里恢复到可服务状态（含原始 XML） |

---

## 8. 依赖、假设与风险

**假设**：有一台常驻机器（或 NAS/VPS）；有可访问的 LLM API；音频若托管则由我自有 CDN/对象存储。

| 风险 | 影响 | 应对 |
|---|---|---|
| 抓取被对端封禁 | 静默丢更新 | 条件请求 + 礼貌限速 + 可联系 UA；健康度页监控 4xx 比例；关键源配双 URL |
| LLM API 涨价/不稳定 | AI 功能失效 | 抽象 provider 接口，可关；产出落库后不因 AI 失败而阻塞主流程 |
| 单用户系统的"备份幻觉" | 数据全丢 | 每日 `pg_dump` + 异地（对象存储）副本 + 每月做一次真实恢复演练 |
| 播客音频带宽成本失控 | 账单意外 | M4 上线时强制走对象存储直链 + 用量告警；不做站内代理转发大文件 |
| Rome 等库维护低频导致解析缺陷 | 不规范源解析失败 | 解析层自己封装 + 原始 XML 留存，可热补丁替换实现 |
| 需求膨胀（四种发布形态同时推进） | 拖垮 MVP | 范围分层见第 2 节；M4/M5 之前不开工 |

---

## 9. 下一步

- 架构与模块边界：[02-architecture.md](./02-architecture.md)
- 数据模型、接口、抓取算法、部署细节：[03-technical-design.md](./03-technical-design.md)
- 里程碑与工时：[04-roadmap.md](./04-roadmap.md)
