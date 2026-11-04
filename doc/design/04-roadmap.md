# 04 · 产研计划（里程碑 · 工时 · 脚手架切片）

> 定位：个人自用项目，一个人做，串行推进，每个里程碑结束都要"能真跑起来的东西"。
> 关联：[01 需求分析](./01-requirements.md) · [02 架构设计](./02-architecture.md) · [03 技术设计](./03-technical-design.md) · 规范依据 [`doc/rss-guide.md`](../rss-guide.md)
> 开工日：**2026-10-19（周一）**，已核实为周一；后续日期均取周一为里程碑起点。

---

## 1. 节奏假设（先说清楚换算口径）

| 口径 | 有效工时 | 用途 |
|---|---|---|
| 全职冲刺 | 1 人日 = 1 个工作日（≈6.5h 真正写代码） | 排期表按这条给出 |
| 兼职常态 | 12h/周 ≈ 1.5 人日/周 | **所有日期 × 3**，这是更可能的现实 |

估算含"设计—实现—测试—修修补补"，不含外部审批。自用项目的最大成本项从来不是功能，而是**静默坏掉之后的排查**，所以每个里程碑都硬性带测试与可观测，不后置。

**串行原则**：一次只开一个里程碑；`M4` 之前不开 `M5`，`M1` 未过门禁不进 `M2`（依赖关系见 §3）。
> 执行时的例外（2026-10-31）：**M5 抢在 M4 前交付**。原句把两者绑在一起是为了防止"能力扩展挤掉刚需"，而 M4 另有三条硬前置（真实使用 ≥4 周、有一档真要发的节目、已选定对象存储/CDN，见 §4/M4 开工前置），至今不满足；M5 没有这类外部前置，只做已订阅源的再分发。所以"不开 M5"的那半句随 M4 一起顺延，M4 仍排在最后。

---

## 2. 里程碑总览

| # | 名称 | 出口（可对外说的一句话） | 人日 | 全职周 | 兼职周 | 计划日期（全职口径） |
|---|---|---|---|---|---|---|
| M0 | 地基与脚手架 | ~~`docker compose up` 起来，空壳可登录~~ → **2026-10-18 完成**：无 docker 版（H2 + 打包 jar）空壳可登录；compose 一项仍"未执行" | 3.5 | 1 | 2.5 | 提前于 2026-10-19 计划起点 |
| M1 | 订阅 + 抓取 + 收件箱 | ~~能替代我现在的阅读器~~ → **2026-10-18 开发完成**（M1 末 181 用例全绿，见下方进度记录）；"150 源跑 72h""Miniflux/FreshRSS 真机导 OPML""P95<200ms"三条门禁未执行 | 14 | 3 | 9 | 2026-10-26 → 11-13 |
| M2 | 发布侧三格式 + 站内文章 | ~~我自己的 feed 上线且能过校验~~ → **2026-10-18 完成**：三 writer + SSR + ETag/304 + 编辑 UI；本机等价校验通过，公网 validator 因未上域名留待上线检查 | 9 | 2 | 6 | 提前于 2026-11-16 计划起点 |
| M3 | 规则告警 + 事件源 | ~~**MVP 完成**：不用回别的网站看更新~~ → **2026-10-18 开发完成**（343 用例全绿，M2 末为 246）；"真机用另一个阅读器订阅 `/feed/events.rss`"与"MVP 自测周"两条**只能人工执行，未做** | 7 | 1.5 | 5 | 2026-11-30 → 12-11 |
| M3.5 | 运营增强（用户追加） | **2026-10-18 完成**：大屏（每源卡片+最新 N 条）+ 设置页（系统/JVM/服务/数据库状态 + 四个在线参数）；360 用例全绿 + 55 项真 HTTP 冒烟全过；浏览器走查留待人工（[03 §14 O19](./03-technical-design.md)） | 2 | 0.5 | 1.5 | 2026-10-18（当日插入执行） |
| M4 | 播客托管（可选） | 能提交目录的一档节目 | 10 | 2.5 | 7 | 2026-12-14 → 2027-01-08 |
| M5 | 聚合再分发 | ~~一个跨源主题 feed~~ → **2026-10-31 开发完成**：`repack` 包 + `/repack/{slug}.{format}` 三格式出口 + 管理页；370 用例全绿 + 86 项真 HTTP 冒烟全过（门禁三条都有断言）；"真机用另一个阅读器订阅一个跨源 feed"与浏览器走查**只能人工执行，未做**（[03 §14 O20](./03-technical-design.md)） | 3.5 | 1 | 2.5 | 提前于 2027-01-11 计划起点 |
| M6 | AI 增强 + 检索 + 归档 | 语义搜历史 + 摘要/去重稳定运行 | 8 | 2 | 5 | 2027-01-18 → 01-29 |
| F1.6 | 变更公告（**M2 补账**，2026-11-01 开发完成） | ~~`/feed/changelog.xml`~~ → 后缀式三格式 `/feed/changelog.{rss,atom,json}` + `GET /api/changelog` + 文章页"变更历史"面板；**关闭 BLK-02**（M2 唯一未交付的需求项）；380 用例全绿 + 129 项真 HTTP 冒烟全过；浏览器走查与"真阅读器订阅公告源"留待人工（[03 §14 O21](./03-technical-design.md)） | 0.5 | — | — | 2026-11-01（插入执行，不占里程碑） |
| F2.4 | 收件箱过滤 / 键盘流 / 桌面通知（**M1 补账**，2026-11-01 开发完成） | ~~只有红标不过滤~~ → `GET /api/entries` 的 `?alerted=`（三态）与 `?category=`（整元素匹配）+ V6 索引 + j/k/n/s/m + 60 秒未读轮询与桌面通知；**关闭 BLK-01**；顺手修掉 FR-9.2 的一处旧欠账（前端硬发 `limit=50` 让"收件箱每页条数"形同摆设）；382 用例全绿 + **150 项**真 HTTP 冒烟全过（两个独立一次性库各一遍）；键盘手感、权限弹窗与通知观感**只能人工执行，未做**（[03 §14 O23](./03-technical-design.md)） | 0.5 | — | — | 2026-11-01（插入执行，不占里程碑） |
| F10/F11/F12 | 订阅就地编辑 + 大屏充实 + 界面令牌（**M1/M3.5 补账 + 一层新抽象**，2026-11-02 开发完成） | 三件事一轮收口：`PATCH /api/feeds/{id}` 的可改白名单（标题/分组/状态/抓取间隔，**URL 拒改而非忽略**）+ V7 两列分权（`custom_title`、`fetch_interval_minutes`）+ 立即抓取真跑本轮（`fire_at=now` + `afterCommit` 唤醒）+ 一次 `GET /api/dashboard` 喂满整屏（24 桶直方图、十个标量、三条轨道）+ `fluent.css` 令牌层（Win11/Fluent，色值只在一个文件里）；**关闭 D-04（间隔占位写死）与 F-05（ttl 无法清空回站点）**；**397 用例 / 38 类全绿 + 193 项真 HTTP 冒烟全过**；就地编辑手感、大屏排版与全部视觉观感**未在浏览器复核**（[03 §14 O24](./03-technical-design.md)） | 1 | — | — | 2026-11-02（插入执行，不占里程碑） |
| F13 | 控制台迁到 Bootstrap 5.3（**F12 那层抽象的再补账**，2026-11-02 开发完成） | 用户口径"UI 太难看了，请使用 bootstrap 重新优化UI"→ 组件层交给框架、品牌层留在自己手里：`bootstrap@5.3.8` + `bootstrap-icons@1.13.2` 进 `dependencies`，`main.ts:5-7` 三段式引入，`theme.css` 只做变量桥接（**保留 `#0067c0` 强调色**）；**不引 Bootstrap 的 JavaScript**；15 个 `.vue` 从 5185 行降到 3747 行、`fluent.css` 退役；顶栏改数据驱动（11 个页签）；**94 modules / CSS 321.03 kB（gzip 47.71）/ JS 221.11 kB** + 397 用例与 193 项冒烟不回退；本轮改出并修掉四个真缺陷（控件圆角 8px、大屏 paused 徽标回琥珀、等宽字体两族并存、一处图标缺 `aria-hidden`）；`/login` 第一次被 `getComputedStyle` 实测，登录后 11 个界面**仍未在浏览器复核**（[03 §14 O24](./03-technical-design.md)） | 0.5 | — | — | 2026-11-02（插入执行，不占里程碑） |
| F14 | 控制台改淡蓝色调（**F13 品牌桥接的再补账**，2026-11-02 开发完成） | 用户口径"采用淡蓝色色调重新优化UI界面"→ 沿用 F13 的取向**保留 `#0067c0` 强调色**，只动品牌层（`theme.css` 取值 + 覆盖块，外加 `web/index.html:6` 一行 `theme-color`）：中性族整体转蓝（画布 `#e9f1f9`、描边三档、墨色三档、mica、阴影），每个值先过对比度计算；改的过程中撞到 Bootstrap 品牌桥接的三条盲区——`--bs-body-bg` 被画布占用导致卡片/输入框/弹层与页面同色（**F13 承诺的"白表面"从未真的成立**）、背景与文字工具类读 `*-rgb` 双生子（`.bg-body-tertiary`/`.text-bg-secondary`/`.text-body` 三家仍是 Bootstrap 灰阶）、五条规则把灰阶写成字面量（`.btn-outline-secondary` 18 个位点最痛）。状态色 `success/danger/warning/info` 按语义保留；**15 个 `.vue` 一行未动**，`.vue` 色值仍 0 处；CSS 321.03 → 322.72 kB（gzip 48.02）、JS 221.11 kB 不变、`vue-tsc` 0 错、397 用例与 193 项冒烟重跑不回退；`/login` 与 13 个代表类的注入探针实测，登录后 11 个界面**仍未在浏览器复核**（[03 §14 O24](./03-technical-design.md)） | 0.2 | — | — | 2026-11-02（插入执行，不占里程碑） |
| F15 | 覆盖面收口 + 交付文档 + 入库（**审的是用例册的诚实度，不是产品**，2026-11-02 完成） | 用户口径"指定完整覆盖所有功能的测试案例，并执行测试案例修复BUG / 更新 readme.md、部署文档 / 提交所有代码"→ 把「控制器真实映射 × 用例编号 × 证据标记」三张表交叉，凡**标记声称有证据而仓库里没有那条证据**的算缺口，找到四处：`GET /api/entries/counters` 只有 MockMvc 却被前端真轮询、`GET /api/rules/{id}` 全目录只有 404 负例、TC-PUB-40/41 挂着 `[C]` 而 `/tag`+`/category` 从未被 curl 过、`[机]` 标记在用例册里没有定义。修法：冒烟 +12 项（**193 → 205**）、`RulesApiTest` 加逐字段断言并立 **TC-RUL-20**（用例 **426 → 427 条 / P0 仍 257**）、§0.3 补 `[机]` 定义；另发现 12 处引用**文件对、行号在范围内、却指错处理器**（M5 加 `/repack` 时整体下移，范围校验看不见），加了新门禁 `.dev-logs/f15-routecheck.js`（把被引行还原成实际声明的 URL 再比，**54 条核对 0 漂移**）。交付面新增 [README.md](../../README.md) 与 [doc/deploy.md](../deploy.md)，写部署文档时撞到一条**真实约束**：Java 自己占了 `/`（公开站），SPA 的收件箱也在 `/`，两者不能同域共存——已按现状写进 deploy.md §4.1 并给出两条出路，不偷偷绕开；**205 项 `ALL PASS` + 397 项全绿**，登录后 11 个界面**仍未在浏览器复核**（[03 §14 O24](./03-technical-design.md)） | 0.2 | — | — | 2026-11-02（插入执行，不占里程碑） |
| F16 | 批量订阅（**M1 FR-2.1 补账**，2026-11-03 开发完成） | 用户口径"增加批量增加订阅的功能，自动以,;:、回车等自动解析成多条"→ 粘贴一整段清单一次订完：`POST /api/feeds/batch/plan` 预览 + `POST /api/feeds/batch` 写入，拆分器 [PastedSubscriptions.java](../../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java) 认逗号/分号（半全角）/顿号/空白/回车为分隔，**冒号只在既不是协议（`://`）也不是端口（`:8443`）时才拆**，且左不像地址右像地址时读成"名字：地址"（名字进 `custom_title`）。三条硬口径：预览真的不写库、批量逐行不发网络请求（抓取交给队列按 10 秒错开）、**读不懂的行仍占一行**（不能让"二十行落十八条"看起来像"十八条"）；判决五态 `ready/added/duplicate/refused/invalid`，一次上限 200 行超出**整批 400** `too_many_lines` 且不留半批，只有分隔符的粘贴 400 `nothing_to_add`。**421 用例 / 40 类全绿**（+`PastedSubscriptionsTest` 12 +`SubscriptionBatchApiTest` 12）、`npm run build` 94 modules / 0 类型错误、`backend/smoke.sh` 由 205 项扩到 **222 项 `ALL PASS`**（F16 段 17 条）、用例 **427 → 461 条 / P0 257 → 280**。批量面板是 `FeedsView.vue` 顶部的一张卡（不加视图、不加路由、不加 store），**未在浏览器复核**（[03 §14 O26](./03-technical-design.md)）；同轮登记 O27：批量判重是字符串相等，`…/feed` 与 `…/feed/` 会各成一行 | 0.2 | — | — | 2026-11-03（插入执行，不占里程碑） |
| — | 合计 | | M0–M3.5 **+ 已提前交付的 M5** + F1.6/F2.4/F10–F12/F13/F14/F15/F16 七笔补账 = **42.1 人日** | ≈10 周 | ≈28 周 | MVP 出口 2026-12-11；M5 实际 2026-10-31 交付；F1.6 与 F2.4 实际同日 2026-11-01 交付；F10–F15 实际同日 2026-11-02 交付；F16 实际 2026-11-03 交付 |

**价值优先顺序的理由**：M1 先做消费侧，因为它替代的是每天真实发生的人工成本；M2 才做发布，因为发布侧晚一周没有任何损失；M3 把"筛选"闭环补上，MVP 到此为止。M4/M5 是能力扩展，不是刚需——[01 §2](./01-requirements.md) 已把它们标成后置，本计划严格执行。

---

## 3. 依赖与关键路径

```
M0 ──▶ M1 ──▶ M2 ──▶ M3 ═══ MVP ═══
              │       └─▶ M5（复用 M2 渲染层）
              └─▶ M4（复用 M2 渲染层 + 对象存储）
M1 ────────────▶ M6（AI 与检索只在条目落库后才有意义）
```
关键路径是 `M0 → M1`：抓取质量决定后面一切。M1 里最耗时的是**容错语料与幂等测试**，不要指望"解析库都能搞定"——[03 §5.5](./03-technical-design.md) 的清洗与 [03 §13](./03-technical-design.md) 的 fixtures 才是工量大头。

对象存储（MinIO）在 M0 就起容器但不接业务，M2 用来存原始 XML，M4 才存音频——避免"为了播客提前一周搞存储"。

---

## 4. 里程碑详情

### M0 · 地基与脚手架（3.5 人日）

**范围**（括号里是 2026-10-18 的实际结果：✅ 做了 / ⚠️ 降级 / ⏭ 推迟）
- ✅ 仓库骨架（[03 §2](./03-technical-design.md) 目录树）、`start.spring.io` 生成 Boot `4.1.1` + Java 21 骨架 —— **生成后必须把 parent 从 `4.1.1.RELEASE` 改成 `4.1.1`**（Maven Central 上无 `.RELEASE` 产物，实测报 `Non-resolvable parent POM`，见 [03 §1](./03-technical-design.md) 脚手架实测第 1 条）。
- ⚠️ **前置动作（M0 第 0 天）**：原计划"只需装容器引擎"。实际按指示**不做 docker**，开发库换 H2 文件库，`docker compose up` 相关门禁全部标"未执行"（详见 [03 §14 O13](./03-technical-design.md)）。
- ✅ Flyway `V1__baseline_schema.sql`：`app_user / api_token / feed / scheduled_task / entry / entry_revision / raw_response / media_asset / settings`（发布与播客表留到各自里程碑，避免空表漂移）；实际是 **`h2/` 与 `postgresql/` 两份方言**，靠 `{vendor}` 选择。
- ⏭ `docker-compose.yml`（app + postgres:18.6 + minio + caddy）、`.env.example`、`backup.sh`。
- ✅⚠️ 安全地基：单账号登录（⚠️ `{bcrypt}` 而非 Argon2id，见 O7）、session、CSRF、`/api/**` 保护 + `/actuator/health` 匿名可读；⚠️ TOTP 与登录失败锁定 **M0 未做**（当时 `grep -rn totp backend/src` 零命中）——两项都在 **M3-b 补齐**，核对见下方 M3 交付状态与 [03 §9.1](./03-technical-design.md)。
- ✅ `SsrfGuard` **在 M0 就写**（不是 M1）：它是抓取的前置条件，且最容易事后补漏。25 个用例覆盖了 M0 门禁列的全部地址类别。
- ✅ ArchUnit `ArchitectureTest`（02 §3 三条边界，实到 7 条规则并做过破坏性验证）；⏭ CI（无远端仓库，等价动作是本地两条命令 + 两个冒烟脚本，见 O14）。
- ⚠️ Micrometer 骨架 + JSON 日志 + 请求 traceId：traceId 全绿（MDC + `X-Trace-Id`），**JSON 日志与指标埋点未做**（[03 §12.2](./03-technical-design.md) 的清单一个都还没接）。

**本阶段不做**：任何业务页面、任何 feed 输出。

