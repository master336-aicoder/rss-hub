# 部署文档（RSS Hub）

> 适用版本：`rss-hub 0.0.1-SNAPSHOT`（2026-11-02 交付面：M0–M3.5 + M5 + F1.6/F2.4/F10–F14）。
> 本篇只讲**怎么把它跑起来并守住数据**；为什么这样设计见 [02 架构 §6 部署拓扑](./design/02-architecture.md)，
> 每个里程碑的实际执行记录见 [04 产研计划](./design/04-roadmap.md)。
> 口令、密钥在任何文档里都不出现——只写"从哪里来"。

---

## 1. 三种形态，先选一个

| 形态 | 数据库 | 静态资源 | 适用 |
|---|---|---|---|
| **A 开发自用**（当前仓库默认） | H2 文件库 `backend/data/rss-hub.mv.db` | Vite dev server 反代 | 日常读写、改代码 |
| **B 单机生产**（本篇主线） | H2 文件库（换目录，别放 `target/`） | 反向代理直接服务 `web/dist/` | 一台小机器/NAS，只有一个运维者 |
| **C 目标形态**（尚未实装） | PostgreSQL（+ 可选 pgvector） | 容器 + 对象存储（MinIO） | 需要多实例、音频托管、语义检索时 |

C 形态在 02 §6 里有拓扑图，但**本仓当前没有 Docker 编排文件**，`--spring.profiles.active=docker` 也没有对应 profile。
按 C 部署前需要先补这块，别把它当成现成能力。

---

## 2. 构建产物

```bash
cd backend && ./mvnw -o -B -ntp verify          # 产物 backend/target/rss-hub-0.0.1-SNAPSHOT.jar（约 38 MB，内嵌 Tomcat）
cd web && npm ci && npm run build               # 产物 web/dist/（index.html + assets/，CSS 322.72 kB / gzip 48.02，JS 221.11 kB / gzip 73.70）
```

- 运行只需要 **JDK/JRE 21**（编译期同样 21；`java -version` 先确认）。
- `web/dist/` 里有两个二进制字体（`bootstrap-icons` 的 woff2 134 kB + woff 180 kB），代理层不必再压一遍。
- 构建脚本 `npm run build` 里已含 `vue-tsc --noEmit`，类型错误会让构建失败——不要绕过。

---

## 3. 配置：全部走环境变量或命令行

`backend/src/main/resources/application.yml` 里每个环境相关项都写成 `${ENV:默认}`。Spring Boot 的宽松绑定会把
`spring.datasource.url` 对应到 `SPRING_DATASOURCE_URL`（点换下划线，大小写不敏感），并且**命令行参数的优先级高于环境变量**
（官方优先级：OS 环境变量第 5 位，命令行第 11 位）。

### 3.1 必设项

| 变量 | 对应属性 | 不设会怎样 |
|---|---|---|
| `RSSHUB_OPERATOR_PASSWORD` | `rsshub.operator.password` | 仅当 `app_user` 表为空时用于创建 admin；库里已有用户时这个值**不会**覆盖口令（引导程序刻意拒绝），换口令请走界面或改库 |
| `RSSHUB_PUBLISH_BASE_URL` | `rsshub.publish.base-url` | 默认 `http://localhost:8080`。feed 里的 GUID 是永久 URL，用请求头现推会让换域名（或一次 Host 欺骗）产生第二个永久 ID，阅读器会把同一篇文章当两条新的 |
| `RSSHUB_SECRET_KEY` | `rsshub.security.secret-key` | 不设则 TOTP 登记被拒绝（宁可不存也不明文保存 secret）。**丢了这把 32 字节随机密钥，已登记的 TOTP secret 与通道签名密钥都解不开**，它必须进安装备份 |

### 3.2 常调项（都有可用默认值）

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | 8080 | 应用监听端口 |
| `DB_URL` | `jdbc:h2:file:./data/rss-hub;DB_CLOSE_ON_EXIT=FALSE` | **相对路径按进程工作目录解析**，写成 `./data/...` 而不是 `data/...` 之外的形态；跨进程访问文件库需另配 H2 Server |
| `DB_USER` / `DB_PASSWORD` | `sa` / 空 | 换 PostgreSQL 时同时改这两项 |
| `RSSHUB_OPERATOR_USER` | `admin` | 引导用户名 |
| `RSSHUB_RAW_DIR` | `data/raw` | 原始响应落盘目录，保留 `rsshub.storage.raw-retention`（默认 30d） |
| `RSSHUB_NOTIFY_ENABLED` | true | 关掉后规则照常命中，但不外发 |
| `RSSHUB_PUBLISH_TITLE` / `_DESCRIPTION` / `_IMAGE` | 我的订阅站 / 空 / 空 | 站点 feed 的 channel 元数据；RSS 2.0 的 description 必填，留空时由代码生成兜底句而不是 500 |
| `rsshub.fetch.*` | 见 yml | 抓取节奏（默认 60 分钟一轮、最小 5 分钟、抖动 5 分钟、并发 6、单次上限 10 MiB、重定向 ≤3） |

