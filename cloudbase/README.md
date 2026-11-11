# 小故事 CloudBase 服务端

## 已有上海 PG 环境接入进度（2026-11-10）

用户已完成官方CLI授权。已在其现有体验环境开通 pgdoc，五个业务集合均通过管理API确认 **ADMINONLY**，五个计划索引已实际存在；未新建/替换环境，也未升配或开启付费。当前还没有业务云函数、公开路由或应用管理员账号，不能开始真实投稿。实际进度以根目录STATUS的CLOUD-002为准，下面未配置/仅Mock描述属于原离线交付阶段。

**PG兼容方式：**官方文档明确支持 Node SDK v3 `/admin` 与事务，所以不因PG模式重写业务层。pgdoc **不支持 CUSTOM 安全规则**；当前统一使用基础权限“无权限／ADMINONLY”，含已发布集合。不要对PG环境照搬 `{read:false,write:false}` 自定义规则并误以为已生效，也不要用JSON规则放开直读。原初始化脚本的建集合/JSON规则流程面向传统模式；本环境集合已由CLI管理API以ADMINONLY创建，不重复执行。业务计数初始化、真实SDK事务及客户端拒绝仍须后续验收。

[官方pgdoc使用说明](https://docs.cloudbase.net/database/postgresql/pg-doc-usage) · [开通API及费用](https://cloud.tencent.com/document/api/876/140402) · [ADMINONLY权限](https://cloud.tencent.com/document/api/876/34819)。


已实现投稿 → 待审 → 审核发布 → 公开分页/正文 → 撤下。生产与本机 Mock 共用 `src/service.js`；Android 和审核页按 `CONTRACT.md` 调用。此目录不包含已经开通的云服务。

## 当前状态

- 真实环境 ID、地域、认证域名、管理员 UID、后台域名均**未配置**；`.env` 不存在，模板空白不是生产配置。
- 未创建云环境/集合，未部署，未开通付费，未安装全局 CLI。只安装源码所需的唯一直接生产依赖 `@cloudbase/node-sdk@3.18.7`，`package-lock.json` 固定完整依赖树；其数据库包为 `@cloudbase/database@1.4.3`。
- 本机 Node v24.19.0 运行测试。生产配置为 Nodejs20.19；实际该运行时、云端事务/规则/索引、正式身份认证、域名与费用仍待已有环境实测。
- 不修改 Android、根文档、Git、`cloudbase/admin/`、`CONTRACT.md`。本会话测试证据依写入边界放在 **cloudbase/verification/story-cloud-20261110/backend/**，供主会话归并到项目 verification 同名目录。

## 本机运行

在 `E:\Desktop\CodexProjects\CampusLifeAssistant\cloudbase` 执行：

```powershell
npm ci --ignore-scripts --no-audit --no-fund
npm test
npm run mock
```

- 固定监听 `127.0.0.1:8787`，不会绑定局域网网卡。审核页从 `cloudbase/admin/` 静态提供：[本机审核页](http://127.0.0.1:8787/)，`/admin/` 也可访问；该目录由审核页任务维护。
- API 为 `POST /api`，响应为 `{ok,data}` 或 `{ok:false,error}`。
- `POST /auth/v1/signin/anonymously`：`x-device-id` 为客户端随机、持久保存的 UUID，body 为 `{}`；返回**原始官方形状** `{access_token,expires_in,sub,token_type}`，不包 `ok/data`。token 是随机 opaque Mock 值，不是 JWT，不是生产 token。
- **专用开发 fixture 账号：`admin` / `mock-admin-password`。仅用于这个 loopback Mock。** 通过 `/api` 的 `adminLogin` 登录，不是 CloudBase 真实账号。生产入口不导入此模块，生产打包也不复制 Mock 文件。
- 默认文件 `cloudbase/mock/data/stories.json`。退出后数据仍在，重启需重新登录；相同设备 UUID 对应相同 Mock UID。正常退出按 Ctrl+C。没有 `/api/reset`、批量清库、远程 seed 或绕过认证的接口。
- 使用独立数据库做整合测试，避免污染日常演示数据：

```powershell
$env:MOCK_DB_FILE = 'E:\Desktop\CodexProjects\CampusLifeAssistant\cloudbase\mock\data\integration-local.json'
npm run mock
```

8787 若已占用，启动失败且不终止原进程。每文件独占 `.lock`；文件损坏时拒绝启动并保留原件。若强制终止后留下锁，先确认旧写入进程已经退出，再只移除对应 `.lock`，绝不删除 JSON。不要并发读写同一数据库文件。

Android 专用断网模拟器使用主会话既有 Debug Mock 构建，配置 `http://127.0.0.1:8787/api` 及同源认证地址，再在指定 serial 上执行：

```powershell
adb -s <专用模拟器serial> reverse tcp:8787 tcp:8787
```

模拟器可继续保持飞行模式，不必访问互联网。测试后可 `adb -s <serial> reverse --remove tcp:8787`。正式构建不得使用 Mock/明文 HTTP；Android 本机 fixture 的 drafts/cache/prefs 隔离由 Android 任务实现，此后端不写 App 数据。

## 校验、事务与隐私

- 投稿标题 **2–30**、正文 **100–5000**、昵称最多 **30**；均按 **trim 后 Unicode 码点**计数，emoji 不按 UTF-16 双份计算。昵称缺省或全空白保存为“匿名”。只接受纯文本，拒绝 HTML 标签/脚本标签；未知字段（包括 uid/status/reviewer）直接拒绝，不作静默过滤。
- consent 必须为 true，版本 `2026-11-10-v1`；consentAt/createdAt 由服务端写入。分类仅五个既有枚举。
- submissionId UUID 映射确定文档 ID，数据库 `_id` 自带唯一性，再配置 submissionId 唯一索引。同 owner、完全相同投稿值重试直接返回原状态；正文等原始字符串变化（包括空白变化）、其他 owner 使用同 ID 返回 409。原始字段只作 SHA256 指纹，不另留正文副本。幂等检查先于限流扣额。
- 每 UID **滚动60秒1篇、UTC自然日5篇**，全站 **UTC自然日50篇**；新投稿、限流计数、待审计数及提交审计在同一事务。匿名身份可以被重新注册，UID 配额不是抗批量注册能力；全站配额和网关限制仍必需。
- approve/reject/withdraw 都只用单文档事务 API；发布/删除公开文档、投稿状态、计数、审计一起提交或回滚。重复操作不重复写审计。拒绝不可转发布；撤下保留 approved 状态及审计、`published=false`，不得再发布。
- `story_stats/counts` 维护 pending/approved/rejected，不扫描全集合统计。缺计数时拒绝写业务；初始化也不会对已有业务数据直接清零计数。
- 发布时 `published_stories` 数据库记录保存原投稿 `submissionId`，用于来源关联；公开列表/详情 DTO 均不返回此字段。公开 DTO 明确挑字段，列表不带正文/owner/授权/审核数据；`readingMinutes` 为正文码点数÷300向上取整，summary 最多120码点。正文详情不含 summary/readingMinutes 或私有字段。未发布/撤下统一 **HTTP 404 + STORY_NOT_FOUND**。
- 稳定分页用 `ISO时间|确定ID` 组成 sortKey，降序 keyset 游标，绑定接口与筛选条件；每页默认20，最大50。不是全表扫描/offset 分页。
- HTTP 请求/响应≤256KiB，官方认证响应≤64KiB；每次认证 fetch 有5秒超时、`redirect:error`，不跟随跳转。默认免费套餐的平台执行时限只有3秒，会先于5秒客户端超时生效，fetch超时配置不能延长平台时限。无凭据、正文、完整上游错误或请求头日志。
- Mock 仓库将整个事务在内存副本完成，文件临时写入+fsync+rename 成功后才替换当前快照；单进程事务串行，异常不提交。它用于本地验证，不替代云端数据库性能/容灾验证。

## 生产认证与管理员

1. Android 匿名登录直接走**真实环境官方** `/auth/v1/signin/anonymously`，不是投稿云函数自行签 token。
2. 所有需要认证的请求均将 bearer 发送到固定官方认证 origin 的 `GET /auth/v1/user/me`。只采用官方返回的 `sub`，并仅接受 `status=ACTIVE/DEFAULT`；BLOCKED/PENDING/未知/缺失状态全部拒绝。客户端 uid、token 自解码内容、groups、昵称都不是权限依据。
3. adminLogin 先代理官方 `POST /auth/v1/signin`，然后用返回 token 再请求 user/me，核对**服务端** `ADMIN_UIDS` 白名单；不信任 signin 响应里自称的 sub。只返回 accessToken/expiresIn，不下发 refresh_token。
4. 每条管理请求都重新 user/me + UID allowlist；没有缓存管理员判断。移出名单需更新实例配置/发布配置，使旧实例退出；官方封禁会在下一次验证生效。
5. 管理员须先由云后台创建真实账号，或走官方验证身份的 signup 流程。**不能只靠用户名密码调用 signup 创建用户**，不仿造 JWT、不自动注册管理员。空白 UID 名单意味着无人可审核。
6. adminLogin 有本实例10次/分钟快拦截，以及跨实例共享事务文档 `story_rate_limits/admin-login-global` 的10次/滚动分钟、100次/UTC日全局尝试上限，失败尝试也计数。不存用户名/密码/IP；不信任 x-forwarded-for。
7. 发布前还须配置**官方网关限频、CloudBase账号防暴力/验证码等账户保护**。应用全局额度会被恶意请求耗尽，不能取代官方边缘保护；匿名登录直连官方认证也须启用平台保护。重试/事务冲突会消耗资源点。

`.env.example` 不会自动载入，也不接受 HTTP 传入环境配置。默认事件函数 SDK 使用平台运行时身份；可选 HTTP 函数必须显式配置独立服务端 `CLOUDBASE_APIKEY`，不能假定它有事件函数的凭据注入，不从请求接收 secretId/secretKey/accessToken。ADMIN_UIDS 不放入 App 或审核页静态配置。

## 初始化与安全规则/索引

以下都是**未来在用户提供已有云环境并批准云操作后**执行的步骤，本次没有执行在线阶段：

1. 先确认文档集合可用；PG环境启用pgdoc后也可使用本项目Node SDK事务，权限必须采用ADMINONLY基础ACL，不支持CUSTOM规则。填入真实运行时环境变量（见 `.env.example`）；先 `node scripts/init-db.js --plan`，默认只输出离线计划，不联网。
2. 在云控制台创建以下五个集合，立即选择“仅管理端可读写”，或将 `database/security-rules.json` 的 `{read:false,write:false}` 分别应用于每一个集合：
   `story_submissions`、`published_stories`、`story_rate_limits`、`story_audit_log`、`story_stats`。**公开故事也拒绝客户端直读，只经服务 API 输出 DTO。** 等规则生效后，用匿名及普通登录客户端实际验证增删改查全拒绝。
3. 如已明确授权脚本建集合，可在已有环境的受信任运行时执行 `node scripts/init-db.js --create-collections --confirm-env=<真实环境ID>`。该阶段只建集合，**不会假装已配置规则/索引**。仅忽略官方 `DATABASE_COLLECTION_ALREADY_EXIST`，其他失败中止。集合新建后仍需先完成上一步权限，不得暴露 API。
4. 控制台根据 `database/indexes.json` 逐项建索引并等状态正常。JSON 是可读的**控制台计划**，不是虚构的 node-sdk createIndex API：
   - story_submissions：submissionId ASC唯一；sortKey DESC；status ASC + sortKey DESC。
   - published_stories：sortKey DESC；category ASC + sortKey DESC。
   - 其他集合依确定 `_id` 访问，自带唯一索引已够用。
   官方管理 SDK 的 `updateCollection(...,{CreateIndexes:...})` 属于 `@cloudbase/manager-node`，不是本项目唯一依赖 node-sdk 的方法。本项目不额外安装管理 SDK/CLI，不把它冒充数据库 SDK API。
5. 人工完成并核对上述规则/索引后，再在受信任运行时执行 `node scripts/init-db.js --initialize-counters --security-reviewed --confirm-env=<真实环境ID>`。该标志仅代表操作者确认，脚本**不能独立核验控制台规则**。初始化只为空库创建 counts；已有 counts 原样保留。已有业务而缺 counts 会拒绝，须另行授权备份核对，不能清零或猜测重建。

初始化脚本不会新建环境、开通套餐、删集合、配置付费或部署。规则与索引控制台阶段仍是正式启用前的必做项，不计为已完成云验证。

## 部署配置与发布前检查

1. `node scripts/stage-production.js` 仅生成 `cloudbase/build/production/`，明确复制生产 src、index/http-server、bootstrap、package/lock。不会复制 Mock、固定 fixture 账号、admin、测试、数据库文件、.env、证据。只允许写 cloudbase/build 下；未知已有文件会拒绝，避免夹带。
2. 在该生产目录运行 `npm ci --omit=dev --ignore-scripts --no-audit --no-fund`，确保 lock 对应依赖；Linux 包中给 scf_bootstrap 可执行权限、LF换行。配置 `installDependency:false`，不依赖平台重新解析 semver。不要直接上传整个 cloudbase 目录。
3. `cloudbaserc.example.json` 默认采用免费优先的 **Event / Nodejs20.19 / 256MB / timeout3秒**，以平台运行时身份读写pgdoc；HTTP网关映射`index.main(event,context)`返回的HTTP信封。若另选HTTP函数，须提供独立服务端API Key，使用`http-server.js`和端口9000，不将长期凭据放App、源码或静态后台。当前上海环境实际函数名为`story-service`，HTTPS入口仍为`/api`。
4. 填真实环境、地域、认证origin、ADMIN_UIDS及后台来源。网关`/api`指向事件函数（SCF），设置限频；本次CLI已安装且已获官方授权，不重复登录。更新代码使用`fn code update`，避免用旧配置覆盖当前环境变量。
5. 审核静态页单独部署，不在生产 API 函数提供。后台配置只能放公开 HTTPS API 地址。最好在同一正式自定义域名下路由 `/api` 和审核页；跨域则配精确 HTTPS origin 白名单，不用 `*`。生产即使同源也需配置后台 origin，否则浏览器带 Origin 请求会被拒绝。
6. 只接受与环境 ID 相匹配的官方认证 origin，禁止任意 URL/本机地址、凭据 URL、路径、跳转重定向。缺任何必要环境字段，生产返回503 NOT_CONFIGURED，**不回落到 Mock**。
7. 上线前用真实匿名 token 验证投稿、变更 owner 冲突、并发限流、事务冲突重试；真实管理员验证批准/拒绝/撤下与审计、名单撤销/账号封禁；客户端 SDK 验证 deny-all。核对 HTTPS、官方 auth CORS/安全域名、静态后台、自定义域名、域名备案条件及费用保护，缺项不宣称生产可用。

## 免费测试、正式后台域名与预算

官方当前套餐表列明：免费体验版云函数固定 **256M、超时3s，均不支持修改**；个人版最大超时900s不是免费能力。模板已将平台 timeout 默认设为3秒。[腾讯云套餐对比](https://cloud.tencent.com/document/product/876/127357)

**3秒可用性必须云端实测**：冷启动、adminLogin 的两次官方认证调用（signin后user/me）、数据库事务（投稿/审核及持久登录限流）是否能在平台时限内完成，不能从本机Mock通过推定。投稿遇到超时，保留**同一 submissionId 和原内容**重试，不生成新ID；审核写操作也按原投稿ID重试，由幂等逻辑处理“已提交但响应丢失”。如果免费套餐无法稳定完成，先报告实测耗时与失败，再由用户决定是否付费；不擅自增加超时/内存、升配或开通付费。

默认域名只适合开发测试。官方说明：**2025-10-09 后创建环境的默认域名有长期访问提示中间页**；正式生产中浏览器直接访问静态网站/云托管/HTTP网关**必须使用自定义域名**，接口及图片资源访问不属这一浏览器直访限制。审核后台是浏览器页面，不能因为 API 可调用就保证后台默认域名可正式使用。[HTTP网关官方说明](https://docs.cloudbase.net/service/introduce)

本机 Mock 不消耗云资源点。3000资源点只用作本项目云测试的**预算上限**，不是“生产永久免费”的承诺。官方资源点表列数据库200点/万次、函数13点/万次、CDN210点/GB；还要纳入执行计算、数据库/文件存储、索引、认证/验证码、域名等实际项目与套餐口径。一次业务事务包含多次数据库操作，每个HTTP调用不等于一次DB调用。计费公式与余量以真实环境控制台为准。[资源点官方FAQ](https://docs.cloudbase.net/quick-start/resource-point)

未设置任何自动升级套餐、资源包购买、超额按量开通或付费部署。本地业务限流不是账单硬停机：公开阅读、认证失败、爬虫也会耗资源；启用前在官方控制台核对预算告警、额度耗尽行为、边缘限频及停用方案，未经新授权不打开付费。

## 本次证据与恢复

运行 `node scripts/verify.js` 会将当前实际测试 TAP、命令/退出码、离线初始化计划、生产空配置断言、生产打包清单、源码 SHA256 保存在 `cloudbase/verification/story-cloud-20261110/backend/`。最初失败日志和后续修复日志分文件保留，不覆盖成“全程一次成功”。日志不写凭据、正文、用户 profile。管理员静态页测试由对应任务/主会话另行保存，不混入本次 Node 数量。

免费3秒模板修正的原件/SHA、离线断言红→绿、完整43项Node回归、命令与退出码另存该证据目录的 `free-tier-3s/`；先前 `20-final-tests.tap` 是此前42项结果，不冒充本次回归。此次没有更改运行中的Mock业务源码、数据、Android或后台，也没有云端部署。

随后补齐公开数据库记录的 submissionId，approve直接读取仓库断言来源字段，公开DTO仍用完整字段白名单断言排除它；与3秒模板一起运行的最终43项回归、生产包同步、原件/SHA和命令证据见 `published-submission-id/`。这次只影响以后新发布的数据库记录，未迁移或改写既有Mock/云端数据。

目前测试覆盖：输入/emoji边界、owner与幂等竞态、UTC额度与滚动分钟、全站额度、管理权限、审核竞态/回滚/审计、撤下404、DTO与游标、Mock真实HTTP及重开持久化、官方认证URL/超时/拒绝跳转/账号状态/allowlist、跨实例登录限额、SDK事务/普通读取形状、安全初始化及生产打包隔离。最终数量与命令以 `20-final-tests.tap`、`commands.json` 为准；不是云端实测或Android验收。

本目录新增服务端，不替换 App 原件。需恢复时先停止 Mock/撤销未来云端路由，保留 mock/data 数据和所需证据，再将源码切回集成前备份；不提供会删除用户故事或清空数据库的恢复命令。已有数据不能在降级时被隐式重建/重置。正常文件仓库重开/原子异常回滚在临时隔离库测试，日常演示库不会被自动测试写入。

## 2026-11-10 官方核对依据

以下本轮通过官方文档搜索读取，部分直达页曾超时，未把未获得页面当成功：

| 依据 | 对应实现/结论 |
|---|---|
| [user/me](https://docs.cloudbase.net/http-api/auth/user-me) | GET bearer、sub；状态枚举 ACTIVE/DEFAULT/PENDING/BLOCKED；仅前二可用 |
| [官方signin](https://docs.cloudbase.net/http-api/auth/auth-sign-in)、[官方signup](https://docs.cloudbase.net/http-api/auth/auth-sign-up)、[HTTP API快速开始](https://docs.cloudbase.net/quick-start/http-api/introduce) | username/password原样代理官方；signup需验证身份；匿名直连官方 |
| [事务](https://docs.cloudbase.net/database/transaction) | NoSQL服务端 node-sdk 单文档事务、冲突重试、回滚；事务10秒回收窗口，不放外部fetch进事务 |
| [安全规则示例](https://docs.cloudbase.net/rule/rule-example)、[数据库规则](https://docs.cloudbase.net/database/security-rules) | 每集合 read/write false，服务端环境身份访问不受客户端规则拦截 |
| [管理SDK数据库](https://docs.cloudbase.net/api-reference/manager/node/database)、[基本错误码](https://docs.cloudbase.net/error-code/basic) | 管理SDK与数据库SDK不混用；索引控制台配置；精确已存在错误码 |
| [HTTP函数](https://docs.cloudbase.net/cloud-function/develop/how-to-writing-functions-code)、[HTTP framework](https://docs.cloudbase.net/cloud-function/quickstart/httpfunc/func-framework) | 原生9000服务和HTTP handler响应结构，不泄露运行时凭据 |
| [启动文件](https://docs.cloudbase.net/cloud-function/develop/scf-bootstrap)、[运行时](https://docs.cloudbase.net/cloud-function/runtime-support)、[函数配置](https://docs.cloudbase.net/cli-v1/functions/configs)、[配置文件](https://docs.cloudbase.net/cli-v1/config) | Node20路径、LF/执行权限、type HTTP、dir、env模板、路由 |
| [腾讯云计费概述/套餐对比](https://cloud.tencent.com/document/product/876/127357) | 免费云函数256M、平台超时3s不可修改；模板默认3秒，不自动采用个人版900秒能力 |

版本由本轮 `npm view @cloudbase/node-sdk version` 得到 **3.18.7**，已精确固定；并逐项检查安装包官方源码/类型：`@cloudbase/database/dist/commonjs/{index,document,transaction/index,query}.js`。特别是普通 `doc.get().data` 为数组，**事务为对象或null**；`doc.set` 直接传字段、不能带 `_id`；`doc.remove()`；`db.runTransaction` 自动提交并对指定冲突重试；`db.createCollection(name)`。测试专门保留了“把事务结果误当数组”的红→绿记录。不调用猜测的 set({data}) / createIndex / transaction.where 等接口。