**验收门禁（2026-10-18 执行结果；本机无 Docker，凡依赖容器/真实 PG 的一项按口径标"未执行"）**
- [ ] ~~`docker compose up -d` 后 `curl localhost/actuator/health` = `{"status":"UP"}`（PG 已迁移到 V1）~~ — **未执行**（开发机无容器引擎，且本轮指示"先不实现 docker"）。等价证据：直接用打包 jar + H2 文件库启动，`curl http://127.0.0.1:18080/actuator/health` = 200/UP，Flyway 在同一日志里 `Successfully applied 1 migration ... now at version v1`。
- [x] 未登录访问 `/api/feeds` 得 401；跨站/缺 CSRF 的写操作被拦；会话能开能用能关 —— 由 `backend/smoke.sh` 14 项与 `web/verify-dev-proxy.sh` 9 项在**真实 HTTP** 上证明（`ALL PASS`）。**浏览器肉眼复核未做**：本项目按既定约束不用自动化跑登录流程，UI 的实际渲染与点击手感没有验证过，人工验收方法见下方"M0 完成记录"第 3 条。
- [x] `mvn verify` 与 `npm run build` 全绿；ArchUnit 做过破坏性验证（详见完成记录）。
- [x] `SsrfGuard` 单测：`127.0.0.1` / `169.254.169.254` / `10.0.0.5` / `::1` / `localhost` / 链路本地、CGNAT、组播、保留段 全拒。
- [ ] ~~`backup.sh` 跑通一次 `pg_dump` 并能在新容器 `restore.sh` 回来~~ — **未执行**：`deploy/` 整体按指示推迟（[03 §14 O13](./03-technical-design.md)）。

**脚手架产出（文件级 · 2026-10-18 实际交付）**
```
backend/
├─ pom.xml                                        Boot 4.1.1 parent + Java 21 + mvnw
├─ smoke.sh                                       14 项真实 HTTP 冒烟（口令只从环境变量进）
├─ src/main/java/com/rsshub/
│  ├─ RssHubApplication.java
│  ├─ bootstrap/{AppConfig,OperatorProperties,OperatorBootstrap}.java
│  ├─ common/{ApiResponses,ApiExceptionHandler,RequestIdFilter,SsrfBlockedException}.java
│  ├─ security/{SecurityConfig,DbUserDetailsService,AuthController,SsrfGuard,SsrfProperties}.java
│  ├─ feedstore/FeedRepository.java               只读，写入是 M1
│  └─ adminapi/FeedController.java                仅 GET /api/feeds
├─ src/main/resources/application.yml
├─ src/main/resources/db/migration/{h2,postgresql}/V1__baseline_schema.sql   两份方言，逐列对齐
└─ src/test/java/com/rsshub/{ArchitectureTest,AuthGateTest}.java + security/SsrfGuardTest.java
web/
├─ package.json  vite.config.ts  tsconfig.json  index.html  .gitignore  verify-dev-proxy.sh
└─ src/{main.ts,App.vue,env.d.ts}  api/{http,errors,auth,feeds}.ts  stores/session.ts
   router/index.ts  views/{LoginView,FeedsView}.vue
```
**未交付（按指示推迟，不是遗漏）**：`deploy/{docker-compose.yml,Caddyfile,.env.example,backup.sh,restore.sh}`、CI 配置、Element Plus、MinIO 容器。

**M0 完成记录与偏差（每条都可复现，括号里是核对位置）**

1. **后端**：`./mvnw -B -ntp verify` → BUILD SUCCESS，`Tests run: 40, Failures: 0, Errors: 0, Skipped: 0`（`target/surefire-reports/`：ArchitectureTest 7、AuthGateTest 8、SsrfGuardTest 25）。破坏性验证做过：临时加一个 `feedrender` 依赖 `FeedRepository` 的类，`rendering_never_touches_the_store` 立刻报 1 处违规，随后删除。真实 jar 起在 18080 上，`bash backend/smoke.sh` → `ALL PASS`（14 项：跨站登录被拒、发出 csrf cookie、登录 200、token 轮换、health 200/UP、匿名 `/api/feeds` 401、匿名 `/` 401、登录后 `/api/feeds` 200、`/me` authenticated=true、无 csrf 的 logout 被拒、有 csrf 的 logout 204、退出后 `/api/feeds` 回到 401、`X-Trace-Id` 长度 36）。
2. **前端**：`npm install`（88 包）+ `npm run build` → `vue-tsc --noEmit` 无错、`vite build` 产出 `dist/`（101.15 kB JS / 1.62 kB CSS）。`bash web/verify-dev-proxy.sh`（口令从环境变量传）→ `ALL PASS`（9 项，含"登录响应确实轮换了 csrf cookie"这条，它是 SPA 后续写操作不被静默 403 的前提）。
3. **仍要人工做的验收**（我没有替它盖章）：浏览器打开 `http://localhost:5173/` 看登录页是否排版正常、输错密码时错误文案是否可见、登录后顶栏是否显示用户名、退出是否回到登录页。**原因**：本项目按约定不用自动化跑登录流程，所以"界面长什么样"这件事没有任何机器证据，只有静态核对——`SecurityConfig.java:57-59` 的匹配规则、`web/src/api/http.ts:38`（表单编码）与 `:47-51`（写操作回传原始 token）、`web/src/router/index.ts:19-31`（守卫与 `?next=` 回跳）。
4. **口径偏差（已同步进 [03 §14 O7–O17](./03-technical-design.md)）**：口令哈希 `{bcrypt}` 而非 Argon2id；TOTP 未实现（列已建，当时 `grep -rn totp backend/src` 零命中）——**这条已在 M3-b 关闭**，登录锁定同理；`FOR UPDATE SKIP LOCKED` 在 H2 上不可用，M1 需可移植的乐观认领；`deploy/` 与 CI 整体推迟；开发库是 H2 文件库，生产仍是 PG，两份方言 DDL 靠人工对齐。
5. **一处版本口径必须更正**：TypeScript 锁 `^6.0.3`（不是 npm latest 的 7.0.2）——`vue-tsc@3.3.13` 靠 `require.resolve("typescript/lib/tsc")` 定位编译器，TS 7 的 `exports` 白名单不含该子路径，`npm run build` 会直接崩栈（`ERR_PACKAGE_PATH_NOT_EXPORTED`）。原因与复现见 [03 §1 落地实测第 8 条](./03-technical-design.md)。

**风险与降范围杠杆**：Caddy/MinIO/TLS 联调最容易吃时间 → 降范围方案是 M0 先用 `localhost` 明文 + 端口映射，TLS 到 M2 再上（自用无公网依赖时不影响功能开发）。
**工具链现实（2026-10-17 实测，已就地修正口径）**：开发机**没有 `docker`**（`which` 为空），`mvn` 也没有但**不构成阻塞**——生成物自带 `mvnw`，实测用它完成编译与测试类编译（约 5 分钟首包下载）。所以 M0 的 3.5 人日里只需扣"装 Docker + 拉 postgres/minio/caddy 镜像"的半天，而**所有依赖 compose 与真实 PG 的门禁项在本工作区无法执行**，一律标"未执行"，不能当已证。

---

### M1 · 订阅 + 抓取 + 收件箱（14 人日）

**范围**（FR-2.1～2.7）
- `feedfetch`：`[Dispatcher]` → `FetchService`（条件请求、退避、体积/超时上限、每次重定向重过 SSRF）→ `raw_response` 留存。
- `feedparse`：RSS2/Atom/RDF/JSON Feed 归一、GUID 优先级、日期归一、编码容错、正文选择与 jsoup 清洗。
- `feedstore`：`(feed_id,guid)` 幂等 upsert + `entry_revision` + `baseline` 首抓基线。
- `scheduled_task` 队列（`FOR UPDATE SKIP LOCKED` 认领、重启续跑）。
- `adminapi`：feeds CRUD/刷新/OPML 导入导出、entries 列表与状态、health 视图数据。
- `web`：FeedsView、InboxView（虚拟列表 + 键盘流）、ReaderPane、HealthView。**实况**：键盘流于 2026-11-01 随 F2.4 补上（`composables/useInboxKeys.ts`），**虚拟列表始终没做**——一页 = 设置项 `inbox.pageSize` 条 + "加载更多"按游标追加，见下面 F2.4 小节的"偏差"第 4 条。
- 自动发现（[03 §4.4](./03-technical-design.md)）：HTML 里 `rel=alternate` + 常见路径回退。

**本阶段不做**：AI（只留 `ai_artifact` 表不接）、规则、任何输出 feed、移动适配（先能桌面用）。

**验收门禁**（对应 [01 §7](./01-requirements.md)）
- [x] 八种响应全过：200新/200无变化/304/301/410/429+`Retry-After`/5xx/超时/非法 XML；断言 `etag`、`fail_count`、下一次 `fire_at`、301 后 `canonical_url`。（原写"WireMock"，实际由 `LocalFeedServer` 提供——见 M1 进度记录行的校正说明）
- [x] 同一源连抓 3 次条目数不变；改 `pubDate` 不改 GUID → 仍是同条目 + 新 revision。（`EntryIngestServiceTest` 10 项，03 §5.4 六条语义逐条有断言）
- [ ] 真实添加 150 个源（含中文站、GitHub releases Atom、JSON Feed 站、一个坏 feed）跑 72h：抓取成功率 ≥ 98%，收件箱无重复条目。
- [ ] 首次订阅只建基线、不刷未读；重启服务后未完成任务自动续跑。（前半句已自动化：`EntryIngestServiceTest` 的 baseline + `EntryFinder.java:303-305` 的 `state=unread` 排除，`[C]` 也在冒烟里跑过；后半句"重启后续跑"只有 `TaskQueueTest#reapStaleRunning…` 的回收断言，完整重启演练属人工门禁）
- [ ] OPML 导出 → 在 Miniflux/FreshRSS 导入可还原分组（真机验一次）。
- [ ] `/api/entries` P95 < 200ms（1500 源量级下的构造数据）。

**脚手架产出**
```
backend/.../feedfetch/{Dispatcher,FetchService,SsrfPolicy,Backoff,RawStore}
backend/.../feedparse/{Sniffer,Rss2Parser,AtomParser,RdfParser,JsonFeedParser,GuidResolver,DateNormalizer,HtmlSanitizer,NormalizedEntry}
backend/.../feedstore/{EntryRepository,InboxService,OpmlService}
backend/.../adminapi/{FeedController,EntryController,OpmlController,HealthController}
backend/src/test/resources/fixtures/{gbk-declared-utf8.xml,bare-amp.xml,no-title.xml,rdf-1.0.json,...}
web/src/views/{FeedsView,InboxView,HealthView} + components/{EntryList,EntryItem,ReaderPane} + stores/{feeds,entries}
```

**M1 进度记录（2026-10-18，全部可复现：`cd backend && ./mvnw -B -ntp -o test`）**