### 3.3 只在测试实例上允许的口子

`--rsshub.ssrf.allow-private-prefixes=127.` —— 冒烟脚本用它让本机 fixture feed 可被抓取。
**生产实例不带这个参数**：SSRF 守卫默认拒绝私网、回环、链路本地与元数据地址范围。

### 3.4 启动示例（Linux）

```bash
export RSSHUB_OPERATOR_PASSWORD="$(cat /etc/rss-hub/operator.pwd)"   # 文件权限 600，属主为服务账号
export RSSHUB_SECRET_KEY="$(cat /etc/rss-hub/secret.key)"
export RSSHUB_PUBLISH_BASE_URL="https://feeds.example.com"
export DB_URL="jdbc:h2:file:/var/lib/rss-hub/data/rss-hub;DB_CLOSE_ON_EXIT=FALSE"
java -jar /opt/rss-hub/rss-hub-0.0.1-SNAPSHOT.jar
```

Windows 上的等价做法是把同一批变量放进服务账号的环境变量，或用 `--属性=值` 的命令行参数（优先级更高）。
仓库里的 `backend/dev.env` 只服务开发脚本 `start-dev.sh`，**不要**把它当生产配置来源，也不要提交。

---

## 4. 反向代理

### 4.1 一个必须先说清的约束：`/` 只有一份

Java 侧 `SecurityConfig.java:78-79` 把 `/`、`/blog`、`/blog/*`、`/event/*/*`、`/feed.*`、`/feed/*`、`/tag/*`、
`/category/*`、`/repack/*` 列为匿名可访问，其中 **`/` 是公开站首页**（`PublicViewController.java:46`）；
而控制台是 `createWebHistory()`（`web/src/router/index.ts:17`），它的收件箱也挂在 `/`（`:19`）。
同域名下这两个 `/` 无法共存，所以：

- **推荐**：公开内容与控制台分域名（下面的 Caddyfile 就是这么写的）。
- 想强行同域名：把 SPA 挪到前缀（`vite.config.ts` 加 `base: "/app/"`，`router` 传 `createWebHistory("/app/")`）后
  由代理把 `/app/**` 指向 `index.html`。**本仓尚未做这个改动**，别照抄别人的 SPA 前缀方案。
- 开发期不存在这个问题：控制台在 `:5173`，公开站在 `:8080`。

### 4.2 Caddy（自动 HTTPS）

```caddy
feeds.example.com {
	encode zstd gzip
	handle /api/* {
		reverse_proxy 127.0.0.1:8080
	}
	handle /actuator/health {
		reverse_proxy 127.0.0.1:8080
	}
	handle {
		reverse_proxy 127.0.0.1:8080   # 公开站：/, /blog, /feed.*, /tag/*, /category/*, /repack/*, /event/*/*
	}
}

admin.example.com {
	encode zstd gzip
	handle /api/* {
		reverse_proxy 127.0.0.1:8080
	}
	handle /actuator/health {
		reverse_proxy 127.0.0.1:8080
	}
	handle {
		root * /srv/rss-hub/dist
		try_files {path} /index.html
		file_server
	}
}
```

- `try_files {path} /index.html` 是 SPA history 路由的必需项，否则刷新 `/dashboard` 会 404。
- 会话 Cookie（`RSSHUB_SESSION`）与 `XSRF-TOKEN` 都是 `SameSite=Lax` + HttpOnly；控制台域名与 `/api` 必须同源，
  跨域部署会让所有写操作 403。
- actuator 只暴露 `health`（`application.yml` 的 `management.endpoints.web.exposure.include: health`），
  其余端点本来就没开，代理层不必再挡，但也不要为它加白名单以外的转发。

### 4.3 nginx（等价写法，节选）

```nginx
location /api/ { proxy_pass http://127.0.0.1:8080; }
location = /actuator/health { proxy_pass http://127.0.0.1:8080; }
location / {
    root /srv/rss-hub/dist;
    try_files $uri $uri/ /index.html;
}
```

---

## 5. 服务化（Linux systemd）

