# RSS Hub

一个人自用的 RSS **订阅 + 发布** 服务：既是每天用来读订阅的收件箱，也是把自己写的内容对外发成
`rss / atom / json` 三格式 feed 的站点，同时还能接机器事件源、按关键词规则告警、把多个源聚合成一条跨源 feed。

后端是 Spring Boot（Java 21，H2 文件库），前端是 Vue 3 + Bootstrap 5.3（只用它的 CSS，不引它的 JavaScript）。
没有 Docker 依赖，一条 `java -jar` 就能起。

---

## 1. 能做什么

| 面 | 交付内容 |
|---|---|
| 订阅消费 | 收件箱（未读/已读/收藏/丢弃四态、`j k n s m` 键盘流、按分组/订阅/告警/分类过滤、桌面通知与 60 秒未读轮询）、阅读器面板、订阅管理（解析候选→就地编辑标题/分组/状态/抓取间隔，**URL 不可改**、立即抓取当轮执行、OPML 导入导出）、健康度页 |
| 抓取 | 定时轮询 + 条件请求（ETag / Last-Modified → 304）、抖动与按主机间隔、失败退避、SSRF 拦截（私网/回环/链路本地/元数据地址默认拒绝）、原始响应落盘 30 天 |
| 内容发布 | 站内文章（Markdown、草稿/定时/发布/撤回、标签与分类）、站点 feed `/feed.{rss,atom,json}`、标签切片 `/tag/{slug}.{format}`、分类切片 `/category/{slug}.{format}`、人类可读页 `/blog/{slug}`（带 `<link rel="alternate">` 发现块）、变更公告 `/feed/changelog.{format}` + `GET /api/changelog` |
| 事件与告警 | 事件源（`POST /api/ingest/events`，Bearer 令牌 + `dedupeKey` 幂等）、事件 feed `/feed/{slug}.{format}` 与详情页 `/event/{slug}/{publicId}`、关键词规则（匹配/抑制/静默时段/命中计数）、投递通道与重试阶梯、投递日志与重放 |
| 聚合再分发 | 转发型集合（跨源主题 feed），管理页 + 出口 `/repack/{slug}.{format}`，条目保留来源标注 |
| 运营视图 | 大屏（一次 `GET /api/dashboard` 喂满整屏：KPI、24 桶直方图、按源卡片墙、告警/发布/变更三条 rail）、系统状态、参数设置 |
| 安全 | 会话 Cookie（HttpOnly + SameSite=Lax）、CSRF 双提交、登录失败锁定、TOTP 二次验证、API 令牌（`Bearer`，只存哈希）、actuator 仅暴露 `health` |

**尚未实现**（设计文档里已明确后置，不是缺陷）：播客托管（M4 / FR-6）、AI 摘要·翻译·去重·语义检索（M6 / FR-4）、外部投稿与人工审核队列。

---

## 2. 技术栈

- 后端：Java 21、Spring Boot `4.1.1`、Spring MVC + Security、Flyway（`db/migration/{vendor}`，H2 与 PostgreSQL 双方言）、H2 2.x 文件库、jsoup + CommonMark（解析与 Markdown 渲染）、ArchUnit（架构断言）、虚拟线程开启。
- 前端：Vue `3.5` + Vite `8.3` + TypeScript `6.0` + Pinia `4.1` + vue-router `5.4`；`bootstrap@5.3.8` 与 `bootstrap-icons@1.13.2` 为运行时依赖，**不加载 Bootstrap 的 JS**（下拉/折叠/toast 由 Vue 状态驱动）。
- 界面令牌只写在一个文件里：`web/src/styles/theme.css`（品牌令牌 + 把 `--bs-*` 指向它的桥接块），15 个 `.vue` 零硬编码色值。

---

## 3. 快速开始

```bash
# 1) 后端：产出可执行 jar（同时跑全部单测/集成测试）
cd backend && ./mvnw -o -B -ntp verify && cd ..

# 2) 前端：类型检查 + 构建
cd web && npm ci && npm run build && cd ..

# 3) 首次运行前，准备口令文件（此文件已被 .gitignore 排除，绝不提交）
echo 'RSSHUB_OPERATOR_PASSWORD=<你想要的管理员口令>' > backend/dev.env

# 4) 起整栈：后端 :8080 + Vite dev :5173（代理 /api 与 /actuator）
bash start-dev.sh          # bash start-dev.sh status / stop
```

打开 http://localhost:5173/ ，用户名 `admin`，口令即第 3 步写入 `backend/dev.env` 的那个值。