| 子项 | 状态 | 证据 |
|---|---|---|
| M1a 队列 + 抓取骨架 | ✅ 完成 | `feedfetch` 56 个用例：`TaskQueueTest` 12、`FeedFetchServiceTest` 22（八种响应）、`DnsPinsTest` 10、`FetchPolicyTest` 5、`HostPolitenessTest` 7。**2026-10-31 校正两处旧口径**：① 行数按 `target/surefire-reports` 与 `grep -c @Test` 双核对，`FeedFetchServiceTest` 是 22 不是 21，包内合计 56 不是 55（M1 后追加过一个用例，本表一直没跟着改）；② 八种响应**不是 WireMock 跑的**——`pom.xml` 里没有 wiremock/testcontainers 依赖，假源站是 JDK `com.sun.net.httpserver` 自建的 `LocalFeedServer`（`LocalFeedServer.java:85`），上面"脚手架产出"清单与本节范围里写的 `FetchService`/`Dispatcher`/`SsrfPolicy`/`Backoff`/`RawStore` 是设计期简称，实际类名见 [03 §2 依赖表](./03-technical-design.md) |
| M1b `feedparse` 归一层 | ✅ 完成 | 53 个用例：`TagsTest` 8、`FlexibleDatesTest` 11、`AddressesTest` 6、`HtmlSanitizerTest` 11、`DocumentDecoderTest` 6、`FeedParserTest` 11；7 个 fixtures 在 `backend/src/test/resources/fixtures/`；实测结论与勘误见 [03 §5.6](./03-technical-design.md) |
| 架构约束 | ✅ 未破 | `ArchitectureTest` 7 条规则全绿（含 `feedparse ↮ feedrender`、网络只允许在 `feedfetch/notify/ai/security`） |
| M1c `feedstore` 幂等落库 | ✅ 完成 | `EntryIngestServiceTest` 10 个用例（in-memory H2，跑真 Flyway 迁移）覆盖 03 §5.4 六条语义：首抓 baseline、正文相同不增修订、正文变化记修订 2 且 `read_state` 不动、来源时间更旧则忽略、无时间也能记修订、解析失败不留半条数据；`MigrationDialectAlignmentTest` 4 个用例把 H2 与 PostgreSQL 两份基线脚本的表序与列名逐一对齐 |
| M1d `adminapi` + 收件箱 UI | ✅ 完成 | `InboxApiTest` 当时 19 个用例（**2026-10-31 实测 30 个**：M2/M3/M3.5 的公开出口、事件与设置断言继续写进了这个类，所以它不再是 M1 快照里的 19；`target/surefire-reports/TEST-com.rsshub.adminapi.InboxApiTest.xml` 里 `tests="30"`）（in-memory H2 + `LocalFeedServer` 回环发布，真跑 SSRF 白名单、真发 HTTP）：解析优先 Atom（[FeedDiscoverer.java:49](../../backend/src/main/java/com/rsshub/feedfetch/FeedDiscoverer.java#L49)）、常见路径兜底（[:45](../../backend/src/main/java/com/rsshub/feedfetch/FeedDiscoverer.java#L45)）、重复订阅 409（[SubscriptionService.java:135-136](../../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L135)）、元数据地址在发请求前被拒（[:127-129](../../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L127)）、暂停→队列 `cancelled`、刷新只提前不叠加（[TaskQueue.java:193-200](../../backend/src/main/java/com/rsshub/feedfetch/TaskQueue.java#L193)）、删除级联条目与队列行、`state=unread` 排除 baseline（[EntryFinder.java:303-305](../../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L303)）、keyset 游标不重复不跳行（[:359-366](../../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L359)、编码在 [EntryController.java:239-243](../../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L239)）、OPML 导出后原样导回并保留分组、导入拒绝不可信地址、健康度把出问题的排最前、匿名请求零写入。接口面见 [FeedController.java:55-128](../../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L55)；前端 `web/src/{api,stores,views,components}` 新增 11 个文件，`npm run build` 通过 |
| 全量回归 | ✅ | 后端 181 个用例 0 失败（M1c 的 162 + `InboxApiTest` 19），`ArchitectureTest` 7 条规则仍全绿 |

偏差与说明（相对上面的范围与清单）：
1. **文件名与上面"脚手架产出"不一致**：解析层按格式分读者而不是按职责分（`RssLikeReader`/`AtomReader`/`JsonFeedReader` + 共用的 `EntryFields`/`FeedFields`/`Tags`），`Sniffer`/`GuidResolver`/`DateNormalizer` 没有独立类——嗅探在 `FeedParser`、ID 阶梯在 `EntryFields.id`、日期阶梯在 `FlexibleDates`。清单是形状示意，不是必须照抄的类名。
2. **编码语料没做成 `.xml` 文件**：GBK 声明 UTF-8 的用例用测试内联字节断言（`FeedParserTest` 第 264 行起），BOM/声明不符在 `DocumentDecoderTest` 覆盖；原因是这类用例要控制**字节**而不是控制文件内容，落成文件反而容易被编辑器的编码设置改掉。
3. 原计划"解析失败即留原始字节 + 标记 error"的降级路径已具备：`feedfetch` 把 `FeedParseException` 映射为 `parse_error`，原始响应先落 `raw_response`，解析在其后。
4. **M1c 的对齐单测抓出两个只在 PostgreSQL 上才会爆的缺陷**（本机没有 Docker/PG，H2 跑不出来，所以才把列名对齐做成单测）：
   - 两处唯一约束在 PG 脚本里写成匿名 `UNIQUE (...)`，而 H2 侧是具名的（[h2:81](../../backend/src/main/resources/db/migration/h2/V1__baseline_schema.sql#L81)、[h2:109](../../backend/src/main/resources/db/migration/h2/V1__baseline_schema.sql#L109)），改名/删列时两边行为不同 → PG 侧补成 `CONSTRAINT uq_task_kind_dedupe`（[V1__baseline_schema.sql:78](../../backend/src/main/resources/db/migration/postgresql/V1__baseline_schema.sql#L78)）与 `uq_entry_feed_guid`（[:108](../../backend/src/main/resources/db/migration/postgresql/V1__baseline_schema.sql#L108)）。
   - `entry_revision.raw` 原本声明为 `JSONB`，但实现里这一列放的是"该修订的正文 HTML"（03 §5.4 的修订快照），第一篇被订正的稿子就会让插入直接失败 → 改为 `TEXT`（[:122](../../backend/src/main/resources/db/migration/postgresql/V1__baseline_schema.sql#L122)），与 H2 侧的 `raw CLOB`（[h2/V1__baseline_schema.sql:120](../../backend/src/main/resources/db/migration/h2/V1__baseline_schema.sql#L120)）同为大字块，不再是一列结构化的 JSON。
   - 唯一允许的列名差异是 `entry.search_ts`（PG 全文检索列，H2 没有对应类型），在 `MigrationDialectAlignmentTest` 里显式登记；测试同时断言"这份差异清单非空"，避免将来有人删掉 PG 特性后测试静默变成假绿。
5. **M1d 没引 Element Plus**（原计划 UI 用组件库）：本机 `web/node_modules` 里没有它，装上是为了几个表格和按钮引入一整套运行时，而 M1 的界面是"三栏收件箱"，代价是四个视图里 421 行手写 CSS（`awk` 数 `views/{InboxView,FeedsView,HealthView}.vue` + `components/ReaderPane.vue` 的 `<style scoped>` 块）。规则：真出现日期选择器、下拉多选这类交互再引，且只在用到的那一页引。
6. **M1d 的 UI 未在浏览器复核**：按既定口径不做浏览器自动化登录。替代证据是三路核对——`vue-tsc --noEmit` 0 错误、`vite build` 产物生成、以及用 curl 带已登录会话实打实调用全部 M1d 接口（`/api/feeds` 201、`/api/entries?state=all&limit=2` 的 `nextCursor` 翻到第二页 id 6-10、`/api/entries/state` 返回 `{"applied":2}`、`/api/feeds/1/read` 返回 `{"marked":19}`、`/api/opml` 导出含分组 `news`）。**所以"页面好不好用"仍是未验证项**，M1 末的"真实使用一周"门禁必须人工做。
7. **真实链路验证**（不是 mock）：订阅 `https://hnrss.org/frontpage` → 解析出 `feedUrl` → 入队 → 10s 内被调度器抓完，`entry_count=20`、`format=rss2`、`avg_fetch_ms=586`、`unread_count=0`。未读为 0 正是 FR-2.3"首次订阅只建基线"的表现，见 [EntryFinder.java:303-305](../../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L303)。
8. **两处 JDK/Spring 实测口径**（写代码时踩到，记下来免得再踩）：
   - Spring Framework 7.0.9 的 `JdbcClient` 没有 `singleValue()`（在 `ResultQuerySpec` 上）也没有 `updateAndReturnGeneratedKey()`；用 `query(...).single()` 与 `update(KeyHolder, "id")`。
   - H2 的 `getGeneratedKeys()` 会把所有带默认值的列都返回（`id, public_id, created_at, updated_at`），`GeneratedKeyHolder.getKey()` 遇到多列直接抛异常，所以插入必须点名身份列——见 [FeedRepository.java:143-150](../../backend/src/main/java/com/rsshub/feedstore/FeedRepository.java#L143) 与 [RawResponseStore.java:62](../../backend/src/main/java/com/rsshub/feedfetch/RawResponseStore.java#L62)。

**风险与降范围杠杆**
| 风险 | 早期信号 | 应对 |
|---|---|---|
| 脏 feed 解析消耗远超预期 | fixtures 通过率 < 90% | 允许"解析失败即留原始字节 + 标记 error"，不追求全兼容；失败率 > 1% 才引 Rome 对比（03 §14 O5） |
| 对端封禁 | 4xx 比例升高 | UA 带联系地址、per-host 串行、关键源双 URL |
| 收件箱 UX 返工 | 自己不想用 | M1 末强制"真实使用一周"，用不动就改，别急着上 M2 |

---

### M2 · 发布侧三格式 + 站内文章（9 人日） ✅ 已完成（2026-10-18）

**交付状态**
- **M2 范围内唯一没交付的是 FR-1.6 变更公告**（当时登为 BLK-02，推到"M6 或之后"）——**2026-11-01 已补账交付**，见本节末尾的 F1.6 小节与 [03 §8.7](./03-technical-design.md)。M2 当时的 246 用例与六条门禁只覆盖 FR-1.1～1.5。
- 后端：`feedrender` 三 writer + `EtagService` + `PublicFeedController`，`editorial` PostRepository/Service/Markdown/Slug/PublicView + Thymeleaf 模板；`MigrationDialectAlignmentTest` 覆盖 V2；共 246 tests 全绿，含 PublishApiTest 13 项端到端。
- 前端：`web/src/api/posts.ts`、`web/src/stores/posts.ts`、`web/src/views/PostsView.vue`（列表 + 内联编辑 + 状态切换 + 预览），路由 `/posts`，`App.vue` 顶部导航加"文章"。`vue-tsc --noEmit` + `vite build` 均通过。
- 与计划的偏差：
  1. `FeedController` 类名与既有 `adminapi.FeedController` 撞了 bean 名，公开侧改叫 `PublicFeedController`；路由与 03 §6.2 完全一致。
  2. `atomRefusesAFeedWithoutAUpdatedAt` 反转：RFC 4287 §4.1.1 要求空 feed 也必须有 `<updated>`，`AtomWriter` 在无 `lastBuildAt` 且无条目时回落到 `Instant.EPOCH`，不再抛错。RSS 侧仍按原口径缺项即省略 `<lastBuildDate>`。
  3. `JsonFeedWriter` 不再依赖 Spring 的 `non_null` Jackson 配置：改为在 writer 内跳过 null 值，让单元/集成两条测试路径共用同一契约（03 §6.3 rule 7 的降级判定也依赖这点）。
  4. `MarkdownRenderer` 从 `escapeHtml(true)` 改为允许原始 HTML 进入 Jsoup Cleaner：`<details>` / `<video>` 是 03 §5.5 里"文章需要但 feed 消费者不需要"的白名单项，转义会让它们永远显示不出现；安全边界从"parser 拒收"移到"Cleaner 白名单"，测试覆盖 `<script>` / `onclick` / `<iframe>` 全被剥离。
  5. 封面（`media_asset`）与对象存储留到 M4 一并接：M2 的 post 表已建 `cover_asset_id`，但 UI 未上传入口，非阻塞。

**范围**（FR-1.1～1.6）
- `feedrender`：`FeedSource` SPI + 三个 writer（RSS2 / Atom / JSON Feed 1.1）+ ETag/304/缓存头 + `atom:link rel=self` + `ttl`。
- `editorial`：Post CRUD、Markdown→HTML、状态机、标签/分类、封面（存对象存储）。
- 公开页 Thymeleaf SSR：文章页/列表页注入 `rel=alternate`（含分类源）（依据 [rss-guide.md:537](../rss-guide.md#L537)）。
- 路由与 URL 形态落定（[03 §6.2](./03-technical-design.md)）：`/feed.{format}`、`/tag/{slug}.{format}`、`/blog/{slug}`。
- 文章编辑界面（PostsView/PostEditor）+ 发布后 feed 60s 内可见（靠 `fingerprint` 自然失效）。
- TLS 上公网 + 备份加对象存储副本。

**本阶段不做**：播客 writer 差异（M4）、再分发（M5）、评论/SEO 优化。

**验收门禁**
- [ ] 三个出口 `curl -I` 有 `ETag`+`Last-Modified`+`Cache-Control`；带 `If-None-Match` 得 304。
- [ ] **改文章标题/正文后 GUID 不变**；`/feed.rss`、`/feed.atom`、`/feed.json` 中同一文章 ID 一致（[rss-quickref.md:133](../rss-quickref.md#L133)）。
- [ ] 把源写成残缺（缺 channel `description`）时 writer 抛错，不输出残缺 feed（必填项按 [rss-quickref.md:21](../rss-quickref.md#L21)）。
- [ ] 黄金样本逐字节快照测试 + 严格 XML 解析通过；**公网可访问后在 `https://validator.w3.org/feed/` 手动跑一次，0 error**（这条无法进 CI，写进上线检查表）。
- [ ] 分类源与标签源各能被发现；文章页 HTML 里 4 个 `rel=alternate` 齐全。
- [ ] 匿名抓取 `/feed.rss` 不创建 session（压测 200 req/s 无内存增长）。

**脚手架产出**
```
backend/.../feedrender/{FeedSource,FeedItem,FeedMeta,Rss2Writer,AtomWriter,JsonFeedWriter,FeedController,EtagService,EscapeUtil}
backend/.../editorial/{PostRepository,PostService,MarkdownRenderer,SlugPolicy,PublicViewController}
backend/src/main/resources/templates/{layout.html,post.html,list.html}
backend/src/test/.../{RenderGoldenFileTest,SpecConformanceTest,GuidStabilityTest}
web/src/views/{PostsView,PostEditor}
```

**风险与降范围杠杆**：`&amp;`/CDATA/BOM 这类低级错误只在真实订阅者那端炸 → 用"三格式黄金样本 + 规范自检单测"前置拦住（[03 §6.3](./03-technical-design.md) 的 7 条 writer 规则全部要有断言）。

---

### M3 · 规则告警 + 事件源（7 人日）✅ 开发完成（2026-10-18；两条人工门禁未执行，见验收门禁）

**交付状态（分四批，每批都跑过全量 `./mvnw -o verify`）**

- **M3-a 事件源**：`V3__events_rules_schema.sql`（双方言，`:6 / :18 / :37 / :58` 四张表 `event_stream / event_item / rule / notification_log`；`api_token` 早在 V1，本轮只改用法不改表）；`events/{EventStreamRepository,EventStreamService,EventIngestService,EventJson,EventFeedSource,EventInboxBridge}` + `adminapi/{IngestController,EventStreamController}` + 公开侧只加了一个 bean：`events/EventFeedSource`（`FeedScope.EVENTS`，`events/EventFeedSource.java:23`）挂到现成路由 `/feed/{slug}.{format}`（`feedrender/PublicFeedController.java:67`）上，默认流 slug 就是 `events` ⇒ `/feed/events.rss|atom|json`，writer/renderer/ETag 一行没改；人类可读页 `GET /event/{slug}/{publicId}`（`events/EventViewController.java:44`）。Bearer `ingest` 令牌、`dedupeKey` 幂等、无 key 回落内容哈希。
- **M3-b 安全**：`security/{ApiTokenService,BearerTokenAuthFilter,SecretCipher,TotpService,TotpController,LoginAttemptGuard,OperatorAuthenticationProvider}`。令牌只存 SHA-256、明文仅创建时返回一次；TOTP 走"先 enroll 出密钥、再 activate 才生效"两步，`RSSHUB_SECRET_KEY` 缺失时 enroll 直接 400；登录顺序为**锁定检查 → 密码 → TOTP**，5 次失败锁 15 分钟，锁定期返回 **429 `account_locked` + `Retry-After`**。
- **M3-c 规则与推送**：`rules/{RuleRepository,RuleMatcher,MatchSpec,Suppression,QuietHours,Action,AlertPayload,RuleJson,RuleEvaluator,RuleService,NotificationRepository}` + `notify/{NotifyChannel,WebhookChannel,DingtalkChannel,WecomChannel,MarkOnlyChannel,Signer,VendorVerdict,ChatFormat,OutboundPoster,DeliveryService,DeliverDispatcher,NotifyPolicy,Result,JsonBody}` + `adminapi/{RulesController,NotificationsController}`。命中时**只落决策快照并入队**（决策阶段不开 socket），重试阶梯 1/5/15/60 分钟，第 5 次失败即写 `disabled_reason` 熔断。
- **M3-d 前端**：`web/src/api/{rules,notifications,events,security}.ts`、`stores/rules.ts`、`components/RuleEditor.vue`、`views/{RulesView,NotificationsView,EventsView,SecurityView}.vue`；改 `LoginView`（动态口令框 + 锁定文案）、`stores/session.ts`（`totpEnabled`）、`api/{auth,entries,health}.ts`、`InboxView`（命中规则红标）、`HealthView`（熔断规则入口）、`router/index.ts` 与 `App.vue` 导航。`npm run build` → `vue-tsc --noEmit` 无错，`vite build` 产出 160.06 kB JS / 19.44 kB CSS（76 modules）。
- 本轮全量：**`Tests run: 343, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS**（M2 末是 246）。新增 M3 用例主要在 `RuleDeliveryTest` 16、`RulesApiTest` 7、`EventsApiTest` 8、`RuleMatcherTest` 17、`TotpLoginGateTest` 7、`TotpServiceTest` 11、`SecretCipherTest` 8、`ChatFormatTest` 4。

**与计划的偏差（每条同步进 [03 §8.3 / §9.1 / §14 O15–O17](./03-technical-design.md)）**

1. **没有邮件通道**：`notify` 只有 webhook / 钉钉 / 企微 / `markOnly`（`rules/Action.java:23`），计划里的 `MailChannel` 未做——自用场景下三个 IM 通道 + 站内标记已覆盖，且引入 SMTP 会拉进一整层凭据管理。记为 O15。
2. **熔断的"保存即重新启用"靠前端自律**：后端 `PATCH /api/rules/{id}` 仍会照 `enabled` 写库（`adminapi/RulesController.java:54-58` → `rules/RuleService.java:86-88`），是前端编辑器在编辑态**永不发送 `enabled`**（`web/src/api/rules.ts` 的注释 + `RuleEditor.vue` 的 `if (!isEdit.value)`）来保证"重新启用只能走 `PATCH /{id}/enabled` 这条独立决策"（`RulesController.java:60-67`，它同时清 `disabled_reason`）。记为 O16。
3. **邮件正文长度改成按字节截断**：`ChatFormat` 的 `CONTENT_MAX_CHARS = 2000` 换成 `CONTENT_MAX_BYTES = 2000`，且按码点安全切割——中文约 3 字节/字，按字符截 2000 实际能发出 ~7200 字节被厂商拒收。厂商口径已联网核对并写进 javadoc（钉钉/企微各 20 条/分钟、钉钉限流 10 分钟、加签误差 ≤1 小时），WeCom `text/markdown` 的官方字节上限页面抓取失败，降级为经验值并标注。
4. **` quietHours` 需要第三种状态**：编辑器要能"关掉静默时段"，而后端原本"字段缺失=保留原值、`{}`=400"表达不了清空。加了 `RuleService.quietHours(node, stored)`：空对象 ⇒ 写 null，并在 `RulesApiTest` 补了清空 + 残缺对象仍 400 两条断言。
5. **厂商限流与时钟偏差无法本机验证**：只有签名（`timestamp+"\n"+secret` → HmacSHA256 → Base64 → urlencode）与 `200 + errcode != 0` 判失败进了单测；20 条/分钟、限流 10 分钟、±1 小时误差只是文档口径，没有对应测试。O4 按实情改写，不再声称已覆盖。

**范围**（FR-5.1～5.4、FR-3.1～3.4）
- `rules`：匹配引擎（关键词/正则/标签/源/时间窗）、抑制窗口、静默时段、自动停用、试跑预览。
- `notify`：`NotifyChannel` 接口 + 钉钉/企微/邮件/通用 webhook（HMAC 签名头）+ 投递日志 + 重试任务。
- `events`：`POST /api/ingest/events`（Bearer `ingest` 令牌、`dedupeKey` 幂等）+ 事件流可见性开关 + `/feed/events.{format}`。
- 收件箱"关键词命中"标记与规则统计视图；`RuleEditor`（含试跑）。

**本阶段不做**：外部 HTTP 出站通道以外的集成（如日历、IM 内机器人交互回调）。

**验收门禁（2026-10-18 执行结果）**
- [x] 同源重复命中只推 1 次；静默时段不发 —— `RuleDeliveryTest`：`the second hit inside the suppression window leaves a record and sends nothing`（`perFeed` 6h 窗口，第 2 次命中写 `suppressed` 行、零外发；第 3 次复用同一窗口逻辑，**未单独加断言**）、`quiet hours keep the inbox mark and hold the send back`（保留收件箱标记、状态 `skipped_quiet`）。
- [x] 试跑用最近 200 条历史给出命中列表且零外发 —— `RulesApiTest`：`FR-5.4: the test button previews the last entries and records or sends nothing`；上限由 `RuleService.PREVIEW_MAX = 200` 钳制。
- [x] 投递失败 5 次自动停用规则并在 HealthView 红色提示 —— `RuleDeliveryTest`：`the whole ladder runs at 1/5/15/60 minutes, then the row fails and the breaker stops the rule`、`the health page counts the rules the breaker stopped, and only those`；`HealthController` 的 `rulesDisabled` + `HealthView.vue` 的红链。**前端红链未经浏览器复核**（O17）。
- [x] `POST /api/ingest/events` 同 `dedupeKey` 二次调用为更新、无 key 时按内容哈希兜底 —— `EventsApiTest`：`FR-3.2: the same key updates in place instead of inserting a second copy`、`FR-3.1/3.2: no key means the content hash is the key, and the default stream mints itself`。
- [ ] `/feed/events.rss` 能被外部阅读器订阅（真机用另一个阅读器验一次）—— **未执行**：服务端三出口由 `FR-3.4: one stream, three outlets, and the validators a poller expects` 证明，但"另开一个真阅读器"是人工动作，本轮按约定没有替它盖章。
- [ ] **MVP 自测周** —— **未执行**：需要连续一周真实使用，属于用户侧动作。

**脚手架产出（实际文件，与计划名的差异见下）**
```
backend/.../rules/{RuleRepository,RuleMatcher,MatchSpec,Suppression,QuietHours,Action,AlertPayload,RuleJson,RuleEvaluator,RuleService,NotificationRepository}
backend/.../notify/{NotifyChannel,WebhookChannel,DingtalkChannel,WecomChannel,MarkOnlyChannel,Signer,VendorVerdict,ChatFormat,OutboundPoster,DeliveryService,DeliverDispatcher,NotifyPolicy,Result,JsonBody}
backend/.../events/{EventStreamRepository,EventStreamService,EventIngestService,EventJson,EventFeedSource,EventInboxBridge,EventViewController}
backend/.../security/{ApiTokenService,BearerTokenAuthFilter,SecretCipher,TotpService,TotpController,LoginAttemptGuard,OperatorAuthenticationProvider}
backend/.../adminapi/{RulesController,NotificationsController,IngestController,EventStreamController,TokenController}
web/src/api/{rules,notifications,events,security}.ts  web/src/stores/rules.ts
web/src/components/RuleEditor.vue  web/src/views/{RulesView,NotificationsView,EventsView,SecurityView}.vue
```
计划里的 `RuleDryRunService`/`DeliveryTaskHandler`/`MailChannel` 不是独立类：试跑在 `RuleService` 里（`PREVIEW_MAX`），投递任务是 `scheduled_task` 的 `deliver` kind，由 `DeliverDispatcher` 认领；邮件通道未做（偏差 1）。

---

### M3.5 · 运营增强：设置 + 系统状态 + 大屏（2 人日，2026-10-18 用户追加并当日完成）

**范围**（FR-8.1、FR-9.1/9.2，见 [01 §4 F8](./01-requirements.md)）

- `settings` 基础设施与四个键：大屏每源条数（1–50，缺省 20）、大屏刷新间隔（0–600 秒，0=仅手动）、收件箱每页（10–200）、投递日志每页（10–500）。**收件箱与投递列表的缺省 limit 直接取自设置**——设置页不可能"改了但没生效"。
- `GET /api/system/status`：系统/JVM/数据库/服务四组状态，全部来自 JDK MXBean 与 SQL 计数，不触碰 actuator 敏感面。
- `GET /api/dashboard`：每源一张卡片 + 最新 N 条，单语句窗口函数，未读角标与收件箱口径一致。
- 前端：`DashboardView`（卡片墙 + 定时刷新）、`SettingsView`（状态四面板 + 参数表单），路由 `/dashboard`、`/settings`。

**交付状态（设计与口径详见 [03 §8.5](./03-technical-design.md)）**

- 后端：`settings/{SettingsRepository,SettingsService}` + `adminapi/{SettingsController,DashboardController,SystemStatusController}`；`feedstore/EntryFinder` 增 `latestPerFeed/unreadByFeed/lastFetchedAt`、`FeedRepository` 增 `listCards/count`；`feedfetch/TaskQueue` 与 `rules/NotificationRepository` 增按状态计数、`rules/RuleRepository` 增 `count`；`adminapi/{EntryController,NotificationsController}` 的缺省 limit 改由设置驱动（显式 `limit` 仍可覆盖）。
- 前端：`web/src/api/{settings,dashboard,system}.ts` + `web/src/views/{DashboardView,SettingsView}.vue` + `router/index.ts` 与 `App.vue` 导航。`npm run build` 通过，产出 168.46 kB JS / 23.08 kB CSS（85 modules，M3 末为 160.06 kB / 76 modules）。
- 全量：**`Tests run: 360, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS**（M3 末 343）。新增 `SettingsApiTest` 9、`DashboardApiTest` 5、`SystemStatusApiTest` 3。

**验收门禁（2026-10-18 执行结果）**

- [x] 参数校验三态：未知键 `unknown_setting`、非整数 `bad_setting`、越界 `setting_out_of_range`，全部先验后写 —— `SettingsApiTest.refusesUnknownKeys / refusesNonWholeNumbers / refusesOutOfRangeValues`（最后一条同时断言库里零行）。
- [x] 缺省 limit 双向生效：`inbox.pageSize`/`notifications.pageSize` 驱动两个列表、显式 `limit` 覆盖 —— `SettingsApiTest.steersTheInboxDefault / steersTheNotificationsDefault`。
- [x] 大屏形状：每源一卡、卡内按发布时间倒序、`entriesPerFeed` 截断、未读角标排除已读与基线、暂停源变淡不消失 —— `DashboardApiTest` 全部 5 条。
- [x] 状态口径：两布尔与 `@ConditionalOnProperty` 同源、四计数与库内行数一致、空库是 0 不是错误、DB 掉线是 `up=false` 不是 500（代码路径，未真断 DB）—— `SystemStatusApiTest` 全部 3 条 + `SystemStatusController.java:123-133`。
- [x] `[C]` 全链路冒烟：`backend/smoke.sh` 从 14 项扩到 55 项（一次性 `:18080` 实例 + 自造临时口令 + 会 304 的本机 fixture 源），2026-10-18 `ALL PASS`；发现的两处均为脚本/文档陈旧（H2 URL 少 `./` 前缀、M0 时代"匿名 `/` 应 401"断言未随 M2 公开落地页更新），**无产品缺陷** —— 实录 [test-plan §20.4](../test-plan.md)。
- [ ] 浏览器走查 `DSH`/`SET`/`SYS` 用例 —— **未执行**：按既定约束不跑浏览器登录自动化，记为 [03 §14 O19](./03-technical-design.md)。

---

### M4 · 播客托管（10 人日，可选开关）

**开工前置（三条缺一就不开工）**：① MVP 已真实使用 ≥ 4 周；② 有一档真要发的节目；③ 已选定对象存储/CDN 且知道计费口径。

**范围**（FR-6.1～6.4）：Show/Episode 管理、presigned 上传、`PodcastWriter`（RSS2 + `itunes:` 全量）、合规自检清单、音频直链、迁移支持（301 + `itunes:new-feed-url`）。
实现细节与硬性依据见 [03 §7](./03-technical-design.md) 与 [rss-guide.md:412](../rss-guide.md#L412)。

**验收门禁**
- [ ] 合规自检全绿：必填项、`explicit` 只 `true/false`、封面正方形 1400–3000 且无 alpha、`enclosure.length` 与 `headObject` 字节一致。
- [ ] 对音频 URL 发 `HEAD` + `Range: bytes=0-1` 得 **206** 且 `Accept-Ranges: bytes`；无 Referer 限制、无重定向。
- [ ] 播客 GUID 建后不可改（改 slug 也不动 GUID）。
- [ ] 播放/抓取请求不经过 JVM（看访问日志与 GC）。
- [ ] `validator.w3.org/feed/` 对节目源 0 error；**真实提交一次目录（Apple 或任一）**并跑通认领——这条只能人工，写进行程里。

**降范围方案**：若只是想发一档节目，先"音频放别处（外链）+ 本服务只出 RSS2+`itunes:` 源"，可省 4–5 人日；音频上传与 Range 服务留到确有需求。

---

### M5 · 聚合再分发（3.5 人日，2026-10-31 开发完成）

**范围**（FR-7.1/7.2）：`RepackFeedSource`（选若干已订阅源 → 主题 feed）、条目 `<source url>` 原出处、`link` 指向原文、**默认只转发摘要**，全文转发按源逐个显式开启并在 UI 提示义务（依据 [rss-guide.md:560](../rss-guide.md#L560) 的 `<source>` 约定）。

**交付状态（设计与口径详见 [03 §8.6](./03-technical-design.md)）**

- 迁移：`V4__repack_schema.sql` 双方言三份表 `repack` / `repack_source` / `repack_source_audit`，`MigrationDialectAlignmentTest` 的 `MIGRATIONS` 同步到 V1–V4。
- 后端：新包 `com.rsshub.repack`（`RepackRepository`/`RepackService`/`RepackFeedSource`/`RepackJson`）+ `adminapi/RepackController`（九个映射）+ `feedrender/PublicFeedController` 的 `/repack/{slug}.{format}` 一条映射 + `security/SecurityConfig` 的 `/repack/*` 匿名白名单。**三个 writer、渲染层、ETag 逻辑一行没改**——M2 建 SPI 时 `FeedScope.REPACK` 就在枚举里，本里程碑只补数据来源。
- 前端：`web/src/api/repacks.ts` + `web/src/views/RepackView.vue`（集合/成员/审计三张表 + 三格式出口 + 开全文前的确认框），路由 `/repacks`、导航"聚合"。`npm run build` 通过，产出 178.07 kB JS / 25.08 kB CSS（89 modules，M3.5 末为 168.46 kB / 23.08 kB / 85 modules）。
- 全量：**`Tests run: 370, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS**（M3.5 末 360），新增 `RepackApiTest` 10 条，测试类 34 → 35。

**验收门禁（2026-10-31 执行结果）**

- [x] 输出源里每条都能回溯原站：`<source url>`/`atom:source`/`external_url` + `<link>` 指向原文 + ID 沿用原 guid —— `RepackApiTest.membersShipTheirSourceAndOriginalLink`、`idsStayStableAcrossTheRepost`；真 HTTP 侧 `smoke.sh` 对 fixture 源站再证一遍（三条断言：`<source url="http://127.0.0.1:19999/feed.xml">`、`<link>http://127.0.0.1:19999/post/`、`guid isPermaLink="true"`）。
- [x] 默认策略为摘要（单测断言）：`full_content` 库层缺省 FALSE、插入语句不接收该参数、只有 `PATCH /api/repacks/{slug}/feeds/{feedId}` 能开 —— `RepackApiTest.fullContentIsPerMemberAndAudited`；`smoke.sh` 断言"没开之前 outlet 里没有 `Full body`、开了之后有"。
- [x] 全文开关有审计日志：`add`/`remove`/`full_content_on`/`full_content_off` 四值 CHECK，标志未变不写行，**审计表无外键**所以级联退订与删集合都删不掉它 —— `RepackApiTest.theTrailOutlivesTheRowsItDescribes` + `smoke.sh` 的"重复 PATCH 后 `full_content_on` 仍只有 1 行"。
- [x] `[C]` 冒烟：`backend/smoke.sh` 从 55 项扩到 **86 项**，2026-10-31 在一次性 `:18080` 实例 + fixture 源上 `ALL PASS`（全新库跑两遍可复现；脏库复跑只在"已存在"类断言上失败，符合脚本头声明的一次性前提）。fixture 源同步补 `<content:encoded>`，否则 FR-7.2 的开关在 HTTP 层不可观测。
- [ ] 浏览器走查 `RPL` 用例、真机用另一个阅读器订阅一个跨源 feed —— **未执行**：按既定约束不跑浏览器登录自动化（记为 [03 §14 O20](./03-technical-design.md)），跨源主题 feed 的"值不值"要真订阅才知道。

**范围外（诚实记录）**：`contributor`（外部投稿进来再分发）与人工审核队列没做——需求侧只承诺"已订阅源的再分发"，投稿入口留待有真实投稿者时再开。每日体检自推送（O18）原计划"M5 顺手做"，**实际未做**。

---

### F1.6 · 变更公告（0.5 人日，2026-11-01 开发完成：M2 的补账，关闭 BLK-02）

**为什么单列**：FR-1.6 本来在 M2 的范围里（"FR-1.1～1.6"），M2 交付时没做，被登成阻塞项 BLK-02 并推到"M6 或之后"。它一直排在后面是因为要动的不是渲染层而是**写侧**——公告的内容取决于"什么算一次公开的变化"，这条判据当时还没人想清楚。M5 把 SPI 的第五个插槽铺好之后，这一项的真实成本只剩一张表 + 一个 source + 一个只读端点，于是当日插入执行。设计与口径详见 [03 §8.7](./03-technical-design.md)。

**范围**（FR-1.6）：`content_change` 表 + 文章四写（新建/编辑/状态移动/删除）各记一笔 + `/feed/changelog.{rss,atom,json}` 出口 + `GET /api/changelog` + 文章页"变更历史"面板 + 公开页的 `rel=alternate` 发现。

**交付状态**

- 迁移：`V5__changelog_schema.sql` 双方言一份表 `content_change`（+ 两条 `id DESC` 索引），`MigrationDialectAlignmentTest` 的 `MIGRATIONS` 同步到 V1–V5（清单现在是 V1–V7，`src/test/java/com/rsshub/MigrationDialectAlignmentTest.java:37-39`）。
- 后端：新包 `com.rsshub.changelog`（`ChangeLogRepository` + `ChangeLogFeedSource`）+ `adminapi/ChangelogController`（一个 `@GetMapping`）+ `PublicFeedController` 的 `/feed/changelog.{format}` 一条字面量映射 + `editorial/PostService` 的四个写点各 `offer` 一笔。**三个 writer、渲染层、ETag、`SecurityConfig` 一行没改**——与 M5 相比这次连安全配置都没动（`/feed/*` 与 `/api/**` 的既有规则恰好覆盖新出口与新端点）。
- 前端：`web/src/api/changelog.ts` + `web/src/views/PostsView.vue` 的一块面板（不新增视图、不新增路由）。`npm run build`（`vue-tsc --noEmit` 严格模式）0 错误，产出 179.91 kB JS / 26.38 kB CSS（**90 modules**，M5 末为 178.07 kB / 25.08 kB / 89 modules）。
- 全量：**`Tests run: 380, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS**（M5 末 370），新增 `ChangeLogApiTest` 10 条，测试类 35 → 36。真 HTTP 冒烟 `backend/smoke.sh` 从 86 项扩到 **129 项**，2026-11-01 在一次性 `:18080` 实例 + fixture 源上 `ALL PASS`。

**验收门禁（2026-11-01 执行结果）**

- [x] 草稿不是新闻：草稿的新建/编辑/删除**零行**，草稿标题不出现在任何公开文档里 —— `ChangeLogApiTest.draftsAreWrittenSilently` + `smoke.sh` 的"从未公开的文章删除后仍 0 行"。
- [x] 四类动作各成一条且带 `<category>`：`created`/`updated`/`status`/`deleted`；**发布一篇草稿记的是 `status`，只有生来公开的文章才记 `created`**（`smoke.sh` 两条都断言，第一次跑出 5 条 FAIL 时改的是脚本而不是产品——见下"偏差"第 2 条）。
- [x] 条目 ID 是变更自己的 URN：`urn:rsshub:change:{public_id}`，`<guid isPermaLink="false">`；同一文章的多条变更互不覆盖，改名不改写既有公告的 ID 与 URL —— `ChangeLogApiTest.aChangeRowHasAStableIdOfItsOwn`。
- [x] 公告活得比文章久：`content_change.post_id` **有意不加外键**，`DELETE /api/posts/{id}` 之后 `Deleted:` 那条仍在出口里、链接仍指向它曾经的地址 —— `ChangeLogApiTest.unpublishingAndDeletingBothAnnounceThemselves` + `smoke.sh` 的"删文后 5 行仍在"。
- [x] 静默的两类写：内容未变的重复 `PUT` 与被状态机拒绝的流转（409）都不追加行 —— `ChangeLogApiTest.noiseAndRefusalsLeaveNoRows` + `smoke.sh` 两条计数断言。
- [x] 管理面只读可筛：`GET /api/changelog?postId&limit`（缺省 50、夹到 1–500）、`POST` 405、匿名 401、出口不建会话、ETag 与站点源**互不相同**、`If-None-Match` 得 304、`.opml` 404、`item-limit` 沿用 —— `ChangeLogApiTest.theAdminViewIsReadonlyAndFilterable`/`theChangelogAnswersWithRealValidators`/`theItemLimitAppliesHereToo` + `smoke.sh` 同一批断言。
- [x] 可被发现：`/blog` 的 `<link rel="alternate">` 里有公告三格式 —— `ChangeLogApiTest.publicPagesLinkToTheChangelog` + `smoke.sh` 的"页面里出现 `/feed/changelog.rss` 与 `Changelog`"。
- [x] 与既有出口不冲突：字面量映射赢过 `/feed/{slug}.{format}`，于是一个名叫 `changelog` 的事件流会被公告遮住——这条行为被钉成断言而不是留给运气 —— `ChangeLogApiTest.theChangelogShadowIsOnlyTheOnePathWide`。
- [ ] 浏览器走查 `CHG` 用例、真阅读器订阅公告源 —— **未执行**：按既定约束不跑浏览器登录自动化（记为 [03 §14 O21](./03-technical-design.md)）。

**与计划的偏差（每条同步进 [03 §6.2 / §8.7 / §14 O21–O22](./03-technical-design.md)）**

1. **URL 从 `.xml` 改成后缀式三格式**。需求原文写的是 `/feed/changelog.xml`，本仓所有出口统一 `/…{rss,atom,json}`（与 D-01 是同一个口径修正，不能只改实现不改需求）。
2. **"发布"记的是状态迁移而不是新建**。首轮冒烟按直觉断言"发布草稿 ⇒ `New post:`"，5 条 FAIL；核对 `ChangeLogApiTest` 与 `recordIfPublic` 后确认实现是对的（`draft → published` 的 `from_status` 就是 `draft`，语义上是一次状态移动），**改的是脚本**，另加一篇"生来公开"的文章专门验 `created`。这条现在写进需求与 03，不再靠直觉。
3. **定时发布到点不产出公告行**——已知取舍，理由在 `ChangeLogRepository.java:72-77`，登记为 O22。它由站内 feed 播报，公告在那篇稿子被人编辑之前沉默。
4. **公告面板不新增视图**。原以为要一个 `/changelog` 页，实际编辑器里的一块 + 公开页的 `rel=alternate` 就够用；公开的公告页（HTML 版）没做，需求也没要求。

**范围外**：公开侧的 HTML 变更记录页（只有 feed）、`content_change` 的保留上限（自用体量下没做清理任务）、以及"到点自动发布"的事件化。

---

### F2.4 · 收件箱过滤 / 键盘流 / 桌面通知（0.5 人日，2026-11-01 开发完成：M1 的补账，关闭 BLK-01）

**为什么单列**：FR-2.4 本来在 M1 的范围里，M1 交付时只做了"四个页签 + 搜索 + 分组 + 三个状态位"，剩下的"标签过滤、只看关键词命中、j/k/n/s/m、桌面通知"被登成 BLK-01 并一路推到"M6 或之后"。它一直排在后面不是因为难，而是因为**前三轮每轮都新加了一个视图**（`RUL`/`DSH`/`RPL`/`CHG`），收件箱这个已经有人在用的界面没人回头补。M3 把 `notification_log` 落库之后，"只看命中"的判据已经有地方问了，剩下的就是把它接进列表的 WHERE。设计与口径详见 [03 §10 的 `/api/entries` 行](./03-technical-design.md) 与 [test-plan 第 3.5–3.7、23 节](../test-plan.md)。

**范围**（FR-2.4）：`GET /api/entries` 的两个新参数（`alerted`、`category`）+ `V6__alert_filter_index.sql`（仅索引，双方言）+ 收件箱的"只看命中"开关与"标签：X ×"清除片 + 正文标签 chip + `useInboxKeys.ts`（j/k/n/s/m + 下一条未读）+ `useUnreadAlerts.ts`（60 秒未读轮询 + 桌面通知授权）。**不新增视图、不新增路由、不新增 store、不新增 Java 包。**

**交付状态**

- 迁移：`V6__alert_filter_index.sql` 只有一条 `CREATE INDEX notif_entry_idx ON notification_log (entry_id)`，两方言文件内容一致；`MigrationDialectAlignmentTest.MIGRATIONS` 已含它（`:37-39`，清单现为 V1–V7），对它的断言退化成"两边解析出的表集合相等"（都不建表，所以是空集相等）——列进清单的价值只在"漏改一个方言时文件缺失立刻炸"。
- 后端：`adminapi/EntryController` 加两个 `@RequestParam` 与一段三态校验（`:102-103`、`:112-122`）、`feedstore/EntryFinder.where()` 加两段条件（`:336-342` 的 EXISTS/NOT EXISTS、`:343-354` 的整元素 LIKE）。**没有新的 Java 包，也没有新的跨包 import**：`feedstore` 用 SQL 读 rules 的 `notification_log`，写入权仍在 rules 手里，所以 `ArchitectureTest` 七条与 `no_package_cycles` 原样全绿。
- 前端：新目录 `web/src/composables/`，两个文件（`useInboxKeys.ts` 键表 + `useUnreadAlerts.ts` 轮询与通知），动作全部复用既有 `stores/entries.ts`；`InboxView.vue` 加开关与标签片，`ReaderPane.vue` 头部加标签按钮（点了就是往列表放一个 `category=`，不是状态写）。`npm run build`（`vue-tsc --noEmit` 严格模式）0 错误，**92 modules / 183.39 kB JS / 26.90 kB CSS**（F1.6 末为 90 modules / 179.91 kB / 26.38 kB）。
- 全量：**`Tests run: 382, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS**（F1.6 末 380），测试类仍 **36**——本轮只往 `InboxApiTest` 加了两条（30 → 32），没建新类。真 HTTP 冒烟 `backend/smoke.sh` 从 129 项扩到 **150 项**（F2.4 段 15 条起于 `smoke.sh:289`，页大小尾段 6 条起于 `:328`），2026-11-01 在**两个独立的一次性 `:18080` 实例**（各自全新的 H2 文件、各自自造临时口令，只经环境变量进进程）上各跑一遍，都是 `150 PASS / 0 FAIL / ALL PASS`。

**验收门禁（2026-11-01 执行结果）**

- [x] "只看命中"与行尾红标是同一事实：过滤出来的第一行必带 `alertedBy`，删掉规则（日志行的 `rule_id` 置空但行仍在）之后仍然筛得出 —— `InboxApiTest.alertedFilterIsTheSameFactAsTheRowBadge` + `smoke.sh` 的"被过滤出来的行带着它承诺的红标"。
- [x] 三态而不是布尔：缺省不窄化、`true` 只回命中、`false` 只回未命中，且**命中 + 未命中 = 全量**；`alerted=maybe` 得 400 且错误类型是本项目词表里的 `urn:rsshub:bad_alerted` —— `InboxApiTest` + `smoke.sh` 三条。
- [x] 标签匹配整个 JSON 元素：`Developer` 不被 `Dev` 命中、`Dev` 也不被 `dev` 命中（存储拼写就是键）、数组里**任一**元素都算、LIKE 的 `%`/`_` 被转义所以标签叫 `50%` 或 `a_b` 也能用、空白不等于过滤条件 —— `InboxApiTest.categoryFilterMatchesWholeElements`（八行样本）+ `smoke.sh` 五条。
- [x] 过滤能与分页、关键词、其余条件叠加，且窄化后的列表照样发游标；**页签徽标不跟随过滤**（设计如此：徽标答"有多少"，不答"屏幕上是什么"）—— `InboxApiTest` + `smoke.sh` 的四条组合断言。
- [x] 收件箱的每页条数真的由设置决定：前端不再发 `limit`，改 10 ⇒ 无 `limit` 的请求只回 10 条并交出游标，改回 50 ⇒ 行数回到改前 —— `SettingsApiTest.steersTheInboxDefault`（12 行种子数据，MockMvc）+ `smoke.sh` 尾段（真 HTTP 双向）。
- [ ] j/k/n/s/m 的实际手感、输入框聚焦时是否让路、通知权限弹窗被拒后的降级文案与桌面横幅观感、正文标签 chip 的可见性 —— **未执行**：这四件事只有浏览器能验，按既定约束不跑浏览器登录自动化（登记为 [03 §14 O23](./03-technical-design.md)）。键盘与通知**没有** `[自]`/`[C]` 之外的机器证据链。

**与计划的偏差（每条同步进 [01 FR-2.4 / FR-9.2、03 §8.5 / §10 / §11 / §14 O23](./03-technical-design.md)）**

1. **发现并修掉一处 FR-9.2 的旧欠账**：`stores/entries.ts` 此前在自己的请求里硬发 `limit=50`，于是"收件箱每页条数"这个设置项在真实读取路径上是摆设——需求写的"设置页不可能改了但没生效"当场不成立。本轮改成**不发 `limit`**，缺省交给 `inbox.pageSize`，并补了真 HTTP 的双向断言。这条不是测试计划里原有的用例，是写文档时对着代码回读捞出来的。
2. **`q` 不是全文检索**。契约里写了"FTS"，实现是 `lower(...) LIKE ? ESCAPE '\'` 的 contains-match（`EntryFinder.java:324-335` 的注释直接说明 FTS 列属 M6）。需求与 03 §10 都按实况改写，M6 落地时再翻回来。
3. **冒烟脚本自己错过一次**：`grep -q '"categories":["SmokeTag","ops"]'` 把 JSON 数组的方括号当成字符集，永远匹配不上，看起来像"后端没下发标签"。加了一个固定串版 `hasf()`（`grep -qF`，`smoke.sh:313`），**产品代码一行没改**——这条口径同时写进了 test-plan 附录 A 的提醒。
4. **虚拟列表仍没做**。草案把它当收件箱的实现手段写了很久，FR-2.4 的验收里并没有它；一页就是设置项那么多条，翻页靠"加载更多"。记为"未兑现的手段"，不要记成需求欠账。

**范围外**：AI 摘要抽屉（M6）、全文检索列与 `pgvector`（M6）、通知的第二通道去重（`notification_log` 的 `dedupe_key` 已有，桌面横幅没用它做多端互斥）、以及"命中即弹"的实时推送（自用不值，仍是 60 秒轮询）。

---

### F10 / F11 / F12 · 订阅就地编辑 + 大屏充实 + 界面令牌（1 人日，2026-11-02 开发完成：M1/M3.5 的补账，关闭 D-04 与 F-05）

**为什么三个一起排**：2026-11-02 用户一次提了三件事——"丰富大屏""订阅管理要能改标题/间隔、URL 不许改、立即抓取要真跑""UI 现代化，参考 Win11"。它们分属 M1（订阅）、M3.5（大屏）和一层此前**根本没有的抽象**（设计令牌），但共同点是"已在用的界面缺了操作员那一半"，所以按同一轮收口，共用一次全量回归。

**F10 · 订阅管理（FR-2.1/2.2）**

- 迁移：`V7__feed_editorial_controls.sql` 两条 `ALTER TABLE feed ADD COLUMN`——`custom_title VARCHAR(512)`（`:14`）与 `fetch_interval_minutes INTEGER`（`:15`），两方言各一份、内容对齐；`MigrationDialectAlignmentTest` 为此从 4 条涨到 **5 条**，新增的 `addedColumnsMatchBetweenTheTwoDialects`（`:111-118`）专抓"ADD COLUMN 而 CREATE TABLE 比对看不见"这一类漏改（V6/V7 在原表/列循环下都是空集，等于免检）。
- 后端：`adminapi/SubscriptionService` 是这轮的实体。**可改白名单**只有四个键（`EDITABLE_FIELDS`，`:51`），键外的 PATCH 一律 400 `urn:rsshub:unknown_field` 并点名被拒的键（`:125-130`）——不"忽略未知键"是刻意的：200 + 什么都没改，操作员会以为地址搬家成功了。间隔四态（`:139-142`）：负数与 >43200 被拒、43200 是天花板、**`0` 表示"交还站点"**而不是"设成 0"。`refresh()`（`:188-194`）改为"把 `fire_at` 推到 now 并唤醒调度器"，`armNow()`（`:202-211`）在 `TransactionSynchronization.afterCommit` 里调 `wakeup.wake()`，所以 202 之后是**本轮**抓，不是等下一个 15 秒轮询。新增 `FetchNowServiceTest`（2 条）与 `FetchWakeupTest`（2 条，新类）把它钉住。
- 数据归属（F-05 的收口）：`ttl_minutes` 归抓取器（`FeedRepository.recordMetadata()` 的 `coalesce(?, ttl_minutes)`，`:258`），`fetch_interval_minutes` 归操作员（`update(...)`，`:181-203`），操作员侧写空用 `nullif(?, 0)`（`:199`）——两列分开之后，"清空回跟随站点"才第一次有地方可写。标题同理分 `custom_title` / `title` 两列，收件箱与健康度读同一个显示名（`DISPLAY_TITLE`，`:44-46`）。
- 前端：`FeedsView.vue` 的四处按钮级行为——点"解析"**先清空上一次的候选**（`resetDiscovery`，`:52-61` + `watch(url, …)`，`:64`），成功后候选行尾有"直接添加"（`:342`，显式传参，不依赖选中的 radio），行内标题/间隔输入框失焦即 `PATCH`（`:118-134`），地址是只读链接（`:467-469`）。间隔输入框的占位来自 `GET /api/feeds/policy`（`stores/feeds.ts:45-52`），不再写死 15 —— 这就是 **D-04 关闭**的位置。

**F11 · 大屏（FR-8.1）**

一次 `GET /api/dashboard` 喂满整屏（`DashboardController.java:116-139`）：24 小时直方图（`WINDOW_HOURS=24`，`:40`，稀疏小时补零而不是缺柱）、十个标量（`StatsView`，`:81-83`）、分组轨、卡片墙（每卡最新 N 条 + `failCount`/`lastError`/`nextFetchAt`）、告警轨、发布轨、变更轨。**边界规则 4 原样成立**：`adminapi` 只做编排与折叠，所有聚合 SQL 仍在归属包里（`EntryFinder.latestPerFeed/arrivedSince/arrivalsByHour`、`NotificationRepository.countsSince/hitsByHour`、`FeedRepository.listCards`），`DashboardController` 里没有一条 SQL。`DashboardApiTest` 从 5 条涨到 **11 条**，`smoke.sh` 的 F11 段（`:436-487`）22 条全部是**跨页交叉核对**：柱高之和 = "今日到达"标量、条目总数 = 收件箱 `counters`、命中数 = 投递日志行数、`failCount` 个数 = 订阅数。

**F12 · 界面令牌（01 的 NFR「界面」行）**

新增 `web/src/styles/fluent.css`（`main.ts:5` 全局引入一次）：`--mica`、`--accent`、`--control-height: 32px`、`--radius-control/card: 4px/8px`、`--ease: cubic-bezier(0.1,0.9,0.2,1)`、`--shadow-card/flyout`、`--font-ui`（含 "Microsoft YaHei UI"）与 `--font-mono`。**全站十六进制色值只允许出现在这个文件里**（`grep -rniE "#[0-9a-f]{3,8}\b" --include=*.vue web/src` 零命中，TC-UIF-02）。hover 是一条条补的，不是一条通用规则：通用 `button:hover:not(:disabled)` 的权重 (0,2,1) 会压过 `.primary` 的基态，登录按钮一悬停就变白——所以删掉通用悬停，各控件自己写 `:hover:not(:disabled)`。焦点环用 `:where(...)`（`fluent.css:96-98`）所以不抢权重。

> **本段是 F12 当时的事实，次日即被下一节的 F13 推翻了一半**：`fluent.css` 已删除、令牌全并入 `theme.css`（色值仍只在一个文件里，TC-UIF-02 的 grep 照样零命中），`--control-height: 32px` 与"逐页自写 hover"两样没了（Bootstrap 自带悬停），焦点环现在在 `theme.css:191-194`。保留原文是因为"权重 (0,2,1) 压过基态"这条教训与框架无关。

**出口数字（第八轮，2026-11-02 实测）**

| 项 | F2.4 末 | 本轮末 |
|---|---|---|
| `./mvnw -o -B -ntp verify` | 382 项 / 36 类 | **397 项 / 0 失败 / 38 类**，ArchUnit 7 条全绿 |
| `backend/smoke.sh` | 150 项 | **193 项 / 0 FAIL / ALL PASS**（F10 段 21 + F11 段 22，`.dev-logs/f11-smoke.log`） |
| `cd web && npm run build` | 92 modules / 183.39 kB JS / 26.90 kB CSS | **93 modules / 190.20 kB JS / 42.40 kB CSS**，`vue-tsc --noEmit` 0 错误 |
| Flyway | V1–V6 | **V1–V7**（h2 与 postgresql 双方言对齐） |
| 用例册 | 390 条 / 228 条 P0 | **423 条 / 255 条 P0**（新增 13 `SUB` + 10 `DSH` + 10 `UIF`） |

**验收门禁（2026-11-02 执行结果）**

- [x] 白名单外的键被拒而不是被忽略，且地址一格未动 —— `InboxApiTest#aFeedCanBeRenamedButNeverRepointed` + `smoke.sh:390-398`。
- [x] 操作员覆盖活得过下一份文档：`<ttl>60</ttl>` 的源设成 15 分钟后，抓取重录 metadata 仍是 15，且重新武装落在 15 分钟 + 抖动窗口内 —— `smoke.sh:411-418`。
- [x] `0` / 空串是"交还"而不是"设值"：响应里对应键整个缺席，下一轮回到站点声明的一小时 —— `smoke.sh:420-432`。
- [x] 立即抓取是本轮：202 → 轮询到条目变化 → 重新武装按覆盖值 —— `FetchNowServiceTest` + `smoke.sh:416-418`。
- [x] 屏上每个数字都对回它声称汇总的那个页面（不是对回抄进脚本的数字）—— `DashboardApiTest` 11 条 + F11 段 22 条。
- [x] 界面承诺可机器验收的那一条：色值不在组件里 —— TC-UIF-02 的 grep 零命中。
- [ ] **未在浏览器复核**：F10 的就地编辑手感、F11 的排版观感、F12 的全部视觉效果（mica 模糊、页签选中态、hover 过渡、焦点环）。桌面端截图工具本轮返回 `NATIVE_BROWSER_VIEWPORT_UNAVAILABLE`，`/login` 匿名页只有 `getComputedStyle` 的结构回读，登录后一个像素都没被人眼看过 —— 登记 [03 §14 O24](./03-technical-design.md)，与 test-plan 24.4 同口径。

**与计划的偏差（每条同步进 [01 FR-2.1/2.2/8.1 + NFR 界面行 + DoD、02 §3 与边界规则 4、03 §4.1/§4.2/§8.5/§10/§11/§13/§14、test-plan 第 5/20.1/24 节与附录 A](./03-technical-design.md)）**

1. **03 §4.1 的参数表原样抄错了一次**：文档写 `60s/5m` 这类键名，实际 `application.yml` 是 `interval: 60m` / `min-interval: 5m`。本轮按文件实况改写，并把"页面占位读 `/api/feeds/policy`"写成硬要求（写死数字正是 D-04 的病根）。
2. **默认一小时一轮是**配置缺省**，不是新写死的常量**：用户要求"默认一小时一轮，支持手动设置"。`fetch.interval: 60m` 本来就是一小时，缺的是"手动设置"这一面——所以 F10 补的是每源覆盖列 + 策略端点，而不是改全局缺省值。
3. **冒烟脚本里中文标题必须走文件**：Windows 原生 `curl.exe` 的 argv 按控制台代码页递交，`-d '{"title":"我的源名"}'` 到服务端已不是 UTF-8 字节，看起来像"后端把中文弄坏了"。`patch_feed()`（`smoke.sh:360-366`）改用 `--data-binary @file`；纯 ASCII 的 body 仍走 `-d`。这条坑同时写进 test-plan 附录 A 第 15 段。
4. **F12 首轮把选择器写成了 `.foot button :hover`**（多一个空格 = 后代选择器，规则永不命中），所以"hover 没反应"看起来像 CSS 没生效。逐页补齐后 `grep ' :hover'` 归零复查。
5. **一次 jar 占用**：`./mvnw verify` 报 `Unable to rename …jar to …jar.original`，因为上一轮的一次性冒烟实例还活着占住文件，而 `bash start-dev.sh stop` 只遍历 8080/5173，管不到 `:18080`（`start-dev.sh:22-31`）。手工 `netstat` + `taskkill` 后重跑，登记为 03 §14 O25。

**范围外**：分组重命名/删除的独立管理面（仍是自由文本，改一次存一次）、多用户协作（架构单用户不变）、深色模式（令牌已具备条件，但没有需求来源）、以及把抓取节奏做成全局设置（03 §8.5 的"刻意不设"仍然成立——每源覆盖已经够，全局节奏一旦能从网页改就要处理正在轮询的队列）。

---

### F13 · 控制台迁到 Bootstrap 5.3（0.5 人日，2026-11-02 开发完成：F12 那层抽象的再补账）

**触发**：F12 收口后用户的原话是"UI 太难看了，请使用 bootstrap 重新优化UI"。回头看，F12 做对的是**令牌层**（一个文件、零硬编码色值、可机器验收），做错的是**组件层**——一百多个控件的间距、表格、表单、徽标、分页全是手写，于是每页各说各话，"令牌统一"救不了"结构不统一"。所以 F13 不是换配色，是**把组件层交给框架**。

**取向（用户在两个方案里选的）**：保留本项目自己的强调色。Bootstrap 只做骨架，品牌层用它的变量入口覆盖（`--bs-primary`、`--bs-btn-bg`、`--bs-link-color`…），**不用 Sass 重编译**，也不引它的 JavaScript。

**做了什么**（15 个 `.vue` 全量 + 入口 + 一个品牌层文件）：

- 依赖：`bootstrap@5.3.8` + `bootstrap-icons@1.13.2` 进 `dependencies`（CSS 也要打进产物，所以不是 devDependency）。入口只有三行：`web/src/main.ts:5-7` 依次引 Bootstrap 的编译 CSS、图标字体表、`web/src/styles/theme.css`——顺序即层叠顺序。
- 品牌桥接：`theme.css:44-69` 把 Bootstrap 的旋钮指向品牌令牌（含 `--bs-primary-rgb`，`:60`），`:96-100` 补三条 primary 的 subtle/emphasis 变体，`:102-116` 的 `.btn-primary` 块逐键覆盖按钮态，`:118-187` 同法处理 outline 按钮、alert、badge、progress、pagination、nav-pills、list-group active、form-check 与表单 focus 圈。**没有 Bootstrap JS**：`grep -rn "bootstrap/js\|new bootstrap\|data-bs-toggle" web/src --include=*.vue --include=*.ts` 零命中（全 `src` 下唯一命中是 `theme.css:14` 那句"不依赖 Popper"的注释），折叠/下拉/toast 是 Vue 状态 + 类名（导航折叠见 `web/src/App.vue:19-22`）。
- 逐页迁移：外壳与登录（`App.vue` 150→77 行、`LoginView.vue` 161→90）、收件箱三栏（`InboxView.vue` 377→221，`ReaderPane.vue` 221→165；键盘流几乎原样带过——`useInboxKeys.ts` 46→48 行，动的只有一处选择器，原来靠 `.row.picked` 把当前行滚进视野，而 `row` 正是 Bootstrap 的栅格类，一引框架就撞名，于是行类统一为 `.entry-row` 并把这份耦合写成注释，`useInboxKeys.ts:40-42` + `InboxView.vue:197`）、订阅（`FeedsView.vue` 436→339）、大屏（`DashboardView.vue` 623→385）、文章（`PostsView.vue` 632→450）、其余各页同法（`EventsView` 437→331、`RuleEditor` 437→342、`RepackView` 532→427、`RulesView` 234→155、`NotificationsView` 203→156、`HealthView` 171→143、`SecurityView` 237→176、`SettingsView` 334→290）。十五个 `.vue` 合计从 5185 行降到 3747 行（-27.7%），因为结构件不再自己画。
- 顶栏改成**数据驱动**：`App.vue:29-41` 的 `tabs` 数组 11 项 + `v-for` 渲染（`:58-62`）。此前是 11 个手写 `<li>`，代价是需求文档跟着漏——`聚合` 那条路由一直没进 TC-NAV-01 的清单（本轮补）。
- 状态色改用 Bootstrap 语汇（`text-bg-success/danger/secondary/warning/info`），大屏投递轨是一张 `ALERT_TONES` 映射表（`DashboardView.vue:116-122`），KPI 是 `metricTone()`（`:129-137`）。

**出口数字（第九轮，2026-11-02 实测）**

| 项 | F12 末 | 本轮末 |
|---|---|---|
| `cd web && npm run build` | 93 modules / 190.20 kB JS / 42.40 kB CSS | **94 modules / 221.11 kB JS（gzip 73.70）/ 321.03 kB CSS（gzip 47.71）**，`vue-tsc --noEmit` 0 错误；产物里第一次出现二进制字体：`bootstrap-icons` 的 woff2 134.01 kB + woff 180.28 kB |
| `.vue` 里的硬编码色值 | 0 处（`fluent.css` 时代） | **0 处**（`grep -rniE "#[0-9a-f]{3,8}\b" --include=*.vue web/src`），色值只在 `theme.css` |
| 自写 hover 规则 | 逐页补的一族 | **只剩 2 处**：`theme.css:261-264`（顶栏页签）与 `InboxView.vue:208-210`（条目行）；`.vue` 里 `grep ":hover"` 实测 1 处命中，其余交给框架自带的 `.btn:hover` 与 `.list-group-item-action:not(.active):hover` |
| 图标 | 无图标库 | **41 个 `bi-*` 名字**（50 个图标位点）逐个对 `bootstrap-icons.css` 的 `.bi-<name>::before` 验过存在，50 个位点全部带 `aria-hidden="true"`（本轮最后一处缺失在 `InboxView.vue:118`，补完重跑构建） |
| `./mvnw -o -B -ntp verify` | 397 项 / 38 类 | **397 项 / 0 失败 / 38 类 / BUILD SUCCESS（57.0 s）** —— 本轮纯前端、后端一行未动，但数字是**重跑出来的**，不是复用上一轮的（`.dev-logs/f13-verify-final.log`；跑之前要先停住占住 jar 的 `:8080` 实例，见 [03 §14 O25](./03-technical-design.md)） |
| `backend/smoke.sh` | 193 项 | **193 项 / 0 FAIL / ALL PASS**（`.dev-logs/f13-smoke.log`，一次性 `::18080` 实例） |
| 文档引用 | — | 五份文档 **898 条 `文件#行号` 引用**全部机器回读：0 缺文件、0 越界，13 条"中文标签 vs 英文代码"的良性不匹配逐条人工确认（脚本 `.dev-logs/f13-check2.js`）；写完本节再跑为 **920 条**，13 条良性不匹配一条没变 |

**本轮改出来的缺陷（都是真缺陷，不是口径调整）**

| 发现 | 原因 | 处置 |
|---|---|---|
| 按钮/输入框圆角 8px，与需求 NFR"控件 4px / 卡片 8px"不符 | Bootstrap 用**同一个** `--bs-border-radius` 驱动控件和卡片，F13 首版把它指向 `--radius-card` | 全局旋钮回 `--radius-control`（`theme.css:66`），卡片自己声明 `--bs-card-border-radius/--bs-card-inner-border-radius`（`:203-209`）；改完用 `getComputedStyle` 复测：卡片 8px、按钮与输入框 4px |
| 大屏卡片上的 `paused`/`muted` 又变回琥珀 | F13 迁移时给非活动源选了 `text-bg-warning`，把 F12 特意定下的"暂停是选择不是故障"写丢了 | 改回中性：`text-bg-secondary` + 整卡 `opacity-75`（`DashboardView.vue:232`、`:238`），投递轨仍按结果上色 |
| 等宽字体两族并存 | `--font-mono` 只作用于 `code/pre`，Bootstrap 的 `.font-monospace` 工具类读自己的 `--bs-font-monospace` | `theme.css:47` 把后者指向 `--font-mono`，`.font-monospace`（文章编辑器四个字段）与 `code/pre` 同一族字 |
| 一处装饰图标缺 `aria-hidden`，读屏会念出字形 | 逐页迁移时漏了 `InboxView.vue:118`（其余 49 个位点都带） | 补上 `aria-hidden="true"`，再用 `grep` 复查"有 `class="bi` 而无 aria-hidden"**0 处**；41 个唯一图标名逐个对 `bootstrap-icons.css` 验过存在。改完重跑构建，JS 221.09 → **221.11 kB** |
| 需求/架构/技术/路线图/测试计划里的 `.vue`/`.ts` 行号引用集体失真 | 一次 UI 重写把每个视图都改短了（最多 `DashboardView` 623→385，-238 行），而本工作区**不是 git 仓库**，没有 diff 可查 | 从 Qoder 的 file-history 镜像里恢复 F13 之前的源码（`.dev-logs/f13-old/`），用 LCS 对齐 + 签名搜索把旧行号机械映射到新行号（`.dev-logs/f13-remap.js` → `.dev-logs/f13-fixdoc.js`），再用独立脚本逐条回读核对（`.dev-logs/f13-check2.js`：898 条引用，0 缺文件、0 越界）；勘误见 [test-plan §24.6](../test-plan.md) |

**验收门禁（2026-11-02 执行结果）**

- [x] 组件层不再自画：15 个 `.vue` 里只剩 14 个 scoped 样式块，且每一块都对应"框架给不了的东西"（列宽、滚动容器、服务端 HTML 不溢出、大屏直方图）。
- [x] 不引 Bootstrap JS 的承诺可机器判：三条 `grep` 零命中（`bootstrap/js`、`new bootstrap`、`data-bs-toggle`，限定 `--include=*.vue --include=*.ts`；全 `src` 下唯一命中是 `theme.css:14` 那句解释"为什么不依赖 Popper"的注释）。
- [x] 键盘流的**代码**不回归：`useInboxKeys.ts` 46→48 行，动的只有选择器改名（`.row.picked` → `.entry-row.picked`）与两行说明注释；j/k/n/s/m 的实际手感属 `[B]`，本轮仍未在浏览器复核（见下一条）。
- [x] 色值仍在唯一一个文件里（TC-UIF-02 的 grep 零命中，本轮实测 0 处）。
- [x] **匿名页第一次真被人眼看过**：`/login` 的截图与 `getComputedStyle` 一致（卡片 8px + `rgba(0,0,0,0.08) 0 1px 3px`、控件 4px、输入框实测高 **34px**、主按钮 `rgb(0,103,192)`、根字号 14px、`--bs-primary` = `#0067c0`、控制台无报错）。F12 那轮 `getComputedStyle` 回读的"输入与按钮高 32px"**只对 `fluent.css` 时代的产物成立**，现在的高度是 Bootstrap padding × 根字号，实测 34px。
- [ ] **登录后仍未在浏览器复核**：11 个管理界面一个像素都没被人眼看过（按既有约束不再驱动浏览器过登录流）。顶栏 mica 模糊在真实数据下的观感、长列表滚动时 `backdrop-filter` 的帧率、卡片墙密度、`navbar-expand-xxl` 折叠后的可用性全部未验 —— 登记 [03 §14 O24](./03-technical-design.md)，与 [test-plan 24.6](../test-plan.md) 同口径。

**与计划的偏差**

1. **F12 的 `fluent.css` 整个删除**，只保留 `theme.css`。需求 NFR 界面行与 DoD 的"构建产物含 `fluent.css`"随之改写（[01 的 NFR/DoD 两行](./01-requirements.md)），因为按文件名验收一个已经不存在的文件没有意义——验收线换成"色值只在 `theme.css`"这一条同样可机器判的表述。
2. **`--control-height: 32px` 这个令牌随文件一起没了**：Bootstrap 的控件高度是 padding × 根字号，不是令牌。所以本轮把 NFR 里"控件高"这类数值承诺降级为"密度由 `html { font-size: 14px }` 决定"，不再假装有一个 32px 的硬值。
3. **02 新增 A12**（Bootstrap 只取 CSS 层）与 §3 的 `web` 模块行：F12 时"前端"在架构文档里根本没有位置，这也是它被反复重做的原因之一。
4. **CSS 从 42.40 kB 涨到 321.03 kB（gzip 47.71 kB）**——这是"用框架换掉手写组件"的直接代价，自用部署在内网可接受；若日后要瘦，路径是按 Bootstrap 的 Sass 白名单只编译用到的组件，而不是回到手写。

**范围外**：深色模式（Bootstrap 5.3 的 `data-bs-theme` 已具备条件，但仍无需求来源）、RTL、Bootstrap 的 JS 组件（`collapse`/`dropdown`/`toast` 全部用 Vue 状态实现，不换实现方式）、以及把 `bootstrap-vue`/`Element Plus` 这类**组件库**引进来（那会把 UI 依赖和 Vue 主次版本绑死，见 02 A12）。

---

### F14 · 控制台改淡蓝色调（0.2 人日，2026-11-02 开发完成：F13 品牌桥接的再补账）

**触发**：F13 收口当天用户的下一句是"采用淡蓝色色调重新优化UI界面"。取向沿用 F13 那次让用户二选一时的选择——**保留 `#0067c0` 强调色**，所以淡蓝落在**中性族**（画布/表面/描边/墨色/mica/阴影）而不是强调色上；这样做的另一个理由是强调色已经背着"这是主操作"的语义，换成淡蓝会和它自己要区分的东西撞色。

**做了什么**（只动 `web/src/styles/theme.css` 与 `web/index.html` 一行 `theme-color`，15 个 `.vue` 一行未改）：

- 中性族逐令牌转蓝，每个值先过对比度计算（脚本 `.dev-logs/f14-contrast.js`、`.dev-logs/f14-contrast2.js`，算法是 WCAG 的相对亮度 + 比值）：画布 `#f3f3f3`→`#e9f1f9`、`--surface-alt` `#fafafa`→`#f3f8fd`、`--surface-hover` `#f0f0f0`→`#dfeaf5`、mica `rgba(243,243,243,.72)`→`rgba(233,241,249,.72)`、描边三档 `#e0e0e0/#ebebeb/#cfcfcf`→`#cddceb/#dce7f3/#b6cbe0`、墨色三档 `#1b1b1b/#606060/#8a8a8a`→`#16212e/#4a5e75/#546a85`、`--bs-emphasis-color` `#000000`→`#0b1a2b`、`--bs-border-color-translucent` `rgb(0 0 0/12%)`→`rgb(20 45 80/14%)`、两道阴影 `rgb(0 0 0/…)`→`rgb(15 40 70/12%)` 与 `/18%`；强调色六件套与 `--accent-wash` 只把 `#eaf3fb` 调到 `#e3eef9`（`--accent-ink` 在其上 10.89:1）。
- **改的过程中撞到 Bootstrap 品牌桥接的三条盲区**，每一条都不是"配色"问题而是"框架在**哪里**取色"的问题，逐条写进 `theme.css` 末尾的 F14 块（`:333-417`）：
  1. `--bs-body-bg` 被画布占用，而 `.card`（`--bs-card-bg`）、`.form-control`、`.dropdown-menu`（`--bs-dropdown-bg`）全从它取底色——F13 文档里"浅色画布上叠白色表面"那句**从未真的成立**：`getComputedStyle` 实测 body、`.card`、`.form-control` 三者同为 `rgb(233,241,249)`。改法是 `--bs-body-bg` 指回 `--surface`（`:51`），页面底色由 `body` 自己画 `--canvas`（`:80`）。
  2. Bootstrap 的背景与文字工具类读的是 **`*-rgb` 双生子**：`.bg-body-tertiary` 用 `rgba(var(--bs-tertiary-bg-rgb), …)`，所以 F13 设过的 `--bs-tertiary-bg` 对它无效；`.text-bg-secondary` 读 `--bs-secondary-rgb` 而不是 `--bs-secondary-bg`；`.text-body` 读 `--bs-body-color-rgb`。补 `:356-367` 六个键。反例也记一笔：`.text-body-secondary`（94 个位点，控制台最多的文字类）读的偏偏是**非** -rgb 的 `--bs-secondary-color`，F13 那次指对了——差一个后缀的两条规则，人眼扫 CSS 扫不出来。
  3. **五条规则把灰阶写成字面量**，任何变量都碰不到：`.btn-outline-secondary`（18 个位点，本控制台最常用的按钮）、`.btn-secondary`、`.btn-outline-dark`、disabled 态的 `.btn-link`、`.form-floating` 的禁用标签。找法是 `.dev-logs/f14-grayhunt.js`：在 `bootstrap.min.css` 里搜灰阶十六进制，把命中选择器与 15 个 `.vue` 真用到的类名取交集。处置沿用 `.btn-primary` 的写法按组件逐键覆盖 `--bs-btn-*`（`:369-417`）。
- **状态色按语义保留**：`success/danger/warning/info` 与它们的 `text-bg-*`/`alert-*`/`table-*` 变体一个没动。淡蓝中性族之上放一枚琥珀或一枚绿，比把语义色也调成蓝更读得出来。
- **验收手段换了**：登录后不能进（既有约束），于是在 `/login` 的 DOM 里临时注入一段含 13 个代表类（`.btn-secondary`/`.btn-outline-secondary`/`.btn-outline-dark`/disabled `.btn-link`、`badge.text-bg-{secondary,light,dark,primary}`、`bg-body`/`bg-body-tertiary`、`text-body`/`-secondary`/`-tertiary`）的片段，读 `getComputedStyle` 再删节点——CSS 全局生效，注入元素与真页面命中同一条规则。13 个位点全部落在蓝族里，无 `#6c757d`/`#212529`/`#f8f9fa`。
- 顺带扫出一条**过度声明**：`theme.css:231-235` 的 `.dropdown-menu, .flyout` 规则没有任何位点使用（15 个 `.vue` 里 `grep -rn "dropdown\|flyout"` 零命中）。留着不亏（`--shadow-flyout` 仍经 `--bs-box-shadow` 在 `:65` 生效），但 F13 那句"下拉是 Vue 状态 + Bootstrap 的类名"里没有真的下拉，本轮按口径修正登记，不算缺陷。

**出口数字（第十轮，2026-11-02 实测）**

| 项 | F13 末 | 本轮末 |
|---|---|---|
| `cd web && npm run build` | 94 modules / 221.11 kB JS（gzip 73.70）/ 321.03 kB CSS（gzip 47.71） | **94 modules / 221.11 kB JS（gzip 73.70）/ 322.72 kB CSS（gzip 48.02）**，`vue-tsc --noEmit` 0 错误；JS 一字节没动，CSS +1.69 kB 全是品牌层末尾的 F14 块 |
| `.vue` 里的硬编码色值 | 0 处 | **0 处**（`grep -rniE "#[0-9a-f]{3,8}\b" --include=*.vue web/src`）——本轮的"换肤"没有碰任何 `.vue`，这条线第一次以"零改动"的方式通过 |
| 自写 hover 规则 / scoped 块 / Bootstrap JS | 1 处 / 14 块 / 三条 grep 零命中 | **1 处 / 14 块 / 零命中**（全部未变，本轮不需要动结构） |
| `theme.css` | 331 行 | **417 行**，新增 86 行**全部在文件末尾**，`test-plan.md` 里 18 条 `theme.css#L` 引用（17 个不同锚点，最大 `#L324`）零位移（改的过程中一度把顶部注释写成两行，`--canvas` 从 `:18` 掉到 `:19`，会集体位移那 17 个锚点——压回单行后重测锚点） |
| `./mvnw -o -B -ntp verify` | 397 项 / 38 类 / 57.0 s | **397 项 / 0 失败 / 38 类 / BUILD SUCCESS（54.7 s）**（`.dev-logs/f14-verify.log`；产品 Java 一行未动，数字仍是重跑出来的。跑之前照例要先停住占 jar 的实例，见 §14 O25） |
| `backend/smoke.sh` | 193 项 | **193 项 / 0 FAIL / ALL PASS**（`.dev-logs/f14-smoke.log`，一次性 `:18080` 实例 + 全新库 `./.dev-logs/smoke-f14`，跑法 `bash .dev-logs/smoke-run.sh f14`） |
| 观感实测 | `/login` 一屏 | `/login` 截图 + **13 个代表类的注入探针**（登录页没有徽标/按钮族，注入比截图更能说明问题）：body `rgb(233,241,249)`、`.card` 与 `.form-control` `rgb(255,255,255)`、卡片 8px / 控件 4px、控件高 34px、主按钮 `rgb(0,103,192)`、`text-bg-secondary` `rgb(90,113,136)`、`bg-body-tertiary` `rgb(243,248,253)`，控制台无报错 |

**本轮改出来的缺陷（都是真缺陷，不是口径调整）**

| 发现 | 原因 | 处置 |
|---|---|---|
| 卡片、输入框、弹层与页面同色，"白表面"从未成立 | Bootstrap 5.3.8 的 `.form-control` 没有组件级背景变量，直接读 `--bs-body-bg`；`.card`/`.dropdown-menu` 同理。F13 把画布挂在这个变量上 | `--bs-body-bg` 改为表面（`theme.css:51`），`body` 自己画画布（`:80`）；改完实测卡片/输入框回到 `rgb(255,255,255)` |
| 6 个 `bg-body-tertiary`、8 个 `text-bg-secondary`、1 个 `text-body` 位点仍是 Bootstrap 灰阶 | 工具类读 `*-rgb` 双生子变量，F13 只设了同名非 -rgb 的那半个 | `theme.css:356-367` 补 `--bs-body-color-rgb`/`--bs-secondary-bg-rgb`/`--bs-tertiary-bg-rgb`/`--bs-secondary-rgb`/`--bs-light-rgb`/`--bs-dark-rgb` |
| 控制台最常用的按钮（`.btn-outline-secondary`，18 个位点）是灰的，令牌碰不到 | Bootstrap 把 `#6c757d` 写成字面量；`.btn-secondary`/`.btn-outline-dark`/disabled `.btn-link` 同 | 按 `.btn-primary` 的既有写法逐键覆盖 `--bs-btn-*`（`:369-417`）：常态文字 `--ink-2`、描边 `--line-strong`、悬停填 slate `#5a7188`（白字 5.06:1） |
| `--ink-3` 在画布上只有 **3.11:1**（`#8a8a8a` vs `#f3f3f3`），白底上也只有 3.45:1，低于正文 AA 的 4.5 | F12 定令牌时按"最淡的一档"取值，没有算过对比度；F13 沿用 | 提到 `#546a85`：画布 4.88 / 白 5.56 / 悬停底 4.56，三档墨色（`#16212e` 14.26、`#4a5e75` 5.85）全部过 AA |
| 浏览器地址栏/状态栏还是旧灰 `#f3f3f3`，转蓝后成了唯一没跟上的色位 | `<meta name="theme-color">` 在 `web/index.html:6`，既不在 `theme.css` 的令牌块里、也不受".vue 色值归零"这条 grep 约束——F13 的验收线正好放过了它 | 改为 `#e9f1f9`（与 `--canvas` 同值），并重新 `npm run build` 让 `dist/index.html` 带上；TC-UIF-09 从此有可核口径 |

**验收门禁（2026-11-02 执行结果）**

- [x] 色值仍只在一个文件里：`.vue` 硬编码色值 0 处，且本轮**没有任何 `.vue` 改动**（`theme.css` 331→417 行）。
- [x] 换肤不依赖框架的默认值：三条桥接（表面/`*-rgb`/字面量灰）都有可复跑的找法——`grep -o '\.form-control{[^}]*}' web/node_modules/bootstrap/dist/css/bootstrap.min.css`、`.dev-logs/f14-grayhunt.js`、`.dev-logs/f14-classaudit.js`（35 个带色类名 × 位点计数）。
- [x] 对比度可机器判：中性族与 slate 的每个比值出自 `.dev-logs/f14-contrast2.js`，不是"看起来还行"。
- [x] 后端与冒烟不回退：397 项 / 38 类全绿 + 193 项 `ALL PASS`，都是本轮重跑。
- [x] 匿名页复测：`/login` 截图（`.dev-logs/f14-login-final.png`）与 `getComputedStyle` 一致，控制台无报错。
- [ ] **登录后仍未在浏览器复核**：与 F13 同一状态、同一约束（不驱动浏览器过登录流）。淡蓝族在真实数据下的密度、顶栏 mica 在蓝画布上的观感、长列表滚动帧率、11 个管理界面的卡片墙对比度全部未验——登记 [03 §14 O24](./03-technical-design.md)，与 [test-plan 24.7](../test-plan.md) 同口径。

**与计划的偏差**

1. **"改一个文件就换肤"这句需要加限定**：F13 的表述容易读成"改 `:root` 就够"。本轮实测出三类必须**按组件**写的覆盖（`.btn-*` 的键、`*-rgb` 双生子、字面量灰），文件仍然只有一个，但契约是"令牌 + 组件级桥接块"两层。已把这条限定回写 [01 NFR 界面行](./01-requirements.md) 与 [03 §11](./03-technical-design.md)。
2. **人日从 0.5 降到 0.2**：F13 已经把组件层交给框架，本轮没有结构工作，只有取值与验证；这也是 F13 那次投入的第一笔回报。
3. **CSS 体积 +1.69 kB（321.03 → 322.72，gzip 47.71 → 48.02）**：全部是品牌层末尾的注释与覆盖块，内网自用可接受。
4. **`.dropdown-menu`/`.flyout` 是一条没有位点的规则**：F13 的文档把它写成"下拉是 Vue 状态 + Bootstrap 类名"，实际本项目当前没有下拉控件。规则留着（`--shadow-flyout` 另有出口），但表述按过度声明修正。

**范围外**：深色模式（`data-bs-theme="dark"` 需要的暗色中性族没有需求来源，本轮只做了浅色）、把状态色也调成蓝（语义优先，见上）、圆角与密度（F13 的 4px/8px/14px 口径不动）、字体族、以及把 `theme.css` 拆成 tokens/overrides 两个文件（只有一个文件是 F13 定下的验收线，拆开就废了）。

---

### F15 · 覆盖面收口 + 交付文档 + 入库（0.2 人日，2026-11-02）

**用户口径**："指定完整覆盖所有功能的测试案例，并执行测试案例修复BUG，直到所有测试通过 / 更新 readme.md 文档、部署文档 / 提交所有代码"。

**这一轮的对象是用例册自己**。前十轮的惯性是"产品有没有做对"，F15 问的是另一件事：**用例册标在每条用例上的证据标记，是不是真的存在**。做法是把三张表交叉——① 控制器里真实存在的映射、② 用例编号、③ 该用例的证据标记（`[自]`/`[机]`/`[C]`/`[B]`）。凡"标记声称有证据、仓库里找不到那条证据"就记一笔缺口。

**四处缺口与它们的形状**

| 缺口 | 形状 | 为什么前面十轮没发现 |
|---|---|---|
| `GET /api/entries/counters` 只有 `[自]InboxApiTest` | 而这条路由**前端每天在打**（`web/src/stores/entries.ts:204` 的轮询） | MockMvc 里"能返回"和"真 HTTP 上能返回"被当成同一件事，标了 `[自]` 就不再问 `[C]` |
| `GET /api/rules/{id}` 全目录只有 404 负例 | 没有一条正向证据说明"编辑器打开一条已存规则"能拿回它自己 | 负例好写、看起来也覆盖了这条路由；**有负例不等于有正例** |
| TC-PUB-40/41 挂着 `[C]`，但 `/tag`、`/category` 从未被 curl 过 | 标记是**愿望**而不是事实 | 冒烟脚本每轮只追加，没人回头核对"标了 `[C]` 的每条是否真在里面" |
| `[机]` 被 6 条用例使用，§0.3 标记表里没有它的定义 | 读者无法判断这档证据强度 | 标记是逐轮长出来的，定义没跟着长 |

**还有一类既有校验器看不见的错**：12 处引用**文件对、行号也在文件范围内**，指的却是别的处理器——M5 给 `PublicFeedController` 加 `/repack` 出口时整体下移，而 `f13-check2.js` 只问"文件在不在、行号越不越界"。范围校验对"指错行"是盲的。补的门禁是 `.dev-logs/f15-routecheck.js`：把被引那行**实际声明的 URL** 还原出来（类级 `@RequestMapping` 前缀 + 附近 ±12 行的映射注解，因为引用常落在处理器的 javadoc 上），再和文档行里的路由 token 逐字比，顺带查 `[Foo.java:NN](…#LMM)` 的标签/锚点自相矛盾。本轮 54 条核对、0 条漂移。局限也写明白：窗口粗、只认控制器类、占位段先归一化——它守"说的是不是同一条路由"，不守"这条路由对不对"。

**交付文档时发现的那条约束（不是笔误，是设计后果）**：Java 侧 [PublicViewController.java:46](../../backend/src/main/java/com/rsshub/editorial/PublicViewController.java#L46) 映射了 `{"/", "/blog"}`，[SecurityConfig.java:78-79](../../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L78) 又把 `/` 放进 permitAll；SPA 用 `createWebHistory()` 且收件箱路由也是 `/`（[web/src/router/index.ts:17-19](../../web/src/router/index.ts#L17)）。**两个界面不能共用一个域名**。开发态不暴露，因为控制台在 :5173、公开站在 :8080。[deploy.md §4.1](../deploy.md) 给了两条出路（公开站与控制台分域，或给 SPA 配 `base` 前缀——后者要同时改 `vite.config.ts` 与 `createWebHistory`，本轮**没有做**，也没伪装成已经能做）。

**验收门禁（2026-11-02 执行结果）**

- [x] 后端全量：`./mvnw -o -B -ntp verify` **397 项 / 38 类 / 0 失败 / BUILD SUCCESS（01:03）**，`.dev-logs/f15-verify.log`。
- [x] 真 HTTP：`bash .dev-logs/smoke-run.sh f15` **205 条 PASS / 0 FAIL / ALL PASS**，`.dev-logs/smoke-f15` 全新库 + 运行时自造口令；开发库 `backend/data/rss-hub.mv.db` 全程未读写。
- [x] 引用完整性：963 条 `文件#L行` 引用，**0 缺失 / 0 越界**，13 条 `NO-LABEL-HIT` 逐条读过的都是"中文标签 vs 英文代码"的取名差异。
- [x] 引用语义：54 条带路由引用，**0 条漂移**。
- [x] 用例册重数：严格式 **427 条 / 257 条 P0**，编号去重后 0 条重复；附录 B 与计数说明同步。
- [ ] **登录后 11 个界面仍未在浏览器复核**：本轮补的两条路由，证据止于真 HTTP 与单元断言，没有证明"UI 按钮点了之后界面变对"——登记 [03 §14 O24](./03-technical-design.md)，与 [test-plan 24.8](../test-plan.md) 同口径。
- [ ] **deploy.md 的 Linux/systemd、PostgreSQL、nginx 三条路径本机未实测**（无 docker、无 PG 实例），文内逐处标注；外部结论按官方文档核对，见该文出处表。

**与计划的偏差**：本轮不产出产品功能，产出的是**可信度**——用例册从"看起来全覆盖"变成"每条标记都能指出证据在哪一行"。代价是 12 处文档引用改写 + 1 个新校验脚本 + 2 份交付文档；好处是下一轮再改控制器映射时，路由漂移会被门禁当场拦下，而不是等人来读。

**范围外**：给 SPA 加 `base` 前缀（需要同时改 `vite.config.ts` 的 `base`、`createWebHistory` 的参数、以及 Java 侧对静态资源的放行策略，属独立一轮）；把 `[B]` 用例真正跑一遍（需要人眼，或需要放开浏览器登录流——本轮按既定约束不做）；M4 播客、M6 AI、外部投稿审核队列。

---

### F16 · 批量订阅（0.2 人日，2026-11-03）

**用户口径**："增加批量增加订阅的功能，自动以,;:、回车等自动解析成多条"。

**需求的形状**：F10 把单条订阅面补齐了，但"一批"这件事仍然只有 OPML 能做——而 OPML 是给"从别的阅读器搬家"准备的。操作员手上更常见的形态是一段聊天记录、一列表格、邮件里粘来的几行地址。[01 FR-2.1](./01-requirements.md) 原文只说"按 URL 添加"，没承诺过能吃下整段文本，这一轮把承诺补上。**不新增 FR 编号、不新增 01 的行**（该文件有行数契约），改的是 FR-2.1 那一行的实况与 §7 DoD 表里 F10 那一行。

**五个决定，以及各自的代价**

| 决定 | 内容 | 为什么不是别的做法 | 代价 |
|---|---|---|---|
| 预览与写入是**两个端点**，但写入**自己把文本重新拆一遍** | `POST /api/feeds/batch/plan` → `{items[], ready}`（[SubscriptionService.java:154-157](../../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L154)）；`POST /api/feeds/batch` → `{added, items[]}`（`:171-199`），入参仍是同一段 `text` | 如果写入端接受客户端回传的 plan，调用方就能把预览过的那一行改名成守卫没查过的地址——预览是**预告**不是**指令** | 两次拆分，代码上靠"两边共用同一个 `classify`"（`:206-253`）保证读法不会分叉 |
| 批量**逐行不发网络请求**，抓取交给队列 | 地址同时写 `requested_url` 与 `canonical_url`，每条排一行 `fetch` 并按写入序**错开 10 秒**（`:189`，沿用 OPML 导入的节奏），提交后统一唤醒调度器（`:194-196`） | 五十个站点就是五十次串行往返，一个不可达的源会把整次请求拖到超时；订阅这个动作不该被别人的服务器卡住 | 批量不做发现——贴进来的是网页地址时，第一轮抓取会在健康度里报 `not_a_feed`，改法是单条"解析"按钮。这条限制写进了 UI 的说明文字（[FeedsView.vue:376-377](../../web/src/views/FeedsView.vue#L376)）也写进了 [03 §14 O27](./03-technical-design.md) |
| **冒号只在不当承重构件时才当分隔符** | 逗号/分号（半全角）/顿号/空白/回车都是分隔；`:` 只在既不是 `://`（协议）也不是 `:\d+`+结尾或 `/ ? #`（端口）时才拆，**且左不像地址而右像**时整枚 token 读成"名字：地址"，名字进 `custom_title`（[PastedSubscriptions.java:103-116](../../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L103)、`:65-67`） | 用户点名要冒号做分隔符，而冒号是每一条 URL 的承重构件；只能靠上下文判身份。全角 `：` 同判，因为中文清单两种都会出现 | 一条"名字里含冒号"的行会被读成两段——读不懂的行仍会出现在表里，操作员看得见 |
| **读不懂的行仍然占一行** | 拆分器不吞行（`:22-24` 是这条规则的注释原文；`:93-95` 是"没地址可认领的名字回炉成候选"） | 二十行落地十八条，如果吞掉两行，表格就只有十八条，操作员没法知道少的是哪两行；`classify` 会给它 `invalid` 并带上行号 | 表里会有"读不懂"的红行，视觉上不像一个干净的成功 |
| 上限 200 行，**超出整批拒绝**而不是截断 | `MAX_BATCH_ITEMS = 200`（`:60`），判决在 `classify` 开头（`:211-214`），发生在任何 `create` 之前，所以不留半批 |  cap 不是为库，是为别让一个请求解析几百个主机名；而"静默丢掉后五十行"比"整批失败"更难发现 | 真有一百条以上的清单要分两次贴。另外空粘贴（只有分隔符）也 400 `nothing_to_add`——空粘贴不能长成"订阅了 0 条"的成功 |

**代码面**：新增一个生产类（[PastedSubscriptions.java](../../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java)，134 行，**纯文本工具**——不归一、不校验 SSRF、不碰库，所以 `admin-api` 这个叶子包仍然不触网，ArchUnit 七条一条没动）+ 两个测试类；改动四个文件（`FeedController.java` +26 行两个端点与 `BatchRequest`、`SubscriptionService.java` 三个方法与五态判决、`web/src/api/feeds.ts` 两个记录两个方法、`web/src/views/FeedsView.vue` 顶部一张卡）。**没有新视图、新路由、新 store、新迁移。**

**界面面**：`FeedsView.vue:354-437` 的批量卡——折叠按钮、多行输入、分组输入、"解析"、"添加 N 条"（N 来自 plan 的 `ready`，为 0 时 disabled）、"清空"，加一张五列逐行结果表（行/名称/地址/结果/说明）和一个独立提示框。四条绑在后端契约上的口径写在 [03 §11](./03-technical-design.md)：改文本即作废上次解析、五种判决只换皮不换语义（说明列**原样印后端的 `reason`**，不翻译）、地址列同时给"贴进来的原文"与"归一后的地址"、只有真加了才清空粘贴框。

**验收门禁（2026-11-03 执行结果，全部本机重跑）**

- [x] 后端全量：`backend/mvnw -o -B -ntp verify` **421 项 / 40 个类 / 0 失败 / BUILD SUCCESS（01:16）**，`.dev-logs/f16b-verify.log`。新增 `PastedSubscriptionsTest` 12 项（纯文本，不起 Spring 上下文）与 `SubscriptionBatchApiTest` 12 项（MockMvc + in-memory H2，显示名 `TC-SUB-61…72`）。ArchUnit 仍 **7 条**全绿。
- [x] 真 HTTP：`bash .dev-logs/smoke-run.sh f16b` **222 条 `PASS` / 0 `FAIL` / 末行 `ALL PASS`**（+17 项全部是 F16 段，[smoke.sh:511-563](../../backend/smoke.sh#L511)），一次性库 `./.dev-logs/smoke-f16b` + 运行时自造口令；开发库 `backend/data/rss-hub.mv.db` 全程未读写。F16 段另证一件 MockMvc 证不了的事：**批量真的不发请求**——没人监听的地址照样入库。文档同步（F16-d）收尾时换 `./.dev-logs/smoke-f16d` 又整体跑一遍，**仍是 222 条 `PASS` / `ALL PASS`**，后端重跑同为 421 项 0 失败（`.dev-logs/f16d-smoke.log`、`.dev-logs/f16d-verify.log`）。
- [x] 前端：`cd web && npm run build` **94 modules / 0 类型错误 / CSS 322.72 kB（gzip 48.01）/ JS 226.64 kB（gzip 75.44）/ 704 ms**，`.dev-logs/f16-build.log`。
- [x] 用例册重数：严格式 **461 条 / P0 280**（本轮 +34：`SBP` 12/6 + `SUB` 22/17），编号去重 0 条重复；附录 B 与计数说明同步。
- [x] 引用完整性与语义：两道常驻门禁重跑，`f13-check2.js` 0 缺失 / 0 越界、`f15-routecheck.js` 0 漂移（本轮数字见 [test-plan 25.4](../test-plan.md)）。
- [ ] **批量面板未在浏览器复核**：`TC-SUB-73…82` 十条全是 `[B]`，只由 `vue-tsc` 与构建产物证明"能编译能打包"，折叠态、长地址换行、按钮文案跳动、200 行表格的渲染都要人眼；登记 [03 §14 O26](./03-technical-design.md)。登录后 11 屏的旧欠账一条没清。

**与计划的偏差**：本轮捞出的四处都在 [test-plan 25.4](../test-plan.md)，其中最费时间的是**行号漂移**——四个文件插行把全仓指向它们之后的引用推动了一次，跨 test-plan 与 02/03/04 共 210 处；做法是拿 `git diff -U0 HEAD` 的 hunk 头算旧→新偏移表再一次性重写（[.dev-logs/f16-remap.js](../../.dev-logs/f16-remap.js)），三个坑（文本匹配会撞同名行、链接正则 `\?` 写错导致锚点全没命中、插入型 hunk 的 off-by-one）都写进了那一节。另外两处值得复述：**冒烟首轮把 CSRF 的 403 当成了产品行为**（写端点的 curl 必须同时带 cookie 与 `X-XSRF-TOKEN`），以及**用例编号是先到先得的资源**（`TC-SUB-01…12` 与第 5 节撞号，改成 61 起）。

**范围外**：跨批次/跨大小写/尾斜杠的 URL 归一判重（O27，明写为"逐行不发现"这个决定的第二笔代价）；批量逐行发现（会把一次请求变成 N 次往返）；批量删除与批量改分组（`PATCH /api/feeds/{id}` 仍是逐行）；粘贴里混 OPML/HTML 时自动识别并改走导入路径。

---

### M6 · AI 增强 + 检索 + 归档（8 人日）

**范围**（FR-4.1～4.5、归档）：`ai_artifact` 缓存 + provider 抽象 + 预算护栏 + 降级标记 + 收件箱摘要抽屉；FTS 搜索完善；`pgvector` 语义检索（先核实版本兼容）；条目按月分区/冷数据导出 JSONL；"本周信息摄入"小报告。

**门禁**：
- [ ] 关 AI 后抓取/阅读完全不受影响（02 §10 验收项）。
- [ ] 同 `content_hash` 不二次计费（断言 `ai_artifact` 命中）；触达日预算后新任务只存不加工并显示"未加工原因：预算"。
- [ ] 摘要展示明确标注"机器生成"，原始 `title/summary` 未被覆盖。
- [ ] 归档演练：导出 6 个月前条目为 JSONL 并删除，收件箱与 feed 不受影响。

---

## 5. 每阶段交付节奏（固定动作，防"自用项目烂尾"）

| 时点 | 动作 |
|---|---|
| 每周一 | 看上一里程碑燃尽 + 只挑一个最大阻塞 |
| 每阶段末（Gate Day） | 跑门禁清单 → 打 tag `v0.<n>` → 写 5 行"本阶段实际与计划的偏差" |
| 每周日 | `backup.sh` + **恢复演练**（备份不恢复验证等于没有备份） |
| 每月 | 复核 AI 预算/源数量/条目体积；清理 `raw_response`（保留 30 天） |
| 每次上线 | 公网 URL 跑一次 `validator.w3.org/feed/`（三格式各一次），结果记 `doc/design/verification-log.md` |

---

## 6. 度量（自用也要有数）

| 指标 | 目标 | 采集 |
|---|---|---|
| 抓取成功率 | ≥ 98% | `fetch_total{result}` |
| 静默坏掉的源 | 0 个被漏掉 | `feed_silence_seconds` 超 48h 且 `state=active` → 日报 |
| 条目重复率 | < 0.5% | 同 GUID 冲突计数 / `entry_duplicate_rejected_total` |
| 收件箱清空耗时 | ≤ 3 分钟 | 自己掐表（[01 §1](./01-requirements.md) 成功判据） |
| feed 校验 | 0 error | Validator + `SpecConformanceTest` |
| 规则命中精确率 | 命中里 ≥ 70% 是我真关心的 | 每周数 `dismissed` vs `read` |
| AI 成本 | ≤ 预算 | `ai_cost_micros_total` |

---

## 7. 变更控制（范围蔓延只靠流程挡住）

1. 新想法一律先进 `doc/design/backlog.md`（一行：动机 + 估计人日 + 影响哪个 FR）。
2. 进里程碑的条件：**同时移出一个等量工作项**，且由门禁日期让步（不改门禁不改范围）。
3. 明确拒绝项（写在墙上的 Won't）：多用户/权限体系、WebSub（本期，理由见 02 A6 与 [rss-guide.md:564](../rss-guide.md#L564)）、站内评论、原生 App、全文抓取存档站。
4. 任何"顺手重构"必须挂到一个已有风险项，否则不做。

---

## 8. 风险登记（Top 5）

| # | 风险 | 概率 | 影响 | 应对（已内建在计划里） |
|---|---|---|---|---|
| R1 | 兼职时间被切碎，M1 拖过 4 周 | 高 | 全线顺延 | M1 内部再切：先"抓取+落库+CLI 查看"，收件箱 UI 允许延后半周 |
| R2 | 脏 feed 容错无止境 | 中 | 时间黑洞 | 失败即留存 + 标记，设 98% 成功率线，超过就接受不完美 |
| R3 | 静默坏掉（没人告警） | 中 | 信任崩塌 | ~~M0 就有指标定义~~（**实际未做**：Micrometer 埋点与 JSON 日志仍空，见 §4/M0 的 ⚠️ 项）；~~M3 加"每日体检自推送"~~（**未做**，记为 [03 §14 O18](./03-technical-design.md)）；`feed_silence_seconds` 目前只有**逐源**形态 `quietForSeconds`（`adminapi/HealthController.java:43`）在 HealthView 上排出来，既没有汇总指标也不会主动推送——静默坏掉仍要靠人打开健康度页 |
| R4 | 播客/再分发提前诱发版权或账单问题 | 中 | 外部成本 | 严格 M4 前置三条 + M5 默认摘要；用量告警在 M6 前不设防但不外发大文件 |
| R5 | 依赖版本判断错（本文件的版本口径来自 2026-10-17 实测） | 低 | 返工 | 每个里程碑 Gate Day 复核一次 `start.spring.io` / registry 版本，写进 `verification-log.md` |

---

## 9. 脚手架交付方式（与用户约定的"分阶段脚手架代码"）

按里程碑逐个交付**可运行**的最小骨架，而不是最后一次性生成整个项目：

| 阶段 | 我产出 | 你需要做的 |
|---|---|---|
| 现在 | **M0 脚手架**（deploy + backend 空壳 + security + Flyway V1 + ArchUnit + CI + web 登录页）—— **已交付**（无 docker 版：H2 + 打包 jar；deploy/CI 见 O13/O14） | ~~装 Docker~~ 已按指示不做；Maven 用自带 `mvnw` 离线跑，Boot 4.1.1 / Java 21 口径已核 |
| M1 末 | feedfetch/feedparse/feedstore + 收件箱 UI —— **已交付** | 提供 10 个真实订阅地址做联调（**仍未做**：72h 稳定运行需要你真实订阅） |
| M2 末 | feedrender + editorial + SSR + 三格式 —— **已交付** | 给一个公网域名以跑 Validator（**未做**，本机上不了公网） |
| M3 末 | rules + notify + events ingest —— **已交付** | 给一个钉钉/企微机器人 webhook（只走环境变量，不入库明文）——**未做**：推送通道只对着本地 `WebhookSink` 测过，没有真实厂商往返 |
| M5 末 | repack 聚合再分发 —— **已交付**（2026-10-31，跳过 M4 先做，理由见 §10） | 用另一个阅读器真订阅一个 `/repack/{slug}.rss` 跨源主题 feed，看两周是否愿意留着它——**未做**，本机上不了公网 |
| F1.6 末 | changelog 变更公告 —— **已交付**（2026-11-01，补 M2 的账，关闭 BLK-02） | 用本地阅读器订阅 `http://localhost:8080/feed/changelog.rss`，改一篇文章、撤销一篇文章，看公告是否各报一次且不重复——**未做**（`[B]` 类人工走查，含 `PostsView` 变更面板，见 O21） |
| F2.4 末 | 收件箱两个过滤 + 键盘流 + 桌面通知 —— **已交付**（2026-11-01，补 M1 的账，关闭 BLK-01） | 自己用一周：j/k 换行、n 跳下一条未读、s/m 打标志、点正文标签 chip 看列表是否只剩这个标签、点"只看命中"看红标行是否就是全部；桌面通知要不要开由你决定——**未做**（`[B]` 类人工走查，见 O23） |
| M4+ | 按需，先确认前置条件 | 决定是否真的要发播客 |

每一阶段的脚手架原计划包含：迁移脚本、失败用例测试、`.env.example` 增量、`verification-log.md` 记录。**实际执行**：迁移脚本与失败用例测试都补齐了（V1–V7 双方言 + 421 用例）；`.env.example` 与 `deploy/` 一起按指示推迟（O13），环境变量口径目前只写在 [03 §12.1](./03-technical-design.md) 和 `backend/dev.env`（gitignored）；`verification-log.md` **从未建立**——各里程碑的验证结果直接写进了本文对应小节与 [03 §14](./03-technical-design.md) 的开放项表，不再另开文件。

---

## 10. 一句话总结

**MVP = M0+M1+M2+M3 ≈ 33.5 人日**，全职口径 2026-12-11 出口，兼职口径约 23 周；播客与再分发各带明确前置条件，不进 MVP。**截至 2026-10-18：M0–M3 的代码与自动化测试已全部交付（343 用例 0 失败、前端 `vue-tsc` + `vite build` 通过），MVP 只剩"人工验收"这一类无法自动化的门禁**——真实阅读器订阅、一周自用、公网 validator、真厂商 webhook。**2026-10-31：M5（聚合再分发）也交付了**——370 用例 0 失败、`npm run build` 通过、86 项真 HTTP 冒烟 `ALL PASS`；它同样只剩人工门禁（真订阅一个跨源 feed、浏览器走查）。剩余里程碑按 **M6 → M4** 推进：M6 需要一个 LLM provider 的凭据 + PG/pgvector（本机没有 PG，见 [03 §14 O3](./03-technical-design.md)），M4 依赖"真实使用 ≥4 周 + 一档真要发的节目 + 对象存储"三条前置（见 §4/M4 开工前置），两条都不在代码手里，故排最后。**2026-11-01：M2 唯一欠账的 FR-1.6 变更公告补账交付，BLK-02 关闭**——380 用例 0 失败 / 36 个测试类、`npm run build` 0 类型错误、`backend/smoke.sh` 由 86 项扩到 **129 项**并 `ALL PASS`。**同日 M1 唯一欠账的 FR-2.4 也补账交付，BLK-01 关闭**——收件箱的"只看关键词命中"与标签两个过滤、j/k/n/s/m 键盘流、60 秒未读轮询与桌面通知全部落地（`?alerted=`/`?category=` + V6 索引 + `composables/` 两个文件），期间还顺手修掉一处 FR-9.2 的旧欠账（前端硬发 `limit=50`，让"收件箱每页条数"这个设置在真实读取路径上是摆设）：口径是 **382 用例 0 失败 / 仍 36 个测试类**、`npm run build` 92 modules / 183.39 kB JS / 26.90 kB CSS 且 `vue-tsc` 0 错误、`backend/smoke.sh` 由 129 项扩到 **150 项**并在两个独立一次性库上各 `ALL PASS` 一遍。**截至 2026-11-02：M0–M3.5 + M5 + F1.6 + F2.4 + F10–F12 范围内已无"功能没做"的阻塞项**，剩下的只有三类：只能人工执行的门禁（真阅读器订阅、浏览器走查、公网 validator、真厂商 webhook——收件箱的键盘手感与通知观感、以及 F10/F11/F12 的全部视觉观感都在这一类里，见 [03 §14 O23/O24](./03-technical-design.md)）、按前置条件排后的 M4/M6，以及明写的欠账：[03 §14](./03-technical-design.md) 的 O15 邮件通道、O18 每日体检自推送、O22 定时发布不进公告，M5 范围外的"外部投稿 + 人工审核队列"（见上面 M5 小节），与 BLK-11 指标埋点、BLK-12 CI。**2026-11-02 同日插入 F10/F11/F12 一轮**：订阅就地编辑（标题/分组/状态/抓取间隔可改、**URL 拒改而非静默忽略**、立即抓取真跑本轮）、大屏一次载荷喂满整屏（24 桶直方图 + 十个标量 + 三条轨道 + 卡片墙）、`fluent.css` 令牌层把界面收进 Win11/Fluent 观感；关闭 test-plan 的 **D-04**（间隔占位写死 15）与 **F-05**（ttl 无法清空回"跟随站点"）两条口径缺陷。出口数字：**397 用例 0 失败 / 38 个测试类**、`npm run build` **93 modules / 190.20 kB JS / 42.40 kB CSS** 且 `vue-tsc` 0 错误、`backend/smoke.sh` 由 150 项扩到 **193 项** `ALL PASS`（F10 段 21 + F11 段 22）、Flyway 到 **V7**（双方言，并为此给方言对齐测试加了"ADD COLUMN 也要比"的第 5 条断言）。这一轮的 `[B]` 面**没有在浏览器里看过一眼**（截图工具返回 `NATIVE_BROWSER_VIEWPORT_UNAVAILABLE`），只有 `/login` 匿名页的 `getComputedStyle` 结构回读——所以界面项的承诺只到"色值只在一个文件里"这一条可机器验收的为止。**2026-11-02 同日再插 F13/F14/F15 三轮**（Bootstrap 迁移、淡蓝品牌层、用例册诚实度审计），交付面加 [README.md](../../README.md) 与 [doc/deploy.md](../deploy.md)。**2026-11-03 插入 F16 一轮（批量订阅）**：FR-2.1 从"按 URL 添加"补成"按 URL 添加，也可以一次粘一段"——`POST /api/feeds/batch/plan` 预览 + `POST /api/feeds/batch` 写入，拆分器认逗号/分号/顿号/空白/回车为分隔、冒号只在不是协议也不是端口时才拆，判决五态且**读不懂的行仍占一行**，批量逐行不发网络请求。出口数字：**421 用例 0 失败 / 40 个测试类**（ArchUnit 仍 7 条）、`npm run build` **94 modules / 0 类型错误 / CSS 322.72 kB / JS 226.64 kB**、`backend/smoke.sh` 由 205 项扩到 **222 项 `ALL PASS`**（F16 段 17 条）、用例 **461 条 / P0 280**、Flyway 仍在 **V7**（本轮无迁移）。当前总口径以本轮为准：**M0–M3.5 + M5 + F1.6 + F2.4 + F10–F16**，只能人工执行的门禁又多一条——批量面板的十条 `[B]`（[03 §14 O26](./03-technical-design.md)），以及明写的第二笔代价：批量判重是字符串相等（[03 §14 O27](./03-technical-design.md)）。