```ini
# /etc/systemd/system/rss-hub.service
[Unit]
Description=RSS Hub
After=network-online.target
Wants=network-online.target

[Service]
User=rsshub
WorkingDirectory=/var/lib/rss-hub
Environment=RSSHUB_OPERATOR_PASSWORD_FILE=/etc/rss-hub/operator.pwd
Environment=RSSHUB_PUBLISH_BASE_URL=https://feeds.example.com
Environment=DB_URL=jdbc:h2:file:/var/lib/rss-hub/data/rss-hub;DB_CLOSE_ON_EXIT=FALSE
ExecStart=/usr/bin/java -jar /opt/rss-hub/rss-hub-0.0.1-SNAPSHOT.jar
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/rss-hub

[Install]
WantedBy=multi-user.target
```

- `WorkingDirectory` 决定相对路径落点，与 `DB_URL`/`RSSHUB_RAW_DIR` 一起看，别一个绝对一个相对。
- systemd 不会自动展开 `$(cat …)`；示例里的 `_FILE` 变量只是命名习惯，真要读文件请写一个
  `EnvironmentFile=`/`ExecStartPre=` 包装脚本，或者用你的部署工具注入。
- Windows 侧没有官方服务化包装：可用任务计划程序（开机启动 + 失败重启）或第三方包装器，两者都属经验做法，
  本仓未实测，写在这是为了别把它当成已验证能力。

---

## 6. 备份与恢复

数据有两个落点：**H2 库文件**与**原始响应目录**（`data/raw`，feed 条目的正文都已在库里，raw 只用于排障与 30 天回放）。

H2 官方口径：**进程持有文件库时直接复制 `.mv.db` 是不安全的**（除非存储层支持快照）。所以：

1. **冷备**（简单，够用）：`systemctl stop rss-hub` → 复制 `data/rss-hub.mv.db` → 起服务。
2. **逻辑备份**（不停机也可用 SQL 导出，但请先停写以免拿到中间状态）：

   ```bash
   java -cp h2.jar org.h2.tools.Script \
     -url "jdbc:h2:file:/var/lib/rss-hub/data/rss-hub" -user sa \
     -script /backup/rss-hub-$(date +%F).zip -options compression zip
   ```

   恢复用 `org.h2.tools.RunScript` 指向一个**空的新库**：

   ```bash
   java -cp h2.jar org.h2.tools.RunScript \
     -url "jdbc:h2:file:/var/lib/rss-hub/data/restore" -user sa \
     -script /backup/rss-hub-2026-11-02.zip -options compression zip
   ```

3. 备份对象里**必须包含** `RSSHUB_SECRET_KEY`（否则 TOTP 与通道密钥全废）；口令哈希在库里，不需要单独备份。

恢复后自检（都是本机 curl，不涉口令）：`GET /actuator/health` 200 → `GET /feed.rss` 200 且条目数与备份前一致 →
`GET /` 200 → 登录后 `GET /api/entries/counters` 的 `total` 与大屏 `GET /api/dashboard` 的 `entries` 相等
（冒烟脚本对这两条一致性有现成断言，见 `backend/smoke.sh`）。

---

## 7. 升级与回滚

1. 备份（§6 的任一种）。
2. 换 jar：`cp rss-hub-<new>.jar /opt/rss-hub/` 后重启；**Flyway 会在启动时自动应用新迁移**
   （`db/migration/h2` 与 `db/migration/postgresql` 两套按 `{vendor}` 选择）。
3. 冒烟回归：`bash .dev-logs/smoke-run.sh upgrade`（一次性库 + 一次性口令，不碰生产数据）。
4. **回滚 = 旧 jar + 恢复备份库**。Flyway 不做 downgrade，所以跨版本的二进制回滚必须连带数据一起回，
   别指望"只退程序"。因此升级窗口内保留的那份备份要留到确认稳定之后再删。

---

## 8. 迁移到 PostgreSQL（形态 C，未实装路径）

代码侧的准备是齐的：`DB_URL/DB_USER/DB_PASSWORD` 三项即可切换方言，Flyway 走 `db/migration/postgresql`，
队列认领已按 `SKIP LOCKED` 设计可多实例。但请注意：

- **本仓没有做过 H2 → PostgreSQL 的数据搬迁演练**，也没有 `pgvector`/MinIO 的编排文件；
- 建好空 PG 库后先直接启动（Flyway 建表），再用 `pg_dump`/SQL 导数据，比试图让两边同时在线安全；
- 迁移后 `rsshub.fetch.per-host-gap` 等抓取参数不变，但**必须重新核对 `RSSHUB_PUBLISH_BASE_URL`**，
  因为 GUID 已按旧域名写进库。

---

## 9. 部署后验收清单

