# 05 · 功能测试计划（细化到按钮）

> 项目：RSS 订阅与发布服务（Spring Boot + Vue3）
> 文档状态：v9（2026-11-03；第二~七轮见 18–23 节，第八轮 F10/F11/F12 见第 24 节（D-04 与 F-05 关闭），第九~十一轮 F13/F14/F15 见 24.6/24.7/24.8，**第十二轮 F16 批量订阅见第 25 节**）
> 依据：[01 需求分析](./design/01-requirements.md) · [02 架构设计](./design/02-architecture.md) · [03 技术设计](./design/03-technical-design.md) · [04 产研计划](./design/04-roadmap.md) · 规范 [rss-guide.md](./rss-guide.md) / [rss-quickref.md](./rss-quickref.md)
> 被测对象：M0–M3.5 + M5 + F1.6 + F2.4 + F10–F15 + F16 已落地代码（后端 **421** 项测试全绿 / 40 个测试类，2026-11-03 实测；第二~十二轮口径分别为 264/343/360/370/380/382/397/397/397/397/397/421，每轮交付前全量复跑）
> **本文每个"控件""端点"都来自实际代码，每条期望都带 `文件:行号`；未实现的 FR 一律进第 12 节标为阻塞，不虚构按钮。**

---

## 0. 使用说明

### 0.1 编号

`TC-<模块>-<两位序号>`。模块码：`AUTH` 登录与会话 · `NAV` 导航 · `INB` 收件箱 · `RDR` 阅读面板 · `SUB` 订阅管理 · `HEA` 健康度 · `PST` 文章编辑 · `PUB` 公开出口 · `FET` 抓取行为 · `API` 接口负例 · `SEC` 安全 · `MIG` 可迁移 · `RUL` 关键词规则 · `NTF` 投递 · `EVT` 事件源 · `TOT` 二次验证（以上四个见第 19 节，M3-d） · `DSH` 大屏 · `SET` 参数 · `SYS` 状态（第 20 节，M3.5） · `RPL` 聚合再分发（第 21 节，M5） · `CHG` 变更公告（第 22 节，F1.6） · `UIF` 界面令牌与外壳（第 24.3 节，F12） · `BLK` 阻塞项。

### 0.2 优先级

| 级 | 含义 | 出口要求 |
|---|---|---|
| P0 | 坏了就不可用，或会伤数据/安全 | 必须 100% 通过 |
| P1 | 主流程可用但信息/体验错误 | 可带条件放行，需登记 |
| P2 | 文案、排序、次要边界 | 记录后择期 |

### 0.3 执行方式标记

| 标记 | 含义 |
|---|---|
| `[自]` | 已被自动化覆盖，"现状"列给出对应测试类，`./mvnw verify` 跑 |
| `[C]` | curl 可跑（无需浏览器），用 `backend/smoke.sh`（222 项，附录 A 为展开参考） —— **本项目主力验证手段** |
| `[B]` | 只能在浏览器人工点（渲染与交互）。**本轮未执行过任何 `[B]` 用例**，见第 16 节 |
| `[机]` | 不开浏览器、也不起服务的机器断言：`grep`/构建产物/`node` 脚本（如 `.dev-logs/f14-contrast2.js`）或单元/集成测试里的一条具体断言。与 `[自]` 的区别是它守的是**源码或产物本身**，不是运行时行为 |

口径来源：[03 §13 第 2 条](./design/03-technical-design.md)"MockMvc 证明不了真实 HTTP……安全与会话链路一律要有一份真 HTTP 的冒烟"；[03 §11](./design/03-technical-design.md)"不写 E2E，改写成重放 SPA 请求序列的 shell 脚本"。

### 0.4 环境

| 项 | 值 | 出处 |
|---|---|---|
| 后端 | `http://127.0.0.1:8080`，Boot 4.1.1 / Java 21 | [application.yml:7](../backend/src/main/resources/application.yml#L7) |
| 前端 dev | `http://localhost:5173/`（Vite 代理 `/api`、`/actuator`） | [start-dev.sh](../start-dev.sh) |
| 数据库 | H2 文件库 `backend/data/rss-hub.mv.db` | [application.yml:33](../backend/src/main/resources/application.yml#L33) |
| 操作员口令 | 只从 `backend/dev.env` 的 `RSSHUB_OPERATOR_PASSWORD` 进入，**不写进脚本或本文** | [application.yml:69-72](../backend/src/main/resources/application.yml#L69) |
| 发布基址 | `RSSHUB_PUBLISH_BASE_URL`（默认 `http://localhost:8080`）。GUID 是永久 URL，定了就别改 | [application.yml:115-117](../backend/src/main/resources/application.yml#L115), [rss-guide.md:616](./rss-guide.md#L616) |
| 抓取开关 | `rsshub.fetch.enabled=false` 关闭调度器，用于隔离测试 | [FetchDispatcher.java:36](../backend/src/main/java/com/rsshub/feedfetch/FetchDispatcher.java#L36) |
| 本机缺的工具 | **`docker`、`jq`、`xmllint` 没有；`python`/`python3` 是 Microsoft Store 的别名占位**——`command -v python` 会返回路径但一执行就是 "Python was not found"，所以别拿它做判据，要拿一次真实执行的输出。替代验法：严格 XML 解析用 JDK `DocumentBuilder`（附录 A 步骤 6，脚本见第 18 节的 StrictXmlCheck），JSON 用 `node -e 'JSON.parse(...)'`，XML 结构断言用 `grep -o` + `wc -l` 数元素，容器化门禁（O13）整体不做 | 2026-11-01 `command -v` + 实跑核对；备注同 [rss-quickref.md §14](./rss-quickref.md) |

### 0.5 测试数据准备（每次执行前先做）

1. **不要用开发库跑功能测试。** 开发机 `./data/rss-hub.mv.db` 会随本地数据漂移 —— 这条口径来自 [03 §13 第 3 条](./design/03-technical-design.md)。
2. 需要"空站点"用例时，用一次性内存库另起进程：`--DB_URL=jdbc:h2:mem:tplan;DB_CLOSE_DELAY=-1`，临时口令从环境变量给（`rsshub.operator.password` 无默认值，空库且口令为空时 `OperatorBootstrap` 直接拒绝启动 —— [application.yml:70-72](../backend/src/main/resources/application.yml#L70)）。
3. 需要"有内容"用例时，另起一份文件库副本，种入：≥3 个订阅（其中 1 个有分组）、≥60 条条目（够触发分页与 `item-limit`）、≥2 篇文章（1 篇 published、1 篇 draft）、1 个多 feed 候选的 HTML 页。
4. **破坏性用例（删除订阅 / 删除文章 / OPML 导入）只在副本库上跑**，见 5.4、6.3。

### 0.6 判定口径（所有负例统一按此判）

- 错误体是 RFC 9457 Problem Details，**断言 `code` 字段而不是消息文本**（`urn:rsshub:<code>`）；`spring.mvc.problemdetails.enabled=true`，[application.yml:27-29](../backend/src/main/resources/application.yml#L27)。
- **可空字段是"缺键"而不是 `null`**：`default-property-inclusion: non_null`（[application.yml:45](../backend/src/main/resources/application.yml#L45)，口径见 [03 §10](./design/03-technical-design.md)）。断言"`lastHttpStatus` 不存在"而不是"等于 null"。
- 未认证一律 **401，绝不 302 到登录页**（[SecurityConfig.java:86-87](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L86)，理由与长期保持要求见 [03 §9.1 表末行](./design/03-technical-design.md)）。
- 服务端异常宁可 5xx，也不 200 + HTML 错误页（[rss-guide.md:619](./rss-guide.md#L619)）。

---

## 1. `AUTH` 登录、会话与登出

### 1.1 控件清单（登录页全部可见控件）

| 控件 | 位置 | 类型 | 关键行为 |
|---|---|---|---|
| 用户名 | [LoginView.vue:54](../web/src/views/LoginView.vue#L54) | `<input required autocomplete=username>` | `v-model.trim`，空值由浏览器原生 required 拦下 |
| 密码 | [LoginView.vue:57-62](../web/src/views/LoginView.vue#L57) | `<input type=password required>` | 不 trim；失败后被清空（[:35](../web/src/views/LoginView.vue#L35)） |
| 动态口令 | [LoginView.vue:64-66](../web/src/views/LoginView.vue#L64) | `<input inputmode=numeric maxlength=10>`，**非 required** | 没启用二次验证时留空即可；[auth.ts:22](../web/src/api/auth.ts#L22) 只在非空时把 `code` 放进表单，所以普通登录不会多发一个空字段。失败后与密码一起清空（[:36](../web/src/views/LoginView.vue#L36)） |
| 登录 | [LoginView.vue:77](../web/src/views/LoginView.vue#L77) | `<button type=submit>` | `busy` 时禁用，文案切"登录中…" |
| 错误区 | [LoginView.vue:71](../web/src/views/LoginView.vue#L71) | `<p class=error role=alert>` | 仅 `error` 非空时存在 |

对应请求：`POST /api/auth/login`，**表单编码**（不是 JSON）—— [auth.ts:18-23](../web/src/api/auth.ts#L18) 的 `http.postForm`，契约见 [03 §10 表首行](./design/03-technical-design.md)。口令字段名是 `code`（[LoginDetailsSource](../backend/src/main/java/com/rsshub/security/OperatorAuthenticationProvider.java#L33) 读表单参数，也接受 `X-TOTP-Code` 头，[:35](../backend/src/main/java/com/rsshub/security/OperatorAuthenticationProvider.java#L35)）。

### 1.2 用例

| 编号 | P | 场景 | 步骤 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-AUTH-01 | P0 | 正确口令登录 | 打开 `/login` → 填 admin + 正确口令 → 点 [登录](../web/src/views/LoginView.vue#L77) | 200 `{"username":"admin"}`；建 `RSSHUB_SESSION`；顶栏出现；重定向到 `next` 或 `/` | [SecurityConfig.java:98-103](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L98) · [LoginView.vue:19-21](../web/src/views/LoginView.vue#L19) `[自]AuthGateTest` `[C]` |
| TC-AUTH-02 | P0 | 口令错误 | 同上，故意错 | 401 ProblemDetail `invalid_credentials`；页面显示"用户名、密码或动态码不正确。"；**密码框与动态码框被清空**；不建会话 | [SecurityConfig.java:115-116](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L115) · [LoginView.vue:25-36](../web/src/views/LoginView.vue#L25) `[自]` `[C]` |
| TC-AUTH-03 | P0 | 回跳目标 | 未登录直接访问 `/posts` | 路由守卫带 `?next=/posts` 弹登录；登录成功后落回 `/posts` | [router/index.ts:43-45](../web/src/router/index.ts#L43) `[B]` |
| TC-AUTH-04 | P0 | `next` 投毒 | 手填 `/login?next=//evil.example` | 仍跳站内：只接受以 `/` 开头的路径，否则退回 `/` | [LoginView.vue:20-21](../web/src/views/LoginView.vue#L20) `[B]` |
| TC-AUTH-05 | P0 | 登录会换 CSRF token | 登录 → 立刻 `POST /api/posts` | 成功。因为登录成功处理器**手工重写一次 `XSRF-TOKEN` cookie**，否则轮换后的 token 要到下一次 GET 才可见 | [SecurityConfig.java:101](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L101) + [:136-140](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L136) · [03 §10 CSRF 口径](./design/03-technical-design.md) `[自]PublishApiTest`（本会话曾因两次取 cookie 得到不同 token 而全线 403）`[C]` |
| TC-AUTH-06 | P0 | 缺 CSRF cookie 的写 | 不带 `X-XSRF-TOKEN` `POST /api/feeds` | 403；前端若发生在登录则提示"缺少 CSRF Cookie，请刷新页面后重试。" | [SecurityConfig.java:88-92](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L88) · [LoginView.vue:31-32](../web/src/views/LoginView.vue#L31) `[自]` `[C]` |
| TC-AUTH-07 | P1 | 空用户名/空密码 | 直接点 [登录](../web/src/views/LoginView.vue#L77) | 浏览器 required 拦住，不发请求（用户名 [:54](../web/src/views/LoginView.vue#L54)、密码 [:61](../web/src/views/LoginView.vue#L61) 两个 input 是 `required`；**动态口令不是**，空着也能提交） | [LoginView.vue:54,61](../web/src/views/LoginView.vue#L54) `[B]` |
| TC-AUTH-08 | P1 | 连点登录 | 快速点两次 [登录](../web/src/views/LoginView.vue#L77) | 第二次被 `disabled` 挡住；按钮文案"登录中…" | [LoginView.vue:77](../web/src/views/LoginView.vue#L77), [:17](../web/src/views/LoginView.vue#L17) `[B]` |
| TC-AUTH-09 | P0 | 后端未启动 | 停掉 :8080 后点登录 | 文案"无法连接服务，请确认后端已启动。"，不是白屏 | [LoginView.vue:34](../web/src/views/LoginView.vue#L34) `[B]` |
| TC-AUTH-10 | P0 | 登出 | 点顶栏 [退出](../web/src/App.vue#L68) | `POST /api/auth/logout` 204；`RSSHUB_SESSION` 被删；回登录页 | [SecurityConfig.java:118-125](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L118) · [App.vue:24-27](../web/src/App.vue#L24) `[C]` |
| TC-AUTH-11 | P0 | 登出后不能复用 | 登出后用旧 cookie 打 `/api/feeds` | 401。注意 `deleteCookies` 的常量必须与 yml 的 cookie 名同步，否则删的是不存在的 cookie | [SecurityConfig.java:45,123](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L45) · [application.yml:15-18](../backend/src/main/resources/application.yml#L15) `[C]` |
| TC-AUTH-12 | P0 | `/api/auth/me` 匿名可读 | 无 cookie `GET /api/auth/me` | **200 `{"authenticated":false}`**，不是 401（否则用户什么都没干就看到错误提示）；此时**没有** `totpEnabled` 键 | [AuthController.java:25-38](../backend/src/main/java/com/rsshub/security/AuthController.java#L25) · [03 §10](./design/03-technical-design.md) `[自]AuthGateTest` `[C]` |
| TC-AUTH-13 | P1 | 会话过期后仍在标签页 | 服务端会话失效后点任一页 | 守卫先 `session.refresh()` 再判，弹回登录并带 `next`；不出现"页面在但数据全 401" | [router/index.ts:33-46](../web/src/router/index.ts#L33) `[B]` |
| TC-AUTH-14 | P2 | 会话 cookie 属性 | 登录后检查 cookie | `HttpOnly` + `SameSite=Lax`；**无 `Secure`（已知偏差，见 13 节 D-05）** | [application.yml:15-18](../backend/src/main/resources/application.yml#L15) · [03 §9.1 表](./design/03-technical-design.md) `[B]` |
| TC-AUTH-15 | P0 | 已登录者访问登录页 | 登录后直接输 `/login` | 守卫改判去收件箱，不出现两个入口 | [router/index.ts:39-41](../web/src/router/index.ts#L39) `[B]` |
| TC-AUTH-16 | P0 | 5 次失败即锁定 | 用错误口令连打 5 次 `/api/auth/login` | 前 4 次 401 `invalid_credentials`，第 5 次起 **429 ProblemDetail `account_locked` + `Retry-After`（epoch 秒）**；登录页文案"失败次数过多，账号已临时锁定（5 次锁 15 分钟）。稍后再试。" | [LoginAttemptGuard.java:18-19,52-53](../backend/src/main/java/com/rsshub/security/LoginAttemptGuard.java#L18) · [SecurityConfig.java:107-114](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L107) · [LoginView.vue:29-30](../web/src/views/LoginView.vue#L29) `[自]TotpLoginGateTest#fiveFailuresLockTheNameAndTheLockRefusesTheRightPasswordToo` |
| TC-AUTH-17 | P0 | 锁定优先于口令 | 锁定期间用**正确**口令再登一次 | 仍然 429 `account_locked`，不是 200——顺序是**锁定检查 → 密码 → TOTP**，口令对也不能绕过锁定 | [OperatorAuthenticationProvider.java:106-109](../backend/src/main/java/com/rsshub/security/OperatorAuthenticationProvider.java#L106) `[自]` 同上 |
| TC-AUTH-18 | P1 | 一次成功清零计数 | 错 4 次 → 成功登录 1 次 → 再错 1 次 | 第 5 次错误不触发锁定（计数已被成功清零）。**注意**：计数器只在内存，重启即清；这是 [03 §9.1](./design/03-technical-design.md) 记录的口径，不是缺陷 | [LoginAttemptGuard.java:10,59](../backend/src/main/java/com/rsshub/security/LoginAttemptGuard.java#L59) `[自]TotpLoginGateTest#aSuccessfulLoginClearsTheFailureCounter` |
| TC-AUTH-19 | P0 | 启用 TOTP 后口令不再单独够用 | 在安全页走完 enroll→activate，退出，再只填用户名+密码登录 | 401 `invalid_credentials`（**不是**专门的"缺 code"错误码，避免外人借此探测该账号是否开了二次验证）；补上当前步的动态码后 200 | [OperatorAuthenticationProvider.java:125-129](../backend/src/main/java/com/rsshub/security/OperatorAuthenticationProvider.java#L125) `[自]TotpLoginGateTest#aPasswordAloneStopsBeingEnoughAndACodeStartsBeingRequired` `[B]` |
| TC-AUTH-20 | P0 | 二次验证要"先证后用" | enroll 后立刻用旧口令登录、并直接调 activate | enroll 只返回密钥与 `otpauth://`，**不写库**（[:58-64](../backend/src/main/java/com/rsshub/security/TotpController.java#L58)）；未 enroll 就 activate 得 400 `secret_required`（[:71-73](../backend/src/main/java/com/rsshub/security/TotpController.java#L71)）；activate 通过校验才把密文写进 `app_user.totp_secret`（[:77](../backend/src/main/java/com/rsshub/security/TotpController.java#L77)，库值是 `v1:` 前缀的密文，明文永不落库） | [TotpController.java:47,67](../backend/src/main/java/com/rsshub/security/TotpController.java#L47) `[自]TotpLoginGateTest#enrolmentStaysInactiveUntilACodeHasBeenProved` |
| TC-AUTH-20b | P1 | 重复 enroll / 空转 deactivate | 已启用后再 enroll；未启用时 deactivate | 前者 **409 `totp_already_active`**（[:54-56](../backend/src/main/java/com/rsshub/security/TotpController.java#L54)），后者 **404 `totp_inactive`**（[:84-85](../backend/src/main/java/com/rsshub/security/TotpController.java#L84)）——"不存在的路径"不当成功静默返回 | [TotpController.java:54,84](../backend/src/main/java/com/rsshub/security/TotpController.java#L54) `[自]TotpLoginGateTest#theStateThatDoesNotExistIsRefusedRatherThanIgnored` |
| TC-AUTH-21 | P1 | 关闭二次验证要口令而不是码 | 已启用后在安全页填**口令**点关闭 | 204 且 `totp_secret` 置空；只给码不给口令是 400 `password_required` | [TotpController.java:80-91](../backend/src/main/java/com/rsshub/security/TotpController.java#L80) `[自]TotpLoginGateTest#removingTheSecondFactorNeedsThePasswordNotTheCode` `[B]` |
| TC-AUTH-22 | P1 | 机器令牌不能替操作员开关二次验证 | 用 `Bearer` 令牌调 `/api/auth/totp/enroll` | **401**——TOTP 路由只认会话（`/api/**` 要 `ROLE_OPERATOR` 或 `SCOPE_admin`，而 `permitAll` 名单里没有它）。否则一次泄漏的 CI 令牌就能把人的第二因子拆掉 | [SecurityConfig.java:74-87](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L74) `[自]TotpLoginGateTest#aBearerTokenSessionCannotEnrolASecondFactorForTheOperator` |

锁定与二次验证的三条实现约束（测的时候按现状判，不当 bug 提）：**计数器只在内存**（重启即清，[LoginAttemptGuard.java:7-14](../backend/src/main/java/com/rsshub/security/LoginAttemptGuard.java#L7) 的类注释写了取舍，且"按用户名而非 IP"也写在同一处）；**TOTP 偏移只允许 ±1 步**（`WINDOW_STEPS`，[TotpService.java:23](../backend/src/main/java/com/rsshub/security/TotpService.java#L23)、[:109](../backend/src/main/java/com/rsshub/security/TotpService.java#L109)），SHA1/6 位/30 秒，时钟漂移超过 30 秒就得靠重新 enroll；**`totp_secret` 存的是 `v1:` 前缀的密文**（[SecretCipher.java:24](../backend/src/main/java/com/rsshub/security/SecretCipher.java#L24)），没设 `RSSHUB_SECRET_KEY` 时 enroll 直接 400。

---

## 2. `NAV` 顶栏与路由

顶栏只在"已登录且不在登录页"时渲染（[App.vue:12](../web/src/App.vue#L12)）。

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-NAV-01 | P1 | [收件箱](../web/src/App.vue#L30) / [大屏](../web/src/App.vue#L31) / [订阅管理](../web/src/App.vue#L32) / [文章](../web/src/App.vue#L33) / [聚合](../web/src/App.vue#L34) / [事件源](../web/src/App.vue#L35) / [规则](../web/src/App.vue#L36) / [投递](../web/src/App.vue#L37) / [健康度](../web/src/App.vue#L38) / [安全](../web/src/App.vue#L39) / [设置](../web/src/App.vue#L40) | 逐个点 | **十一条**。F13 起顶栏不再手写十一个 `<li>`：它们来自 `tabs` 数组（[App.vue:29-41](../web/src/App.vue#L29)），模板只有一句 `v-for`（[:58-62](../web/src/App.vue#L58)），所以**加一个页签改一处**。每个 `tab.name` 都要能在路由表 [router/index.ts:19-30](../web/src/router/index.ts#L19) 里找到同名路由——注意**两处顺序不同**（`tabs` 是 聚合→事件源→规则→投递，路由表是 规则→投递→事件源），只数条数抓不到写错的 `name`；当前项由 `.router-link-active` 上色，不重复挂载请求 | `[机]`比对 11 个 name + `[B]` |
| TC-NAV-02 | P1 | [RSS Hub 品牌](../web/src/App.vue#L48) | 点 | 回收件箱（`:to="{ name: 'inbox' }"`，不是 `path: '/'`） | [App.vue:48-51](../web/src/App.vue#L48) `[B]` |
| TC-NAV-03 | P1 | 用户名 | 观察 | 显示 `session.username`（来自 `/api/auth/me`），不是硬编码 | [App.vue:66](../web/src/App.vue#L66) `[B]` |
| TC-NAV-04 | P1 | 整条顶栏 | 未登录访问任意管理路由 | 顶栏不渲染（含"退出"按钮 [:68](../web/src/App.vue#L68)），避免匿名态出现可点项 | [App.vue:12](../web/src/App.vue#L12) 的 `showChrome` + `<nav v-if>` [:46](../web/src/App.vue#L46) `[B]` |
| TC-NAV-05 | P2 | 布局 | 进收件箱 | 三栏铺满视口：`flush` 时 `<main>` 从 `flex-grow-1 p-3 p-lg-4` 换成 `flex-grow-1 d-flex pane-host`（[App.vue:15](../web/src/App.vue#L15) 的判定、[:73](../web/src/App.vue#L73) 的绑定）；左/中两列宽度由 `.inbox-side`/`.inbox-list` 定（[InboxView.vue:189-195](../web/src/views/InboxView.vue#L189)），阅读列 `.reader` 以 `flex: 1 1 auto` 吸收余量（[ReaderPane.vue:126-131](../web/src/components/ReaderPane.vue#L126)）；`main` 的 `pane-host` 带 `min-height: 0`，否则三栏里只有一栏会滚（[theme.css:324-331](../web/src/styles/theme.css#L324)）| [InboxView.vue:63](../web/src/views/InboxView.vue#L63) `[B]` |
| TC-NAV-06 | P1 | 浏览器前进/后退 | 在 11 个视图间来回 | 历史正常；`createWebHistory`（[router/index.ts:17](../web/src/router/index.ts#L17)）下刷新 `/rules` 不 404（Vite/Caddy SPA 回退） | [router/index.ts:19-30](../web/src/router/index.ts#L19) `[B]` |
| TC-NAV-07 | P2 | 窄屏（自用也会在手机上开） | 把窗口拖到 900px 以下 | `navbar-expand-xxl` 在 1400px 以下就把页签收进折叠按钮（[App.vue:46](../web/src/App.vue#L46)、`#appNav` 的 `collapse`/`show` 由 Vue 的 `navOpen` 切换 [:52-56](../web/src/App.vue#L52），**不再是靠横向滚动**；展开后页签用 `flex-row flex-wrap` 换行而不遮住"退出"按钮 | **未做响应式验收**；折叠状态机在 [App.vue:19-22](../web/src/App.vue#L19)，路由切换时收起 `navOpen`（watch） |

---

## 3. `INB` 收件箱（`InboxView.vue` + `stores/entries.ts`）

覆盖 [FR-2.4](./design/01-requirements.md#L76)。控件清点（**只有这些**，2026-11-01 补齐键盘流与两个过滤后）：4 个状态标签、1 个搜索框、N 个分组按钮、N 个订阅按钮、N 个条目行、1 个"只看命中"开关、1 个"标签：X ×"清除片（只在有标签时出现）、N 个正文标签按钮、1 个"开启桌面通知"、1 个"此订阅全部已读"、1 个"加载更多"，外加 j/k/n/s/m 五个键盘动作。

### 3.1 状态标签（4 个按钮）

| 按钮 | 位置 | 计数来源 |
|---|---|---|
| 未读 | [InboxView.vue:64-76](../web/src/views/InboxView.vue#L64)（`tabs[0]`，[定义 :19-24](../web/src/views/InboxView.vue#L19)） | `counters.unread`，且 `unread` 排除 baseline 行 |
| 全部 | 同上（`:19`） | `counters.total` |
| 星标 | 同上（`:20`） | `counters.starred` |
| 归档 | 同上（`:21`） | `counters.archived` |

计数 SQL 见 [EntryFinder.java:171-175](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L171)：`unread` 的口径是 `read_state='unread' AND baseline=FALSE`。

| 编号 | P | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|
| TC-INB-01 | P0 | 点任一标签 | `entries.setState` → `GET /api/entries?state=<tab>`（**不带 `limit`**，页大小由服务端的设置缺省决定，见 TC-INB-34）；当前项加 `.on`（[InboxView.vue:68](../web/src/views/InboxView.vue#L68)）；先 `abort()` 掉上一个在飞的请求，避免旧结果覆盖新结果 | [InboxView.vue:36-39](../web/src/views/InboxView.vue#L36) · [entries.ts:65-83](../web/src/stores/entries.ts#L65) `[自]InboxApiTest` `[B]` |
| TC-INB-02 | P0 | 首次订阅后看"未读" | **baseline 条目不算未读**（FR-2.3"首次订阅只建基线不报未读"）；但"全部"里有它 | [01 FR-2.3](./design/01-requirements.md#L75) · [EntryFinder.java:174 · :303-305](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L174) `[自]EntryIngestServiceTest` |
| TC-INB-03 | P1 | 徽标一致性 | 四个标签的徽标数与 `GET /api/entries/counters` 完全一致；侧栏订阅行的未读数与之不相矛盾（同源 store，[feeds.ts:10](../web/src/stores/feeds.ts#L10) 单例） | [InboxView.vue:19,103-104](../web/src/views/InboxView.vue#L19) `[B]` |
| TC-INB-04 | P1 | 空态文案 | `state=unread` 且无条目 → "没有未读。抓取按每个来源的节奏，默认一小时一轮。"；其他标签 → "这里没有条目。" | [InboxView.vue:149-152](../web/src/views/InboxView.vue#L149) **文案与配置不符，见 13 节 D-03** `[B]` |
| TC-INB-05 | P1 | 单订阅视图 | 点侧栏某个订阅 → 列表头出现"· 单个订阅"并冒出 [此订阅全部已读](../web/src/views/InboxView.vue#L141) | [InboxView.vue:101,129,141](../web/src/views/InboxView.vue#L101) `[B]` |
| TC-INB-06 | P0 | 非法 state | 手改 URL/直接 `GET /api/entries?state=bogus` | 400 `bad_state`，消息列出允许值 | [EntryController.java:39,110](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L39) `[自]InboxApiTest` `[C]` |

### 3.2 搜索框

控件：[InboxView.vue:78-81](../web/src/views/InboxView.vue#L78)（`type=search`，回车提交，无独立按钮）。

| 编号 | P | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|
| TC-INB-10 | P1 | 输入关键词回车 | `q` 进 `GET /api/entries?q=`；大小写不敏感 | [entries.ts:116-119](../web/src/stores/entries.ts#L116) · [EntryFinder.java:324-335](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L324) `[自]InboxApiTest` |
| TC-INB-11 | P1 | 只搜标题与摘要 | 正文独有词**不应**命中（当前实现就是 `title`/`summary` 的 LIKE） | [EntryFinder.java:330-334](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L330)（注释说明 PG 全文检索属 M6） `[自]` |
| TC-INB-12 | P2 | 通配/百分号注入 | 输入 `%` 或 `_` 当作字面量匹配，不放大结果集 | [EntryFinder.java:330-332](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L330)（`escapeLike` 在 [:374-376](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L374)，`ESCAPE '\'` 在同一条件里） `[自]InboxApiTest 的 growth-50%-milestone 组` |
| TC-INB-13 | P1 | 清空搜索 | 空/全空格时 `q` **不发**（`undefined`），结果回到未过滤 | [entries.ts:51-63](../web/src/stores/entries.ts#L51) `[B]` |
| TC-INB-14 | P0 | 搜索期间抓取落库 | 换页仍不重复/不漏条目：分页是 `(sort_key,id)` 游标而不是 offset | [EntryController.java:30-32 注释](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L30) `[自]` |

### 3.3 分组与订阅按钮

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-INB-20 | P1 | [分组按钮](../web/src/views/InboxView.vue#L87) | 点一个分组 | 按 `group=` 过滤；再点同一项 → **取消过滤**（`clear` 判断在 [`:42-46`](../web/src/views/InboxView.vue#L42)），并清掉 `feedId` | `[B]` |
| TC-INB-21 | P2 | 分组区可见性 | 无任何分组 | "分组"标题与列表整块不渲染 | [InboxView.vue:83-84](../web/src/views/InboxView.vue#L83) `[B]` |
| TC-INB-22 | P1 | [订阅按钮](../web/src/views/InboxView.vue#L100) | 点一个订阅 | 保持当前标签语义（非 `all` 时仍用 `unread`，[`:101`](../web/src/views/InboxView.vue#L101)）；`title` 属性 = canonical URL 便于核对搬家 | `[B]` |
| TC-INB-23 | P2 | 订阅行未读徽标 | 有未读 / 无未读但非 active | 前者显示数字，后者显示状态字（`muted`/`paused`/…）；两者互斥 | [InboxView.vue:103-104](../web/src/views/InboxView.vue#L103) `[B]` |
| TC-INB-24 | P1 | 无标题订阅 | feed 还没解析出标题 | 回落显示 `canonicalUrl`，不显示空白 | [InboxView.vue:102](../web/src/views/InboxView.vue#L102) `[B]` |
| TC-INB-25 | P0 | 订阅列表加载失败 | `/api/feeds` 出错 | 侧栏底部出错误行（`code: message` 格式），页面不崩 | [InboxView.vue:111](../web/src/views/InboxView.vue#L111) · [feeds.ts:27-29,37](../web/src/stores/feeds.ts#L27) `[B]` |

### 3.4 条目行与"加载更多"

| 编号 | P | 控件 | 操作/观察 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-INB-30 | P0 | [条目行](../web/src/views/InboxView.vue#L155) | 点一行 | `entries.open(id)` → `GET /api/entries/{id}`；行加 `.picked` | [entries.ts:131-143](../web/src/stores/entries.ts#L131) `[自]InboxApiTest` `[B]` |
| TC-INB-31 | P2 | 行内要素 | 观察 | 未读加粗（`.unread .headline`）、星标 `★`、副行"来源 · 相对时间 · 第 N 次"、摘要截 140 字 | [InboxView.vue:158-171](../web/src/views/InboxView.vue#L158) `[B]` |
| TC-INB-32 | P2 | 无标题条目 | 观察 | 显示"(无标题)"而不是空 | [InboxView.vue:160](../web/src/views/InboxView.vue#L160) `[B]` |
| TC-INB-33 | P0 | [加载更多](../web/src/views/InboxView.vue#L176) | 点 | 带 `cursor` 再取一页并**追加**；无 `nextCursor` 时按钮消失；`loading` 期间禁用且文案"加载中…" | [entries.ts:86-99](../web/src/stores/entries.ts#L86) · [EntryController.java:133-142](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L133) `[B]` |
| TC-INB-34 | P0 | 每页大小 | 抓包 | 请求**不带 `limit`**——于是一页的条数就是设置项 `inbox.pageSize`（FR-9.2 的"改了不可能不生效"靠这一条成立；2026-11-01 前这里硬发 `limit=50`，设置是摆设）。后端侧 `limit` 夹在 [1, 200]（`MAX_LIMIT=200`，缺省取设置项），显式传 `limit=9999` 也不越界 | [entries.ts:65-83](../web/src/stores/entries.ts#L65) 的"不发 limit"注释 · [EntryController.java:41,123-126](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L41) `[自]SettingsApiTest#steersTheInboxDefault`（12 行种子 → 无 `limit` 得 10 条 + 游标） `[C]smoke.sh` 尾段 6 条 `[B]` |
| TC-INB-35 | P0 | 坏 cursor | `GET /api/entries?cursor=###` | 400 `bad_cursor`，消息"cursor is not one this endpoint handed out"（不解释内部编码） | [EntryController.java:245-266](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L245) `[自]` `[C]` |
| TC-INB-36 | P1 | 列表接口 404 条目 | `GET /api/entries/999999` | 404 `entry_not_found`，面板显示错误行而不是空正文 | [EntryController.java:150-153](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L150) · [ReaderPane.vue:35](../web/src/components/ReaderPane.vue#L35) `[自]` `[B]` |
| TC-INB-37 | P2 | 未读切换的"行消失" | 在 `unread` 标签下点"标记已读" | 该行**从列表消失**（重读而非本地打补丁），这是设计而非 bug | [entries.ts:193-201](../web/src/stores/entries.ts#L193) `[B]` |
| TC-INB-38 | P1 | `since` 参数 | `GET /api/entries?since=2026-10-01T00:00:00Z` | 只返回 `coalesce(published_at, fetched_at) >= since`；非法值 400 `bad_since` | [EntryController.java:216-225](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L216) · [EntryFinder.java:355-358](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L355) `[自]` `[C]` |

### 3.5 "只看命中"与标签过滤（2026-11-01 新增，关闭 BLK-01 的过滤面）

控件：[InboxView.vue:131](../web/src/views/InboxView.vue#L131) 的"只看命中"开关、[:133-138](../web/src/views/InboxView.vue#L133) 的"标签：X ×"清除片、[ReaderPane.vue:54-62](../web/src/components/ReaderPane.vue#L54) 的正文标签按钮。后端两个参数都挂在同一个 `GET /api/entries` 上（[EntryController.java:102-103](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L102)）。

| 编号 | P | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|
| TC-INB-40 | P0 | 点"只看命中" | 请求带 `alerted=true`，按钮加 `.on`；再点 → 参数**整个不发**（不是 `alerted=false`） | [InboxView.vue:131](../web/src/views/InboxView.vue#L131) · [entries.ts:126-129](../web/src/stores/entries.ts#L126) `[B]` |
| TC-INB-41 | P0 | 点正文里的一个标签 | 请求带 `category=<标签原文>`，列表只剩带这个标签的条目；再点同一个 → 取消 | [ReaderPane.vue:59](../web/src/components/ReaderPane.vue#L59) · [entries.ts:121-124](../web/src/stores/entries.ts#L121) `[自]` `[B]` |
| TC-INB-42 | P1 | 清除片可见性 | 只有 `category` 有值时头部才出现"标签：X ×"；点它等于取消过滤 | [InboxView.vue:133-138](../web/src/views/InboxView.vue#L133) `[B]` |
| TC-INB-43 | P0 | 整元素匹配 | `?category=Smoke` **不**命中打了 `SmokeTag` 标签的条目；`?category=ops` 命中第二个元素（不是只有首元素能命中） | [EntryFinder.java:343-354](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L343) `[自]InboxApiTest` `[C]smoke F2.4 段` |
| TC-INB-44 | P0 | 过滤与红标同源 | `alerted=true` 返回的每一行都带非空 `alertedBy`；`alerted=true` 与 `alerted=false` 的行数之和 = 不带参数时的行数 | [EntryFinder.java:336-342](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L336)（两侧都读 `notification_log`） `[自]` `[C]` |
| TC-INB-45 | P0 | `alerted` 只认布尔字面量 | `?alerted=maybe` → 400 `urn:rsshub:bad_alerted`（不用 Spring 的类型不匹配形状） | [EntryController.java:48,115-122](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L48) `[自]` `[C]` |
| TC-INB-46 | P1 | 空值与组合 | `category=`（空串）等于不过滤；`alerted=true&q=` / `&limit=1` 与游标照常组合，一页只有一条命中时**不给** `nextCursor` | [entries.ts:51-63](../web/src/stores/entries.ts#L51) · [EntryFinder.java:343-354 · :359-366](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L343) `[自]` `[C]` |
| TC-INB-47 | P1 | 徽标不跟过滤走 | 打开任一过滤后 `counters.*` 数值不变——标签上的数答的是"一共有多少"，不是"屏上有几条" | [EntryFinder.java:171-179](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L171)（SQL 里没有过滤条件） `[自]InboxApiTest` `[C]smoke 尾段` |

### 3.6 键盘流 j/k/n/s/m（2026-11-01 新增）

实现：[useInboxKeys.ts](../web/src/composables/useInboxKeys.ts)（键表 :7-13、打字判断 :16-20、窗口绑定 :44-45），动作落在 store 的 [selectRelative :152-162](../web/src/stores/entries.ts#L152)、[selectNextUnread :165-172](../web/src/stores/entries.ts#L165)、[toggleStarred :175-179](../web/src/stores/entries.ts#L175)、[toggleRead :182-186](../web/src/stores/entries.ts#L182)。提示文案见 [InboxView.vue:120](../web/src/views/InboxView.vue#L120)。

| 编号 | P | 按键 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|
| TC-INB-50 | P0 | `j` / `k` | 在**服务端返回的顺序**里下移/上移一行并打开它；未打开任何条目时 `j` 从第一条开始（不是跳过第一条）；走到两端停住，**不绕回** | [useInboxKeys.ts:8-9](../web/src/composables/useInboxKeys.ts#L8) · [entries.ts:146,152-162](../web/src/stores/entries.ts#L152) `[B]` |
| TC-INB-51 | P0 | `n` | 跳到下一条**未读且非 baseline** 的行；后面没有了就不动（不循环） | [useInboxKeys.ts:10](../web/src/composables/useInboxKeys.ts#L10) · [entries.ts:165-172](../web/src/stores/entries.ts#L165) `[B]` |
| TC-INB-52 | P0 | `s` | 等价于点正文面板的"加星标/取消星标"；在 `unread` 标签下星标后该行离开列表（重读所致，非 bug） | [entries.ts:175-179](../web/src/stores/entries.ts#L175) `[B]` |
| TC-INB-53 | P0 | `m` | 已读↔未读切换；`dismissed` 的行按 `m` 变成已读（`m` 的语义是"这条我看完了"） | [entries.ts:182-186](../web/src/stores/entries.ts#L182) `[B]` |
| TC-INB-54 | P0 | 输入框里打字 | 搜索框内敲 `j`/`k`/`n`/`s`/`m` 只进搜索词，**不**移动选择（`INPUT`/`TEXTAREA`/`SELECT`/`contenteditable` 都跳过） | [useInboxKeys.ts:16-20,34](../web/src/composables/useInboxKeys.ts#L16) `[B]` |
| TC-INB-55 | P1 | 带修饰键 | `Ctrl`/`Meta`/`Alt` 组合一律放行给浏览器（`Ctrl+S` 仍是浏览器的保存） | [useInboxKeys.ts:33](../web/src/composables/useInboxKeys.ts#L33) `[B]` |
| TC-INB-56 | P1 | 键盘动作后的可见性 | 当前行进入可视区（`scrollIntoView({block:'nearest'})`），列表很长时按 `j` 不会把选中项滚出屏幕 | [useInboxKeys.ts:39-40](../web/src/composables/useInboxKeys.ts#L39) `[B]` |
| TC-INB-57 | P2 | 离开收件箱 | 切到大屏/规则页后按 `j` 不驱动收件箱（监听器随视图卸载） | [useInboxKeys.ts:44-45](../web/src/composables/useInboxKeys.ts#L46) `[B]` |

### 3.7 桌面通知（可选面，2026-11-01 新增）

实现：[useUnreadAlerts.ts](../web/src/composables/useUnreadAlerts.ts)（轮询周期 :5、权限 :30-45、增长判定 :47-56），控件在 [InboxView.vue:113-119](../web/src/views/InboxView.vue#L113)。

| 编号 | P | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|
| TC-INB-60 | P1 | 点"开启桌面通知" | 触发 `Notification.requestPermission()`；授权后按钮换成"桌面通知已开启"文案 | [useUnreadAlerts.ts:30-45](../web/src/composables/useUnreadAlerts.ts#L30) · [InboxView.vue:115-117](../web/src/views/InboxView.vue#L115) `[B]` |
| TC-INB-61 | P1 | 挂着收件箱等一轮抓取 | 每 60 秒读一次 `/api/entries/counters`；**只有未读数增长**才通知，首次进页面不弹（没有比较基准时不发） | [useUnreadAlerts.ts:5,47-56,58-62](../web/src/composables/useUnreadAlerts.ts#L47) `[B]` |
| TC-INB-62 | P1 | 系统拒绝或不支持 | 不弹窗、不报错，侧栏出现"未读数仍然会在左侧标签上更新"的可见文案 | [useUnreadAlerts.ts:32,43](../web/src/composables/useUnreadAlerts.ts#L32) `[B]` |
| TC-INB-63 | P2 | 通知正文 | 只有条数（"N 条新未读（共 M 条）"），**不含条目原标题**——标题是来源写的，桌面弹窗这里既不能清洗也不能点击拦截 | [useUnreadAlerts.ts:18-20,53-54](../web/src/composables/useUnreadAlerts.ts#L53) `[B]` |

---

## 4. `RDR` 阅读面板（`ReaderPane.vue`）

覆盖 [FR-2.5](./design/01-requirements.md#L77)。动作区一共 **5 类按钮**，全部打到同一个端点 `POST /api/entries/state`；面板头部另有 **N 个标签按钮**（2026-11-01 F2.4 起，它不是状态写，而是往 `GET /api/entries` 放一个 `category=`，用例见 3.5 节 TC-INB-41）。

| 按钮 | 位置 | 发送体 |
|---|---|---|
| 标记已读 / 标为未读（二选一渲染） | [ReaderPane.vue:70-70](../web/src/components/ReaderPane.vue#L70) | `{ids:[id], readState:"read"|"unread"}` |
| 加星标 / 取消星标 | [:74-77](../web/src/components/ReaderPane.vue#L74) | `{ids:[id], starred:!starred}` |
| 忽略 | [:78](../web/src/components/ReaderPane.vue#L78) | `{ids:[id], readState:"dismissed"}` |
| 归档 / 移出归档 | [:82-82](../web/src/components/ReaderPane.vue#L82) | `{ids:[id], archived:!archived}` |
| 来源名（跳转过滤） | [:50](../web/src/components/ReaderPane.vue#L50) | 走 `entries.setFeed`，不是状态写 |
| 标题（回原文） | [:44-46](../web/src/components/ReaderPane.vue#L44) | `<a target=_blank rel="noreferrer noopener">` |

| 编号 | P | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|
| TC-RDR-01 | P0 | 未选中时 | 面板显示"选择左侧的一条查看正文。"，**没有任何动作按钮** | [ReaderPane.vue:38](../web/src/components/ReaderPane.vue#L38) `[B]` |
| TC-RDR-02 | P0 | 标记已读 | 200 `{applied:n}`；随后重读列表 + 重读该条目；按钮文案变"标为未读" | [ReaderPane.vue:70-70,22-27](../web/src/components/ReaderPane.vue#L70) · [entries.ts:193-201](../web/src/stores/entries.ts#L193) `[自]InboxApiTest` `[B]` |
| TC-RDR-03 | P0 | 忽略 | `readState=dismissed` 合法；条目离开未读；**不进入"归档"计数** | [EntryController.java:180-183](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L180) · [EntryFinder.java:171-175](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L171) `[自]` |
| TC-RDR-04 | P1 | 星标后 | 按钮出现 `.on` 高亮；"星标"标签计数 +1；"全部"计数不变 | [ReaderPane.vue:89-91](../web/src/components/ReaderPane.vue#L89) `[B]` |
| TC-RDR-05 | P1 | 归档/移出 | 归档后条目从 `unread`/`all` 语义中按后端定义呈现；徽标回到"归档" | [ReaderPane.vue:82-82](../web/src/components/ReaderPane.vue#L82) `[自]InboxApiTest` `[B]` |
| TC-RDR-06 | P0 | 三个标志全空 | 手工 `POST /api/entries/state {"ids":[1]}` | 400 `nothing_to_set` | [EntryController.java:184-186](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L184) `[自]` `[C]` |
| TC-RDR-07 | P0 | 空 ids | `{"ids":[]}` | 400 `no_ids` | [EntryController.java:177-179](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L177) `[自]` `[C]` |
| TC-RDR-08 | P0 | 非法 readState | `{"ids":[1],"readState":"deleted"}` | 400 `bad_read_state`（允许值仅 `unread/read/dismissed`） | [EntryController.java:180-183](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L180) `[自]` `[C]` |
| TC-RDR-09 | P1 | 批量上限 | 501 个 id | 400 `too_many_ids`；500 个通过 | [EntryController.java:187-189](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L187) `[自]` `[C]` |
| TC-RDR-10 | P1 | `applied` 语义 | 对已读条目再发"标记已读" | 返回**匹配行数**而不是"值改变行数"，UI 不得据此判断成败（代码注释已声明） | [EntryController.java:171-174,190-191](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L171) `[自]` |
| TC-RDR-11 | P0 | 正文渲染 | 打开一条含全文的条目 | 渲染 `content_html`（入库前已清洗，v-html 的前提成立）；缺全文时回落 `summary`；两者都无 → "（无正文）" | [ReaderPane.vue:89-91](../web/src/components/ReaderPane.vue#L89) · [03 §5.5](./design/03-technical-design.md) `[自]HtmlSanitizerTest` `[B]` |
| TC-RDR-12 | P0 | 正文安全 | 打开一条源站塞 `<script>`/`onclick`/跟踪像素的条目 | 脚本与事件属性**不存在**；`img` 域名相对化处理过 | [FR-2.5](./design/01-requirements.md#L77) · [HtmlSanitizerTest](../backend/src/test/java/com/rsshub/feedparse/HtmlSanitizerTest.java)（11 项）`[自]` |
| TC-RDR-13 | P1 | 音频 enclosure | 打开含 `audio/*` 附件的条目 | 渲染 `<audio controls>`（逐条一个播放器）；非音频进附件列表带 KB | [ReaderPane.vue:15-20,87,93-99](../web/src/components/ReaderPane.vue#L15) `[B]` |
| TC-RDR-14 | P1 | 修订历史 | 打开被源站改过的条目 | `<details>` 文案"来源改动过 N 次（当前第 M 次）"；展开列出每次的标题/时间/哈希前 12 位 | [ReaderPane.vue:101-110](../web/src/components/ReaderPane.vue#L101) · [EntryController.java:154-156](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L154) `[自]EntryIngestServiceTest` `[B]` |
| TC-RDR-15 | P0 | 修订不新增条目 | 源站改 `pubDate` 不改 GUID | 仍同一条目 + `revision` +1，`entry` 行数不变 | [FR-2.3](./design/01-requirements.md#L75) · [DoD](./design/01-requirements.md#L174) `[自]` |
| TC-RDR-16 | P1 | 不稳定 id 警告 | 打开 GUID 缺失、靠内容哈希兜底的条目 | 出现橙色提示"这条的 id 不稳定（…），来源改动可能被当成新文章。"；`stableId=false` | [ReaderPane.vue:114-117](../web/src/components/ReaderPane.vue#L114) · [EntryController.java:163,168](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L163) `[自]` `[B]` |
| TC-RDR-17 | P2 | 语言标签 | 条目有 `lang` | 显示"语言：xx"；无则**整段不出现**（缺键，不是 null） | [ReaderPane.vue:118](../web/src/components/ReaderPane.vue#L118) · [application.yml:45](../backend/src/main/resources/application.yml#L45) `[B]` |
| TC-RDR-18 | P1 | 点来源名 | 点 [:50](../web/src/components/ReaderPane.vue#L50) | 列表切到该订阅（`setFeed`），不跳外站 | `[B]` |
| TC-RDR-19 | P0 | 外链安全 | 检查标题链接 | `target="_blank"` 必配 `rel="noreferrer noopener"`（面板 [:44](../web/src/components/ReaderPane.vue#L44)、附件 [:96](../web/src/components/ReaderPane.vue#L96)、订阅 URL [FeedsView.vue:467-470](../web/src/views/FeedsView.vue#L467)） | `[B]` |

---

## 5. `SUB` 订阅管理（`FeedsView.vue`）

覆盖 [FR-2.1](./design/01-requirements.md#L73)、[FR-2.2](./design/01-requirements.md#L74)、[FR-2.6](./design/01-requirements.md#L78)、[FR-2.7 的写侧](./design/01-requirements.md#L79)。控件清点（F10 之后）：1 个 URL 输入、1 个分组输入、**2 个按钮（解析 / 添加）**、N 个候选单选 + N 个"直接添加"、1 个导出链接、1 个文件输入；表格每行 **1 个标题输入 + 1 个只读地址链接 + 1 个分组输入 + 1 个状态下拉 + 1 个抓取间隔数字输入 + 3 个按钮（立即抓取 / 全部已读 / 删除）**。行内可改的四个键与"地址不可改"的口径见 [24.1 节](#241-tc-sub-4860-f10-订阅编辑)，本节只留控件级基线。

### 5.1 添加区

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-SUB-01 | P0 | [解析](../web/src/views/FeedsView.vue#L318) | 贴一个博客首页地址 → 点 | `POST /api/feeds/discover`；成功显示"找到 <feedUrl>"（多于一个候选时追加"这一步给了候选，可以换或点候选后面的添加"）；`url` 为空时按钮禁用 | [FeedsView.vue:67-84](../web/src/views/FeedsView.vue#L67) · [FeedController.java:75-78](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L75) `[自]` `[C]` |
| TC-SUB-02 | P0 | 解析=纯 feed 地址 | 直接贴 `…/feed.xml` | `candidates` 为空数组、`feedUrl` = 最终地址（Content-Type 已是 feed 型，不再嗅探） | [FeedDiscoverer.java:83-87](../backend/src/main/java/com/rsshub/feedfetch/FeedDiscoverer.java#L83) · [03 §4.4](./design/03-technical-design.md) `[自]` |
| TC-SUB-03 | P0 | [候选单选](../web/src/views/FeedsView.vue#L331) | 一个页面给多个 feed | 渲染多个 radio（标题 + 类型 `code`），默认选中 `feedUrl` 或第一个（[`:73`](../web/src/views/FeedsView.vue#L73)）；换一个再点"添加"，订阅的是**选中项** | [FeedsView.vue:327-327](../web/src/views/FeedsView.vue#L327) `[B]` |
| TC-SUB-04 | P1 | 候选优先级 | 页面同时有 json/atom/rss | 按文档顺序取 `rel=alternate`，优先级 json > atom > rss | [03 §4.4 第 1 步](./design/03-technical-design.md) `[自]` |
| TC-SUB-05 | P1 | 相对地址绝对化 | feed 链接写成 `/feed.xml` | 用 `<base>`/页面 URL 绝对化后才入库 | [03 §4.4 第 4 步](./design/03-technical-design.md) · [rss-guide.md:614](./rss-guide.md#L614) `[自]AddressesTest` |
| TC-SUB-06 | P0 | 常见路径回退 | HTML 里没有 `rel=alternate` | 依次试 `/feed` `/feed.xml` `/rss` `/rss.xml` `/atom.xml` `/index.xml` `/feed.json`；仍无则提示"这个地址上没有 feed（+ note）"并标为失败态 | [FeedsView.vue:74-76](../web/src/views/FeedsView.vue#L74) · [03 §4.4 第 2 步](./design/03-technical-design.md) · [rss-guide.md:551](./rss-guide.md#L551) `[自]` |
| TC-SUB-07 | P0 | [添加](../web/src/views/FeedsView.vue#L321) | 点 | `POST /api/feeds` → **201** + 行对象 + 抓取已排队（消息"已订阅：… ，正在抓取"）；随后地址/分组输入与候选选择被清空 | [FeedController.java:80-84](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L80) · [FeedsView.vue:86-101](../web/src/views/FeedsView.vue#L86) `[自]` `[C]` |
| TC-SUB-08 | P0 | 添加无 feed 的地址 | 选中的候选是空 | 400 `no_feed_found`；**解析失败与写失败必须是两条独立提示**（发现是网络往返，不能看起来像写坏了） | [SubscriptionService.java:113-116](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L113) · [03 §4.4](./design/03-technical-design.md) `[自]` |
| TC-SUB-09 | P0 | 重复订阅 | 同一 canonical URL 再加 | 409 `already_subscribed`，列表不出现第二行 | [SubscriptionService.java:135-136](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L135) `[自]` `[C]` |
| TC-SUB-10 | P1 | 裸域名补协议 | 输入 `example.com/rss` | 接受并按 `https://` 补齐 | [SubscriptionService.java:425-435](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L425) `[自]` |
| TC-SUB-11 | P0 | 非 http(s) 协议 | 输入 `file:///etc/passwd`、`gopher://x`、`ftp://x` | 400 `scheme_not_allowed`（"Only http and https subscriptions are supported"） | [SubscriptionService.java:430-433](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L430) `[自]SsrfGuardTest` `[C]` |
| TC-SUB-12 | P1 | 空串 | 清空后直接提交表单 | 400 `empty_url`（正常路径下 URL input 的 `required` 已先拦住） | [SubscriptionService.java:425-429](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L425) · [FeedsView.vue:311](../web/src/views/FeedsView.vue#L311) `[自]` |
| TC-SUB-13 | P1 | 带 `#fragment` | 输入 `…/feed.xml#a` | 片段被剥掉，避免同一订阅裂成两行 | [SubscriptionService.java:436-438](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L436) `[自]` |
| TC-SUB-14 | P1 | 分组一起提交 | 填分组再点添加 | 新行带 `groupName`；分组输入里的空格被 trim；空串＝无分组 | [FeedsView.vue:91](../web/src/views/FeedsView.vue#L91) · [FeedController.java:47-49](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L47) `[自]` `[B]` |
| TC-SUB-15 | P1 | 消息配色语义 | 成功 vs 失败 | 同一行文案，成功 `class=ok`、失败 `class=error`（`failed` 标志位驱动） | [FeedsView.vue:287](../web/src/views/FeedsView.vue#L287), [:37-45](../web/src/views/FeedsView.vue#L37) `[B]` |

### 5.2 OPML（导出 / 导入）

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-SUB-20 | P0 | [导出 OPML](../web/src/views/FeedsView.vue#L292) | 点 | `GET /api/opml` → `application/opml+xml` + `Content-Disposition: attachment; filename="subscriptions-YYYYMMDD-HHmm.opml"`（UTC 戳，两次导出不互相覆盖）；下载名带 `.opml` 且是 `download` 属性 | [OpmlController.java:39-46](../backend/src/main/java/com/rsshub/adminapi/OpmlController.java#L39) `[自]` `[C]` |
| TC-SUB-21 | P0 | 导出可还原 | 导出 → 在别的阅读器导入 | 订阅与分组一致（需求侧的明确要求写在用户故事，DoD 表**没有 OPML 行**） | [FR-2.6](./design/01-requirements.md#L78) · [01 §6 Must OPML 条](./design/01-requirements.md#L150) `[B]` |
| TC-SUB-22 | P0 | [导入 OPML](../web/src/views/FeedsView.vue#L295) | 选一个 `.opml` | `POST /api/opml`（文件文本作为 body）→ 提示"OPML 导入：新增 X 条，跳过 Y 条"；表格刷新 | [FeedsView.vue:167-182](../web/src/views/FeedsView.vue#L167) · [OpmlController.java:53-57](../backend/src/main/java/com/rsshub/adminapi/OpmlController.java#L53) `[自]` `[C]` |
| TC-SUB-23 | P0 | 导入的 Content-Type | Windows 上 `.opml` 常被识别为 octet-stream | 仍成功：端点显式 `consumes` 四种类型（xml / text/xml / text/plain / octet-stream） | [OpmlController.java:48-54](../backend/src/main/java/com/rsshub/adminapi/OpmlController.java#L48) `[自]` `[C]` |
| TC-SUB-24 | P0 | 导入含私网地址 | 文件里放 `http://169.254.169.254/…` | 该条计入 `skipped`，**导入整体继续**；说明文字明写"（已有或被 SSRF 规则拒绝）" | [SubscriptionService.java:390-395](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L390) · [FeedsView.vue:299-301](../web/src/views/FeedsView.vue#L299) `[自]` `[C]` |
| TC-SUB-25 | P1 | 导入去重 | 文件内同一 `xmlUrl` 出现两次；或库里已存在 | 两次都 `skipped`，只建一行 | [SubscriptionService.java:396-399](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L396) `[自]` |
| TC-SUB-26 | P1 | 导入不分组的 outline | 只有 `<outline title xmlUrl>` | 仍导入，`groupName` 为空 | [SubscriptionService.java:400](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L400) `[自]` |
| TC-SUB-27 | P0 | 空文档 | 上传无 `xmlUrl` 的 XML | 400 `no_outlines`（不静默"成功 0 条"） | [SubscriptionService.java:380-383](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L380) `[自]` `[C]` |
| TC-SUB-28 | P1 | 导入不打扰网络 | 200 条一次导入 | **导入阶段不做发现**（导出器已解析过）；任务按插入顺序每条 +10s 错峰，避免 200 个并发 socket | [SubscriptionService.java:374-376,401-404](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L374) `[自]` `[C]` |
| TC-SUB-29 | P2 | 文件选择器复位 | 同一个文件连选两次 | 第二次仍触发导入（`input.value=""` 已复位） | [FeedsView.vue:180](../web/src/views/FeedsView.vue#L180) `[B]` |

### 5.3 行内编辑（五个控件）

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-SUB-30 | P1 | [分组输入](../web/src/views/FeedsView.vue#L476) | 改名后失焦（`@change`） | `PATCH /api/feeds/{id}` → 消息"分组已保存"，**返回的整行回写**而不是本地猜 | [FeedsView.vue:109-116](../web/src/views/FeedsView.vue#L109) · [feeds.ts:61-64](../web/src/stores/feeds.ts#L61) `[自]` `[B]` |
| TC-SUB-31 | P1 | 清空分组 | 删空后失焦 | 移到"无分组"（空串在 API 里表示"移出分组"，不是"不改"） | [FeedController.java:86-96](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L86) · [FeedRepository.java:188-192](../backend/src/main/java/com/rsshub/feedstore/FeedRepository.java#L188) `[自]` |
| TC-SUB-32 | P0 | [状态下拉](../web/src/views/FeedsView.vue#L480) | 依次选 `active`/`muted`/`paused` | 三个都是操作员可设值；`paused` **取消**队列任务，切回其他状态**重新排队** | [SubscriptionService.java:44,292-297](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L44) `[自]TaskQueueTest` `[C]` |
| TC-SUB-33 | P0 | 非操作员状态 | 手工 `PATCH {state:"error"}` / `"gone"` | 400 `bad_state` —— 这两个归抓取器所有，不给手改 | [SubscriptionService.java:280-282](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L280) `[自]` `[C]` |
| TC-SUB-34 | P2 | 未知旧状态显示 | 行状态是 `error`（抓取器写的） | 下拉仍可读：把当前值作为一个 `disabled` 选项追加，不让界面假装它是 `active` | [FeedsView.vue:484-486](../web/src/views/FeedsView.vue#L484) `[B]` |
| TC-SUB-35 | P0 | [抓取间隔输入](../web/src/views/FeedsView.vue#L490) | 填 60 后失焦 | `PATCH {intervalMinutes:60}` → 该源按 60 分钟节奏，且**不被站点 `<ttl>` 的下一次刷新覆盖**（操作员列 `fetch_interval_minutes` 与站点列 `ttl_minutes` 分列存储） | [FeedsView.vue:127-134](../web/src/views/FeedsView.vue#L127) · [SubscriptionService.java:283-286](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L283) · [FeedRepository.java:198-202](../backend/src/main/java/com/rsshub/feedstore/FeedRepository.java#L198) `[自]InboxApiTest#intervalAndGroupEditsAreBoundedAndExplicit` `[C]smoke.sh:411-418` |
| TC-SUB-36 | P0 | 间隔越界 | 手工 `PATCH {intervalMinutes:-1}` 或 `43201` | 400 `bad_interval`（合法：`0`＝跟随站点，或 1 分钟 ~ 30 天）；注意**抓取侧另有 5 分钟地板**：源自称 `ttl=1` 也不会热循环 | [SubscriptionService.java:47,283-286](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L283) · [FetchPolicy.java:80-85](../backend/src/main/java/com/rsshub/feedfetch/FetchPolicy.java#L80) `[自]FetchPolicyTest` `[C]smoke.sh:404-409` |
| TC-SUB-37 | P2 | ~~轮询占位显示 15~~ → 占位来自本机策略 | 观察空数字框 | **缺陷 D-04 已闭合**：输入框不再写死 15，占位文案是"跟随站点（默认 60 分钟）"，`min` 取 `GET /api/feeds/policy` 的 `minIntervalMinutes`；无覆盖时 `value` 为空 | [FeedsView.vue:491-492](../web/src/views/FeedsView.vue#L491) · [stores/feeds.ts:46-52](../web/src/stores/feeds.ts#L46) · [application.yml:83-84](../backend/src/main/resources/application.yml#L83) `[自]InboxApiTest#theFetchPolicyReflectsTheConfiguredInterval` `[B]` |
| TC-SUB-38 | P1 | 搬家提示 | `requestedUrl ≠ canonicalUrl` | 行内显示"原地址 …（来源搬过家）" | [FeedsView.vue:470-472](../web/src/views/FeedsView.vue#L470) `[B]` |
| TC-SUB-39 | P1 | 三列计数 | 观察"未读 / 条目"、"上次抓取"、"下次" | 分别是 `unreadCount/entryCount`、`ago(lastFetchedAt)`+失败次数+错误+平均 ms、`ago(nextFetchAt)`；**队列为空时显示"不在队列"**（就是 `paused`/`gone` 的现场证据） | [FeedsView.vue:497-507](../web/src/views/FeedsView.vue#L497) · [FeedRepository.java:34-38](../backend/src/main/java/com/rsshub/feedstore/FeedRepository.java#L34) `[B]` |

### 5.4 行按钮（三个）

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-SUB-40 | P0 | [立即抓取](../web/src/views/FeedsView.vue#L510) | 点 | `POST /api/feeds/{id}/refresh` → **202** `{queued:true,id:n}`；语义是**把 `fire_at` 提前到此刻并唤醒调度器**，不新建行 ⇒ 连点不会堆工作量。按钮的"抓取中…"与最终文案的口径见 TC-SUB-59 | [FeedController.java:104-114](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L104) · [SubscriptionService.java:332-338,341-363](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L332) `[自]FetchNowServiceTest` `[C]smoke.sh:414-418` |
| TC-SUB-41 | P0 | 对不可调度源点抓取 | 先 `paused`（或 `gone`）再点 | 409 `not_schedulable`，提示"set it active first"。**故意不静默忽略**：按按钮的人以为它是活的 | [SubscriptionService.java:334-336](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L334) `[自]` `[C]` |
| TC-SUB-42 | P0 | [全部已读](../web/src/views/FeedsView.vue#L515) | 点 | `POST /api/feeds/{id}/read` → `{marked:n}`；未读徽标与计数随之归零；随后 `load()` 重读列表 | [FeedController.java:116-121](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L116) `[自]InboxApiTest` `[B]` |
| TC-SUB-43 | P0 | [删除](../web/src/views/FeedsView.vue#L516) | 点 | **弹 `window.confirm`**（唯一先确认的行操作，因为级联删条目+任务）；确认后 `DELETE /api/feeds/{id}` → **204**，行消失 | [FeedsView.vue:161-165](../web/src/views/FeedsView.vue#L161) · [FeedController.java:98-102](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L98) `[自]` `[B]` |
| TC-SUB-44 | P0 | 删除取消 | confirm 选"取消" | 不发请求、行不消失 | [FeedsView.vue:163](../web/src/views/FeedsView.vue#L163) `[B]` |
| TC-SUB-45 | P1 | 删不存在的源 | `DELETE /api/feeds/999999` | 404 `feed_not_found`（存在性检查在所有写路径前面） | [SubscriptionService.java:321-326,410-418](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L410) `[自]` `[C]` |
| TC-SUB-46 | P1 | 删除后条目 | 查库 | 该 feed 的 `entry` 与任务一并消失（级联）；其他订阅不受影响 | [FeedRepository.java:212-221](../backend/src/main/java/com/rsshub/feedstore/FeedRepository.java#L212) `[自]` `[C]` |
| TC-SUB-47 | P1 | 行操作失败可见 | 断网后点任一按钮 | 消息区显示 `code: message` 且转成失败色；表格不出现"看起来成功了"的假象 | [FeedsView.vue:33-40,109-116](../web/src/views/FeedsView.vue#L109) `[B]` |

---

## 6. `HEA` 健康度（`HealthView.vue`）

覆盖 [FR-2.7](./design/01-requirements.md#L79)。控件只有 **1 个按钮**（重新读取）+ 一张 10 列表格。

| 编号 | P | 控件/列 | 操作/断言 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-HEA-01 | P0 | [重新读取](../web/src/views/HealthView.vue#L51) | 点 | `GET /api/health/feeds`；读取期间按钮禁用且文案"读取中…"；**不做轮询**（行由抓取器维护，一次刷新够了） | [HealthView.vue:15-16](../web/src/views/HealthView.vue#L15) `[自]` `[B]` |
| TC-HEA-02 | P0 | 汇总条 | 观察 | "<总> 个订阅 · <failing> 个有问题 · <quiet> 个超过三天没内容"；`slowestFetchMs` 缺省时**整段不显示** | [HealthView.vue:55-59](../web/src/views/HealthView.vue#L55)) · [HealthController.java:78-97](../backend/src/main/java/com/rsshub/adminapi/HealthController.java#L78) `[自]` `[B]` |
| TC-HEA-03 | P0 | `failing` 定义 | 造一个 `fail_count>0` 的行、一个 `state=error`、一个 `state=gone` | 三者都计入 `failing`；仅有 `lastError` 而无失败计数的行**计入 `problem`（行底色）但不计入 `failing`** —— 两个口径不同，属实现事实，需按代码为准则 | [HealthController.java:70-71,85](../backend/src/main/java/com/rsshub/adminapi/HealthController.java#L70) vs [:68 row.problem](../backend/src/main/java/com/rsshub/adminapi/HealthController.java#L68) `[自]` |
| TC-HEA-04 | P1 | `quiet` 阈值 | 造一个 4 天无内容的源 | 计入 `quiet`（>3×24h）；恰好 2 天不计。阈值硬编码不可配，是有意为之 | [HealthController.java:88-92](../backend/src/main/java/com/rsshub/adminapi/HealthController.java#L88) `[自]` |
| TC-HEA-05 | P0 | 状态分布 | 观察第二行 caption | `byState` 逐状态计数，和表格行数一致 | [HealthView.vue:60-62](../web/src/views/HealthView.vue#L60) `[B]` |
| TC-HEA-06 | P0 | 排序 | 混合"有问题"与"很安静"的行 | **问题行在前**，其后按安静时长**从长到短**（`null` 最后），再按 id | [HealthController.java:61-63](../backend/src/main/java/com/rsshub/adminapi/HealthController.java#L61) `[自]` |
| TC-HEA-07 | P1 | 问题行底色 | 观察 | `tr.problem` 淡橙底（`#fff8f5`） | [HealthView.vue:98](../web/src/views/HealthView.vue#L98) · [:127-129](../web/src/views/HealthView.vue#L127) `[B]` |
| TC-HEA-08 | P1 | "上次 HTTP"列 | 造 304/429/500 | 显示 `lastHttpStatus`；从未抓取显示 `—` | [HealthView.vue:113](../web/src/views/HealthView.vue#L113) · [03 §4.3 第 7 步](./design/03-technical-design.md) `[自]` |
| TC-HEA-09 | P1 | "连续 404" | 造 404 若干次 | `notfoundStreak>0` 才出现"· 连续 404 N 次" | [HealthView.vue:108](../web/src/views/HealthView.vue#L108) · [03 §4.3](./design/03-technical-design.md) `[自]` |
| TC-HEA-10 | P1 | "错误"列 | 造 `last_error` | 原文直显（如 `auth_required`、`parse_error`、`size_limit`），是排查抓手 | [HealthView.vue:114](../web/src/views/HealthView.vue#L114) · [03 §4.3 第 2/5 步](./design/03-technical-design.md) `[自]` |
| TC-HEA-11 | P1 | "上次有内容"/"安静时长" | 造有/无 `lastChangeAt` | 前者相对时间；后者显示"从未"，安静时长列用"4 天 6 小时"这种可读跨度 | [HealthView.vue:115-117](../web/src/views/HealthView.vue#L115) · [time.ts:25-33](../web/src/utils/time.ts#L25) `[B]` |
| TC-HEA-12 | P1 | "平均耗时" | 观察 | `avgFetchMs` + `ms`；缺省 `—` | [HealthView.vue:118](../web/src/views/HealthView.vue#L118) `[B]` |
| TC-HEA-13 | P0 | "下次抓取" | `paused` 的源 | 显示"不在队列"（`nextFetchAt` 为空），这是"静默坏掉"的第二现场 | [HealthView.vue:119](../web/src/views/HealthView.vue#L119) `[B]` |
| TC-HEA-14 | P1 | 空态 | 无订阅时进页 | "还没有订阅，先去「订阅管理」加一个。" | [HealthView.vue:69-73](../web/src/views/HealthView.vue#L69) `[B]` |
| TC-HEA-15 | P1 | 加载失败 | 断网后点重新读取 | 错误行 `code: message`；表格保留上一次结果还是清空按当前实现是**保留**（`view` 不清），需在测试中确认不产生误导 | [HealthView.vue:19-29](../web/src/views/HealthView.vue#L19) `[B]` |
| TC-HEA-16 | P0 | `/actuator/health` 匿名 | 无 cookie `GET /actuator/health` | 200，`show-details: never`（不泄漏 DB 细节）；且**只暴露 health**，`env`/`configprops`/`heapdump` 不在链路上 | [application.yml:47-59](../backend/src/main/resources/application.yml#L47) · [SecurityConfig.java:58](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L58) `[自]AuthGateTest` `[C]` |

> 本表**不探测**任何敏感 actuator 端点（`heapdump`/`shutdown`）。该限制是硬性的，验证方式只到"配置里没暴露"（[application.yml:51-53](../backend/src/main/resources/application.yml#L51)）+ 未授权时 401/404 的既有断言为止。

---

## 7. `PST` 文章编辑（`PostsView.vue`）

覆盖 [FR-1.1](./design/01-requirements.md#L64)。控件清点：列表区 1 个"新文章"按钮 + N 个行按钮；表单 8 个字段（标题 / Slug / 语言 / 发布时间 / 摘要 / 标签 / 分类 / 正文）；动作区 **1 个"保存" + 5 个状态按钮 + 1 个"删除"**；另有 permalink 链接与服务端预览区。

### 7.1 列表区

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-PST-01 | P0 | [新文章](../web/src/views/PostsView.vue#L235) | 点 | 进入新建态：字段清空、`status=draft`、Slug 输入框**出现**、提示"新文章：写好后点保存。URL slug 会在保存时按标题生成，之后不再改变。" | [PostsView.vue:113-148](../web/src/views/PostsView.vue#L113)) `[B]` |
| TC-PST-02 | P0 | [文章行](../web/src/views/PostsView.vue#L247) | 点 | 载入该篇（`open()` 回填全部字段，标签/分类转逗号文本）；行加选中样式 `.on`（`:class="{ on: … }"`，:248） | [PostsView.vue:132-148](../web/src/views/PostsView.vue#L132)) `[B]` |
| TC-PST-03 | P1 | 行内容 | 观察 | 状态徽标（`statusTone()` 选 `text-bg-*`，:84-86）+ 标题 + 时间（`publishedAt ?? updatedAt`）；草稿**必须在编辑器可见**（与公开侧相反） | [PostsView.vue:249-253](../web/src/views/PostsView.vue#L249)) · [PublishApiTest 草稿可见性](../backend/src/test/java/com/rsshub/editorial/PublishApiTest.java) `[自]` `[B]` |
| TC-PST-04 | P1 | 排序 | 编辑一篇后观察 | 本地按"最后动作时间"新在前（`:57-64`），与后端 `published_at desc nulls last, id desc` 一致口径 | [PostsView.vue:57-64](../web/src/views/PostsView.vue#L57)) · [PostRepository NEWEST_FIRST](../backend/src/main/java/com/rsshub/editorial/PostRepository.java) `[自]PublishApiTest` |
| TC-PST-05 | P2 | 空态 | 无文章 | "还没有文章，点上方"新文章"开始。"；表单区提示"选左侧的一篇，或点"新文章"。" | [PostsView.vue:243-244](../web/src/views/PostsView.vue#L243), [:262](../web/src/views/PostsView.vue#L262) `[B]` |
| TC-PST-06 | P1 | 状态筛选 | `GET /api/posts?status=draft` | 只回草稿；非法 status 不 500 | [PostController.java:43-46](../backend/src/main/java/com/rsshub/adminapi/PostController.java#L43) `[自]` `[C]` |

### 7.2 表单字段

| 编号 | P | 字段 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-PST-10 | P0 | [标题](../web/src/views/PostsView.vue#L271) | 留空保存 | **前端先拦**："标题和正文不能为空。"，不发请求；`maxlength=512` 与后端 DDL 对齐 | [PostsView.vue:161-163](../web/src/views/PostsView.vue#L161)) `[B]` |
| TC-PST-11 | P0 | [Slug](../web/src/views/PostsView.vue#L277) | 新建时填非法 slug（含 `/ ? # % & + 空格 引号 <> {} |` 等） | 400 `invalid_slug`；`SlugPolicy.FORBIDDEN` 逐字符拒绝 | [PostRepository SlugPolicy](../backend/src/main/java/com/rsshub/editorial/PostRepository.java) · [SlugPolicyTest](../backend/src/test/java/com/rsshub/editorial/SlugPolicyTest.java)（11 项）`[自]PublishApiTest` |
| TC-PST-12 | P0 | Slug 留空 | 新建时留空 | 按标题自动生成；生成后**不可改** | [PostsView.vue:171](../web/src/views/PostsView.vue#L171)) · [PostService.chooseSlug](../backend/src/main/java/com/rsshub/editorial/PostService.java) `[自]` |
| TC-PST-13 | P0 | Slug 冲突 | 用已存在的 slug 新建 | 409 `slug_taken` | [PostService](../backend/src/main/java/com/rsshub/editorial/PostService.java)（`findBySlug` → conflict）`[自]PublishApiTest` |
| TC-PST-14 | P0 | 编辑期 Slug | 打开已有文章 | **输入框根本不出现**（`v-if="isNew"`）；`PUT` 里带的 slug 被忽略，URL 不动 | [PostsView.vue:273](../web/src/views/PostsView.vue#L273), [:171](../web/src/views/PostsView.vue#L171) · [PostService.update](../backend/src/main/java/com/rsshub/editorial/PostService.java) `[自]PublishApiTest` |
| TC-PST-15 | P0 | [发布时间](../web/src/views/PostsView.vue#L286) | 状态 `scheduled` 且时间空 | 400 `publish_at_required`（创建与改状态两条路径都会拒） | [PostService.java:87-88,143-148](../backend/src/main/java/com/rsshub/editorial/PostService.java#L87) `[自]PublishApiTest` `[C]` |
| TC-PST-16 | P1 | 发布时间格式 | 填 `2026-11-01T00:00:00Z` / `2026-11-01 00:00` | 前者接受（ISO-8601 时刻）；后者 400（`Instant` 绑定失败走 ProblemDetail）。字段说明文字已写明"ISO 8601" | [PostsView.vue:285-287](../web/src/views/PostsView.vue#L285)) · [03 §10 时间口径](./design/03-technical-design.md) `[C]` |
| TC-PST-17 | P0 | [未来时间 + published](../web/src/views/PostsView.vue#L327) | 把 `publishedAt` 设未来并点 `published` | 可见性按 `VISIBLE` 判定：`status='published'` 或（`scheduled` 且 `published_at <= CURRENT_TIMESTAMP`）。用改系统时钟/直接改库来验"到点自动可见" | [PostRepository VISIBLE](../backend/src/main/java/com/rsshub/editorial/PostRepository.java) `[自]PublishApiTest（scheduled 到时可见）` |
| TC-PST-18 | P1 | [摘要 Markdown](../web/src/views/PostsView.vue#L293) | 写带 `**粗体**` 的摘要 | feed 的 `description` 优先取摘要渲染结果；留空时回落正文 `summarize()` | [PostsView.vue:292](../web/src/views/PostsView.vue#L292)) · [application.yml:102-103](../backend/src/main/resources/application.yml#L102) `[自]FeedRendererTest` |
| TC-PST-19 | P0 | [标签输入](../web/src/views/PostsView.vue#L299) | 填 `ebpf, kernel`（含空格/空项） | 按英文逗号切分并 trim，空项丢弃（`splitList`）；后端解析成 term，同名合并 | [PostsView.vue:150-158](../web/src/views/PostsView.vue#L150)) `[自]PublishApiTest` `[B]` |
| TC-PST-20 | P2 | 已用词条 | 观察 [:300-304](../web/src/views/PostsView.vue#L300)) | 标签/分类各显示最多 12 个 `title`（不是 slug），来自 `GET /api/posts/terms` | [PostController.java:76-79](../backend/src/main/java/com/rsshub/adminapi/PostController.java#L76) `[B]` |
| TC-PST-21 | P0 | [正文 Markdown](../web/src/views/PostsView.vue#L319) | 写表格/删除线/`<details>`/`<video>` | GFM 表格与删除线生效；**白名单内**的原始 HTML 通过（`details/summary/video/source/figure` 等） | [MarkdownRenderer](../backend/src/main/java/com/rsshub/editorial/MarkdownRenderer.java) `[自]MarkdownRendererTest（15 项）` |
| TC-PST-22 | P0 | 正文含脚本 | 写 `<script>alert(1)</script>` 与 `<img onerror=…>` | **保存成功但输出里没有 `<script>`、没有 `onerror`**（清洗器而非解析器是安全边界；`escapeHtml` 故意关掉，否则 `<details>` 也进不去） | [MarkdownRenderer RENDERER 注释](../backend/src/main/java/com/rsshub/editorial/MarkdownRenderer.java) `[自]` `[B]` |
| TC-PST-23 | P1 | 危险协议链接 | 正文放 `[x](javascript:alert(1))` | 链接被白名单协议（http/https/mailto）拒绝，`href` 不保留 | [MarkdownRenderer Safelist](../backend/src/main/java/com/rsshub/editorial/MarkdownRenderer.java) `[自]` |
| TC-PST-24 | P0 | [预览区](../web/src/views/PostsView.vue#L339) | 保存后观察 | 显示的是**服务端渲染并清洗后的 `body_html`**，不是浏览器二次转译 Markdown；保存后立即反映新内容 | [PostsView.vue:340-342](../web/src/views/PostsView.vue#L340) · [PostService.PostView bodyHtml](../backend/src/main/java/com/rsshub/editorial/PostService.java) `[B]` |

### 7.3 动作按钮

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-PST-30 | P0 | [保存](../web/src/views/PostsView.vue#L324) | 新建后点 | `POST /api/posts` → **201**；随后自动切到编辑态（`isNew=false`）、消息"已保存：<url>"、permalink 出现 | [PostController.java:53-57](../backend/src/main/java/com/rsshub/adminapi/PostController.java#L53) · [PostsView.vue:160-188](../web/src/views/PostsView.vue#L160)) `[自]PublishApiTest` `[B]` |
| TC-PST-31 | P0 | [保存](../web/src/views/PostsView.vue#L324) | 已有文章改正文再点 | `PUT /api/posts/{id}` → 200；消息"已更新：<slug>"；**`updatedAt` 变、`url` 不变** | [PostController.java:59-62](../backend/src/main/java/com/rsshub/adminapi/PostController.java#L59) `[自]` |
| TC-PST-32 | P0 | [保存](../web/src/views/PostsView.vue#L324) | 请求途连点 | 按钮 `:disabled="busy"` 拦住；不产生两个版本 | [PostsView.vue:324](../web/src/views/PostsView.vue#L324), [:165](../web/src/views/PostsView.vue#L165) `[B]` |
| TC-PST-33 | P0 | 五个状态按钮 | 逐个点（`draft/scheduled/published/unlisted/archived`） | `PATCH /api/posts/{id}/status`；成功消息"状态切换到 X。"；**当前状态那个按钮禁用**（`selected.status === status`），新文章时也全禁用（无 id） | [PostsView.vue:327-330](../web/src/views/PostsView.vue#L327)) · [PostController.java:64-67](../backend/src/main/java/com/rsshub/adminapi/PostController.java#L64) `[自]PublishApiTest` `[B]` |
| TC-PST-34 | P0 | 状态机：合法流转 | 按矩阵逐条走 | `draft→{scheduled,published,archived}`；`scheduled→{draft,published,archived}`；`published→{draft,unlisted,archived}`；`unlisted→{published,archived}`；`archived→{draft,published}` | [PostService.java:31-36](../backend/src/main/java/com/rsshub/editorial/PostService.java#L31) `[自]` `[C]` |
| TC-PST-35 | P0 | 状态机：非法流转 | `draft → unlisted`、`unlisted → draft`、`archived → scheduled` | **409 `illegal_transition`**，消息形如 `draft cannot become unlisted`；前端把这条当"状态机拒绝"而不是网络错显示 | [PostService.java:102-103](../backend/src/main/java/com/rsshub/editorial/PostService.java#L102) · [PostsView.vue:197-199](../web/src/views/PostsView.vue#L197)) `[自]PublishApiTest` `[C]` |
| TC-PST-36 | P1 | 幂等状态请求 | 点已是当前状态的按钮（绕过禁用，直接发请求） | 200 且不改数据（`existing.status()==target` 提前返回） | [PostService.java:99-101](../backend/src/main/java/com/rsshub/editorial/PostService.java#L99) `[自]` `[C]` |
| TC-PST-37 | P0 | 非法状态值 | `PATCH {status:"deleted"}` | 400 `invalid_status`，消息列出五个合法值（不让拼错变成约束错误） | [PostService.java:24,194-197](../backend/src/main/java/com/rsshub/editorial/PostService.java#L24) `[自]` `[C]` |
| TC-PST-38 | P0 | 空 status | `PATCH {}` / `{status:""}` | 400（`@NotBlank`） | [PostController.java:34](../backend/src/main/java/com/rsshub/adminapi/PostController.java#L34) `[C]` |
| TC-PST-39 | P0 | [删除](../web/src/views/PostsView.vue#L331) | 点 → 确认 | 先 `window.confirm`，文案明写"不可撤销，permanent URL 会一并消失"；确认后 `DELETE` → **204**，列表刷新、编辑器清空 | [PostsView.vue:205-221](../web/src/views/PostsView.vue#L205)) · [PostController.java:69-73](../backend/src/main/java/com/rsshub/adminapi/PostController.java#L69) `[自]` `[B]` |
| TC-PST-40 | P0 | [删除](../web/src/views/PostsView.vue#L331) 后的 feed | 删除一篇已发布文章后拉 `/feed.rss` | 该条**消失**，且 `ETag` 变化（指纹含条目 id 摘要） | [EtagService.java:22-27](../backend/src/main/java/com/rsshub/feedrender/EtagService.java#L22) `[自]` `[C]` |
| TC-PST-41 | P1 | 新建态点删除 | 未保存就观察动作区 | 删除按钮**不出现**（`v-if="selected"`） | [PostsView.vue:331](../web/src/views/PostsView.vue#L331) `[B]` |
| TC-PST-42 | P1 | 错误提示格式 | 制造 409 | 消息区 `alert-danger` 红底，内容 `illegal_transition: draft cannot become unlisted`（code 前置，可直接粘进工单） | [PostsView.vue:92-94](../web/src/views/PostsView.vue#L92), [:335](../web/src/views/PostsView.vue#L335) `[B]` |
| TC-PST-43 | P0 | [permalink](../web/src/views/PostsView.vue#L265) | 点链接 | 新标签打开 `/blog/<slug>`（**服务端渲染**页，非 SPA）；旁边固定说明"slug 一旦创建就不能改，编辑标题不会挪动 URL" | [PostsView.vue:264-267](../web/src/views/PostsView.vue#L264) · [PublicViewController.java:58](../backend/src/main/java/com/rsshub/editorial/PublicViewController.java#L58) `[B]` |
| TC-PST-44 | P0 | 改标题后 permalink | 改标题保存，再访问旧 URL | **仍可访问**，feed 里 `guid`/`atom:id`/JSON `id` 全部不变（FR-1.5 的核心） | [FR-1.5](./design/01-requirements.md#L68) · [rss-guide.md:616](./rss-guide.md#L616) `[自]PublishApiTest` `[C]` |
| TC-PST-45 | P1 | 发布→可见时延 | 点 `published` 后轮询 `/feed.rss` | ≤60s 出现在 feed 里（指纹自然失效，无需手工刷新） | [FR-1.1/1.4](./design/01-requirements.md#L64) · [01 §5 时延行](./design/01-requirements.md#L132) `[C]` |

---

## 8. `PUB` 公开出口（**页面上没有按钮**，全部是 URL 级断言）

这一节没有控件，但它是 [FR-1.2/1.3/1.4/1.5](./design/01-requirements.md#L65) 唯一可验证的地方，也是"三个格式都能过校验"这条成功判据的落点。出口共 **3 条路由模板 × 3 个格式**：

| 出口 | 代码 | 格式数 |
|---|---|---|
| `GET /feed.{format}` 全站 | [PublicFeedController.java:45](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L45) | rss / atom / json |
| `GET /tag/{slug}.{format}` | [:63](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L63) | 同上 |
| `GET /category/{slug}.{format}` | [:69](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L69) | 同上 |
| `GET /` 与 `GET /blog` 列表页 | [PublicViewController.java:45](../backend/src/main/java/com/rsshub/editorial/PublicViewController.java#L45) | HTML（Thymeleaf SSR） |
| `GET /blog/{slug}` 文章页 | [:58](../backend/src/main/java/com/rsshub/editorial/PublicViewController.java#L58) | HTML |

> **URL 形态与需求文档不一致**：[FR-1.2](./design/01-requirements.md#L65) 写 `/feed/rss`，实现与设计 [03 §6.2](./design/03-technical-design.md) 都是 `/feed.{format}`。按"页面 URL 与 feed URL 不能相同"的硬性要求（[rss-guide.md:548](./rss-guide.md#L548)），实现口径是对的、需求文档要改 —— 见 13 节 D-01。

### 8.1 匿名可见与会话卫生

| 编号 | P | 请求 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|
| TC-PUB-01 | P0 | 无 cookie `GET /feed.rss` | **200**；`Content-Type: application/rss+xml;charset=UTF-8`；`Cache-Control: max-age=60, must-revalidate, private` | [PublicFeedController.java:35,111-114](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L35) · [03 §6.3 表](./design/03-technical-design.md) `[自]PublishApiTest` `[C]` |
| TC-PUB-02 | P0 | `GET /feed.atom` | 200，`application/atom+xml;charset=UTF-8` | [03 §6.3](./design/03-technical-design.md) `[C]` |
| TC-PUB-03 | P0 | `GET /feed.json` | 200，`application/feed+json;charset=UTF-8` | [03 §6.3](./design/03-technical-design.md) `[自]` `[C]` |
| TC-PUB-04 | P0 | 访问 `/`、`/blog`、`/blog/{slug}`、`/tag/x.rss` | 全部 200，匿名 | [SecurityConfig.java:61](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L61) `[自]PublishApiTest` `[C]` |
| TC-PUB-05 | P0 | **不建会话** | 匿名抓 feed 后，响应 `Set-Cookie` 不含 `RSSHUB_SESSION`；会话表不被爬虫刷爆 | [03 §9.1 公开域行](./design/03-technical-design.md) `[自]PublishApiTest（本会话实测 cookie jar 无 RSSHUB_SESSION）` `[C]` |
| TC-PUB-06 | P1 | 三个出口内部一致 | 同一篇文章在 rss/atom/json 里 ID 相同（都取永久 URL），标题/时间只有格式差别 | [FR-1.5](./design/01-requirements.md#L68) · [rss-quickref.md:133](./rss-quickref.md#L133) `[自]PublishApiTest 三格式对齐` `[C]` |

### 8.2 ETag / 304 / 缓存

| 编号 | P | 请求 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|
| TC-PUB-10 | P0 | `GET /feed.rss` 看 `ETag` | 形如 `W/"16位hex"`；**弱验证器**是刻意的（多机产出的字节差异不该被当成内容变化） | [EtagService.java:7-27](../backend/src/main/java/com/rsshub/feedrender/EtagService.java#L7) `[自]` `[C]` |
| TC-PUB-11 | P0 | 内容不变再请求 | 同一 ETag；带 `If-None-Match: <同一值>` → **304 且无 body**；短路发生在渲染之前 | [PublicFeedController.java:101-104](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L101) · [03 §6.4](./design/03-technical-design.md) `[自]PublishApiTest` `[C]` |
| TC-PUB-12 | P0 | 弱比较语义 | 发 `If-None-Match: "<去掉 W/ 前缀的同值>"`、`"a, <同值>"`、`*` | 三者都判为命中 → 304 | [EtagService.java:34-57](../backend/src/main/java/com/rsshub/feedrender/EtagService.java#L34)（RFC 9110 §8.8.3.2 弱比较）`[自]PublishApiTest` `[C]` |
| TC-PUB-13 | P0 | 内容变则 ETag 变 | 发布新文章后 `GET /feed.rss` | ETag 改变（指纹 = `cacheKey|format|maxUpdatedAt|count|idDigest`） | [EtagService.java:22-27](../backend/src/main/java/com/rsshub/feedrender/EtagService.java#L22) `[自]` `[C]` |
| TC-PUB-14 | P1 | 同一内容不同格式 | 比 rss 与 atom 的 ETag | **必须不同**：种子含出口路径与格式，防止代理把 XML 喂给 JSON 客户端 | [EtagService.java:14-16,23](../backend/src/main/java/com/rsshub/feedrender/EtagService.java#L14) `[自]` `[C]` |
| TC-PUB-15 | P1 | `Last-Modified` | 有内容时 | 等于最新条目时间；**空出口没有 `Last-Modified`**（该头可选，无值就不写） | [PublicFeedController.java:115-117](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L115) `[自]FeedRendererTest` |
| TC-PUB-16 | P1 | `ttl` 与 `max-age` | 观察 yml `ttl-minutes: 60` vs `max-age=60` | **数值刻意不一致**：`max-age`（秒）只管边缘缓存，`ttl`（分钟）是给订阅者的轮询建议。测试时别把它当 bug 提 | [03 §6.4 第 1 条](./design/03-technical-design.md) · [application.yml:108](../backend/src/main/resources/application.yml#L108) |

### 8.3 writer 输出契约（七条规则，违反任一条都算 bug）

| 编号 | P | 规则 | 断言方法 | 依据 / 现状 |
|---|---|---|---|---|
| TC-PUB-20 | P0 | 1 XML 声明从字节起始 | `xxd` 前 5 字节 = `<?xml`；无 BOM、无前导空行/注释 | [03 §6.3 规则1](./design/03-technical-design.md) · [rss-guide.md:610](./rss-guide.md#L610) `[自]FeedWritersTest` |
| TC-PUB-21 | P0 | 2 统一转义 | 标题含 `A & B <x>` → 输出 `A &amp; B &lt;x&gt;`；URL query 里裸 `&` 必须 `&amp;` | [03 §6.3 规则2](./design/03-technical-design.md) · [rss-guide.md:612](./rss-guide.md#L612) `[自]` |
| TC-PUB-22 | P1 | 3 命名空间在根声明 | 根元素声明 atom/content/dc/…；URI 逐字取自 [rss-quickref.md:143](./rss-quickref.md#L143)；GeoRSS 无尾斜杠 | [03 §6.3 规则3](./design/03-technical-design.md) `[自]` |
| TC-PUB-23 | P1 | 4 CDATA | HTML 正文包 CDATA；正文含 `]]>` 时拆段转义且**不产生非法序列** | [03 §6.3 规则4](./design/03-technical-design.md) · [rss-guide.md:613](./rss-guide.md#L613) `[自]` |
| TC-PUB-24 | P0 | 5 条目上限 | 站上有 120 篇 → feed 只出 50，且**新在前**；上限来自配置不是硬编码 | [application.yml:107](../backend/src/main/resources/application.yml#L107) · [rss-guide.md:618,620](./rss-guide.md#L618) `[自]FeedRendererTest（seenLimit=50 of 120）` |
| TC-PUB-25 | P0 | 6 异常不降级成 HTML | 让渲染抛错 → 5xx + JSON ProblemDetail，**绝不 200 + HTML 错误页** | [03 §6.3 规则6](./design/03-technical-design.md) · [rss-guide.md:619](./rss-guide.md#L619) `[自]` |
| TC-PUB-26 | P0 | 7 JSON Feed ≤100KB | 塞入超预算正文 → 保留最新一篇全文、其余降级为摘要+回链，文件 ≤ `json-max-bytes`；服务端记 `degraded` 警告 | [application.yml:106-109](../backend/src/main/resources/application.yml#L106) · [03 §6.3 规则7](./design/03-technical-design.md) `[自]FeedRendererTest + PublishApiTest` |
| TC-PUB-27 | P0 | 必填项齐备（RSS） | channel 有 `title/link/description`；`description` 未配置时**生成兜底句而不是 500** | [03 §6.3 表](./design/03-technical-design.md) · [application.yml:102-103](../backend/src/main/resources/application.yml#L102) `[自]` |
| TC-PUB-28 | P0 | 必填项齐备（Atom） | feed 级 `id/title/updated` 各恰好一个；**空出口也必须有 `<updated>`**（RFC 4287 §4.1.1 强制），无条目时回落 `1970-01-01T00:00:00Z` | [AtomWriter.java:33,109-120](../backend/src/main/java/com/rsshub/feedrender/AtomWriter.java#L33) `[自]FeedWritersTest` —— 这是 M2 修的那个空出口崩溃，回归必测 |
| TC-PUB-29 | P1 | Atom 无 content 的约束 | 无正文条目必须带 `rel=alternate` 链接；`content` 带 `src` 或非 text/XML 基型时必须有 `summary` | [03 §6.3 校验硬约束](./design/03-technical-design.md) · [rss-quickref.md:21](./rss-quickref.md#L21) `[自]` |
| TC-PUB-30 | P0 | 日期格式 | RSS 用 RFC 822 四位年 + `+0800`/大写 `Z`；Atom/JSON 用 RFC 3339（`T`/`Z` 大写、带偏移） | [03 §6.3 表](./design/03-technical-design.md) · [rss-guide.md:615](./rss-guide.md#L615) `[自]` |
| TC-PUB-31 | P1 | `atom:link rel=self` | 每个 RSS 出口含 `<atom:link … rel="self">`；Atom 用 `<link rel="self" type="application/atom+xml">`；路径与实际请求一致 | [rss-guide.md:558](./rss-guide.md#L558) · [03 §6.2](./design/03-technical-design.md) `[自]` |
| TC-PUB-32 | P1 | 附件三要素 | 有 enclosure 时 RSS 出 `url/length/type`、Atom 出 `link rel=enclosure`、JSON 出 `attachments[]`（`url`+`mime_type` 必填） | [03 §6.3 表](./design/03-technical-design.md) `[自]` |
| TC-PUB-33 | P2 | 空站点仍合法 | 一篇文章都没有 → 三格式仍 200 且 XML/JSON 可解析（RSS 允许零 item，`lastBuildDate` 缺省不写） | [01 §7 兼容行](./design/01-requirements.md#L137) `[自]PublishApiTest` `[C]` |
| TC-PUB-34 | P1 | 严格解析 | 三份输出各跑一次严格 XML 解析 / JSON 解析，0 错 | [DoD FR-1.4/1.5 行](./design/01-requirements.md#L175) `[自]` + `[B]` 人工跑 `validator.w3.org/feed`（**需要公网 URL，本机跑不了**，见 12 节 BLK-06） |

### 8.4 切片与发现

| 编号 | P | 请求 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|
| TC-PUB-40 | P0 | `GET /tag/<slug>.rss` | 200，只含该标签文章；`<title>` 用 **term.title 而不是 slug** | [PublicFeedController.java:63](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L63) · 真 HTTP [smoke.sh:495-497](../backend/smoke.sh#L495) `[自]PublishApiTest` `[C]` |
| TC-PUB-41 | P0 | `GET /category/<slug>.atom` | 200，只含该分类 | [:69](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L69) · 真 HTTP [:498](../backend/smoke.sh#L498) `[自]` `[C]` |
| TC-PUB-42 | P0 | 不存在的标签/分类 | 404 `unknown_tag` / `unknown_category`（是"站点没有这个 URL"，不是"你参数写错了"） | [FeedRenderer.sourceFor](../backend/src/main/java/com/rsshub/feedrender/FeedRenderer.java) · 真 HTTP [:501-502](../backend/smoke.sh#L501) `[自]PublishApiTest` `[C]` |
| TC-PUB-43 | P0 | 未知格式后缀 `GET /feed.opml` | **404 `unknown_format`**，消息列出 rss/atom/json | [PublicFeedController.java:121-127](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L121) `[自]PublishApiTest` `[C]` |
| TC-PUB-44 | P0 | `GET /` 或 `/blog/{slug}` 的 `<head>` | 注入 `rel=alternate`：三个全站格式 + 每个标签/分类源；文章页**额外**注入本文第一个标签的源；feed URL 与页面 URL 不同 | [PublicViewController.java:54,66-71](../backend/src/main/java/com/rsshub/editorial/PublicViewController.java#L54) · [03 §6.2](./design/03-technical-design.md) · [rss-guide.md:536](./rss-guide.md#L536), [:548](./rss-guide.md#L548) `[自]PublishApiTest` `[C]` |
| TC-PUB-45 | P0 | 草稿不可见 | `status=draft` 的文章：`/blog/{slug}` → **404 `unknown_post`（不是 403）**；`/feed.*` 不含它；`unlisted` 有 URL 但不进 feed | [PublicViewController.java:62-63](../backend/src/main/java/com/rsshub/editorial/PublicViewController.java#L62) `[自]PublishApiTest` `[C]` |
| TC-PUB-46 | P1 | 基址唯一真源 | 换 `RSSHUB_PUBLISH_BASE_URL` 重启 → 所有 `guid`/`self`/绝对 URL 一起变。**这正是"定了就别改"的代价**，测试要在副本库上做 | [application.yml:115-117](../backend/src/main/resources/application.yml#L115) · [rss-guide.md:616](./rss-guide.md#L616) `[C]` |

---

## 9. `FET` 抓取行为（后台无按钮，但每一行都在 UI 上有后果）

判定顺序按 [03 §4.3](./design/03-technical-design.md) 写死的 0–7 共八步。这些用例需要可控源站，**本机可用的两种做法**：① 单测里用 `backend/src/test/java/com/rsshub/feedfetch/LocalFeedServer.java`（JDK `com.sun.net.httpserver`，非 WireMock——`pom.xml` 里没有这个依赖）+ in-memory H2；② 真 HTTP 侧用 `.dev-logs/smoke-feed-server.js`（Node）当源站 + 一次性文件库 + `--rsshub.ssrf.allow-private-prefixes=127.`。原写法推荐的 `python -m http.server` 在本机跑不了（**没装 python**，同 [rss-quickref.md §14 的本机环境备注](./rss-quickref.md)），而且它也不会发 304/`Retry-After`，控不了状态码，本来就不够用。

| 编号 | P | 源站响应 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|
| TC-FET-01 | P0 | 200 新内容 | 条目落库、`entry_count`/`unread` 增加；写 `etag/last_modified/last_http_status/avg_fetch_ms/last_change_at`；`fail_count=0`；重排 `fire_at` | [03 §4.3 步 1/7](./design/03-technical-design.md), [03 §4.3 步 7](./design/03-technical-design.md) `[自]FeedFetchServiceTest` |
| TC-FET-02 | P0 | 304 | 状态算成功、`fail_count=0`、`notfound_streak=0`、写下一次 `fire_at`；**不发正文**；`etag` 缺省时保留原值 | [03 §4.3 步 3](./design/03-technical-design.md) · [rss-guide.md:499](./rss-guide.md#L499) `[自]` |
| TC-FET-03 | P0 | 请求带条件头 | 第二次请求必须携带 `If-None-Match`（有 etag 时）/`If-Modified-Since`；`Accept` 五值优先级顺序正确；`User-Agent` 每请求显式设置 | [03 §4.3 步 1](./design/03-technical-design.md) · [rss-quickref.md:102](./rss-quickref.md#L102) `[自]` |
| TC-FET-04 | P0 | 301/308 | 跟随（≤3 跳）且**更新 `canonical_url`**；UI 出现"原地址 …（来源搬过家）" | [03 §4.3 步 3](./design/03-technical-design.md) · [FeedsView.vue:470-472](../web/src/views/FeedsView.vue#L470) `[自]` |
| TC-FET-05 | P1 | 302/307 | 跟随但**不改** `canonical_url`，只记日志 | [03 §4.3 步 3](./design/03-technical-design.md) `[自]` |
| TC-FET-06 | P0 | 410 | `state='gone'`，任务转 `cancelled`，UI"下次"列显示"不在队列"；点"立即抓取"得 409 | [03 §4.3 步 3](./design/03-technical-design.md) · [rss-guide.md:510](./rss-guide.md#L510) `[自]` |
| TC-FET-07 | P0 | 429 + `Retry-After` | **严格读**该头（秒或 HTTP-date 两种形态都要测）；无头则按退避；**绝不立即重试**，封顶 `backoff-cap` | [03 §4.3 步 3](./design/03-technical-design.md) · [DoD FR-2.2 行](./design/01-requirements.md#L173) `[自]` |
| TC-FET-08 | P0 | 5xx / 网络异常 / 超时 | `fail_count++`；退避 `min(base × 2^fail_count, 24h)` + jitter（指数位移先夹到 20 防溢出） | [03 §4.3 步 3](./design/03-technical-design.md) · [FetchPolicy.java:92-100](../backend/src/main/java/com/rsshub/feedfetch/FetchPolicy.java#L92) `[自]FetchPolicyTest` |
| TC-FET-09 | P1 | 404 | `notfound_streak++`，未超阈值按退避重试；持续 404 超过 `notfound-gone-days`(30d) 转 `gone` | [03 §4.3 步 3](./design/03-technical-design.md) · [application.yml:90](../backend/src/main/resources/application.yml#L90) `[自]` |
| TC-FET-10 | P0 | 401/403 | `state='error'`、`last_error='auth_required'`、退避拉到 24h、界面提示补凭证 | [03 §4.3 步 3](./design/03-technical-design.md) `[自]` |
| TC-FET-11 | P0 | 200 但体是 HTML | 走"嗅探 feed 地址"分支并提示换订阅地址；**原始字节仍留存**（那是"订阅地址填成网页"的唯一现场证据） | [03 §4.3 步 3/4](./design/03-technical-design.md) `[自]` |
| TC-FET-12 | P0 | 非法 XML（200） | 标 `parse_error` 并**保留原始字节**，退避 1h 先重试一次（排除半截响应）；解析层不抛给调用方 | [03 §4.3 步 5](./design/03-technical-design.md) `[自]FeedParserTest（11 项）` |
| TC-FET-13 | P0 | 超过 `max-bytes`(10 MiB) | 立即断流，记 `size_limit`；**不算源站故障**：退避 ×0.5、`fail_count` 不 ++ | [03 §4.3 步 2](./design/03-technical-design.md) · [application.yml:84](../backend/src/main/resources/application.yml#L84) `[自]` |
| TC-FET-14 | P0 | 同一 feed 连抓 3 次 | 条目数**完全不变**（`(feed_id, guid)` 幂等） | [DoD FR-2.3 行](./design/01-requirements.md#L174) `[自]EntryIngestServiceTest` `[C]` |
| TC-FET-15 | P0 | 改 `pubDate` 不改 GUID | 仍同一条目 + 新增一条 `entry_revision`；`revision` 递增 | [DoD FR-2.3 行](./design/01-requirements.md#L174) `[自]` |
| TC-FET-16 | P0 | 首次订阅 | 只建基线：`baseline=true` 的条目**不进未读** | [FR-2.3](./design/01-requirements.md#L75) `[自]` |
| TC-FET-17 | P1 | GUID 归一优先级 | `isPermaLink="false"` 的 guid **不得**被当 URL；缺 guid 才逐级回落（link → 内容哈希），并在 UI 标"id 不稳定" | [rss-quickref.md:133](./rss-quickref.md#L133) · [03 §5.3](./design/03-technical-design.md) `[自]` |
| TC-FET-18 | P1 | 容错语料 | GBK 声明成 UTF-8、缺 `title`、日期写成 `-0500 EST` 之类缩写、混合命名空间 → **解析不抛**，降级字段可见 | [03 §13 容错语料行](./design/03-technical-design.md) `[自]DocumentDecoderTest / FlexibleDatesTest（11 项）` |
| TC-FET-19 | P0 | 礼貌限速 | 同域名两次请求间隔 ≥30s（`per-host-gap`）；全局并发 ≤6（信号量在认领**之前**取，避免"已认领却在等许可"） | [01 §5 吞吐行](./design/01-requirements.md#L133) · [FetchDispatcher.java:22-25,65-67](../backend/src/main/java/com/rsshub/feedfetch/FetchDispatcher.java#L22) · [application.yml:86,91](../backend/src/main/resources/application.yml#L86) `[自]HostPolitenessTest` |
| TC-FET-20 | P1 | 轮询节奏 | 无 `ttl` 的源按 `interval=60m` + jitter；有 `ttl` 的取 `max(min-interval=5m, ttl)` | [application.yml:83-85](../backend/src/main/resources/application.yml#L83) · [FetchPolicy.java:80-85](../backend/src/main/java/com/rsshub/feedfetch/FetchPolicy.java#L80) `[自]` |
| TC-FET-21 | P1 | 调度器节拍 | 队列排空后每 15 秒醒一次（`fixedDelay=PT15S`，首次延迟 5s）；**这不是抓取间隔**，别和 `interval` 混淆 | [FetchDispatcher.java:62](../backend/src/main/java/com/rsshub/feedfetch/FetchDispatcher.java#L62) `[C] 看日志` |
| TC-FET-22 | P0 | 崩溃遗留任务 | worker 死掉后停在 `running` 的行，会在"连接+读取超时+2 分钟"之后被回收并 30s 后重排 | [FetchDispatcher.java:41-42,96-101](../backend/src/main/java/com/rsshub/feedfetch/FetchDispatcher.java#L96) `[自]TaskQueueTest` |
| TC-FET-23 | P0 | 并发认领不重复 | 4 线程同抢一批任务 → 无重复落库（H2 用乐观声明按受影响行数，PG 叠 `SKIP LOCKED`） | [03 §13 幂等与并发行](./design/03-technical-design.md) · [03 §14 O9](./design/03-technical-design.md) `[自]TaskQueueTest` |
| TC-FET-24 | P1 | 原始 XML 留存 | 抓完后 `data/raw` 有内容寻址文件（重复抓取不重复占盘）；保留 ≥30 天 | [03 §4.3 步 4](./design/03-technical-design.md) · [application.yml:93-96](../backend/src/main/resources/application.yml#L93) `[自]` |
| TC-FET-25 | P1 | 断掉的源不影响阅读 | 某源连错 3 天，其历史条目仍完整可读、已读状态不丢 | [01 §5 可靠行](./design/01-requirements.md#L134) `[B]` |

---

## 10. `API` 端点级负例与边界（无 UI 入口，但必须测）

| 编号 | P | 端点 | 场景 | 预期 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-API-01 | P0 | `POST /api/feeds` | `url` 缺失/空 | 400（`@Valid @NotBlank`） | [FeedController.java:47-49,80-84](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L47) `[自]` |
| TC-API-02 | P0 | `POST /api/posts` | `title` 或 `bodyMd` 空 | 400（`@NotBlank`）；不产生半条记录 | [PostController.java CreateRequest](../backend/src/main/java/com/rsshub/adminapi/PostController.java) `[自]` |
| TC-API-03 | P0 | `GET/PUT/PATCH/DELETE /api/*` | 未登录 | **401**，不是 302；错误体是 JSON | [SecurityConfig.java:62,86-87](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L62) `[自]AuthGateTest` `[C]` |
| TC-API-04 | P0 | `GET /api/private-ish` 任何未定义路径 | 例如 `/private/index` | 401/404 且**不由应用服务**（`anyRequest().denyAll()`）；用 `/private/index` 探测而不是 `/`（`/` 自 M2 起公开） | [SecurityConfig.java:63](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L63) `[自]AuthGateTest` `[C]` |
| TC-API-05 | P1 | `GET /api/feeds/{id}` | 不存在 | 404 `feed_not_found` | [FeedController.java:65-68](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L65) `[自]` |
| TC-API-06 | P1 | `GET /api/feeds/groups` | 有分组 | 去重 + 排序的名称数组（收件箱分组区的数据源） | [FeedController.java:123-135](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L123) `[自]` |
| TC-API-07 | P1 | `GET /api/entries/counters` | 任意 | 四个计数与列表页返回的 `counters` 一致（两处同源，避免"三个视图三个答案"） | [EntryController.java:145-148](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L145) · 真 HTTP [smoke.sh:505-509](../backend/smoke.sh#L505)（含匿名 401）`[自]InboxApiTest` `[C]` |
| TC-API-08 | P1 | 时间字段进出 | 任一响应 | 一律 ISO-8601 时刻；本地化是前端的业务（`formatAt`/`ago`），不在 API 层混用 | [03 §10 约定](./design/03-technical-design.md) · [time.ts](../web/src/utils/time.ts) `[C]` |
| TC-API-09 | P2 | 可空字段 | `lastHttpStatus` 未知时 | **键不存在**，不是 `null`（前端 TS 全部写成可选属性） | [application.yml:45](../backend/src/main/resources/application.yml#L45) · [03 §10 约定](./design/03-technical-design.md) `[C]` |
| TC-API-10 | P1 | 双方言 DDL | `./mvnw test` | H2 与 PostgreSQL 两份迁移**逐列一致**（有专门单测守这个门，现覆盖 V1–V7）。V6 只建索引不建表、V7 只给已有表加列，两者对"表/列集合相等"的循环都是**盲的**（对它们的断言退化成"两个方言解析出的表集合都为空且相等"）——列进 `MIGRATIONS` 的价值在于**漏改一个方言时文件缺失会立刻炸**，V7 另有一条专门的加列对齐断言 | [MigrationDialectAlignmentTest](../backend/src/test/java/com/rsshub/MigrationDialectAlignmentTest.java)（5 项，名单在 [:37-39](../backend/src/test/java/com/rsshub/MigrationDialectAlignmentTest.java#L37)，V7 的加列断言是 `addedColumnsMatchBetweenTheTwoDialects` [:111-118](../backend/src/test/java/com/rsshub/MigrationDialectAlignmentTest.java#L111)）`[自]` |
| TC-API-11 | P0 | 模块边界 | `./mvnw verify` | 7 条 ArchUnit 规则全过：`feedparse ↮ feedrender`、`feedrender ↮ feedstore`、外部 HTTP 只在 `feedfetch/notify/ai/security`、`common` 在底、`adminapi` 是叶、无环 | [ArchitectureTest](../backend/src/test/java/com/rsshub/ArchitectureTest.java) `[自]` 口径见 [03 §13 第 1 条](./design/03-technical-design.md)（规则要主动破坏一次才算数） |

---

## 11. `SEC` 安全 与 `MIG` 可迁移

### 11.1 SSRF（本项目第一安全风险，DoD 有专门用例）

抓取任意 URL = 天然的 SSRF 面（[01 §5 安全行](./design/01-requirements.md#L136)）。每条都必须"被拒 + 有日志"。

| 编号 | P | 输入（贴进订阅 URL 或塞进 OPML） | 预期 | 依据 / 现状 |
|---|---|---|---|---|
| TC-SEC-01 | P0 | `http://127.0.0.1/feed`、`http://localhost/x` | 拒绝（回环） | [DoD 安全行](./design/01-requirements.md#L182) · [03 §9.2](./design/03-technical-design.md) `[自]SsrfGuardTest` `[C]` |
| TC-SEC-02 | P0 | `http://169.254.169.254/latest/meta-data/` | 拒绝（云元数据） | [03 §9.2](./design/03-technical-design.md) `[自]` `[C]` |
| TC-SEC-03 | P0 | `http://10.0.0.5/`、`192.168.x`、`172.16.x`、`100.64.0.0/10`（CGNAT）、`::1`、`fc00::/7`、链路本地、组播、`0.0.0.0` | **全部**拒绝；A/AAAA **逐条**判定，不是"只解析第一个" | [03 §9.2](./design/03-technical-design.md) `[自]`（M0 实测 15 条私网/保留地址，见 [03 §13 M0 段落](./design/03-technical-design.md)） |
| TC-SEC-04 | P0 | 302/301 **跳到内网** | 每一跳都重新过守卫并拒绝；`max-redirects` 用手动跟随实现，不交给 HTTP 客户端 | [03 §4.3 步 3](./design/03-technical-design.md) · [FetchPolicy.java:17 注释](../backend/src/main/java/com/rsshub/feedfetch/FetchPolicy.java#L17) `[自]FeedFetchServiceTest` `[C]` |
| TC-SEC-05 | P0 | DNS 重绑定（先解析到公网、再解析到内网） | 校验过的 IP 被**原样钉住**用于本次连接（消 TOCTOU） | [03 §9.2](./design/03-technical-design.md) · [PinnedAddressResolver.java](../backend/src/main/java/com/rsshub/feedfetch/PinnedAddressResolver.java) `[自]DnsPinsTest`（10 项） |
| TC-SEC-06 | P0 | 非 http(s)：`file://`、`gopher://`、`jar://` | 400 `scheme_not_allowed`（在 normalise 层拒，消息比守卫精确） | [SubscriptionService.java:430-433](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L430) `[自]` `[C]` |
| TC-SEC-07 | P1 | 私网白名单 | `rsshub.ssrf.allow-private-prefixes` 显式登记后**内网源可订阅**，且日志记"用户显式授权"；默认空 ⇒ 无私网例外 | [application.yml:73-75](../backend/src/main/resources/application.yml#L73) · [03 §9.2](./design/03-technical-design.md) `[自]`（白名单是唯一回门） |
| TC-SEC-08 | P0 | 日志脱敏 | 检查 `.dev-logs/backend.log`：不出现 token / `Authorization` / `Cookie` 值 | [03 §9.2 配套](./design/03-technical-design.md) `[B/C 人工读日志]` |
| TC-SEC-09 | P0 | actuator 面 | 配置只暴露 `health` 且 `show-details: never`；**测试不去探测 `heapdump`/`shutdown`**，只核配置与 `SecurityConfig` 的匹配器 | [application.yml:47-59](../backend/src/main/resources/application.yml#L47) `[自]AuthGateTest` |
| TC-SEC-10 | P0 | 口令来源 | 空库 + 空口令 → **拒绝启动**（杜绝默认口令上线）；口令只在 `backend/dev.env`（已被 gitignore）与环境变量里 | [application.yml:69-72](../backend/src/main/resources/application.yml#L69) · [start-dev.sh](../start-dev.sh) 前置检查 `[C 用一次性内存库验]` |
| TC-SEC-11 | P1 | 口令哈希口径 | 库里是 `{bcrypt}` 前缀的 delegating 编码；**Argon2id 尚未落地（O7）**，测试断言"前缀可辨识 + 非明文"，不断言算法 | [03 §9.1 落地表](./design/03-technical-design.md), [03 §14 O7](./design/03-technical-design.md) `[自]` |
| TC-SEC-12 | P1 | 错误信息不泄漏 | 私网地址被拒时，返回给前端的消息**不含内网拓扑细节** | [SubscriptionService.java:420-423 注释](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L420) `[C]` |

> 本轮**不执行**的两类动作（硬性约束）：浏览器自动化跑登录流、读取或比对口令字段/哈希。口令只向操作员索取，绝不猜、绝不写进脚本。

### 11.2 可迁移与恢复

| 编号 | P | 场景 | 预期 | 依据 |
|---|---|---|---|---|
| TC-MIG-01 | P0 | OPML 往返 | 导出 → 清空 → 导入，订阅数与分组一致 | [FR-2.6](./design/01-requirements.md#L78) `[自]` |
| TC-MIG-02 | P0 | feed URL 十年不变形态 | `/feed.{format}`、`/tag/{slug}.{format}` 不含 id、不含时间戳；slug 不可变 | [03 §6.2](./design/03-technical-design.md) · [01 §5 可迁移行](./design/01-requirements.md#L138) `[C]` |
| TC-MIG-03 | P1 | 原始 XML 可重解析 | 取 `data/raw` 里的字节重新过一遍解析层，结果与库中条目一致 | [01 §5 可靠行](./design/01-requirements.md#L134) `[自]` |
| TC-MIG-04 | P1 | 备份→恢复 | 备份后在干净实例恢复，三格式仍能输出且 GUID 一致 | [DoD 备份行](./design/01-requirements.md#L183) —— **`deploy/` 整体推迟（O13，[03 §14](./design/03-technical-design.md)），本用例当前 BLOCKED** |

---

## 12. 未实现范围（M1 收尾 / M4–M6）阻塞项

**这一节是"没有按钮所以不测"的清单，不是缺陷清单。** 逐条对应需求编号，防止测试时把"文档写了、代码没有"误报成 bug，也防止交付时漏掉未验范围。**2026-10-18 复核：BLK-03/05/07 随 M3 关闭**（保留行，把"缺什么"改写成"实际交付了什么 + 还缺什么"，方便复跑时对号入座）；剩下的 M3 尾巴只有一项——邮件通道（O15）。**2026-11-01 复核：BLK-09 随 M5 关闭**（写法同上），M5 的尾巴是"投稿入口与人工审核队列未做"与两条只能人工的门禁（真订阅一个跨源 feed、浏览器走查 `RPL`）。**同日 BLK-02 随 F1.6 变更公告关闭**（写法同上）。**同日 BLK-01 随 F2.4 关闭**（写法同上）：键盘流、桌面通知与"只看关键词命中"/标签过滤都已落地，`web/src` 里 `keydown`/`Notification` 从"零命中"变成两个 composable 文件。**关闭的含义是"代码与 `[自]`/`[C]` 断言都在"，不等于"人在浏览器里走查过"**——3.5/3.6/3.7 三节里带 `[B]` 的行仍然全部未执行。于是 **M0–M3.5 + M5 + F1.6 + F2.4 范围内已无"功能没做"的阻塞项**，剩下的只有三类：M3 的邮件通道尾巴（O15，记在 BLK-05 行内）、各里程碑的人工项（真阅读器订阅、浏览器走查）、两条非功能尾巴（BLK-11 指标埋点、BLK-12 CI）。

| 编号 | 需求 | 缺什么（实测证据） | 状态 | 何时可测 |
|---|---|---|---|---|
| BLK-01 | [FR-2.4](./design/01-requirements.md#L76) 键盘流 j/k/n/s/m + 桌面通知 + 标签/关键词命中过滤 | ✅ **F2.4 已实现（2026-11-01）**：**过滤**——`GET /api/entries` 新增 `?alerted=`（三态；EXISTS/NOT EXISTS 读 `notification_log`，与条目行红标同源，[EntryFinder.java:336-342](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L336)）与 `?category=`（整元素匹配编码后的 JSON token，[EntryFinder.java:343-354](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L343)），加 `V6__alert_filter_index.sql`（仅索引）。**键盘**——[useInboxKeys.ts](../web/src/composables/useInboxKeys.ts) 键表，动作在 store [entries.ts:152-186](../web/src/stores/entries.ts#L152)。**桌面通知 + 未读轮询**——[useUnreadAlerts.ts](../web/src/composables/useUnreadAlerts.ts)（60 秒一轮、只在增长时弹、正文只有条数）。**控件**——[InboxView.vue:131-138](../web/src/views/InboxView.vue#L131) 的"只看命中"与"标签：X ×"、[ReaderPane.vue:56-62](../web/src/components/ReaderPane.vue#L56) 的正文标签按钮、[:113-120](../web/src/views/InboxView.vue#L113) 的通知开关与键盘提示行。**补账**：同日的 23.4 节修掉前端硬发 `limit: 50`，让"收件箱每页条数"设置真正生效（FR-9.2）。**剩下的只有人工项**：`[B]` 走查（键盘手感、权限弹窗、通知观感、chip 可见性，见 [03 §14 O23](./design/03-technical-design.md)）；[03 §11 未读提示轮询](./design/03-technical-design.md) 已随之落地 | 已实现 | 本文 3.5/3.6/3.7 节 TC-INB-40～63；`[自]InboxApiTest` 两组 + `[C]` smoke F2.4 段 15 条 + 尾段 6 条 |
| BLK-02 | [FR-1.6](./design/01-requirements.md#L69) 变更公告出口 | ✅ **F1.6 已实现（2026-11-01）**：新包 `changelog/{ChangeLogRepository,ChangeLogFeedSource}` + `adminapi/ChangelogController`（只读）+ 出口 `GET /feed/changelog.{format}`（[PublicFeedController.java:57](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L57)）+ `V5__changelog_schema.sql` 一张 `content_change`。**URL 口径修正**：需求原文写 `/feed/changelog.xml`，实现与 D-01 同源，是后缀式三格式。**剩下的只有人工项**：真阅读器订阅公告源、浏览器走查 `CHG` | 已实现 | 本文第 22 节 `CHG` 用例 |
| BLK-03 | [FR-3.1–3.4](./design/01-requirements.md#L85) 事件源与 `POST /api/ingest/events`、`/feed/events.xml` | ✅ **M3-a/M3-b 已实现**：`adminapi/IngestController` + `events/*` + `security/{ApiTokenService,BearerTokenAuthFilter}`；出口复用现成路由 `/feed/{slug}.{format}`（[PublicFeedController.java:79](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L79) + [EventFeedSource.java](../backend/src/main/java/com/rsshub/events/EventFeedSource.java#L23)），默认流 slug 就是 `events` ⇒ `/feed/events.rss|atom|json`。**剩下的只有人工项**：真机用外部阅读器订阅一次 | 已实现 | 本文第 19 节 `EVT` 用例 |
| BLK-04 | [FR-4.1–4.5](./design/01-requirements.md#L92) AI 摘要/去重/预算/`/api/ai/usage`/向量检索 | 无 `ai` 包；`grep -rn "api/ai" backend/src/main/java` 零命中；[03 §8.4](./design/03-technical-design.md) 只是设计 | **M6** | M6 |
| BLK-05 | [FR-5.1–5.4](./design/01-requirements.md#L100) 规则/钉钉企微邮件 Webhook/抑制/试跑 | ✅ **M3-c/M3-d 已实现**：`rules/*` + `notify/{Webhook,Dingtalk,Wecom,MarkOnly}` + `adminapi/{RulesController,NotificationsController}` + `web/src/views/{RulesView,NotificationsView}.vue` + `components/RuleEditor.vue`。**仍缺邮件通道**（[03 §14 O15](./design/03-technical-design.md)），且厂商侧限流/时钟偏差没有本地测试可证（O4） | 已实现（除 email） | 本文第 19 节 `RUL`/`NTF` 用例 |
| BLK-06 | [FR-1.4 的公网校验](./design/01-requirements.md#L67) / [DoD Validator 行](./design/01-requirements.md#L175) | `validator.w3.org/feed` 需要公网 URL，本机不可达 | 人工待办 | 上线后一次（[03 §13 末段](./design/03-technical-design.md)） |
| BLK-07 | [01 §5 安全行 TOTP](./design/01-requirements.md#L136) + 5 次锁定 | ✅ **M3-b 已实现**：`security/{TotpService,TotpController,LoginAttemptGuard,OperatorAuthenticationProvider}`，`[自]TotpLoginGateTest` 7 项 + `TotpServiceTest` 11 项；前端 `views/SecurityView.vue` 有开关界面 | 已实现 | 用例见本文 1.2 节 TC-AUTH-16～22（[03 §14 O8](./design/03-technical-design.md) 已关闭） |
| BLK-08 | [FR-6.1–6.4](./design/01-requirements.md#L107) 播客托管（含封面/音频上传、presign、合规自检） | 无 `podcast` 包；[01 §2 范围表](./design/01-requirements.md#L39) 明标 ⛔ M4。M2 已把封面上传挪到这里（[04 偏差](./design/04-roadmap.md)） | **M4** | M4 |
| BLK-09 | [FR-7.1–7.2](./design/01-requirements.md#L114) 聚合再分发（`<source>` 出处标注） | ✅ **M5 已实现（2026-10-31）**：新包 `repack/{RepackRepository,RepackService,RepackFeedSource,RepackJson}` + `adminapi/RepackController` + 出口 `GET /repack/{slug}.{format}` + `V4__repack_schema.sql` 三张表 + `web/src/views/RepackView.vue`。`<source>`/`external_url`/`atom:source` 三写法、默认摘要、逐源全文开关与审计全部有断言（`[自]RepackApiTest` 10 条 + `[C]` 冒烟 M5 段 29 条）。**没做**：外部投稿入口与人工审核队列（需求文本只承诺"已订阅源"） | 已实现 | 本文第 21 节 `RPL` 用例；真机用另一个阅读器订阅一个跨源 feed 仍是人工项 |
| BLK-10 | [01 §5 部署行 docker compose](./design/01-requirements.md#L140) | 按"先不实现 docker"的指示整体推迟；本机也没装 Docker | 不做 | 见 [03 §14 O13](./design/03-technical-design.md) |
| BLK-11 | [03 §12.2 指标埋点](./design/03-technical-design.md) | `fetch_total`/`feed_silence_seconds` 等**一个都没埋**（Actuator 只到"在跑"） | 未实现 | M1 之后补 |
| BLK-12 | [03 §14 O14 CI](./design/03-technical-design.md) | 本目录不是 git 仓库，无远端；等价动作是附录 A 的本地四条 | 未做 | 建仓当天 |

> 明确**不做**的（不设用例、别提需求）：多用户/权限体系、付费墙、公共注册、推荐算法、站内评论、WebSub/hub 发现（[01 §3 非目标](./design/01-requirements.md#L56)、[01 §5 时延行](./design/01-requirements.md#L132)）。JSON Feed 里因此**没有** `hubs` 成员。

---

## 13. 文档与实现口径差异（先定谁是对的，再测）

| # | 现说法（文档/代码） | 应以谁为准 | 处置建议 | 出处 |
|---|---|---|---|---|
| D-01 | 需求写 `/feed/rss`、`/feed/atom`、`/feed/json`；实现与 03 都是 `/feed.{format}` | **实现**（后缀式更稳，且满足"页面 URL 与 feed URL 不能相同"） | ✅ 已改 01 FR-1.2 措辞；本文按 `/feed.rss` 执行 | [01 FR-1.2](./design/01-requirements.md#L65) vs [03 §6.2](./design/03-technical-design.md) · [rss-guide.md:548](./rss-guide.md#L548) |
| D-02 | 03 §10 契约表写 `POST /api/posts/{id}/publish` | **实现**：`PATCH /api/posts/{id}/status` | ✅ 已改 03 §10 该行 | [03 §10](./design/03-technical-design.md) vs [PostController.java:64](../backend/src/main/java/com/rsshub/adminapi/PostController.java#L64) |
| D-03 | 收件箱空态说"抓取每 15 分钟一轮" | **配置**（`rsshub.fetch.interval: 60m`；15 秒只是队列唤醒节拍） | ✅ 文案已改为"抓取按每个来源的节奏，默认一小时一轮" | [InboxView.vue:152](../web/src/views/InboxView.vue#L152) vs [application.yml:83-84](../backend/src/main/resources/application.yml#L83) · [FetchDispatcher.java:62](../backend/src/main/java/com/rsshub/feedfetch/FetchDispatcher.java#L62) |
| D-04 | 订阅管理"轮询"列占位显示 15 | **配置**（缺覆盖时真实节奏是 `interval`=60 分钟，再被 `min-interval`=5 分钟夹） | ✅ F10 闭合：无覆盖时输入框留空、占位与 `min` 都取 `GET /api/feeds/policy` 的本机数字；空框提交 `0`＝交还站点 | [FeedsView.vue:489-495](../web/src/views/FeedsView.vue#L489) · [stores/feeds.ts:46-52](../web/src/stores/feeds.ts#L46) vs [FetchPolicy.java:80-85](../backend/src/main/java/com/rsshub/feedfetch/FetchPolicy.java#L80) |
| D-05 | 会话 cookie 无 `Secure`（设计 03 §9.1 要求 `HttpOnly; Secure; SameSite=Lax`） | **现状可接受**：本机明文 `localhost` 下加 `Secure` 就发不出去 | 上 TLS 时一起打开，测试时按"未实现"记，不算回归失败 | [application.yml:15-18](../backend/src/main/resources/application.yml#L15) · [03 §9.1 表](./design/03-technical-design.md) |
| D-06 | 03 §11 说构建产物 `dist/` 由 Caddy 服务、`/api` 反代 | **现状**：开发期由 Vite 代理；`deploy/` 推迟 | ✅ 文档已补"当前只有 Vite 代理，Caddy 属 deploy 里程碑" | [03 §11](./design/03-technical-design.md) vs [start-dev.sh](../start-dev.sh) |
| D-07 | `application.yml` 用 `DB_URL`，03 §12.1 计划 `RSSHUB_DB_URL` | **现状**（未统一） | 与 `deploy/.env.example` 一起统一（O10） | [03 §12.1 M0 实况表](./design/03-technical-design.md), [03 §14 O10](./design/03-technical-design.md) |

---

## 14. 执行顺序与回归清单

### 14.1 一轮完整功能测试的顺序（约 3.5 人时，`[自]`+`[C]` 部分）

1. `cd backend && ./mvnw verify` → 421 项全绿（这是所有 `[自]` 用例的集体执行，覆盖 40 个测试类；F16 后口径，此前第二~七轮分别是 264/343/360/370/380/382，第八~十一轮均为 397）。**必须先跑**，因为 `jar` 被运行中的后端占用时 `package` 会失败（第八轮又撞到一次，见 24.4 与 [03 §14 O25](./design/03-technical-design.md)）。
   > **复算口径**：以 Maven 汇总行 `Tests run: 421, Failures: 0, Errors: 0, Skipped: 0` 为准，**不要**拿 `target/surefire-reports/` 的和数当准。两个原因：`MarkdownRendererTest`、`SlugPolicyTest`、`FeedWritersTest` 三个类共 10 个 `@Nested` 子组，子组计数写在 Maven 控制台（本轮实测控制台 50 行"Tests run … -- in"——40 个外层类 + 10 个子组，其中三个带子组的外层类那一行是 `Tests run: 0`、计数全落在子组行上；surefire 的 `.txt` 只有 40 份、求和 **376**（本轮实测，差的就是那 10 个子组）而不并入外层 `.txt`；而 `*.xml` 的 `tests=` 求和**本轮恰好等于 421**（40 个 XML 实测），这只说明这一轮子组都写进了各自外层 XML，属于"这一轮碰巧对得上"，不是可以放心换轮的算法。别拿偏小的和数报"文档虚报"。
2. `cd web && npm run build`（含 `vue-tsc` 类型检查）→ 0 错。
3. 起服务：`bash start-dev.sh`（该脚本的 `"../$LOG_DIR/…"` 绝对路径坑已在 2026-10-18 第二轮修掉，见第 18 节 R-08；jar 被占用时先 `bash start-dev.sh stop`）。
4. 跑 `[C]` 序列：先起 `:19999` 的有状态 fixture（`node .dev-logs/smoke-feed-server.js`，第八轮重启过，见 24.4），再对一次性实例执行 `SMOKE_PASS=<临时口令> bash backend/smoke.sh`（222 项，M0 会话/CSRF → M1 订阅与条件抓取 → M3 规则/令牌/摄入 → M3.5 设置/大屏/状态 → M5 聚合再分发与公开出口 → F1.6 变更公告与变更轨迹 → F2.4 收件箱两个过滤 → F2.4 尾段"每页条数由设置决定" → **F10 订阅编辑与立即抓取** → **F11 大屏五条轨对账** → **F15 标签/分类切片与独立计数路由** → **F16 批量订阅（解析/写入/上限/匿名）** → 登出；环境与启动命令见 20.4 节，M5 段实录见 21.3 节，F1.6 段实录见 22.3 节，F2.4 段与尾段实录见 23.3/23.4 节，F10/F11 段实录见 24.4 节，F15 段实录见 24.8 节，F16 段实录见 25.4 节）。附录 A 是各步骤的展开参考与手工复现路径。
5. 破坏性用例（删除订阅/文章、OPML 导入）在副本库上做，做完丢弃。
6. `[B]` 用例：只有这一轮需要人坐在屏幕前点。

### 14.2 每次改动的最小回归（按改动面选）

| 改动面 | 必跑用例 |
|---|---|
| `feedrender/*`（writer、ETag、路由） | TC-PUB-01..46 全量 + `./mvnw verify` |
| `editorial/*`（Markdown、Slug、状态机） | TC-PST-10..45 + TC-PUB-45 |
| `feedfetch/*`（抓取、发现、退避） | TC-FET-01..25 + TC-SEC-01..07 |
| `adminapi/*` | TC-API-01..11 + 对应视图的分节 |
| `security/*` | 第 11 节全量 + TC-AUTH-05/06/10/11/12（**这条链必须有真 HTTP**，MockMvc 证明不了 CSRF 轮换） |
| `web/src/views/<X>` | 该视图所在小节的全部 `[B]` 用例 + 一次 `npm run build` |
| 迁移 DDL（`db/migration/*`） | TC-API-10（方言对齐）+ 干净库首次启动 + TC-SEC-10 |

---

## 15. 出口准则与缺陷判定

**可交付判定（全部满足才算通过）** —— 2026-10-18 第二轮逐条判定见第 18.4 节
1. 所有 P0 用例通过；P1 缺陷登记且有明确规避说明；P2 可延后。→ **达成**（`[自]`+`[C]` 范围内；`[B]` 仍未执行，见第 16 节）
2. `./mvnw verify` 全绿（第二轮实测 264 项；第三~八轮以 343/360/370/380/382/397 项全量复测，第九~十一轮维持 397，第十二轮 **421**，口径见第 14.1 节）；`npm run build` 0 类型错误。→ **达成**
3. 附录 A 的 `[C]` 序列在本机真实 HTTP 上跑通（不是 MockMvc）。→ **达成**（一次性库 + 临时口令的 :18080 实例，含真抓取与 304；第五轮再加 M5 的公开出口与全文开关，86 项 `ALL PASS`，见 21.3；第七轮再加 F2.4 的两个过滤与"页大小由设置决定"的尾段，150 项 `ALL PASS`，见 23.3/23.4；**第八轮再加 F10 的订阅编辑/立即抓取与 F11 的五条轨对账，收口 193 项 `ALL PASS`**（`.dev-logs/f11-smoke.log`：193 条 `PASS`、0 条 `FAIL`），见 24.4；第十一轮补 GAP-1/GAP-3 到 205 项，见 24.8；**第十二轮再加 F16 批量订阅（拆分读法 / 两个端点 / 200 条上限 / 匿名拒绝），收口 222 项 `ALL PASS`**（`.dev-logs/f16b-smoke.log`：222 条 `PASS`、0 条 `FAIL`），见 25.4）
4. 三个公开出口可被严格 XML/JSON 解析器解析（TC-PUB-34 的本地部分）。→ **达成**（JDK DocumentBuilder + `JSON.parse`）
5. 第 12 节每一条阻塞项都在交付说明里被点名，不出现"看起来测过了"的灰色区域。→ **达成**（第 18.5 节逐条点名 BLK-01..12）

**算 bug 的判定（有明文依据）**
- writer 违反 [03 §6.3 七条规则](./design/03-technical-design.md) 任一条（原文："违反任一条都算 bug，均有单测"）。
- 输出 200 + HTML 错误页、或 feed 里混进 HTML/调试注释（[rss-guide.md:619](./rss-guide.md#L619)）。
- GUID 在改标题/改正文后变化（[FR-1.5](./design/01-requirements.md#L68)、[rss-guide.md:616](./rss-guide.md#L616)）。
- 匿名 feed 请求建立会话（[03 §9.1 公开域行](./design/03-technical-design.md)）。
- 再分发出口把条目的 `<link>`/ID 指向本站、或丢了 `<source>` 原出处，或在未开启全文时带出正文（[FR-7.1/7.2](./design/01-requirements.md#L114)、[03 §8.6](./design/03-technical-design.md)）。
- SSRF 任一条未被拒，或私网地址细节泄漏进响应/日志（[01 §5 安全行](./design/01-requirements.md#L136)）。
- 未登录写接口返回 302 而不是 401（[03 §9.1 表末行](./design/03-technical-design.md)）。

**不算 bug（口径已定，别重复提）**
- `ttl=60` 分钟 vs `max-age=60` 秒不一致（[03 §6.4](./design/03-technical-design.md)）。
- 弱 ETag（`W/"…"`）而非强 ETag（[EtagService.java:9-12](../backend/src/main/java/com/rsshub/feedrender/EtagService.java#L9)）。
- `applied` 返回匹配行数而非变化行数（[EntryController.java:171-174,190-191](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L171)）。
- 标为已读后该行从"未读"列表消失（[entries.ts:193-201](../web/src/stores/entries.ts#L193)：重读而不是本地打补丁）。
- 无 `hubs` 成员（明确不做 WebSub）、`application/rss+xml` 与 `application/feed+json` 未在 IANA 注册（[rss-quickref.md:88](./rss-quickref.md#L88)）。

---

## 16. 复核范围声明（必读）

- 本文是**从需求文档 + 设计文档 + 实际代码逐条对照**产出的，每个控件与端点都在源码里存在，行号已回读核对。
- **所有 `[B]` 用例（浏览器渲染与交互）本轮未执行，未在浏览器复核。** 包括但不限于登录回跳、按钮禁用观感、模态确认框、预览渲染外观、三栏布局。
- 已执行并验证过的部分：`./mvnw` 全量测试（第二轮 264 项 → M3.5 后 360 项，0 失败）、`npm run build`（0 错）、以及 2026-10-18 第二轮**完整真 HTTP curl 冒烟**——一次性文件库 + 自造临时口令的 :18080 实例，覆盖登录与 CSRF 轮换、订阅、本地固定源的真实抓取（首轮 baseline、源未变 304、源变更后新条目）、发文（含 CJK/emoji/转义）、三格式严格解析、条件请求四种写法、匿名不建会话、SSRF 与协议负例、OPML 导出与四种 content-type 导入、状态机全对、登出复用、日志脱敏。逐条结果与缺陷登记见第 18 节；第四轮把 M3/M3.5 新端点（rules/notifications/events/settings/dashboard/system；totp 留在 `[自]` 覆盖）补进 `backend/smoke.sh` 后对一次性实例全量重跑，55 项 `ALL PASS`（实录见 20.4 节），至此 `[C]` 面覆盖 M0–M3.5。第五轮（2026-10-31）给 `backend/smoke.sh` 加 M5 段（`/api/repacks` 九个端点 + `/repack/{slug}.{format}` 三格式出口 + 摘要缺省与全文开关 + 审计 + ETag/304）后删库重跑，**86 项 `ALL PASS`**，`[C]` 面覆盖 M0–M3.5 + M5（实录见 21.3 节；`RPL` 的 `[B]` 部分同样未在浏览器复核）。第六轮（2026-11-01）给 `backend/smoke.sh` 加 F1.6 段（43 条：`GET /api/changelog` 的 401/405/`postId`/`limit` 夹取、草稿零行与"从未公开的删除也零行"两条负例、四类动作各一行、`<category>`、URN guid、与站点源不同的 ETag 与 304、三格式、`.opml` 404、`/blog` 的发现链接、删文后 5 行仍活）后删库重跑，**129 项 `ALL PASS`**，`[C]` 面覆盖 M0–M3.5 + M5 + F1.6（实录见 22.3 节；`CHG` 的 `[B]` 部分同样未在浏览器复核）。**第七轮（2026-11-01）**给 `backend/smoke.sh` 加 F2.4 段（15 条：事件流翻进收件箱后一条摄入同时给出标签行与命中行、`category=` 的整元素/第二元素/前缀/空串四种判据、`alerted=true` 的行带 `alertedBy`、`true`+`false` 之和等于不过滤、与 `q=`/`limit=` 的组合、单条命中不给 `nextCursor`、`alerted=maybe` 得 400 `urn:rsshub:bad_alerted`）后删库重跑，**144 项 `ALL PASS`**，并在两个独立一次性库（`.dev-logs/smoke-f24b`、`smoke-f24c`）上各复跑一遍；**同一轮写文档时捞出 FR-9.2 的一处旧欠账**——[entries.ts](../web/src/stores/entries.ts#L65) 硬发 `limit: 50`，让设置项"收件箱每页条数"在真实客户端里形同摆设（`[自]SettingsApiTest#steersTheInboxDefault` 只钉住后端契约，掩盖了端到端没走那条路）——修掉之后再加 **6 条尾段**（PATCH `inbox.pageSize=10` → 一个不带 `limit` 的请求只得 10 条并拿到游标 → 徽标仍是全局数 → 还原 50 → 页数跟着变回去），收口 **150 项**，又在两个全新独立一次性库（`.dev-logs/smoke-f24e`、`smoke-f24f`）各复跑一遍，两次都是 150 条 `PASS` / 0 条 `FAIL` / 末行 `ALL PASS`；`./mvnw -o -B -ntp verify` 同时 **382 项 0 失败**（产品 Java 一行未改）、`npm run build` **92 modules / 183.39 kB JS / 26.90 kB CSS** 且 0 类型错误、Flyway 迁到 **V6**（`Successfully applied 6 migrations`）。`[C]` 面覆盖 M0–M3.5 + M5 + F1.6 + F2.4（实录见 23.3 与 23.4 节；**F2.4 的 `[B]` 部分——键盘流、权限弹窗、桌面通知观感、标签 chip 可见性——同样未在浏览器复核，且这一部分没有第三方可自动化的替身**，登记为 [03 §14 O23](./design/03-technical-design.md)）。**第八轮（2026-11-02）**给 `backend/smoke.sh` 加 F10 段（21 条：`/api/feeds/policy` 报出本机节奏、改名只写操作员列且收件箱/健康度同源同名、带地址的 PATCH 得 400 `urn:rsshub:unknown_field` 且地址原地不动、`intervalMinutes` 的负数/30 天上界/30 天整可写、15 分钟覆盖不被站点 `<ttl>` 拽回、`0` 交还站点后下一轮回到一小时、空标题把命名交回 `<title>`）与 F11 段（22 条：恒 24 根柱且两端相距 23 小时、柱和等于头条数、大屏的条目/未读数就是收件箱的数、每源一卡、告警轨带规则名与条目名、发布轨与轨迹轨的目录/历史之别、安静订阅只有推流事件源），在 `.dev-logs/f11-smoke.log` 收口 **193 条 PASS / 0 条 FAIL / 末行 ALL PASS**；`./mvnw -o -B -ntp verify` 同时 **397 项 0 失败 / 38 个测试类**、`npm run build` **93 modules / 190.20 kB JS / 42.40 kB CSS** 且 0 类型错误、Flyway 迁到 **V7**（`feed.custom_title` + `feed.fetch_interval_minutes` 两列，两个方言各一份）。`[C]` 面覆盖 M0–M3.5 + M5 + F1.6 + F2.4 + F10 + F11。**第九~十一轮（F13 Bootstrap 迁移 / F14 淡蓝色调 / F15 覆盖面收口）**是两轮纯前端 + 一轮审计：后端测试数在 397 上不动（F15 只在既有测试方法里加断言、不新增方法），`[C]` 从 193 项到 **205 项**（+GAP-1 的 4 条计数路由、+GAP-3 的 8 条术语切片），实录分别见 24.6 / 24.7 / 24.8。**第十二轮（F16 批量订阅）**给 `backend/smoke.sh` 加 F16 段 17 条（plan 读满五行、只把两条算 ready、内网 refused 带原因、非 http 判 invalid、读不懂的行仍占一行、plan 零写入、写入 2 条 + 名字成标题 + 整批同组 + 排好抓取、错开 10 s、批量不发请求、重复算 already_subscribed 且整批 200、超 200 条 400 `urn:rsshub:too_many_lines` 且不写半批、匿名 4 字头），`[C]` 收口 **222 项 `ALL PASS`**、`[自]` 从 397 到 **421**（拆分器 12 + 两个端点 12），实录见 25.4。`[C]` 面至此覆盖 M0–M3.5 + M5 + F1.6 + F2.4 + F10 + F11 + F15 + F16。**这一轮的 `[B]` 面最大**：F10 的就地编辑手感、F11 的五条轨排版、F12 的全部观感（mica/圆角/阴影/悬停/焦点环）都**未在浏览器复核**；F12 只留下三条可机器复核的替代证据（构建 0 错、色值 grep 归零、`/login` 的 `getComputedStyle`），且截图工具在本机返回 `NATIVE_BROWSER_VIEWPORT_UNAVAILABLE`，登记为 [03 §14 O24](./design/03-technical-design.md)。
- 明确不做的验证动作：浏览器自动化跑登录流、读取/比对口令字段或哈希、探测敏感 actuator 端点（含 HEAD）。这些位置一律以配置核对 + 既有自动化断言代替，并在结论里写"该处未复核"。
- 公网 Feed Validator、Apple 目录提交、真实 IM 通道端到端推送：**需要公网与账号，属上线后人工动作**，不在 CI 假设范围内（[03 §13 末段](./design/03-technical-design.md)）。

---

## 17. 勘误表（本文引用规范时的原说法 → 正确说法 → 出处）

本文所有规范性表述都按 [rss-guide.md](./rss-guide.md) / [rss-quickref.md](./rss-quickref.md)（两份已逐条联网核对）复核过；下表是本轮**文档/代码之间**发现的口径纠正，规范侧未发现新的误记。

| 项 | 原说法 | 正确说法 | 出处 |
|---|---|---|---|
| 空 Atom 出口 | "没有条目就没有 feed 级 `<updated>`，writer 应当报错" | `<updated>` 是 feed 级**强制**项，空出口也必须出；实现回落到条目最新时间，都没有时用 epoch | [AtomWriter.java:109-120](../backend/src/main/java/com/rsshub/feedrender/AtomWriter.java#L109) · RFC 4287 §4.1.1（口径已写入 [04 M2 偏差](./design/04-roadmap.md)） |
| RSS 的 `lastBuildDate` | "三个格式都必须带最后构建时间" | RSS 2.0 里它**可选**，无值时不写；Atom 的 `<updated>` 才是强制 | [PublicFeedController.java:115-117](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L115) · [rss-quickref.md:21](./rss-quickref.md#L21) |
| 条件请求的 `If-None-Match: *` | "只有具名验证器能命中 304" | 弱比较下 `*` 也算命中，实现按 RFC 9110 §8.8.3.2 处理逗号列表与 `*` | [EtagService.java:34-46](../backend/src/main/java/com/rsshub/feedrender/EtagService.java#L34) |
| 429 的规范归属 | "`Retry-After` 属 RFC 9110，429 也是" | `Retry-After`/304 属 9110；**429 由 RFC 6585 补充** | [03 §4.3 出处对照](./design/03-technical-design.md) |
| 播客 feed 大小 | "封面 512KB / feed 8MB / 125–300 集是硬上限" | Apple **未公布** feed 大小与条目数上限，这类数字不得写进代码常量 | [03 §7.1 引注](./design/03-technical-design.md) · [rss-guide.md:1012](./rss-guide.md#L1012) |
| `enclosure length` | "写传输字节数" | 写**原始文件字节**，不能是 gzip 后的字节 | [03 §6.4](./design/03-technical-design.md) · [rss-guide.md:507](./rss-guide.md#L507) |
| 口令哈希算法 | "已用 Argon2id" | 当前是 `{bcrypt}` delegating 前缀，Argon2id 属 O7 待办 | [03 §9.1 落地表](./design/03-technical-design.md) |
| 抓取间隔 | "15 分钟一轮"（前端文案） | 默认 `interval=60m`（`min-interval=5m` 是地板），15 秒只是调度器唤醒节拍 | [application.yml:83-84](../backend/src/main/resources/application.yml#L83) · [FetchDispatcher.java:62](../backend/src/main/java/com/rsshub/feedfetch/FetchDispatcher.java#L62) |
| 冒烟库的 H2 URL | （18.1 原记录）`jdbc:h2:file:.dev-logs/smoke-db` | H2 2.x 拒绝隐式相对路径（错误 90011：必须 `./` 前缀、`~/` 或绝对路径），18.1 与脚本均已改为 `jdbc:h2:file:./.dev-logs/smoke-db` | 第四轮实测（实例拒绝启动的报错）+ [application.yml:33](../backend/src/main/resources/application.yml#L33) 默认值即 `./data/rss-hub` 形式 |

---

## 18. 第二轮执行结果（2026-10-18："执行 → 修复 → 全量回归"）

### 18.1 执行环境（没有动开发库）

| 项 | 值 | 为什么这样搭 |
|---|---|---|
| 进程 | `java -jar target/rss-hub-0.0.1-SNAPSHOT.jar --server.port=18080`（`PORT=18080`） | :8080 上的实例会锁 jar，`package` 就失败（§14.1 第 1 条） |
| 库 | `DB_URL=jdbc:h2:file:./.dev-logs/smoke-db` 全新空库，跑完即删（原记录少 `./` 前缀，H2 2.x 报 90011，第四轮实测后按勘误表更正） | §0.5 第 1 条："不要用开发库跑功能测试"；Flyway 在该库上从 V1 迁起成功（迁移日志 `.dev-logs/smoke-backend.log`——该文件已被第四轮同名覆盖，现行内容见 20.4） |
| 口令 | 测试自造的临时口令，只以环境变量 `RSSHUB_OPERATOR_PASSWORD` 进入进程 | 未读取、未比对开发库里的任何口令字段或哈希（第 16 节的"明确不做"） |
| 被抓源 | `.dev-logs/smoke-feed-server.js`：RSS 2.0、发弱 ETag、认 `If-None-Match`、`/bump` 增加条目 | 真 HTTP 上必须有一个"会 304、会变"的源，否则 TC-FET 的条件请求段只能靠 MockWebServer |
| SSRF | 该实例带 `--rsshub.ssrf.allow-private-prefixes=127.`（仅为了让本机被抓源可达） | 产品默认仍是空清单（[application.yml:73-75](../backend/src/main/resources/application.yml#L73)）；`SsrfGuardTest` 25 项在无清单的进程里跑，且本实例实测 169.254/10.0.0.5 依旧被拒（18.2 第 9 段） |

### 18.2 `[C]` 序列逐段实测

| 段（附录 A 编号） | 断言 | 实测 |
|---|---|---|
| 1 匿名基线 | `/api/auth/me` = 200 + `authenticated:false` | 通过，且此时 cookie jar 里没有 `RSSHUB_SESSION`（匿名 GET 不建会话） |
| 2 登录 | 带 `X-XSRF-TOKEN` 的表单登录 200 | `{"username":"admin"}`；**会话 id 与 XSRF token 同时轮换**（重登一次也换 id）；错口令 401 `invalid_credentials` |
| 2′ 少一步预检 | 直接 POST 登录 | **401 而不是 403**：匿名态下 CsrfFilter 的拒绝走 entry point（[SecurityConfig.java:86-87](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L86)）；这就是原脚本"看着像口令错"的原因 → R-05 |
| 3 订阅 | 201 + 分组 | `{"id":1,"state":"active","nextFetchAt":…}`；分组字段是 `group`（[FeedController.java:47-49](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L47)），传 `groupName` 会被静默忽略 → F-02 |
| 4 抓取观测 | refresh 只算入队 | 202 后 1s 内条目落库：`lastHttpStatus:200`，标题/描述/`selfUrl`/`format:rss2` 全部解析出来 |
| 4′ 源没变 | 第二次抓 → 304 | `lastHttpStatus:304`，`entryCount` 与 `lastChangeAt` 都不动 |
| 4″ 源变了 | 新条目该算未读、改标题不该 | 新条目 `baseline:false` → `counters.unread:1`；旧条目改标题 → `revision:2` + `revisions[]` 有历史，`stableId:true`、guid 没变（不触犯 §15"算 bug 判定"里的 GUID 那条） |
| 4‴ 首批基线 | 新订阅首批条目不算未读 | 三条 feed 的 `entryCount:2 / unread:0`，条目 `baseline:true`（[EntryIngestService.java:42](../backend/src/main/java/com/rsshub/feedstore/EntryIngestService.java#L42)） |
| 5 发文 | UTF-8 文件进、CJK 出 | 201；标题「中文标题测试：订阅与发布」、emoji ✅、`& <尖括号>` 在 JSON/HTML/RSS/Atom 里都不串码不乱序，Markdown 转义正确 |
| 6 三出口 | 头 + 严格解析 | 200；三种 content-type；`Cache-Control: max-age=60, must-revalidate, private`；弱 ETag 每个格式各一个；JDK `DocumentBuilder`（命名空间敏感、禁外部实体）判 rss/atom 均 `xml-ok`，`JSON.parse` 判 json feed 通过、`version` 是 1.1 |
| 7 条件请求 | 弱比较的四种写法 | `W/"…"`→304、去掉 `W/` 的强形式→304、`*`→304、`"x", E` 逗号列表→304、陈旧 tag→200、把 rss 的 tag 拿去问 atom→200；`If-Modified-Since` 命中 304、1970 值 200 |
| 8 匿名不建会话 | 出口与 SSR 页 | `grep -c RSSHUB_SESSION` = 0；`/`、`/blog`、`/blog/{slug}`、`/tag/{中文}.rss` 全 200 |
| 9 负例 | 未认证 / 无 CSRF / 未知后缀 / 私网 / 非 http | 401；403；404 `urn:rsshub:unknown_format`（消息列出 rss/atom/json）；400 `ssrf_blocked`（169.254 与 10.0.0.5 都拒，尽管本机清单放开了 127.）；400 `scheme_not_allowed`；重复订阅 409 `already_subscribed` |
| 10 登出复用 | 会话作废 | 登出 204；旧会话 `/api/feeds` 401、`/me` 回到 `authenticated:false`；带死 token 再登出 401 |
| OPML | 导出 + 四 content-type 导入 | 导出带 `Content-Disposition: attachment`；导入 `{"imported":2,"skipped":1}`（撞库那条被 skip），两条 `nextFetchAt` 相差 10s，**导入期间 0 次网络探测**；`text/xml`/`application/xml`/`text/plain`/`application/octet-stream` 都收，`application/opml+xml` → 415；空 body → 400 `no_outlines` |
| 状态机 | 20 对全跑 | 合法 200 且状态变；非法 409 `illegal_transition`，detail 形如 `published cannot become scheduled`；同状态重复 PATCH 200 且 `updatedAt` 不动；`{}` 与空白 status 400；scheduled 缺时间 400 `publish_at_required`；slug 撞 409 `slug_taken`、非法 400 `invalid_slug` |
| 定时可见 | 到点即出，不改状态 | `scheduled` + 过去时间：状态仍是 scheduled，但三出口与 `/blog/{slug}` 全部可见（[PostRepository.java:35](../backend/src/main/java/com/rsshub/editorial/PostRepository.java#L35) 的 VISIBLE 谓词） |
| 草稿/unlisted | 404 而非 403 | draft 的 `/blog/{slug}` → 404 `unknown_post` 且不在 feed 里；unlisted 有可访问 URL 但不进 feed |
| 基址唯一真源（TC-PUB-46） | 换 `base-url` 重启、不重发文 | alpha→beta 后 `<guid>`、`atom:link rel=self`、`home_page_url`、item `id`/`url`、HTML 绝对链接**一起换**，文档里 0 处残留旧域名，ETag 随之变化 |
| 日志脱敏（TC-SEC-08） | 冒烟日志 grep | 口令、三个会话 id、XSRF token、`Authorization`、`Cookie`、`password` 命中数**全 0**；`ERROR` 0 条 |
| 可迁移（TC-MIG-03） | 归档原始字节可再解析 | `.dev-logs/smoke-raw/2026/10/feed-*.bin` 690B，严格解析 `xml-ok root=rss` |

> 本表的"段"编号是**第二轮当时的附录 A 编号**，之后附录只在尾部追加过新段（现第 10 段聚合再分发、第 11 段变更公告，登出复用因此是第 12 段）。历史实录不回改编号，只对得上名字：`10 登出复用` 说的就是现在 `# 17) 登出后复用` 那一段——第八轮的 F10/F11 两段插在它之前，登出复用现在是**第 17 段**。

### 18.3 本轮修掉的东西

| # | 层 | 症状 | 根因与修法 | 守住它的自动化 |
|---|---|---|---|---|
| R-01 | 代码 | 搜索框输入 `%` 命中全部条目、`a_b` 命中任意两字符 —— 搜索只能放大结果集，不能缩小 | LIKE 参数未转义（[EntryFinder.java:330-334](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L330)），新增 `escapeLike`（[:374-376](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L374)），先转义转义符本身 | `InboxApiTest#wildcardsInTheQueryAreCharactersRatherThanOperators` |
| R-02 | 代码 | 分组清空后 `groupName` 回的是 `""` 而不是缺键，前端"无分组"和"空名分组"两种写法并存 | `update` 直接写传入值（现 [FeedRepository.java:188-192](../backend/src/main/java/com/rsshub/feedstore/FeedRepository.java#L188)，F10 后拆成一键一句），改为 `nullif(?, '')` 并 trim | `InboxApiTest#intervalAndGroupEditsAreBoundedAndExplicit` |
| R-03 | 代码 | `/api/feeds/groups` 同名分组重复出现、未排序 | 少了 `distinct/sorted`（[FeedController.java:127-135](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L127)） | `InboxApiTest#groupsAreDeduplicatedAndARepeatStateWriteIsHarmless` |
| R-04 | 代码 | 文章页六个 `rel=alternate` 全叫站点名，读者分不出哪个是标签源 | `alternates()` 硬用 `site.siteTitle()`（[PublicViewController.java:91-113](../backend/src/main/java/com/rsshub/editorial/PublicViewController.java#L91)），改为按 scope 取 term.title、缺 term 回落 slug | `PublishApiTest#publicPagesAdvertiseEveryOutletTheyPublish`（[:285-301](../backend/src/test/java/com/rsshub/editorial/PublishApiTest.java#L285) 断言整段 `<link>` 元素串） |
| R-05 | 本文 | 附录 A 的登录步骤不带 CSRF 头，失败表现是 401，容易被当成口令错反复排查 | 附录 A 已重写：先 GET `/me` 取 token，登录后重读 token 与会话 id | — |
| R-06 | 本文 | §0/§14/§15/§16 仍写 246 项、22 个测试类，且 §15 五条准则没有判定 | 计数改 264/23，§15 逐条给结论，§16"已执行"段指向本节 | — |
| R-07 | 本文 | §14.1 第 4 步承诺"订阅 → 抓取观测"，附录 A 里却没有这两段 | 附录 A 补第 3、4 段（含 304 与新条目观测、`feed` 参数名） | — |
| R-08 | 脚本 | `bash start-dev.sh` 起不来：`"../$LOG_DIR/…"` 把绝对路径拼成 `..//d/…`，两个日志写不出、60s 超时 | [start-dev.sh:72-73](../start-dev.sh#L72) 去掉 `../` 前缀；已在修好后实测 `ready: http://localhost:5173/` | — |

本轮把执行数从 246 抬到 264（+18）：`InboxApiTest` 19→30（+11）、`PublishApiTest` 13→16（+3）、`FeedFetchServiceTest` 21→22（+1，重定向落到私网地址时逐跳复检）、新增 `bootstrap/OperatorBootstrapTest` 3 项（空库无口令拒绝启动、存的是带前缀哈希、已有账号不被覆盖）。

### 18.4 出口准则判定

§15 五条全部满足，判定已写在该节。唯一保留项：**`[B]` 用例一条都没执行**（第 16 节），所以"P0 全通过"的范围是 `[自]`+`[C]`。

### 18.5 第 12 节阻塞项点名（不出现"看起来测过了"）

BLK-01 键盘流/桌面通知/未读轮询、BLK-02 `/feed/changelog.xml`、BLK-03 事件源 ingest、BLK-04 AI 摘要与检索、BLK-05 规则与 Webhook、BLK-07 TOTP 与登录锁定、BLK-08 播客托管、BLK-09 聚合再分发、BLK-11 指标埋点：**本轮一条未测，因为代码不存在**。BLK-06 公网 Feed Validator 需要公网域名，本机不可达，属上线后人工动作。BLK-10 docker 按指示不做（本机也没有 Docker）。BLK-12 CI：本目录不是 git 仓库，等价动作就是附录 A 这四条本地序列。
附带一句：BLK-03/07 提到的 `api_token` 目前只有表没有链路，所以 TC-SEC-08 的"token 脱敏"本轮只能证明**日志里不出现口令/会话/CSRF 值**，令牌那条要等 M3 再测。

### 18.6 登记未修（P2，含规避）

| # | 现象 | 为什么这轮不改 | 规避 |
|---|---|---|---|
| F-01 | 框架自己抛的 404/405/415 体没有 `type: urn:rsshub:*`（如 `/api/health` 的 "No static resource"、POST 到 PATCH 端点的 405） | 那是 Spring 的 ProblemDetail 默认信封，套一层我们的 code 要么全局改写异常映射、要么给每个动词加兜底，收益只有"看起来一致" | 自动化与脚本对框架错误只断言 `status`；凡是应用自己抛的错都带 urn（18.2 第 9 段逐条实测） |
| F-02 | 请求体字段名拼错会被静默丢弃，于是收到的是"缺字段"的错而不是"未知字段"（实测 `groupName`、`publishAt` 两次） | Boot 默认 `FAIL_ON_UNKNOWN_PROPERTIES=false`；关掉它等于放弃前后端不同版本共存时的前向兼容 | 附录 A 标注了 `group`/`publishedAt` 两个真实字段名；改名时 `adminapi`、`editorial` 两包的测试会红 |
| F-03 | 对已经 `scheduled` 的文章再 PATCH 一次 `scheduled`+新时间，时间不变（同状态提前返回，见 [PostService.java:96-107](../backend/src/main/java/com/rsshub/editorial/PostService.java#L96)） | 这是"同状态写入不该产生新 updatedAt"的既有口径（TC-PST-36），改它要连带改那条 P0 | 先 PATCH 回 `draft` 再 PATCH `scheduled`+`publishedAt`，实测两步都 200 且时间生效（18.2 定时可见行） |
| F-04 | 文章页只带**按 slug 字典序**第一个标签的源，不是编辑器里第一个 | 顺序来自 [PostRepository.java:263](../backend/src/main/java/com/rsshub/editorial/PostRepository.java#L263) 的 `order by t.slug`，改排序会让已发布页面的发现链接漂移 | TC-PUB-44 的"第一个"按本表口径读作"slug 最小者"；`PublishApiTest` 已锁死这个顺序 |
| F-05 | ~~`ttlMinutes` 只能设值，无法清空回"跟随站点"~~ **F10 已闭合**：站点声明与操作员覆盖分列（`ttl_minutes` 归抓取器、`fetch_interval_minutes` 归操作员），覆盖用 `nullif(?, 0)` 写，`0` 就是"清空" | 旧口径要把"清空"塞进 `coalesce` 的合并规则里（[FeedRepository.java:254-264](../backend/src/main/java/com/rsshub/feedstore/FeedRepository.java#L254) 的 `coalesce(?, ttl_minutes)` 把 null 读作"不改"），这正是当时不改它的原因 | 现在的口径：PATCH `{intervalMinutes:0}` → 响应里**没有** `fetchIntervalMinutes` 键；实测见 [smoke.sh:420-427](../backend/smoke.sh#L420)、[FeedRepository.java:177-203](../backend/src/main/java/com/rsshub/feedstore/FeedRepository.java#L177) 与 TC-SUB-56 |

---

## 19. 第三轮新增界面用例（M3-d：`RUL` 规则 / `NTF` 投递 / `EVT` 事件源与令牌 / `TOT` 二次验证）

**这些用例的 `[B]` 全部尚未执行。** M3-d 交付时的证据只有三种：①`npm run build`（`vue-tsc --noEmit` 严格模式 + `noUnusedLocals`）通过，产物 160.06 kB JS；②`web/src/api/*.ts` 与五个 controller 逐条对照方法名/路径/参数/记录字段；③同一后端行为都有 `[自]` 用例兜着。"按钮点下去长什么样"没有任何机器证据（[03 §14 O17](./design/03-technical-design.md)）。**本表就是给人工走查用的清单**，逐条点，发现的偏差回写 O17。

### 19.1 `RUL` 规则页（`RulesView.vue` + `components/RuleEditor.vue`）

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-RUL-01 | P0 | 顶栏 [规则](../web/src/App.vue#L34) | 点 | `GET /api/rules` 后每条规则一张卡：名称、启用态、`hitCount`、更新时间 | [RulesView.vue:104-109](../web/src/views/RulesView.vue#L104) · [RulesController.java:38](../backend/src/main/java/com/rsshub/adminapi/RulesController.java#L38) `[自]RulesApiTest` |
| TC-RUL-02 | P0 | [新建规则](../web/src/views/RulesView.vue#L70) | 点 | 右侧 sticky 区出现空编辑器，标题"新建规则"；同时把"编辑中"的规则清空 | [RulesView.vue:70,133](../web/src/views/RulesView.vue#L70) · [RuleEditor.vue:175](../web/src/components/RuleEditor.vue#L175) `[B]` |
| TC-RUL-03 | P0 | [保存] 的可点性 | 名称留空 / 把动作删到 0 | 提交按钮 `disabled`：名称是 `required`，且 `form.actions.length === 0` 也禁用 | [RuleEditor.vue:309](../web/src/components/RuleEditor.vue#L309), [:184](../web/src/components/RuleEditor.vue#L184) `[B]` |
| TC-RUL-04 | P1 | 关键词 textarea | "包含任一"里写 `OOM, 内存`+换行`泄漏` | 提交的是**三元素数组**（逗号与换行都算分隔符），不是整串 | [RuleEditor.vue:25](../web/src/components/RuleEditor.vue#L25) 的 `toList()` · [自]RuleMatcherTest `[B]` |
| TC-RUL-05 | P1 | 两个匹配复选框 | 观察新建表单 | `wholeWord`、`caseSensitive` 默认**不勾**，与后端默认一致（`MatchSpec` 的 `wholeWord` 默认 false） | [RuleEditor.vue:190,192](../web/src/components/RuleEditor.vue#L190) · [MatchSpec.java:30](../backend/src/main/java/com/rsshub/rules/MatchSpec.java#L30) `[B]` |
| TC-RUL-06 | P0 | 时间窗数字框 | 输 43201，或直接发请求 | 前端 `min=0 max=43200`（[:238](../web/src/components/RuleEditor.vue#L238)）；越过前端直接 PATCH 得 400 并点名 `window` | [自]RulesApiTest `every way to write a bad rule is a 400 that names the field` |
| TC-RUL-07 | P1 | 正则框 | 粘 201 字符或 `(a+)+b` 这类回溯灾难 | 400（长度上限 200，[MatchSpec.java:60-62](../backend/src/main/java/com/rsshub/rules/MatchSpec.java#L60)）；`code: detail` 原样显示在错误行 | [RuleEditor.vue:204,306](../web/src/components/RuleEditor.vue#L204) · [03 §14 O2](./design/03-technical-design.md) `[B]` |
| TC-RUL-08 | P0 | 通道选择 + 目标框 | 动作选 `markOnly` | **"目标地址"输入框消失**（`needsTarget()` 只对 webhook/dingtalk/wecom 为真），保存 201 | [RuleEditor.vue:246-254](../web/src/components/RuleEditor.vue#L246) · [Action.java:23](../backend/src/main/java/com/rsshub/rules/Action.java#L23) `[自]RuleDeliveryTest#markOnly marks the entry and never opens a connection` |
| TC-RUL-09 | P0 | 通道缺目标 | 选钉钉但目标留空后提交 | 请求**不带** `target`（空串被 trim 掉），后端 400 `target_required` 显示在错误行。**注意**：按钮不会因此禁用，是"提交后报错"而不是"提交前拦住"——UX 待改，不算后端缺陷 | [RuleEditor.vue:124-127](../web/src/components/RuleEditor.vue#L124), [:158](../web/src/components/RuleEditor.vue#L158) `[B]` |
| TC-RUL-10 | P1 | `secretRef` 框 | 填一个不存在的环境变量名并保存 | 保存成功（它只是**名字**），投递时才失败；在投递页看到 `failed` 行，错误指向无法解析的引用 | [RuleEditor.vue:254](../web/src/components/RuleEditor.vue#L254) `[自]RuleDeliveryTest#DingTalk carries its 加签 in the query, and a secret that will not resolve stops the send` |
| TC-RUL-11 | P0 | 抑制窗口 | 下拉选 `perFeed`，小时数 6 | 提交 `{key:"perFeed",hours:6}`；小时框 `min=0 max=720`；换 `perContentHash` 后同一源的不同正文算两条 | [RuleEditor.vue:270-276](../web/src/components/RuleEditor.vue#L270) `[自]RuleDeliveryTest#suppressionKeepsTheSecondHitOfTheSameSourceQuiet`、`a different content hash is a different hit under perContentHash` |
| TC-RUL-12 | P0 | 静默时段三件套 | 勾选后只填 `from` | 三个 input 都是 `required`（[:287-296](../web/src/components/RuleEditor.vue#L287)）；绕过前端发残缺对象 → 400 `quiet_hours_incomplete` | [自]RulesApiTest（本轮补的清空/残缺两条断言） |
| TC-RUL-13 | P0 | 取消勾选静默时段 | 编辑一条**已存**静默窗口的规则，取消勾选再保存 | 请求里 `quietHours` 是**空对象** `{}`（不是缺失），后端把空对象读作"清空"（`RuleService.quietHours`），回读时 `quietHours` 键整个消失 | [RuleEditor.vue:138-141](../web/src/components/RuleEditor.vue#L138) · [04 M3 偏差 4](./design/04-roadmap.md) `[自]` 同上 |
| TC-RUL-14 | P0 | 保存与熔断的边界 | 编辑一条被自动关闭的规则，只改名字后保存 | **请求体不含 `enabled`**（`if (!isEdit.value)`），行仍是关闭态、`disabledReason` 保留；这是 O16 目前唯一的护栏 | [RuleEditor.vue:143-145](../web/src/components/RuleEditor.vue#L143) · [03 §14 O16](./design/03-technical-design.md) `[自]RulesApiTest#enabling is its own decision, and a save cannot walk past the circuit breaker` |
| TC-RUL-15 | P0 | [关闭] / [重新启用] | 点行内开关 | 走 `PATCH /api/rules/{id}/enabled`（不是普通 PATCH）；被熔断的规则重新启用后 `disabledReason` 被清空 | [RulesView.vue:44,104](../web/src/views/RulesView.vue#L104) · [RulesController.java:60-67](../backend/src/main/java/com/rsshub/adminapi/RulesController.java#L60) `[自]` 同上 |
| TC-RUL-16 | P0 | [测试] | 点 | `POST /api/rules/{id}/test?limit=200`（前端不传 limit，默认 200）；下方 `<details>` 出现"查了 N 条，命中 M 条"与命中列表（含命中的关键词）；**`notification_log` 零新增、零外发** | [RulesView.vue:35,114-127](../web/src/views/RulesView.vue#L114) · [RuleService.java:26](../backend/src/main/java/com/rsshub/rules/RuleService.java#L26) `[自]RulesApiTest#FR-5.4: the test button previews the last entries and records or sends nothing` |
| TC-RUL-17 | P1 | 熔断横幅 | 造一条 `disabled_reason` 以 `delivery failed` 开头的规则 | 顶部出现"N 条被投递熔断自动关闭"，行内显示原因原文；健康度页的红链计数同步（同一 SQL 口径） | [RulesView.vue:76-77,111](../web/src/views/RulesView.vue#L76) · [stores/rules.ts](../web/src/stores/rules.ts) 的 `autoDisabled` · [HealthController.java:57-61](../backend/src/main/java/com/rsshub/adminapi/HealthController.java#L57) `[自]RulesApiTest#the health page counts the rules the breaker stopped, and only those` |
| TC-RUL-18 | P0 | [删除] | 点 → 确认框 | `window.confirm("删除规则「…」？投递记录会留下，只是不再指向任何规则。")`；确认后 DELETE 204，行消失，投递页该行的规则名变成"（规则已删除）" | [RulesView.vue:50-51,109](../web/src/views/RulesView.vue#L50) `[自]RuleDeliveryTest#the delivery log outlives the rule` `[B]` |
| TC-RUL-19 | P2 | [重新读取] / 后端停机 | 停掉后端再点 | 按钮文案变"读取中…"并禁用；错误行显示，不白屏、不无限转圈 | [RulesView.vue:74-82](../web/src/views/RulesView.vue#L74) `[B]` |
| TC-RUL-20 | P1 | 单条规则 `GET /api/rules/{id}` | 直接请求一条已存在规则的详情 | **200**，且返回体与列表里同一条**逐字段一致**（`name`/`actions[0].channel`/`suppression.key`；刚清空的 `quietHours` 不该有字段）。F15 之前此路由只有 404 负例，没有正向证据 | [RulesController.java:43](../backend/src/main/java/com/rsshub/adminapi/RulesController.java#L43) · 断言 [RulesApiTest.java:130-139](../backend/src/test/java/com/rsshub/adminapi/RulesApiTest.java#L130) `[机]` |

### 19.2 `NTF` 投递页（`NotificationsView.vue`）

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-NTF-01 | P0 | 状态筛选 | 依次选 `pending/sent/suppressed/failed/skipped_quiet` | 每次 `@change` 重新 `GET /api/notifications?state=`；列表只剩该状态 | [NotificationsView.vue:83](../web/src/views/NotificationsView.vue#L83) · [NotificationsController.java:49](../backend/src/main/java/com/rsshub/adminapi/NotificationsController.java#L49) `[自]RulesApiTest#the delivery log filters by state and rule` |
| TC-NTF-02 | P1 | 规则筛选 | 选某条规则 | 下拉项来自 `useRulesStore`（与规则页同一份数据）；与状态筛选可叠加 | [NotificationsView.vue:90](../web/src/views/NotificationsView.vue#L90) `[自]` 同上 `[B]` |
| TC-NTF-03 | P0 | [重发] 的出现条件 | 观察各行 | **只有 `state=failed` 且当初存过 alert 正文的行**才有按钮；`sent`/`suppressed`/`skipped_quiet` 没有 | [NotificationsView.vue:141](../web/src/views/NotificationsView.vue#L141) 的 `replayable()` · [自]RulesApiTest#replay is offered only for a failed row, and only when an alert body survives |
| TC-NTF-04 | P0 | [重发] 的语义 | 点一条 `failed` 的行 | `POST /api/notifications/{id}/replay`；**用的是当初决策时快照的正文**，中途改过规则也不影响；`attempts` 得到新的 5 次预算；行就地替换为返回的新状态 | [NotificationsView.vue:141](../web/src/views/NotificationsView.vue#L141) · [NotificationsController.java:63](../backend/src/main/java/com/rsshub/adminapi/NotificationsController.java#L63) `[自]RuleDeliveryTest#manual replay re-sends the body that was decided on` |
| TC-NTF-05 | P1 | 删除过的规则 | 筛选前先删掉一条有记录的老规则 | 该行 `ruleId` 键**整个不出现**（`non_null` 包含策略），名字列显示"（规则已删除）"，不能点进详情 | [api/notifications.ts:15,28](../web/src/api/notifications.ts#L15) 的可选字段（ruleId 缺失的语义写在 [:10](../web/src/api/notifications.ts#L10)） · [application.yml:45](../backend/src/main/resources/application.yml#L45) `[自]` 同上 `[B]` |
| TC-NTF-06 | P2 | 列宽与错误原文 | 造一条 5xx 失败 | `error` 列直显原始错误串（如 `http_status=500`），`httpStatus` 单独成列；不做截断 | [NotificationsView.vue:4-141](../web/src/views/NotificationsView.vue#L4) `[B]` |
| TC-NTF-07 | P1 | 上限 | 直接请求 `?limit=9999` | 后端钳到 500（默认 100）；页面本身不带分页控件，**超过 500 条记录时需要人工用 SQL 看**（已知限制，非 bug） | [NotificationsController.java:49](../backend/src/main/java/com/rsshub/adminapi/NotificationsController.java#L49) `[自]` |

### 19.3 `EVT` 事件源与机器令牌（`EventsView.vue`）

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-EVT-01 | P0 | 创建表单 | 填 slug `ci-builds` + 标题，点 [创建] | `POST /api/events` 201；表格里出现该流，`itemCount=0`；提示条写明"机器现在可以往 `/api/ingest/events/ci-builds` 写事件" | [EventsView.vue:214-232,82](../web/src/views/EventsView.vue#L214) · [EventStreamController.java:54](../backend/src/main/java/com/rsshub/adminapi/EventStreamController.java#L54) `[自]EventsApiTest` |
| TC-EVT-02 | P0 | slug 形状 | 输入 `CI_Builds` 或 65 字符 | `pattern="[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?"` + `maxlength=64`，浏览器就拦住；绕过前端则后端 400 | [EventsView.vue:214](../web/src/views/EventsView.vue#L214) `[自]EventsApiTest#FR-3.3: the stream is managed through the session and PATCH only changes what it sends` |
| TC-EVT-03 | P0 | 编辑时 slug 锁死 | 点某行 [编辑] 改标题 | slug 输入框 `disabled`，只 PATCH 标题/描述；换 slug 等于换 feed URL，必须另建流 | [EventsView.vue:199,214](../web/src/views/EventsView.vue#L199) `[自]` 同上 |
| TC-EVT-04 | P0 | 可见性药丸 | 点 [仅内部]→[进收件箱] | 打开时**回填历史条目**（收件箱里能翻到之前的事件），关闭时虚拟 feed 消失并弹确认框"已读状态一起删除" | [EventsView.vue:91-93,191-193](../web/src/views/EventsView.vue#L91) · [EventInboxBridge.java:32](../backend/src/main/java/com/rsshub/events/EventInboxBridge.java#L32) 的 `event:` 标记 `[自]EventsApiTest#FR-3.3: virtual_feed bridges the history into the inbox, internal takes it back out` |
| TC-EVT-05 | P0 | 删除流 | 点 [删除] | 确认框带上条目数"及其 N 条事件"；确认后条目与流一起消失，`/feed/{slug}.rss` 变 404 | [EventsView.vue:106,201](../web/src/views/EventsView.vue#L106) `[自]EventsApiTest` `[B]` |
| TC-EVT-06 | P0 | 出口 URL | 浏览器直接开 `/feed/<slug>.rss`、`.atom`、`.json`（无 cookie） | 三格式都能出，带 `ETag`；`guid` 指向 `/event/{slug}/{publicId}` 的人类可读页 | [PublicFeedController.java:79](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L79) · [EventViewController.java:44](../backend/src/main/java/com/rsshub/events/EventViewController.java#L44) `[自]EventsApiTest#FR-3.4` `#FR-1.3` `[C]` |
| TC-EVT-07 | P0 | 创建令牌 | 名字 + scope 选 `ingest`，点 [创建] | **一次性**弹出 `secret` 面板（`.secret`），含"我已经存好了"关闭按钮；列表里此后只有 `id/name/scope/createdAt` | [EventsView.vue:286-306](../web/src/views/EventsView.vue#L286) · [TokenController.java:47](../backend/src/main/java/com/rsshub/adminapi/TokenController.java#L47) `[自]EventsApiTest#FR-3.1: the secret is shown once` |
| TC-EVT-08 | P0 | 库里没有明文 | 关闭面板后用 SQL 看 `api_token` | 只有 SHA-256 的 `token_hash`；重开列表接口也不再返回任何 secret 字段 | [V1__baseline_schema.sql:17-25](../backend/src/main/resources/db/migration/h2/V1__baseline_schema.sql#L17) · [ApiTokenService.java:63-64](../backend/src/main/java/com/rsshub/security/ApiTokenService.java#L63) `[自]` 同上 |
| TC-EVT-09 | P0 | scope 是真能力 | 用 `read` 令牌 `POST /api/ingest/events` | 401/403——`/api/ingest/**` 只接受 `SCOPE_ingest` 或 `SCOPE_admin`；`read` 令牌只能 `GET /api/entries/**`、`/api/feeds/**` | [SecurityConfig.java:83-86](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L83) · [ApiTokenService.java:27](../backend/src/main/java/com/rsshub/security/ApiTokenService.java#L27) `[自]EventsApiTest#FR-3.1: a scope is a capability, not a label` |
| TC-EVT-10 | P1 | [吊销] | 点某行 [吊销] → 确认 | 确认框写明"用它的任务会立刻开始收到 401"；DELETE 后该令牌下一次请求即 401 | [EventsView.vue:134,270](../web/src/views/EventsView.vue#L134) `[自]EventsApiTest` `[B]` |
| TC-EVT-11 | P1 | 页脚的 curl 样例 | 复制框里的命令（把 `<token>` 换成真的） | 命令里的 origin 取自 `window.location.origin`，**开发期是 5173，需手改成后端端口**——这条样例在 `vite dev` 下直接粘贴会打到前端服务器上 | [EventsView.vue:29,240-243](../web/src/views/EventsView.vue#L240) `[B]` |
| TC-EVT-12 | P1 | 令牌不带头也发写请求 | 不带 `Authorization` `POST /api/ingest/events` | 401；带 Bearer 时**不要求 CSRF**（`.ignoringRequestMatchers(HAS_BEARER)`），否则脚本客户端全得先抓 cookie | [SecurityConfig.java:91-94](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L91) `[自]EventsApiTest` `[C]` |

### 19.4 `TOT` 安全页（`SecurityView.vue`）

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-TOT-01 | P0 | 当前状态行 | 进入页面 | 显示"已启用/未启用"，数据来自 `session.refresh()` 读的 `/api/auth/me` 的 `totpEnabled`——**只有已登录时后端才给这个键** | [SecurityView.vue:23,93](../web/src/views/SecurityView.vue#L23) · [AuthController.java:32-37](../backend/src/main/java/com/rsshub/security/AuthController.java#L32) `[自]TotpLoginGateTest` |
| TC-TOT-02 | P0 | [开始绑定] | 点 | `POST /api/auth/totp/enroll` 返回 Base32 密钥 + `otpauth://` + 位数/周期，页面显示出来；此时**尚未生效**（库里没写） | [SecurityView.vue:132](../web/src/views/SecurityView.vue#L132) · [TotpController.java:47-65](../backend/src/main/java/com/rsshub/security/TotpController.java#L47) `[自]TotpLoginGateTest#enrolmentStaysInactiveUntilACodeHasBeenProved` |
| TC-TOT-03 | P0 | 未设 `RSSHUB_SECRET_KEY` | 去掉该环境变量后点 [开始绑定] | 400 `secret_key_missing`，页面把这句显示出来；**不能**退回"明文存库" | [TotpController.java:49-52](../backend/src/main/java/com/rsshub/security/TotpController.java#L49) `[自]` `[B]` |
| TC-TOT-04 | P0 | [启用] 的门槛 | 动态码没输满 6 位 | 按钮 `disabled`（`code.activate.trim().length < 6`）；输错码提交得 400 `bad_code`，提示去核对手机时钟 | [SecurityView.vue:111-113](../web/src/views/SecurityView.vue#L111) · [TotpController.java:74-76](../backend/src/main/java/com/rsshub/security/TotpController.java#L74) `[自]` `[B]` |
| TC-TOT-05 | P0 | [放弃这次绑定] | 点 | 不调任何接口，回到"未启用"视图；密钥只在这次响应里出现过一次，页面清空即可 | [SecurityView.vue:117](../web/src/views/SecurityView.vue#L117) `[B]` |
| TC-TOT-06 | P0 | 启用后的连锁 | 成功 [启用] 后退出登录 | 登录页的**动态口令框变成必填**（不带码就 401）；`/api/auth/me` 的 `totpEnabled` 变 true，页面状态行随之改变 | [SecurityView.vue:50](../web/src/views/SecurityView.vue#L50) · [LoginView.vue:64-66](../web/src/views/LoginView.vue#L64) `[自]TotpLoginGateTest#aPasswordAloneStopsBeingEnoughAndACodeStartsBeingRequired` |
| TC-TOT-07 | P0 | [关闭二次验证] | 填**口令**后点 | 204 后 `session.refresh()`，状态回"未启用"；只给码不给口令是 400 `password_required` | [SecurityView.vue:123-123](../web/src/views/SecurityView.vue#L123) · [TotpController.java:80-91](../backend/src/main/java/com/rsshub/security/TotpController.java#L80) `[自]TotpLoginGateTest#removingTheSecondFactorNeedsThePasswordNotTheCode` |
| TC-TOT-08 | P1 | 未启用时点关闭 | 直接 `POST /api/auth/totp/deactivate` | 404 `totp_inactive`（不是 204 假装成功） | [TotpController.java:84-85](../backend/src/main/java/com/rsshub/security/TotpController.java#L84) `[自]TotpLoginGateTest#theStateThatDoesNotExistIsRefusedRatherThanIgnored` |
| TC-TOT-09 | P1 | 页面下方的说明区 | 观察 | "登录失败锁定"一节写明 5 次锁 15 分钟、429 `account_locked` + `Retry-After`、enroll/activate 两步的理由——**是文档不是控件**，别当按钮找 | [SecurityView.vue:140](../web/src/views/SecurityView.vue#L140) `[B]` |

---

## 20. 第四轮新增用例（M3.5：`DSH` 大屏 / `SET` 系统参数 / `SYS` 系统状态）

**`[B]` 全部尚未执行**，与第 19 节同一约束：不跑浏览器登录自动化，交付证据是 `npm run build`（严格 TS）+ `web/src/api/{settings,dashboard,system}.ts` 对照三个新 controller + 后端行为全有 `[自]` MockMvc 兜底（[03 §14 O19](./design/03-technical-design.md)）。本表是人工走查清单。设计口径集中一处：[03 §8.5](./design/03-technical-design.md)。

### 20.1 `DSH` 大屏（`DashboardView.vue`）

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-DSH-01 | P0 | 顶栏 [大屏](../web/src/App.vue#L31) | 点 | `GET /api/dashboard`；标题下说明行"每源最新 20 条 · 每 60 秒自动刷新 · 最近抓取 …"（三个数字全部来自响应，不是写死的；`refreshSeconds` 为 0 时第二段读作"自动刷新已关闭"） | [DashboardView.vue:54-58,151](../web/src/views/DashboardView.vue#L54) · [DashboardController.java:116-139](../backend/src/main/java/com/rsshub/adminapi/DashboardController.java#L116) `[自]DashboardApiTest#emptyDatabaseAndAuth` `[B]` |
| TC-DSH-02 | P0 | 空状态 | 无订阅时进入 | 提示"还没有订阅，先去「订阅管理」加一个，大屏会随抓取自动补上"，不渲染空网格 | [DashboardView.vue:162](../web/src/views/DashboardView.vue#L162) `[自]` 同上 `[B]` |
| TC-DSH-03 | P0 | 卡片墙 | 有 2 个以上订阅 | 每源一卡（含空源），按"未分组在前、组名字典序"排；卡头 = 标题 + 分组标签 + 未读角标。**标题是操作员的名字**：`custom_title` 有值就用它，否则回落站点 `<title>`，再回落地址 | [DashboardView.vue:230-242](../web/src/views/DashboardView.vue#L230) · [FeedRepository.java:97-120](../backend/src/main/java/com/rsshub/feedstore/FeedRepository.java#L97) `[自]DashboardApiTest#oneCardPerFeedNewestFirst` `[C]smoke.sh:384-387` `[B]` |
| TC-DSH-04 | P0 | 卡内条目 | 观察 | 每卡最多 N 条、按发布时间倒序；标题超长省略号；右侧相对时间（"3 分钟前"） | [DashboardView.vue:251-262](../web/src/views/DashboardView.vue#L251) · [EntryFinder.java:203-225](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L203) `[自]` 同上 `[B]` |
| TC-DSH-05 | P0 | 已读条目 | 把一条标记已读后刷新 | 该行灰显（`.read`），仍在卡里；未读角标少了 1 | [DashboardView.vue:253](../web/src/views/DashboardView.vue#L253) `[自]DashboardApiTest#unreadBadgeExcludesReadAndBaseline` `[B]` |
| TC-DSH-06 | P1 | 无链接条目 | 造一条 `url` 为空的 | 标题渲染为纯文本 span，不可点（有 url 才是链接，`target=_blank` + `rel=noreferrer`） | [DashboardView.vue:256-258](../web/src/views/DashboardView.vue#L256) `[B]` |
| TC-DSH-07 | P0 | 暂停源 | 把某源置 `paused` | 卡片灰底（`.dim`）+ `paused` 标签（**中性色**，暂停是选择不是故障，见 TC-UIF-08），**不消失**；空源卡显示"还没有条目" | [DashboardView.vue:232,232-242,250](../web/src/views/DashboardView.vue#L232) `[自]DashboardApiTest#pausedAndEmptyFeedsStayVisible` `[B]` |
| TC-DSH-08 | P0 | 自动刷新 | 等一个刷新周期 | `refreshSeconds>0` 时每周期静默重拉；改成 0 保存后**不再发请求**（`watch` 重建定时器，离开页面清理） | [DashboardView.vue:17-52](../web/src/views/DashboardView.vue#L17) `[B]`（定时器行为无自动断言） |
| TC-DSH-09 | P0 | 与设置联动 | 设置页把"大屏每源条数"改成 2 保存 | 大屏点 [立即刷新] 后每卡只剩 2 条且是最新的两条，说明行变"每源最新 2 条" | [SettingsView.vue:239-246](../web/src/views/SettingsView.vue#L239) `[自]DashboardApiTest#honoursTheEntriesPerFeedSetting` `[B]` |
| TC-DSH-10 | P2 | 后端停机 | 停后端点 [立即刷新] | 按钮变"读取中…"并禁用，错误行显示 `code: message`，已有卡片保留不白屏 | [DashboardView.vue:142-159,27-36](../web/src/views/DashboardView.vue#L142) `[B]` |

### 20.2 `SET` 系统参数（`SettingsView.vue` 表单区）

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-SET-01 | P0 | 进入页面 | 观察 | `GET /api/settings` 与 `GET /api/system/status` 并行加载；四个输入框回显当前值（新装是 20/60/50/100） | [SettingsView.vue:24,39-47,239](../web/src/views/SettingsView.vue#L24) · [SettingsController.java:25-33](../backend/src/main/java/com/rsshub/adminapi/SettingsController.java#L25) `[自]SettingsApiTest#defaultsAndAuth` `[B]` |
| TC-SET-02 | P0 | 越界值 | "大屏每源条数"输 0 或 51 | 红字"必须在 1–50 之间"（正常态是灰字"范围 1–50"），[保存] `disabled` | [SettingsView.vue:55-60,243-246,252](../web/src/views/SettingsView.vue#L55) `[自]SettingsApiTest#refusesOutOfRangeValues` `[B]` |
| TC-SET-03 | P0 | 非整数 | 输 12.5 | 同上红字——`Number.isInteger` 把小数一并拦下；绕过前端发小数得 400 `bad_setting` | [SettingsView.vue:57](../web/src/views/SettingsView.vue#L57) · [SettingsService.java:105-107](../backend/src/main/java/com/rsshub/settings/SettingsService.java#L105) `[自]SettingsApiTest#refusesNonWholeNumbers` `[B]` |
| TC-SET-04 | P0 | 保存的最小面 | 只改一个字段后保存 | 请求体**只含被改的键**（`changed` 逐键比对），成功后绿字"已保存，下一次读取即生效" | [SettingsView.vue:62-73,79,258](../web/src/views/SettingsView.vue#L62) `[自]SettingsApiTest#patchIsPartialAndPersistent` `[B]` |
| TC-SET-05 | P0 | 未改动就保存 | 观察 [保存] | 无任何改动时 `disabled`（`changed` 为空），不给"保存了个寂寞"的机会 | [SettingsView.vue:252](../web/src/views/SettingsView.vue#L252) `[B]` |
| TC-SET-06 | P0 | 参数真实生效 | 把"收件箱每页条数"改 10 后开收件箱 | 收件箱首屏 10 条（缺省 limit 来自设置——SPA 的请求**不带 `limit`**，2026-11-01 修掉了 [entries.ts](../web/src/stores/entries.ts#L65) 里硬发的 `limit: 50`，见 23.4）；显式翻页不受影响 | [EntryController.java:123-126](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L123) `[自]SettingsApiTest#steersTheInboxDefault / steersTheNotificationsDefault` `[C]smoke.sh` 尾段（PATCH 10 → 无 `limit` 得 10 条 + 游标 → 徽标不变 → 还原 50 → 页数跟着变回） `[B]` |
| TC-SET-07 | P1 | 绕过前端发未知键 | curl `PATCH /api/settings` 带 `{"fetch.intervalMinutes":5}` | 400 `unknown_setting`，错误行显示键名；前端表单根本没有这个输入框——抓取节奏**刻意不入库**（改它要重启） | [SettingsService.java:99-101](../backend/src/main/java/com/rsshub/settings/SettingsService.java#L99) · [SettingsView.vue:235-237](../web/src/views/SettingsView.vue#L235) 的说明段 `[自]SettingsApiTest#refusesUnknownKeys` `[C]` |
| TC-SET-08 | P1 | 范围端点 | 四个字段分别填各自 min/max | 全部可保存（1/50、0/600、10/200、10/500）——上限与控制器 MAX_LIMIT 一致，不存在"页面收了、控制器又钳掉"的假设置 | [api/settings.ts:12-17](../web/src/api/settings.ts#L12) `[自]SettingsApiTest#acceptsTheRangeEnds` `[B]` |
| TC-SET-09 | P2 | 库里手改坏值 | SQL 直接 `update settings set setting_value='999'` | 页面显示 50（夹回边界），写垃圾字符串则回缺省 20；不炸、不静默沿用 999 | [SettingsService.java:118-125](../backend/src/main/java/com/rsshub/settings/SettingsService.java#L118) `[自]SettingsApiTest#survivesHandEditedRows` |

### 20.3 `SYS` 系统状态（`SettingsView.vue` 状态区 + `/api/system/status`）

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-SYS-01 | P0 | 四个状态面板 | 进入设置页 | 系统（OS/Java/启动时间/运行时长）、JVM（堆 三数+百分比/线程/类）、数据库、服务四个面板都出数；"采集于"带当前时间 | [SettingsView.vue:129-231](../web/src/views/SettingsView.vue#L129) · [SystemStatusController.java:86-137](../backend/src/main/java/com/rsshub/adminapi/SystemStatusController.java#L86) `[自]SystemStatusApiTest#reportsTheMachineAndItsServices` `[B]` |
| TC-SYS-02 | P0 | 服务两布尔 | 开发态观察；再去掉 `RSSHUB_NOTIFY_ENABLED` 重启 | 两个调度器分别显示"运行中/已停用"，与 `@ConditionalOnProperty` 同源——**状态页不能对没装配的调度器说运行中** | [SettingsView.vue:209-213](../web/src/views/SettingsView.vue#L209) · [FetchDispatcher.java:36](../backend/src/main/java/com/rsshub/feedfetch/FetchDispatcher.java#L36) · [DeliverDispatcher.java:33](../backend/src/main/java/com/rsshub/notify/DeliverDispatcher.java#L33) `[自]` 同上（两断言） `[B]` |
| TC-SYS-03 | P0 | 计数一致性 | 对照各页面/SQL | 订阅/条目/规则/通知四个计数与库内行数一致（条目含已读归档全部；角标口径是另一回事，别拿收件箱角标来对） | [SystemStatusController.java:105-108](../backend/src/main/java/com/rsshub/adminapi/SystemStatusController.java#L105) `[自]SystemStatusApiTest#countsMatchTheStoredRows` `[B]` |
| TC-SYS-04 | P1 | 队列与日志分布 chips | 空库 / 有投递记录时 | 空库显示"空"（不是 0 也不是缺键）；有行显示 `sent 1` 这样的 chip，`pending`>0 且投递开着 = 排队中的外发 | [SettingsView.vue:215,221](../web/src/views/SettingsView.vue#L215) `[自]SystemStatusApiTest#zeroRowsIsAZeroStatusNotAnError` `[B]` |
| TC-SYS-05 | P1 | [重新读取] | 点 | 按钮禁用变"读取中…"，"采集于"时间戳前进 | [SettingsView.vue:120-123](../web/src/views/SettingsView.vue#L120) `[B]` |
| TC-SYS-06 | P0 | 未登录直连 | 不带会话 GET 三端点 | `/api/dashboard`、`/api/settings`、`/api/system/status` 全部 401（不 302） | [SecurityConfig.java:86-87](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L86) `[自]` 三类第一条 `[C]` |
| TC-SYS-07 | P2 | 数据库掉线 | 只能代码路径验证：本机 H2 停进程即全停 | `up=false`、四计数 0、页面仍 200——状态页的故障态就是它要报告的内容，不是 500 | [SystemStatusController.java:123-133](../backend/src/main/java/com/rsshub/adminapi/SystemStatusController.java#L123) `[自]`（代码断言，未真断库） |

### 20.4 第四轮 `[C]` 执行实录（2026-10-18，M0–M3.5 全链路真 HTTP）

**工具**：[backend/smoke.sh](../backend/smoke.sh) 从 M0 的 14 项扩到 **55 项**（第四轮当时口径；此后每轮只追加不删除，现 **222 项**，见 §0.3 与 25.4），一次跑完：M0 会话/CSRF 卫生 → M3/M3.5 各操作员端点的匿名 401 与缺 CSRF 拦截 → M1 订阅 + 首抓基线 + 未变 304 + 变更后新条目（fixture 源上的真实条件请求）→ M3 规则/试跑按钮/机器令牌/Bearer 摄入/dedupe 重放/公开事件源 → M3.5 设置缺省-PATCH-持久化-双 400 负例/大屏回显-单卡-截断-未读角标/系统状态五组事实 → 登出与 trace 收尾。本节标 `[C]` 的 TC-SET-07、TC-SYS-06 由本段覆盖（`[B]` 仍留人工）。

**环境**（§0.5"不动开发库"口径）：一次性 `:18080` 实例——`DB_URL=jdbc:h2:file:./.dev-logs/smoke-db` 全新空库（跑完即删）、口令为运行时自造的临时值（只经环境变量进进程，未读未记真实口令）、`--rsshub.ssrf.allow-private-prefixes=127.`（仅为放行本机 fixture 源 `:19999`，产品默认空清单不变）；被测 jar 为同日 `./mvnw verify` 360 项全绿后重新打包的产物。启动与 Flyway 迁移日志：`.dev-logs/smoke-backend.log`（全新库从 V1 迁到 V3；该文件已覆盖第二轮同名日志）。

**结果**：`ALL PASS`（55/55）。修复循环发现的两处都是"脚本/文档陈旧"，**没有产品缺陷**：

| 发现 | 原因 | 处置 |
|---|---|---|
| 一次性实例拒绝启动，H2 报 90011 "implicitly relative path is not allowed" | 18.1 节记录的 URL 少 `./` 前缀；H2 2.x 不接受隐式相对路径，[application.yml:33](../backend/src/main/resources/application.yml#L33) 默认值正是 `./data/rss-hub` 形式 | 启动命令与 18.1 改用 `./.dev-logs` 形式；登记 §17 勘误表与 [03 §14 O12](./design/03-technical-design.md) 第 ⑤ 条 |
| M0 断言"匿名 `GET /` → 401"失败（实得 200） | M2 起 `/` 是公开 HTML 落地页：[SecurityConfig.java:78-79](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L78) 把 `/` 与 `/feed.*`、`/blog`、`/event/*/*` 一并 `permitAll`。第二轮只重跑了附录 A 序列、没重跑 M0 版脚本，过期断言一直没暴露 | 断言改为"匿名可访问（200）"与"读它不得建立会话（jar 里无 `RSSHUB_SESSION`）"两条——后者才是要守住的安全属性 |

---

## 21. 第五轮新增用例（M5：`RPL` 聚合再分发）

**`[B]` 全部尚未执行**，与第 19/20 节同一约束：不跑浏览器登录自动化，交付证据是 `npm run build`（严格 TS）+ `web/src/api/repacks.ts` 逐字段对照 `RepackService` 的四个视图记录 + 后端行为全有 `[自]` MockMvc 与 `[C]` 真 HTTP 兜底（[03 §14 O20](./design/03-technical-design.md)）。设计口径集中一处：[03 §8.6](./design/03-technical-design.md)；需求侧的版权义务表述见 [01 §4 F7](./design/01-requirements.md)。

本轮的特殊性：**FR-7.1/7.2 的验收面在公开出口上，不在页面上**——"每条可回溯原站""默认只转发摘要"要用 `curl` 打到 `/repack/{slug}.rss|atom|json` 才看得见，所以下表把 UI 断言与出口断言成对写，出口那半边已有 `[自]`/`[C]` 覆盖，UI 那半边等人工。

### 21.1 `RPL` 聚合页（`RepackView.vue`，路由 `/repacks`）

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-RPL-01 | P0 | 顶栏 [聚合](../web/src/App.vue#L34) | 点 | `GET /api/repacks` 与 `GET /api/feeds` 并行加载；左列集合表（slug/标题/成员数/出口/操作），右列未选中时提示"点左侧的 slug 查看成员与全文开关的审计日志" | [RepackView.vue:227,306](../web/src/views/RepackView.vue#L227) · [router/index.ts:23](../web/src/router/index.ts#L23) · [RepackController.java:51](../backend/src/main/java/com/rsshub/adminapi/RepackController.java#L51) `[自]RepackApiTest#collectionCrud` `[B]` |
| TC-RPL-02 | P0 | slug 输入框 | 填 `Bad Slug`/`-abc`/`abc-` | 浏览器 `pattern` 当场拦下（`[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?`），提交按钮不发请求；绕过前端仍得 400 `invalid_slug` | [RepackView.vue:280](../web/src/views/RepackView.vue#L280) · [RepackService.java:156-158](../backend/src/main/java/com/rsshub/repack/RepackService.java#L156) `[自]` 同上 `[C]` 冒烟"slug with a space refused" `[B]` |
| TC-RPL-03 | P0 | [创建] | 填 slug+标题提交 | 201，列表首行出现该集合，绿字提示公开地址；大写的 slug 在**后端**已被小写化后落库 | [RepackView.vue:116,294](../web/src/views/RepackView.vue#L116) · [RepackController.java:61-62](../backend/src/main/java/com/rsshub/adminapi/RepackController.java#L61) `[自]collectionCrud` `[C]` "create lower-cases the slug" |
| TC-RPL-04 | P0 | [创建] 重名 | 用已存在的 slug 再建 | 错误行显示 `repack_exists: …`，列表不出现第二行 | [RepackService.java:83](../backend/src/main/java/com/rsshub/repack/RepackService.java#L83) `[自]collectionCrud` `[B]` |
| TC-RPL-05 | P0 | slug（编辑态） | 点 [编辑] | 输入框 `disabled`——slug 是公开 URL 的路径段，创建后不可改；标题/说明可改 | [RepackView.vue:25](../web/src/views/RepackView.vue#L25) 的 `:disabled="repackForm.editing"` · [RepackService.java:91](../backend/src/main/java/com/rsshub/repack/RepackService.java#L91) `[自]collectionCrud`（PATCH 未提交的 description 保持原值） `[B]` |
| TC-RPL-06 | P0 | [出口] 三个 pill | 点 rss/atom/json | 新标签打开 `/repack/{slug}.{format}`，**匿名可读**、不建会话；三者由同一 `publicUrl` 换后缀得到 | [RepackView.vue:253](../web/src/views/RepackView.vue#L253) · [repacks.ts:65-70](../web/src/api/repacks.ts#L65) · [PublicFeedController.java:89](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L89) · [SecurityConfig.java:78](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L78) `[自]theOutletBehavesLikeAPollTarget` `[C]` "the outlet mints no session" |
| TC-RPL-07 | P0 | 出口内容 | `curl` 该 RSS | 每个 `<item>` 同时有 `<source url="原站 URL">原站标题</source>`、`<link>` 指向原文、`<guid isPermaLink>` 沿用原 guid；`.atom` 是 `<source><id>`、`.json` 是 `external_url` | [RepackFeedSource.java:65](../backend/src/main/java/com/rsshub/repack/RepackFeedSource.java#L65) · [Rss2Writer.java:108-112](../backend/src/main/java/com/rsshub/feedrender/Rss2Writer.java#L108) `[自]membersShipTheirSourceAndOriginalLink` `[C]` "each item names its source" 等 6 条 |
| TC-RPL-08 | P0 | 出口正文（FR-7.2 缺省） | 新建集合加入源后 `curl` | **没有** `content:encoded` / `content_html` 全文，只有摘要；摘要缺失时是 300 字预览 | [RepackFeedSource.java:66-69](../backend/src/main/java/com/rsshub/repack/RepackFeedSource.java#L66) · [V4__repack_schema.sql h2:19](../backend/src/main/resources/db/migration/h2/V4__repack_schema.sql#L19) `[自]fullContentIsPerMemberAndAudited` `[C]` "FR-7.2: full text withheld by default" |
| TC-RPL-09 | P0 | [加入集合] 下拉 | 打开 | 只列出**尚未在这个集合里**的订阅源，数量写在标签上；新加入一律"仅摘要" | [RepackView.vue:30,366,370,374](../web/src/views/RepackView.vue#L366) · [RepackRepository.java:169](../backend/src/main/java/com/rsshub/repack/RepackRepository.java#L169)（插入语句不接收 `full_content`） `[自]membershipErrors` `[B]` |
| TC-RPL-10 | P0 | [加入集合] 提交 | 选一个源点提交 | 成员表出现该源（条目数、"仅摘要"pill、决定时间 `—`）；绿字"已加入，默认只转发摘要。"；重复加入得 409 `already_repacked`、未知源 404 `feed_not_found` | [RepackView.vue:160,163](../web/src/views/RepackView.vue#L160) · [RepackService.java:106-112](../backend/src/main/java/com/rsshub/repack/RepackService.java#L106) `[自]membershipErrors` `[C]` "same feed added twice" |
| TC-RPL-11 | P0 | 全文 pill（开） | 点"仅摘要"想开全文 | **先弹确认框**："转发「…」的全文会把该站点的正文原样再发布一次，转载授权由你确认。这一步会写进审计日志。继续吗？"——确认后 pill 变"转发全文"，出口里出现全文 | [RepackView.vue:174-186](../web/src/views/RepackView.vue#L174) · [RepackService.java:134](../backend/src/main/java/com/rsshub/repack/RepackService.java#L134) `[自]fullContentIsPerMemberAndAudited` `[C]` "full text ships after the opt-in" `[B]`（确认框本身只能人眼看） |
| TC-RPL-12 | P1 | 全文 pill（关） | 再点一次 | 退回"仅摘要"**不弹确认**（关不是风险动作），出口里全文消失，审计多一行 `full_content_off` | [RepackView.vue:171-179](../web/src/views/RepackView.vue#L171) `[自]fullContentIsPerMemberAndAudited` `[B]` |
| TC-RPL-13 | P0 | 审计表 | 观察 | 四动作显示为中文（加入集合/移出集合/开启全文/关闭全文），最近 50 条倒序；**标志没变的重试不产生新行** | [RepackView.vue:33-37,382,396](../web/src/views/RepackView.vue#L382) · [RepackService.java:28,138-141](../backend/src/main/java/com/rsshub/repack/RepackService.java#L138) `[自]fullContentIsPerMemberAndAudited` `[C]` "an unchanged flag writes no audit row" |
| TC-RPL-14 | P0 | [移出] / [删除] | 点 | 两个都有 confirm（删除写明"成员关系一起删除，公开出口随即消失"）；移出后出口少该源全部条目；删集合后 `GET /repack/{slug}.rss` → 404 | [RepackView.vue:133,194](../web/src/views/RepackView.vue#L133) · [RepackService.java:100,120](../backend/src/main/java/com/rsshub/repack/RepackService.java#L100) `[自]theAdminSurfaceIsGuardedWhileTheOutletIsAnonymous` `[C]` "the outlet is gone with it" `[B]` |
| TC-RPL-15 | P0 | 审计的持久性 | 移出成员 / 退订原站 / 删集合后再查 | 审计行**仍在**（该表无外键），这是 M5 门禁"全文开关有审计日志"的全部意义；页面底部有同一句话 | [RepackView.vue:412](../web/src/views/RepackView.vue#L412) · [V4__repack_schema.sql h2:25-35](../backend/src/main/resources/db/migration/h2/V4__repack_schema.sql#L25) `[自]theTrailOutlivesTheRowsItDescribes` |
| TC-RPL-16 | P1 | 空集合 | 建好不加成员就点出口 | 200 + 合法空 feed（成员区文案"空集合也是一个合法的出口"）；未知 slug 才是 404 | [RepackView.vue:354](../web/src/views/RepackView.vue#L354) · [RepackFeedSource.java:104-105](../backend/src/main/java/com/rsshub/repack/RepackFeedSource.java#L104) `[自]anEmptyCollectionStillShips` `[C]` "an unknown collection has no outlet" |
| TC-RPL-17 | P2 | 后端错误呈现 | 停后端/塞错值 | 错误行统一 `code: message`（`urn:rsshub:<code>` 剥出 code），已有列表不清空 | [RepackView.vue:44-45](../web/src/views/RepackView.vue#L44) 的 `note()` · [api/errors.ts](../web/src/api/errors.ts) `[B]` |

### 21.2 本轮自动化与真 HTTP 覆盖（2026-10-31 实测）

- `[自]`：`adminapi/RepackApiTest` **10 条**（370 = M3.5 末 360 + 10；测试类 34 → 35）——`theAdminSurfaceIsGuardedWhileTheOutletIsAnonymous`（管理面匿名 401、未知出口 404、有会话无 CSRF 403）、`collectionCrud`、`membersShipTheirSourceAndOriginalLink`、`fullContentIsPerMemberAndAudited`、`dismissedEntriesAreNeverForwarded`、`idsStayStableAcrossTheRepost`、`theOutletBehavesLikeAPollTarget`、`anEmptyCollectionStillShips`、`membershipErrors`、`theTrailOutlivesTheRowsItDescribes`；另有 `MigrationDialectAlignmentTest` 的 `MIGRATIONS` 扩到 V1–V4，`ArchitectureTest` 七条不变（新包 `repack` 只 import `common` + `feedrender`）。
- `[C]`：`backend/smoke.sh` 静态 `check` 行 51 → **79**，加匿名循环里的 `/api/repacks` 与既有两处循环展开后实测执行数 **57 → 86**（脚本头的旧口径"55 项"本就少算 2 项，本轮以实际输出为准）。M5 段 29 条覆盖：slug 小写化、publicUrl 路径、缺 CSRF 拒绝、非法 slug 400、成员加入/409/404、RSS 的 `<source>`+`<link>`+`guid isPermaLink`、摘要缺省（无 `Full body`）、JSON `external_url`、Atom `<source><id>`、未知集合 404、开全文后全文出现、审计两行、重复 PATCH 不追加、ETag 与 304、出口不建会话、PATCH 保留未提交字段、删集合 204 与出口 404。
- 执行结果：**2026-10-31 `ALL PASS`（86/86）**，`./mvnw -o -B -ntp verify` `Tests run: 370, Failures: 0`，`npm run build` 通过（89 modules，178.07 kB JS / 25.08 kB CSS）。实录见 21.3。

### 21.3 第五轮 `[C]` 执行实录（2026-10-31，M0–M5 全链路真 HTTP）

**环境**（与 20.4 同一口径）：一次性 `:18080` 实例 + `node .dev-logs/smoke-feed-server.js`（`:19999`），`DB_URL=jdbc:h2:file:./.dev-logs/smoke-db` 全新空库、Flyway 从 V1 迁到 **V4**、口令为运行时自造的临时值（只经环境变量进进程），`--rsshub.ssrf.allow-private-prefixes=127.` 仅放行 fixture 源；被测 jar 是同日 `verify` 370 项全绿后 repackage 的产物；日志 `.dev-logs/smoke-backend.log`。

**两处为可观测性做的准备（不是缺陷）**：

| 发现 | 处置 |
|---|---|
| fixture 源原本只有 `<description>`，`entry.content_html` 恒为 NULL → FR-7.2 的全文开关在 HTTP 层**看不出差别**，冒烟会变成假绿 | 给 `.dev-logs/smoke-feed-server.js` 的每条 item 加 `<content:encoded><![CDATA[Full body …]]></content:encoded>`，于是"开关前无 `Full body`、开关后有"成为真断言 |
| 脏库复跑（不删 `smoke-db` 再跑一次）在 23 条上失败 | 不是产品缺陷：这些断言的前提是"一次性实例"（订阅已存在 → `FID` 为空 → 下游连带失败）。脚本头第 6–7 行已声明 fresh DB 前提，本轮据此把 smoke-db 删除后重跑，得到 `ALL PASS`——记录在此以免下次误判 |

**出口发现**：无。M5 的实现面（`<source>`/摘要缺省/审计/ETag）在第一轮全绿，唯一被改动的产品侧代码是 `PublicFeedController` 多一条映射、`SecurityConfig` 白名单多 `/repack/*`，均在这一轮之前已由 `[自]` 覆盖。

---

## 22. 第六轮新增用例（F1.6：`CHG` 变更公告，2026-11-01）

关闭的是第 12 节的 **BLK-02**——M2 范围内唯一没交付的需求项。设计口径集中一处：[03 §8.7](./design/03-technical-design.md)；需求侧的 v4 口径修正（`.xml` → 后缀式三格式）见 [01 §4 F1](./design/01-requirements.md) 与本文第 13 节 D-01 同源。

`[B]` 全部尚未执行，与第 19/20/21 节同一约束：不跑浏览器登录自动化，交付证据是 `npm run build`（`vue-tsc --noEmit` 严格模式）+ `web/src/api/changelog.ts` 逐字段对照 `ChangelogController.Entry` + 后端行为全有 `[自]` MockMvc 与 `[C]` 真 HTTP 兜底（[03 §14 O21](./design/03-technical-design.md)）。

本轮的特殊性有两条：**验收面在"写侧判据"上，不在读侧**——"草稿不是新闻""no-op 保存不算变更""删除也要公告"这三条只能靠"造出这个动作，再数库里有几行"来证，所以 `[自]` 与 `[C]` 都成对写；**另一条是需求原文与实现的 URL 口径不一致**（`/feed/changelog.xml` vs 三格式），按 D-01 的先例裁定"实现为准、需求文档改"，本轮已在 01 落笔。

### 22.1 `CHG` 变更公告（出口 + `GET /api/changelog` + `PostsView` 面板）

| 编号 | P | 面 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-CHG-01 | P0 | RSS 出口 | 发布一篇文章后 `GET /feed/changelog.rss` | `<title>New post: <标题></title>`、`<link>` 是文章 permalink、`<atom:link rel="self">` 指回 `.rss` 自己、`<category>created</category>`、`<guid isPermaLink="false">urn:rsshub:change:…` | [ChangeLogFeedSource.java:88](../backend/src/main/java/com/rsshub/changelog/ChangeLogFeedSource.java#L88) · [Rss2Writer.java:88](../backend/src/main/java/com/rsshub/feedrender/Rss2Writer.java#L88) `[自]aPublishedPostIsAnnouncedThreeTimesOver` `[C]` "publishing announces the transition"/"the item is tagged with its action"/"the guid is the change's own URN" |
| TC-CHG-02 | P1 | Atom 出口 | `GET /feed/changelog.atom` | `<entry>` 存在，`<link rel="self" …href="…changelog.atom"/>`，标题与 RSS 一致 | [PublicFeedController.java:57](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L57) `[自]` 同上 `[C]` "Atom renders the same trail" |
| TC-CHG-03 | P0 | JSON 出口 | `GET /feed/changelog.json` | `title` 是 `Changelog · <站点标题>`、`feed_url` 指 `.json`、条目的 `url` 是 permalink、`tags[0]` 是动作名，且**没有** `content_html` 键（公告是指针不是副本） | [ChangeLogFeedSource.java:29,52,74](../backend/src/main/java/com/rsshub/changelog/ChangeLogFeedSource.java#L74) `[自]` 同上 `[C]` "JSON Feed renders the same trail" |
| TC-CHG-04 | P0 | 写侧判据：草稿 | 建草稿 → 编辑草稿 → 三格式各拉一次 → 再发布 | 前三步 `content_change` **零行**，草稿标题不出现在任何公开文档里；空出口仍 200 且 `items: []`；发布那一步才出现第一行，且标题是 `Published: <草稿标题>`（**不是** `New post:`，因为这是一次状态移动） | [ChangeLogRepository.java:78,92](../backend/src/main/java/com/rsshub/changelog/ChangeLogRepository.java#L78) · [PostService.java:130](../backend/src/main/java/com/rsshub/editorial/PostService.java#L130) `[自]draftsAreWrittenSilently` `[C]` "a draft writes no change row"/"a draft never reaches the outlet"/"publishing announces the transition" |
| TC-CHG-05 | P1 | 写侧判据：生来公开 | `POST /api/posts` 时 `status=published` | 一行 `created`，标题 `New post: …`——与 TC-CHG-04 是**两个不同动作**，必须分别验 | [PostService.java:83](../backend/src/main/java/com/rsshub/editorial/PostService.java#L83) `[自]draftsAreWrittenSilently`（后半段）`[C]` "a birth announces New post"/"a birth is tagged created" |
| TC-CHG-06 | P0 | 撤销与删除 | published → draft → published → `DELETE` | 每一步各一行；`Deleted: …` 仍在出口里、`<link>` 仍指向已 404 的 permalink、描述含"不再可访问"；`/feed.rss` 不再含该 permalink；`select count(*) from post`=0 而变更行数**不减** | [V5__changelog_schema.sql h2:6-7](../backend/src/main/resources/db/migration/h2/V5__changelog_schema.sql#L6) · [ChangeLogFeedSource.java:111](../backend/src/main/java/com/rsshub/changelog/ChangeLogFeedSource.java#L111) `[自]unpublishingAndDeletingBothAnnounceThemselves` `[C]` "the removal is announced"/"the notice says the URL is dead"/"the trail outlives the post"（5 行） |
| TC-CHG-07 | P1 | 静默：无变化 | 把刚读到的文档原样 `PUT` 回去；再发一次同状态 `PATCH` | 行数不变。`updated_at` 会动，但那不是变更（[PostService.java:99](../backend/src/main/java/com/rsshub/editorial/PostService.java#L99) 的注释即此） | `[自]noiseAndRefusalsLeaveNoRows` `[C]` "a no-op save adds no row" |
| TC-CHG-08 | P0 | 静默：被拒绝的写 | `PATCH` archived→scheduled（不在迁移表）；`POST /api/posts` 缺正文 | 分别 409 `urn:rsshub:illegal_transition`、400；**两者都零新行**——先判后写，拒绝不能留下半条历史 | [PostService.java:30-42](../backend/src/main/java/com/rsshub/editorial/PostService.java#L30) `[自]noiseAndRefusalsLeaveNoRows` `[C]` "an illegal transition is refused" + "an illegal transition writes nothing" |
| TC-CHG-09 | P0 | 条目 ID 稳定 | 发布 → 改名并改正文 | 两条条目，旧的 `id` 与 `url` **一字不变**（`url` 仍是旧 slug 的地址），新的 `id` 与旧的不同且不含 `/blog/`；新条目标题 `Updated: <新标题>` | [ChangeLogFeedSource.java:79-86](../backend/src/main/java/com/rsshub/changelog/ChangeLogFeedSource.java#L79) `[自]aChangeRowHasAStableIdOfItsOwn` |
| TC-CHG-10 | P0 | 出口鉴权与缓存头 | 匿名 `GET /feed/changelog.rss` | 200 + `Content-Type: application/rss+xml;charset=UTF-8` + `Cache-Control` 含 `max-age=60` 与 `must-revalidate` + `ETag` + `Last-Modified`；响应里**没有** `RSSHUB_SESSION` cookie，服务端**没有**新建会话 | [SecurityConfig 公开域](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L75) · [03 §6.4](./design/03-technical-design.md) `[自]theChangelogAnswersWithRealValidators` `[C]` "the outlet mints no session" |
| TC-CHG-11 | P1 | ETag 语义 | 带 `If-None-Match` 复拉 → 原样重存同一文档 → 再拉 → 新建一篇 → 再拉 | 304 → 仍 304（指纹数的是**变更行**，不是文章的 `updated_at`）→ 200 且 ETag 变了 | [ChangeLogFeedSource.java:63](../backend/src/main/java/com/rsshub/changelog/ChangeLogFeedSource.java#L63) · [ChangeLogRepository.java:131](../backend/src/main/java/com/rsshub/changelog/ChangeLogRepository.java#L131) `[自]` 同上 `[C]` "the outlet answers a conditional GET" |
| TC-CHG-12 | P1 | 验证子互不通用 | 取三格式各自的 ETag；再取 `/feed.rss` 的 | 三个格式三个不同的验证子（一份文档三种序列化），且**都不等于站点源的**；拿 RSS 的 ETag 去问 `.json` 得 200 不是 304 | [03 §6.4](./design/03-technical-design.md) `[自]` 同上 `[C]` "the validator differs from the site feed's" |
| TC-CHG-13 | P2 | 路由冲突 | 建一个 slug 恰为 `changelog` 的事件流，再 `GET /feed/changelog.rss` 与 `/feed/builds.rss` | 前者是**变更公告**（字面量映射赢过 `/feed/{slug}.{format}`），后者仍正常；`GET /feed/changelog.opml` → 404 `urn:rsshub:unknown_format`。这条不是 bug，是 URL 形态的代价，写死是为了别让人当缺陷修 | [PublicFeedController.java:57,79](../backend/src/main/java/com/rsshub/feedrender/PublicFeedController.java#L57) · [FeedScope.java:37](../backend/src/main/java/com/rsshub/feedrender/FeedScope.java#L37) `[自]theChangelogShadowIsOnlyTheOnePathWide` `[C]` "the changelog path has no opml format" |
| TC-CHG-14 | P1 | 条数上限 | 连发 51 篇 | 出口 50 条、最新在前、最旧的 `Change 01` 被丢；`item-limit` 沿用 `application.yml:124`，公告**没有**自己的上限参数 | [FeedKey.java:14,32](../backend/src/main/java/com/rsshub/feedrender/FeedKey.java#L14) `[自]theItemLimitAppliesHereToo` |
| TC-CHG-15 | P0 | `GET /api/changelog` | 匿名一次、登录后一次 | 匿名 401（`/api/**` 既有匹配器，本轮没改安全配置）；登录后按 `id DESC` 返回，顺序与出口**完全一致**（面板和 feed 是同一条列表）；`fromStatus` 为空时**键缺席**而不是 `null`（`non_null` 包含策略） | [ChangelogController.java:21,38](../backend/src/main/java/com/rsshub/adminapi/ChangelogController.java#L38) `[自]theAdminViewIsReadonlyAndFilterable` `[C]` "anonymous GET /api/changelog" |
| TC-CHG-16 | P1 | 面板的过滤与上限 | `?postId=` 存在的 / 不存在的、`?limit=1`、`?limit=0` | 分别 2 条 / 0 条 / 1 条 / 1 条（`limit` 夹到 1–500，越界**不报错**只夹，缺省 50） | [ChangelogController.java:24-25,41](../backend/src/main/java/com/rsshub/adminapi/ChangelogController.java#L24) `[自]` 同上 `[C]` "limit=1 keeps one row"/"limit=0 is clamped to one"/"an unknown post has no trail" |
| TC-CHG-17 | P0 | 只读契约 | `POST /api/changelog` | **405**。写变更行的唯一路径是 `/api/posts/**` 那几个写——不存在"绕过文章编历史"的端点，这是 FR-1.6 可信的前提 | [ChangelogController.java:21,38](../backend/src/main/java/com/rsshub/adminapi/ChangelogController.java#L38) `[自]` 同上 `[C]` "the changelog API is read-only" |
| TC-CHG-18 | P1 | 可发现性 | `GET /blog` 与 `GET /blog/<slug>` | 两个页面都挂三条 `<link rel="alternate">`（rss/atom/json），`title` 形如 `Changelog · <站点标题> (rss)`——公告没人发现就等于没做 | [PublicViewController.java:88,115](../backend/src/main/java/com/rsshub/editorial/PublicViewController.java#L88) `[自]publicPagesLinkToTheChangelog` `[C]` "the blog page advertises the outlet"/"the advertisement names the outlet" |
| TC-CHG-19 | P2 | [文章] 页"变更历史"表头 | 点某篇 / 点 [新文章] | 标题在"变更历史 · 本篇"与"变更历史 · 全站最新"之间切换（判据是 `historyPostId`，不是"有没有选中文章"） | [PostsView.vue:25,348](../web/src/views/PostsView.vue#L348) `[B]` |
| TC-CHG-20 | P1 | 面板在删除之后 | 删掉当前这篇 | 面板**不消失也不清空**，仍显示这篇的轨迹（含刚写入的 `Deleted:` 行）——`historyPostId` 与 `selected` 是分开存的，因为 `content_change` 有意没有外键 | [PostsView.vue:215](../web/src/views/PostsView.vue#L215) · [V5 DDL 文件头](../backend/src/main/resources/db/migration/h2/V5__changelog_schema.sql) `[B]`（后端行为已由 TC-CHG-06 覆盖） |
| TC-CHG-21 | P2 | 出口 pill | 点 rss/atom/json | 打开的是**配置的公开基址**（`http://localhost:8080/feed/changelog.*`），不是 `http://localhost:5173/…`——dev 期 vite 只代理 `/api` 与 `/actuator`，`/feed` 打到 5173 必定 404。origin 是从后端下发的文章 `url` 里取的，前端没有第二个基址常量（那个基址的唯一真源是 [application.yml:117](../backend/src/main/resources/application.yml#L117) 的 `rsshub.publish.base-url`） | [PostsView.vue:32](../web/src/views/PostsView.vue#L32) · [changelog.ts:49](../web/src/api/changelog.ts#L49) `[B]`（URL 本身由 TC-CHG-01～03 在真 HTTP 上证明可达，基址与请求 Host 无关由 22.4 实测） |
| TC-CHG-22 | P2 | 面板的失败态 | 让 `GET /api/changelog` 失败（改坏会话/停后端） | 面板自己一行红字错误，**不覆盖**保存/发布的结果文案；列表清空但编辑器内容不动；草稿期空态显示"只有公开过的变化才会留痕"而不是"没有历史" | [PostsView.vue:22,102-109,355-358](../web/src/views/PostsView.vue#L355) `[B]` |

> 控件清点（本轮唯一的新界面）：`PostsView` 编辑器表单下方一块面板 —— 1 个标题（本篇/全站最新切换）、0–3 个出口 pill（拿不到公开基址时整组不渲染）、1 行错误文案或 1 行空态、N 行历史（每行 = 动作 chip + 时间 + 链接或"已失效"文本 + `from → to`）。**没有新路由、没有新视图、没有新 store**。

### 22.2 本轮自动化与真 HTTP 覆盖（2026-11-01 实测）

- `[自]`：`changelog/ChangeLogApiTest` **10 个 `@Test`**，`@DisplayName` 用 `TC-CHG-01/02/03`、`04`、`05/06`、`07/08`、`09`、`10/11/12`、`13`、`14`、`15/16/17`、`18` 十组把这 18 条编号全部覆盖（上表逐条点名了方法名）。
- 全量：`./mvnw -o -B -ntp verify` = **`Tests run: 380, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS**（第五轮 370），测试类 **35 → 36**；`MigrationDialectAlignmentTest` 的 `MIGRATIONS` 已含 `V5__changelog_schema.sql`（h2 与 postgresql 两份逐列对齐）。
- ArchUnit：仍 **7 条全绿**。新增边只有 `changelog → {common, feedrender}`，反向 importers 是 `editorial/PostService`、`editorial/PublicViewController`、`adminapi/ChangelogController`，无环（实测口径见 [03 §2](./design/03-technical-design.md)）。
- 前端：`npm run build` 0 类型错误，**90 modules** / 179.91 kB JS / 26.38 kB CSS（第五轮末 89 / 178.07 / 25.08）。`[B]` 用例（TC-CHG-19～22）**未执行**。
- `[C]`：`backend/smoke.sh` 从 86 项扩到 **129 项**（F1.6 段见 [smoke.sh:197](../backend/smoke.sh#L197)，起至登出段之前共 **43 条**），实录见 22.3。附录 A 里给人手敲的新增第 11 段也逐条实跑过，见 22.4。

### 22.3 第六轮 `[C]` 执行实录（2026-11-01，M0–M5 + F1.6 全链路真 HTTP）

**环境**（与 20.4/21.3 同一口径）：一次性 `:18080` 实例 + `node .dev-logs/smoke-feed-server.js`（`:19999`），`DB_URL=jdbc:h2:file:D:/qorder/rss/.dev-logs/smoke-db` 全新空库、Flyway 从 V1 迁到 **V5**、口令为运行时自造的临时值（只经环境变量进进程，不落本文与脚本），`--rsshub.ssrf.allow-private-prefixes=127.` 仅放行 fixture 源；被测 jar 是同日 `verify` 380 项全绿后 repackage 的产物；日志 `.dev-logs/smoke-console.log`（129 条 `PASS`、0 条 `FAIL`、末行 `ALL PASS`）与 `.dev-logs/smoke-backend.log`。

**本轮唯一的"失败"是脚本错、不是产品错（但值得留档）**：F1.6 段第一次跑有 **5 条 FAIL**，全部集中在"标题该写 `New post:` 还是 `Published:`""删除是否一定留痕"两处。核对 `ChangeLogApiTest` 与 `recordIfPublic` 后确认实现是对的：

| 我的错误预期 | 实际契约 | 处置 |
|---|---|---|
| 发布一篇草稿 ⇒ `New post: X` | 草稿发布是一次**状态移动**（`from=draft, to=published`）⇒ `Published: X`；`created` 只属于"创建时就公开"的文章 | 改脚本：新增一篇 `status=published` 直接建出来的文章专门验 `created`（"a birth announces New post"） |
| 删除任何文章 ⇒ 留一行 `Deleted:` | 判据是"变更前**或**变更后任一侧公开"；删一篇从未公开的草稿**零行**，标题也从未进过出口 | 改脚本：加一条负例（"its removal is not announced" / "its title never reached the outlet"），把判据钉死在 `[C]` 面 |

这条改动的价值在于：`[C]` 面第一次**显式**表达了"可见性判据"，而不是靠读者去读 Java 才知道公告为什么沉默。产品侧代码一行没改。

**出口发现**：无新增缺陷。F1.6 的实现面（三格式、URN ID、只读端点、ETag 独立、撤销留痕）第一轮 `[自]` 全绿，`[C]` 唯一被改的是脚本预期；`PublicFeedController` 多一条字面量映射（`:57`）带来的"事件流不能叫 `changelog`"这条代价已被 TC-CHG-13 钉成断言。安全配置**一行没动**——`/feed/*` 与 `/api/**` 的既有匹配器恰好覆盖新出口与新端点，这一点由 TC-CHG-10（匿名可读、不建会话）与 TC-CHG-15/17（匿名 401、POST 405）在两侧同时证明。

### 22.4 附录 A 第 11 步的逐条实跑（2026-11-01，同一 `:18080` 一次性实例）

新增到附录 A 的 `# 11) 变更公告` 不是照着 smoke.sh 抄完就算——它把冒烟脚本的断言翻译成了人手敲的 curl，翻译过程本身会引入错误，所以逐条跑过一遍（全新库、Flyway `Successfully applied 5 migrations`、口令仍是运行时自造）：

| 附录里那一条 | 实测 |
|---|---|
| `api=%{http_code}` / `write=%{http_code}` | `api=401`、`write=405` |
| 草稿期 `grep -c 'Changelog probe'` | `0`：公告里查不到这篇（发布之后才出现，见下一行） |
| `PATCH /api/posts/$PID/status` 后 `grep -o 'Published: …'` | `Published: Changelog probe`——**没有** `New post:`，与 22.3 第一条同一个契约 |
| `grep -o '<guid isPermaLink="false">urn:rsshub:change:…'` | 命中一条 URN |
| `If-None-Match: $CTAG` | `304=304`（ETag 形如 `W/"eb02f65f9ee813b3"`，弱验证子照样能条件复拉） |
| 三格式 + `.opml` | `rss=200 atom=200 json=200`、`opml=404` |
| 匿名 cookie jar `grep -c RSSHUB_SESSION` | `0` |
| `curl $BASE/blog \| grep -o '/feed/changelog\.rss'` | 1 次命中 |
| `?limit=0` / `?postId=$PID` / `?postId=999999` | 三者分别是"夹到 1 条的那一条"（`action":"status"`）/ 同一行 / `[]` |

> 这一趟的库里**本来就只有一个变更行**，所以附录里的 `?limit=0` 只回到 1 条并不能单独证明"是夹取而不是碰巧"。夹取本身由两处钉死：`[自]theAdminViewIsReadonlyAndFilterable`（TC-CHG-16，多行库里 `limit=1`/`limit=0` 都得 1 条）与 `[C]` 的 "limit=0 is clamped to one"（[smoke.sh:249](../backend/smoke.sh#L249)，跑到那一行时全站已有 **4** 行）。附录这条只是给人手一个"不该报错、也不该返回全量"的下限检查。
| 登出后 `api/auth/logout` → `api/feeds` | `logout=204` → `after-logout=401` |

**这一趟顺手证实了 TC-CHG-21 的前提**：实例跑在 `:18080`，但 `GET /api/changelog` 下发的 `url` 仍是 `http://localhost:8080/blog/changelog-probe`——绝对地址来自 [application.yml:117](../backend/src/main/resources/application.yml#L117) 的 `rsshub.publish.base-url`（缺省 `http://localhost:8080`，可经 `RSSHUB_PUBLISH_BASE_URL` 覆盖），**不来自请求的 Host**。所以前端拼出口 pill 时必须用这个值，`window.location.origin`（dev 期是 `:5173`）一定错。产物：`.dev-logs/appendix-clg.sh`（逐条 eval 的跑法，口令仍只在运行时自造）、`.dev-logs/appendix-backend.log`（`Successfully applied 5 migrations`）、一次性库 `.dev-logs/appendix-db4.mv.db`。

**跑附录踩到的两处脚本级坑**（都不是产品问题，留档免得下次重犯）：① `PID` 必须从 `POST /api/posts` 的响应里 `sed` 出来，附录第 5 步硬编码的 `posts/1` 只是"那张库里的第一篇文章"，与本轮新建的探针文章不是同一篇；② 在 heredoc/`eval` 里再套一层引号会把 `grep -o '/feed/changelog\.rss'` 的 `\.` 变成 `\\\.`，于是**匹配不到**——看着像出口没被宣告，其实是转义。

---

## 23. 第七轮新增用例与执行结果（F2.4：收件箱过滤 / 键盘流 / 桌面通知，2026-11-01）

关闭的是第 12 节的 **BLK-01**——FR-2.4 里"文档写了、代码没有"的三件事（j/k/n/s/m、桌面通知、"只看关键词命中"的过滤）。用例**不另起编号段**，直接补进第 3 节的 3.5/3.6/3.7（TC-INB-40～63 共 20 条，其中 10 条 P0），因为 F2.4 **没有新视图、没有新路由、没有新 store**：它长在你已经在点的那三栏收件箱里。第三~六轮各开一节的前提是"新界面对象"（`RUL`/`NTF`/`EVT`/`TOT`、`DSH`/`SET`/`SYS`、`RPL`、`CHG`），本轮没有，硬拆一节反而会让"收件箱有哪些控件"这个清点散在两处。

本轮的特殊性有两条：**一半的验收面是 `[B]`**（键盘手感、权限弹窗、通知观感无法用 MockMvc 与 curl 证明），机器证据只能覆盖到"参数进对了、SQL 过滤对了、键表绑定了、产物类型检查过了"；**另一条是"过滤"与"红标"必须是同一事实**——否则用户会看到"筛得出、屏上无标"或"有标、筛不到"这两种最伤信任的偏差。所以 `alerted` 与 `alertedBy` 同源写成了 TC-INB-44 的 P0，并在 `[自]` 与 `[C]` 两侧各钉一遍。

### 23.1 交付面（代码与契约，逐处可点）

| 面 | 落点 | 关键契约 |
|---|---|---|
| 后端·过滤 | [EntryFinder.java:80-81](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L80) 的 `Filter` 加 `Boolean alerted, String category`；[:336-342](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L336) EXISTS/NOT EXISTS；[:343-354](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L343) 标签整元素 LIKE | `alerted=null` 不缩窄、`true`/`false` 互补；标签匹配的是 [EntryCodec.java:49](../backend/src/main/java/com/rsshub/feedstore/EntryCodec.java#L49) `categoryToken` 编码后的带引号 token，`"Dev"` 不会命中 `["Developer"]` |
| 后端·参数 | [EntryController.java:102-103](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L102) 两个 `@RequestParam`；[:114-121](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L114) 按字符串校验；[:129-130](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L129) 组装 `Filter` | 坏值走本项目的错误词汇表（400 `urn:rsshub:bad_alerted`），不是 Spring 的类型不匹配形状；空串等于不过滤 |
| 后端·索引 | `V6__alert_filter_index.sql`（[h2](../backend/src/main/resources/db/migration/h2/V6__alert_filter_index.sql) 与 [postgresql](../backend/src/main/resources/db/migration/postgresql/V6__alert_filter_index.sql) 各一条 `notif_entry_idx`） | 只加索引、不动表结构；`MigrationDialectAlignmentTest.MIGRATIONS` 已收录，双方言逐条对齐 |
| 红标同源 | [NotificationRepository.java:193](../backend/src/main/java/com/rsshub/rules/NotificationRepository.java#L193) `ruleNamesByEntries` → [RuleService.java:117](../backend/src/main/java/com/rsshub/rules/RuleService.java#L117) → `EntryView.alertedBy` | 与 EXISTS 子查询读同一张 `notification_log`，**跨包只有 SQL、没有 Java 依赖**，ArchUnit 七条与 no-cycles 不变 |
| 前端·键盘 | [useInboxKeys.ts](../web/src/composables/useInboxKeys.ts)（键表 :8-14、打字判断 :16-22、`preventDefault` :37、滚动 :38-40、卸载 :44-45） | 动作在 store 而非组件（要拿列表顺序）：[entries.ts:152-162](../web/src/stores/entries.ts#L152) `selectRelative`（服务端顺序、不绕回）、[:163-171](../web/src/stores/entries.ts#L163) `selectNextUnread`（跳过 baseline）、[:173-185](../web/src/stores/entries.ts#L173) `s`/`m` |
| 前端·通知 | [useUnreadAlerts.ts](../web/src/composables/useUnreadAlerts.ts)（`POLL_MS=60_000` :5、`ask` :30-43、增长判定 :45-54、挂载守卫 :59-61） | 只有未读数**增长**才弹；首次采样不弹（没有基准）；正文只有条数，不含来源写的标题 |
| 前端·控件 | [InboxView.vue:131](../web/src/views/InboxView.vue#L131) 只看命中、[:133-138](../web/src/views/InboxView.vue#L133) 标签清除片、[:113-120](../web/src/views/InboxView.vue#L113) 通知开关与键盘提示行、[ReaderPane.vue:54-62](../web/src/components/ReaderPane.vue#L54) 正文标签按钮 | `alertedOnly=false` 时参数**整个不发**（[entries.ts:51-63](../web/src/stores/entries.ts#L51)），避免"关着过滤"被后端读成"只要没命中的" |

### 23.2 本轮自动化与真 HTTP 覆盖（2026-11-01 实测）

- `[自]`：`adminapi/InboxApiTest` 从 30 → **32 个 `@Test`**（该类现有 32 个 `@Test` 注解）。两条新用例：
  - [InboxApiTest.java:339](../backend/src/test/java/com/rsshub/adminapi/InboxApiTest.java#L339) `alertedFilterIsTheSameFactAsTheRowBadge`——钉 TC-INB-44/45/46 的过滤面：命中行=带 `alertedBy` 的行、`true`+`false` 之和=不过滤、缺省不缩窄、`alerted=maybe` 得 400、**删掉规则后该行仍算命中**（FK 是 `ON DELETE SET NULL`，日志行留着）。
  - [InboxApiTest.java:371](../backend/src/test/java/com/rsshub/adminapi/InboxApiTest.java#L371) `categoryFilterMatchesWholeElements`——钉 TC-INB-41/43/46/47 的标签面：8 行语料含 `Developer`/`Dev`、`50%`/`50 percent`、`a_b`/`axb`、`a"b` 四组易混对；大小写敏感；`&limit=1` 的游标交接；徽标不跟过滤走；与 `q=` 组合。
- 全量：`./mvnw -o -B -ntp verify` = **`Tests run: 382, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS**（第六轮 380），测试类仍 **36**（本轮只加方法，没加类）。
- ArchUnit：仍 **7 条全绿**——`feedstore` 直接读 `notification_log` 表，**没有**新增 `com.rsshub.rules` 的 import，所以包图一条新边都没加。
- 前端：`npm run build` 0 类型错误（`vue-tsc --noEmit` 严格模式 + `noUnusedLocals`），**92 modules** / 183.41 kB JS / 26.90 kB CSS（第六轮末 90 / 179.91 / 26.38）。**这是 23.4 修复前的数字**：修掉硬发 `limit: 50` 之后同一轮收口为 92 / **183.39** / 26.90，仍 0 类型错误。`[B]` 用例（3.5/3.6/3.7 里带 `[B]` 的行，含 TC-INB-50～57 全部）**未执行**。
- `[C]`：`backend/smoke.sh` 从 129 项扩到 **144 项**（F2.4 段起于 [smoke.sh:289](../backend/smoke.sh#L289)，在登出段之前，共 **15 条**），实录见 23.3。**随后 23.4 的修复又加了 6 条尾段**（起于 [smoke.sh:328](../backend/smoke.sh#L328)），本轮收口在 **150 项**（[smoke.sh:346](../backend/smoke.sh#L346) 止）。附录 A 与人手敲的展开随之多了一段：**第 12 段**是 F2.4 的两个过滤，**第 13 段**是"每页条数由设置决定"，登出复用挪到**第 14 段**。

### 23.3 第七轮 `[C]` 执行实录（2026-11-01，M0–M5 + F1.6 + F2.4 全链路真 HTTP）

**环境**（与 20.4/21.3/22.3 同一口径，开发库一行没碰）：一次性 `:18080` 实例 + `node .dev-logs/smoke-feed-server.js`（`:19999`），启动器 `.dev-logs/smoke-run.sh`（口令 `SMOKE_PASS` 与 `RSSHUB_OPERATOR_PASSWORD` 都用 `openssl rand -hex 12` 现场自造、只经环境变量进进程），`DB_URL=jdbc:h2:file:D:/qorder/rss/.dev-logs/smoke-<tag>` 全新空库、Flyway 从 V1 迁到 **V6**（`Successfully applied 6 migrations`），`--rsshub.ssrf.allow-private-prefixes=127.` 仅放行 fixture 源；被测 jar 是同日 `verify` 382 项全绿后 repackage 的产物。**两个独立标签各跑一遍**（`f24b`、`f24c`），两次都是 144 条 `PASS`、0 条 `FAIL`、末行 `ALL PASS`。

**F2.4 段的做法**：一条 HTTP 调用同时造出"命中行"和"带标签行"——先 `PATCH /api/events/events/visibility` 把默认事件流翻进收件箱，再用 M3 的 Bearer 令牌 `POST /api/ingest/events`（`tags:["SmokeTag","ops"]`）。事件经 [EventInboxBridge.upsertEntry](../backend/src/main/java/com/rsshub/events/EventInboxBridge.java) 落成 `entry`，`tags` 原样进 `entry.categories`；规则"smoke mark"由前面 M3 段已经建好并命中。

**本轮唯一的"失败"是脚本错、不是产品错（但值得留档）**：第一次跑有 **2 条 FAIL**——"the list row hands back the tags the reader pane clicks" 与 "the filtered row carries the badge the filter promised"。两条的断言本身（`category=SmokeTag`→1、`alerted=true`→1）第一次就过了；失败在**取证写法**：

| 我的错误写法 | 为什么必然失败 | 处置 |
|---|---|---|
| `grep -q '"categories":["SmokeTag","ops"]'` | 方括号被正则读成**字符集合**（"这一个字符属于该集合"），整串永远匹配不上 | 新增 [smoke.sh:305](../backend/smoke.sh#L305) 的 `hasf()`（`grep -qF` 固定串），两处取证改用它 |

产品侧代码一行没改。**教训值得写进规范**：任何以 JSON 数组为针的断言都必须用固定串匹配，`smoke.sh` 里 `has`（正则）与 `hasf`（字面）从此分工明确。

**设计取舍留档**（为什么是最省的正确形状）：
- `alerted` 用 EXISTS 而不是给 `entry` 加布尔列：加列就要在规则删除、重跑、抑制三处维护一致性；读日志表则**过滤与红标天生同步**，代价是一次子查询，已用 `notif_entry_idx` 把它的形状钉成索引扫描。
- `category` 用编码 token 的 LIKE 而不是新建 `entry_category` 表：类别本来就是每行一个 JSON 数组 CLOB（[03 §3](./design/03-technical-design.md)），建新表等于把同一事实存两处。LIKE 的风险是通配符与转义，所以 needle 由**写这列的那个编码器**生成，再走 `escapeLike` + `ESCAPE '\'`——`"Dev"` 打不进 `["Developer"]`，`50%`、`a_b`、`a\"b` 都按字面命中（各有语料行）。
- 通知正文只给条数：桌面弹窗是本站唯一**不能清洗、不能排版、不能拦截点击**的表面，而条目标题是来源写的原文（FR-2.5 的清洗规则管不到 OS 通知区）。
- `alerted` 三态而 `alertedOnly` 是开关：把"关"表达成"不缩窄"，而不是"只查没命中的"——后者是另一条查询，不是同一条的反面。

> 遗留人工项：3.5/3.6/3.7 的 `[B]` 行走查（键盘、权限弹窗、通知观感、标签 chip 的可见性）、"真阅读器订阅"类门禁照旧。键盘流与桌面通知**没有** `[自]`/`[C]` 之外的机器证据链，第 16 节的声明覆盖它们。

### 23.4 写文档时捞出的一处旧欠账（FR-9.2：收件箱每页条数，2026-11-01）

**怎么发现的**：不是跑用例跑出来的，是**同步 [03 §11](./design/03-technical-design.md) 前端实况**时对代码回读出来的。那一节要写"收件箱一页多少条"，我去 `stores/entries.ts` 看请求形状，看到两处 `entriesApi.list({ ...filter(), limit: 50 })` ——前端把 50 写死在每一次请求里。而后端 `GET /api/entries` 的缺省 limit 才是设置项 `inbox.pageSize`（[EntryController.java:123-126](../backend/src/main/java/com/rsshub/adminapi/EntryController.java#L123)）。

**为什么这是缺陷而不是口径**：FR-9.2 的验收原话是"参数值与真实读取路径一致（收件箱/投递列表的缺省 limit 直接取自这里），**设置页不可能'改了但没生效'**"（[01:123](./design/01-requirements.md#L123)）。当时的状态是：`SettingsApiTest.steersTheInboxDefault` 在 MockMvc 上证明了"不带 limit 的请求确实跟随设置"，`GET /api/settings` 也把值正确地存了下来——**唯独真实读取它的那个客户端从来不带缺省**。所以 TC-SET-06 标着 `[自]`+`[B]`，`[自]` 那半边是真的绿，`[B]` 那半边（"改 10 后开收件箱应看到 10 条"）即使有人去点也必然失败。第 12 节把它记成"已自动化"是不准确的：自动化断言的是后端的契约，不是端到端的承诺。

**处置**：删掉两处显式 `limit`（[entries.ts:65-83](../web/src/stores/entries.ts#L65)、[:86-99](../web/src/stores/entries.ts#L86)），让缺省回到服务端；键的语义、范围（10–200）与 `MAX_LIMIT` 都没动，后端一行代码没改。**没有加新 `[自]` 用例**——`steersTheInboxDefault` 已经覆盖契约；本轮的增量证据是真 HTTP 的 6 条尾段（[smoke.sh:328](../backend/smoke.sh#L328) 起）：

| 断言 | 内容 |
|---|---|
| 1 | `PATCH {"inbox.pageSize":10}` 得 200（写通道活着，且 10 是合法下界——`1` 早在 M3.5 段被判 400） |
| 2 | **不带 `limit`** 的 `GET /api/entries?state=all` 恰好回 `min(全量, 10)` 条——期望值随数据量算，因为一次性库里可能不足 10 行，写死 10 会假失败 |
| 3 | 数据多于 10 行时必须交回 `nextCursor`；不多时必须不交回（同一条断言两种期望，前提消失就换成另一个期望，不静默通过） |
| 4 | 页面缩水时 `counters.total` **不动**——与 TC-INB-47 同一条口径的第二次钉死 |
| 5+6 | 改回 50 → 写成功，且行数回到改动前（双向跟随，不是单向夹紧） |

**复测**：`npm run build`（`vue-tsc --noEmit` 严格模式 + `noUnusedLocals`）0 错误，**92 modules / 183.39 kB JS / 26.90 kB CSS**（本轮唯一的功能改动就在这 183.39 kB 里）；`./mvnw -o -B -ntp verify` 因产品 Java 一行未改而**仍 382 项 0 失败 / 36 个测试类**（复跑一遍确认，不为改动造假数据）；`backend/smoke.sh` **150 项**，在 `f24e`、`f24f` 两个全新一次性库上各跑一遍，两次都是 150 `PASS` / 0 `FAIL` / `ALL PASS`。

**留档的教训（写进规范，不只在本文）**：文档同步不是"改完代码补文档"的最后一步，它是**这一轮里唯一一次逐行回读前端源码**的动作，所以它是真会捞出东西的。本轮捞出的这条既有 `[自]` 绿灯掩护、又有一条 P0 用例挂在 `[B]` 上永远没人点——正是第 16 节那条声明（"`[B]` 未执行"）最容易变成挡箭牌的地方。下一步的处置建议不变：浏览器走查要么人做，要么明确写"不做"，但不能被 `[自]` 的颜色冒充成"已验"。

**同步去向**：[01 FR-2.4/FR-9.2 与 v5 文档状态](./design/01-requirements.md)、[02 §3 的 `settings` 行](./design/02-architecture.md)、[03 §8.5 的 `inbox.pageSize` 行 + §11 分页实况](./design/03-technical-design.md)、[04 F2.4 小节的"偏差"第 1 条](./design/04-roadmap.md)；本文 TC-INB-34 由 P1 升 **P0**（它是那条需求承诺的唯一端到端断言）、TC-SET-06 的依据补上 `[C]`。

---

## 24. 第八轮新增用例与执行结果（F10 订阅编辑 / F11 大屏丰富化 / F12 界面令牌，2026-11-02）

三条需求都是操作员原话，落成的编号与需求面的对应：**F10** → [01 FR-2.1/FR-2.2](./design/01-requirements.md)、**F11** → [01 FR-8.1](./design/01-requirements.md)、**F12** → [01 NFR 表"界面"行](./design/01-requirements.md）。设计侧口径在 [03 §4.1/§4.2/§8.5/§10/§11](./design/03-technical-design.md)，架构侧边界在 [02 §3 与边界规则 4](./design/02-architecture.md)。

编号沿用各页所在小节的序列（F10 接第 5 节、F11 接第 20.1 节），F12 开新前缀 `UIF`——它不是某个页面的功能面，是横在所有页面上的同一套令牌。

### 24.1 `SUB` 新增：就地编辑、解析后直添、立即抓取的往返（接 5.1/5.3/5.4）

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-SUB-48 | P0 | [解析] 前的清空 | 解析 A → 把地址改成 B | A 的候选列表、选中项、上一条提示**全部消失**（`resetDiscovery` 四件套：discovery/chosen/message/failed），添加按钮不能拿着 A 的地址提交 | [FeedsView.vue:52-61](../web/src/views/FeedsView.vue#L52) · 改地址即触发 [:64](../web/src/views/FeedsView.vue#L64) 的 `watch` `[B]` |
| TC-SUB-49 | P0 | 解析失败的清空 | 解析一个非 feed 地址 | 失败态同样清空候选与选中项，`chosen` 归空 ⇒ 不会出现"上一次解析还能添加" | [FeedsView.vue:77-81](../web/src/views/FeedsView.vue#L77) `[B]` |
| TC-SUB-50 | P1 | 候选行的 [直接添加] | 不选 radio，直接点某条候选后面的按钮 | 订阅的是**这一条**候选，与 `chosen` 无关（`add(candidate.url)` 显式传参，`add()` 的第一优先参数就是它） | [FeedsView.vue:342](../web/src/views/FeedsView.vue#L342) · [:86-88](../web/src/views/FeedsView.vue#L86) `[B]` |
| TC-SUB-51 | P0 | 订阅标题就地编辑 | 改标题后失焦 | `PATCH /api/feeds/{id}` 带 `{title}` → 返回整行回写；消息"标题已保存"；**收件箱与健康度页同源同名**（读同一列，不是三处各存一份） | [FeedsView.vue:118-124](../web/src/views/FeedsView.vue#L118) · [SubscriptionService.java:275-291](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L275) `[自]InboxApiTest#aFeedCanBeRenamedButNeverRepointed` `[C]smoke.sh:380-387` |
| TC-SUB-52 | P0 | 标题交还给站点 | 清空标题后失焦 | 空串写 `NULL` 覆盖列，显示回落到站点自己的 `<title>`；消息仍是"标题已保存" | [FeedsView.vue:465-466](../web/src/views/FeedsView.vue#L465) · [api/feeds.ts:58-68](../web/src/api/feeds.ts#L58) `[C]smoke.sh:429-432`（改回后 `feedTitle` 变回 "Smoke source"） |
| TC-SUB-53 | P0 | 地址不可更改 | 悬停行内的地址链接 | `title` 提示明写"换地址请删除后重新订阅，条目与队列都挂在它上面"；页面上没有让地址变成输入框的入口 | [FeedsView.vue:467-470](../web/src/views/FeedsView.vue#L467) `[B]` |
| TC-SUB-54 | P0 | 绕过页面改地址 | `PATCH {"canonicalUrl":"…"}` 或 `{"requestedUrl":"…}` | **400 `urn:rsshub:unknown_field`**，且消息里点名被拒的键；地址一格未动。**故意不"忽略未知键"**：200 + 什么都没改，操作员会以为搬家成功了 | [SubscriptionService.java:52-53](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L52) · [:269-273](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L269) `[自]` 同上 `[C]smoke.sh:390-398` |
| TC-SUB-55 | P0 | 抓取间隔就地编辑 | 填 15 后失焦 | `PATCH {intervalMinutes:15}` → `fetchIntervalMinutes:15`；**下一轮抓取后仍是 15**（覆盖列与站点 `<ttl>` 分列存储，抓取只写后者） | [FeedsView.vue:126-134](../web/src/views/FeedsView.vue#L126) · [smoke.sh:411-418](../backend/smoke.sh#L411) `[自]InboxApiTest#intervalAndGroupEditsAreBoundedAndExplicit` `[C]` |
| TC-SUB-56 | P0 | 间隔交还给站点 | 清空数字框后失焦 | 空框在页面侧换算成 `0`，`0` 在 API 侧 = "停止覆盖" ⇒ 响应里**没有** `fetchIntervalMinutes` 键，下次到期回到站点声明的一小时 | [FeedsView.vue:126-132](../web/src/views/FeedsView.vue#L126) · [stores/feeds.ts:61-64](../web/src/stores/feeds.ts#L61) `[C]smoke.sh:420-427` `[B]` |
| TC-SUB-57 | P0 | 间隔越界 | 手工 `PATCH {intervalMinutes:-1}` / `43201` | 400 `urn:rsshub:bad_interval`；`43200`（30 天）本身合法可写。前端 `min` 只是提示，不是护栏 | [SubscriptionService.java:283-286](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L283) · [FeedsView.vue:491](../web/src/views/FeedsView.vue#L491) `[自]` 同上 `[C]smoke.sh:404-409` |
| TC-SUB-58 | P1 | 间隔输入的占位 | 观察空数字框 | 提示文案是"跟随站点（默认 **60** 分钟）"，`min` 是 **5**——两个数都来自 `GET /api/feeds/policy`，不是前端抄的常量；`/policy` 失败时提示仍显示、按钮仍可用（`policy` 为 `undefined` 才回落到字面量） | [FeedsView.vue:492](../web/src/views/FeedsView.vue#L492) · [stores/feeds.ts:45-52](../web/src/stores/feeds.ts#L45) · [FeedController.java:51-53,60-63](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L60) `[自]InboxApiTest#theFetchPolicyReflectsTheConfiguredInterval` `[C]smoke.sh:377-378` |
| TC-SUB-59 | P0 | 立即抓取的往返真相 | 点 [立即抓取] | 按钮变"抓取中…"且**只有这一行**变（`fetching` 存 id 不存布尔）；202 之后页面**轮询这一行**直到 `lastFetchedAt` 变了才报"已抓取，新增 N 条，下次按策略更新"——202 只代表队列答应了，不代表抓完了 | [FeedsView.vue:29-30,136-159](../web/src/views/FeedsView.vue#L136) · [stores/feeds.ts:71-94](../web/src/stores/feeds.ts#L77) `[自]FetchNowServiceTest#refreshingPushesTheWorkForwardAndRunsIt` `[C]smoke.sh:416-418` `[B]` |
| TC-SUB-60 | P1 | 立即抓取超时 | 15 秒内没落回（同一主机被别的订阅占住） | 失败态文案"已排队，但这一轮还没抓完…稍后刷新查看"，**不谎报成功**；表格不被整表 `load()` 闪一下（所以轮询单行而不是重载列表） | [FeedsView.vue:145-149](../web/src/views/FeedsView.vue#L145) · [stores/feeds.ts:71-76](../web/src/stores/feeds.ts#L71) `[B]` |

### 24.2 `DSH` 新增：五个区块与"数字必须能对上账"（接 20.1）

| 编号 | P | 区块 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-DSH-11 | P0 | KPI 带 | 观察 | 七张卡：未读条目（副标"库内共 N 条"）、24 小时到达、24 小时命中（副标"其中投递成功 N"）、投递失败、问题订阅、安静订阅、站内文章；后三张在 >0 时转红/琥珀，=0 时转绿 | [DashboardView.vue:60-73](../web/src/views/DashboardView.vue#L60) · [DashboardController.java:81-83](../backend/src/main/java/com/rsshub/adminapi/DashboardController.java#L81) `[自]DashboardApiTest#totalsAndHistogramAgree` `[B]` |
| TC-DSH-12 | P0 | 直方图 | 观察 | **恒为 24 根柱**（`WINDOW_HOURS`），最旧在前、两端相距 23 小时；两系列共用一个刻度上限（命中柱不会高过造成它的到达柱） | [DashboardController.java:40-43,120](../backend/src/main/java/com/rsshub/adminapi/DashboardController.java#L120) · [DashboardView.vue:75-86](../web/src/views/DashboardView.vue#L75) `[自]` 同上 `[C]smoke.sh:447-451` `[B]` |
| TC-DSH-13 | P0 | 柱与头条对账 | 把 `stats.arrivedToday` 与 24 根柱的 `arrivals` 求和比较 | 两者**必须相等**（`hits` 同理）——柱数恒为 24 就是这条断言成立的前提；不相等说明窗口锚定漂了 | [smoke.sh:452-455](../backend/smoke.sh#L452) `[自]DashboardApiTest#totalsAndHistogramAgree` `[C]` |
| TC-DSH-14 | P0 | 分组轨 | 给两个源填同组 | 每组图名一行，含"源数 / 未读 / 24h 到达"；操作员改过的标题进组名旁的卡（分组轨折叠的就是卡片集合） | [DashboardView.vue:208-223](../web/src/views/DashboardView.vue#L208) · [DashboardController.java:91](../backend/src/main/java/com/rsshub/adminapi/DashboardController.java#L91) `[自]DashboardApiTest#groupRailFoldsTheCards` `[C]smoke.sh:475` |
| TC-DSH-15 | P0 | 规则告警轨 | 命中一条规则 | 最多 10 行（`ALERT_ROWS`），每行 = 规则名（链到投递页）+ 状态 pill + 条目名 + `channel · 绝对时间`；**规则被删后仍显示"（规则已删除）"**而不是消失 | [DashboardView.vue:276-291](../web/src/views/DashboardView.vue#L276) · [NotificationRepository.java:286](../backend/src/main/java/com/rsshub/rules/NotificationRepository.java#L286) `[自]DashboardApiTest#alertRailSurvivesDeletions` `[C]smoke.sh:480-481` |
| TC-DSH-16 | P0 | 发布轨 | 发布一篇文章 | 最多 6 行（`POST_ROWS`），链到 `/blog/{slug}`；**草稿与已删的不在**，但已删的出现在轨迹轨——两轨一个是目录、一个是历史 | [DashboardView.vue:293-304](../web/src/views/DashboardView.vue#L293) · [PostRepository.java:164](../backend/src/main/java/com/rsshub/editorial/PostRepository.java#L164) `[自]DashboardApiTest#postsRailFollowsVisibility` `[C]smoke.sh:482-487` |
| TC-DSH-17 | P0 | 轨迹轨 | 编辑/改名/删除已发布文章 | 最多 6 行（`CHANGE_ROWS`），带动作标签，按 `changedAt` 倒序 | [DashboardView.vue:306-319](../web/src/views/DashboardView.vue#L306) · [DashboardController.java:113](../backend/src/main/java/com/rsshub/adminapi/DashboardController.java#L113) `[自]DashboardApiTest#changesRailNewestFirst` `[C]` |
| TC-DSH-18 | P0 | 卡片的故障与排队 | 观察一张卡 | 有失败时显示"连续失败 N 次"或 `lastError`（截断 + `title` 全文）；**没问题时响应里连 `lastError` 键都不出现**（`non_null` 包含策略），页面上也就没有空错误行；页脚"下次抓取 …"或"排队中" | [DashboardView.vue:246-268](../web/src/views/DashboardView.vue#L246) `[自]DashboardApiTest#cardCarriesFailuresAndQueue` `[C]smoke.sh:476-478` |
| TC-DSH-19 | P0 | 大屏与收件箱同源 | 对照 KPI 与收件箱徽标 | `stats.entries/unread` 等于 `GET /api/entries?state=all` 的 `counters.total/unread`——两处读同一条 SQL 口径，不存在"大屏自己数一遍" | [smoke.sh:458-462](../backend/smoke.sh#L458) `[自]DashboardApiTest#totalsAndHistogramAgree` `[C]` |
| TC-DSH-20 | P1 | 安静订阅的定义 | 观察推流型事件源 | 它 24 小时内没有抓取记录 ⇒ 计入"安静订阅"，卡片仍在墙上。**这是有意的**：推流源不是坏了，但大屏不该假装每个源都有抓取戳 | [DashboardController.java:81-83](../backend/src/main/java/com/rsshub/adminapi/DashboardController.java#L81) · [smoke.sh:471-473](../backend/smoke.sh#L471) `[自]` `[C]` |

### 24.3 `UIF` 界面令牌与外壳（F12 立，F13 迁到 Bootstrap 5.3 后逐行重写，F14 补三条"框架在哪里取色"的断言；除 TC-UIF-01/02/11/12/13 外全部 `[B]`）

| 编号 | P | 检查面 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-UIF-01 | P0 | 样式确实被引入 | 看首屏 | `bootstrap.min.css` + `bootstrap-icons` 字体表 + `theme.css` 在 `main.ts` 各引入一次，**Bootstrap 的 JS 一行没引**（折叠导航是 Vue 状态 + CSS 类，不依赖 Popper；**F14 盘点修正**：本项目当前**没有下拉，也没有页内 toast**——15 个 `.vue` 里 `grep -rn dropdown` 与 `grep -rn toast` 各自零命中，只有导航折叠是真的（`collapse`/`show` 三处位点），所以"下拉/折叠/toast 全是 Vue 状态实现"这句曾经的说法只有一半位点支撑）。F12 曾经不成立的是"文件写好没 import"，F13 后同一个失败模式换成"引了 Bootstrap 却漏了 theme.css"——那样界面照样能跑，只是品牌色静默回落到 Bootstrap 自己的蓝 | [main.ts:5-7](../web/src/main.ts#L5) · [theme.css:1-15](../web/src/styles/theme.css#L1) `[机]grep`（见 24.4） `[B]` |
| TC-UIF-02 | P0 | 组件里不许有硬编码色值 | `grep -rniE "#[0-9a-f]{3,8}\b" --include=*.vue web/src` | **零命中**（F13 实测 0 处，**F14 复测仍 0 处且本轮没有任何 `.vue` 改动**）；十六进制只允许出现在 `theme.css` 的令牌块与桥接块里。改了令牌就改了全站，这是 F12 立、F13 续约的唯一可机器验收承诺——**但 F14 给这句加了限定**：Bootstrap 有五条规则把灰阶写成字面量（`.btn-outline-secondary`/`.btn-secondary`/`.btn-outline-dark`/disabled `.btn-link`/`.form-floating`），令牌碰不到，必须按组件逐键覆盖，所以契约是"一个文件里的两层：令牌 + 组件级桥接块" | [theme.css:18-41](../web/src/styles/theme.css#L18) · 桥接块 [:102-116](../web/src/styles/theme.css#L102) · F14 块 [:356-367](../web/src/styles/theme.css#L356)、按钮覆盖 [:369-417](../web/src/styles/theme.css#L369) `[机]` |
| TC-UIF-03 | P0 | 顶栏 | 登录后观察 | `.navbar.navbar-expand-xxl.app-navbar.sticky-top`；`--mica` 半透（F14 起是 `rgba(233,241,249,.72)`，与淡蓝画布同色相，模糊才读得出是"画布透上来"而不是灰） + `backdrop-filter: blur(16px) saturate(1.6)`；当前页签是白底 + 卡片阴影 + **底部 16×3px 强调色下划线**（`.router-link-active::after`）；窄屏由 `navbar-expand-xxl` 折成汉堡按钮，不再是横向滚动条 | [App.vue:46-62](../web/src/App.vue#L46) · 折叠按钮 [App.vue:52-55](../web/src/App.vue#L52) · [theme.css:242-248](../web/src/styles/theme.css#L242) · 页签态 [:249-280](../web/src/styles/theme.css#L252) · `--mica` [:22](../web/src/styles/theme.css#L22) `[B]`（模糊观感未经人眼确认） |
| TC-UIF-04 | P0 | 主按钮 | 观察登录页与两处"保存" | 强调色统一为 `--accent`（蓝），**不再有一处绿一处蓝**：底色/描边/hover/active/disabled 全写成 Bootstrap 自己的 `--bs-btn-*`，所以 F12 那个"通用 `button:hover:not(:disabled)` 权重 (0,2,1) 压过主按钮基态"的问题在结构上不存在了 | [theme.css:102-116](../web/src/styles/theme.css#L102) · 调用点 [LoginView.vue:75](../web/src/views/LoginView.vue#L75) · [RuleEditor.vue:310](../web/src/components/RuleEditor.vue#L310) · [PostsView.vue:324](../web/src/views/PostsView.vue#L324) `[B]` |
| TC-UIF-05 | P0 | 输入框 | 聚焦/悬停/对比底色 | `.form-control` / `.form-select` 由 Bootstrap 给结构，**底色来自 `--bs-body-bg`**——F14 把这条写死成断言：`--bs-body-bg` 必须是"表面"（白），页面底色由 `body` 自己画 `--canvas`，否则卡片、输入框与页面三者同色（F13 就是这个状态，实测 `rgb(233,241,249)` 三处相同）；focus 圈换成强调色描边 + 25% 光晕；控件密度整体靠 `html { font-size: 14px }`（Bootstrap 全 rem，所以改根字号就等于改所有控件尺寸） | [theme.css:183-187](../web/src/styles/theme.css#L183) · `--bs-body-bg` [:51](../web/src/styles/theme.css#L51) · `body` [:80](../web/src/styles/theme.css#L80) · 根字号 [:74-76](../web/src/styles/theme.css#L74) · 登录页两个字段 [LoginView.vue:53-59](../web/src/views/LoginView.vue#L53) `[机]探针`（见 24.7）+ `[B]` |
| TC-UIF-06 | P0 | 每个可点控件有 hover | 逐页悬停：解析/添加/立即抓取/全部已读/删除/候选行的直接添加/收件箱筛选 chip/加载更多/大屏立即刷新/阅读面板动作/聚合页出口 pill 与操作链接 | 悬停底色由 Bootstrap 提供（`.btn:hover`、`.list-group-item-action:hover`、`.nav-link:hover`），自己只剩两处：**顶栏页签**（120ms `--ease`）与**收件箱条目行**；**文本型链接**（"显示密钥"这类）是 `.btn-link`，悬停出下划线而不是加底色 | [theme.css:261-264](../web/src/styles/theme.css#L261) · [InboxView.vue:208-210](../web/src/views/InboxView.vue#L208) · 控件面 [FeedsView.vue:319](../web/src/views/FeedsView.vue#L319) · [DashboardView.vue:149](../web/src/views/DashboardView.vue#L149) · [ReaderPane.vue:66-83](../web/src/components/ReaderPane.vue#L66) · [RepackView.vue:254-260](../web/src/views/RepackView.vue#L254) `[B]` |
| TC-UIF-07 | P0 | 键盘焦点可见 | Tab 走一遍登录页与订阅页 | 焦点环是 2px 强调色 outline + 1px 外偏移，`:where(...)` 写法所以不抢组件自己的选择器权重；表单字段另外保留 Bootstrap 的 focus 光晕 | [theme.css:191-194](../web/src/styles/theme.css#L191) `[B]` |
| TC-UIF-08 | P0 | 状态标签按结果上色 | 大屏投递轨观察 `sent/failed/pending/suppressed/skipped_quiet` 五种 | 轨上的 pill 由 `ALERT_TONES` 映射到 Bootstrap 的 `text-bg-*`，未知值落 `secondary` 而不是继承一个会读成"成功"的颜色；**卡片上的 `paused`/`muted` 保持中性**（`text-bg-secondary` + 整卡 `opacity-75`）——暂停是操作员的选择，不是故障。**F14 起 `secondary` 不是 Bootstrap 的灰**：`.text-bg-secondary` 读 `--bs-secondary-rgb`，本项目把它指成 slate `rgb(90,113,136)`（白字 5.06:1），所以"安静"这一档在淡蓝族里仍是安静的那一档 | [DashboardView.vue:116-122](../web/src/views/DashboardView.vue#L116) · `alertTone` [:124-126](../web/src/views/DashboardView.vue#L124) · 轨上 pill [:285](../web/src/views/DashboardView.vue#L285) · 卡片侧 [:232](../web/src/views/DashboardView.vue#L232)、[:238](../web/src/views/DashboardView.vue#L238) · `--bs-secondary-rgb` [theme.css:360](../web/src/styles/theme.css#L360) `[B]` |
| TC-UIF-09 | P1 | 卡片/面板阴影 | 对照登录卡、大屏面板、订阅页候选框 | 统一 `--shadow-card`（8px 圆角 + 1px 3px，**F14 起是 `rgb(15 40 70 / 12%)` 而不是纯黑**，蓝画布上的投影才不脏）走 `.card`；`--shadow-flyout` 经 `--bs-box-shadow` 给 Bootstrap 自己的弹层类；**`.dropdown-menu`/`.flyout` 这两条规则当前没有任何位点使用**（15 个 `.vue` 里 `grep -rn dropdown` 与 `grep -rn flyout` 各自零命中，F14 实测）——留着不亏，但别把它当成"本项目有下拉"的证据；`theme-color` 与画布同色（**F14 实测时它还是 F13 的 `#f3f3f3`，画布转蓝后成了唯一没跟上的色位，已改 `#e9f1f9`**） | [theme.css:203-209](../web/src/styles/theme.css#L203) · 弹层 [:228-233](../web/src/styles/theme.css#L231) · 阴影令牌 [:37-38](../web/src/styles/theme.css#L37) · [index.html:6](../web/index.html#L6) `[B]` |
| TC-UIF-10 | P1 | 数字与字体 | 检查令牌值、原始 XML 示例、规则表达式、文章正文编辑器 | 等宽字体是令牌 `--font-mono`，并把它接到 Bootstrap 的 `--bs-font-monospace`——这样 `.font-monospace` 工具类和 `code`/`pre` 用的是同一族字；界面字体 `--font-ui`（含"Microsoft YaHei UI"，中文不回落成宋体）经 `--bs-body-font-family` 生效；原始 XML 块 `.sample` 用 `--bs-secondary-bg` 底 + 横向滚动 | [theme.css:40-47](../web/src/styles/theme.css#L40) · `code/pre` [:84-90](../web/src/styles/theme.css#L84) · [`.sample` EventsView.vue:316-325](../web/src/views/EventsView.vue#L316) · [PostsView.vue:277](../web/src/views/PostsView.vue#L277)、[:286](../web/src/views/PostsView.vue#L286)、[:293](../web/src/views/PostsView.vue#L293)、[:319](../web/src/views/PostsView.vue#L319) `[B]` |
| TC-UIF-11 | P0 | 表面与画布必须分色（F14 立） | 注入探针读 `getComputedStyle`：`body`、`.card`、`.form-control` 三处的 `backgroundColor` | 画布是 `rgb(233,241,249)`，卡片与输入框是 `rgb(255,255,255)`；**三者相等即为缺陷**——Bootstrap 5.3.8 的 `.form-control` 没有组件级背景变量，底色直读 `--bs-body-bg`，`.card` 读 `--bs-card-bg`（默认 `var(--bs-body-bg)`），所以 F13 把画布挂上 `--bs-body-bg` 的那一天，"浅色画布上叠白色表面"就只是文档里的意图。断言必须打在**解析结果**上而不是令牌值上，因为令牌各就各位却仍然同色 | [theme.css:51](../web/src/styles/theme.css#L51) · `body` [:78-82](../web/src/styles/theme.css#L78) · 实测记录见 24.7 `[机]` |
| TC-UIF-12 | P0 | 工具类不许落在 Bootstrap 的默认灰阶（F14 立） | 在 `/login` 的 DOM 里临时注入 13 个代表类（`.btn-secondary`、`.btn-outline-secondary`、`.btn-outline-dark`、disabled `.btn-link`、`badge.text-bg-{secondary,light,dark,primary}`、`.bg-body`、`.bg-body-tertiary`、`.text-body`、`.text-body-secondary`、`.text-body-tertiary`），逐个读 `color`/`backgroundColor`/`borderTopColor`，读完删节点 | 无一处等于 `#6c757d`/`#212529`/`#dee2e6`/`#f8f9fa`/`#0d6efd`。实测：slate `rgb(90,113,136)`、surface-alt `rgb(243,248,253)`、emphasis `rgb(11,26,43)`、墨色三档 `rgb(22,33,46)`/`rgb(74,94,117)`/`rgb(84,106,133)`、描边 `rgb(182,203,224)`。**两类失败模式**：工具类读 `*-rgb` 双生子（`.bg-body-tertiary` 用 `--bs-tertiary-bg-rgb`，设 `--bs-tertiary-bg` 无效），以及字面量灰（`.btn-outline-secondary` 的 18 个位点）——找法分别是 `grep -o '\.bg-body-tertiary{[^}]*}' web/node_modules/bootstrap/dist/css/bootstrap.min.css` 与 `.dev-logs/f14-grayhunt.js` | [theme.css:356-367](../web/src/styles/theme.css#L356) · 按钮覆盖 [:369-417](../web/src/styles/theme.css#L369) · 位点清点 `.dev-logs/f14-classaudit.js`（35 个带色类名） `[机]` |
| TC-UIF-13 | P1 | 中性族过 AA（F14 立） | 用 WCAG 相对亮度公式算比值（`.dev-logs/f14-contrast2.js`，本机 `node` 直跑） | 正文 `--ink #16212e` 对白 16.27 / 对画布 14.26；次级 `--ink-2 #4a5e75` 6.67 / 5.85；三级 `--ink-3 #546a85` 5.56 / 4.88 / 悬停底 4.56；白字对强调色 `#0067c0` 5.67、对 slate 5.06；`--accent-ink` 对 `--accent-wash` 10.89。**F13 的 `--ink-3 #8a8a8a` 只有 3.45 / 3.11，低于正文 AA 的 4.5**——F12 按"最淡的一档"取值时没算过对比度 | [theme.css:18-41](../web/src/styles/theme.css#L18) · 比值来源 24.7 `[机]` |

### 24.4 第八轮执行实录（2026-11-02）

**门禁四条**：
- `MAVEN_OPTS="-Duser.language=en -Duser.country=US" ./mvnw -o -B -ntp verify` → **397 项 / 0 失败 / BUILD SUCCESS**，ArchUnit 7 条全绿，测试类 36→**38**。（`backend/` 目录下执行；日志 `.dev-logs/f12-verify.log`，同日重跑复现。）
- `cd web && npm run build`（`vue-tsc --noEmit` 严格模式 + `noUnusedLocals`）→ **0 类型错误 / 93 modules**，产物 190.20 kB JS + 42.40 kB CSS（gzip 6.38）。
- `backend/smoke.sh` → **193 项 PASS / 0 FAIL / ALL PASS**（首轮 `.dev-logs/f11-smoke.log`；同日在**全新的一次性 `:18080` 实例**上重跑复现，`.dev-logs/f12-smoke.log`：193 条 `PASS`、0 条 `FAIL`、末行 `ALL PASS`，前提是先把 `.dev-logs/smoke-feed-server.js` 重启到 revision=1，否则 `entryCount` 的 1/2/3/4 递增断言对不上），比上一轮 150 项多 43 项：F10 段 21 项（[smoke.sh:356-435](../backend/smoke.sh#L356)）+ F11 段 22 项（[:436-487](../backend/smoke.sh#L436)）。
- `web/verify-dev-proxy.sh` → 本轮未重跑（前端代理链路无改动，F10/F11/F12 都不动 `/api` 路径规则）。

**新增测试的分项账（382→397）**：`FetchNowServiceTest` +2（新类，`addingASubscriptionFetchesItWithoutWaitingForARound`/`refreshingPushesTheWorkForwardAndRunsIt`）、`FetchWakeupTest` +2（新类，`aWakeFetchesWithoutWaitingForTheNextRound`/`nothingIsFetchedThatWasNotDue`）、`InboxApiTest` 32→34、`DashboardApiTest` 5→**11**、`MigrationDialectAlignmentTest` 4→5、`FeedFetchServiceTest` 22→23、`EntryIngestServiceTest` 10→11。38 个 surefire XML 的行数加起来正好 397，所以本轮没有"旧 XML 冒充新结果"。

**本轮改出来的缺陷（都是代码缺陷，不是脚本陈旧）**：

| 发现 | 原因 | 处置 |
|---|---|---|
| 大屏所有状态 pill 一律琥珀色，包括"已发送" | F11 重写样式时留下**两条同名的空 `.state {` 规则**，后面的 `border-color`/`color` 无处可写，视觉上等于没写 | 按投递结果拆成 `sent/failed/pending/suppressed/skipped_quiet` 五条；F13 后这五条不再是自写 CSS，而是 `ALERT_TONES` 到 Bootstrap `text-bg-*` 的映射（[DashboardView.vue:116-122](../web/src/views/DashboardView.vue#L116)），卡片侧的 `state` 保持中性 |
| 令牌文件写了但界面没变 | F12 的令牌文件（当时叫 `fluent.css`）没有被任何入口引入 | 那次补的是引入一行；F13 迁移后该文件退役，入口只剩 [main.ts:5-7](../web/src/main.ts#L5) 三行（Bootstrap 的 CSS、图标字体表、`theme.css`），新增 TC-UIF-01 盯的就是这三行 |
| 主按钮一 hover 就变白，绿/蓝两处不一致 | 通用 `button:hover:not(:disabled)` 的选择器权重 (0,2,1) **压过** `.primary`/`button[type="submit"]` 的基态 (0,1,1)/(0,1,2) | 主按钮自己写 `:hover:not(:disabled)` 的强调色规则；顺带把两处主按钮统一到 `--accent`（TC-UIF-04） |
| 订阅页/大屏/收件箱/阅读/聚合/事件页的按钮悬停没反应 | F12 首轮只给部分页补了 hover；且首次批量插入把选择器写成 `.foot button :hover`（多一个空格 = 后代选择器，规则永不命中） | 那一轮逐页补齐后 `grep ' :hover' ` 归零复查；**F13 将这一族手写 hover 全部删除**，悬停交给 Bootstrap 的 `.btn:hover` / `.list-group-item-action:hover` / `.nav-link:hover`，全站自写的只剩两处——顶栏页签 [theme.css:261-264](../web/src/styles/theme.css#L261) 与收件箱条目行 [InboxView.vue:208-210](../web/src/views/InboxView.vue#L208)（本轮实测 `grep -rn ":hover" --include=*.vue web/src` = 1 命中） |
| `./mvnw verify` 打不出包，报 `Unable to rename …jar to …jar.original` | 上一轮遗留的一次性冒烟实例还活着占住 jar；`bash start-dev.sh stop` 只遍历 8080/5173，管不到 `:18080`（[start-dev.sh:22-31](../start-dev.sh#L22)） | 手工 `netstat` 找 PID + `taskkill` 后重跑；登记 [03 §14 O25](./design/03-technical-design.md) |

**`[B]` 未复核声明（本轮的诚实部分）**：F12 的观感只在 `/login` 一个匿名页面上用 `getComputedStyle` 做过结构回读（body `rgb(243,243,243)`、卡片 8px + 阴影、输入框 32px 带 2px 下边框、按钮 `rgb(0,103,192)`，控制台无报错）；桌面端截图工具本次返回 `NATIVE_BROWSER_VIEWPORT_UNAVAILABLE`，**登录后界面的任何视觉效果都没有被人眼看过**，mica 模糊、`backdrop-filter` 滚动帧率、页签选中态与新补的 hover 观感都属未验。TC-SUB-48～53/56/59/60 与 TC-UIF-03～10 的 `[B]` 行同此。口径与 [03 §14 O24](./design/03-technical-design.md) 一致，第 16 节的总声明覆盖它们。

**同步去向**：[01 的 FR-2.1/FR-2.2/FR-8.1/NFR 界面行 + DoD 三行](./design/01-requirements.md)、[02 §3 的 `feedfetch`/`admin-api` 行与边界规则 4](./design/02-architecture.md)、[03 §4.1 参数表校正 / §4.2 立即刷新 / §8.5 F11 小节 / §10 端点面 / §11 前端实况 / §13 计数 / §14 O24+O25](./design/03-technical-design.md)、[04 的 F10/F11/F12 小节与出口数字](./design/04-roadmap.md)。

### 24.5 引用行号勘误（第八轮收尾，2026-11-02）

写完 F10/F11/F12 后把四份文档里的 `文件:行号` 全部抽出来逐个回读源码核对（抽取口径：`grep -rhoE "(EntryFinder|SubscriptionService|FeedRepository|DashboardController|TaskQueue|EntryController|FeedDiscoverer)\.java:[0-9]+(-[0-9]+)?" doc` 与同法扫 `.vue:` / `smoke.sh:` / `application.yml:`），**约 150 处引用**。下表 23 行是本轮改掉的全部失效引用（同一行里多个引用合计），改前都重新读过被指那一行，没有一处是"按估计改"。

**根因是三次插行，不是写错**：① F10 在 `adminapi/SubscriptionService.java` 里加了 `update`/`text`/`wholeNumber`/`refresh`/`armNow` 五个方法；② F11 在 `feedstore/EntryFinder.java` 的 `where()` **之上**插入 `arrivedByGroupSince`（`:256-270`）与 `arrivalsByHour`（`:281-297`），把 `where()` 推到今天的 `:299`——**旧引用 `:246-250` 现读作 `:303-305`，整体下移 57 行**，它下面每一个条件分支的引用同时集体失效；③ F10/F11 又给 `feedstore/FeedRepository.java` 加 `FeedCard`+`listCards`、给 `adminapi/DashboardController.java` 加三类记录，`application.yml` 的 notify/storage 段与 `backend/smoke.sh` 的 F10/F11 两段同理工整下移。

| 原说法（文档里曾经写的） | 正确说法 | 出处（=本轮读源码确认的位置） |
|---|---|---|
| 重复订阅 409 在 `SubscriptionService.java:118-120` / `:84` | `:100-101`（`already_subscribed`） | [SubscriptionService.java:135-136](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L135) |
| 订阅前 SSRF 校验在 `:76` | `:92-94`（`guard.validate(requested)` 在发现之前） | [:128](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L128) |
| 导入拒绝不可信地址在 `:153-160` | `:241-246`（`importOpml` 里逐条 `guard.validate`，失败只 `skipped++`） | [:391](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L391) |
| 非 http(s) 拒绝在 `:196-199` | `:281-284`（`scheme_not_allowed`） | [:430-433](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L430) |
| `normalise` 契约在 `:186-190` | `:271-274`（javadoc）+ 方法体 `:276-290` | [:425](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L425) |
| 收件箱 `q` 的 LIKE 在 `EntryFinder.java:268-276` / `:268-278` | `:324-335` | [EntryFinder.java:324-335](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L324) |
| `escapeLike` 在 `:318`、`ESCAPE '\'` 在 `:274` | `:374-376`、`:330-332` | [:374-376](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L374) |
| "只看命中"的 EXISTS 在 `:280-286` | `:336-342` | [:336-342](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L336)（也修了 [02 §3 `feedstore` 行](./design/02-architecture.md)） |
| 标签整元素 LIKE 在 `:287-298` | `:343-354` | [:343-354](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L343) |
| `state=unread` 排除 baseline 在 `:187` / `:246-250` | `:303-305`（`where()` 的 switch） | [:303-305](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L303) |
| keyset 游标条件在 `:303-306` | `:359-366` | [:359-366](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L359) |
| `counters()` 在 `:169-176` | `:171-185` | [:171](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L171) |
| 大屏每源最新 N 条在 `:196-219`、角标在 `:221-229` | `:203-225`、`:228-236` | [:203](../backend/src/main/java/com/rsshub/feedstore/EntryFinder.java#L203) |
| 卡片头轻查询在 `FeedRepository.java:77-89` | `:97-120`（`listCards`） | [FeedRepository.java:97](../backend/src/main/java/com/rsshub/feedstore/FeedRepository.java#L97) |
| 插入须点名身份列在 `FeedRepository.java:92` | `:143-150`（`.update(key, "id")` 与上面的注释） | [:148-150](../backend/src/main/java/com/rsshub/feedstore/FeedRepository.java#L148) |
| 刷新只提前不叠加在 `TaskQueue.java:187` | `:193-200`（`armNow` 的 javadoc + 声明） | [TaskQueue.java:200](../backend/src/main/java/com/rsshub/feedfetch/TaskQueue.java#L200) |
| 订阅接口面在 `FeedController.java:51-110` | `:55-128`（`/policy`、`/discover`、`PATCH /{id}`、`/{id}/refresh` 都在这段里） | [FeedController.java:55-128](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L55) |
| `DashboardController` 的端点与记录在 `:60-61`（03 §14 O19） | `:69-114`（九个响应记录，含 `CardEntryView:103`）+ `:116-139`（`@GetMapping` 与装配） | [DashboardController.java:116](../backend/src/main/java/com/rsshub/adminapi/DashboardController.java#L116) |
| 顶栏 [退出] 在 `App.vue#L39`（TC-AUTH-10） | 第八轮写作时是 `:42`（那时 `signOut` 在 `:17-20`）；**F13 重写外壳后是 [App.vue:68](../web/src/App.vue#L68)**，`signOut` 在 `:24-27` | 同一处控件，两次改版都读过源码；本轮位置见下文 24.6 |
| `MIGRATIONS` 在 `MigrationDialectAlignmentTest.java:35-36` / `:35-37` | `:37-39`，清单现为 V1–V7 | [MigrationDialectAlignmentTest.java:37-39](../backend/src/test/java/com/rsshub/MigrationDialectAlignmentTest.java#L37) |
| 发布基址在 `application.yml:98-100` | `:115-117`（`publish.base-url`，注释在上一行） | [application.yml:117](../backend/src/main/resources/application.yml#L117) |
| `hasf()` 固定串版在 `smoke.sh:305` | `:313` | [smoke.sh:313](../backend/smoke.sh#L313) |
| 焦点环在 `fluent.css` 旧行号（TC-UIF-07） | 该行号随文件一起作废：F13 起焦点环在品牌层 [theme.css:191-194](../web/src/styles/theme.css#L191) | `:where(...)` 写法原样搬过来了，只是换了文件 |

**没变的**：`EntryController` 的每一处引用（本轮没动那个文件，`:39/:41/:48/:98-106/:102-103/:112-122/:133-142/:145-148/:150-153/:163/:171-174/:177-189/:216-225/:239-243/:245-266` 按声明行核对，全部落在 `STATES`/`MAX_LIMIT`/`ALERTED_FLAGS`/`list()`/`counters()`/`detail()`/`state()`/`instant()`/`Cursor` 各自的方法体内）、`FeedDiscoverer.java:45`（`COMMON_PATHS`）与 `:49`（`PRIORITY`）、`RawResponseStore.java:62`、`SettingsController.java:25-33`、`SystemStatusController.java:86-87`。**这份"没变"只覆盖后端**：同一批 `.vue` 引用在次日整批重写（F13），`App.vue` 里只有 `:12`（`showChrome`）与 `:15`（`flush`）至今仍对，`:17-20/:25/:26/:29/:38` 五处已各就新位（折叠状态 `:19-22`、`signOut` `:24-27`、页签数组 `:29-41`）——全部逐条重扫，见 24.6。

**口径固化**：文档里的行号是**易腐引用**。写之前先 grep 定位、写完立刻回读被指行；一旦某次改动在某个方法**上方**插了行，同一个文件下游每一条 `#L` 都要重扫——本表里 `EntryFinder` 那一族 8 行全部来自 ② 那一次插行。

**本轮这一节的改动只在文档**：代码一行没动，所以 24.4 的四条门禁不受影响，无需重跑。

### 24.6 第九轮执行实录与勘误（F13：控制台迁到 Bootstrap 5.3，2026-11-02）

**这一轮测的是"换了骨架，一切还在"**。改动面只有 `web/src/**` 与文档，后端一行 Java 没动——但门禁仍然重跑，不拿上一轮的数字充当本轮结论。

**门禁（本轮实测）**

| 门禁 | 命令 | 结果 |
|---|---|---|
| 后端 | `cd backend && ./mvnw -o -B -ntp verify` | **397 项 / 0 失败 / 0 错误 / 0 跳过，38 个测试类，BUILD SUCCESS**（57.0 s，日志 `.dev-logs/f13-verify-final.log`）；ArchUnit 7 条含在其中全绿 |
| 真 HTTP 冒烟 | `backend/smoke.sh`（一次性 `::18080` 实例 + 一次性 H2 库，口令只走环境变量） | **193 项 / 0 FAIL / ALL PASS**，日志 `.dev-logs/f13-smoke.log` |
| 类型与构建 | `cd web && npm run build`（脚本内先跑 `vue-tsc --noEmit`） | **0 类型错误**；94 modules；JS **221.11 kB**（gzip 73.70）；CSS **321.03 kB**（gzip 47.71）；产物里第一次有二进制字体：`bootstrap-icons` 的 woff2 134.01 kB + woff 180.28 kB |
| 三条可机器判的界面承诺 | `grep` | `.vue` 里硬编码色值 **0 处**；`.vue` 里 `:hover` **1 处**；Bootstrap 的 JS **0 处**（`--include=*.vue --include=*.ts` 三条 `grep` 皆零命中） |

**本轮测出来并修掉的四个真缺陷**

| 症状 | 根因 | 处置 | 复测 |
|---|---|---|---|
| 按钮与输入框圆角 8px，与 [01 的 NFR「界面」行](./design/01-requirements.md)"控件 4px / 卡片 8px"不符 | Bootstrap 用**同一个** `--bs-border-radius` 同时驱动控件与 `.card`（`.card{--bs-card-border-radius:var(--bs-border-radius)}`），首版把它指向了 `--radius-card` | 全局旋钮回控件值 [theme.css:66](../web/src/styles/theme.css#L66)，卡片自己声明两个内圆角 [theme.css:203-209](../web/src/styles/theme.css#L203) | `getComputedStyle` 实测：卡片 `8px`、输入框与按钮 `4px` |
| 大屏非活动源的 `paused` 徽标变琥珀，把 F12 定下的"暂停是选择不是故障"写丢了 | 迁移时随手选了 `text-bg-warning` | 改中性 [DashboardView.vue:238](../web/src/views/DashboardView.vue#L238) + 整卡 `opacity-75`（[:232](../web/src/views/DashboardView.vue#L232)） | 类名可 grep，观感属 `[B]` |
| 等宽字体两族并存（`.font-monospace` 与 `code/pre` 不同族） | 项目只设了 `--font-mono`，Bootstrap 的工具类读自己的 `--bs-font-monospace` | [theme.css:47](../web/src/styles/theme.css#L47) 把后者指向 `--font-mono` | 实测 `--bs-font-monospace` 回读到 `"Cascadia Mono", "Consolas", …` |
| 一处装饰图标缺 `aria-hidden`，读屏会念出字形 | 逐页迁移时漏掉 [InboxView.vue:118](../web/src/views/InboxView.vue#L118) | 补 `aria-hidden="true"` 后重跑构建 | 41 个唯一 `bi-*` 名 / 50 个图标位点，`grep` 复查"缺 aria-hidden"**0 处**；JS 由 221.09 → **221.11 kB** |

**`/login` 的实测值**（本轮重跑构建后 `getComputedStyle` 回读，匿名页，不涉登录流）：根字号 `14px`（[theme.css:74-76](../web/src/styles/theme.css#L74)）、`--bs-primary` `#0067c0`、卡片 `8px` + 阴影 `rgba(0,0,0,0.08) 0px 1px 3px`、输入框与主按钮圆角 `4px`、**实测高度 34px**、主按钮底色 `rgb(0,103,192)`。

**用例口径的变化（本轮不新增编号，只改既有断言的指向）**

| 用例 | 变化 | 依据 |
|---|---|---|
| TC-NAV-01 | 十条页签 → **十一条**：`聚合`（路由 `repacks`）此前一直没进清单 | [App.vue:29-41](../web/src/App.vue#L29) 的 `tabs` 数组实测 11 项 |
| TC-NAV-04 | 顶栏渲染条件没变（[:12](../web/src/App.vue#L12)），但窄屏折叠的实现从"`App.vue` 的 `<style scoped>`"换成 Bootstrap 的 `navbar-expand-xxl` + Vue 状态驱动两个类 | [:17-22](../web/src/App.vue#L17)；**仍属 `[B]`，响应式未验收** |
| TC-NAV-02 | 品牌链接位置随重写变化 | [:48](../web/src/App.vue#L48) |
| TC-UIF-01…10 | 整族按 Bootstrap 5.3 + `theme.css` 重写；`fluent.css` 不再是被引用的实现文件 | 24.3 的表本轮已逐行改写 |
| TC-UIF-02 | 断言从"`fluent.css` 里"改为"`theme.css` 里"，grep 命令不变，实测 0 命中 | `.vue` 全量 `grep -rniE "#[0-9a-f]{3,8}\b"` |
| TC-UIF-07 | 焦点环从 `fluent.css:96-98` 搬到 [theme.css:191-194](../web/src/styles/theme.css#L191)，`:where(...)` 写法原样（不抢权重） | 同上 |
| TC-SET 堆内存行 | `heapPercent` 从"字符串带 %"改为**数值**，因为 `role="progressbar"` 要 `aria-valuenow`，上色也要数值阈值 | [SettingsView.vue:99-103](../web/src/views/SettingsView.vue#L99)（计算）+ [:106-108](../web/src/views/SettingsView.vue#L106)（`barTone`）+ [:167-170](../web/src/views/SettingsView.vue#L167)（进度条） |
| TC-INB 键盘流 | `useInboxKeys` 的选择器改名是**跨文件契约**：`InboxView` 的行类从 `row` 改为 `entry-row`（`row` 与 Bootstrap 栅格撞名），两端必须同改 | [useInboxKeys.ts:40-42](../web/src/composables/useInboxKeys.ts#L40) ↔ [InboxView.vue:197](../web/src/views/InboxView.vue#L197) 的注释 |

**引用行号的大扫除（本轮最大的一笔账）**

一次 UI 重写把 15 个 `.vue` 从 5185 行改到 3747 行，五份文档里按旧内容写的 `文件#行号` 集体失真，而本工作区**不是 git 仓库**，没有 diff 可查。做法四步：① 从 Qoder 的 file-history 镜像恢复 F13 之前的 17 个前端源文件到 `.dev-logs/f13-old/`；② `.dev-logs/f13-remap.js` 逐文件做 LCS 对齐（先把 `class="…"`、`:class="…"`、`{{ … }}` 规范化掉再比），旧行号映射到新行号，未命中的继承下一条已匹配行并标为低置信；③ `.dev-logs/f13-fixdoc.js` 按映射表改写文档引用，逐条打印判定依据；④ 用**独立**脚本 `.dev-logs/f13-check2.js` 重新解析五份文档的每个 markdown 链接（相对各文档自己的目录解析路径），只问三件事：文件在不在、行号越不越界、被指行 ±4 行里有没有标签里的标识符或中文词。

**回读结果（2026-11-02）**：898 条引用，**0 缺文件、0 越界**，13 条 NO-LABEL-HIT；写完本节再跑一遍是 **920 条**（多出的 22 条就是 24.6 自己指向源码的引用），0 缺文件、0 越界、13 条 NO-LABEL-HIT 一条没变。这 13 条逐条读过被指行，全部是"中文标签 vs 英文代码"的取名差异（例如标签"分组按钮"对应 `<button v-for="name in feeds.groups">`、标签"预览区"对应 `class="preview"`、标签"RSS Hub 品牌"对应 `navbar-brand`），没有一条是行号错位。用例册计数不动：`grep -cE '^\| TC-[A-Z]+-[0-9]+[a-z]?' doc/test-plan.md` = **423**、定义行去重后仍 423（0 条重号）、P0 列同法 = **255**。

**勘误表（本轮写文档时的原说法 → 正确说法 → 出处）**

| 原说法 | 正确说法 | 出处 |
|---|---|---|
| 控件高 32px（01 的 NFR 与 TC-UIF 旧行） | `32px` 是 F12 令牌 `--control-height` 的值，该令牌随 `fluent.css` 一起退役；Bootstrap 时代控件高 = padding × 根字号，`/login` 实测 **34px** | [theme.css:74-76](../web/src/styles/theme.css#L74) + 本轮 getComputedStyle |
| 旧 `App.vue` 151 行 | 同一口径（`awk 'END{print NR}'`）是 **150** 行 | `.dev-logs/f13-old/App.vue` |
| 旧 `InboxView.vue` 437 行 | **377** 行；437 是 `EventsView.vue` 与 `RuleEditor.vue` 的行数，抄串了 | `.dev-logs/f13-old/InboxView.vue` |
| 15 个 `.vue` 合计 4591 → 3197 行 | **5185 → 3747** 行（-27.7%） | 逐文件 `awk 'END{print NR}'` 求和 |
| 37 个图标名 | **41 个**唯一 `bi-*` 名、**50 个**位点 | `grep -rhoE "bi-[a-z0-9-]+" --include=*.vue web/src | sort -u` |
| "键盘流一行没改" | 改了一处选择器（`.row.picked` → `.entry-row.picked`）并加两行注释，46→48 行 | [useInboxKeys.ts:42](../web/src/composables/useInboxKeys.ts#L42) |
| "Bootstrap JS 全 `src` 零命中" | 全 `src` 下有 1 条命中，是 [theme.css:14](../web/src/styles/theme.css#L14) 注释里那句 `new bootstrap.Dropdown(...)`；限定 `--include=*.vue --include=*.ts` 才真正零命中 | 同上 |
| JS 产物 221.09 kB | 补 `aria-hidden` 后重跑构建为 **221.11 kB**（gzip 仍 73.70） | `npm run build` 输出 |
| 24.5 表里"顶栏 [退出] 在 `App.vue:42`、`signOut` 在 `:17-20`" | 那是第八轮的位置；F13 重写后 `[退出]` 在 [App.vue:68](../web/src/App.vue#L68)、`signOut` 在 [:24-27](../web/src/App.vue#L24) | 本轮读源码 |
| 24.5 "没变的" 清单里含 `App.vue:17-20/:25/:26/:29/:38` | 该清单只覆盖后端与两个仍对的 `.vue` 位置（`:12`、`:15`）；其余五处已在 F13 各就新位，见 24.6 | 同上 |

**本轮这一节的代码改动只有两处**：`InboxView.vue:118` 的 `aria-hidden` 与 24.6 表里那三条缺陷的修复（前两条在 F13-g 已改）；其余是文档。

**未复核（与 24.4 同口径，登记在 [03 §14 O24](./design/03-technical-design.md)）**

- [ ] 登录后的 11 个管理界面本轮**一个像素都没被人眼看过**（按既有约束不驱动浏览器过登录流）。
- [ ] 视觉类 `[B]` 全部未验：顶栏 mica 模糊在真实数据下的观感、长列表滚动时 `backdrop-filter` 的帧率、大屏卡片墙密度、`navbar-expand-xxl` 折叠后的可用性、hover 过渡与焦点环观感。
- [ ] 键盘流 j/k/n/s/m 的手感——本轮只证明代码路径未变（选择器两端同步改名），未证明按起来还对。

---

### 24.7 第十轮执行实录与勘误（F14：控制台改淡蓝色调，2026-11-02）

**触发与口径**：用户原话"采用淡蓝色色调重新优化UI界面"。取向沿用 F13 那次二选一里用户选定的**保留强调色**，所以淡蓝只落在中性族；实现面**只动 `web/src/styles/theme.css` 与 `web/index.html` 一行**，15 个 `.vue` 零改动。产品 Java 一行未动。

**门禁（本轮重跑，不复用上一轮数字）**

| 门禁 | 结果 | 证据 |
|---|---|---|
| `cd web && npm run build` | 94 modules / **JS 221.11 kB（gzip 73.70）不变** / **CSS 322.72 kB（gzip 48.02）**；`vue-tsc --noEmit` 0 错误 | JS 一字节没动 = 本轮无脚本改动；CSS +1.69 kB 全是品牌层末尾的 F14 块。文档同步收尾时因 `web/index.html:6` 改了 `theme-color` **又重跑一次**，构建数字不变，实测 `dist/index.html:6` 已带 `content="#e9f1f9"` |
| `.vue` 里硬编码色值 | **0 处**（`grep -rniE "#[0-9a-f]{3,8}\b" --include=*.vue web/src`） | TC-UIF-02 |
| `.vue` 里的 `:hover` / scoped 块 / Bootstrap JS | **1 处 / 14 块 / 三条 grep 零命中** | 与 F13 同，本轮不动结构 |
| `./mvnw -o -B -ntp verify` | **397 项 / 0 失败 / 38 个测试类 / BUILD SUCCESS（54.7 s）** | `.dev-logs/f14-verify.log`（跑前先 `bash start-dev.sh stop` 释放 jar，见 [03 §14 O25](./design/03-technical-design.md)） |
| `backend/smoke.sh` | **193 条 `PASS` / 0 条 `FAIL` / 末行 `ALL PASS`** | `.dev-logs/f14-smoke.log`；一次性实例由 `bash .dev-logs/smoke-run.sh f14` 起（全新库 `./.dev-logs/smoke-f14`、口令运行时自造、`:19999` fixture 源随脚本重启到 revision=1） |
| 开发栈 | :8080 与 :5173 均 200（`/actuator/health`、`/login`） | 收尾时重启 |
| `theme.css` 行号锚点 | 331 → **417 行，新增全在文件末尾**；`test-plan.md` 的 `theme.css#L` 引用现为 **28 条 / 27 个锚点**（F14 加了 `#L22`/`#L37`/`#L78`/`#L356`/`#L369` 五条，最大 `#L369`），`04-roadmap.md` 另 1 条——逐条机器回读，零位移、零越界 | 中途一度把顶部令牌注释写成两行，`--canvas` 从 `:18` 掉到 `:19`，会集体位移 17 个锚点；压回单行后重测。全套文档的 `file#L` 引用由 `.dev-logs/f13-check2.js` 复跑：**939 条，0 缺失 / 0 越界**，13 条 `NO-LABEL-HIT` 是链接文字与代码行的语义差（F13 基线同数），不是坏引用 |

**本轮改出来的缺陷（四条 + 一条掉队）**

| 发现 | 原因 | 处置 |
|---|---|---|
| 卡片、输入框、弹层与页面**同色**，需求 NFR 的"白表面"从未成立 | Bootstrap 5.3.8 的 `.form-control` 没有组件级背景变量，底色直读 `--bs-body-bg`；`.card` 读 `--bs-card-bg`（默认 `var(--bs-body-bg)`）、`.dropdown-menu` 读 `--bs-dropdown-bg`。F13 把画布挂在这个变量上 | `--bs-body-bg` 指回"表面"（[theme.css:51](../web/src/styles/theme.css#L51)），页面底色由 `body` 自己画 `--canvas`（[:78-82](../web/src/styles/theme.css#L78)）。改前实测 body/`.card`/`.form-control` 全是 `rgb(233,241,249)`，改后卡片与输入框 `rgb(255,255,255)` |
| 6 个 `.bg-body-tertiary`、8 个 `.text-bg-secondary`、1 个 `.text-body` 位点仍是 Bootstrap 灰阶 | 背景与文字工具类读的是 **`*-rgb` 双生子**：`.bg-body-tertiary` 用 `rgba(var(--bs-tertiary-bg-rgb), …)`，F13 设的同名非 -rgb 变量对它无效；`.text-bg-secondary` 读 `--bs-secondary-rgb` 而非 `--bs-secondary-bg`；`.text-body` 读 `--bs-body-color-rgb` | [theme.css:356-367](../web/src/styles/theme.css#L356) 补 `--bs-body-color-rgb`/`--bs-secondary-bg-rgb`/`--bs-tertiary-bg-rgb`/`--bs-secondary-rgb`/`--bs-light-rgb`/`--bs-dark-rgb`。**反例同记**：`.text-body-secondary`（94 个位点，本控制台最多的文字类）读的偏偏是非 -rgb 的 `--bs-secondary-color`，F13 那次指对了——差一个后缀的两条规则，人眼扫 CSS 扫不出来 |
| 控制台最常用的按钮（`.btn-outline-secondary`，**18 个位点**）是灰的，令牌碰不到 | Bootstrap 把 `#6c757d` 写成字面量；`.btn-secondary`、`.btn-outline-dark`、disabled 态 `.btn-link`、`.form-floating` 的禁用标签同 | 沿用 `.btn-primary` 的写法按组件逐键覆盖 `--bs-btn-*`（[:369-417](../web/src/styles/theme.css#L369)）：常态文字 `--ink-2`、描边 `--line-strong`、悬停填 slate `#5a7188`（白字 5.06:1）。找法 `.dev-logs/f14-grayhunt.js`（在 `bootstrap.min.css` 里搜灰阶十六进制，与 15 个 `.vue` 真用到的类名取交集），命中 5 条 |
| `--ink-3` 在画布上只有 **3.11:1**（白底 3.45:1），低于正文 AA 的 4.5 | F12 定令牌时按"最淡的一档"取值，没算过对比度；F13 原样沿用 `#8a8a8a` | 提到 `#546a85`：画布 4.88 / 白 5.56 / 悬停底 4.56；三档墨色全部过 AA（比值出自 `.dev-logs/f14-contrast2.js`，新增 TC-UIF-13 钉住） |
| `theme-color` 是**唯一没跟上转蓝的色位** | 它在 `web/index.html:6`，不在 `theme.css` 的令牌块里，也不受 `.vue` 色值 grep 约束——F13 那条"色值只在一个文件"的验收线正好放过了它 | 改为 `#e9f1f9`（与 `--canvas` 同值）。TC-UIF-09 的"`theme-color` 与画布同色"从此有实测值可核 |

**探针式验收（替代登录后走查）**：登录页没有徽标族与按钮族，于是在 `/login` 的 DOM 里临时注入 13 个代表类、读 `getComputedStyle` 后删节点——CSS 全局生效，注入元素与真页面命中同一条规则。实测：`body rgb(233,241,249)`｜`.card` 与 `.form-control` `rgb(255,255,255)`｜卡片 `8px` / 控件 `4px`｜控件高 `34px`｜`.btn-primary rgb(0,103,192)`｜`.btn-outline-secondary` 文字 `rgb(74,94,117)` 描边 `rgb(182,203,224)`｜`.btn-secondary` 与 `.text-bg-secondary` `rgb(90,113,136)`｜`.text-bg-light` 与 `.bg-body-tertiary` `rgb(243,248,253)`｜`.text-bg-dark` `rgb(11,26,43)`｜`.text-body`/`-secondary`/`-tertiary` `rgb(22,33,46)`/`rgb(74,94,117)`/`rgb(84,106,133)`；**无一处落在 `#6c757d`/`#212529`/`#dee2e6`/`#f8f9fa`/`#0d6efd`**；控制台无报错。截图 `.dev-logs/f14-login-final.png`。

**用例面变更**

| 用例 | 变更 | 依据 |
|---|---|---|
| TC-UIF-11（新） | 表面与画布必须分色：断言打在 `getComputedStyle` 的解析结果上，不打在令牌值上 | F14 缺陷第一条；令牌各就各位却仍然同色是本轮实测出来的失败模式 |
| TC-UIF-12（新） | 工具类不许落在 Bootstrap 默认灰阶：13 个代表类的注入探针 + `*-rgb` 双生子 + 字面量灰两类找法 | `.dev-logs/f14-grayhunt.js`、`.dev-logs/f14-classaudit.js`（35 个带色类名 × 位点计数） |
| TC-UIF-13（新） | 中性族过 AA：六个比值写进用例 | `.dev-logs/f14-contrast2.js` |
| TC-UIF-01 | "下拉/折叠/toast 全是 Vue 状态 + CSS 类"降级为**只有折叠导航成立**：`grep -rn dropdown`、`grep -rn toast` 在 15 个 `.vue` 里各自零命中 | 本轮盘点 |
| TC-UIF-02 | 加限定："改一个文件"= 同一个文件里的**两层**（令牌 + 组件级桥接块），不等于"只改 `:root`" | F14 三条盲区 |
| TC-UIF-03 / 05 / 08 / 09 | 分别补 `--mica` 的淡蓝值、`.form-control` 底色来自 `--bs-body-bg`、`text-bg-secondary` 已是 slate、阴影不再是纯黑且 `theme-color` 曾掉队 | 本轮实测 |

**勘误（原说法 → 正确说法 → 出处）**

| 原说法 | 正确说法 | 出处 |
|---|---|---|
| 01 NFR 界面行："浅色 mica 画布上叠白色表面" | F13 的实现里画布与表面同色；F14 起才成立，且成立条件是 `--bs-body-bg` = 表面、`body` 自己画画布 | [theme.css:51](../web/src/styles/theme.css#L51)、[:78-82](../web/src/styles/theme.css#L78) |
| 03 §11："色值只在一个文件里，所以换肤改一个文件" | 仍只一个文件，但 Bootstrap 有 5 条规则把灰阶写成字面量、6 个工具类读 `*-rgb` 双生子，必须按组件覆盖；`.vue` 与 `index.html` 各有一处框架外的色位（后者是 `theme-color`） | `.dev-logs/f14-grayhunt.js` 命中 5 条；`web/index.html:6` |
| 03 §11 / 04 F13："下拉/折叠/toast 全部是 Vue 的 `ref` + Bootstrap 的类名" | 本项目**没有下拉，也没有页内 toast**；只有 `App.vue` 的导航折叠用到 `collapse`/`show`（三处位点）。`theme.css:231-235` 的 `.dropdown-menu, .flyout` 是无位点规则 | 15 个 `.vue` 的 grep；`--shadow-flyout` 仍经 `--bs-box-shadow`（[:65](../web/src/styles/theme.css#L65)）生效 |
| 04 F13 出口数字表："自写 hover 规则只剩 2 处" | 仍然成立（`theme.css:261-264` 顶栏页签 + `InboxView.vue:208-210` 条目行），本轮未动 | `grep -rn ":hover" --include=*.vue web/src` = 1 处 |
| F12/F13 的 `--ink-3: #8a8a8a`（"最淡的一档"） | 对白 3.45:1、对旧画布 3.11:1，低于 AA 4.5；F14 起 `#546a85`（5.56 / 4.88） | `.dev-logs/f14-contrast2.js` |
| 04 F13："CSS 321.03 kB（gzip 47.71）" | F14 末为 **322.72 kB（gzip 48.02）**；JS 221.11 kB 不变 | `npm run build` 本轮输出 |

**未复核（与 24.4/24.6 同口径，登记在 [03 §14 O24](./design/03-technical-design.md)）**

- [ ] 登录后的 11 个管理界面本轮**仍一个像素都没被人眼看过**：淡蓝族在真实数据下的密度与对比、顶栏 mica 在新画布上的观感、长列表滚动时 `backdrop-filter` 的帧率、大屏卡片墙、收件箱三栏的换行——全部未验。替代证据是 13 个代表类的注入探针（CSS 全局生效，与真页面同一条规则）+ `vue-tsc` 0 错误 + 三条 grep 断言 + 397/193 两个后端门禁不回退。
- [ ] 状态色（`success/danger/warning/info`）本轮**按语义刻意没动**，因此"淡蓝族里放一枚琥珀/一枚绿够不够协调"属观感，没有机器替身。
- [ ] 深色模式（`data-bs-theme="dark"`）没有需求来源，本轮只做了浅色；如果日后做，F14 这套 `*-rgb` 双生子与字面量灰清单就是必须重做一遍的清单。

---

### 24.8 第十一轮执行实录与勘误（F15：覆盖面收口 —— "声明了但没证据"的路由，2026-11-02）

本轮的问题是用户口径里的"完整覆盖所有功能"，所以审的对象**不是产品，而是这份目录自己的诚实度**：把三张表交叉——① 控制器里真实存在的映射、② 用例编号、③ 每条用例的证据标记（`[自]`/`[C]`/`[机]`）——凡"标记声称有证据、仓库里找不到那条证据"的，都算缺口。找到四处，全部补齐；另有一类**行号漂移**是既有校验器看不见的，加了新门禁。

**四处覆盖缺口与修法**

| 编号 | 缺口 | 补的证据 | 落点 |
|---|---|---|---|
| GAP-1 | `GET /api/entries/counters` 只有 `[自]InboxApiTest` 一个标记，而它是前端**真在轮询**的路由（[entries.ts:204](../web/src/stores/entries.ts#L204)），真 HTTP 从没打过 | 冒烟脚本新增 4 条断言：有 `unread` 标量、与页面内嵌 `counters.total` 同源相等、匿名 401 | [smoke.sh:505-509](../backend/smoke.sh#L505)；用例 TC-API-07 |
| GAP-2 | `GET /api/rules/{id}` 在全目录里**只有 404 负例**，没有一条正向证据说明"编辑器打开一条已存规则"能拿回它自己 | 在既有 `RulesApiTest` 方法内加 5 条逐字段断言（`id`/`name`/`actions[0].channel`/`suppression.key`/清空的 `quietHours` 不该有字段） | [RulesApiTest.java:130-139](../backend/src/test/java/com/rsshub/adminapi/RulesApiTest.java#L130)；新增用例 TC-RUL-20 |
| GAP-3 | TC-PUB-40/41 挂着 `[C]`，但 `/tag`、`/category` 两个切片**从未被 curl 过一次**（只在 MockMvc 里见过） | 冒烟脚本新增 8 条：标签切片 200 + 只含本文 + `<title>` 用 term.title、分类 atom 200、json 切片一条 item、没打这个标签的文章不得混入、术语不存在 = 404（标签与分类各一条） | [smoke.sh:490-502](../backend/smoke.sh#L490)；TC-PUB-40/41/42 |
| GAP-4 | `[机]` 这个标记被 6 条用例使用，但 §0.3 的标记表里**没有它的定义** | 补一行：不开浏览器、不起服务，守的是源码或构建产物本身（grep / 构建 / `node` 脚本），与 `[自]` 的区别写在字面 | 本文 §0.3 |

**引用行号勘误（12 处，全部指向 `PublicFeedController.java` 的错误处理器）**

这一类的形状值得单独记：12 条引用**文件对、行号在文件范围内**，所以 `.dev-logs/f13-check2.js` 的"缺失/越界"两项都是 0，只有真的把行读出来才知道指错了地方。根因是 M5 给该控制器加了 `/repack` 出口，处理器整体下移，而文档当时只校了范围。

| 旧引用 | 正确行 | 文档位置 | 那两行实际声明的是 |
|---|---|---|---|
| `:51` | `:63` | 8.1 路由总表（本文 :428）、TC-PUB-40（:482） | `/tag/{slug}.{format}` |
| `:57` | `:69` | 8.1 路由总表（:429）、TC-PUB-41（:483） | `/category/{slug}.{format}` |
| `:35,79-82` | `:35,111-114` | TC-PUB-01（:439） | `CACHE` 常量 + `Cache-Control` 写入 |
| `:69-72` | `:101-104` | TC-PUB-11（:451） | ETag 命中走 304 的短路 |
| `:83-85` | `:115-117` | TC-PUB-15（:455）、§17 勘误表"RSS `lastBuildDate`"行（:685） | `Last-Modified` 只在有值时写 |
| `:89-96` | `:121-127` | TC-PUB-43（:485） | `parseFormat` 的 `unknown_format` |
| `:67` | `:79` | TC-EVT-06（:822） | `/feed/{slug}.{format}`（事件源出口） |
| `:77` | `:89` | TC-RPL-06（:921） | `/repack/{slug}.{format}` |
| `:51-57,79` | `:57,79` | TC-CHG-13 路由冲突（:979） | `/feed/changelog.{format}` 与 `/feed/{slug}.{format}` 的遮蔽关系 |

**新门禁：`.dev-logs/f15-routecheck.js`**

它把每条"引用 `.java` 某行"的文档行还原成**那一行实际声明的 URL**（类级 `@RequestMapping` 前缀 + 附近 ±12 行内的映射注解——因为引用常落在那段 javadoc 上，而不是方法签名行），再和文档行里写的路由 token 逐字对；顺带检查 `[Foo.java:NN](…#LMM)` 的标签与锚点是否自相矛盾。本轮 **54 条带路由的引用全部核对，0 条漂移**。已知局限也写在这里：±12 行窗口是粗的、只认控制器类、`{id}`/`<slug>`/数字段会先归一化再比——它守的是"文档说的路由和代码声明的路由是不是同一条"，不是"这条路由对不对"。

**执行门禁（全部在本机重跑，日志留档）**

| 门禁 | 命令 | 结果 | 日志 |
|---|---|---|---|
| 后端全量 | `backend/mvnw -o -B -ntp verify` | **397 项 / 38 个类 / 0 失败 0 错误 / BUILD SUCCESS**（01:03 min） | `.dev-logs/f15-verify.log` |
| 真 HTTP 冒烟 | `bash .dev-logs/smoke-run.sh f15` | **205 条 `PASS` / 0 条 `FAIL` / 末行 `ALL PASS`**，比上一轮 193 项多 12 项（GAP-1 4 条 + GAP-3 8 条），全部落在文档最高被引行 471 之后，20 条既有引用不受影响 | `.dev-logs/f15-smoke.log` |
| 引用完整性 | `node .dev-logs/f13-check2.js` | **963 条** `文件#L行` 引用：**0 缺失、0 越界**，13 条 `NO-LABEL-HIT`（中文标签与英文代码行的语义差，逐条读过被指行，F13 基线同数） | 本轮 |
| 引用语义 | `node .dev-logs/f15-routecheck.js` | **54 条**带路由的引用，**0 条漂移** | 本轮 |
| 前端 | `npm run build` | 94 模块、`vue-tsc` 0 错误、CSS 322.72 kB（gzip 48.02）、JS 221.11 kB（gzip 73.70） | 本轮 |

一次性实例口径不变：全新库 `./.dev-logs/smoke-f15`、口令运行时自造只经环境变量、`:19999` fixture 源由 `smoke-run.sh` 重启到 revision=1、`--rsshub.ssrf.allow-private-prefixes=127.` 只为放行它。开发库 `backend/data/rss-hub.mv.db` 全程未被读写。

**交付物（本轮另两问）**

- [README.md](../README.md)：能做什么 / 技术栈 / 快速开始 / 测试与验收 / 界面与路由 / 仓库结构 / 安全边界。
- [doc/deploy.md](./deploy.md)：三种部署形态、构建产物、环境变量表、反代配置（Caddy 与 nginx 两版）、systemd、备份恢复、升级回滚、PostgreSQL 路径、验收清单、常见故障。
- 写部署文档时**撞到一条真实约束**（不是笔误，是设计后果）：Java 占了 `/`（[PublicViewController.java:46](../backend/src/main/java/com/rsshub/editorial/PublicViewController.java#L46) 映射 `{"/", "/blog"}`，[SecurityConfig.java:78-79](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L78) 把 `/` 放进 permitAll），而 SPA 用 `createWebHistory()`（[router/index.ts:17](../web/src/router/index.ts#L17)）且收件箱也在 `/`（[:19](../web/src/router/index.ts#L19)）——**两个界面不能共用一个域名**。开发态没暴露它，因为控制台在 :5173、公开站在 :8080。deploy.md §4.1 给了两条出路（分域，或给 SPA 加 `base` 前缀），并按"未实测"标注后者。

**勘误（原说法 → 正确说法 → 出处）**

| 原说法 | 正确说法 | 出处 |
|---|---|---|
| §0.3：`[C]` = "用 `backend/smoke.sh`（193 项）" | 本轮起 205 项；且 193 这个数字在 24.4/24.6/24.7 里指**各自那一轮**的实测，不是当前值，历史口径保留不改写 | `.dev-logs/f15-smoke.log` |
| TC-PUB-40/41/42 的 `[C]` | 此前不成立（无 curl 证据），现由 [smoke.sh:490-502](../backend/smoke.sh#L490) 使其成立 | 本轮 |
| TC-API-07 只有 `[自]` | 现同时有 `[自]` 与 `[C]`；且断言的是"两处同源"（独立路由 vs 页面内嵌 `counters`），不是"能返回 200" | [smoke.sh:507-508](../backend/smoke.sh#L507) |
| "路由引用的行号在文件范围内就算对" | 范围内 ≠ 指对；需要把那一行的映射还原出来比字符串。已加 `f15-routecheck.js` 守这个门 | 12 处漂移清单 |
| 部署文档可以写"Java 服务静态文件即可单域部署" | 当前代码里 Java 自己服务 `/`，与 SPA 的 `/` 冲突；要么分域，要么改 SPA 的 `base`（尚未做） | [PublicViewController.java:46](../backend/src/main/java/com/rsshub/editorial/PublicViewController.java#L46) · [router/index.ts:17-19](../web/src/router/index.ts#L17) |

**未复核（与 24.4/24.6/24.7 同口径，登记在 [03 §14 O24](./design/03-technical-design.md)）**

- [ ] 登录后的 11 个管理界面本轮**仍未在浏览器里被人眼看过**；本轮补的两条路由（`/api/entries/counters`、`/api/rules/{id}`）证据止于真 HTTP 与单元断言，**没有证明 UI 按钮点了之后界面变对**。
- [ ] `doc/deploy.md` 的 Linux/systemd、PostgreSQL、nginx 三条路径**本机未实测**（没有 docker、没有 PG 实例），文档内已逐处标注；Caddy 与 Spring Boot 的外部结论按官方文档核对，见该文 §11 出处表。
- [ ] GAP-3 的"切片只含本文"用的是布尔 grep 计数，粒度到标题而不是 GUID；跨标签同名文章的误判风险没有专门用例。

---

## 25. 第十二轮新增用例与执行结果（F16：批量订阅，2026-11-03）

用户口径：**"增加批量增加订阅的功能，自动以 `,` `;` `:` 、 回车等自动解析成多条"**。落点是订阅管理页上方新增的一张折叠卡片：贴一段文本 → 本机把它读成"一条地址 + 地址前面的名字" → 先看逐行清单 → 再一次性写入。本轮四件东西：一个纯文本拆分器、两个端点、一个面板、一批断言。

| 层 | 位置 | 这一层只负责什么 |
|---|---|---|
| 拆分器 | `PastedSubscriptions.split(String)`（[PastedSubscriptions.java:37-52](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L37)） | 文本的读法：不补协议、不判 SSRF、不碰库；输出 `Candidate(lineNo, label, address)`（[:29](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L29)） |
| 预览端点 | `POST /api/feeds/batch/plan`（[FeedController.java:148-151](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L148)）→ `planBatch`（[SubscriptionService.java:154-157](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L154)） | 逐行判决 `ready`/`duplicate`/`refused`/`invalid` 与一个 `ready` 计数，**一行都不写** |
| 写入端点 | `POST /api/feeds/batch`（[FeedController.java:158-161](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L158)）→ `addBatch`（[SubscriptionService.java:171-199](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L171)） | 只写 `ready` 行，返回 `added` 与逐行的最终判决（写成功的行把 `ready` 覆盖成 `added`） |
| 面板 | [FeedsView.vue:354-441](../web/src/views/FeedsView.vue#L354) | 折叠卡片（本项目不引 Bootstrap 的 JavaScript，所以是 `v-if` + `:aria-expanded`，不是 collapse 组件），提示框与单条添加各用一套 |
| 客户端 | 三个类型 [api/feeds.ts:84-106](../web/src/api/feeds.ts#L84) · 两个方法 [:124-126](../web/src/api/feeds.ts#L124) | 能发的键只有 `text` 与 `group`：客户端没有"把某条地址改成另一条"的通道 |

三条口径先钉住，25.1–25.3 的用例基本都在验它们：

1. **预览是预览，不是指令。** 写入端点重新解析同一段文本——`addBatch` 的第一句就是 `classify(text)`（[SubscriptionService.java:172-173](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L172)），与 `planBatch` 用的 [:155](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L155) 是同一个私有方法 [:206-253](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L206)——所以拿着 plan 的返回体也改不出一个 guard 没检查过的地址；两次调用之间被别人订阅走的地址，这一轮表现为 `duplicate/already_subscribed` 而不是第二行（TC-SUB-64）。
2. **批量不做逐行"解析"。** 与 OPML 导入同一口径，理由写在 [SubscriptionService.java:166-169](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L166)：五十个地址各发一次发现请求，一个不可达的主机就能挂住整批。地址原样入库（`requested_url = canonical_url`，[:183](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L183)）；贴进来的是网页 ⇒ 第一抓在健康度列写 `not_a_feed`，补救入口是上面那个单地址"解析"按钮，面板的说明文字把这条限制直接告诉操作员（[FeedsView.vue:374-378](../web/src/views/FeedsView.vue#L374)）。
3. **不静默丢行。** 读不懂的一行仍占一行（[PastedSubscriptions.java:93-95](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L93)：行尾没人认领的名字放回候选），否则"贴二十行成了十八行"与"本来只有十八行"在屏幕上长得一样（TC-SBP-09 / TC-SUB-69）。

### 25.1 `SBP` 拆分器：粘贴文本的读法（12 条，全部 `[自]`，不碰网络也不碰库）

| 编号 | P | 输入形状 | 期望读法 | 依据 / 现状 |
|---|---|---|---|---|
| TC-SBP-01 | P0 | 每行一个地址，中间夹一行空行 | 一条候选一条地址；`lineNo` 是**原文行号**（1、3、4），空行不产候选但行号不塌陷，表里点得出是哪一行出问题 | [PastedSubscriptions.java:42-50](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L42) 按 `\R` 分行 · [PastedSubscriptionsTest.java:17](../backend/src/test/java/com/rsshub/adminapi/PastedSubscriptionsTest.java#L17) `oneAddressPerLineKeepsItsLineNumber` `[自]` |
| TC-SBP-02 | P0 | `, ，; ；、` 与空格/回车混排，含 `a,,b` 这种连续分隔符 | 全部拆开，空 token 不出现——一条 `TOKENS = [^\s,，;；、]+` 吃掉所有宽度，不需要逐字符判断中英文 | [:32](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L32) · [PastedSubscriptionsTest.java:32](../backend/src/test/java/com/rsshub/adminapi/PastedSubscriptionsTest.java#L32) `[自]` |
| TC-SBP-03 | P0 | `阮一峰:https://…/atom.xml`（半角冒号） | **一条**候选：`label=阮一峰`、`address=https://…`。冒号左侧不像地址而右侧像 ⇒ 这道冒号是"命名"，不是分隔 | [:58-72](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L58) `emit` · [PastedSubscriptionsTest.java:43](../backend/src/test/java/com/rsshub/adminapi/PastedSubscriptionsTest.java#L43) `[自]` |
| TC-SBP-04 | P0 | `王垚： http://…`（全角冒号 + 其后空格） | 与 TC-SBP-03 同一读法：`firstSplitColon` 同时认 `:` 与 `：`，中文输入法下的冒号不会退化成"两条订阅" | [:103-116](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L103) · [PastedSubscriptionsTest.java:52](../backend/src/test/java/com/rsshub/adminapi/PastedSubscriptionsTest.java#L52) `[自]` |
| TC-SBP-05 | P1 | `笔记 https://…`（名字与地址之间只有空格） | 仍是"名字 + 地址"一条，**不会**多出一条订阅——聊天记录与两列表格贴过来就是这个形状 | [:79-96](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L79) `nameTheAddresses` · [PastedSubscriptionsTest.java:61](../backend/src/test/java/com/rsshub/adminapi/PastedSubscriptionsTest.java#L61) `[自]` |
| TC-SBP-06 | P0 | `https://p.example:8443/feed?a=1&b=2`（CRLF 换行） | 协议冒号与端口冒号都不许拆：`:8443/` 后面是"数字 + `/`"，属于地址的一部分，整条原样保留；`\R` 认 CRLF | [:110](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L110) `after.matches("\\d+([/?#].*)?")` · [PastedSubscriptionsTest.java:72](../backend/src/test/java/com/rsshub/adminapi/PastedSubscriptionsTest.java#L72) `[自]` |
| TC-SBP-07 | P1 | `a.example/rss:b.example/rss`（冒号两侧都像地址） | 两条地址，左侧**不是**名字——否则第二个地址会被当成第一个的标题而丢掉 | [:69-70](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L69) · [PastedSubscriptionsTest.java:82](../backend/src/test/java/com/rsshub/adminapi/PastedSubscriptionsTest.java#L82) `[自]` |
| TC-SBP-08 | P1 | `旧竹新苑 博客 https://…`（一行两个非地址 token） | 合成一个名字"旧竹新苑 博客" + 一条地址：名字并成一支，第二条订阅不会带着半个名字入库 | [:83](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L83) · [PastedSubscriptionsTest.java:90](../backend/src/test/java/com/rsshub/adminapi/PastedSubscriptionsTest.java#L90) `[自]` |
| TC-SBP-09 | P0 | `见备注`（整行读不懂） | 仍产出一条候选 `address=见备注`——判 `invalid` 是 `classify` 的事，拆分器无权丢行 | [:93-95](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L93) · [PastedSubscriptionsTest.java:98](../backend/src/test/java/com/rsshub/adminapi/PastedSubscriptionsTest.java#L98) `[自]` |
| TC-SBP-10 | P1 | `https://x.example/feed 备注`（名字写在地址**后面**） | 两条候选：地址一条、"备注"一条。并名字只在"非地址且走在地址前面"时发生；尾部的孤立 token 放回候选（同 TC-SBP-09 的机制） | [:82-84](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L82) · [PastedSubscriptionsTest.java:108](../backend/src/test/java/com/rsshub/adminapi/PastedSubscriptionsTest.java#L108) `[自]` |
| TC-SBP-11 | P1 | `example.com`（裸域名，没有路径） | 仍作为**地址**交下去（`label` 为空）：`looksLikeAddress` 认的是"点 + 斜杠"，缺斜杠时它先被当名字，但行尾没人认领就原样回到候选——补不补协议由 `normalise` 判 | [:123-128](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L123) + [:93-95](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L93) · [PastedSubscriptionsTest.java:116](../backend/src/test/java/com/rsshub/adminapi/PastedSubscriptionsTest.java#L116) `[自]` |
| TC-SBP-12 | P2 | 只有空白/制表/换行，以及 `null` | 返回**空列表**而不是抛异常："这堆文本里没有地址"是服务的 400 `nothing_to_add`（TC-SUB-71），不是拆分器的崩溃现场 | [:39-41](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L39) · [PastedSubscriptionsTest.java:126](../backend/src/test/java/com/rsshub/adminapi/PastedSubscriptionsTest.java#L126) `[自]` |

### 25.2 `SUB` 新增：批量两个端点（12 条，`[自]` + `[C]`）

| 编号 | P | 端点 / 场景 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-SUB-61 | P0 | `POST /api/feeds/batch/plan` | 贴"两行 + 其中一行两条（逗号分隔）" | `items` 三行、`ready:3`、`lineNo` 按原文（1,2,2）；**`feed` 表仍然零行**——预览不写 | [SubscriptionService.java:154-157](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L154) · [SubscriptionBatchApiTest.java:87](../backend/src/test/java/com/rsshub/adminapi/SubscriptionBatchApiTest.java#L87) `planSplitsEveryLineAndWritesNothing` `[自]` `[C]smoke.sh:523-529` |
| TC-SUB-62 | P0 | 名字落库 | 贴 `一号: <地址>` 与一条无名地址，再写入 | 有名字的写进 `custom_title`；无名字的那条 `custom_title` **为 NULL**（显示回落到站点 `<title>`，与 TC-SUB-52 同一条回落链）；两条都落进同一个 `group_name` | [SubscriptionService.java:183-186](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L183) · [SubscriptionBatchApiTest.java:100](../backend/src/test/java/com/rsshub/adminapi/SubscriptionBatchApiTest.java#L100) `[自]` `[C]smoke.sh:535-536` |
| TC-SUB-63 | P0 | 一次粘贴里的重复 | 同一地址贴两遍 | 第一条 `ready`、第二条 `duplicate` + `reason=already_in_paste`、`ready=1` ⇒ [添加 1 条] 只写一条 | [:243-245](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L243) · [SubscriptionBatchApiTest.java:115](../backend/src/test/java/com/rsshub/adminapi/SubscriptionBatchApiTest.java#L115) `[自]` |
| TC-SUB-64 | P0 | 已订阅的地址**不是**冲突 | 先批量写 A，再批量贴 `A\nB` | **200 而不是 409**（单条添加走的是 `already_subscribed` 冲突，见 TC-SUB-09）：`added:1`，A 行 `duplicate/already_subscribed`，B 行 `added`，表里两行 | [:246-249](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L246) · [SubscriptionBatchApiTest.java:127](../backend/src/test/java/com/rsshub/adminapi/SubscriptionBatchApiTest.java#L127) `[自]` `[C]smoke.sh:548-550` |
| TC-SUB-65 | P0 | 每条新订阅各自排队 | 批量写三条 | `scheduled_task` 三行 `kind='fetch'`，`dedupe_key=feed:{id}` 各不同；`fire_at` 依次相隔 **10 秒**，第一条不推迟（与 OPML 导入同法：两百条不能同时开两百个 socket） | [:187-189](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L187) · [SubscriptionBatchApiTest.java:142](../backend/src/test/java/com/rsshub/adminapi/SubscriptionBatchApiTest.java#L142) `[自]` `[C]smoke.sh:537` |
| TC-SUB-66 | P0 | 批量不触网 | 写一个没人监听的地址 | 照样 `added`，`requested_url = canonical_url =` 原样地址；抓取是队列 worker 的事，请求本身不等它（面板因此不会被一个不可达主机卡住） | [:166-169](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L166) · [SubscriptionBatchApiTest.java:156](../backend/src/test/java/com/rsshub/adminapi/SubscriptionBatchApiTest.java#L156) `[自]` |
| TC-SUB-67 | P0 | 私网地址逐行拒 | 贴 `http://192.168.7.7/inside.rss` + 一条正常地址 | 该行 `refused` 且 `reason` 以 `private_target` 开头（同一把 [SsrfGuard](../backend/src/main/java/com/rsshub/security/SsrfGuard.java#L1)，与单条添加不是两套规则）；写入端只落正常那条 | [:232-236](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L232) · [SubscriptionBatchApiTest.java:166](../backend/src/test/java/com/rsshub/adminapi/SubscriptionBatchApiTest.java#L166) `[自]` `[C]smoke.sh:526` |
| TC-SUB-68 | P0 | 非 http(s) 算"无效"不算"被拒" | 贴 `ftp://files.example/rss` | `status=invalid` + `reason=scheme_not_allowed`——协议不过与 SSRF 拦截在 UI 上是两个 pill，混成一个就没人知道该改哪儿 | [:226-231](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L226) · [SubscriptionBatchApiTest.java:182](../backend/src/test/java/com/rsshub/adminapi/SubscriptionBatchApiTest.java#L182) `[自]` `[C]smoke.sh:527` |
| TC-SUB-69 | P0 | 读不懂的行仍在表里 | 贴 `见备注` + 一条正常地址 | 两行都返回，`见备注` 那行 `status != ready` 且带着原始文本——操作员看得见"这条我没订"，不用猜少了几条 | [:206-253](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L206) · [SubscriptionBatchApiTest.java:193](../backend/src/test/java/com/rsshub/adminapi/SubscriptionBatchApiTest.java#L193) `[自]` `[C]smoke.sh:528` |
| TC-SUB-70 | P0 | 条数上限 | 贴 201 条 | **400** `urn:rsshub:too_many_lines`，且**半批都不写**（`feed`、`scheduled_task` 均 0 行）：计数在读完文本之后、写之前，`@Transactional` 之外也没有"先写一半" | [:211-214](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L211)（`MAX_BATCH_ITEMS` [:60](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L60)） · [SubscriptionBatchApiTest.java:204](../backend/src/test/java/com/rsshub/adminapi/SubscriptionBatchApiTest.java#L204) `[自]` `[C]smoke.sh:554-559` |
| TC-SUB-71 | P1 | 空粘贴与纯分隔符粘贴 | `{"text":"   "}`、`{"text":""}`、`{"text":",,;\n、"}` | 前两个 400 来自 `@NotBlank`（[FeedController.java:141-143](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L141)），第三个越过契约但读不出地址 ⇒ 400 `urn:rsshub:nothing_to_add`（[:209](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L209)）。**不给"新增了 0 条"的成功响应** | [SubscriptionBatchApiTest.java:219](../backend/src/test/java/com/rsshub/adminapi/SubscriptionBatchApiTest.java#L219) `[自]` |
| TC-SUB-72 | P0 | 未登录 | 匿名 `POST` 两个端点 | 两个都 4xx 且**库里零行**（本项目的匿名写先撞 CsrfFilter ⇒ 403，匿名读 401，口径见第 10 节与 TC-SUB-45；`[C]` 侧同样是 4 字头） | [SubscriptionBatchApiTest.java:235](../backend/src/test/java/com/rsshub/adminapi/SubscriptionBatchApiTest.java#L235) `[自]` `[C]smoke.sh:560-562` |

### 25.3 `SUB` 新增：面板上的按钮与表（10 条，全部 `[B]`）

控件清点（F16 之后，接第 5 节开头那句）：批量卡片 = **1 个折叠按钮（展开/收起）+ 1 个多行地址输入 + 1 个分组输入 + 3 个按钮（解析 / 添加 N 条 / 清空）+ 1 个逐行结果表（5 列）+ 1 个独立提示框**。

| 编号 | P | 控件 | 操作 | 预期结果 | 依据 / 现状 |
|---|---|---|---|---|---|
| TC-SUB-73 | P0 | [展开] / [收起] | 点 | `v-if="batchOpen"` 收起整张卡（不引 Bootstrap 的 JavaScript，所以没有 collapse 动画）；收起后若文本仍在，卡片头显示"已粘贴 N 行" | [FeedsView.vue:360-366](../web/src/views/FeedsView.vue#L360) `[B]` |
| TC-SUB-74 | P0 | 批量地址输入 | 贴一段文本 | `id="batch-text"` 用等宽字体（对齐靠它）；占位文案直接给两种写法（每行一个 / `名字: 地址`）；行数 `batchLines` 按 `/\r\n\|\r\|\n/` 数非空行 | [:372](../web/src/views/FeedsView.vue#L372) · [:199](../web/src/views/FeedsView.vue#L199) `[B]` |
| TC-SUB-75 | P0 | [解析] | 点 | `POST /api/feeds/batch/plan` → 逐行表渲染；`batchBusy` 期间按钮禁用并转 spinner；文本为空时禁用；无一条可加时提示转失败色但**表仍然显示**（要说清为什么不行） | [:386-389](../web/src/views/FeedsView.vue#L386) · [:236-255](../web/src/views/FeedsView.vue#L236) `[B]` |
| TC-SUB-76 | P0 | [添加 N 条] | 点 | 按钮文案里的 N **就是**后端给的 `ready` 计数（`batchReady`）；`ready===0` 时禁用 ⇒ 不存在"点下去写了 0 条还报成功" | [:391-393](../web/src/views/FeedsView.vue#L391) · [:233](../web/src/views/FeedsView.vue#L233) `[B]` |
| TC-SUB-77 | P0 | 逐行结果表 | 看 | 五列：行 / 名称 / 地址 / 结果 / 说明；五个判决各有文案与语义色——可添加 `text-bg-primary`、已添加 `success`、重复 `secondary`、被拒绝 `warning`、读不懂 `danger`；`reason` 原样跟在后面供报障 | [:400-429](../web/src/views/FeedsView.vue#L400) · [:202-208](../web/src/views/FeedsView.vue#L202) `[B]` |
| TC-SUB-78 | P1 | 补协议可视化 | 贴 `example.com/feed` | 原始文本下面另起一行 `→ https://example.com/feed`（只在 `url !== address` 时出现）：操作员知道自己订的是哪个地址，与 TC-SUB-38 的"搬过家"提示同一思路 | [:417-419](../web/src/views/FeedsView.vue#L417) `[B]` |
| TC-SUB-79 | P1 | 改文本即清上次解析 | 解析后再编辑文本 | `watch(batchText, resetBatchPlan)` 清表、清消息、清失败态 ⇒ 不会出现"上一次解析的结果还能点添加"（与 TC-SUB-48 同一口径） | [:231](../web/src/views/FeedsView.vue#L231) · [:225-229](../web/src/views/FeedsView.vue#L225) `[B]` |
| TC-SUB-80 | P1 | [清空] | 点 | 只清文本，不动分组、不动折叠态；文本为空时禁用 | [:395-396](../web/src/views/FeedsView.vue#L395) `[B]` |
| TC-SUB-81 | P0 | 两套提示互不串台 | 单条添加失败后紧接着批量成功 | 批量用自己的 `batchMessage`/`batchFailed`，单条用自己的 `message`/`failed`：上方的"抓取失败"不会被批量的"已订阅 N 条"顶掉，反之亦然（两条写路径回答的是两个问题） | [:188-194](../web/src/views/FeedsView.vue#L188) · [:430-434](../web/src/views/FeedsView.vue#L430) vs [:346](../web/src/views/FeedsView.vue#L346) `[B]` |
| TC-SUB-82 | P1 | 零写入时不吞文本 | 整批都是重复 → 点添加 | `added===0`：**保留文本**、把表刷成写入端的最终判决（`ready` 归 0）、提示转失败色"没有新订阅写入，N 条已存在或被拒绝"；`added>0` 才清空文本与表并 `load()` 重读列表 | [:266-276](../web/src/views/FeedsView.vue#L266) `[B]` |

### 25.4 第十二轮执行实录与勘误（F16：批量订阅，2026-11-03）

本轮的产物是功能，不是审计，所以勘误集中在**代码自己说的和做的不一致**、以及**新增代码把全仓行号推动**两类。

**执行门禁（全部本机重跑，日志留档）**

| 门禁 | 命令 | 结果 | 日志 |
|---|---|---|---|
| 后端全量 | `backend/mvnw -o -B -ntp verify` | **421 项 / 40 个类 / 0 失败 0 错误 / BUILD SUCCESS**（01:16 min）——比 F15 的 397 项多 24 项：`PastedSubscriptionsTest` 12 条 + `SubscriptionBatchApiTest` 12 条 | `.dev-logs/f16b-verify.log` |
| 真 HTTP 冒烟 | `bash .dev-logs/smoke-run.sh f16b` | **222 条 `PASS` / 0 条 `FAIL` / 末行 `ALL PASS`**，比上一轮 205 项多 17 项（全部是 F16 段），落在文档最高被引行 509 之后，既有 205 项引用不受影响 | `.dev-logs/f16b-smoke.log` |
| 引用完整性 | `node .dev-logs/f13-check2.js` | **1059 条** `文件#L行` 引用：**0 缺失、0 越界**，13 条 `NO-LABEL-HIT`（中文标签与英文代码行的语义差，逐条读过被指行，与 F13 基线同数）。数字含文档同步（02/03/04/README 补 F16 口径）新增的引用 | 本轮收尾 |
| 引用语义 | `node .dev-logs/f15-routecheck.js` | **57 条**带路由的引用（比 F15 多 3 条：`/api/feeds/batch`、`/api/feeds/batch/plan` 与 04 里那条），**0 条漂移** | 本轮收尾 |
| 前端 | `npm run build`（含 `vue-tsc`） | 94 模块、0 类型错误、CSS 322.72 kB（gzip 48.01）、JS 226.64 kB（gzip 75.44）、704 ms | `.dev-logs/f16-build.log` |

**文档同步之后又整体重跑了一遍**（F16-d 收尾，`.dev-logs/f16d-verify.log` 与 `.dev-logs/f16d-smoke.log`）：后端 **421 项 / 0 失败 / BUILD SUCCESS（01:06）**，冒烟换全新一次性库 `./.dev-logs/smoke-f16d` + 运行时自造口令再打一遍，**222 条 `PASS` / 0 条 `FAIL` / 末行 `ALL PASS`**，前端 94 模块、CSS 322.72 kB（gzip 48.01）、JS 226.64 kB（gzip 75.44）逐字节同上。这一步不是为了抓代码回归——本轮在 `f16b` 之后只动过文档——而是把文档里每个数字钉在**改完文档之后仍然成立**的那次运行上；数字没变，所以 1507–1509 三行的实测值同时适用于 `f16b` 与 `f16d` 两次。

一次性实例口径不变：全新库 `./.dev-logs/smoke-f16b`、口令 `openssl rand -hex 12` 运行时自造只经环境变量、`:19999` fixture 源由 `smoke-run.sh` 重启到 revision=1、`--rsshub.ssrf.allow-private-prefixes=127.` 只为放行它。开发库 `backend/data/rss-hub.mv.db` 全程未被读写。

**代码与注释不一致（1 处，改了注释并补了钉子）**

[PastedSubscriptions.java:118-121](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L118) 的 javadoc 原写"`either a dot **or** a path separator in it"，而 [:128](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L128) 的实现是 `indexOf('.') >= 0 && indexOf('/') >= 0`——**与**。这不是笔误级别的差别：按注释读，裸域名 `example.com` 该被当成地址；按实现读，它没有斜杠，会被 [:82-84](../backend/src/main/java/com/rsshub/adminapi/PastedSubscriptions.java#L82) 的"名字"规则吞成下一条地址的标签，粘贴 `example.com/feed` 这类**省略协议又恰好没有斜杠**的行会悄悄变成标签。两处都选了"实现为准、把行为钉住"：javadoc 改成"点**和**斜杠都要有"，并新增 [PastedSubscriptionsTest.java:116](../backend/src/test/java/com/rsshub/adminapi/PastedSubscriptionsTest.java#L116)（TC-SBP-11）断言裸域名**仍作为独立候选出现**、不当标签——它读不懂也没关系，但不能不出现（第 25 节第三条设计规则）。

**用例编号撞号（1 处，改测试不改文档）**

`SubscriptionBatchApiTest` 的 12 个 `@DisplayName` 最初按本地顺序写成 `TC-SUB-01…12`，而第 5 节早已把这些号发给单条订阅的用例——目录里同一编号会指向两套完全不同的东西。grep 确认 `TC-SUB-60` 是当时最大号、61 起空闲后，测试里的显示名整体改为 `TC-SUB-61…72`。教训与 F15 的 GAP 同类：**编号是先占先得的资源，落笔前查一次重复**（`grep -oE 'TC-[A-Z]+-[0-9]+' doc/test-plan.md | sort -u | tail`）。

**声明了但没证据（1 处，补断言）**

§25.2 一开始把 `nothing_to_add`（粘贴里只有分隔符、拆出零条）写成"服务会拒绝"，但没有任何测试真的发过这个请求——属于 F15 那轮定义的"标记声称有证据、仓库里找不到"。补进 [SubscriptionBatchApiTest.java:219](../backend/src/test/java/com/rsshub/adminapi/SubscriptionBatchApiTest.java#L219)（TC-SUB-71）：`,,;\n、` 过得了 `@NotBlank`（[:141](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L141)），但拆分器交出空列表，所以必须 400 且 `type` 是 `urn:rsshub:nothing_to_add`、库里零行——**空粘贴不能长成"订阅了 0 条 / 共 0 条"的成功**。

**冒烟首轮的假阴性**

F16 段第一版 `batch_write()` 只带了 cookie，没带 `X-XSRF-TOKEN` 头，于是"plan writes nothing"这类断言把 CSRF 的 403 当成了产品行为。附录 A 开头那条坑（匿名 CSRF 拒绝被 `ExceptionTranslationFilter` 表现成 401，见第 18 节）在这里换了个方向咬回来：**写端点的冒烟必须显式带双 token**，否则测到的是过滤器而不是功能。加上 `csrf()` 后 17 项全绿。

**行号漂移：F16 把全仓引用推动了一次**

本轮在 4 个文件里插了行（[FeedController.java](../backend/src/main/java/com/rsshub/adminapi/FeedController.java) +26、[feeds.ts](../web/src/api/feeds.ts) 两段、[FeedsView.vue](../web/src/views/FeedsView.vue) 批量区、[smoke.sh](../backend/smoke.sh) +53），文档里指向这些文件**插入点之后**的引用全部失配，跨 test-plan 与 02/03/04 四份文档共 210 处链接与标签。做法是拿 `git diff -U0 HEAD` 的 hunk 头算出每个文件的旧→新行偏移表，再一次性重写：[.dev-logs/f16-remap.js](../.dev-logs/f16-remap.js)（对 diff 结果做算术，不做文本匹配）。三个坑值得记，因为它们都会**安静地写错文档**：

| 坑 | 症状 | 修法 |
|---|---|---|
| 先写过一版按文本匹配的工具 | 把 `<td>` 那行映射成 `L421`，正确是 `L489`——文本相同但位置不同 | 弃用文本匹配，只信 hunk 算术 |
| 链接正则写成 `([\w.-]+)\?#L` | `\?` 是"必须有字面问号"，于是 `#Lnn` 锚点一个都没命中，首轮 122 处只改了中括号标签里的数字，锚点仍是旧的 | `git checkout -- doc` 回滚，改正则后重跑，共 210 处 |
| 插入型 hunk 的 off-by-one（`-135,0 +136,26`） | 旧 135 行被算成 161，指向了新代码内部 | `h.oldLen === 0 ? line <= h.oldStart : line < h.oldStart` |

外加一处同名歧义：`feeds.ts` 在 `api/` 与 `stores/` 各有一份，按 basename 匹配会把 `stores/feeds.ts:71-94` 改成 `api/feeds.ts` 的新行号。改成**按路径后缀唯一命中，命中不唯一就跳过并报告**，1 处歧义人工核对后保留。

这批重写无法用同一个工具自证归零（`f16-remap.js` 是一次性地对着 `HEAD` 的 diff 算的，文档已经改到新行号，再跑一次会二次平移），所以归零的证据是**两个常驻门禁**：`f13-check2.js` 的 0 缺失 / 0 越界（位置对不对）加 `f15-routecheck.js` 的 0 漂移（那一行说的是不是文档声称的那条路由）。提交后工作树干净，`git diff -U0 HEAD` 为空，届时重跑 remap 的空跑结果自然是零改动。

**本轮没有新增对外部规范的引用**（拆分与判决规则全部是本项目自己的口径），所以第 17 节勘误表不动。

**文档面同步（本轮的 F16-d 步骤）**：[01](./design/01-requirements.md) 的 FR-2.1 实况与 §7 DoD 的 F10 行各补一段、文档状态升到 v6，**行数仍是 210 行**（该文件被 44 处 `#L` 引用，是契约）；[02](./design/02-architecture.md) 的 `admin-api` 行加两个批量端点与"拆分器是纯文本、批量不触网，所以边界规则 2 不破"，`feedfetch` 行的 `SubscriptionService` 行号随本轮位移重核；[03](./design/03-technical-design.md) 新增 **§4.5 粘贴清单的拆分**（分隔符表、冒号的两种身份、"读不懂的行仍出一条候选"、`normalise` 收口、上限与空粘贴的判决位置）、§10 加两行端点契约并把"拆分规则"指向 §4.5、§11 加"批量面板"一段（六个控件 + 五个绑在后端契约上的口径）、§13 的测试口径 397→421 / 38→40 类并记下"`*.txt` 之和是 376、差额 45 在三个 `@Nested` 类里"这条数数坑、§14 新增 **O26**（批量面板 `[B]` 面）与 **O27**（批量判重是字符串相等）；[04](./design/04-roadmap.md) 里程碑表加 F16 行、合计 41.9→42.1 人日、新增 F16 小节（五个决定与各自代价）并把尾部总口径改到 421 / 222 / 461；[README](../../README.md) 的能力表、`mvnw`/`smoke-run.sh` 注释、用例数与门禁实测值同步。

**未在浏览器复核**：TC-SUB-73…82 十条全部是 `[B]`，本轮只由 `vue-tsc` 的类型检查与上面的构建产物证明它们能编译、能打包，**没有肉眼在浏览器里点过**折叠按钮、[解析]、[添加 N 条]、[清空] 和逐行结果表的实际渲染。这一类与既有的 `[B]` 用例同样待人工验收。

---

## 附录 A：`[C]` curl 冒烟序列（口令只从环境变量进）

> v2 修订：登录必须先有一次 GET 拿到 `XSRF-TOKEN`，再把 token 放进 `X-XSRF-TOKEN` 头。原脚本直接 POST 登录，
> 匿名态下的 CSRF 拒绝会被 `ExceptionTranslationFilter` 表现成 **401**（[SecurityConfig.java:86-87](../backend/src/main/java/com/rsshub/security/SecurityConfig.java#L86)），
> 看起来像"口令错"。这条坑在 2026-10-18 第二轮执行时踩到，实测结论见第 18 节。

```bash
# 前置：backend/dev.env 里有 RSSHUB_OPERATOR_PASSWORD；绝不把口令写进脚本或本文。
set -a; . backend/dev.env; set +a
BASE=http://127.0.0.1:8080
J=.dev-logs/cookies.txt; rm -f $J

# 1) 匿名基线：/me 必须 200 且 authenticated:false；这一步顺带落下 XSRF-TOKEN，也证明匿名不建会话
curl -s -c $J $BASE/api/auth/me
TOK=$(awk '/XSRF-TOKEN/ {print $7}' $J)

# 2) 登录（表单编码 + CSRF 头）。登录会同时轮换会话 id 与 token，所以两个都要重读
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -X POST $BASE/api/auth/login \
  --data-urlencode "username=${RSSHUB_OPERATOR_USER:-admin}" \
  --data-urlencode "password=${RSSHUB_OPERATOR_PASSWORD}"
TOK=$(awk '/XSRF-TOKEN/ {print $7}' $J)
curl -s -b $J $BASE/api/auth/me          # authenticated:true 才算登录成功

# 3) 订阅：分组字段是 group，不是 groupName（[FeedController.java:47-49](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L47)）
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X POST $BASE/api/feeds -d '{"url":"https://example.com/feed.xml","group":"Smoke"}'

# 4) 抓取观测：refresh 只回 202=入队，要轮询状态；列表参数是 feed 而不是 feedId
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -X POST $BASE/api/feeds/1/refresh
for _ in $(seq 1 20); do sleep 1; curl -s -b $J $BASE/api/feeds/1 | grep -q '"entryCount":[1-9]' && break; done
curl -s -b $J $BASE/api/feeds/1                        # lastHttpStatus / entryCount / lastChangeAt
curl -s -b $J "$BASE/api/entries?state=all&feed=1"      # 新订阅首批条目 baseline=true，所以 unread 计数为 0
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -X POST $BASE/api/feeds/1/refresh
curl -s -b $J $BASE/api/feeds/1 | grep -o '"lastHttpStatus":[0-9]*'   # 源没变 → 304

# 5) 发文：正文一律走 UTF-8 文件（GBK 控制台会弄坏内联中文）；状态变更是 PATCH，时间字段叫 publishedAt
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X POST $BASE/api/posts --data-binary @.dev-logs/smoke-post.json
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X PATCH $BASE/api/posts/1/status \
  -d '{"status":"scheduled","publishedAt":"2026-11-01T00:00:00Z"}'

# 6) 三个出口 + 严格解析（StrictXmlCheck 见第 18 节，命名空间敏感且拒绝外部实体）
for f in rss atom json; do
  curl -s -D- -o .dev-logs/feed.$f $BASE/feed.$f | grep -iE 'HTTP/|content-type|etag|cache-control|last-modified'
done
java .dev-logs/StrictXmlCheck.java .dev-logs/feed.rss .dev-logs/feed.atom
node -e 'JSON.parse(require("fs").readFileSync(".dev-logs/feed.json","utf8")); console.log("json-ok")'

# 7) 条件请求（弱比较：带 W/ 与不带都命中，`*` 命中，逗号列表命中，跨格式不命中）
E=$(curl -s -D- -o /dev/null $BASE/feed.rss | awk -F': ' 'tolower($1)=="etag"{print $2}' | tr -d '\r')
curl -s -o /dev/null -w 'etag=%{http_code}\n'         -H "If-None-Match: $E" $BASE/feed.rss
curl -s -o /dev/null -w 'strong=%{http_code}\n'       -H "If-None-Match: ${E#W/}" $BASE/feed.rss
curl -s -o /dev/null -w 'star=%{http_code}\n'         -H 'If-None-Match: *' $BASE/feed.rss
curl -s -o /dev/null -w 'list=%{http_code}\n'         -H "If-None-Match: \"x\", $E" $BASE/feed.rss
curl -s -o /dev/null -w 'other-format=%{http_code}\n' -H "If-None-Match: $E" $BASE/feed.atom

# 8) 匿名不得建会话：这个 jar 里不应出现 RSSHUB_SESSION
curl -s -c .dev-logs/anon.txt -o /dev/null $BASE/feed.rss && grep -c RSSHUB_SESSION .dev-logs/anon.txt

# 9) 负例（TC-API-03 / TC-PUB-43 / TC-SEC-01/06）——写接口都要带 CSRF，否则测到的是 401-CSRF
curl -s -o /dev/null -w 'unauth=%{http_code}\n' $BASE/api/feeds
curl -s $BASE/feed.opml | head -c 200
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X POST $BASE/api/feeds -d '{"url":"http://169.254.169.254/latest/meta-data/"}'
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X POST $BASE/api/feeds -d '{"url":"file:///etc/passwd"}'

# 10) 聚合再分发（FR-7.1/7.2，M5；与 smoke.sh 的 M5 段同一套断言）
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X POST $BASE/api/repacks -d '{"slug":"weekly-picks","title":"Weekly picks"}'      # 201，slug 会被小写化
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X POST $BASE/api/repacks/weekly-picks/feeds -d '{"feedId":1}'                       # 重复 → 409，未知 feed → 404
curl -s $BASE/repack/weekly-picks.rss | grep -o '<source url="[^"]*"'                  # 匿名可读，每条带原出处
curl -s $BASE/repack/weekly-picks.rss | grep -c 'content:encoded'                       # 0：默认只转发摘要
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X PATCH $BASE/api/repacks/weekly-picks/feeds/1 -d '{"fullContent":true}'             # 显式开全文
curl -s $BASE/repack/weekly-picks.rss | grep -c 'content:encoded'                       # ≥1：全文只在开之后出现
# 前提：这个源自己发过 content:encoded（`entry.content_html` 非空）。只发 description 的源，
# 全文开关怎么看都是 0——所以 smoke.sh 用的是会发 `<content:encoded>` 的 fixture 源，见 21.3。
curl -s -b $J $BASE/api/repacks/weekly-picks/audit                                      # add + full_content_on
curl -s -b $J -c $J -o /dev/null -w 'del=%{http_code}\n' -H "X-XSRF-TOKEN: $TOK" -X DELETE $BASE/api/repacks/weekly-picks
curl -s -o /dev/null -w 'outlet=%{http_code}\n' $BASE/repack/weekly-picks.rss           # 404，集合没了出口就没了

# 11) 变更公告（FR-1.6，F1.6；与 smoke.sh 的 F1.6 段同一套断言）
curl -s -o /dev/null -w 'api=%{http_code}\n' $BASE/api/changelog                        # 401：历史是内部的
curl -s -o /dev/null -w 'write=%{http_code}\n' -b $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X POST $BASE/api/changelog -d '{"action":"created"}'                                 # 405：只读，没有写入面
PID=$(curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X POST $BASE/api/posts -d '{"title":"Changelog probe","bodyMd":"Body one.","status":"draft"}' \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
# 此刻公告里不该有它——草稿不公开，变更行一条都不写（TC-CHG-04 的前半）
curl -s $BASE/feed/changelog.rss | grep -c 'Changelog probe'                            # 0
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X PATCH $BASE/api/posts/$PID/status -d '{"status":"published"}'                       # 发布一个草稿
curl -s $BASE/feed/changelog.rss | grep -o 'Published: Changelog probe'                 # 出现的是状态词，不是 "New post:"
curl -s $BASE/feed/changelog.rss | grep -o '<guid isPermaLink="false">urn:rsshub:change:[^<]*'
CTAG=$(curl -s -D - -o /dev/null $BASE/feed/changelog.rss | tr -d '\r' | sed -n 's/^[Ee][Tt]ag: //p')
curl -s -o /dev/null -w '304=%{http_code}\n' -H "If-None-Match: $CTAG" $BASE/feed/changelog.rss   # 304
for f in rss atom json; do curl -s -o /dev/null -w "$f=%{http_code} " $BASE/feed/changelog.$f; done
curl -s -o /dev/null -w 'opml=%{http_code}\n' $BASE/feed/changelog.opml                  # 404 urn:rsshub:unknown_format
curl -s -c .dev-logs/clg-anon.txt -o /dev/null $BASE/feed/changelog.json
grep -c RSSHUB_SESSION .dev-logs/clg-anon.txt                                            # 0：出口不发会话
curl -s $BASE/blog | grep -o '/feed/changelog\.rss'                                      # 公开页自我宣告
curl -s -b $J "$BASE/api/changelog?limit=0" | head -c 200                                # 不报错，夹到 1 条
curl -s -b $J "$BASE/api/changelog?postId=$PID" | grep -o '"action":"status"'           # 只有 1 行：草稿期不写，发布才写（TC-CHG-04）
curl -s -b $J "$BASE/api/changelog?postId=999999"                                        # []

# 12) 收件箱过滤（FR-2.4，F2.4；与 smoke.sh 的 F2.4 段同一套断言）
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X PATCH $BASE/api/events/events/visibility -d '{"visibility":"virtual_feed"}'        # 把默认事件流翻进收件箱
curl -s -H "Authorization: Bearer $SECRET" -H 'Content-Type: application/json' \
  -X POST $BASE/api/ingest/events \
  -d '{"title":"Filter probe","dedupeKey":"probe-1","tags":["SmokeTag","ops"]}'         # 一条调用同时给出标签行与命中行
# $SECRET 是 [smoke.sh:104](../backend/smoke.sh#L104) 那次 POST /api/tokens 响应的明文，只在签发那一次可读；本文不写任何令牌值
n() { grep -o '"id":' | wc -l | tr -d ' '; }                                            # smoke.sh 里的 count 同义
curl -s -b $J "$BASE/api/entries?state=all" | grep -F '"categories":["SmokeTag","ops"]' >/dev/null && echo tagged
curl -s -b $J "$BASE/api/entries?state=all&category=SmokeTag" | n                       # 1：整元素命中
curl -s -b $J "$BASE/api/entries?state=all&category=ops" | n                             # 1：第二个元素也算命中
curl -s -b $J "$BASE/api/entries?state=all&category=Smoke" | n                           # 0：前缀不是该标签
curl -s -b $J "$BASE/api/entries?state=all&alerted=true" | grep -F '"alertedBy":['       # 过滤与红标同源
curl -s -b $J "$BASE/api/entries?state=all&alerted=true" | n                             # 1（本库里有 markOnly 命中行时）
curl -s -o /dev/null -w 'bad=%{http_code}\n' -b $J "$BASE/api/entries?state=all&alerted=maybe"   # 400 urn:rsshub:bad_alerted
# 两条口径提醒：① 计数一律数 `"id":` 的出现次数——`counters.*` 是全站数，**不跟过滤走**（TC-INB-47），拿它当"筛出几条"必错；
# ② JSON 数组的针必须用 `grep -F`——`grep '"categories":['` 会被读成字符集合，永远匹配不上（23.3 实录的第一条坑）。

# 13) 每页条数由设置决定（FR-9.2 的补账，2026-11-01；与 smoke.sh 的 F2.4 尾段同一套断言）
ALL=$(curl -s -b $J "$BASE/api/entries?state=all" | n)              # 先记下"不带 limit 时一页几条"
curl -s -o /dev/null -w 'patch=%{http_code}\n' -b $J -c $J -H "X-XSRF-TOKEN: $TOK" \
  -H 'Content-Type: application/json' -X PATCH $BASE/api/settings -d '{"inbox.pageSize":10}'
curl -s -b $J "$BASE/api/entries?state=all" | n                     # min(ALL,10)：请求里没有 limit，页数却跟着设置变了
curl -s -b $J "$BASE/api/entries?state=all" | grep -c nextCursor    # ALL>10 → 1（装不下的行交给游标）；ALL≤10 → 0
curl -s -b $J "$BASE/api/entries?state=all" | sed -n 's/.*"total":\([0-9]*\).*/\1/p'   # 徽标仍是全站数，不跟着缩
curl -s -o /dev/null -w 'restore=%{http_code}\n' -b $J -c $J -H "X-XSRF-TOKEN: $TOK" \
  -H 'Content-Type: application/json' -X PATCH $BASE/api/settings -d '{"inbox.pageSize":50}'
curl -s -b $J "$BASE/api/entries?state=all" | n                     # 还原后页数回到 $ALL
# 口径：这条断言只在收件箱本来就装得下 10 条以上时才看得见"缩小"。smoke.sh 用 min(ALL,10) 让期望值跟着数据走，
# 所以它在空库上既不空过也不误报；手工跑时照抄上面第一行，别把 10 当成绝对期望（23.4 节记了这件事）。

# 15) 订阅就地编辑与立即抓取（F10，第 24.1 节；与 smoke.sh 的 F10 段同一套断言，见 [smoke.sh:356-435](../backend/smoke.sh#L356)）
# 中文标题必须走文件：Windows 原生 curl.exe 的 argv 按控制台代码页递交，-d '{"title":"中文"}' 到服务端已不是 UTF-8 字节。
# 纯 ASCII 的 body 用 -d 没问题——smoke.sh 自己也是这么分的（[smoke.sh:360-366](../backend/smoke.sh#L360) 走文件，[:400-403](../backend/smoke.sh#L400) 走 -d）。
printf '%s' '{"title":"我的源名"}' > .dev-logs/f10-patch.json
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X PATCH $BASE/api/feeds/1 --data-binary @.dev-logs/f10-patch.json | grep -o '"customTitle":"[^"]*"'
# 两个标题列各归各的主：customTitle 是操作者改的那半，title 那半下一份文档会刷新（F-05 收口，见 18 节）
curl -s -b $J $BASE/api/feeds/1 | grep -o '"title":"[^"]*"'
curl -s -b $J "$BASE/api/entries?state=all&feed=1" | grep -o '"feedTitle":"[^"]*"' | head -1   # 收件箱跟着改名（TC-SUB-51）
curl -s -b $J $BASE/api/health/feeds | grep -o '"title":"[^"]*"' | head -1                       # 健康度同一个名字（TC-SUB-51）
# 地址改不动：不是被静默忽略，是 400 + urn:rsshub:unknown_field（TC-SUB-54；白名单只有 title/group/state/intervalMinutes）
curl -s -o /dev/null -w 'url-edit=%{http_code}\n' -b $J -c $J -H "X-XSRF-TOKEN: $TOK" \
  -H 'Content-Type: application/json' -X PATCH $BASE/api/feeds/1 -d '{"canonicalUrl":"https://other.example/feed.xml"}'
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X PATCH $BASE/api/feeds/1 -d '{"canonicalUrl":"https://other.example/feed.xml"}' | grep -o '"type":"[^"]*"'
# 间隔的四态：负数与超过 30 天被拒，43200 是天花板本身，15 写进操作者自己的列（[SubscriptionService.java:47,283-286](../backend/src/main/java/com/rsshub/adminapi/SubscriptionService.java#L283)）
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X PATCH $BASE/api/feeds/1 -d '{"intervalMinutes":-1}' | grep -o '"type":"[^"]*"'      # urn:rsshub:bad_interval
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X PATCH $BASE/api/feeds/1 -d '{"intervalMinutes":15}' | grep -o '"fetchIntervalMinutes":15'
# 立即抓取：202 只代表入队，真正的手感是 nextFetchAt 被拉到当前时刻，抓完再按覆盖值重新武装（TC-SUB-40）
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -X POST $BASE/api/feeds/1/refresh              # {"queued":true,"id":1}
for _ in $(seq 1 20); do sleep 1; curl -s -b $J $BASE/api/feeds/1 | grep -q '"entryCount":[1-9]' && break; done
curl -s -b $J $BASE/api/feeds/1 | grep -o '"nextFetchAt":"[^"]*"'                          # 此刻落在 15 分钟 + 抖动之内
# 页面占位不能写死：节奏读策略端点，本机默认值是 60/5（D-04 收口，[FeedController.java:60-63](../backend/src/main/java/com/rsshub/adminapi/FeedController.java#L60)）
curl -s -b $J $BASE/api/feeds/policy | grep -o '"defaultIntervalMinutes":[0-9]*,"minIntervalMinutes":[0-9]*'
# 交还控制权用的是"空"，不是"删除"：0 清间隔覆盖、空串清标题，列还在、值变 NULL，站点那半重新生效（TC-SUB-56 间隔、TC-SUB-52 标题）
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X PATCH $BASE/api/feeds/1 -d '{"intervalMinutes":0}' | grep -c '"fetchIntervalMinutes"'  # 0：字段整个缺席
printf '%s' '{"title":""}' > .dev-logs/f10-patch.json
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X PATCH $BASE/api/feeds/1 --data-binary @.dev-logs/f10-patch.json | grep -c '"customTitle"'   # 同上；随后 feedTitle 回落成站点的 <title>

# 16) 大屏的一次载荷与交叉核对（F11，第 24.2 节；与 smoke.sh 的 F11 段同一套断言，见 [smoke.sh:436-487](../backend/smoke.sh#L436)）
W=$(curl -s -b $J $BASE/api/dashboard)
# 柱数不随数据抖：恒定 24 个桶，旧在前（WINDOW_HOURS=24，[DashboardController.java:40,120](../backend/src/main/java/com/rsshub/adminapi/DashboardController.java#L40)）
printf '%s' "$W" | grep -o '"arrivals":' | wc -l | tr -d ' '                               # 24
printf '%s' "$W" | grep -o '"hour":"[^"]*"' | sed 's/.*:"//; s/"$//' | sed -n '1p;$p'      # 首尾相差 23 小时
# 柱高之和 = 标量区的"今日到达"：屏上两个数必须讲同一个故事，否则读者会挑错的那个信
printf '%s' "$W" | grep -o '"arrivals":[0-9]*' | cut -d: -f2 | awk '{s+=$1} END{print s+0}'
printf '%s' "$W" | sed -n 's/.*"stats":{\([^}]*\)}.*/\1/p'   # 十个标量：feeds/entries/unread/publishedPosts/arrivedToday/hitsToday/deliveredToday/failedToday/problemFeeds/quietFeeds
# 每个标量都对回它声称汇总的那个页面，而不是对回抄进脚本的数字：
curl -s -b $J "$BASE/api/entries?state=all" | sed -n 's/.*"counters":{\([^}]*\)}.*/\1/p'   # 条目总数与未读与收件箱同源
printf '%s' "$W" | grep -o '"failCount":' | wc -l | tr -d ' '                              # 一张卡一个订阅（= stats.feeds）
curl -s -b $J $BASE/api/notifications | grep -o '"channel":' | wc -l | tr -d ' '           # 命中数 = 投递日志行数
# 三条轨道各有各的口径：告警带规则名与条目名，发布轨只收"曾经公开"的，变更轨是历史——删掉的帖子仍在后者上
printf '%s' "$W" | sed -n 's/.*"alerts":\[\(.*\)\],"posts".*/\1/p'
printf '%s' "$W" | sed -n 's/.*"posts":\[\(.*\)\],"changes".*/\1/p'
printf '%s' "$W" | sed -n 's/.*"changes":\[\(.*\)\]}\?$/\1/p'
printf '%s' "$W" | grep -o '"name":"[^"]*"'                                                # 分组轨用的是操作者自己的标签（第 15 段那次 PATCH 之后）
# 刷新周期是设置项，0 合法（[SettingsService.java:35](../backend/src/main/java/com/rsshub/settings/SettingsService.java#L35)：min 0 / max 600 / 默认 60）；
# 注意 GET 的响应是驼峰、PATCH 的 body 是点号键，两边写法不一样，手工复现时最容易在这里 400
curl -s -b $J $BASE/api/settings | grep -o '"dashboardRefreshSeconds":[0-9]*'
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' \
  -X PATCH $BASE/api/settings -d '{"dashboard.refreshSeconds":0}'
curl -s -b $J $BASE/api/dashboard | grep -o '"refreshSeconds":0'                           # 页面据此显示"自动刷新已关闭"（TC-DSH-01 说明行 / TC-DSH-08 定时器）

# 16.5) 术语切片与独立计数路由（F15，第 24.8 节；与 smoke.sh 的 F15 段同一套断言，见 backend/smoke.sh:490-509）
#   先造一篇生来就带标签和分类的文章，再匿名打开四个切片 URL：切片的价值是"只含这一篇"，不是"能返回 200"
P=$(curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' -X POST $BASE/api/posts \
     --data '{"title":"Smoke outlet post","bodyMd":"Tagged at birth.","status":"published","tags":["Smoke Outlet"],"categories":["Smoke Notes"]}')
curl -s -o /dev/null -w 'tag=%{http_code}\n'      $BASE/tag/smoke-outlet.rss                                   # 200，匿名
curl -s $BASE/tag/smoke-outlet.rss | grep -c '<title>Smoke outlet post</title>'                                 # 1：切片只含本文
curl -s $BASE/tag/smoke-outlet.rss | grep -c '<title>Smoke Outlet · '                                           # 1：<title> 用 term.title 而不是 slug
curl -s -o /dev/null -w 'cat=%{http_code}\n'      $BASE/category/smoke-notes.atom                               # 200
curl -s $BASE/tag/smoke-outlet.json | grep -c '"title":"Smoke outlet post"'                                    # 1：json 切片同一条
curl -s -o /dev/null -w 'missing-tag=%{http_code}\n'  $BASE/tag/no-such-smoke-tag.rss                           # 404：术语不存在是"站点没这个 URL"
curl -s -o /dev/null -w 'missing-cat=%{http_code}\n'  $BASE/category/no-such-smoke-category.json                # 404
# 收件箱角标走的是一条独立路由，页面内嵌的 counters 与它必须同源（前端在 entries.ts:204 轮询它）
curl -s -b $J $BASE/api/entries/counters
curl -s -b $J "$BASE/api/entries?state=all" | sed -n 's/.*"counters":{\([^}]*\)}.*/\1/p'                         # 两处的 total 必须相等
curl -s -o /dev/null -w 'anon-counters=%{http_code}\n' $BASE/api/entries/counters                               # 401

# 16.6) 批量订阅（F16，第 25 节；与 smoke.sh 的 F16 段同一套断言，见 [smoke.sh:511-563](../backend/smoke.sh#L511)）
#   这段粘贴一次考满拆分器的四种读法：`名字: 地址` × 逗号并列、回车、内网地址、非 http 协议、整行读不懂
BODY=smoke-batch.json                                                          # 落在当前目录，跑完手工 rm
printf '{"text":"甲: http://127.0.0.1:19999/feed.xml?b=1, http://127.0.0.1:19999/feed.xml?b=2\\nhttp://192.168.9.9/in.rss\\nftp://127.0.0.1:19999/f\\n见备注","group":"批量"}' > $BODY
B=$(curl -s -b $J $BASE/api/feeds | grep -o '"id":' | wc -l)                                                     # 写之前的订阅数
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' -X POST $BASE/api/feeds/batch/plan \
  --data-binary @$BODY | sed -n 's/.*"ready":\([0-9]*\).*/ready=\1/p'                                             # ready=2：只有两条可加
#   同一份响应里还应有 "status":"refused" + private_target、"reason":"scheme_not_allowed"、"address":"见备注"
curl -s -b $J $BASE/api/feeds | grep -o '"id":' | wc -l                                                           # 仍是 $B：plan 一行都不写
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' -X POST $BASE/api/feeds/batch \
  --data-binary @$BODY | sed -n 's/.*"added":\([0-9]*\).*/added=\1/p'                                             # added=2
curl -s -b $J "$BASE/api/feeds" | grep -c '"customTitle":"甲"'                                                    # 1：名字写在地址前面就成了订阅名（GET /api/feeds 没有过滤参数，整表里数）
sleep 12; curl -s -b $J "$BASE/api/feeds" | grep -c '"lastHttpStatus":200'                                        # ≥1：错开 10 s 抓取，批量不发请求也能入库
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' -X POST $BASE/api/feeds/batch/plan \
  --data-binary @$BODY | grep -o '"reason":"already_subscribed"' | wc -l                                          # 2：第二次整批都是重复，仍返回 200
BIG=smoke-batch-big.json; { printf '{"text":"'; for _i in $(seq 1 201); do printf 'http://127.0.0.1:19999/feed.xml?n=%s\\n' "$_i"; done; printf '"}'; } > $BIG
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $TOK" -H 'Content-Type: application/json' -X POST $BASE/api/feeds/batch \
  --data-binary @$BIG -o /dev/null -w 'cap=%{http_code}\n'                                                        # 400：超 200 条整批拒绝，不写半批
curl -s -o /dev/null -w 'anon-batch=%{http_code}\n' -H 'Content-Type: application/json' -X POST \
  $BASE/api/feeds/batch --data-binary @$BODY | cut -c1                                                            # 4：匿名写先撞 CsrfFilter（TC-SUB-72）
rm -f $BODY $BIG

# 17) 登出后复用（TC-AUTH-10/11）
curl -s -o /dev/null -w 'logout=%{http_code}\n' -b $J -H "X-XSRF-TOKEN: $TOK" -X POST $BASE/api/auth/logout
curl -s -o /dev/null -w 'after-logout=%{http_code}\n' -b $J $BASE/api/feeds
```

脚本里**没有**任何口令字面量；缺 `RSSHUB_OPERATOR_PASSWORD` 时第 2 步就 401，不会退化默认值。

---

## 附录 B：用例统计

| 节 | 前缀 | 条数 | 其中 P0 |
|---|---|---|---|
| 1 登录与会话 | `AUTH` | 23 | 15 |
| 2 导航 | `NAV` | 7 | 0 |
| 3 收件箱 | `INB` | 46 | 19 |
| 4 阅读面板 | `RDR` | 19 | 10 |
| 5 + 25.2 + 25.3 订阅管理（含 F16 批量：61…72 接口 / 73…82 按钮） | `SUB` | 78 | 50 |
| 6 健康度 | `HEA` | 16 | 7 |
| 7 文章编辑 | `PST` | 37 | 25 |
| 8 公开出口 | `PUB` | 35 | 23 |
| 9 抓取行为 | `FET` | 25 | 17 |
| 10 接口负例 | `API` | 11 | 5 |
| 11 安全 | `SEC` | 12 | 9 |
| 11 可迁移 | `MIG` | 4 | 2 |
| 19 规则/投递/事件/二次验证（M3-d，含 24.8 的 TC-RUL-20） | `RUL`/`NTF`/`EVT`/`TOT` | 48 | 32 |
| 20 大屏/参数/状态（M3.5，含 24.2 的 DSH-11…20） | `DSH`/`SET`/`SYS` | 36 | 27 |
| 21 聚合再分发（M5） | `RPL` | 17 | 14 |
| 22 变更公告（F1.6） | `CHG` | 22 | 9 |
| 24.3 界面令牌与外壳（F12 立，F13 重写，F14 加三条"框架在哪里取色"） | `UIF` | 13 | 10 |
| 25.1 粘贴拆分器（F16） | `SBP` | 12 | 6 |
| — | 合计 | **461** | **280** |

> 计数由机器实测得出（2026-11-03 第十二轮 F16 之后）。**口径仍是严格式**：`grep -cE '^\| TC-[A-Z]+-[0-9]+[a-z]? \| P[012] \|' doc/test-plan.md` = **461** 行，P0 列同法 = **280**。查重也限定义行，别对全文编号做 `uniq -d`：`grep -oE '^\| TC-[A-Z]+-[0-9]+[a-z]? \| P[012] \|' doc/test-plan.md | awk -F'|' '{print $2}' | sort | uniq -d` **零输出**（461 行 ↔ 461 个不同编号）。全文去重会假报 6 条（`TC-NAV-01/02/04`、`TC-UIF-01/02/07`），那是 24.5/24.6 的实录表在行首复述既有编号——它们是引用，不是第二条用例。`TC-AUTH-20` 与 `TC-AUTH-20b` 是两条，别用前缀匹配去重。
>
> 为什么换口径：宽松式 `grep -cE '^\| TC-[A-Z]+-[0-9]+[a-z]?'` 现在会数到 **468**——24.5/24.6/24.7/24.8/25.4 五节"执行实录"里的说明表也在行首复述用例编号（如 24.8 的"TC-PUB-40/41/42 的 `[C]`"行），那些不是用例行。严格式多要求"下一列必须是 P0/P1/P2"，只命中真表；以后加实录表不会再污染计数。
>
> 本轮（F16）新增 34 条：`SBP` 12 条/6 P0（拆分器，全 `[自]`）+ `SUB` 22 条/17 P0（12 条接口 + 10 条按钮），合计 427 → **461**、P0 257 → **280**。`SUB` 前缀整体实测 **78 条/50 P0**（第 5 节的 56/33 加 25.2 的 12/7 加 25.3 的 10/10）。此外先前几轮数错的格子仍按第九轮勘误保留：第 20 节 `DSH`/`SET`/`SYS` 的 P0 是 **27**（DSH 17 + SET 6 + SYS 4）；第 24.3 节 `UIF` 是 **13 条/10 P0**。逐前缀机器实测：AUTH 23/15 · INB 46/19 · RDR 19/10 · NAV 7/0 · SUB 78/50 · SBP 12/6 · HEA 16/7 · PST 37/25 · PUB 35/23 · FET 25/17 · API 11/5 · SEC 12/9 · MIG 4/2 · RUL 20/13 · NTF 7/3 · EVT 12/9 · TOT 9/7 · DSH 20/17 · SET 9/6 · SYS 7/4 · RPL 17/14 · CHG 22/9 · UIF 13/10 = **461/280**。旧版"249"是 M1 末口径、"331/194"是 M3.5、"348/208"是 M5、"370/217"是 F1.6、"390/228"是 F2.4、"423/255"是 F12、"426/257"是 F13/F14、"427/257"是 F15，均已过时。
>
> 撞号事故与修法记在 25.4：`SubscriptionBatchApiTest` 的 `@DisplayName` 一度占用 `TC-SUB-01…12`，与第 5 节同号，测试侧已整体改为 `TC-SUB-61…72`；**编号是先占先得的资源，落笔前先 `grep -oE 'TC-[A-Z]+-[0-9]+' doc/test-plan.md | sort -u | tail`**。

另有第 12 节 12 条阻塞项（不设为用例）、第 13 节 7 条口径差异（先裁定再测）。

