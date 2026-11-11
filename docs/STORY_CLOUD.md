# 小故事投稿：本地验收与 CloudBase 接入

## 已有上海 PG 环境接入进度（2026-11-10）

真实云端测试已接通。匿名投稿、幂等和待审隔离通过；用户人工完成管理员登录、通过与撤下，公开HTTPS读结果已独立核对。新Debug包包含真实公开API，本机审核入口为http://127.0.0.1:8791/。具体使用、到期时间与待手机事项见[CLOUD_CONNECT](CLOUD_CONNECT.md)。下文“未配置/仅Mock”保留说明旧离线包，不代表当前云端状态。

实际使用story-service事件函数（256MB/3秒）＋HTTP网关SCF映射，模板亦默认Event；无需长期服务端Key。HTTP试用Key已撤销。IgnoreSysLog=true避免返回令牌和待审内容写入平台系统日志。默认静态域名实测返回attachment，六文件虽然上传成功但不能作为可直接打开的后台；现提供只绑定127.0.0.1的本机审核，不改平台响应限制、不收费。

**PG兼容方式：**官方文档明确支持 Node SDK v3 `/admin` 与事务，所以不因PG模式重写业务层。pgdoc **不支持 CUSTOM 安全规则**；当前统一使用基础权限“无权限／ADMINONLY”，含已发布集合。不要对PG环境照搬 `{read:false,write:false}` 自定义规则并误以为已生效，也不要用JSON规则放开直读。原初始化脚本的建集合/JSON规则流程面向传统模式；本环境集合已由CLI管理API以ADMINONLY创建，不重复执行。业务计数初始化及真实SDK事务回滚已实测；独立客户端数据库直连拒绝仍待端到端验收。