- [ ] `GET /actuator/health` = 200；`GET /actuator/env` = 404（其它端点未暴露）。
- [ ] 匿名 `GET /feed.rss` / `.atom` / `.json` 均 200，`GET /feed.opml` 这类未知后缀为 404 而不是 400。
- [ ] 二次请求带 `If-None-Match` 得到 304 且无 body（ETag 短路在渲染之前）。
- [ ] 未登录 `GET /api/feeds` = 401；不带 `X-XSRF-TOKEN` 的 POST = 被拒（401/403 皆算拒绝）；登出后回到 401。
- [ ] 订阅一个源并"立即抓取"，本轮内条目出现在收件箱；抓取间隔默认 60 分钟生效。
- [ ] 大屏 `/api/dashboard` 一次返回 KPI + 直方图 + 卡片墙 + 三条 rail；`entries` 与 `/api/entries/counters.total` 相等。
- [ ] 备份任务已在系统里排上（cron/systemd timer），并真的产出过一个可恢复的文件。
- [ ] `RSSHUB_SECRET_KEY` 已进备份；`backend/dev.env`、任何 `.env` 不在版本库里。
- [ ] 上线的这份代码在**它自己的机器**上跑过三条门禁：`cd backend && ./mvnw -o -B -ntp verify`（421 项 / 40 类）、`bash .dev-logs/smoke-run.sh <tag>`（222 项真 HTTP `ALL PASS`，一次性库与一次性口令）、`cd web && npm run build`（`vue-tsc --noEmit` 0 错误）。用例册口径见 [test-plan.md](./test-plan.md) §25.4。

---

## 10. 常见故障

| 现象 | 原因 | 处置 |
|---|---|---|
| `mvnw` 报 jar 被占用 / `verify` 失败在 repackage | 运行中的实例占着 `backend/target/rss-hub-0.0.1-SNAPSHOT.jar` | 先 `bash start-dev.sh stop`；注意它不会杀掉 `:18080` 上的冒烟实例，那个要单独停（见 03 §14 O25） |
| `curl http://127.0.0.1:5173/` 连不上 | Vite 8 dev server 只听 `localhost`（IPv6） | 用 `http://localhost:5173/`；代理**后端**目标写 `127.0.0.1` 是对的 |
| 启动即 `Database may be already in use` | 两个进程打开同一个 H2 文件库 | 停掉另一个，或给测试实例换 `DB_URL`（一次性库放 `.dev-logs/` 下） |
| 写操作全部 403 | 缺 `X-XSRF-TOKEN` 或 Cookie 不是第一方 | 确认控制台与 `/api` 同源；换域名后必须重新登录 |
| 首次启动没建出 admin | `RSSHUB_OPERATOR_PASSWORD` 为空 | 库里已有用户时该变量不会再覆盖口令——这是设计（见 §3.1） |
| 抓取一直失败且日志报私网拒绝 | 目标是内网/回环地址 | 生产不该抓内网；测试实例才加 `--rsshub.ssrf.allow-private-prefixes=127.` |
| feed 里 GUID 换了域名的条目被阅读器当新文章 | 迁移域名时没同步 `RSSHUB_PUBLISH_BASE_URL`，或曾用请求头现推 | GUID 是永久的：换域名要在同一个安装里保留旧域名的可解析性，并理解新旧 GUID 会并存 |

---

## 11. 本篇的外部依据（交付前逐条核对）

| 本文的说法 | 出处 |
|---|---|
| `spring.datasource.url` ↔ `SPRING_DATASOURCE_URL`（点换下划线、大小写不敏感），且命令行参数优先级高于 OS 环境变量（列表顺序 5 vs 11） | [Spring Boot 官方 · Externalized Application Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html) |
| Caddyfile 的 `reverse_proxy` / `try_files {path} /index.html` / `encode zstd gzip` 写法 | [Caddyfile 官方文档 · Concepts](https://caddyserver.com/docs/caddyfile/concepts) |
| H2 文件库：进程持有时直接复制 `.mv.db` 不安全；官方推荐导出压缩 SQL 脚本（`org.h2.tools.Script`，`-options compression zip`），恢复用 `RunScript` | [H2 官方 · Tutorial（Backup/Restore）](https://h2database.com/html/tutorial.html)；补充参考 [H2 SQL 导出语法（社区汇总）](https://gist.github.com/162cbba559907faefc5a) |
| systemd 单元指令（`Restart=on-failure`、`ProtectSystem=strict`、`ReadWritePaths`） | [systemd.service 官方 man page](https://www.freedesktop.org/software/systemd/man/systemd.service.html) |
| Windows 服务化（任务计划程序 / 第三方包装器） | **本仓未实测**，降级为经验做法，不作为验收项 |
