# 03 · 技术设计

> 定位：个人自用、单操作员、自托管。本篇给出可直接开工的实现细节：技术栈与版本口径、工程结构、数据模型（DDL）、抓取/解析/渲染算法、播客媒体链路、AI 与规则管道、API 契约、前端结构、安全、可观测、测试与 CI/CD。
> 关联：[01 需求分析](./01-requirements.md) · [02 架构设计](./02-architecture.md) · [04 产研计划](./04-roadmap.md) · 规范依据 [`doc/rss-guide.md`](../rss-guide.md) / [`doc/rss-quickref.md`](../rss-quickref.md)
> 引用约定：`rss-guide.md:L` 指主文档行号，`rss-quickref.md:L` 指速查页行号。**每条规范相关决策都挂了出处**，落地时按出处复核而不是凭记忆。

---

## 1. 技术栈与版本口径

| 层 | 选型 | 版本口径 | 理由 |
|---|---|---|---|
| 语言 | Java | **21**（本机实测 `java -version` = 21.0.2 LTS，2026-10-17；Boot 4.1 可选档含 17/21/25/27，见下方证据） | 与现有 JDK 一致，省一次装机；21 仍是受支持的 LTS，若日后升 25 只需换 `<java.version>` |
| 应用框架 | Spring Boot | **4.1.1**（Initializr 默认档；另有 4.0.8 线） | Web + 调度 + Security + Actuator 一站搞定；单体足够 |
| 数据访问 | Spring Data JDBC（优先）/ MyBatis（复杂查询处） | 跟随 Boot | 单用户业务，聚合根清晰，不需要 JPA 的脏检查与 N+1 陷阱 |
| DB | PostgreSQL | 18.x（当前 minor 18.6；17/16 亦在支持期） | `jsonb` + FTS + 部分索引 + `FOR UPDATE SKIP LOCKED`（决策 A3） |
| 迁移 | Flyway | 跟随 Boot | `docker compose up` 时自动升级，杜绝手工 SQL |
| XML 输出 | 自研 writer（StAX / 手工拼接 + 转义工具） | — | 要精确控制命名空间、CDATA、日期格式、`&amp;`；见 §6 |
| XML/JSON 解析 | 自研归一层；可选 Rome 作参考实现 | Rome 2.1.0（Maven Central `<release>`，2026-10-17 查） | 决策 A4：网络与容错必须自持；Rome 只在解析缺陷时备用 |
| HTTP 客户端 | JDK `java.net.http.HttpClient` | — | 原生条件请求/超时/重定向控制；不额外引依赖 |
| HTML 清洗 | jsoup | 1.23.2（Maven Central，同日查） | 白名单清洗 + URL 绝对化，正文渲染安全底线 |
| 调度 | Spring `@Scheduled` + DB 任务表 | — | 状态全在库里，重启可续（架构 §10 验收项） |
| 对象存储 | MinIO（本地）或云 S3 兼容桶 | 按部署日版本 | 音频 presign + Range 由存储层负责（A8） |
| 反向代理 | Caddy | 2.x | 自动 TLS、gzip/br、静态资源、`/feed` 缓存头 |
| 前端 | Vue 3.5.44 + Vite 8.3.4 + TypeScript **6.0.3**（不是 7.0.2，原因见 §1 末「M0 前端实测」）+ Pinia 4.1.0 + Vue Router 5.4.0 + **Bootstrap 5.3.8（只用 CSS）+ bootstrap-icons 1.13.2** | npm registry 2026-10-18 实测安装；Bootstrap 与图标字体是 2026-11-02 随 F13 落地的依赖（回读 `web/package.json` 的 `dependencies` 五项） | 只做管理端 SPA，公开页见 A10。**口径改过两次**：设计期写“Element Plus 到 M1 再接”，M0～F12 实际一直没接（全靠手写 CSS + F12 的令牌层）；F13 接的是 Bootstrap，但**只取编译后的 CSS 与图标字体，不取它的 JavaScript，也不取 Sass 源码**——组件行为仍由 Vue 状态 + 类名驱动（见 §11 与 02 的 A12）。 |
| 公开页 | Thymeleaf SSR（服务端渲染文章页/目录页） | 跟随 Boot | 保证 `<link rel=alternate>` 与文章 HTML 对爬虫可见 |
| 可观测 | Micrometer + Actuator + JSON 日志 | 跟随 Boot | 见 §12 |
| 测试 | JUnit 5 + AssertJ + ArchUnit；假源站是 **JDK `com.sun.net.httpserver` 自建的 `LocalFeedServer`** | 跟随 Boot；`LocalFeedServer` 见 `backend/src/test/java/com/rsshub/feedfetch/LocalFeedServer.java:85` | 见 §13。**设计期写的 Testcontainers 与 WireMock 都没有引入**（`grep -n "wiremock\|testcontainers" backend/pom.xml` 零命中）：本机没有 Docker，离线 Maven 也不想再赌一组传递依赖，所以八种 HTTP 响应由 JDK 自带 httpserver 提供，数据库侧统一用 H2 文件/内存库。 |
| 向量检索（M6） | pgvector | 按需，落地时核对 | 可选，不阻塞主链路 |

**版本证据（2026-10-17 实测抓取，不是记忆）**：
- `start.spring.io/metadata/client` → `bootVersion.default = 4.1.1.RELEASE`，同列 `4.0.8.RELEASE`、`4.2.0.M2`；`javaVersion` 提供 `17 / 21 / 25 / 27`（站点默认 17）。**即 Boot 4.1 的运行期基线远低于 25**，可以用 21；此前"README 要求 JDK 25"指的是构建 spring-boot 自身源码，不是跑应用。
- `start.spring.io/actuator/info` → 该站自身跑 Boot 4.1.0，元数据新鲜（git commit 2026-10-16）。
- Maven Central `maven-metadata.xml`：`com/rometools/rome = 2.1.0`、`org/jsoup/jsoup = 1.23.2`、`org/flywaydb/flyway-core = 13.10.3`（Flyway 实际以 Boot BOM 管理，这里只证生态活跃）。
- npm `/<pkg>/latest`：Vue 3.5.44、Vite 8.3.4、TypeScript 7.0.2、Pinia 4.1.0、Vue Router 5.4.0、Element Plus 2.14.7、naive-ui 2.45.3（备选 UI 库）。**F13 的补记（2026-11-02）**：实际接的备选都不是这两个，而是 `bootstrap@5.3.8` + `bootstrap-icons@1.13.2`——上面这行是 2026-10-17 的当日快照，保留不改，新版本号以 `web/package.json` 与 `npm ls bootstrap` 为准。
- 口径提醒：`bootstrap` 是 **runtime dependency**（CSS 也要打进产物），图标字体两枚（woff2 134.01 kB / woff 180.28 kB）由 `bootstrap-icons` 的 `@font-face` 带进 `dist/assets/`，这是 F13 之后产物里第一次出现二进制字体资源。
- PostgreSQL 官方版本页：当前支持 14–18，18 的 minor 为 18.6。

**仍需复核（开工时执行动作，不要凭本文）**：
1. `docker-compose.yml` 里 PG 用固定 tag `postgres:18.6`，禁用 `latest`。
2. pgvector 与 PG 18 的兼容版本（M6 才需要）。
3. 各家 IM 机器人（钉钉/企微）签名与限流细节 —— 见 §8.3 的写法是按"厂商文档当日复核"来落地的，本文件不复述未验证的数字。
4. MinIO Java SDK 与"presigned PUT/GET + Range"行为 —— 先用 `mc`/Console 做一次真实上传与 206 探测，再写代码。

**本机工具链实测（2026-10-17，`which` + `java -version` + 真跑 `mvnw`）**：`java/javac` = 21.0.2、Node 可用；`mvn` 与 `docker` 都不在 PATH。实测**这只有一个是真阻塞**：Maven 由生成物自带的 `mvnw` 解决（见下），**Docker/容器引擎才是 M0 唯一要先装的东西**。所以本工作区当前能编译与跑单测，但跑不了 `docker compose up`、也跑不了需要真实 PG 的集成验证——凡后者一律标"未在开发机执行"。

**脚手架实测结论（2026-10-17 深夜真跑过一次，不是推断）**：用 `curl https://start.spring.io/starter.zip -d bootVersion=4.1.1.RELEASE -d javaVersion=21 -d dependencies=web,actuator,data-jdbc,flyway,security,validation` 生成、解包、并用它自带的 `mvnw` 构建，结果：

1. **坑 1（会让构建第一步就红）**：Initializr 把 parent 写成 `spring-boot-starter-parent:4.1.1.RELEASE`，但 Maven Central 上只有 **`4.1.1`**（`.RELEASE` 后缀是 Initializr 元数据里的 id，不是发布产物；实测报 `Non-resolvable parent POM ... (absent)`）。**落地动作：生成后立刻把 `<version>4.1.1.RELEASE</version>` 改成 `<version>4.1.1</version>`**，之后 `./mvnw verify` 才能走下去。
2. **坑 2（Boot 4 改名）**：生成的依赖是 `spring-boot-starter-webmvc`（不是 `web`）、`spring-boot-starter-flyway`，测试依赖是**每个 starter 配一个** `spring-boot-starter-*-test`。别按 Boot 3 的肌肉记忆写 `starter-web` + `starter-test`。
3. **Java 21 可用**：改完 parent 后 `[debug parameters release 21]` 编译主类与测试类均通过 → Boot 4.1.1 与 Java 21 组合成立，本机不必换 JDK。
4. **唯一失败是预期的**：`M0probeApplicationTests.contextLoads` 报 `Failed to determine a suitable driver class`——因为没配 `spring.datasource.url` 且无 PG。这正是 M0 必须先起 compose（或本地 PG）再谈测试的证据；纯 `@SpringBootTest` 在没数据库时必挂，所以 §13 的集成测试统一走 Testcontainers，不依赖开发机装 PG。
5. **Maven 不需要单独装**：生成物自带 `mvnw`/`mvnw.cmd` + `.mvn/wrapper/maven-wrapper.properties`（wrapper 3.3.4、`only-script`、指向 Maven 3.10.0），首次运行自动拉取；实测从空 `~/.m2` 到编译完成约 5 分钟（需联网）。
6. **仍不可本地验证**：`docker compose up`（本机无 Docker 引擎），以及 Flyway 对 `postgres:18.6` 的迁移、MinIO presign 行为——这三项归 M0/M2/M4 的人工动作，不许当已证。
7. 生成物默认是 `application.properties`（内容仅 `spring.application.name`）；本设计用 `application.yml`，落地时删掉 properties，别两套并存。

> 结论修正：M0 的"装工具链"实际只剩**装 Docker**一件；Maven 与 JDK 已就绪，且已用真实构建确认过版本号与 starter 名单。

**M0 落地实测（2026-10-18，全部来自真跑，不是推断）**

后端：`./mvnw -B -ntp verify` = BUILD SUCCESS，`Tests run: 40, Failures: 0, Errors: 0, Skipped: 0`；打好的 jar 用 `backend/smoke.sh` 在真实 HTTP 上 14 项全过。前端：`npm install` + `npm run build` 全绿。过程中撞到并修掉的、以后会再撞的坑：

1. **Boot 4 带的是 Jackson 3**：包名是 `tools.jackson.databind.ObjectMapper`，`com.fasterxml.jackson.databind` 直接不存在（编译报 `程序包...不存在`）。所有 JSON 出入参配置都要按 Jackson 3 的键名写；例如 `spring.jackson.serialization.write-dates-as-timestamps` 已失效（该开关搬到了 `DateTimeFeature`），配了会在上下文启动时抛 `No enum constant tools.jackson.databind.SerializationFeature.write-dates-as-timestamps`。
2. **Boot 4 挪了自动配置测试注解的包**：`@AutoConfigureMockMvc` 现在在 `org.springframework.boot.webmvc.test.autoconfigure`，按 Boot 3 的 `test.autoconfigure.web.servlet` 导入会编译不过。
3. **开发库换成 H2 2.4.240（文件模式）而不是 PG**：按"先不实现 docker"的指示，M0 用 `jdbc:h2:file:./data/rss-hub;DB_CLOSE_ON_EXIT=FALSE`。两个实测约束：**H2 的 `AUTO_SERVER=TRUE` 与 `DB_CLOSE_ON_EXIT=FALSE` 互斥**（同时给就 `JdbcSQLFeatureNotSupportedException: Feature not supported: "AUTO_SERVER=TRUE && DB_CLOSE_ON_EXIT=FALSE"`，而且只有跑打包 jar 起真服务才暴露，MockMvc 用内存库跑不出来）；**`KEY`/`VALUE` 在 H2 是保留字**，所以 `settings` 表的列在两份方言里统一改叫 `setting_key`/`setting_value`（见 §3）。Flyway 侧用 `classpath:db/migration/{vendor}` 分方言目录，`h2/` 与 `postgresql/` 两份 `V1__baseline_schema.sql` 必须**逐列对齐**，这是一笔长期维护债。
4. **口令哈希用 `{bcrypt}` 而不是 §9.1 的 Argon2id**：`PasswordEncoderFactories.createDelegatingPasswordEncoder()` 开箱即用，Argon2 需要额外依赖，M0 先不引；delegating 前缀格式意味着以后加 `{argon2}` 不需要迁移已有行。
5. **CSRF：登录成功时必须自己再写一次 cookie**。Spring Security 7.1.1 的 `CsrfAuthenticationStrategy` 换了 token 但走 `loadDeferredToken` 不落地，而 `UsernamePasswordAuthenticationFilter` 认证通过后**短路整条过滤器链**，`CsrfCookieFilter` 根本不会跑 ⇒ 新 token 不写回，SPA 下一次写操作静默 403。修法是在 `formLogin().successHandler` 里取 `request.getAttribute(CsrfToken.class.getName())` 调一次 `getToken()`。这条只有真实 HTTP 能测出来，MockMvc 测不到。
6. **CSRF 契约是"header 放原始值、表单参数放 BREACH 掩码值"**（`SpaCsrfTokenRequestHandler`，见 §9.1）——所以 `spring-security-test` 的 `csrf()`/`csrf().asHeader()` 在测试里**不能用**（它塞的是掩码值，必 403）。单测改成模拟浏览器：先 GET 拿 `XSRF-TOKEN` cookie，再以 `X-XSRF-TOKEN` 原样回传。前端 `web/src/api/http.ts` 走的是同一个契约，`backend/smoke.sh` 与 `web/verify-dev-proxy.sh` 各验一遍。
7. **MockMvc 与真实 HTTP 的判定不完全等价**：跨站表单登录在 MockMvc 下是 403、真 HTTP 下是 401，断言只能写成"被拒且未认证"（`is4xxClientError()`）。凡是安全边界类断言，最终以真 HTTP 为准。
8. **`vue-tsc@3.3.13`（当前 latest）不吃 TypeScript 7**：TS 7 的 `package.json` 有 `exports` 白名单且不再导出 `./lib/tsc`，vue-tsc 靠 `require.resolve("typescript/lib/tsc")` 定位编译器，直接 `ERR_PACKAGE_PATH_NOT_EXPORTED` 崩栈。TS 6.0.3 无 `exports` 字段所以能过。**口径：TypeScript 锁 `^6.0.3`**，等 vue-tsc 适配 TS 7 再升；`npm run build` 里的 `vue-tsc --noEmit` 因此仍有效。
9. **登录路径以代码为准是 `/api/auth/login`**（不是 §10 早期写的 `/api/login`），`/api/auth/logout`、`/api/auth/me` 同前缀。§10 已按实现更正。

**M1a 落地实测（2026-10-18，`backend/src/main/java/com/rsshub/feedfetch/`，全部来自真跑）**

`./mvnw -B -ntp test` = `Tests run: 95, Failures: 0, Errors: 0`（其中抓取判定表 21 条、队列 12 条、DNS 固定 10 条）。`verify` 的最后一步 `spring-boot-maven-plugin:repackage` 在 Windows 上会报 `Unable to rename ...rss-hub-0.0.1-SNAPSHOT.jar`——原因是**正在运行的后端占着那个 jar**，不是构建问题；改代码期间用 `test`，要出包就先停进程。过程中撞到的、以后还会撞的：

10. **`JdbcClient` 没有 `one()`，也没有 `.column(Class)`**：`query()` 返回 `ResultQuerySpec`，只有 `singleValue/optionalValue/singleRow/listOfRows/singleColumn/rowSet`；`query(Class)` 或 `query(RowMapper)` 返回 `MappedQuerySpec`，只有 `stream/list/set/single/optional`。按 MyBatis 直觉写 `.one()` 直接编译不过。另外 **`single()` 遇到 SQL NULL 会抛 `TypeMismatchDataAccessException: Result value is null but no null value expected`**，而 `last_error`/`claimed_at` 的 NULL 恰恰是要断言的语义，所以读这些列要么 `.optional()`，要么走 RowMapper（见 `FeedFetchServiceTest.java:469` 起的 `text()/num()/instant()`）。
11. **`HttpClient.Builder` 没有 `userAgent(String)`**（javap 确认 spring 依赖的 JDK 21 `java.net.http` 里没有这个方法），UA 只能做成每个请求的 header；`user-agent` 不在受限 header 名单里（受限的是 connection/content-length/expect/host/upgrade），所以不需要 `-Djdk.httpclient.allowRestrictedHeaders`。
12. **H2 的 `getGeneratedKeys()` 会把所有带 DEFAULT 的列都返回**（`feed` 表上是 `id, public_id, created_at, updated_at` 四列），于是 `GeneratedKeyHolder.getKey()` 抛 `InvalidDataAccessApiUsage: The getKey method should only be used when a single key is returned`。口径：**必须** `update(keyHolder, "id")` 显式点名标识列（`RawResponseStore.java:62`），或者干脆不取生成键、按唯一业务列读回（测试夹具 `FeedFetchServiceTest.java:397`）。PG 只返回 OID 列，所以这条坑只在开发库上出现。
13. **`java.net.http` 没有 `DnsResolver` 接口**，§9.2 要求的"把校验过的 IP 固定用于本次连接"只有一条路：JDK 18+ 的 `java.net.spi.InetAddressResolverProvider` SPI（`backend/src/main/resources/META-INF/services/java.net.spi.InetAddressResolverProvider`）。两条实测细节：**（a）** `InetAddress.getAllByName` 的负缓存（默认 ~10s）和正缓存（默认 ~30s）都在 `InetAddress` 层、**在 SPI 之前**命中，所以"先证明解析不了、再证明 pin 上了"这种写法一定会假失败——测试改成只对没查过的名字（`.invalid` + `System.nanoTime()`）断言 pin 生效；**（b）** 平台默认 `LookupPolicy` 的 characteristics 同时含 `IPV4` 和 `IPV6`（`InetAddress.initializePlatformLookupPolicy`，默认 `IPV4 | IPV6 | IPV4_FIRST`），所以 `characteristics & IPV4 != 0` **不等于**"只要 IPv4"，按单族过滤会把双栈源的 IPv6 全丢掉；只有 `IPV4`/`IPV6` 恰好一个置位时才收窄（`PinnedAddressResolver.java:41` `preferFamily`）。
14. **认领语句必须重读 `fire_at`，不能只校 `state='pending'`**。§4.2 原来的写法（SELECT 候选 → UPDATE ... WHERE state='pending'）在并发下会把一个"刚被别人重新排到未来"的行提前认领走——6 个 worker 跑 24 条到期行，实测重复认领 6 次（`TaskQueueTest.twoWorkersNeverGetTheSameRow`）。修法是 UPDATE 里带上 `and fire_at <= ?`（`TaskQueue.java:92`）。单线程测试永远发现不了这条。
15. **§4.3 的 `Accept-Encoding: gzip, br` 在 JDK 客户端上是错的口径**：`java.net.http` 自己声明并解码 gzip，手工再发一遍只会让解析层拿到已解压的字节却以为没有；Brotli 没有 JDK 支持，要引依赖才有。口径改为**不发 `Accept-Encoding`**（`FeedFetchServiceTest.java:128` 断言请求里确实没有这个头）。
16. **`text/html` 的 200 也要留原始字节**：这条是"订阅地址填成了网页"最常见的现场，判定顺序里没写，实现按 §4.3 step 4 的留存优先（先落盘再判类型，`FeedFetchService.java:133`）；键是内容寻址的，同一页每天重复只多一份行、不重复占盘。

---

## 2. 工程结构

单 Maven 模块 + 按领域分包（自用项目不值得多模块的构建复杂度），用 **ArchUnit** 把 [02 §3 的三条边界规则](./02-architecture.md)变成会失败的测试——实测代码化为 **7 条** `ArchRule`（逐条名称见 §13 与 `ArchitectureTest.java:26-74`）。