- 口令只在 `app_user` 表为空时被引导程序读取一次；之后数据库拥有凭据，改口令请走界面或数据库，不要改这个文件。
- H2 数据文件默认落在 `backend/data/rss-hub.mv.db`（相对后端进程的启动目录解析）。
- 生产部署（反向代理、systemd、备份与升级）见 [doc/deploy.md](doc/deploy.md)。

---

## 4. 测试与验收

```bash
cd backend && ./mvnw -o -B -ntp verify          # 397 项单测/集成测试，38 个测试类
cd web && npm run build                          # vue-tsc --noEmit 0 错误 + vite 构建
bash .dev-logs/smoke-run.sh <tag>                # 一次性实例 + 一次性库 + 临时口令，205 项真 HTTP 断言
```

`smoke-run.sh` 会自己造口令（只存在于该次 shell 环境），**永远不要**拿开发库 `backend/data/rss-hub.mv.db` 做功能测试。
用例册与验收口径在 [doc/test-plan.md](doc/test-plan.md)：427 条用例（257 条 P0），按 `[自] / [机] / [C] / [B]` 四档标注——`[自]` 由 `./mvnw verify` 的单元/集成测试，`[机]` 由构建产物或 grep 断言，`[C]` 由冒烟脚本打真 HTTP，`[B]` 只能人工在浏览器里验。

当前自动化门禁的实测值：397 项测试全绿、205 项冒烟 `ALL PASS`、`.vue` 硬编码色值 0 处、全套文档 `file#L` 引用 963 条零越界、路由引用漂移审计（`.dev-logs/f15-routecheck.js`）54 条核对 0 条漂移。**登录后的 11 个界面仍未在浏览器逐屏复核**，这条限制记录在 [03 §14 O24](doc/design/03-technical-design.md) 与用例册 §24.8。

---

## 5. 界面与路由

顶栏 11 个页签：收件箱、大屏、订阅管理、文章、聚合、事件源、规则、投递、健康度、安全、设置。
公开侧无需登录：`/`、`/blog`、`/blog/{slug}`、`/event/{slug}/{publicId}`、`/feed.*`、`/feed/{slug}.*`、`/feed/changelog.*`、`/tag/{slug}.*`、`/category/{slug}.*`、`/repack/{slug}.*`，以及 `GET /actuator/health`。

管理 API 按前缀分组（全部需要会话，写操作需要 `X-XSRF-TOKEN`）：

```
/api/auth      /api/feeds      /api/entries     /api/health     /api/opml
/api/posts     /api/changelog  /api/repacks     /api/events     /api/ingest
/api/rules     /api/notifications   /api/settings   /api/system   /api/tokens   /api/dashboard
```

---

## 6. 仓库结构

```
backend/          Spring Boot 应用（src/main/java/com/rsshub/{feedparse,feedfetch,feedstore,adminapi,
                  feedrender,editorial,events,rules,notify,repack,changelog,settings,security,...}）
                  smoke.sh —— 真 HTTP 冒烟序列；src/test/java —— 38 个测试类
web/              Vue 3 控制台（src/views 12 个页面 + src/components + src/api + src/styles/theme.css）
doc/              rss-guide.md / rss-quickref.md（RSS 规范与速查）、test-plan.md（用例册）、deploy.md
doc/design/       01 需求 · 02 架构 · 03 技术设计 · 04 产研计划（含每个里程碑的实际执行记录）
.dev-logs/        本机日志与一次性库目录（已 gitignore，不落仓库）
start-dev.sh      开发栈起停脚本
```

---

## 7. 安全边界（部署前请读一遍）

- **SSRF**：抓取器默认拒绝私网/回环/链路本地/元数据地址。只有在**本机测试**时才用
  `--rsshub.ssrf.allow-private-prefixes=127.` 开口子，生产实例不要带这个参数。
- **口令与密钥**：`RSSHUB_OPERATOR_PASSWORD`、`RSSHUB_SECRET_KEY`（TOTP 加密密钥，丢了就解不开已登记的 secrets）、
  投递通道的签名密钥都只从环境进来。`backend/dev.env`、任何 `.env` 均不入库。
- **CSRF / 会话**：写操作要求 `X-XSRF-TOKEN` 与 Cookie 双提交；`RSSHUB_SESSION` 是 HttpOnly + SameSite=Lax。
- **actuator**：只暴露 `health`，其余端点在配置里就是关闭的。
- **公开出口**：feed 与文章页匿名可读，但 `RSSHUB_PUBLISH_BASE_URL` 必须显式设成对外域名——GUID 是永久 URL，
  用请求头现推会让同一次换绑产生第二个永久 ID。
