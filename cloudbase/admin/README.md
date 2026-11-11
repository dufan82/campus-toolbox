# 校伴 · 小故事审核后台

纯 HTML / CSS / JavaScript，无框架、CDN、打包步骤或运行时第三方依赖。源文件保留空API模板；本项目已有云服务，实际部署状态与接入见[云端记录](../../docs/CLOUD_CONNECT.md)。视觉复用 Android 小故事的海军蓝、青绿、橙色与浅灰，使用原生系统字体。

## 免费本机审核（真实云端）

如云平台默认网页域名将HTML作为附件下载，可用 `node cloudbase/scripts/local-admin.js --api <真实HTTPS API>` 启动，再打开 http://127.0.0.1:8791/ 。页面标明“本机审核·真实云端”，不是Mock；文件服务只绑定回环地址，不接收密码、不代理Token，浏览器直接HTTPS调用云端。服务端须精确配置ADMIN_LOCAL_ORIGIN；不要放开任意HTTP来源。未配置真实API时，此命令会拒绝启动。

## 本机Mock运行

由后端 Mock 提供静态目录与 API 后，在 Edge 打开 [本地Mock后台](http://127.0.0.1:8787/admin/)。页面始终醒目标明 **本地Mock**；配置空值且页面主机精确为 `localhost`、`127.0.0.1` 或 `[::1]` 时才自动使用同源 `/api`，不会回退到外部服务。

演示登录 `admin` / `mock-admin-password` 仅适用于本机 fixture。页面没有预填账号或密码，这组凭据未写入生产配置。Mock 的启动与服务端代码由后端任务负责；本目录不修改后端或 Android 代码。显式授权的本机 fixture 测试只通过 API 新建并审核自己的标记稿件。

直接双击 HTML 不可用。仅用静态服务器打开页面也不等于 API 已启动；无服务时登录会报连接失败。

## 功能

- 独立管理员登录；投稿计数由服务端返回，不用当前页条数推算。
- 待审核 / 已通过 / 已拒绝 / 全部筛选，每页 20 条，使用不透明 cursor 前后翻页；切换筛选、手动刷新、审核成功后回第一页。
- 详情中的标题、署名、正文、同意信息与内部备注全部按纯文本展示，保留正文换行。时间统一显示北京时间（UTC+8）。
- 待审核投稿可通过或拒绝，均需独立弹窗二次确认；通过后才公开。
- 拒绝可填写不超过 500 字的内部备注，不作为公开理由。撤下也可填写内部备注。
- 已通过且公开中的投稿可二次确认撤下。已撤下仍属于“已通过”计数，但显示“已撤下”，不提供重新发布按钮。
- 不自动轮询、不自动重试。15 秒超时或网络失败时，明确提示结果尚未确认，需刷新核对，不能把超时当作审核失败。

## 契约和权限边界

所有请求均为 `POST apiUrl`，请求体 `{action,payload}`；只处理 `{ok:true,data}` / `{ok:false,error}`，最大响应 256 KiB。不会渲染服务端原始错误文本，以免反射凭据或不可信内容。

| 操作 | action | payload |
| --- | --- | --- |
| 登录 | `adminLogin` | `username,password` |
| 列表与计数 | `getPendingStories` | `status,limit:20,cursor?` |
| 详情 | `getSubmissionDetail` | `id` |
| 通过发布 | `approveStory` | `id` |
| 拒绝 | `rejectStory` | `id,note?` |
| 撤下 | `deletePublishedStory` | `id,note?`，id 为投稿编号，不是公开故事编号 |

除登录外，每次管理请求都带内存中的 Bearer token。客户端不发送自称的 UID / role / reviewer，也不使用 Android 匿名身份取得管理员权限。

**服务端必须独立执行的前置条件**：官方管理员账号登录后验证 token 的可信 `sub`，用该 UID 核对管理员 allowlist；每次管理请求重新验证。直接数据库访问应 deny all。按钮显隐仅用于操作体验，不能代替服务端鉴权。真实云端 UID 验证与 allowlist 配置需后端部署验收，本页或注入测试不能证明其已上线。

## 生产接入（本次未执行）

1. 在 HTTPS 站点托管本目录的六个运行文件：`index.html`、`config.js`、`api.js`、`app.js`、`main.js`、`styles.css`。无需上传 `tests/`。
2. 只修改外部 `config.js` 的 `apiUrl` 空字符串为真实固定 HTTPS API URL；不填演示域名、凭据、查询参数或 fragment。HTTPS 页面不接受 `/api` 等相对配置，不会启用本机 Mock；未配置时禁用登录。页面“已配置 API”只代表地址有效，不代表云端已连通或部署成功。
3. 后端 CORS 仅允许后台实际 origin，并允许 `Content-Type` / `Authorization`。客户端固定 `credentials:omit`、`cache:no-store`、`redirect:error`、`referrerPolicy:no-referrer`。
4. HTML 自带 CSP，不依赖 inline script/style、内联事件、eval 或外部字体。生产建议通过响应头设置完整 CSP，将 `connect-src` 从通用 HTTPS 收窄到实际 API origin；额外设置 `frame-ancestors 'none'`、`X-Frame-Options: DENY`、`X-Content-Type-Options: nosniff`、`Cache-Control: no-store`。`frame-ancestors` 必须用响应头，不能放在 meta 中代替。
5. 不在前端放 CloudBase 密钥、数据库管理凭据、UID allowlist 或 token。保留现有安全配置，不通过取消 TLS / CORS / 身份检查来联调。

Token 仅在 API 客户端闭包中，不写 localStorage / sessionStorage / IndexedDB / cookie / URL / 日志。刷新、退出、`pagehide`（包括 BFCache）、`beforeunload` 或卸载应用实例时清除 token、终止未完成请求并清空详情；401 回登录，403 也清除会话并提示核对服务端授权。过期 token 在下一次请求前清除。密码在发出登录请求后立即清空输入框；应用不记录凭据。

## 测试与证据

无需 npm install。当前环境已有 Node、Python Playwright 和 Edge；没有这些工具时不自动安装，也不把未运行测试描述为通过。

```powershell
Set-Location 'E:\Desktop\CodexProjects\CampusLifeAssistant\cloudbase\admin'
node --test tests/api.test.mjs tests/static.test.mjs
$env:PYTHONUTF8 = '1'
python tests/test_browser.py
# 仅当另一个任务的真实本机 Mock 已启动后运行：
python tests/smoke_mock.py
# 明确允许只在上述固定本机 Mock 新增 3 条测试稿并执行真实 UI 审核：
python tests/mock_ui_workflow.py --allow-local-fixture-writes
# 统一重跑并保存原始输出、退出状态与文件 SHA256；可选 -WithMock
powershell -NoProfile -File tests/verify.ps1 -WithMock
# 包含上述本机 fixture 写入与真实 UI 审核，禁止用于生产：
powershell -NoProfile -File tests/verify.ps1 -WithMockFixtureWrites
```

- `tests/api.test.mjs`：端点配置、action envelope、每请求 Bearer、无 UID、401/403、清会话和在途请求、过期、响应大小/格式、超时不重试。
- `tests/static.test.mjs`：CSP / 外置资源 / 禁止 HTML 注入 API / 禁止持久存储与日志 / 空生产配置 / 校伴配色。
- `tests/test_browser.py`：在临时 loopback 静态服务器上，用已有 Playwright 驱动无头 Edge，以隔离内存 API 注入验证真实 DOM 交互。覆盖登录、计数、20 条分页、筛选、文本注入、二次确认、重复点击、内部备注、已撤下不能再发布、错误、键盘、手机宽度、页面卸载和空生产配置。不创建截图，不改后端数据。
- `tests/smoke_mock.py`：只连接固定 `127.0.0.1:8787`，不拦截 API，不修改投稿。绕过 UI 直接检查 5 个管理 action 在无 token、伪造 token、已登录非管理员 token 下均被服务端拒绝（15 项），再登录页面读取列表与可用详情、打开并取消确认、退出。只为实际本机 Mock 页面拍摄标识可见的截图；没有待审 fixture 时会如实记录未验详情，不制造数据或图片。
- `tests/mock_ui_workflow.py`：需要显式 `--allow-local-fixture-writes`，固定 loopback 地址不可通过参数换成生产。通过 randomUUID 匿名身份和 API 新建三条 500 字、标题带“本机验收”的唯一 fixture，再通过真实 UI 完成通过、拒绝（保存纯文本内部备注）、撤下；核对公开详情通过后 200、不泄漏审核字段，撤下后 404，已撤下不可再次发布。保留一条待审 fixture，原有投稿不改动。报告及截图保存在 `tests/artifacts/mock-ui-workflow.json`；脚本在日志中不输出登录凭据或 token。每次执行都会创建新测试稿，会消耗本机 Mock 的投稿配额。
- `tests/verify.ps1`：将本轮命令、原始输出、退出状态、契约摘要及源码 SHA256 保存到 `tests/artifacts/verification.json` 和对应 `.txt`。`tests/artifacts/mock-smoke.json` 单独记录实际 Mock 验证及截图清单；这些是本地证据，不是云端部署证明。

本轮实际通过数量、执行时间与文件摘要以 `tests/artifacts/verification.json` 为准。首次 API 测试因尚无实现失败；交互测试曾发现移动端绝对定位辅助文本造成页面溢出，已为表格滚动容器增加定位边界并通过同项回归。

本次真实 Mock UI 已完成通过 / 拒绝 / 撤下，确认通过后的公开详情 200、撤下后的 404、内部备注纯文本和已撤下不可重新发布；另验证 15 项服务端鉴权拒绝，浏览器脚本异常为 0。详情与审核弹窗内也标有“本地Mock”。只读 smoke 与写入 workflow 使用不同详情截图文件名，避免覆盖证据。测试只证明当时运行的本机服务，不声称新版后端校验或云部署已验收；运行进程信息保存在 `verification.json`，后端更新并重启后可按上述命令复跑。

## 文件清单与撤回

运行文件为上面的六项；另有 `package.json`（仅便捷 test 脚本，无依赖）、`.gitignore`、本 README，以及 `tests/` 的六个测试/验收脚本和本次运行证据。精确大小及 SHA256 见验证报告。

本次新建 `cloudbase/admin/`，没有被覆盖的旧后台文件，原始 SHA256 不适用。需要撤回时先备份本目录，再按验证报告移走本次新增文件；不要删除 `cloudbase/` 的其他内容，也不要回滚其他协作任务。未修改 Android、根文档或后端，未运行 Gradle、提交、推送或部署。