```
rss-hub/
├─ backend/
│  ├─ pom.xml
│  └─ src/
│     ├─ main/java/com/rsshub/
│     │  ├─ RssHubApplication.java
│     │  ├─ bootstrap/        # 配置类、安全装配、调度器、Actuator 定制
│     │  ├─ common/           # 时钟、ID、分页、错误、Result 映射、事务工具
│     │  ├─ security/         # 登录/TOTP/session/API token/SSRF 守卫
│     │  ├─ feedfetch/        # FeedFetchService/FetchDispatcher/HttpFeedFetcher/FetchPolicy/HostPoliteness/RawResponseStore/TaskQueue（草案里的 FetchService、Dispatcher 是简称）
│     │  ├─ feedparse/        # 归一层：NormalizedFeed / NormalizedEntry
│     │  ├─ feedstore/        # 幂等落库、修订、阅读状态
│     │  ├─ feedrender/       # FeedSource SPI + Rss2/Atom/Json11/Podcast writer
│     │  ├─ editorial/        # Post、分类、标签、公开页 SSR（发布状态机 + 把公开变更交给 changelog 记一笔）
│     │  ├─ events/           # ingest API、事件流
│     │  ├─ repack/           # 聚合再分发源（F7，M5 交付）
│     │  ├─ changelog/        # 变更公告（F1.6，2026-11-01 交付）：content_change 读写 + 变更出口源
│     │  ├─ podcast/          # Show/Episode、合规自检 —— **M4，包尚未创建**
│     │  ├─ rules/            # 规则匹配、抑制、试跑
│     │  ├─ notify/           # 通道适配器、投递日志、重试
│     │  ├─ settings/         # 运行参数键值（M3.5 交付）
│     │  ├─ ai/               # 任务、缓存、预算护栏、provider 抽象 —— **M6，包尚未创建**
│     │  └─ adminapi/         # 给 Vue3 的 REST
│     ├─ main/resources/
│     │  ├─ application.yml   # 只有 profile 无关的默认值
│     │  ├─ db/migration/     # 实际是 db/migration/{h2,postgresql}/V1__baseline_schema.sql … V5（见下方差异清单）
│     │  ├─ templates/        # Thymeleaf 公开页（实测 4 个：layout/post/list/event）
│     │  └─ META-INF/services/java.net.spi.InetAddressResolverProvider  # 注册 `feedfetch.PinnedAddressResolverProvider`，让 DNS 解析结果被原样钉住再用（SSRF 防重绑定）
│     └─ test/resources/fixtures/  # 供集成测试用的脏 feed 样本（设计期写在 main/resources，实际属测试资源）
│     └─ test/java/com/rsshub/… # 含 ArchitectureTest.java
├─ web/                       # Vue3 + Vite + TS（管理端）
├─ deploy/
│  ├─ docker-compose.yml
│  ├─ Caddyfile
│  ├─ backup.sh / restore.sh
│  └─ .env.example
└─ doc/design/                # 本套文档
```

包依赖方向（ArchUnit 规则）：
```
adminapi → (editorial | feedstore | rules | podcast | events | feedfetch | changelog) → feedrender → feedstore
feedfetch → feedparse → feedstore          # 解析只产出模型，不碰网络也不碰渲染
禁止：feedparse ↔ feedrender 相互依赖        # 02 §3 规则 1
禁止：除 feedfetch/security/notify/ai 外任何包发外部 HTTP   # 02 §3 规则 2
```

**M0 落地后与上图的差异（2026-10-18）**：
- `resources/db/migration/` 实际是 **`{vendor}` 分包**（`db/migration/h2/`、`db/migration/postgresql/`，由 `spring.flyway.locations=classpath:db/migration/{vendor}` 选择），文件名统一 `V1__baseline_schema.sql`。两份必须逐列对齐，这是 H2 开发库换来的债（§14 O11）。
- `SecurityConfig` 落在 **`security`** 包而不是图上写的 `bootstrap`（`bootstrap/` 只留 `AppConfig` 的 `@ConfigurationPropertiesScan` + `@EnableScheduling`、`OperatorProperties`、`OperatorBootstrap`）：按领域内聚放，登录装配与 `DbUserDetailsService`、`SsrfGuard` 同包。目前实测**没有任何 `security → bootstrap` 或 `bootstrap → security` 的 import**（`grep "^import com.rsshub" bootstrap/*.java security/*.java`：只有 `SecurityConfig.java:3 → common.ApiResponses` 与 `SsrfGuard.java:3 → common.SsrfBlockedException`），所以这个选择不会撞上 `no_package_cycles`；将来若把装配搬进 `bootstrap`，这条规则会立刻替你把关。
- `common` 比图上多了 `SsrfBlockedException`：**SSRF 异常必须放 `common`**，因为 `ApiExceptionHandler` 在 `common` 要捕获它，而 `common → security` 违反 `common_is_the_bottom_layer`。守卫本体仍在 `security`。
- `deploy/` **整体未交付**（指示：先不做 docker）。`adminapi`/`feedstore` 在 M1–M3.5 已长成完整读写面，本图那句"只读 GET /api/feeds"是 M0 快照。
- **`repack` 只依赖 `common` + `feedrender`**（`grep "^import com.rsshub" repack/*.java` 实测：`ApiException`/`Hashing`/`JdbcTimes` 三个 common 类型 + `FeedFingerprint`/`FeedItem`/`FeedKey`/`FeedMeta`/`FeedScope`/`FeedSource`/`FeedWriter`/`PlainText`/`PublishProperties` 九个 feedrender 类型），数据访问用 Spring 的 `JdbcClient` 直接打 `entry`/`feed` 两张表，**不 import `feedstore`**——走的是 `events` 包已经立过的先例。这样 `rendering_never_touches_the_store`、`parsing_does_not_reach_rendering` 两条都不受影响，`ArchitectureTest` 七条全绿（`Tests run: 7`，测试类总数的口径见 §13）。`settings` 是被别人读的叶子包。
- **`changelog` 也只有两条出边**（2026-11-01 F1.6 交付，`grep "^import com.rsshub" changelog/*.java` 实测：`ChangeLogRepository` → `common` 的 `Hashing`/`JdbcTimes`；`ChangeLogFeedSource` → `feedrender` 的 `FeedFingerprint`/`FeedItem`/`FeedKey`/`FeedMeta`/`FeedScope`/`FeedSource`/`PublishProperties` 七个类型），**两个类、零 Spring 之外的依赖**，同样用 `JdbcClient` 直接打自己的 `content_change` 表。反向边是新增的：`editorial/PostService`、`editorial/PublicViewController`、`adminapi/ChangelogController` 三个文件 import `changelog`（`grep -rln "com.rsshub.changelog"` 实测），形状是 `editorial → changelog → feedrender`，**没有环**，所以 `ArchitectureTest` 七条仍全绿（测试类 35 → 36，本轮 `Tests run: 380, Failures: 0`）。
- **上图里 "`→ feedrender → feedstore`" 这一段从未成立**：渲染层被 `rendering_never_touches_the_store` 明令禁止依赖 `feedstore`（M0 就写了这条规则，理由见 `ArchitectureTest.java:50-54`）。实际的形状是"谁实现 `FeedSource`，谁自己去取数据"——`editorial` 取文章、`events` 取事件条目、`repack` 取成员源的条目、`changelog` 取变更行，各自用 `JdbcClient`/`JdbcTemplate` 打库，`feedrender` 只拿到已经取好的 `FeedItem` 列表做格式化与指纹。图上真正成立的部分是 `adminapi → (editorial | feedstore | feedparse | rules | notify | events | feedfetch | repack | changelog | settings | security | common)`（`grep -ho "com\.rsshub\.[a-z]*" adminapi/*.java | sort -u` 实测的完整出边，2026-11-01 回读）；`podcast` 那一路属 M4，包还不存在。

---

## 3. 数据模型（DDL 草案）

命名：表名单数、时间列统一 `timestamptz`、软删除用 `state` 而非 `deleted_at`（自用不需要）。ID 用 `bigint generated always as identity`，对外暴露另发 `public_id`（uuid），避免"自增 ID 泄漏业务量"且方便导出。

```sql
-- 3.1 操作员与令牌 ---------------------------------------------------
create table app_user (
  id            bigint primary key generated always as identity,
  username      text not null unique,
  password_hash text not null,                 -- Argon2id，由 Spring Security 生成
  totp_secret   text,                          -- 加密存储（见 §9）
  created_at    timestamptz not null default now()
);

create table api_token (
  id           bigint primary key generated always as identity,
  name         text not null,
  scope        text not null check (scope in ('ingest','read','admin')),
  token_hash   text not null unique,           -- 只存哈希，明文只在创建时展示一次
  created_at   timestamptz not null default now(),
  last_used_at timestamptz,
  revoked_at   timestamptz
);

-- 3.2 订阅源 ---------------------------------------------------------
create table feed (
  id                bigint primary key generated always as identity,
  public_id         uuid not null default gen_random_uuid() unique,
  canonical_url     text not null unique,      -- 301 之后写新地址
  requested_url     text not null,             -- 用户当初贴的地址，保留用于诊断
  self_url          text,                      -- atom:link rel=self
  format            text,                      -- rss2 | atom | rdf | json1 | json1.1 | unknown
  title             text,
  site_url          text,
  description       text,
  language          text,
  image_url         text,
  etag              text,
  last_modified_hdr text,
  ttl_minutes       int,                       -- 源自带 ttl；无则用全局默认
  auth_ref          text,                      -- 指向加密凭证；默认匿名
  state             text not null default 'active'
                    check (state in ('active','muted','error','gone','paused')),
  fail_count        int  not null default 0,   -- 连续失败次数（成功即归零）
  notfound_streak   int  not null default 0,   -- 连续 404/410 计数
  last_http_status  int,
  last_error        text,
  last_fetched_at   timestamptz,
  last_change_at    timestamptz,               -- 最后一次真的拿到新条目
  avg_fetch_ms      int,
  group_name        text,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);
create index feed_due_idx on feed (state, last_fetched_at);  -- 供调度粗筛

-- 3.3 通用任务表（抓取/AI/投递/自检共用；A2 的"PG 当队列"） ----------
create table scheduled_task (
  id          bigint primary key generated always as identity,
  kind        text    not null check (kind in
              ('fetch','ai_summary','ai_dedupe','deliver','media_probe','sitemap')),
  ref_id      bigint  not null,                -- 语义由 kind 决定
  dedupe_key  text    not null,                -- kind + ref_id，保证同任务不重复入队
  state       text    not null default 'pending'
              check (state in ('pending','running','success','failed','cancelled')),
  fire_at     timestamptz not null default now(),
  attempts    int     not null default 0,
  max_attempts int    not null default 5,
  claimed_at  timestamptz,
  locked_by   text,                            -- 实例标识，便于将来多实例
  last_error  text,
  payload     jsonb   not null default '{}',
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  unique (kind, dedupe_key)
);
-- 只索引待执行行，规模 20 万条目/日时这张表仍能秒级认领
create index task_due_idx on scheduled_task (fire_at, id) where state = 'pending';

-- 3.4 条目（消费侧规范化模型） ---------------------------------------
create table entry (
  id             bigint generated always as identity,
  public_id      uuid not null default gen_random_uuid(),
  feed_id        bigint not null references feed(id) on delete cascade,
  guid           text not null,               -- 归一后的永久 ID，见 §5.3
  url            text,                        -- 原文绝对地址
  title          text,
  summary        text,
  content_html   text,                        -- 已清洗
  content_hash   text,                        -- sha256(规范化正文)，AI/去重键
  authors        jsonb not null default '[]', -- [{name,email,url}]
  categories     jsonb not null default '[]',
  enclosures     jsonb not null default '[]', -- [{url,lengthInBytes,type}]
  published_at   timestamptz,
  updated_at_src timestamptz,                 -- 源声称的更新时间
  fetched_at     timestamptz not null default now(),
  revision       int  not null default 1,
  read_state     text not null default 'unread'
                 check (read_state in ('unread','read','dismissed')),
  starred        boolean not null default false,
  archived       boolean not null default false,
  baseline       boolean not null default false, -- 首次订阅只建基线（FR-2.3）
  lang           text,
  search_ts      tsvector generated always as
                 (to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(summary,''))) stored,
  primary key (id),
  unique (feed_id, guid)                      -- 幂等的根
);
create index entry_inbox_idx  on entry (feed_id, published_at desc nulls last, id desc);
create index entry_unread_idx on entry (read_state, published_at desc) where read_state = 'unread';
create index entry_search_idx on entry using gin (search_ts);

create table entry_revision (
  entry_id     bigint not null references entry(id) on delete cascade,
  revision     int  not null,
  title        text,
  content_hash text,
  updated_at_src timestamptz,
  raw          jsonb,
  recorded_at  timestamptz not null default now(),
  primary key (entry_id, revision)
);

-- 3.5 原始响应留存（可靠 NFR：能用原始字节重放解析） ------------------
create table raw_response (
  id           bigint primary key generated always as identity,
  feed_id      bigint not null references feed(id) on delete cascade,
  fetched_at   timestamptz not null default now(),
  http_status  int  not null,
  content_type text,
  byte_size    int  not null,
  sha256       text not null,
  storage_key  text not null,                 -- 对象存储/磁盘路径，保留 ≥30 天后清理
  entry_count  int                            -- 解析出多少条，用于"200 但空源"告警
);
create index raw_time_idx on raw_response (fetched_at);

-- 3.6 发布侧：站内文章 -----------------------------------------------
create table post (
  id            bigint primary key generated always as identity,
  public_id     uuid not null default gen_random_uuid(),
  slug          text not null unique,          -- 决定永久 URL，创建后禁止改（改则 301）
  title         text not null,
  summary_md    text,
  body_md       text not null,
  body_html     text not null,                 -- 由 Markdown 渲染后缓存
  status        text not null default 'draft'
                check (status in ('draft','scheduled','published','unlisted','archived')),
  published_at  timestamptz,
  updated_at    timestamptz not null default now(),
  cover_asset_id bigint references media_asset(id),
  canonical_url text                            -- 允许指向外部原文（syndication）
);
create index post_feed_idx on post (status, published_at desc nulls last);

create table tag       (id bigint primary key generated always as identity,
                        slug text not null unique, title text not null);
create table post_tag  (post_id bigint references post(id) on delete cascade,
                        tag_id  bigint references tag(id)  on delete cascade,
                        primary key (post_id, tag_id));
create table category  (id bigint primary key generated always as identity,
                        slug text not null unique, title text not null, feed_enabled boolean not null default true);
create table post_category (post_id bigint, category_id bigint, primary key (post_id, category_id));

-- 3.7 事件源（F3） ---------------------------------------------------
create table event_stream (
  id          bigint primary key generated always as identity,
  slug        text not null unique,           -- /feed/{slug}.xml
  title       text not null,
  visibility  text not null default 'internal'
              check (visibility in ('internal','virtual_feed')),  -- FR-3.3
  created_at  timestamptz not null default now()
);
create table event_item (
  id          bigint primary key generated always as identity,
  stream_id   bigint not null references event_stream(id) on delete cascade,
  dedupe_key  text not null,
  title       text not null,
  url         text,
  summary     text,
  tags        jsonb not null default '[]',
  published_at timestamptz not null default now(),
  content_hash text not null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  unique (stream_id, dedupe_key)              -- FR-3.2：同 key 更新而非新增
);

-- 3.8 播客（F6，M4） ------------------------------------------------
create table show (
  id            bigint primary key generated always as identity,
  slug          text not null unique,
  title         text not null,
  author        text,
  owner_email   text not null,               -- 目录认领与通知用
  description   text not null,
  language      text not null,
  explicit      text not null default 'false' check (explicit in ('true','false')),
  episode_type  text not null default 'episodic' check (episode_type in ('episodic','serial')),
  category1     text, category2 text, category3 text,
  cover_asset_id bigint references media_asset(id),
  feed_path     text not null unique,         -- /podcast/{slug}/feed.xml，创建后不可变
  new_feed_url  text,                         -- 迁移期写 itunes:new-feed-url
  created_at    timestamptz not null default now()
);
create table episode (
  id            bigint primary key generated always as identity,
  show_id       bigint not null references show(id) on delete cascade,
  guid          text not null,               -- 永久不可变（FR-6.3）
  title         text not null,
  description   text not null,
  season        int, number int,
  explicit      text not null default 'false',
  audio_asset_id bigint not null references media_asset(id),
  duration_seconds int,                     -- 秒数，Apple 推荐（rss-quickref.md:174）
  published_at  timestamptz not null,
  itunes_type   text default 'full',        -- full | trailer | bonus
  created_at    timestamptz not null default now(),
  unique (show_id, guid)
);

-- 3.9 媒体资产 -------------------------------------------------------
create table media_asset (
  id            bigint primary key generated always as identity,
  kind          text not null check (kind in ('cover','audio','image','other')),
  storage_key   text not null unique,
  filename      text,
  content_type  text,
  byte_size     bigint,                      -- enclosure 的 length 必须等于真实字节
  sha256        text,
  width_px      int, height_px int,
  duration_seconds numeric,
  probe_json    jsonb,                       -- ffprobe 结果，便于复现校验
  created_at    timestamptz not null default now()
);

-- 3.10 规则与推送 ----------------------------------------------------
create table rule (
  id            bigint primary key generated always as identity,
  name          text not null,
  enabled       boolean not null default true,
  match         jsonb not null,   -- {includeAny:[],excludeAny:[],regex:[],feedIds:[],groups:[],tags:[],windowMinutes:int}
  actions       jsonb not null,   -- [{channel:'dingtalk',target:'...'}] | [{channel:'markOnly'}]
  suppression   jsonb not null default '{"perKeyHours":6}',
  quiet_hours   jsonb,            -- {"from":"23:00","to":"07:00","tz":"Asia/Shanghai"}
  hit_count     int not null default 0,
  disabled_reason text,           -- 连续投递失败自动停用时写这里（FR-5.3）
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create table notification_log (
  id           bigint primary key generated always as identity,
  rule_id      bigint references rule(id) on delete set null,
  entry_id     bigint references entry(id) on delete set null,
  channel      text not null,
  dedupe_key   text not null,      -- 抑制窗口的键
  state        text not null check (state in ('sent','suppressed','failed','skipped_quiet')),
  http_status  int,
  error        text,
  attempts     int not null default 1,
  created_at   timestamptz not null default now()
);
create index notif_dedupe_idx on notification_log (dedupe_key, created_at desc);

-- 3.11 AI ------------------------------------------------------------
create table ai_artifact (
  content_hash  text not null,
  task          text not null,     -- summary | translate | tag | near_dup
  model         text not null,
  output        jsonb not null,
  prompt_tokens int, completion_tokens int, cost_micros bigint,
  created_at    timestamptz not null default now(),
  primary key (content_hash, task, model)   -- FR-4.2：同内容不二次计费
);
create table ai_budget (
  day        date primary key,
  provider   text not null,
  calls      int  not null default 0,
  cost_micros bigint not null default 0,
  degraded   boolean not null default false  -- 触达上限后置 true，新任务只存不加工
);

-- 3.12 设置 ----------------------------------------------------------
-- 列名必须是 setting_key/setting_value：`KEY` 与 `VALUE` 在 H2 里是保留字，`create table settings (key ...)` 直接语法报错。
-- PG 侧容忍这两个名字，但两份方言必须逐列一致，所以统一改名（M0 实测，见 §1 落地实测第 3 条）。
create table settings (
  setting_key   text primary key,
  setting_value jsonb not null,
  updated_at    timestamptz not null default now()
);

-- 3.13 聚合再分发（F7）-----------------------------------------------
-- 草案里原本**没有**这一段：M5 开工（2026-10-31）才定表，落进 `V4__repack_schema.sql` 双方言两份。
-- 三条不在草案里、实现里却是硬要求的：`full_content` 缺省 FALSE（摘要才是缺省，不是全文）、
-- `unique (repack_id, feed_id)`（重复加入在库层面就不可能）、审计表**不建外键**（见 §8.6）。
create table repack (
  id          bigint primary key generated always as identity,
  slug        text        not null unique,     -- 公开 URL 的路径段，创建后不可改
  title       text        not null,
  description text,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create table repack_source (
  id           bigint primary key generated always as identity,
  repack_id    bigint    not null references repack (id) on delete cascade,
  feed_id      bigint    not null references feed (id) on delete cascade,
  full_content boolean   not null default false,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  constraint uq_repack_source unique (repack_id, feed_id)
);

create table repack_source_audit (
  id           bigint primary key generated always as identity,
  repack_id    bigint      not null,           -- 无外键，故意
  feed_id      bigint      not null,           -- 无外键，故意
  action       text        not null check (action in ('add','remove','full_content_on','full_content_off')),
  full_content boolean     not null default false,
  created_at   timestamptz not null default now()
);

-- 3.14 变更公告（F1.6）-----------------------------------------------
-- 草案里同样**没有**这一段：2026-11-01 交付时落进 `V5__changelog_schema.sql` 双方言两份。
-- 这一张表是"事实的快照"而不是"文章的外延"：标题/URL/状态全部**抄录**下来，不做 join，
-- 所以文章改名不会改写历史条目、文章删除也不会带走它的变更史（`post_id` 无外键，与 repack_source_audit 同一先例）。
create table content_change (
  id          bigint primary key generated always as identity,
  public_id   uuid          not null unique,     -- feed 条目 ID 用它，见下方约定第 1 条的例外
  post_id     bigint        not null,            -- 无外键，故意
  post_slug   varchar(255)  not null,
  post_title  varchar(512)  not null,            -- 变更发生**当时**的标题
  post_url    varchar(2048) not null,            -- 变更发生**当时**的永久 URL
  action      varchar(16)   not null
              check (action in ('created','updated','status','deleted')),
  from_status varchar(16),                       -- deleted 的 to_status 为 null，created 的 from_status 为 null
  to_status   varchar(16),
  changed_at  timestamptz   not null default now()
);
create index content_change_recent_idx on content_change (id desc);   -- 出口按 id 倒序取前 N 条
create index content_change_post_idx   on content_change (post_id, id desc);  -- 管理面按文章查自己的历史
```