[官方pgdoc使用说明](https://docs.cloudbase.net/database/postgresql/pg-doc-usage) · [开通API及费用](https://cloud.tencent.com/document/api/876/140402) · [ADMINONLY权限](https://cloud.tencent.com/document/api/876/34819)。


## 已完成与未上线

Android 原生投稿、服务端校验/去重/限流、管理员审核、发布/撤下、公开分页和离线缓存均已编写。本轮只在本机 Mock 验证，没有创建 CloudBase 环境、部署、购买服务或发布新版本。手机预览默认不配置云地址，因此只保存草稿，不会显示假的投稿成功。

- 服务端与部署步骤：[cloudbase/README.md](../cloudbase/README.md)
- 接口：[CONTRACT.md](../cloudbase/CONTRACT.md)
- 管理后台：[admin/README.md](../cloudbase/admin/README.md)
- 数据库集合、索引：[database/indexes.json](../cloudbase/database/indexes.json)
- 客户端直读直写全部拒绝：[security-rules.json](../cloudbase/database/security-rules.json)

## 需要提供和配置的内容

| 配置 | 在哪里使用 | 说明 |
| --- | --- | --- |
| 真实环境 ID | `TCB_ENV_ID` | 控制台已有的文档型 NoSQL 环境；不能使用示例值 |
| 地域 | `TCB_REGION` | 与环境一致，以控制台为准 |
| 官方认证 origin | `TCB_AUTH_ORIGIN`、Android `STORY_AUTH_URL` | `https://<环境ID>.api.tcloudbasegateway.com`，真实值才可填 |
| 云函数 HTTP 地址 | Android `STORY_API_URL`、后台 `config.js` 的 `apiUrl` | 实际 HTTPS `/api` 路由，无密钥、查询参数和 fragment |
| 管理员账号及 UID | 云身份认证、服务端 `ADMIN_UIDS` | 先创建真实账号，按官方 user/me 的 sub 配置白名单；不是腾讯云主账号密码 |
| 后台来源 | `ADMIN_ALLOWED_ORIGINS` | 精确 HTTPS origin，不能填 `*` |
| 五个集合及索引 | 云数据库控制台 | 先 deny-all、建索引、空库初始化计数，再暴露接口 |
| 云函数与管理页面 | 控制台/已有部署工具 | 分开部署；默认没有执行部署 |

环境变量模板为 `cloudbase/.env.example`，所有值为空。不要把 Secret、管理员密码或完整 Token 发到聊天、放入 APK 或提交 Git；事件函数SDK使用运行时身份；HTTP备选必须另配服务端Key，不能照搬默认凭据。Android 只需要公开 API 和认证地址，可经本机 `local.properties` 或构建环境变量配置，两项必须一起填写。后台静态配置也只有公开 API 地址。

## 推荐接入顺序

1. 在云控制台确认免费环境的可用能力和到期时间，不勾选自动付费或升配。
2. 按服务端 README 开启官方匿名登录；创建独立管理员账号，并核对 UID。
3. 创建五个集合，应用 deny-all 和索引；`node scripts/init-db.js --plan` 只生成离线计划。后续在线初始化必须明确确认真实环境，脚本不会自动申请环境或套餐。
4. 仅部署生产 staging 目录，不上传 Mock、测试账号、数据库文件和 `.env`。事件云函数默认 256M、3 秒，路由 `/api`。配置服务端身份和精确后台来源。
5. 测试真实匿名投稿、普通账号越权拒绝、待审不可读、批准/拒绝/撤下和云事务并发；核对网关限频、认证保护和额度耗尽行为。
6. 部署管理页的六个静态运行文件，填写真实 `apiUrl`。测试默认域名可用于开发；正式浏览器后台需要已有备案自定义域名，不能保证免费默认域名长期提供正式后台。无需为本轮购买域名。
7. 配置 Android 两个公开地址、构建手机测试包；在真机完成投稿→审核→第二台设备阅读→断网缓存验收，再另行授权正式发布。

## 本机 Mock

在 `cloudbase` 目录执行 `npm ci --ignore-scripts --no-audit --no-fund`、`npm run mock`，打开 `http://127.0.0.1:8787/admin/`。专用测试登录见服务端 README，不能拿它登录真实云端。

Android 仅显式 `-PstoryMock=true` 的 Debug 包连接本机 Mock；指定专用模拟器后执行 `adb -s <serial> reverse tcp:8787 tcp:8787`。Mock 与正式草稿/缓存隔离。常规交付包绝不启用此参数，也不删除地图 ARM 原生库。

测试：`npm test --prefix cloudbase`；`npm test --prefix cloudbase/admin`；`python cloudbase/admin/tests/test_browser.py`。真实本机后台 UI 审核脚本需显式 fixture 写入参数，不能指向生产。Android 正常检查沿用 Gradle Wrapper，测试命令与本次结果详见交付报告和 verification。

## 费用和限额

官方免费环境目前为每月 3000 资源点、每账号一个、单次续期六个月；套餐与可续期条件以控制台为准。免费云函数 **256M、3 秒**，不能按免费环境部署 15 秒函数。冷启动、两次管理员认证和数据库事务能否稳定在 3 秒内完成，必须用真实云环境测量。超时使用原投稿 ID 重试；如免费套餐不稳定，先报告结果，再决定是否付费。

用于预算的例子：假设每月 1 万次函数调用、1～3 万次数据库操作，按官方调用部分单价约 **213～613 点**；这不含计算、认证、存储、索引、流量和重试，不能据此承诺可支持多少人。设置预算告警、网关限频并检查用量；不配置定时轮询，不开短信、不购服务器、不自动升级套餐。本机 Mock 不消耗云额度。

默认投稿限额为每匿名 UID 滚动 60 秒一篇、每日五篇，全站每日五十篇；管理员登录全局十次/分钟、一百次/日。额度使用 UTC 日期，后台时间显示北京时间。匿名 UID 限额不能替代平台抗批量注册保护；公开阅读、失败调用和爬虫仍会耗资源。

官方依据：[套餐](https://www.cloudbase.net/pricing)、[套餐能力/3秒限制](https://cloud.tencent.com/document/product/876/127357)、[资源点](https://docs.cloudbase.net/quick-start/resource-point)、[默认域名](https://docs.cloudbase.net/service/introduce)、[匿名认证](https://docs.cloudbase.net/http-api/auth/auth-sign-in-anonymously)、[user/me](https://docs.cloudbase.net/http-api/auth/user-me)。

## 数据与撤稿

保留 APK 内置故事和既有本机草稿、收藏、阅读进度。云故事只由独立 ID 合并，不覆盖内置内容；最多缓存一百篇已打开的正文。在线确认撤下后禁止旧缓存回退；完全断网无法得知云端刚发生的撤稿，联网核实后再清理。尚未读取完整正文的故事不承诺离线可读。

投稿页“撤稿与版权反馈”进入现有真实反馈渠道，作者可附投稿编号申请撤下；**不会把投稿正文提交到反馈问卷**。管理员内部备注、UID、授权与审计字段不在公开接口输出。定期由管理员核对数据库空间、待审积压、撤稿和版权投诉，第一阶段不自动删除审核/审计记录。