约定：
- 所有对外 feed 的 ID 一律走 `guid`/`slug` 这类**语义稳定值**，不用 `id`/`public_id` 序列化到 feed——数据库迁移或重建不影响 GUID（DoD：[01 §7](./01-requirements.md) 的"FR-1.4/1.5 输出"行；规范依据 [rss-guide.md:616](../rss-guide.md#L616)）。**唯一的例外是变更公告出口**：一篇文章在 `/feed/changelog.{format}` 里天然对应多条记录，永久 URL 在这里不唯一，拿它当条目 ID 会让后一条覆盖前一条（阅读器按 ID 去重），所以那里发的是 `urn:rsshub:change:{public_id}`——它同样不受迁移影响（uuid 是列值，不是自增主键），且 `urn:` 前缀明说了这不是可点击的链接。文章的三个常规出口仍然只用永久 URL。
- `entry` 主键 `(id)` + `published_at` 已按 [02 §9](./02-architecture.md) 预留按月分区接缝。
- `owner_id` 暂不加列：单用户，将来开放时一次性带默认值的迁移比每表留空列干净（与 [02 §9](./02-architecture.md) 的口径一致）。

---

## 4. 抓取实现（`feedfetch`）

### 4.1 参数表（写进 `application.yml`，可被 `settings` 覆盖）

> **2026-11-02 口径校正**：下表前两行的键名按本仓 `application.yml:80-92` 的真实写法（Duration 后缀 `m/h/s`，不是 `-minutes`/`-seconds` 整数列），并把 F10 新增的"每源覆盖"一行补进来。**谁是权威**：代码与配置文件是权威，本节原先的 `fetch.interval-minutes` 是从未落地过的写法。

| 参数 | 默认 | 说明 | 出处 |
|---|---|---|---|
| `rsshub.fetch.interval` | `60m` | 全局轮询基准（**一小时一轮**）；源有 `ttl` 时取 `max(ttl, floor)` | [application.yml:83](../../backend/src/main/resources/application.yml#L83) · [rss-guide.md:504](../rss-guide.md#L504)（`ttl` 单位分钟，消费端不应更短） |
| `feed.fetch_interval_minutes`（每源覆盖） | NULL=不覆盖 | **操作者手动设的这一源的节奏**，优先级高于站点 `ttl`：`FetchPolicy.effectiveInterval(操作者分钟, 站点 ttl)` 取"有覆盖就用覆盖"，但**下限 `min-interval` 永远压得住**（护栏是"别把对端打爆"，覆盖不许把它抬走）；`0` 是"交还站点"的写法 | [FeedFetchService.java:192](../../backend/src/main/java/com/rsshub/feedfetch/FeedFetchService.java#L192) · [FetchPolicy.java:92-99](../../backend/src/main/java/com/rsshub/feedfetch/FetchPolicy.java#L92) |
| `rsshub.fetch.min-interval` | `5m` | 轮询下限（防 `ttl=1` 变成轮询风暴） | [application.yml:84](../../backend/src/main/resources/application.yml#L84) |
| `rsshub.fetch.jitter` | `5m` | 同批内打散，避免整点风暴 | — |
| `rsshub.fetch.connect-timeout` / `read-timeout` | 10s / 30s | | [rss-guide.md:524](../rss-guide.md#L524) |
| `rsshub.fetch.max-bytes` | 10 MiB（`10485760`） | **读满即断**，解析前限制 | 同上 |
| `rsshub.fetch.concurrency` | 6 | 全局并发 | [application.yml:95](../../backend/src/main/resources/application.yml#L95) · NFR 吞吐 |
| `rsshub.fetch.per-host-gap` | `30s`（同域名串行 + 最小间隔） | 礼貌限速 | [application.yml:90](../../backend/src/main/resources/application.yml#L90) |
| `rsshub.fetch.user-agent` | `rss-hub/1.0 (self-hosted; personal reader)` | **实现里没有设计初稿写的"可联系 URL + contact= 邮箱"**：自用系统不对外承担联系义务，UA 只表明是自建阅读器。若要公开抓取，应改回带联系方式的形态（[rss-guide.md:524](../rss-guide.md#L524) 的建议） | [application.yml:96](../../backend/src/main/resources/application.yml#L96) |
| `rsshub.fetch.max-redirects` | 3，且每跳重做 SSRF 校验 | | §9 |
| `rsshub.fetch.backoff-base` | `5m` | 失败退避基数 | [application.yml:92](../../backend/src/main/resources/application.yml#L92) · [rss-guide.md:521](../rss-guide.md#L521) |
| `rsshub.fetch.auth-backoff` | `24h` | 401/403 一类的授权失败按天退避，不做指数风暴 | [application.yml:91](../../backend/src/main/resources/application.yml#L91) |
| `rsshub.fetch.backoff-cap` | `24h` | 指数封顶 | [application.yml:93](../../backend/src/main/resources/application.yml#L93) · 同上 |
| `rsshub.fetch.notfound-gone-days` | `30d` | 连续 404 达到天数才标 `gone` | [application.yml:94](../../backend/src/main/resources/application.yml#L94) · [rss-guide.md:518](../rss-guide.md#L518) |

### 4.2 调度与认领

```java
// Dispatcher：每 15s 触发一批
@Scheduled(fixedDelay = 15_000)
void drain() {
  int free = concurrencyLimiter.availablePermits();
  List<Task> batch = tx.template().queryForList(
    "select * from scheduled_task " +
    "where kind='fetch' and state='pending' and fire_at <= now() " +
    "order by fire_at limit ? for update skip locked", free);
  batch.forEach(t -> claim(t) && submit(t));   // 认领即置 running/claimed_at
}
```
- `FOR UPDATE SKIP LOCKED` 让同一任务不被两个线程/实例重复领取（02 A2；也满足"重启可续"验收项）。
- **M1a 实现口径**：H2 2.4 不支持 `SKIP LOCKED`（§14 O9），所以认领改成"SELECT 候选 → 逐条条件 UPDATE"，而**这条 UPDATE 必须把 `fire_at <= ?` 一起写进 WHERE**——只校 `state='pending'` 时，一个刚被别的 worker 抓完并重排到未来的行会被上一个 worker 的旧候选列表提前认领（实测 6 worker / 24 行重复认领 6 次，见 §1 落地实测第 14 条）。认领崩溃恢复由 `reapStaleRunning(claimedBefore, fireAt)` 负责，预算取 `connect-timeout + read-timeout + 2min`。
- 每个 `feed` 恒有一条 `kind='fetch'` 任务，靠 `dedupe_key=feed:{id}` 保证不重复入队；抓取完成时在**同一事务**里更新 `feed` 缓存字段并写入下一次 `fire_at`。
- 手动"立即刷新"按钮只把 `fire_at` 改成 `now()`，不新建任务（避免刷爆）；且只动 `pending` 行，正在 `running` 的不插队。**F10 补了后半件事**：改完 `fire_at` 还要**唤醒调度器**，否则这一枪要等最多 15 秒的下一拍才响——唤醒必须挂在**事务提交之后**（`TransactionSynchronization.afterCommit`），因为调度器用的是另一条连接，提交前它扫到的还是旧队列（[SubscriptionService.java:196-214](../../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L196)，`FetchWakeupTest` 钉"唤醒发生在提交后而不是提交前"）。`paused`/`gone` 的订阅点它得 **409 `not_schedulable`**，不是静默 202——操作员按下按钮时以为它还活着（[SubscriptionService.java:188-194](../../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L188)）。跑完这一轮之后 `fire_at` 由 `FetchPolicy.effectiveInterval(...)` 重排，回到策略节奏（每源覆盖优先，缺省 60 分钟）。

### 4.3 单次抓取的判定顺序（实现即按此序列写死）

```
0 前置   解析 URL（scheme 必须 http/https）→ SSRF 守卫（§9.2）
1 请求   GET，带 If-None-Match: <etag> / If-Modified-Since: <lastModified>
         Accept: application/rss+xml, application/atom+xml, application/feed+json, application/xml;q=0.9, */*;q=0.1
         User-Agent: <rsshub.fetch.user-agent>（每请求头；JDK HttpClient 没有 builder.userAgent()）
         不发 Accept-Encoding：java.net.http 自己声明并透明解码 gzip，brotli 需额外依赖（§1 第 15 条）
2 边界   累计字节 > maxBytes → 立即断流，记 'size_limit'（不算源站故障，退避 ×0.5，fail_count 不++）
3 判定   304 → 状态成功、fail_count=0、notfound_streak=0、写 next fire_at，结束（etag 缺省时保留原值）
         200 → 进入解析；若 content-type 是 text/html → 走"嗅探 feed 地址"分支并提示换订阅地址
         301/302/307/308 → 跟随（≤3 跳）；仅 301/308 更新 canonical_url，其余只记日志
         410 → state='gone'，任务转 cancelled，界面提示取消订阅
         404 → notfound_streak++，未超阈值按退避重试，超阈值 state='gone'
         429 → 严格读 Retry-After（秒或 HTTP-date），无则按退避；不立即重试（封顶 backoff-cap）
         401/403 → state='error'，last_error='auth_required'，退避拉到 24h，提示补凭证
         5xx / 网络异常 / 超时 → fail_count++，退避 = min(base * 2^fail_count, 24h) + jitter
4 留存   有 body 就落盘（含 text/html 的 200：那是"订阅地址填成网页"的唯一现场证据）→ 写 raw_response
         （sha256、byte_size；entry_count 由解析层回填）；键内容寻址，重复抓取不重复占盘
5 解析   见 §5；解析失败但 HTTP 200 → 标 'parse_error' 并保留原始字节，退避 1h（先重试一次，排除半截响应）
6 落库   见 §5.4 幂等；提交后发 EntryNewEvent（应用事件，异步）
7 收尾   同事务写 feed.etag/last_modified/last_http_status/avg_fetch_ms/last_change_at + 重排 fire_at
```

出处对照：状态码语义 [rss-guide.md:512-524](../rss-guide.md#L512)、`Retry-After`/304/410 的 RFC 归属 [rss-guide.md:526](../rss-guide.md#L526)（429 是 RFC 6585 补充，别写进 9110）、条件请求 [rss-quickref.md:102](../rss-quickref.md#L102)。

### 4.4 自动发现（添加订阅时，FR-2.1）

```
GET 用户贴的 URL
├─ Content-Type 是 feed 类型 → 直接建 feed（rss-quickref.md:88 的类型清单）
└─ 是 HTML →
   1 按文档顺序取 <link rel="alternate" type="application/rss+xml|atom+xml|feed+json">（优先级 json > atom > rss）
   2 没有则试常见路径：/feed /feed.xml /rss /rss.xml /atom.xml /index.xml /feed.json（rss-guide.md:551）
   3 仍没有 → 报告"未找到 feed"，并给出"仍要按 HTML 页轮询"选项（不默认开）
   4 URL 相对地址一律用 <base>/页面 URL 绝对化（rss-guide.md:614：feed 内必须是绝对地址，发现阶段同理）
```

---

## 5. 解析与归一（`feedparse`）

### 5.1 归一模型（消费侧唯一模型，**不得**外泄到渲染层）

```java
record NormalizedFeed(String title, String siteUrl, String feedUrl, String description,
                      String language, String format, Integer ttlMinutes,
                      List<NormalizedEntry> entries) {}

record NormalizedEntry(String guid, String url, String title, String summary,
                       String contentHtml, List<Author> authors, List<String> categories,
                       List<Enclosure> enclosures, Instant publishedAt, Instant updatedAt,
                       String lang, Map<String,String> extras) {}
```

### 5.2 格式嗅探顺序

```
字节 → 去 BOM / 探测真实编码（GBK 声明 UTF-8 是中文站高频错，见 rss-guide.md:611）
     → 首非空标签： <feed → Atom | <rss version=2.0 → RSS2 | <rdf:RDF → RSS1.0
     → '{' → JSON Feed（校验 version 是 1.0 还是 1.1，字段映射见 rss-guide.md:327）
```

`extras` 保留 `dc:date`、`sy:updatePeriod`、`wfw:commentRss` 等未在核心模型里的字段，落 `entry` 的 `jsonb`，不做业务判断（避免"扩展字段驱动逻辑"导致不可移植）。

### 5.3 guid / id 归一规则（最常踩，[rss-quickref.md:133](../rss-quickref.md#L133)）

优先级从高到低，取到即停：
1. RSS `guid`（`isPermaLink="false"` 时仍按字符串用，别解析成 URL）。
2. Atom `atom:id`。
3. JSON Feed `items[].id`（数字要转字符串；`url` 兜底）。
4. `link` / `url`（绝对化后）。
5. 都没有 → `sha256(feed_id + title + published_at)` 并**在健康度页标"ID 不稳定"**，提示这个源可能会重复推送。

写库时 `entry.guid` 存归一值，`(feed_id, guid)` 唯一。**禁止**用标题或正文哈希做首选 ID——改标题就变新文章（[rss-guide.md:616](../rss-guide.md#L616)）。

### 5.4 幂等落库

```
每条 NormalizedEntry：
  content_hash = sha256(规范化后的 title|contentHtml)     # 去空白/BOM、URL 绝对化后计算
  upsert on (feed_id, guid):
    不存在 → insert entry(baseline = feed.isFirstFetch)
    存在且 content_hash 相同 → 什么都不做（不刷 updated_at、不重排未读）
    存在且 content_hash 变化 → 只有当 updatedAt_src >= entry.updated_at_src 才
                              递增 revision、写 entry_revision、更新主行
    源给出更旧的更新时间 → 丢弃并计数（防止源把时间倒挂导致"老文章反复变新"）
```
- 只有 `baseline=false` 且新增的条目才发 `EntryNewEvent` → 规则/AI 只对真实新内容触发（FR-2.3、02 §4）。
- 修订不新增条目：`entry_revision` 是追加表，收件箱仍只显示最新 revision（DoD [01 §7](./01-requirements.md) 的"FR-2.3 幂等"行）。

### 5.5 正文与清洗（FR-2.5）

```
选正文优先级：content:encoded > entry content > description/summary（rss-guide.md:621：别把 HTML 同时塞两处）
清洗白名单：p br ul ol li blockquote pre code h1..h4 a img em strong table thead tbody tr th td
            span del hr s sup sub figure figcaption iframe(仅白名单域：youtube/bilibili 且加 sandbox)
移除：script style link meta form input button，一切 on* 事件属性，javascript:/data: 协议
改写：a/img/iframe 的相对地址 → 绝对（rss-guide.md:614）
      跟踪像素：img[src 含 /open.aspx? 或 1x1 + 已知邮件域名] → 移除（经验规则，非规范）
输出：渲染时统一 rel="noopener noreferrer" + target="_blank"
```
清洗必须在**入库前**做一次、渲染时只信任已清洗内容（避免每次渲染都跑一遍 jsoup 的开销与不一致）。

### 5.6 M1b 实测结论（`feedparse` 已落地，后端 148 个单测全绿）

代码在 `backend/src/main/java/com/rsshub/feedparse/`，容错语料在 `backend/src/test/resources/fixtures/`（rss2 干净样、rss2 脏样、atom、rdf、json 1.1、json 1.0、sy/itunes 共 7 个）。以下是写这一层时**实测**出来、与本文原表述不同或原表述没覆盖的点，全部给出行号；本节末尾是本节纠正掉的说法。

1. **XML 解析器保留标签原样大小写**，所以按 local name 查标签时查询串必须小写化，否则 `pubDate`、`updatePeriod` 这类驼峰查询静默返回空（jsoup `Parser.xmlParser()` 不做 HTML 式的小写归一）。见 [Tags.java:47](../../backend/src/main/java/com/rsshub/feedparse/Tags.java#L47)（children）、[Tags.java:64](../../backend/src/main/java/com/rsshub/feedparse/Tags.java#L64)（descendants）。
2. **同名子节点要全部试完，不能只看第一个**：RSS 频道里 `<atom:link rel="self">` 与 `<link>` 的 local name 都是 `link`，前者文本为空；早退会让 `site_url` 变 null。见 [Tags.java:84](../../backend/src/main/java/com/rsshub/feedparse/Tags.java#L84) 与调用侧 [RssLikeReader.java:27](../../backend/src/main/java/com/rsshub/feedparse/RssLikeReader.java#L27)；语料 [rss2-blog.xml:6](../../backend/src/test/resources/fixtures/rss2-blog.xml#L6)。
3. **相对地址绝对化交给 jsoup Cleaner**（`Safelist.preserveRelativeLinks` 默认 false），实测协议相对地址会被补上 scheme：`//cdn.example.com/x.png` → `https://cdn.example.com/x.png`（[HtmlSanitizer.java:67](../../backend/src/main/java/com/rsshub/feedparse/HtmlSanitizer.java#L67)，断言在 [FeedParserTest.java:217](../../backend/src/test/java/com/rsshub/feedparse/FeedParserTest.java#L217)）。正文里的相对地址按**条目自己的 URL**解析，不是 feed 地址（[EntryFields.java:97](../../backend/src/main/java/com/rsshub/feedparse/EntryFields.java#L97)）。
4. **编码阶梯**：BOM > （HTTP `Content-Type` 声明、XML  prolog 声明）> 猜测列表；且"声明"只有在字节能严格解码时才可信，声明与实际不符时落到猜测而不是写进乱码（[DocumentDecoder.java:46](../../backend/src/main/java/com/rsshub/feedparse/DocumentDecoder.java#L46)）。§5.2 第 536 行那句"探测真实编码"按此实现。
5. **日期**：星期名用正则剥掉、不参与解析（错的星期名不该判死一个日期，RFC 1123 §5.2.14 也要求接收方忽略星期）[FlexibleDates.java:39](../../backend/src/main/java/com/rsshub/feedparse/FlexibleDates.java#L39)、[:107](../../backend/src/main/java/com/rsshub/feedparse/FlexibleDates.java#L107)；纯数字门槛 1995-01-01，低于它当数字不当时间戳 [FlexibleDates.java:73](../../backend/src/main/java/com/rsshub/feedparse/FlexibleDates.java#L73)；只有年月也能解析 [FlexibleDates.java:133](../../backend/src/main/java/com/rsshub/feedparse/FlexibleDates.java#L133)。**三位字母时区里 JDK 认得 JST/GMT/UTC/EST**（JST 按 +09:00），实测 `Sun, 18 Oct 2026 05:00:00 JST` → `2026-10-17T20:00:00Z`（语料 [rdf-blog.xml:33](../../backend/src/test/resources/fixtures/rdf-blog.xml#L33)，断言 [FeedParserTest.java:173](../../backend/src/test/java/com/rsshub/feedparse/FeedParserTest.java#L173)）；认不出的时区返回 null，条目照常入库。
6. **`sy:updatePeriod` 不能用"去掉 ly"归一**（`daily` 会变成 `dai`），要显式列举：[FeedFields.java:21](../../backend/src/main/java/com/rsshub/feedparse/FeedFields.java#L21)。官方口径只列 hourly/daily/weekly/monthly/yearly 五个副词、`updateFrequency` 缺省按 1（[FeedFields.java:72](../../backend/src/main/java/com/rsshub/feedparse/FeedFields.java#L72)）；名词写法与 minute/minutely 属**经验容错，非规范**。
7. **ID 阶梯**与 §5.3 一致；第 5 级哈希里用**订阅 URL** 代替 `feed_id`，因为解析层拿不到行身份（[EntryFields.java:51](../../backend/src/main/java/com/rsshub/feedparse/EntryFields.java#L51)）。
8. **作者有两种顺序**：RSS 2.0 官方例子是 `lawyer@boyer.net (Lawyer Boyer)`（邮箱在前、括号是名字），`dc:creator` 的真实语料里出现 `A Bot (robot@example.com)`（名字在前）。所以哪半是邮箱由 `@` 判定，不由位置判定：[EntryFields.java:189](../../backend/src/main/java/com/rsshub/feedparse/EntryFields.java#L189)、[:211](../../backend/src/main/java/com/rsshub/feedparse/EntryFields.java#L211)。`author`/`contributor`/`dc:creator`/`byline` 四类一并收集 [EntryFields.java:151](../../backend/src/main/java/com/rsshub/feedparse/EntryFields.java#L151)。
9. **分类**：`<category term>` 优先于元素文本、逗号拆分、`dc:subject` 与 `media:keyword` 视为同义（[EntryFields.java:223](../../backend/src/main/java/com/rsshub/feedparse/EntryFields.java#L223)，语料 [rdf-blog.xml:26](../../backend/src/test/resources/fixtures/rdf-blog.xml#L26)）。
10. **`extras` 只收带命名空间前缀的元素**，32 条上限、单条 300 字截断；已映射进核心模型的前缀字段排除在外，但 **`itunes:duration` 必须保留**——条目没有 enclosure 时它是时长唯一出口（[EntryFields.java:330](../../backend/src/main/java/com/rsshub/feedparse/EntryFields.java#L330)、[:350](../../backend/src/main/java/com/rsshub/feedparse/EntryFields.java#L350)）。
11. **JSON Feed 不做版本分支**：1.0 的 `author` 与 1.1 的 `authors`、单对象与数组两种形状都认（[JsonFeedReader.java:106](../../backend/src/main/java/com/rsshub/feedparse/JsonFeedReader.java#L106)）；`version` 只用来确认"这是 JSON Feed"（[:37](../../backend/src/main/java/com/rsshub/feedparse/JsonFeedReader.java#L37)）。裸 `content` 键两版规范都没有，仍然读，但**按 HTML 处理不转义**（[:71](../../backend/src/main/java/com/rsshub/feedparse/JsonFeedReader.java#L71)）。
12. **Jackson 3 的 `JsonNode.asText()` 已过时**，数字转字符串用 `asString()`：[JsonFeedReader.java:208](../../backend/src/main/java/com/rsshub/feedparse/JsonFeedReader.java#L208)（编译期 `-Dmaven.compiler.showDeprecation=true` 实测报"已过时"）。

本节纠正掉的说法：

| 原说法 | 正确说法 | 出处 |
|---|---|---|
| §5.2 第 541 行"`extras` 保留 `dc:date`、`sy:updatePeriod`" | 已映射到核心模型的前缀字段不进 `extras`；`itunes:duration` 反而必须留 | [EntryFields.java:350](../../backend/src/main/java/com/rsshub/feedparse/EntryFields.java#L350) |
| "三位字母时区缩写解析不了，日期只能丢" | JDK 遗留短名表能解析 JST（+09:00），GMT/UTC/EST 同理 | 实测 [FeedParserTest.java:173](../../backend/src/test/java/com/rsshub/feedparse/FeedParserTest.java#L173) |
| "JSON Feed 1.0 允许裸 `content`（`_html`/`_text` 拆分之前）" | 1.0 与 1.1 都没有裸 `content`；保留只是经验容错 | https://jsonfeed.org/version/1 、https://jsonfeed.org/version/1.1 |
| "`sy:updatePeriod` 去掉 `ly` 就是周期名" | `daily` → `dai`，必须显式列举五个官方值 | [FeedFields.java:21](../../backend/src/main/java/com/rsshub/feedparse/FeedFields.java#L21)、https://web.resource.org/rss/1.0/modules/syndication/ |
| "RSS `<author>` 括号里是邮箱" | 规范例子是 `邮箱 (人名)`；`dc:creator` 语料里才是 `人名 (邮箱)` | https://www.rssboard.org/rss-specification |

---

## 6. 渲染与发布（`feedrender`）

### 6.1 FeedSource SPI（六种发布形态共用一个渲染层）

```java
public interface FeedSource {                        // 只读，禁止写库（02 §3 规则 3）
  FeedMeta meta(FeedKey key);                       // title/link/description/language/ttl/image/self
  List<FeedItem> items(FeedKey key, int limit);     // 新在前（rss-guide.md:618）
  Instant fingerprint(FeedKey key);                 // 用于 ETag：max(updated_at) + count + 内容指纹
}
```
实现：`PostFeedSource`（F1 全站）、`TagFeedSource`/`CategoryFeedSource`（F1 分类源，三者共用 `AbstractPostFeedSource`）、`EventFeedSource`（F3）、`ShowFeedSource`（F6，**M4 未创建**）、`RepackFeedSource`（F7）、`ChangeLogFeedSource`（F1.6，2026-11-01 交付）。

`FeedKey = {scope, slug, format}`；例如 `blog/rss`、`tag/observability/atom`、`events/rss`、`podcast/myshow/rss`。**2026-11-01 的口径补一句**：`FeedKey` 的构造器把 `SITE` 与 `CHANGELOG` 列为"一站一条"的 scope（`SINGLETON_OUTLETS`），传 slug 直接 `IllegalArgumentException`——不是省事，而是这两个出口各只有一份，多余的 slug 会让缓存键变成 `changelog/changelog` 这种自指形状。`FeedScope` 的枚举值仍是"一个 scope 一个 `FeedSource` bean"，重复 scope 在启动期就失败（`FeedSourceRegistry` 用 `EnumMap` 收）。

### 6.2 路由与 URL 形态（设计成十年不变）

```
公开出口（全部匿名可读，无 session）：
  GET /feed.{format}                       全站          format ∈ rss | atom | json
  GET /tag/{slug}.{format}                 标签源
  GET /category/{slug}.{format}            分类源
  GET /feed/events.{format}                事件源（FR-3.4）
  GET /feed/changelog.{format}             变更公告（FR-1.6，2026-11-01 已交付，见 §8.7）
  GET /repack/{slug}.{format}              聚合再分发源（FR-7.1，M5 已交付，见 §8.6）
  GET /podcast/{showSlug}/feed.xml         播客源（仅 RSS2.0，路径创建后不可变）—— M4，**未实现**
  GET /podcast/{showSlug}/episode/{guid}   单集页（HTML，含 rel=alternate 指向节目源）—— M4，**未实现**
  GET /blog/{slug}                         文章页（Thymeleaf SSR，注入发现标签）
  GET /api/…                               管理端（需登录/令牌）
```
- 每个 HTML 页 `<head>` 注入 `rel=alternate`（多格式 + 分类源），示例见 [rss-guide.md:536-549](../rss-guide.md#L536)；**页面 URL 与 feed URL 不能相同**（[rss-guide.md:548](../rss-guide.md#L548)），所以上面用 `/blog/{slug}` 与 `/feed.rss` 分开。
- 每个 feed 都写 `atom:link rel="self"`（RSS 2.0 里是 Profile 推荐，出处 [rss-guide.md:558](../rss-guide.md#L558)）；播客源迁移期同时输出 `itunes:new-feed-url`。
- **`/feed/changelog.{format}` 是字面量段，会赢过 `/feed/{slug}.{format}`**（Spring 的 `PathPattern` 比较器给字面量段更高得分，`PublicFeedController` 里两条映射的先后顺序因此不参与裁决）。实测代价写在用例里：一条**真的叫 `changelog`** 的事件流会被这个出口吞掉——`/feed/changelog.rss` 返回变更公告，`/feed/builds.rss` 仍是那条流（TC-CHG-13 断言的就是这一对）。这不是缺陷而是短 URL 的价格，但**运营时要记得**：给事件流起名请避开 `changelog`。反方向的负例也测了：`/feed/changelog.opml` → 404 `unknown_format`，说明命中的是出口而不是兜底。
- 公开出口的口径修正历史：需求原文 FR-1.2 写 `/feed/rss`、FR-1.6 写 `/feed/changelog.xml`，两处都按本节的**后缀式**实现（`format ∈ rss|atom|json`），登记见 [test-plan 13 节 D-01](../test-plan.md) 与 [01 §4 FR-1.6 行](./01-requirements.md)。

### 6.3 三个 writer 的输出契约

| 项 | RSS 2.0 | Atom 1.0 | JSON Feed 1.1 |
|---|---|---|---|
| Content-Type | `application/rss+xml;charset=UTF-8` | `application/atom+xml;charset=UTF-8` | `application/feed+json;charset=UTF-8` |
| 根/必填 | `<rss version="2.0">` + channel 的 `title/link/description` | `<feed xmlns=…Atom>` + 各恰好一个 `id/title/updated` | `version/title/items` |
| 条目 ID | `guid`（永久 URL） | `id`（永久 URL 或 tag URI） | `id`（字符串） |
| 日期 | RFC 822 语法、四位年、`+0800` 或大写 `Z` | RFC 3339，`T`/`Z` 大写、带偏移 | RFC 3339 |
| 正文 | `description` 摘要 + `content:encoded` CDATA 全文 | `content` | `content_text` + `content_html` |
| 附件 | `enclosure url length type` | `link rel="enclosure"` | `attachments[]` |
| 出处 | `source url=`（F7 必带，M5 已按此行落进三个 writer：`Rss2Writer.java:108-112`、`AtomWriter.java:92-99`、`JsonFeedWriter.java:64,86-92`） | `source` 元素 | `external_url`（自定义字段 `rss_hub_source`） |
| 校验硬约束 | item 至少 `title` 或 `description` 之一 | 无 `content` 必须有 `rel=alternate` link；`content` 带 `src` 或 Base64 非 text/XML 必须有 `summary` | `items[].id` 必填；`attachments[].url/mime_type` 必填 |

必填项与约束的原文核对：[rss-quickref.md:21](../rss-quickref.md#L21)、[rss-guide.md:267](../rss-guide.md#L267)、[rss-guide.md:327](../rss-guide.md#L327)、[rss-guide.md:175](../rss-guide.md#L175)。
`application/rss+xml` 与 `application/feed+json` **未在 IANA 注册**（[rss-quickref.md:88](../rss-quickref.md#L88)），实现里按事实标准值输出，并在 README 说明这点——别在自家文档里写"已注册"。

writer 输出规则（违反任一条都算 bug，均有单测）：
1. XML 声明从字节起始，无 BOM、无前导空行/注释（[rss-guide.md:610](../rss-guide.md#L610)）。
2. 所有文本节点走统一转义器：`&`→`&amp;`、`<`→`&lt;`；URL 参数里的裸 `&` 是高频致命错（[rss-guide.md:612](../rss-guide.md#L612)）。
3. 命名空间前缀 `atom/content/dc/georss/itunes/media` 全部在根元素声明，URI 从 [rss-quickref.md:143](../rss-quickref.md#L143) 的复制块取常量（GeoRSS 无尾斜杠）。
4. HTML 正文包 CDATA，且 CDATA 内不含 `]]>`（转义拆段处理）（[rss-guide.md:613](../rss-guide.md#L613)）。
5. 只输出最近 `feed.item-limit`（默认 50；播客源输出全量）（[rss-guide.md:620](../rss-guide.md#L620)）。
6. 服务端异常宁可 5xx，也不 200 + HTML 错误页（[rss-guide.md:619](../rss-guide.md#L619)）。
7. JSON Feed 单文件目标 ≤ 100KB：超限时正文降级为 `summary + 原文回链`。

### 6.4 ETag / 304 与缓存头

```java
// 短路在渲染之前，内容不变零成本
String etag = "W/\"" + sha256(fingerprint).substring(0,16) + "\"";   // 指纹 = max(updated_at) + count + 条目 id 集合哈希
if (etag.equals(ifNoneMatch)) return ResponseEntity.status(NOT_MODIFIED).eTag(etag).build();
byte[] body = writer.render(source);
return ResponseEntity.ok()
   .contentType(mt)                    // §6.3
   .eTag(etag)
   .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePrivate().mustRevalidate())
   .lastModified(maxUpdatedAt)
   .body(body);
```
- `ttl` 与 `Cache-Control` 语义要对齐（`ttl=60` 分钟 vs `max-age=60` 秒不一致是有意的：`max-age` 只管 CDN 边缘，`ttl` 是告诉订阅者轮询间隔）。
- **不要把 gzip 后的字节数写进 `enclosure length`**（播客硬性，[rss-guide.md:507](../rss-guide.md#L507)）。
- 公开 feed 的 CDN 缓存放长（如 5 分钟），管理端 `/api` 全加 `no-store`。

---

## 7. 播客实现（`podcast`，M4）

### 7.1 音频链路（音频字节永不进 JVM）

```
上传：浏览器 → POST /api/podcasts/{show}/episodes/presign → 返回 {storageKey, uploadUrl}
      浏览器直传 MinIO/S3（presigned PUT，限 content-length ≤ 500MB）
      上传完 POST /api/.../complete → 服务端 headObject 取真实 byte_size/content_type
      → 异步 probe（ffprobe 取 duration；封面取宽高）→ 写 media_asset
播放：Apple/客户端 → GET /podcast/{show}/feed.xml 里的 enclosure url
      = https://cdn.example.com/podcast/{show}/{guid}.mp3（presigned 或公开桶直链）
      Range/206、HEAD、无 Referer 限制、无重定向由存储/CDN 保证
```
依据（Apple 硬性清单，逐条对应 [rss-guide.md:412](../rss-guide.md#L412) 与 [rss-quickref.md:164](../rss-quickref.md#L164) 起的播客速查，封面尺寸/无 alpha 的具体判据在 [rss-quickref.md:171](../rss-quickref.md#L171)）：`enclosure` 三要素必填、`length` 等于真实字节、支持 byte-range 与 HEAD、无 Referer 限制、GUID 永不变、`itunes:explicit` 只允许 `true/false`、封面正方形 1400–3000px、PNG/JPG、无 alpha 通道。

> 官方**未公布** feed 大小与条目数上限（"512KB 封面 / 8MB feed / 125–300 集"这类数字不要写进代码常量）——见 [rss-guide.md:1012 起第 19 行勘误](../rss-guide.md#L1012)。

### 7.2 合规自检（FR-6.4）

`GET /api/podcasts/{show}/compliance` 返回清单式结论（`PASS/WARN/FAIL + 原因`），逐项检查：

| 检查 | 判据 |
|---|---|
| 必填 channel 字段 | `title/description/language/itunes:explicit/itunes:category/≥1 集` |
| 封面 | 存在、正方形、1400–3000px、PNG/JPG、无 alpha |
| 每集 enclosure | url 绝对 + https、`length>0` 且等于 `headObject` 字节、type 与真实 MIME 一致 |
| 每集音频可达 | 发 HEAD（Range: bytes=0-1）探测，期望 206；期望 `Accept-Ranges: bytes` |
| GUID | 全局唯一、非纯数字自增、历史未变更（对比 `episode.guid` 旧值） |
| 条目顺序 | `episodic` 时新在前 |
| 迁移 | `new_feed_url` 非空时提示"老地址需 301 + 至少 4 周并存"（[rss-guide.md:559](../rss-guide.md#L559)） |
| 输出可校验 | 本地跑严格 XML 解析 + 上述规则；**最终判定仍以 `https://validator.w3.org/feed/` 人工跑一次为准**（自用机器无法访问公网 URL 时无法自动化） |

### 7.3 `itunes:` writer 要点
```
channel：itunes:author（可选但实践必写）、itunes:owner(name+email)、itunes:summary、
        itunes:image href、itunes:category text=…（可嵌套 subcategory）、itunes:explicit、
        itunes:type、itunes:new-feed-url（迁移期）
item   ：itunes:title（与 title 分开，目录搜索友好）、itunes:duration（秒）、
        itunes:episode、itunes:season、itunes:explicit、itunes:episodeType
```
命名空间 URI 用 `http://www.itunes.com/dtds/podcast-1.0.dtd`（照 [rss-quickref.md:143](../rss-quickref.md#L143) 复制块，不手写）。

---

## 8. 规则、推送与 AI

### 8.1 规则匹配（`rules`）

```
触发：EntryNewEvent（仅 baseline=false 的新条目；条目更新重放不重复推）
判定：match jsonb → includeAny（大小写/全词可选）| excludeAny | regex（预编译缓存）
      | feedIds/groups/tags | windowMinutes（只认 published_at 在窗内）
执行：同事务只写"命中事实"到 notification_log(state='pending')，
      投递在独立事务/独立 scheduled_task(kind='deliver') 里做
```
- 规则判定不做外部调用（不查 LLM），纯本地字符串/正则——保证抓取延迟可控、成本可预测。
- 正则来自我自己输入，但仍设 `regex timeout`（`java.util.regex` 无原生超时，用"长度截断 + 简单黑名单"或换 RE2，见 §14 待办）。

### 8.2 抑制与静默（FR-5.3）

```
dedupe_key = rule:{ruleId}:key(命中键)
  命中键粒度可配：perFeed（同一源 N 小时一次） | perContentHash | perTitleNorm
投前查 notification_log 最近 N 小时同 key 是否 sent → 是则记 'suppressed'，不发
quiet_hours 命中 → 'skipped_quiet'（进收件箱标记但不外发）
连续 5 次投递失败 → rule.enabled=false + disabled_reason，并在健康度页红色提示
```

### 8.3 通道（`notify`）

统一 `NotifyChannel` 接口：`send(Notification) -> Result{httpStatus, body}`。适配器：`dingtalk`、`wecom`、`email(SMTP)`、`webhook(通用 + HMAC-SHA256 签名头)`、`markOnly`。
通用 webhook 载荷固定字段：`{ruleId, ruleName, entryId, title, url, summary, feedTitle, matchedKeywords[], publishedAt}`，签名头 `X-Rss-Hub-Signature: sha256=<hmac(body, secret)>`。
重试：`deliver` 任务 `max_attempts=5`，退避 1/5/15/60 分钟；全部失败落 `notification_log.state='failed'`，可在 UI 手动重放。
> 钉钉/企微的加签算法与限流阈值按当日厂商文档复核后写入 `notify` 适配器单测，本文件不复述未验证数字（§1 待复核第 3 条）。

**M3 落地实况与厂商口径**（`backend/src/main/java/com/rsshub/notify/`，数字为 2026-10-18 联网核对结果）：

| 项 | 上表设计 | 实际落地 | 差在哪 / 依据 |
|---|---|---|---|
| 通道 | `dingtalk`、`wecom`、`email(SMTP)`、`webhook`、`markOnly` | `webhook`、`dingtalk`、`wecom`、`markOnly` | **`email` 未实现**：白名单在 `rules/Action.java:23` 的 `CHANNELS`，保存 `channel:"email"` 得到 400 `unknown_channel`。这一台离线机器上没有 SMTP 客户端依赖，加依赖属于依赖决策而不是功能开关；单操作员自用由 webhook/钉钉/企微已覆盖。要补就一次性补 `spring-boot-starter-mail` + 凭据走环境变量 |
| 通用 webhook 签名头 | `X-Rss-Hub-Signature: sha256=<hmac(body, secret)>` | 同头，值是 **HMAC-SHA256 的十六进制**（`sha256=<hex>`），签的是实际发出的字节 | `WebhookChannel.java:12,35`；未另公布 base64 变体，接收方按 hex 校验 |
| 判定成败的口径 | 未区分 | 通用 webhook 只信 HTTP 状态；**只有钉钉/企微走 `VendorVerdict`**（200 但 `errcode!=0` 记为失败） | `DingtalkChannel.java:40`、`WecomChannel.java:34`、`notify/VendorVerdict.java`。厂商限流就是"200 + 错误码"，只看状态会把被丢弃的告警当已送达 |
| 载荷字段名 | `{... matchedKeywords[] ...}` | 投递体是 `matchedKeywords`；规则预览与收件箱标记是 `keywords` | `Notification.webhookBody()`（`Notification.java:41`）对 `RuleService.Hit`。**同名概念、两个字段**，前端别互换 |
| 正文长度 | 未规定 | 按 **UTF-8 字节**截到 2000，且只在整码点处切（`ChatFormat.CONTENT_MAX_BYTES`、`ChatFormatTest`） | 厂商公布的是字节数：企微群机器人页「markdown_v2内容，最长不超过4096个字节，必须是utf8编码」；中文约 3 字节/字，按字符数设限会一次塞进去 6000+ 字节被拒 |
| 限流阈值 | 未规定 | 钉钉：**每个机器人每分钟最多 20 条，超过限流 10 分钟**；企微：**每个群机器人 20 条/分钟** | [钉钉·自定义机器人发送群消息](https://open.dingtalk.com/document/development/custom-robots-send-group-messages)、[企业微信·消息推送配置说明](https://developer.work.weixin.qq.com/document/path/91770)（2026-10-18 取到原文）。本项目的收敛窗口（§8.2 `suppression`）是实际上防限流的那道闸，不是重试阶梯 |
| 钉钉加签 | 未写算法 | `timestamp + "\n" + secret` → HmacSHA256 → Base64 → URL 编码，与 `timestamp`（**毫秒**）一起作查询参数追加；**与服务器时间差超过 1 小时的签名视为非法** | 实现 `DingtalkChannel.signed()`；依据 [钉钉·自定义机器人安全设置](https://open.dingtalk.com/document/dingstart/customize-robot-security-settings) 与 [企业内部机器人群聊校验合法请求](https://open.dingtalk.com/document/group/process-of-signing-a-message-with-a-custom-chatbot)（2026-10-18）。**时钟漂出 1 小时会整条链路报"签名错误"**，排查顺序先看 `date` |
| 企微 text/markdown 的字节上限 | 未规定 | 未取到该页 `text.content`/`markdown.content` 两行的原文，只取到 `markdown_v2` 一行 | 保守按 2000 字节截，见上；**降级为经验值**，不作为厂商承诺 |

### 8.4 AI 管道（`ai`，FR-4）

```
入队条件（硬闸门，缺一不可）：
  settings.ai.enabled = true
  AND (entry.starred OR 命中任一 rule OR feed.ai_policy = 'eager')
  AND ai_budget(day).cost_micros < dailyBudgetMicros
  AND NOT EXISTS ai_artifact(content_hash, task, model)
任务：kind='ai_summary'/'ai_dedupe'，ref_id=entry.id，dedupe_key=ai:{task}:{content_hash}
处理：取正文前 maxInputChars（默认 6000）→ provider 调用 → 解析 JSON → 写 ai_artifact
      → 只写 ai_* 字段（ai_summary/ai_tags/ai_lang），绝不覆盖 title/summary/content（FR-4.4）
近似重复：ai_dedupe 用 (titleNorm + content_hash 前缀 + url host) 先做规则聚类，
          再对候选集调 LLM 判"是否同一事件"，避免 N² 调用（M6 再上 pgvector 向量）
成本护栏：provider 抛错或超预算 → task 标 failed/degraded，界面显示"未加工原因：预算/供应商"
隔离：ai 模块异常不回滚抓取事务（02 §4 第 6 步）；关掉 ai 包，抓取与阅读必须照常（02 §10 验收项）
```

### 8.5 运营增强（M3.5，2026-10-18 用户追加：设置、状态与大屏）

**设置：`settings` 表的四个键**。表本身是 schema-free 键值（`V1__baseline_schema.sql:27-31`，两方言同名同列），所以加参数不加迁移。键名与范围集中在 `SettingsService`（键常量 `settings/SettingsService.java:23-26`，范围表 `IntSpec` `:33-49`）：

| 键 | 范围 | 缺省 | 谁真正读它 |
|---|---|---|---|
| `dashboard.entriesPerFeed` | 1–50 | 20 | `DashboardController.dashboard()` → `EntryFinder.latestPerFeed` |
| `dashboard.refreshSeconds` | 0–600 | 60 | 随大屏响应下发，前端定时器用（0=仅手动） |
| `inbox.pageSize` | 10–200 | 50 | 收件箱列表的**缺省 limit**（`adminapi/EntryController.java:123-126`；上限即该控制器的 `MAX_LIMIT=200`）。**2026-11-01 修正**：SPA 此前在自己的请求里硬发 `limit=50`，等于把这个键变成摆设；现在前端**不发 `limit`**（`web/src/stores/entries.ts:65-83`），键值才是真实读取路径。真 HTTP 侧的对齐断言在 `smoke.sh` 的 F2.4 尾段（改 10 → 无 limit 的请求确实只回 10 条并交出游标；改回 50 → 行数回到改前） |
| `notifications.pageSize` | 10–500 | 100 | 投递日志的缺省 limit（`adminapi/NotificationsController.java:59`） |

- 读取：每请求一次主键查找，**不缓存**（`SettingsService.java:81-89`）——单操作员读写下，缓存带来的陈旧性 bug 比省下的一条 PK 查询贵。
- 写入：update-then-insert（`settings/SettingsRepository.java:35-44`），不用 MERGE/ON CONFLICT——那是方言问题（O11 同理）。
- 校验先于落库：未知键 400 `unknown_setting`、非整数 400 `bad_setting`、越界 400 `setting_out_of_range`（`SettingsService.java:95-115`），拒绝的 PATCH 一行都不写。
- 容错：库里被手工改坏的值，解析失败回缺省、越界夹回边界（`SettingsService.java:118-125`）。
- **刻意不设**的键：抓取节奏与投递超时。它们是 dispatcher 级参数（§4.1/§8.3），从网页改一个正在周期中的调度器需要"重启安全传播"的故事，这个项目没有理由现在建（`SettingsService.java:16-18`）。**F10 落地时沿用了同一个判断**：全局节奏仍是 `rsshub.fetch.interval=60m`（部署级），网页能改的是**某一个源**的节奏——它落在 `feed.fetch_interval_minutes` 这一列而不是 `settings` 表，因为它天生是"每源一行"的事实，塞进键值表要再造一套 `fetch:{feedId}` 命名空间。

**系统状态口径**（`adminapi/SystemStatusController.java:87-137`）：全部来自 JDK MXBean 与 SQL 计数，**不**触碰 actuator 敏感面（§12.2 的原则；本应用也只暴露 `health`）。两条硬约束：

1. `services.fetchEnabled/notifyEnabled` 必须与 dispatcher 的 `@ConditionalOnProperty` 同源（`feedfetch/FetchDispatcher.java:36`、`notify/DeliverDispatcher.java:33`，含"缺省即开"）——状态页说"运行中"而进程根本没装配调度器，是这个页面能撒的最大谎。
2. 数据库不可达时 `up=false`、计数归零，**不是 500**：状态页的故障态正是它存在的意义（`SystemStatusController.java:123-133` 的 catch 分支）。

**大屏查询**（FR-8.1）：每源最新 N 条用**一条语句**——`row_number() OVER (PARTITION BY feed_id ORDER BY coalesce(published_at, fetched_at) DESC, id DESC)`（`feedstore/EntryFinder.java:203-225`），H2 2.x 与 PostgreSQL 同形；不做"每源一查"，因为常驻大屏上那是 N+1 次"半屏加载"的机会。未读角标一条 `GROUP BY`（`EntryFinder.java:228-236`，排除已读与基线，与收件箱口径一致）；卡片头走轻查询（`feedstore/FeedRepository.java:97-120`）——健康度列表那种带三个子查询的 admin 视图对大屏太重。

**大屏的其余四块（F11，2026-11-02）**——同一个 `GET /api/dashboard` 一次带回，聚合仍**只在归属包里读**，`adminapi` 一条 SQL 都不写：

| 块 | 数据来源 | 口径要点 |
|---|---|---|
| KPI 条（7 项） | `DashboardController.stats(...)`（`:141`）从**已经取回的卡片**汇总，不再回表；`arrivedToday/hitsToday/deliveredToday/failedToday` 走 24 小时窗口 | "今日"一律是**滚动 24 小时**而不是日历日：大屏挂在墙上，跨零点的空屏不该被读成"服务坏了" |
| 24 小时直方图 | `EntryFinder.arrivalsByHour(since)` + `NotificationRepository.hitsByHour(since)`，两条 `date_trunc('hour', …)` 分组 | 窗口**锚到整点**（`now.truncatedTo(HOURS).minus(23h)`，`DashboardController.java:120`）并按固定 `WINDOW_HOURS=24` 迭代——早先按 `now.minus(24h)` 锚会产出 24 **或 25** 个桶，且柱子和头条各查一次导致两者能差 1。现在**桶和恒等于** `arrivedToday`/`hitsToday`，这条等式由 `DashboardApiTest#totalsAndHistogramAgree` 与 smoke 的两条 awk 求和断言钉住 |
| 分组轨道 | `folders(...)`（`:175`）在卡片上汇总，空 `name` 是"没分组"那一堆 | 与 KPI 同源同一次查询，不额外打库 |
| 三条侧栏 | `alerts()`（`rules/NotificationRepository`）、`publishedPosts()`（`editorial/PostRepository.recentPublished`）、`recentChanges()`（`changelog`） | 命中轨道在**规则删了、条目删了**之后照样出得来（`notification_log` 两个 FK 都是 `ON DELETE SET NULL`，规则名回落成常量"（规则已删除）"`rules/NotificationRepository.java:286`）；发布轨道**跟随可见性**，`unlisted` 与草稿刻意不上墙；变更轨道本就没有外键，删文章删不掉历史 |
| 卡片增强 | `feedstore/FeedRepository.FeedCard` + 队列的 `next_fetch_at` | `failCount`/`lastError` 让"坏了的源"在墙上就看得见，不必跳健康度页；队列里没有活（暂停/已删）时 `nextFetchAt` **字段缺席**而不是 `null`——Jackson 省略 null，前端 `if (feed.nextFetchAt)` 就是全部判空逻辑 |

> **写 SQL 时的一条方言坑**：H2 2.x 里 `hour` 是保留字，`date_trunc('hour', ts) AS hour` 直接 `BadSqlGrammarException（expected "identifier"）`。别名用 `hour_start`，`GROUP BY` 里重复表达式本身（不引用别名），两条方言才同形。实测发生在 2026-11-02 的 `arrivalsByHour` 首版。

**前端**：`web/src/views/DashboardView.vue`（刷新间隔 0=仅手动；已读条目变淡；空源仍出卡片）、`web/src/views/SettingsView.vue`（状态四面板 + 参数表单，客户端范围校验与后端读同一张表 `web/src/api/settings.ts:12-17`）；路由 `web/src/router/index.ts:19,27`，导航 `web/src/App.vue:29,37`。

### 8.6 聚合再分发（M5，2026-10-31 交付：`repack`）

**一句话**：把若干已订阅源的条目投影成一个公开 feed，条目**保留原出处、原链接、原 ID**，正文默认只给摘要——渲染层（§6.1 SPI、三个 writer、ETag/304、`FeedKey`）**一行没改**，M5 只是往 SPI 里插了第四个 `FeedSource`。这与 M3 加事件出口是同一条路径。

**三张表（`V4__repack_schema.sql`，h2 与 postgresql 各一份、同表同列同序，由 `MigrationDialectAlignmentTest` 盯着）**：

| 表 | 作用 | 关键列与理由 |
|---|---|---|
| `repack` | 集合本身 | `slug` 唯一，它同时是公开 URL 的路径段，所以创建后不可改（同 `event_stream`） |
| `repack_source` | 成员：一个 `feed` 进哪个集合 | `full_content BOOLEAN NOT NULL DEFAULT FALSE`（`:19`）——全文是**例外**不是缺省；`UNIQUE (repack_id, feed_id)` 让重复加入在库层面就不可能；两个外键 `ON DELETE CASCADE`，退订即退出集合 |
| `repack_source_audit` | 加入/移出/开关全文的历史 | `action` 由 CHECK 限定四值 `add`/`remove`/`full_content_on`/`full_content_off`（`:31`）；**故意不建外键**——源站被删、集合被删，这条"谁在什么时候决定转发全文"的记录必须还在（M5 门禁"审计日志"的全部意义） |

**服务（`repack/RepackService.java`）**：`create:77` `update:91` `delete:100` `addFeed:106` `removeFeed:120` `setFullContent:134` `audit:69`；slug 先 `trim().toLowerCase()` 再撞正则（`normalizeSlug:155`，与事件流同一条 `[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?`）。错误码全部走 §10 的 ProblemDetail：`title_required`/`invalid_slug` 400、`repack_exists`/`already_repacked` 409、`feed_not_found`/`not_a_member` 404。`setFullContent` 在标志没变时**不写审计行**（`:134-142`）——否则"反复点一下"就能把审计表灌成噪音。成员的 `full_content` 缺省 FALSE 且插入语句根本不接收该参数（`RepackRepository.java:167-172`），全文只能由创建之后的显式 PATCH 打开。

**出口（`repack/RepackFeedSource.java`）**：`scope():33` 返回 `FeedScope.REPACK`——这个 scope 和它的 URL 早在 M2 建 SPI 时就写进了枚举（`feedrender/FeedScope.java:15,36`），M5 只是补上数据来源；URL 形态就是 §6.2 那行 `/repack/{slug}.{format}`；条目查询是一条 SQL（`RepackRepository.java:51-64`），`join repack_source` + `join feed` 把 `f.canonical_url`/`f.title` 直接带成 `source_url`/`source_title`，`read_state <> 'dismissed'` 让"我明确不要看的条目"不会出现在对外 feed 里，`fetch first ? rows only` 截断（H2 与 PG 同形，缺省 50 条来自 `application.yml:124`）。三条产出规则（`toItem:65`）：

1. **ID 不变**（`stableId:81`）：原 guid 是绝对 URL 就沿用；不是就用条目自己的 permalink；都没有才铸 `urn:rsshub:entry:{public_id}`。已经订阅了源站的读者不该看到两个 ID 的同一条文章（§5.3、[rss-quickref.md:133](../rss-quickref.md#L133)）。
2. **链接不回本站**：`<link>`/`external_url`/`atom:link` 一律是原文 URL，本站只是转发者——这既是 FR-7.1 的定义，也是不给源站添流量的唯一办法。
3. **正文默认缺席**（FR-7.2）：`full_content` 为假时 `contentHtml=null`，只发 `description`；`description` 为空时从全文压出 300 字预览（`feedrender/PlainText.java:19,14`），所以"摘要 + 回链"永远成立。标题另有一条回退链（`title:92`）：条目标题 → 摘要前缀 → 源站标题，因为 V1 允许 `entry.title` 为空而 Atom 要求每条都有 `title`（§6.3 的硬约束），否则出口 500。

**管理面**：`adminapi/RepackController.java:29` 起九个映射（集合 CRUD + `/{slug}/feeds` 三个成员动作 + `/{slug}/audit`），POST 201、DELETE 204，成员写操作返回整个 `Detail`，前端不必二次拉取。`SecurityConfig.java:78` 把 `/repack/*` 放进匿名白名单（出口无 session），管理端仍要会话 + CSRF。

**前端**：`web/src/api/repacks.ts`（视图记录逐条对齐 service record；`outletUrl():69` 只做 `.rss` → `.atom`/`.json` 的后缀替换）、`web/src/views/RepackView.vue`（左列集合表 + 创建/编辑表单，右列成员表 + 审计表；三格式出口 pill；**开全文前 `window.confirm` 提示版权与源站流量义务**——FR-7.2 的"UI 提示"就是这一处）；路由 `web/src/router/index.ts:11,23`，导航"聚合"`web/src/App.vue:32`。**页面未在浏览器复核**（同 O17/O19），证据只有 `npm run build`（严格 TS）+ 与 controller 的逐字段对照。

**验证**：`adminapi/RepackApiTest`（10 用例）覆盖门禁三条——每条可回溯原站（RSS `<source url>`/Atom `<source><id>`/JSON `external_url`）、默认摘要（`full_content` 关时 outlet 无 `content:encoded`）、开关有审计；另加匿名 401/CSRF 403、409/404/400 三类错误、dismissed 不外发、ID 跨格式稳定、空集合仍出 200、审计活得比 cascade 与删除久。真 HTTP 侧 `backend/smoke.sh` 的 M5 段落把同一件事对着 fixture 源站再做一遍（含 `.rss`/`.atom`/`.json` 三格式与 ETag 304）。

### 8.7 变更公告（F1.6，2026-11-01 交付：`changelog`）

**一句话**：把站内文章每次**公开可见**的改动投影成一条公开 feed，读者由此分清"新增了文章""改了已发布的文章""文章撤了"三件事。与 M5 同一条路径：往 §6.1 的 SPI 里再插一个 `FeedSource`，三个 writer、ETag/304、`FeedKey`、渲染层**一行没改**；`/api/**` 的鉴权面也一行没改（读接口落在既有的 `ROLE_OPERATOR` 匹配器里）。唯一的新增写侧是 `editorial` 在既有事务里多交一笔给 `changelog`。

**一张表（`V5__changelog_schema.sql`，h2 与 postgresql 各一份、同表同列同序，仍由 `MigrationDialectAlignmentTest` 盯着）**：

| 列 | 作用与理由 |
|---|---|
| `id BIGINT IDENTITY` | 唯一的排序键：出口和单篇面板都只按 `id DESC` 排（`ChangeLogRepository.java:112,120`），`changed_at` 只用于展示与 ETag 种子。理由写在 `:110`——同一秒里的两次编辑也要分得出先后，自增 id 天然分得出 |
| `public_id UUID UNIQUE`（`:9`） | 出口条目 ID 用它，不用 `id`：见下面"ID 例外" |
| `post_id BIGINT NOT NULL` | **故意不建外键**（文件头 `:6-7`）。文章删掉之后"它存在过、标题是什么、什么时候没的"仍然要说得清；删掉历史等于把变更公告里最重要的一类（retraction）一起删了。口径同 V4 的 `repack_source_audit` |
| `post_slug` / `post_title` / `post_url` | 写入时的快照，不是读时再拼（`:13`）。改名不重写历史，出口里的 `<link>` 在文章消失之后仍指向它曾经的位置 |
| `action`（`:15-16`） | CHECK 限四值 `created`/`updated`/`status`/`deleted`；Java 侧常量 `ChangeLogRepository.java:25-28` |
| `from_status` / `to_status` | 状态流转的两端，管理面与出口的"从 X 到 Y"都读它；`deleted` 行的 `to_status` 为空 |
| 两条索引（`:23-24`） | `(id DESC)` 给全站出口，`(post_id, id DESC)` 给单篇面板——两个查询都只问"最近的"，都从后往前扫 |

**写侧判据：只有公开过的变化才留痕**（`ChangeLogRepository.recordIfPublic:78` → `isPublic:92`）。一条变更行**只在 `from_status` 或 `to_status` 任一侧公开时**写入，公开 = `published`、`unlisted`，或 `scheduled` 且 `published_at <= now`。这条判据是 `PostRepository.VISIBLE` 与 `PublicViewController.isReadable` 的 Java 副本，三处各写一遍是因为其中两处是 SQL 和页面，而出口不能和它们对"什么算公开"有分歧。由此定死的四种行为（都已被断言，不是推测）：

1. 草稿期的一切写**静默**——草稿的创建、编辑、草稿→草稿的状态改动都不进表（TC-CHG-04）。
2. **草稿发布**记的是 `status`（`from=draft, to=published`），标题 `Published: X`；只有**生来公开**的文章（创建时 `status=published`）才记 `created`，标题 `New post: X`（`PostService.java:83`）。
3. `PUT` 的幂等判据在前，公告在后：文档内容没变就直接返回，不写变更行（`PostService.java:99` 的注释即为此）——否则"编辑器保存两次"就制造两条新闻。
4. 状态机拒绝的流转（409 `illegal_transition`）一行都不写（`PostService.java:130` 只在通过 `TRANSITIONS` 后才 `offer`）。

**放弃的那一件事**（`ChangeLogRepository.java:72-77` 的 javadoc 写死了理由）：定时发布到点不会产出变更行——没有写操作发生。它由站内 feed 出口负责播报（那边数的是可见行），变更公告在它被人编辑之前保持沉默。要记它就得给未来时刻预写行并在发布时间变动或退回草稿时作废，正确性风险大于收益。

**出口（`changelog/ChangeLogFeedSource.java`）**：`scope():46` 返回 `FeedScope.CHANGELOG`（枚举项 `feedrender/FeedScope.java:11`，`pathFor` 的 `case CHANGELOG -> "/feed/changelog"` 在 `:37`）；`items():58` 直取 `ChangeLogRepository.list(limit)`；`fingerprint():63` 用 `feedStats(limit):131` 的（行数、最新 `changed_at`、窗口内 id 摘要），所以 ETag 只在真的有新变更时变。四条产出规则：

1. **ID 是 URN**（`id:84`）：`urn:rsshub:change:{public_id}`。这是 §3 约定 #1（"绝不把 `id`/`public_id` 序列化进 feed"）的**唯一例外**，因为一篇文章必然对应多条变更，条目自己的 permalink 不唯一，用它当 ID 会让读者的阅读器把五条新闻折叠成一条。RSS 侧因此输出 `<guid isPermaLink="false">`（`feedrender/Rss2Writer.java:88` 按 `FeedWriter.isUrl(id)` 分流）。
2. **链接指向文章，正文永不进公告**：RSS 的 `<link>`、Atom 的 `<link href>`、JSON Feed 的 `url` 一律是写入时快照 `post_url`（注意是 `url` 不是 M5 那个 `external_url`——公告说的就是本站的一篇文章）；被撤销的文章仍指向它曾经的地址，描述里补一句"不再可访问"（`description:111`）。JSON 条目**没有** `content_html`：变更是一条指针，不是文章的第二个副本（`ChangeLogApiTest` 直接断言这个键缺席）。
3. **标题是人话**（`title:88` + `statusWord:101`）：`New post:` / `Updated:` / `Deleted:` 由 `action` 决定，状态移动是 `<动作词>: <标题>`——`Published` / `Scheduled` / `Taken off the lists` / `Archived` / `Back to draft` 由 `to_status` 决定。**所以发布一篇草稿显示的是 `Published: X` 而不是 `New post: X`**，这条最容易写反（见 [04 §F1.6 偏差 2](./04-roadmap.md)）。
4. **动作名跟着条目走**：RSS/Atom 是 `<category>created</category>`，JSON Feed 是 `tags: ["created"]`——订阅者按动作过滤时两边都有得筛。

**它是"一站一条"的出口**（`feedrender/FeedKey.java:14` 的 `SINGLETON_OUTLETS = Set.of(SITE, CHANGELOG)`）：给这两个 scope 传 slug 会 `IllegalArgumentException`，其余 scope 反之必须传 slug（`:17`）；`FeedKey.changelog()`（`:32`）是唯一的构造入口。由此产生一条必须写死的 URL 冲突（TC-CHG-13）：Spring MVC 的字面量映射 `/feed/changelog.{format}` 优先于捕获映射 `/feed/{slug}.{format}`，所以**一个叫 `changelog` 的事件流会被变更公告遮住**——不是 bug，是路由特异性的必然结果，测试把它钉成"两边都在库里、访问的是变更公告"。

**管理面**：`adminapi/ChangelogController.java:21` 只挂一个 `@GetMapping`（`:38`），参数 `postId`（可选）+ `limit`（缺省 `DEFAULT_LIMIT=50`，夹到 1–500，`:24-25,:41`）。**只读**：`POST` 落 405，匿名落 401（既有的 `/api/**` 匹配器，见 §9.1 授权行），写变更行的唯一路径仍然是 `PostService` 的那些写——不存在"绕过文章直接编历史"的端点。`Entry`（`:28`）逐字段对齐 `ChangeLogRepository.Change`（`:33`）。

**前端**：`web/src/api/changelog.ts`（`ChangeEntry:8` 对齐 controller 的 `Entry`；`CHANGE_ACTION_LABELS:25` 四个中文标签；`changelogApi.list():38`；`changelogOutlets():49` 复用 M5 的 `outletUrl`/`OUTLET_SUFFIXES`，`web/src/api/repacks.ts:2` 那条 import 是唯一的跨页复用）、`web/src/views/PostsView.vue` 的"变更历史"面板（`:304` 起；表头在"本篇"与"全站最新"之间切换，靠 `historyPostId:25` 而不是 `selected`——文章删掉后这条轨迹要留在屏幕上，`:189` 在删除前先把 id 存下来）。三处口径值得单记：

- **出口绝对地址不从 `window.location.origin` 拼**：dev 期 vite 只代理 `/api` 与 `/actuator`，`/feed` 打到 5173 必定 404。`publicOrigin`（`:32`）从后端下发的绝对 `url` 里 `new URL(...).origin` 取，取不到就不渲染 pill（一篇文章都没发布过时如此）。这是 §11 那条"避免两套 URL"约束的一个具体后果。
- 面板的错误行**自己占一行**（`historyError:22,:83`），不与保存/发布结果共用，读历史失败不能盖掉写操作的结果。
- 空态文案直接说"只有公开过的变化才会留痕"——判据是上面那四条，界面不复述就等于让人以为功能坏了。

**公开页发现**：`editorial/PublicViewController.java:88` 把 `/feed/changelog.{rss,atom,json}` 三个 `<link rel="alternate">` 和站点 feed 一起挂上 `/blog`，标题走 `ChangeLogFeedSource.outletTitle()`（`:115` 的 switch 分支）。理由是 FR-1.6 的验收不只看出口能不能读，还看订阅者找不找得到——找不到就等于没做。

**验证**：`changelog/ChangeLogApiTest`（10 个 `@Test`，覆盖 TC-CHG-01…18）管三档：三格式各拉一次且匿名可读、草稿静默与四种写侧判据、撤销活得比 `post` 久；再加改名 ID 不变、`limit` 夹取、no-op 保存与 409 都不追加行、ETag 与站点 feed 的是**不同**的验证子、`/feed/changelog.opml` 404、事件流同名被遮、`/blog` 里能发现出口。真 HTTP 侧 `backend/smoke.sh` 的 F1.6 段落（`# --- F1.6: the changelog outlet and the trail that feeds it ---`）对着真 jar 再做一遍，并补了两条 MockMvc 证不了的东西：405 的实际返回码，以及"删除一篇从未公开过的文章产出 0 行"。**页面未在浏览器复核**（同 O17/O19/O20），前端证据只有 `vue-tsc` 严格模式 + `npm run build` + 与 `ChangelogController.Entry` 的逐字段对照。

---

## 9. 安全实现（`security`）

### 9.1 操作员侧
| 项 | 做法 |
|---|---|
| 登录 | 单账号 + 密码（Argon2id）+ TOTP；失败 5 次锁 15 分钟（内存计数器即可） |
| 会话 | Cookie：`HttpOnly; Secure; SameSite=Lax`；服务器端会话（便于一键踢出） |
| CSRF | 所有 `/api/**` 非 GET 强制 `X-XSRF-TOKEN`（Spring CookieCsrfTokenRepository）；`/feed/**` 是 GET 不受影响 |
| 令牌 | `api_token.token_hash`（不存明文）+ `scope` 限制：`ingest` 只能 POST 事件，`read` 只能 GET |
| 公开域 | `/feed/**`、`/blog/**`、`/podcast/**/feed.xml` 全匿名可读，不建 session（防被爬虫刷爆会话表） |

**落地实况与偏差**（`backend/src/main/java/com/rsshub/security/`，行号为 2026-10-18 M3-b 后实测；改这个包记得回读一遍，插行会集体失效）：

| 项 | 上表设计 | 实际落地 | 差在哪、什么时候补 |
|---|---|---|---|
| 登录 | Argon2id | delegating `{bcrypt}`（`PasswordEncoderFactories.createDelegatingPasswordEncoder()`，`SecurityConfig.java:48-49`） | delegating 前缀让"以后加 `{argon2}`"不需要迁移旧哈希；O7 |
| TOTP | 有 | **已实现**（M3-b）：`TotpService`（RFC 6238 手写，SHA1/6 位/30 秒、前后各 1 步窗口，`TotpService.java:22-26`）；认证顺序=先锁定再口令再动态码（`OperatorAuthenticationProvider.java:106-129`）；管理接口 `POST /api/auth/totp/{enroll,activate,deactivate}`（`TotpController.java:47,67,80`），密钥经 `SecretCipher` 加密后写 `app_user.totp_secret`（`TotpController.java:77`） | enroll/activate 分两步：半途放弃的绑定不落库，否则手机没扫成就把自己的账号锁死；`deactivate` 要口令，是唯一出口。`enroll` 需要 `RSSHUB_SECRET_KEY`，缺了报 400 `secret_key_missing` |
| 登录表单 | 未规定 | 动态码是表单参数 `code`（`OperatorAuthenticationProvider.LoginDetailsSource:30-37`）；失败 401 `invalid_credentials`，**锁定 429 `account_locked` + `Retry-After` 秒级时间戳**（`SecurityConfig.java:107-116`） | 匿名 `/api/auth/me` **不**透露是否启用了 TOTP（`AuthController.java:29-31`），所以登录页把动态码框常驻显示为"选填"，不猜状态 |
| 失败锁定 | 5 次锁 15 分钟 | **已实现**：`LoginAttemptGuard.java:18-19`，内存计数器 | 重启清零、多实例不共享——单进程自用可接受；若上公网或多副本，改成库里计数 |
| 会话 | `HttpOnly; Secure; SameSite=Lax`，服务器端会话 | `RSSHUB_SESSION`，`HttpOnly` + `SameSite=Lax`，**没有 `Secure`**（`application.yml` 的 `server.servlet.session.cookie`） | `Secure` 依赖 TLS，本机是明文 `localhost`，加了就发不出去；上 Caddy/TLS 时一起打开。退出用 `deleteCookies(SESSION_COOKIE_NAME)`（`SecurityConfig.java:123`），常量必须与 yml 里的名字同步，否则删的是不存在的 cookie |
| CSRF | 非 GET 强制 `X-XSRF-TOKEN` | 同，但**取值口径要写死**：cookie 是原始值且非 httpOnly（`withHttpOnlyFalse()`，`SecurityConfig.java:89`），SPA 原样回头；参数路径才是 BREACH 掩码值（`SpaCsrfTokenRequestHandler`，`:157`，装配在 `:61,90`）。另外**登录成功要手工写一次 cookie**（`:101` → `writeCsrfCookie`，`:136`），且带 Bearer 的请求豁免（`ignoringRequestMatchers(HAS_BEARER)`，`:92`） | 见 §1 落地实测第 5/6 条，两处都是只有真 HTTP 才能发现 |
| 授权范围 | `/api/**` 需登录 | 匿名：`/actuator/health`、`/api/auth/{login,logout,me}`；公开域：`/feed.*`、`/feed/*`、`/tag/*`、`/category/*`、`/blog`、`/blog/*`、`/event/*/*`、`/`（`:75-79`）；`/api/ingest/**` 要 `SCOPE_ingest`/`SCOPE_admin`（`:83`）；`/api/**` 要 `ROLE_OPERATOR` 或 `SCOPE_admin`（`:86`）；**其余 `denyAll()`**（`:87`） | `read` 作用域令牌目前只能读 `/api/entries/**`、`/api/feeds/**`（`:84-85`）；M5 的投稿面要单开一条匹配器，别拿 `read` 当万能只读 |
| 机器令牌 | `token_hash` + `scope` | **已实现**：`BearerTokenAuthFilter`（`:94` 挂在 `BasicAuthenticationFilter` 前）+ `ApiTokenService`；只存 SHA-256，明文只在 `POST /api/tokens` 的响应里出现一次（`TokenController.java:33`）；吊销是写 `revoked_at` | 前缀/哈希与脱敏口径见 §9.1 上方约束；UI 侧 `EventsView` 的"新建令牌"是唯一能看到明文的地方 |
| 未认证响应 | — | `HttpStatusEntryPoint(UNAUTHORIZED)`，**401 而不是 302 到登录页**（`SecurityConfig.java:127`） | SPA 的 `fetch()` 拿到 302 后的 HTML 没用；这条要在 §10 的错误契约里长期保持 |

### 9.2 SSRF 守卫（本项目第一风险，DoD 有专门用例）

```java
// 必须在“每一次实际连接前”执行，包括每一跳重定向
SsrfGuard.assertPublic(UriBuilder uri, Allowlist allow) {
  if (!scheme in http/https) reject("scheme")
  host -> 解析全部 A/AAAA（不做"只解析第一个"的偷懒）
  每个地址逐个判：isLoopback / isSiteLocal / isLinkLocal / isAnyLocal /
                   multicast / 100.64.0.0/10 / fc00::/7 / ::1 /
                  169.254.169.254（云元数据）/ 运营商级 NAT 段 → reject
  通过自定义 DnsResolver 把校验后的 IP 固定用于本次连接（消 TOCTOU 重绑定）
  allow 里显式登记的私网前缀才放行（自用内网源需要，且日志记"用户显式授权"）
}
```
配套：
- 下载器（音频/AI 取图）**不得**绕过 `SsrfGuard`；仍遵循"外部 HTTP 只能由 `feedfetch`/`notify`/`ai` 三个包发起"的边界规则。
- 401/403 私有源凭证：只在 HTTPS 下发送，`auth_ref` 指向加密字段（密钥来自环境变量 `RSSHUB_SECRET_KEY`）。
- 日志里 token/Authorization/Cookie 一律脱敏（自定义 logback converter）。
- 上传：presign 前校验扩展名 + MIME + 大小上限；服务端不信任浏览器给的 `content-type`。

---

## 10. API 契约（`adminapi`，给 Vue3）

错误体统一用 RFC 9457 Problem Details（`type/title/status/detail/instance` + 自定义 `code`）。分页统一 `?limit&cursor`（cursor = `(published_at,id)` 的 base64），避免深偏移。

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | — | `application/x-www-form-urlencoded` 的 `username`/`password`（+ M3 的 TOTP）→ 建会话；成功 200 `{username}`，失败 401 ProblemDetail `urn:rsshub:invalid_credentials` |
| POST | `/api/auth/logout` | session + CSRF | 204，并清 `RSSHUB_SESSION` cookie |
| GET | `/api/auth/me` | 匿名可读 | 未登录也返 200 `{authenticated:false}`（不是 401），供前端路由守卫冷启动判断 |
| POST | `/api/ingest/events` | `Bearer ingest` | FR-3.1；`{stream, dedupeKey, title, url, summary, tags, publishedAt}`；幂等 upsert |
| GET | `/api/feeds` | session | 列表 + 健康字段（`state/fail_count/last_http_status/nextFetchAt/lastChangeAt`）。**F10 起每行同时带两对"同一事实的两个主人"**：`title`（站点自报，每轮抓取覆盖）与 `customTitle`（操作者输入，只有操作者改），`ttlMinutes`（站点 `<ttl>`）与 `fetchIntervalMinutes`（操作者要的）；界面显示的那行标题由读取侧 `coalesce(custom_title, title)`，两者都空则回落地址（`feedstore/FeedRepository.java:74-81` 的 `AdminFeed.displayTitle()`） |
| POST | `/api/feeds` | session | `{url, group?}` → 走 §4.4 发现；返回 feed 或候选列表让人选 |
| POST | `/api/feeds/discover` | session | 只解析不加订：`{url}` → `{feedUrl?, candidates[]}`。**F10 的口径**：这一发之前前端**先清空上一次的解析结果**（`FeedsView.vue:49`），解析成功即可**直接把解析出的地址添加为订阅**（单候选自动选中，多候选逐条"添加"），不需要复制粘贴 |
| PATCH | `/api/feeds/{id}` | session | **白名单只有四个键**：`title`、`group`、`state`（只允许 `active/muted/paused`）、`intervalMinutes`（`adminapi/SubscriptionService.java:51,123-154`）。**地址不可改**——补丁里出现任何别的键（含 `url`）都是 400 `unknown_field`，消息直接点名"the address never is"；`""` 是"取消覆盖、交回站点"，`0` 是"节奏交回站点 `ttl`"；越界 400 `bad_interval`（允许 0 或 1–43200 分钟）、超长 400 `bad_title`（≤512，与 DDL 同宽）。改 `paused` 顺手取消队列行，改回活动状态则重新武装（不等下一拍） |
| POST | `/api/feeds/{id}/refresh` | session | "立即抓取"：把该订阅的队列行 `fire_at` 提到当下，**事务提交后**唤醒调度器（§4.2）；活动订阅 202 且当轮就执行，`paused`/`gone` 得 **409 `not_schedulable`**。之后回到策略节奏（该源的 `fetchIntervalMinutes` 优先，否则站点 `ttl`，都缺则全局 `interval=60m`，下限 5m） |
| GET | `/api/feeds/policy` | session | 把两个节奏边界交给界面：`{defaultIntervalMinutes, minIntervalMinutes}`（`FeedController.java:52,60`，来自 `FetchPolicy`，不是前端抄一份常量）——"默认一小时一轮，支持手动设置"这句话在 UI 上的落点 |
| DELETE | `/api/feeds/{id}` | session | 级联删条目（软删改为 `state='gone'` 更稳） |
| GET | `/api/opml` / POST | session | 导出/导入（FR-2.6） |
| GET | `/api/entries` | session | 过滤：`state=all`/`unread`/`read`/`dismissed`/`starred`/`archived`（`EntryController.java:39`，缺省 `unread`）、`feed`、`group`、`q`（**标题/摘要 contains-match，`LIKE … ESCAPE '\'`** —— `EntryFinder.java:324-335`；原稿写的"FTS"属 M6，本机两侧言都还没建全文列）、`since`、`cursor`；**缺省 limit 取 `inbox.pageSize`**（M3.5），显式 `limit` 仍可覆盖。**F2.4 新增两个（2026-11-01）**：`alerted`（FR-2.4"只看关键词命中"）与 `category`（按标签窄化） |
| GET | `/api/entries/counters` | session | 收件箱三个页签的全局计数 `{total,unread,starred,archived}`（`EntryFinder.java:171-185`，一趟 SQL）。**不跟随任何过滤条件**，所以"只看命中"时徽标仍是全量；F2.4 的 60 秒未读轮询打的正是这个端点，不是重读列表 |
| POST | `/api/entries/state` | session | `{ids:[], readState?, starred?, archived?}`；单次最多 500 个 id（M1d 实况） |
| GET | `/api/entries/{id}` | session | 全文（已清洗）+ 修订列表 + AI 产物 |
| GET/POST/PATCH/DELETE | `/api/posts` | session | FR-1.1；发布后 60s 内 feed 可见（靠 `fingerprint` 自然失效） |
| PATCH | `/api/posts/{id}/status` | session | 状态机流转，写 `published_at`（M2 实况：动词是 PATCH 不是 POST，路径不是 `/publish`）；每一次**被接受且公开可见**的流转都会追加一条变更行（§8.7） |
| GET | `/api/changelog` | session | F1.6 变更历史**只读**：`?postId`（缺省=全站）+ `?limit`（缺省 50，夹到 1–500，越界不报错只夹）。`POST` 405——写变更行的唯一路径是 `/api/posts/**` 那几个写，不存在"绕过文章直接编历史"的端点（§8.7） |
| GET/POST/PATCH | `/api/rules` `/api/rules/{id}` | session | FR-5.1/5.2 |
| POST | `/api/rules/{id}/test` | session | FR-5.4 试跑：取最近 200 条返回命中预览（不外发） |
| GET | `/api/notifications` | session | 投递日志 + 失败原因 |
| GET | `/api/podcasts` `/api/podcasts/{show}` | session | CRUD |
| POST | `/api/podcasts/{show}/episodes` | session | 建剧集（先 presign 再 complete） |
| GET | `/api/podcasts/{show}/compliance` | session | §7.2 清单 |
| GET | `/api/health/feeds` | session | 抓取健康度视图数据源（FR-2.7） |
| GET | `/api/ai/usage` | session | 日调用量 + 花费 + 是否降级 |
| GET | `/api/settings` | session | M3.5：四个运行参数的**解析视图**（缺省已应用），无 per-key 读——省一个和页面不同步的端点 |
| PATCH | `/api/settings` | session | M3.5：部分更新 `{key: int}`；未知键 400 `unknown_setting`、非整数 400 `bad_setting`、越界 400 `setting_out_of_range`。**与设计稿的 `GET/PUT /api/settings/{key}` 不同**：键集是 dashboard/inbox/notifications 四个（§8.5），原稿设想的默认 ttl/item-limit/UA/AI 预算属部署配置不入库 |
| GET | `/api/dashboard` | session | M3.5 大屏（FR-8.1）：每源卡片 + 最新 N 条 + 未读角标；`entriesPerFeed`/`refreshSeconds` 随响应下发，改设置无需重载页面。**F11（2026-11-02）同一份响应再加四块**：`stats`（7 项 KPI，"今日"=滚动 24 小时）、`hourly`（**恒 24 个桶**、锚到整点、到达与命中两条，桶和 = `arrivedToday`/`hitsToday`）、`groups`（分组轨道，空名=未分组）、`alerts`/`posts`/`changes`（三条侧栏，口径见 §8.5 的表）；卡片新增 `failCount`/`lastError`/`lastChangeAt`/`nextFetchAt`，队列无活时后者**缺键**。字段仍全部来自归属包的读方法，`adminapi` 不写 SQL |
| GET | `/api/system/status` | session | M3.5 系统状态（FR-9.1）：MXBean + SQL 计数（§8.5 的两条硬约束）；库不可达时 `up=false` 而非 500 |
| GET/POST/PATCH/DELETE | `/api/repacks` `/api/repacks/{slug}` | session | M5 FR-7.1 集合 CRUD：POST 201、DELETE 204；slug 创建后不可改（它是公开 URL）；重名 409 `repack_exists`、非法 slug 400 `invalid_slug` |
| GET | `/api/repacks/{slug}/audit` | session | M5 FR-7.2 审计（最近 50 条）：`add`/`remove`/`full_content_on`/`full_content_off`；行不建外键，集合删了这个端点才 404、历史仍在库里 |
| POST/PATCH/DELETE | `/api/repacks/{slug}/feeds` `/api/repacks/{slug}/feeds/{feedId}` | session | M5 成员：POST `{feedId}` 重复 409 `already_repacked`、未知源 404 `feed_not_found`；PATCH `{fullContent}` 是唯一能开全文的入口（缺省永远是摘要），标志没变不写审计；三种写操作都返回整个 `Detail` |

约定：
- 写操作全部要求 CSRF 头；`/api/ingest/**` 用 Bearer 不受会话影响。
- **CSRF 传输入口径**（M0 定死，前后端与测试都按它写）：`XSRF-TOKEN` cookie 是**非 httpOnly、原始值**；SPA 把它**原样**放进 `X-XSRF-TOKEN` 头。只有走表单字段/查询参数时才需要 BREACH 掩码值（`SpaCsrfTokenRequestHandler` 按"有没有头"来分流）。登录成功会换 token 并立刻重写 cookie，所以前端**每次写操作前重读 cookie**，绝不缓存。
- **空值不出现在 JSON 里**：`spring.jackson.default-property-inclusion: non_null`，可空字段（`title`、`lastHttpStatus`、`lastFetchedAt`…）是**缺键**而不是 `null`。前端 TS 接口必须写成可选属性（M0 的 `FeedSummary` 已如此）。
- `POST /api/ingest/events` 幂等靠 `unique(stream, dedupeKey)` + `content_hash` 相同则直接 200（DoD 幂等测试）。
- 时间字段进出一律 ISO-8601 UTC（前端展示本地化），别在 API 层混用两种格式——这也是 feed 输出必须带偏移的原因（[rss-guide.md:615](../rss-guide.md#L615)）。

---

## 11. 前端（`web/`，Vue3 + Vite + TS）

```
src/
├─ main.ts  router/  stores/  api/(生成的客户端或手写 fetch 层)
├─ views/
│  ├─ InboxView          收件箱：虚拟列表 + j/k/n/s/m 键盘流（FR-2.4）
│  ├─ ReaderPane         原文渲染：已清洗 HTML + 原文回链 + AI 摘要抽屉
│  ├─ FeedsView          订阅管理 + 分组拖拽 + OPML 导入导出
│  ├─ HealthView         抓取健康度：失败排序、状态码、下次重试时间（FR-2.7）
│  ├─ PostsView / PostEditor   发文章：Markdown 编辑、草稿/发布、标签、封面
│  ├─ RulesView / RuleEditor   条件构造器 + 试跑预览
│  ├─ PodcastView        Show/Episode + 上传进度 + 合规清单红绿灯
│  ├─ AiUsageView        用量/预算/降级标记
│  └─ SettingsView       参数、令牌管理
├─ components/  (EntryList, EntryItem, FeedBadge, KeywordHit, TimeAgo, …)
└─ composables/ (useKeyboard, usePolling, useUnreadCount)
```
实现要点：
- **收件箱分页（实况与草案不同，2026-11-01 校正）**：**没有虚拟列表，也没有 `IntersectionObserver` 自动滚动**——一页的条数是设置项 `inbox.pageSize`（缺省 50，上限 `MAX_LIMIT=200`），靠"加载更多"按游标手动追加。前端**不发 `limit`**（`web/src/stores/entries.ts:65-83`），否则后端那个缺省值永远轮不到，FR-9.2 的"设置页不可能改了但没生效"就是空话。
- **状态写不乐观**：草案的"先改 UI，失败回滚并 toast"没照做——`apply()`（`entries.ts:186-199`）成功后**重读列表 + 重读该条目**，因为改完一个标志这条就可能掉出当前过滤（未读→已读会从未读页签消失），本地打补丁只会让 UI 和服务器各说一套话。
- **新条目提示**：60 秒轮一次 `GET /api/entries/counters` 的 `unread`（`composables/useUnreadAlerts.ts:5,47-56`；授权只在点"开启桌面通知"时才申请，被拒就退化成页面上的一行提示），不引 WebSocket（自用不值）。草案写的"轮 `?state=unread&limit=1` 的计数"不对——那个请求返的是行，徽标数字在 counters 端点里。
- 构建产物 `dist/` 由 Caddy 直接服务，`/api/**`、`/feed/**` 反代到后端；开发期 Vite proxy 同路径，避免两套 URL。
  - **当前实况（M2 交付时）**：只有 Vite 代理在跑，`deploy/` 与 Caddy 属后续里程碑；公开页与三个 feed 出口在本机由后端自己直出（`localhost:8080`），前端界面走 `localhost:5173`。上 TLS 时再一起做两件事：Caddy 接管 `dist/` 与反代、会话 cookie 补 `Secure`（见 [test-plan.md §13 D-05/D-06](../test-plan.md)）。
- 管理端与公开页分离：公开文章页是后端 Thymeleaf（A10），Vue 只负责登录后界面，不承担 SEO/爬虫发现职责。

**M0 实际落到的范围**（`web/`，已 `npm run build` 全绿）：`vite.config.ts`（`/api`、`/actuator` 代理，`VITE_API_TARGET` 可覆盖）、`src/api/{http,errors,auth,feeds}.ts`、`src/stores/session.ts`、`src/router/index.ts`（守卫 + `?next=` 回跳）、`src/views/{LoginView,FeedsView}.vue`、`App.vue`（顶栏仅在已登录且非登录页时渲染）。没有 UI 库、没有虚拟列表、没有键盘流——都按里程碑推到 M1。

**上面那棵 views 树是设计期草案，与现状的差值（2026-10-31 实测 `ls web/src/views/ web/src/components/`）**：已交付 12 个视图、11 条路由 + `/login`（`web/src/router/index.ts:19-30`）——`InboxView`、`DashboardView`、`FeedsView`、`PostsView`、`RepackView`（M5，见 §8.6）、`RulesView`、`NotificationsView`、`EventsView`、`SecurityView`、`HealthView`、`SettingsView`、`LoginView`。树里**没有**的两个 `PodcastView`、`AiUsageView` 属 M4/M6，仍未实现；`components/` 只有 `ReaderPane.vue`、`RuleEditor.vue` 两个，`InboxView`/`RulesView` 各引其一。定时器有两处——`DashboardView.vue` 里的 `setInterval`（刷新间隔可在设置里改，0=仅手动）与 F2.4 的未读轮询，其余视图是挂载时拉一次、之后按需刷新，`HealthView.vue:16` 还专门注释了"这里不做轮询"。

**`composables/` 目录是 2026-11-01 随 F2.4 建的**（`ls web/src/composables/`：`useInboxKeys.ts`、`useUnreadAlerts.ts`），草案里那三个名字 `useKeyboard`/`usePolling`/`useUnreadCount` 落地成了两个文件——轮询和未读计数是同一件事的两半，拆开会互相喂状态。**虚拟列表仍然没有**——一页就是设置项那么多条，翻页靠"加载更多"。**这不是 FR-2.4 的要求**：[01 §F2](./01-requirements.md) 那条只承诺过滤、三态、键盘与通知，虚拟列表是设计期为"上千条不卡"选的实现手段，自用规模下没兑现也没成为阻塞；它从 M1 挂到现在，属**草案里未兑现的手段**，别当成需求欠账。键盘流的落地形状是"键表在 composable、动作在 store"（`useInboxKeys.ts:7-14` 只映射到 `entries.selectRelative/selectNextUnread/toggleStarred/toggleRead`），因为 j/k 要走的是服务器返回的顺序，不是组件自己的顺序。

**F1.6 没有新增视图，新增的是 `PostsView` 里的一块**（§8.7）：编辑器表单下方多一个"变更历史"面板——`GET /api/changelog` 的读、四个动作标签、状态迁移的"从 X → 到 Y"、以及三格式出口 pill。它带两条本项目级的口径：① 出口 pill 的绝对地址**从后端下发的文章 `url` 里取 origin**（`new URL(...).origin`），不从 `window.location.origin` 拼——dev 期 vite 只代理 `/api` 与 `/actuator`，`/feed` 打到 5173 必定 404，这正是上面"避免两套 URL"那条约束的具体后果；② 面板的失败态**自己占一行**，不与保存/发布结果共用错误位，读历史失败不能盖掉写操作的结果。

**F10/F11 在前端各落一块**：`FeedsView.vue` 的四处按钮级行为——① 点"解析"**先把上一次的解析结果清掉**再发请求（`:49`），失败的解析也照样清空（`:70`），屏上不会出现"旧候选挂着新地址"；② 解析成功后**直接把解析出的地址加进订阅**，单候选自动选中并高亮，多候选每条后面一个"添加"（`:188-196`）；③ 列表每行**就地可改**标题（`:115`，空串=交回站点）、分组（`:256`）、状态（`:263`）、抓取间隔（`:118-124`，空箱按 0 发"取消覆盖"），**没有**编辑地址的入口；④ "立即抓取"只对**被点的那一行**显示忙态（`:21` 记 `fetching` 的 id，`:136` 发请求，`:293` 文案切"抓取中…"），因为 `POST /refresh` 只说明队列收了活，真正的交换在 worker 上——所以前端**重读列表**而不是本地猜结果。`DashboardView.vue` 按 F11 的五个块渲染：7 张 KPI、`.stem.arrivals`/`.stem.hits` 两色直方图（轴标每 6 桶一个 + 末桶）、分组轨道、三条侧栏（投递轨道 `RouterLink` 到 `/notifications`、发布轨道链到本站文章 `/blog/<slug>`、变更轨道读 `changes`），卡片的页脚写"下次抓取 …"与"上次变化 …"，两行都只在字段存在时出现。

**F12（2026-11-02）把配色收进一个文件，F13（同日）把这一个文件换成"Bootstrap 之上的品牌层"**：F12 当年新增 `web/src/styles/fluent.css`，`:root` 里六组令牌——surface（`--canvas/--surface/--surface-alt/--surface-hover/--chip/--mica`）、描边（`--line/--line-soft/--line-strong`）、墨色（`--ink/--ink-2/--ink-3/--on-accent`）、强调色（`--accent/--accent-hover/--accent-ink/--accent-wash/--accent-line`）、状态色（danger/ok/warn + 各自 wash/line/ink）、几何（`--radius-control: 4px`/`--radius-card: 8px`/`--radius-pill: 999px`/`--control-height: 32px`/`--shadow-card`/`--shadow-flyout`/`--ease`）与字体（`--font-ui` 走 Segoe UI Variable Text，`--font-mono` 走 Cascadia Mono），并把 15 个 `.vue` 里原先散着的 42 个十六进制色值换成 `var(...)`。**F13 之后 `fluent.css` 已删除**（`ls web/src/styles/` 只剩 `theme.css`），因为用户反馈"UI 太难看"——组件层是手写的，一百多个控件的间距/表格/表单/徽标各自为政。迁移后仍然成立的那条验收线是 **`grep -rniE "#[0-9a-f]{3,8}\b" --include=*.vue web/src` 零命中**（本轮实测 0 处），只是"零命中"之后色值落到了 `theme.css` 而不是 `fluent.css`；不成立的是控件几何：`--control-height: 32px` 这个令牌随文件一起删了，现在的高度是 Bootstrap 的 padding × 根字号（`html { font-size: 14px }`，`theme.css:74-76`），所以 24.4 里那次 `getComputedStyle` 回读（输入与按钮高 32px）**只对 F12 的产物成立，不再是现状**。

**F13（2026-11-02）前端的形状**：入口是 `web/src/main.ts:5-7` 三行——`bootstrap/dist/css/bootstrap.min.css` → `bootstrap-icons/font/bootstrap-icons.css` → `./styles/theme.css`，顺序即层叠顺序，品牌层在后。**没有 `import bootstrap`**：`grep -rn "bootstrap/js\|new bootstrap\|data-bs-toggle" web/src --include=*.vue --include=*.ts` 零命中（全 `src` 下唯一命中是 `theme.css:14` 那句解释"为什么不依赖 Popper"的注释），下拉/折叠/toast 全部是 Vue 的 `ref` + Bootstrap 的类名（`App.vue:19-22` 的 `navOpen` 就是导航折叠的状态机，路由切换时自动收起）。品牌桥接靠的是"Bootstrap 的编译产物在**使用时**读 `--bs-*`"这一条：`theme.css:44-69` 把 `--bs-body-*`/`--bs-border-*`/`--bs-link-color`/`--bs-primary`（外加 `--bs-primary-rgb: 0, 103, 192`，`theme.css:60`）指向品牌令牌，`:96-100` 再补 `--bs-primary-bg-subtle/border-subtle/text-emphasis`；而 `--bs-btn-bg` 这类**按组件定义**的变量没有全局入口，所以 `theme.css:102-116` 的 `.btn-primary` 块逐键覆盖（hover/active/disabled 同一处），`.btn-outline-primary`/`.alert-primary`/`.badge.text-bg-primary`/`.progress-bar`/`.page-link`/`.nav-pills`/`.list-group-item.active`/`.form-check-input:checked` 各自同理（`:118-187`）。**这就是"只换 CSS 不换 JS、只改一个文件就换肤"的代价与收益**：不用 Sass 重新编译，也不用跟框架的默认蓝较劲，代价是 `--bs-*` 的键名成为本项目的隐性契约——升级 Bootstrap 主版本要先核这些键还在不在。

- **组件层归 Bootstrap，本项目只剩 14 个 scoped 样式块**：各视图 `.vue` 里的 `<style scoped>` 只保留框架给不了的东西——列宽（`FeedsView.vue:334-339` 的 `.col-wide`、`RepackView.vue:421-427` 的 `.member-cell`、`LoginView.vue:84-90` 的 `.login-card`）、滚动容器（`InboxView.vue:186-221`、`ReaderPane.vue:124-165`、`PostsView.vue:381-450`、`RulesView.vue:139-155`）、服务端 HTML 的 `<table>`/`<pre>` 不溢出（`PostsView.vue:426-449` 的 `.rendered :deep(...)`、`ReaderPane.vue:140-164`）、以及大屏直方图这种没有对应组件的图形（`DashboardView.vue:327-385` 的 `.bars/.slot/.column/.stem`）。跨视图共用的形状全部提到 `theme.css`：`.app-navbar`（`:242-248`，mica + `backdrop-filter`）、`.metric`（`:287-292`）、`.pane-host`/`.pane-scroll`（`:324-331`，Bootstrap 没有 `min-height: 0` 工具类，少了它三栏只会有一栏滚动）、`.sticky-page-head`（`:314-320`）。
- **状态色改用 Bootstrap 的语汇**：徽标不再自己写颜色，而是选 `text-bg-success/danger/secondary/warning/info`——大屏投递轨是 `ALERT_TONES` 那张映射表（`DashboardView.vue:116-122`，未知值在 `:125` 落 `text-bg-secondary`），KPI 是 `metricTone()`（`:129-137`），文章行状态同理。这样做的好处是"颜色"这件事第一次有了可枚举的取值集合，坏处是 `[B]` 类观感断言从此要靠类名而不是 `var()` 值来核。
- **图标**：41 个唯一 `bi-*` 名、50 个位点，逐个对 `bootstrap-icons.css` 的 `.bi-<name>::before` 验过存在（`grep` 口径见 [测试计划 24.6](../test-plan.md)）；图标一律 `aria-hidden="true"`，文字标签留在按钮里，所以读屏不受影响——这轮收尾时实测出**一处漏网**（`InboxView.vue:118` 的"桌面通知已开启"勾形图标），补完再 `grep` 复查为 0 处。
- **顶栏是数据驱动的**：`App.vue:29-41` 的 `tabs` 数组（11 项：收件箱/大屏/订阅管理/文章/聚合/事件源/规则/投递/健康度/安全/设置）+ `v-for` 渲染（`:58-62`）。F12 之前这里是 11 个手写 `<li>`，加一个页签要改模板，也漏过 `聚合` 这条路由（需求文档曾只列十条）。

**F14（2026-11-02，同日）把中性色族整体转成淡蓝，并因此补了 Bootstrap 品牌桥接的三条盲区**：用户的原话是"采用淡蓝色色调重新优化UI界面"；取向沿用 F13 那次 AskUserQuestion 的选择——**强调色 `--accent: #0067c0` 不动**（白字 5.67:1），动的是中性族：画布 `#e9f1f9`、`--surface-alt`/`--surface-hover` `#f3f8fd`/`#dfeaf5`、描边 `#cddceb`/`#dce7f3`/`#b6cbe0`、墨色 `#16212e`/`#4a5e75`/`#546a85`、mica 与阴影改成同色相的蓝（`rgb(15 40 70 / …)`）。每个值都是拿 `.dev-logs/f14-contrast.js` 算过相对亮度与对比度才定下来的：`--ink-3` 从原来的 `#8a8a8a`（画布上约 3.5:1）提到 `#546a85`（白 5.56 / 画布 4.88 / 悬停底 4.56），`--ink-2` 定在 `#4a5e75`（白 6.67 / 画布 5.85），状态徽标的 slate `#5a7188` 白字 5.06:1。**改的过程中撞到四件事——三件在 Bootstrap 的取值路径上，一件根本不在 CSS 里，每一件都是"令牌统一"救不了的**：

- **① `--bs-body-bg` 被画布占用，于是卡片、输入框、弹层与页面同色**。Bootstrap 5.3.8 的 `.form-control` 没有自己的背景变量（`grep -o '\.form-control{[^}]*}' web/node_modules/bootstrap/dist/css/bootstrap.min.css` 里是 `background-color:var(--bs-body-bg)`），`.card` 读 `--bs-card-bg: var(--bs-body-bg)`，`.dropdown-menu` 读 `--bs-dropdown-bg`——所以 F13 那句"浅色画布上叠白色表面"从来没真的成立过，`getComputedStyle` 实测 body、`.card`、`.form-control` 三者全是同一个 `rgb(233,241,249)`。改法是让 `--bs-body-bg` 回到它的本义"表面"（`theme.css:51` 指 `--surface`），页面底色由 `body` 自己画 `--canvas`（`:80`）。
- **② Bootstrap 的背景/文字工具类读的是 `*-rgb` 双生子，不是同名变量**。`.bg-body-tertiary` 用 `rgba(var(--bs-tertiary-bg-rgb), …)`，所以只设 `--bs-tertiary-bg`（F13 设了）对它无效；`.text-bg-secondary` 读 `--bs-secondary-rgb` 而不是 `--bs-secondary-bg`；`.text-body` 读 `--bs-body-color-rgb`。这批桥接落在 `theme.css` 末尾的 F14 块（`:356-367`）。同一族里 `.text-body-secondary`（94 个位点，本控制台用得最多的文字类）读的是**非** -rgb 的 `--bs-secondary-color`，F13 那次恰好指对了——两条相差一个后缀的规则，靠人眼扫 CSS 是扫不出来的，只有把类名逐个 `getComputedStyle` 回读才会露。
- **③ 五条规则把灰阶写成字面量，任何变量都碰不到它们**：`.btn-outline-secondary`（18 个位点，本控制台最常用的按钮）、`.btn-secondary`、`.btn-outline-dark`、disabled 态的 `.btn-link`、`.form-floating` 的禁用标签。找法是 `.dev-logs/f14-grayhunt.js`：在 `bootstrap.min.css` 里搜灰阶十六进制（`#6c757d`/`#212529`/`#dee2e6`/`#f8f9fa`…），把命中的选择器与 15 个 `.vue` 真正用到的类名取交集。处置沿用 `.btn-primary` 的写法——按组件逐键覆盖 `--bs-btn-*`（`theme.css:369-417`），quiet 按钮的常态文字取 `--ink-2`、描边取 `--line-strong`，悬停才填 slate。

**状态色不动**：`success/danger/warning/info` 及其 `text-bg-*`、`alert-*`、`table-*` 变体保留 Bootstrap 的原值——它们是语义，不是品牌；淡蓝中性族之上放一枚琥珀或一枚绿，比把它们也调成蓝更读得出来。**第四件事不在 CSS 里**：`web/index.html:6` 的 `<meta name="theme-color">` 还是 F13 的 `#f3f3f3`——它既不在 `theme.css` 的令牌块内、也不受"`.vue` 色值归零"那条 grep 约束，正好从 F13 的验收线缝里漏出去；改成与 `--canvas` 同值的 `#e9f1f9` 并重新 `npm run build`（`dist/index.html` 已核对带上）。这也是"色值只在一个文件里"这句契约的真实边界：**一个文件 + 框架外的两个色位**（`.vue` 与 `index.html`）。**探针式验收**：登录后不能进（既有约束），于是在 `/login` 的 DOM 里临时注入一段含 13 个代表类（`.btn-*` 五态、`badge.text-bg-*` 四种、`bg-body`/`bg-body-tertiary`、`text-body`/`-secondary`/`-tertiary`）的片段读 `getComputedStyle` 再删掉节点——CSS 是全局的，注入元素与真页面上的解析结果同一条规则。全部值落在蓝族里，无一处 `#6c757d`/`#212529`/`#f8f9fa`。顺带扫出 `theme.css:231-235` 的 `.dropdown-menu, .flyout` 规则**当前没有任何位点使用**（15 个 `.vue` 里 `grep dropdown|flyout` 零命中）：留着不亏（`--shadow-flyout` 仍经 `--bs-box-shadow` 生效），但它是 F13 那句"下拉是 Vue 状态 + Bootstrap 类名"的过度声明，登记为口径修正而非缺陷。

两个 M0 学到的口径：
- **代理目标必须用 `localhost` 而不是 `127.0.0.1`**：Vite 8 的 dev server 实测只听 IPv6/`localhost`，`curl http://127.0.0.1:5173/` 直接连不上（`000`），会把"前端没起来"误判成"后端没起来"。代理的**后端**目标写 `127.0.0.1` 是好的（Tomcat 双栈都听）。回归脚本 `web/verify-dev-proxy.sh` 的 `PROXY_BASE` 默认值按此写。
- **不写 E2E，改写成"重放 SPA 请求序列"的 shell 脚本**：`verify-dev-proxy.sh` 用 curl 精确复刻 `http.ts` 的每一步（GET `/api/auth/me` 拿 cookie → 表单体 + `X-XSRF-TOKEN` 登录 → 断言 cookie 里的 token **确实变了** → 用新 token 打 `/api/feeds` → 不带 token 的 POST 必须 403 → 带 token 的 logout 204 → 之后 `/api/feeds` 回到 401），口令只从环境变量进、不进脚本。它在真实 HTTP + 真实代理上覆盖了浏览器手点的等价路径，**但渲染与交互本身仍未在浏览器里肉眼复核**（见 [04 §M0 验收](./04-roadmap.md) 的标注）。


---

## 12. 配置与可观测

### 12.1 环境变量（`deploy/.env.example`）
```
RSSHUB_DB_URL / RSSHUB_DB_USER / RSSHUB_DB_PASS
RSSHUB_SECRET_KEY                # 加密 auth_ref、totp_secret
RSSHUB_BASE_URL                  # 生成绝对 URL 与 feed self 链接的唯一真源
RSSHUB_ADMIN_USER / RSSHUB_ADMIN_PASSWORD_ARGON2
RSSHUB_FETCH_CONCURRENCY / RSSHUB_FETCH_INTERVAL_MINUTES / RSSHUB_FETCH_MAX_BYTES
RSSHUB_SSRF_ALLOW                # 逗号分隔的私网前缀白名单（默认空）
RSSHUB_S3_ENDPOINT/BUCKET/ACCESS/SECRET   MinIO 或云桶
RSSHUB_LLM_BASE_URL/API_KEY/MODEL / RSSHUB_AI_DAILY_BUDGET_MICROS / RSSHUB_AI_MAX_INPUT_CHARS
RSSHUB_SMTP_* / RSSHUB_DINGTALK_* / RSSHUB_WECOM_* / RSSHUB_WEBHOOK_SECRET
```
规则：`application.yml` 只放非敏感默认值，密钥一律来自环境（NFR 安全行）。`RSSHUB_BASE_URL` 改一次就要重算所有 feed 的 `guid`/`self`——所以文档里写死"定了就别改"（[rss-guide.md:616](../rss-guide.md#L616)）。

**M0 实际用到的变量名与默认值**（`backend/src/main/resources/application.yml`，与上面的清单还不一致，差额见后）：

| 环境变量 | yml 里的键 | M0 默认 | 备注 |
|---|---|---|---|
| `PORT` | `server.port` | 8080 | 冒烟脚本用 18080+ 避让 |
| `DB_URL` | `spring.datasource.url` | `jdbc:h2:file:./data/rss-hub;DB_CLOSE_ON_EXIT=FALSE` | **与计划的 `RSSHUB_DB_URL` 不同名**；切 PG 时只换环境变量，不改 yml |
| `DB_USER` / `DB_PASSWORD` | `spring.datasource.username/password` | `sa` / 空 | 只对本机 H2 安全；上 PG 前必须改名并强制非空 |
| `RSSHUB_OPERATOR_USER` / `RSSHUB_OPERATOR_PASSWORD` | `rsshub.operator.*` | `admin` / **无默认** | 空库且口令为空时 `OperatorBootstrap` 直接拒绝启动，杜绝"默认口令上线" |
| `rsshub.ssrf.allow-private-prefixes` | 同 | `[]` | 目前只能改 yml/启动参数，还没接成环境变量 |

还没接的环境变量（`RSSHUB_BASE_URL`、`RSSHUB_SECRET_KEY`、fetch 三件套、`RSSHUB_SSRF_ALLOW`、S3、LLM、通知类）都随各自里程碑落地；`deploy/.env.example` 因此推迟到 `deploy/` 一起做（§14 O13）。**命名统一动作**（`DB_URL` → `RSSHUB_DB_URL` 一类）见 §14 O10。

M0 的可观测面只有三条，且刻意不加更多：
- Actuator 只暴露 `health`、`show-details: never`（`application.yml:47-56`）——`env`/`configprops`/`heapdump` 会把密钥和内部路径送给任何能连上端口的人。
- 每个请求一个 `traceId`：`RequestIdFilter`（`@Order(-150)`，必须早于 `-100` 的安全链）写进 MDC，日志图案用 `%X{traceId:-}` 打出来，同时回写 `X-Trace-Id` 响应头；**入站同名头一律忽略**，不给人伪造别人日志的机会。实测一个请求的 6 行日志都带同一个 36 字符 id。
- 错误体统一 RFC 9457，problem type 用 `urn:rsshub:<code>`（不编造文档 URL）；`spring.mvc.problemdetails.enabled=true` 保证框架级错误也是 JSON 5xx，不会掉回 200 + HTML。

上面的 §12.2 指标清单**一个都还没埋点**（Micrometer 骨架只到"Actuator 在跑"）；`feed_silence_seconds` 之类要等 `feedfetch` 存在，属于 M1。JSON 日志（logstash-encoder）也没接，当前是带 traceId 的普通图案。

### 12.2 指标（Micrometer → Prometheus；自用也可只看 Actuator）
```
fetch_total{result=ok|not_modified|redirect|error|size_limit}
fetch_duration_seconds          (histogram)
fetch_fail_count{feed}          (gauge, 来自 feed.fail_count)
feed_silence_seconds{feed}      now - last_change_at  ← “静默坏掉”的主指标
entries_ingested_total{feed}
entry_duplicate_rejected_total
render_requests_total{format,cache=hit|miss}
render_duration_seconds{format}
rule_hits_total{rule}
notify_total{channel,state=sent|suppressed|failed}
ai_cost_micros_total{provider,model}   ai_task_total{result=cached|fresh|degraded|failed}
task_queue_depth{kind}  task_oldest_pending_seconds{kind}
```
结构化日志字段固定：`ts level logger msg feed_id entry_id task_id http_status dur_ms trace_id`。
自建服务的自监控：一条"每日体检"规则（自己的 `/api/health/feeds` 生成日报推给自己），连续 24h `feed_silence` 异常就告警。

---

## 13. 测试策略

| 层 | 覆盖 | 关键用例（对应 DoD [01 §7](./01-requirements.md)） |
|---|---|---|
| 单元 | 日期归一、转义器、GUID 优先级、退避计算、规则匹配、抑制键 | 裸 `&` 不产生非法 XML；`isPermaLink="false"` 不误当 URL；`-0500 EST` 之类缩写时区解析失败但不崩 |
| 切片 | writer 输出快照 | 三格式黄金样本逐字节比对；必填项缺失时 writer 抛错而非输出残缺 feed |
| 集成 | 本地假源站驱动 `feedfetch`（`LocalFeedServer`，JDK httpserver，非 WireMock） | **八种响应**：200 新内容 / 200 无新内容 / 304 / 301 / 410 / 429(+Retry-After) / 5xx / 超时 + 非法 XML；断言 `etag`、`fail_count`、`next fire_at`、`canonical_url` 变更 |
| 集成 | ~~Testcontainers PG~~ → 实际用 H2（文件库与内存库两种）跑同一批幂等断言；PG 方言差异只靠 V1–V7 双方言迁移脚本 + `MigrationDialectAlignmentTest` 类断言压住，**真 PG 未跑过**。V6 只建索引（`notif_entry_idx`）、V7 只加两列（`feed.custom_title`、`feed.fetch_interval_minutes`），对齐测试对它们的断言退化成"两个方言解析出的表集合相等"——因为不建表，两边都是空集（`MigrationDialectAlignmentTest.java:35-57`，现 **5 项**）。把它们列进 `MIGRATIONS` 的价值在"**漏改一个方言时文件缺失会立刻炸**" | 同 feed 重复抓 3 次条目数不变；改 `pubDate` 不改 GUID → 同一条目 + 新 revision |
| 集成 | 幂等与并发 | 多线程同时认领同一批任务，无重复落库（`TaskQueueTest#twoWorkersNeverGetTheSameRow`；断言的是"条件更新只有一个 worker 拿到行"，不是设计期的 `SKIP LOCKED`——H2 不支持，见 §14 O9） |
| 容错语料 | `resources/fixtures/*.xml` | 真站的脏 feed（GBK 声明 UTF-8、缺 title、日期乱、混合 ns）解析不抛且降级字段可见 |
| 安全 | 参数化 | `127.0.0.1`、`169.254.169.254`、`10.0.0.5`、`::1`、内网域名、**302 跳内网**、DNS 重绑定样本 → 全拒且有日志 |
| 发布 | 端到端 | 发一篇文章 → 三格式各拉一次 → ETag 命中 304 → 改标题 GUID 不变 → 严格 XML 解析通过（`validator.w3.org/feed` 需公网 URL，故作为**上线后人工一次**的动作写进检查表，不在 CI 里假设可跑） |
| 播客 | 合规自检单测 | `length` 与 headObject 不一致 → FAIL；封面 1200px → FAIL；`explicit=yes` → FAIL |
| 架构 | ArchUnit | `ArchitectureTest` 实测 **7 条**规则（02 §3 边界规则的代码化）：`parsing_does_not_reach_rendering`、`rendering_does_not_reach_parsing`、`only_designated_packages_reach_the_network`、`rendering_never_touches_the_store`、`common_is_the_bottom_layer`、`admin_api_is_a_leaf`、`no_package_cycles` |
| 恢复 | 脚本演练 | 备份 → 干净容器恢复 → 三格式仍能输出且 GUID 一致 |

未自动化、需在检查表里人工执行：Feed Validator 公网校验、Apple/各家目录提交、真实 IM 通道端到端推送（需要账号与外网）。

**M0 已跑绿的测试（2026-10-18 实测计数）**：`./mvnw verify` = 40 个测试全过 —— `SsrfGuardTest` 25（15 条私网/保留地址 + `localhost`/`::1` + 非 HTTP 与畸形 URL + 公网放行 + 白名单是唯一回门 + 解析出的地址要原样交给 fetcher 钉住）、`AuthGateTest` 8、`ArchitectureTest` 7。**截至 2026-10-31 该口径已长成 370 项 / 35 个测试类**（第五轮实录见 [test-plan §21.2](../test-plan.md)）；**2026-11-01 F1.6 之后是 380 项 / 36 个测试类**（`ChangeLogApiTest` 10 项）；**同日 F2.4 之后是 382 项 / 仍 36 个测试类**（`./mvnw -o -B -ntp verify` = `Tests run: 382, Failures: 0, Errors: 0, Skipped: 0` 于 2026-11-01 实测；本轮只往既有类里加方法——`InboxApiTest` 30 → 32，所以类数不动，实录见 [test-plan §23.2](../test-plan.md)）；**2026-11-02 F10/F11 之后是 397 项 / 38 个测试类**（`Tests run: 397, Failures: 0, Errors: 0, Skipped: 0` + BUILD SUCCESS 于 2026-11-02 实测；新增两类 `adminapi/FetchNowServiceTest` 2 项与 `feedfetch/FetchWakeupTest` 2 项，`DashboardApiTest` 5 → 11、`InboxApiTest` 32 → 34、`MigrationDialectAlignmentTest` 4 → 5、`FeedFetchServiceTest` 22 → 23、`EntryIngestServiceTest` 10 → 11，ArchUnit 仍 **7 条**全绿，实录见 [test-plan §24.4](../test-plan.md)）：上表里的单元、切片、集成（假源站八种响应）、幂等与并发、容错语料、安全参数化、发布端到端、架构各行**都已自动化并跑绿**；仍未执行的只剩两行——播客合规自检（M4 未开工，`podcast` 包不存在）与备份→干净容器恢复演练（`deploy/` 整体推迟，O13）。

三条 M0 学到的测试口径：
1. **ArchUnit 的边界规则要主动破坏一次才算数**：临时加一个 `com.rsshub.feedrender` 里依赖 `feedstore` 的类，确认 `rendering_never_touches_the_store` 报 1 处违规后再删除。空包会让规则以 `failed to check any classes` 失败，所以前向守卫（`feedparse`/`feedrender` 尚不存在的方向）三处必须 `.allowEmptyShould(true)`。
2. **MockMvc 证明不了真实 HTTP**：H2 的 `AUTO_SERVER + DB_CLOSE_ON_EXIT` 冲突、登录轮换 CSRF cookie、隐式相对路径被拒（O12 第 ⑤ 条）这几件事都是打真 jar 才暴露的。规则：**安全与会话链路一律要有一份真 HTTP 的冒烟**（`backend/smoke.sh` **205 项**，覆盖 M0–M3.5 + M5 + F1.6 + F2.4 + F10 + F11 + F15；`web/verify-dev-proxy.sh` 另走 vite 代理 9 项），MockMvc 只用于单元级门禁。
3. **`@SpringBootTest` 必须自带内存库**（`jdbc:h2:mem:authgate;DB_CLOSE_DELAY=-1`）与专用口令，绝不能依赖开发机上那个 `./data/rss-hub.mv.db`，否则测试会随本地数据漂移。

---

## 14. 待办与已知技术开放项

| # | 开放项 | 当前处理 | 决定时点 |
|---|---|---|---|
| O1 | ~~Boot 4.x 运行期最低 JDK 未直证~~ | **已核实**：Initializr 对 4.1.1 提供 17/21/25/27，故基线 ≤17；本机 JDK 21.0.2，项目定 Java 21（见 §1 证据） | 已关闭 |
| O2 | `java.util.regex` 无超时（ReDoS） | ~~推后~~ **已落**：模式长度 200 上限（`rules/MatchSpec.java:60-62`）+ 编译校验 + 嵌套量词黑名单（`RuleMatcher.checkPattern`，`rules/RuleMatcher.java:240-251`，命中报 400 `unsafe_regex`）；RE2/joni 仍未引入 | 已关闭（M3）；若真实规则撞到黑名单再评估替换 |
| O3 | pgvector 与 PG 18 兼容版本 | 语义检索推后到 M6，届时核对 | M6 |
| O4 | ~~钉钉/企微签名与限流~~ | **口径已核**（2026-10-18 联网核对，原文与数字见 §8.3 的「M3 落地实况与厂商口径」表）。代码与单测只覆盖能自证的部分：加签串与 `timestamp`/`sign` 参数（`DingtalkChannel.signed()`，`RuleDeliveryTest` 的钉钉用例断言签名进查询串）、"200 但 `errcode!=0` 记为失败"（`VendorVerdict`，`RuleDeliveryTest.aVendorRefusalBehindA200IsNotADelivery`）。**每分钟 20 条与 1 小时时间戳容差没有单测**——那是厂商侧行为，本机测不到，只能靠防抖（§8.2 抑制窗）和时钟健康 | 已关闭（M3）；厂商改版时按 §8.3 的链接复查 |
| O5 | Rome 是否值得留作解析备用 | 先自研；解析失败率 > 1% 时引入对比 | M1 后 |
| O6 | 大规模条目分区/归档（02 §9） | 6 个月后再看实际体量 | M6 后 |
| O7 | 口令哈希 Argon2id 未落地 | **M0 偏离**：delegating `{bcrypt}`（`PasswordEncoderFactories`），前缀格式让"以后补 Argon2"不需要迁移已有行、也不用重设口令；BouncyCastle 依赖未引 | 接真实用户/上公网前；长期只在本机则可评估后决定不做 |
| O8 | ~~TOTP 二次因子未实现~~ | **已关闭（M3-b）**：`TotpService` + 认证链校验 + `POST /api/auth/totp/{enroll,activate,deactivate}` + 前端「安全」页与登录动态码框；登录锁定同期落地（`LoginAttemptGuard`，5 次/15 分钟）。落地口径与文件行号见 §9.1 的实况表 | 已关闭；剩余风险只在"没有第二台设备可核对"——`deactivate` 用口令兜底 |
| O9 | 任务认领的 `FOR UPDATE SKIP LOCKED` 在 H2 上不可用 | M1 需要一条**双方言都能跑**的认领路径：先用乐观声明 `UPDATE scheduled_task SET state='running', locked_by=?, claimed_at=now() WHERE id=? AND state='pending'` 看受影响行数，PG 上再叠加 `SKIP LOCKED` 做批量优化；两种写法都要过"4 线程同抢一批不重复" | M1（写 Dispatcher 当天就要定，别先写死 PG 语法） |
| O10 | 环境变量命名不统一 | 数据源用 `DB_URL/DB_USER/DB_PASSWORD`（Boot 习惯）而本文件 §12.1 写 `RSSHUB_DB_*`；操作员 `RSSHUB_OPERATOR_*` 符合稿。统一动作与 `deploy/.env.example` 一起做 | M2 |
| O11 | 两份方言 DDL 的手工同步 | `db/migration/h2` 与 `db/migration/postgresql` 的 `V1__baseline_schema.sql` 目前靠文件头注释 + 人工逐列对齐（本轮已因此把 `settings.key/value` 改名 `setting_key/setting_value`）。值得加一个读两份文件、比对表名与列名集合的单测 | M1 前，成本很低 |
| O12 | H2 与 PG 的行为差异还没穷举 | 已撞到 5 条：`KEY`/`VALUE` 保留字、`AUTO_SERVER` 与 `DB_CLOSE_ON_EXIT` 互斥、无 `tsvector`（dev 全文检索退化成 LIKE/ILIKE）、**时间戳按微秒向上取整**：亚微秒精度的 `Instant` 写进 H2/PG 会往后跳，于是"到期时间=现在"的任务读回来仍显示未到期、认领不到（`common/JdbcTimes.toSql` 统一 `truncatedTo(ChronoUnit.MICROS)` 才是根治，别在测试里把 `fire_at` 往前挪；M3 的 `deliver` 队列就因此抖过三次）、**隐式相对路径的 JDBC URL 被拒**：`jdbc:h2:file:.dev-logs/…` 报 90011，必须 `./` 前缀或绝对路径（`application.yml:33` 默认值即 `./data/rss-hub` 形式；2026-10-18 第四轮冒烟实测，勘误见 test-plan §17）。M1 涉及日期时区与 `jsonb` 的 SQL 要**按 PG 写、在 H2 上测**，并在 M2 用真实 PG 复跑一遍 | M1 持续，M2 收口 |
| O13 | `deploy/`（compose、Caddy、`.env.example`、备份/恢复脚本） | **按"先不实现 docker"的指示整体推迟**；M0 门禁里依赖它的两项在 [04](./04-roadmap.md) 标为未执行，TLS / 反代 / `pg_dump` 演练一并顺延 | M2 上线前 |
| O14 | CI | 未配（本目录不是 git 仓库，也没有远端）。M0 的等价动作是本地四条：`./mvnw verify`、`npm run build`、`backend/smoke.sh`、`web/verify-dev-proxy.sh` | 建仓当天 |
| O15 | `email` 推送通道缺失 | §8.3 列了它，实现里没有：`rules/Action.java:23` 的白名单会把它判成 400 `unknown_channel`。这台机器离线、classpath 上没有 SMTP 客户端，加依赖是依赖决策不是功能开关 | 真要邮件时一次性补 `spring-boot-starter-mail`，凭据照 §9.1 只走环境变量 |
| O16 | 熔断的"保存即重新启用"靠前端自律 | 后端 `PATCH /api/rules/{id}` **允许**携带 `enabled`，且值不同就真的改标志（`RuleService.update`，`rules/RuleService.java:86-88`）。护栏有两层：编辑器的保存只在创建时发 `enabled`（`web/src/components/RuleEditor.vue` 的 `build()`），恢复只走 `PATCH /{id}/enabled`（`RulesController.java:64-67`，同时清 `disabled_reason`） | 出现第二个写客户端（脚本/CLI）时，应在后端拒绝"经普通 PATCH 重新启用被熔断的规则"，而不是继续依赖 UI 不发字段 |
| O17 | M3-d 新增页面**未在浏览器复核** | 按既定约束不跑浏览器登录自动化。验证方式只有三种：①`npm run build`（`vue-tsc --noEmit` 严格模式 + `noUnusedLocals` 通过）②`web/src/api/*.ts` 逐条对照 controller 的映射方法与路径/参数/记录字段（`RulesController.java:38-79`、`NotificationsController.java:49-73`、`EventStreamController.java:44-70`、`TokenController.java:42-53`、`TotpController.java:47-92`）③可空字段对照 `application.yml:45` 的 `non_null` 包含策略。**渲染效果、点击流、错误提示位置未经人眼确认** | 人工验收时按 `doc/test-plan.md` 第 19 节的 `RUL`/`NTF`/`EVT`/`TOT` 用例走查一遍；发现的偏差回写这一行 |
| O18 | 风险 R3 的"每日体检自推送"从未落地 | [04 §8 R3](./04-roadmap.md) 把它写成 M3 的应对动作，实际 M3 没做：`scheduled_task` 只有 `fetch`/`deliver` 两种 kind（`feedfetch/FetchDispatcher.java:71`、`notify/DeliveryService.java:42`），没有第三种；静默时长只有**逐源**形态 `quietForSeconds`（`adminapi/HealthController.java:43`），要人打开健康度页才看得见——"没人告警=静默坏掉"这条风险目前**没有被任何机制挡住** | M5 一并做最省事（**M5 已于 2026-10-31 交付，没顺手做它**——`scheduled_task` 仍只有 `fetch`/`deliver` 两种 kind）：`deliver` 通道已具备，只缺一个 `digest` kind + 一条内置规则；或者接真实 webhook 后先手动试跑 |
| O19 | M3.5 新增页面（大屏/设置）**未在浏览器复核** | 与 O17 同一约束、同一套替代验证：①`npm run build`（严格 TS + `noUnusedLocals`）②`web/src/api/{settings,dashboard,system}.ts` 逐条对照 controller（`SettingsController.java:25-33`、`DashboardController.java:69-114`（响应记录）+ `:116-139`（`@GetMapping` 与装配）、`SystemStatusController.java:86-87`）③可空字段对照 `non_null` 包含策略。**渲染效果、定时器行为、表单错误提示位置未经人眼确认** | 人工验收时按 `doc/test-plan.md` 第 20 节的 `DSH`/`SET`/`SYS` 用例走查；发现的偏差回写这一行 |
| O20 | M5 新增页面（聚合再分发）**未在浏览器复核** | 同 O17/O19：①`npm run build` 通过②`web/src/api/repacks.ts` 的四个视图记录逐字段对照 `RepackService` 的 `Summary`/`Detail`/`MemberView`/`AuditView`③出口 pill 的 `.rss`→`.atom`/`.json` 只是后缀替换（`outletUrl()`，`web/src/api/repacks.ts:69`），真实可达性由 `smoke.sh` 的 M5 段落在 HTTP 层证明。**"开全文前的 `window.confirm` 是否真的挡住了误操作"属交互，未由人眼确认** | 人工验收时按 `doc/test-plan.md` 第 21 节的 `RPL` 用例走查；发现的偏差回写这一行 |
| O21 | F1.6 新增面板（`PostsView` 的"变更历史"）**未在浏览器复核** | 同 O17/O19/O20 的三路替代验证：①`npm run build`（`vue-tsc --noEmit` 严格模式 + `noUnusedLocals`）通过 ②`web/src/api/changelog.ts` 的 `ChangeEntry` 逐字段对照 `ChangelogController.Entry`，`CHANGE_ACTIONS` 四值对照 `ChangeLogRepository.java:25-28` 与 DDL 的 CHECK ③出口 pill 的绝对地址来自文章 `url` 的 origin（见 §11），可达性由 `smoke.sh` 的 F1.6 段落证明。**"删除文章后面板仍显示那篇的轨迹""草稿期空态文案是否看得见"属交互，未由人眼确认** | 人工验收时按 `doc/test-plan.md` 第 22 节的 `CHG` 用例走查；发现的偏差回写这一行 |
| O22 | 定时发布到点不产出变更行 | 已知取舍，理由写在 `ChangeLogRepository.java:72-77` 的 javadoc 里：到点生效没有写操作，也就没有行；它由站内 feed 出口播报，变更公告在那篇文章被编辑之前保持沉默。要记它得预写未来时刻的行并在发布时间改动或退回草稿时作废 | 若真出现"读者要靠公告发现定时稿"的需求，再评估"发布事件"这一 kind（与 O18 的 `digest` kind 同一套 `scheduled_task` 扩展） |
| O23 | F2.4 的收件箱新面**未在浏览器复核** | 同一约束、同一套替代验证：①`npm run build`（`vue-tsc --noEmit` 严格模式 + `noUnusedLocals`）通过 ②`web/src/api/entries.ts` 的 `EntryQuery` 逐字段对照 `EntryController.list` 的 `@RequestParam`（`state/feed/group/q/alerted/category/since/cursor/limit`，`EntryController.java:98-106`），`EntryListItem` 对照 `EntryView`（含 `categories`、`alertedBy`）③两个过滤的真 HTTP 证据在 `smoke.sh` 的 F2.4 段（15 条）与 `InboxApiTest`（32 项）。**未由人眼确认的是四件事**：j/k/n/s/m 在输入框聚焦时是否真的不抢键、`Notification.requestPermission()` 弹窗被拒后的降级文案、桌面横幅的实际观感、正文标签 chip 的可见性与点选 | 人工验收时按 `doc/test-plan.md` 第 3.5/3.6/3.7 节的 TC-INB-40～63 走查；发现的偏差回写这一行 |
| O24 | 界面观感**登录后仍未在浏览器复核**（F12 提出，F13 迁移后状态更新） | 截图工具在 F12 那一轮返回 `NATIVE_BROWSER_VIEWPORT_UNAVAILABLE`，本轮（F13 收尾）**恢复可用**，于是把匿名能到的那一屏真看了：`/login` 的截图与 `getComputedStyle` 回读一致——卡片 8px 圆角 + `rgba(0,0,0,0.08) 0 1px 3px` 阴影、按钮与输入框 4px 圆角、输入框实测高 **34px**（F12 的 32px 是 `--control-height` 令牌时代的值，那个令牌随 `fluent.css` 一起删了）、主按钮 `rgb(0,103,192)`、根字号 14px、`--bs-primary` 解析为 `#0067c0`、控制台无报错。**这一条当场揪出一个真缺陷**：`--bs-border-radius` 同时驱动控件与卡片，F13 首版把它设成 8px，于是按钮/输入框全成了 8px，与需求 NFR 界面行承诺的"控件 4px / 卡片 8px"不符；改法是全局旋钮回到 `--radius-control`，由 `.card` 自己声明 `--bs-card-border-radius/--bs-card-inner-border-radius`（`web/src/styles/theme.css:66`、`:203-209`）。**仍未覆盖的部分**：登录后的 11 个界面（顶栏 mica 模糊、长列表滚动时 `backdrop-filter` 的帧率、卡片墙密度、收件箱三栏在真实数据下的换行）一个像素都没被人眼看过——按既有约束不再驱动浏览器过登录流，替代验证是 `vue-tsc` 0 错误 + 94 modules 构建 + `grep` 色值/图标/hover 三条机器断言，口径见 [测试计划 24.6](../test-plan.md)。**F14（同日改淡蓝）复测仍只到匿名页**：`/login` 的截图 + 在 DOM 里临时注入 13 个代表类读 `getComputedStyle` 再删除，实测 body `rgb(233,241,249)`、`.card`/`.form-control` `rgb(255,255,255)`、卡片 8px / 控件 4px、控件高 34px、主按钮 `rgb(0,103,192)`、控制台无报错（实录见 [测试计划 24.7](../test-plan.md)），**登录后的 11 屏依旧一个像素都没被人眼看过** | 人工验收时按 `doc/test-plan.md` 第 24.3 节的 `UIF` 用例走查；把观感偏差（不是缺陷）也回写这一行 |
| O25 | `start-dev.sh stop` 杀不掉临时冒烟实例，它会锁住 jar | 本轮实测踩过一次：`bash start-dev.sh stop` 之后 `./mvnw verify` 仍 BUILD FAILURE，报 `Unable to rename 'backend/target/rss-hub-0.0.1-SNAPSHOT.jar' to '…jar.original'`——`start-dev.sh:22-31` 的 `stop()` 只遍历 `for port in 8080 5173`，而 `smoke.sh` 要的是一次性实例（`BASE` 默认 `http://127.0.0.1:18080`，`smoke.sh:15`），根本不在它的表里。根治要么把端口参数化（`stop 18080`），要么 `stop()` 改成按 `rss-hub-0.0.1-SNAPSHOT.jar` 的命令行特征找进程。**当前做法是人工兜**：`netstat -ano | grep :18080` 拿 PID → `taskkill //PID <pid> //F` → 再 verify。这条不是"打包机制坏了"，重跑一次就绿；记它是因为它会伪装成代码问题 | 下次改 `start-dev.sh` 时顺手参数化；不做也不阻塞交付 |

---

## 15. 下一步

里程碑拆分、工时与每阶段脚手架范围：[04-roadmap.md](./04-roadmap.md)
