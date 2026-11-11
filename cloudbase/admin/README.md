# 校伴 · 随记精选与投稿管理

现有原生HTML/CSS/JavaScript审核页扩展，无框架、CDN、新依赖或打包步骤。**本地新版；真实云端仍旧版，本轮未部署/联调。** 原配置未改。

## 使用

项目根运行 `node cloudbase/mock/server.js`，Edge打开[本机Mock管理页](http://127.0.0.1:8787/admin/)。创建/复用Mock自己的文件数据库，不读真实云环境。测试账号仅适用于Mock：admin / mock-admin-password，网页不预填。

- 新增精选：粘贴完整HTTPS原文链接，区分评论作者与作品发布者；填写正文草稿/独立介绍、来源作品/评论定位、授权说明、整数排序、隐藏状态。
- 先存草稿，再核对并二次确认发布。未授权仅公开介绍/必要摘录和来源入口；完整草稿/rightsNote仅管理员。
- 公开编辑需确认，服务端重新判权；撤销授权清空公开全文。隐藏可恢复，撤下不可重发。
- 原投稿审核/拒绝/备注、筛选/游标分页保持。pending含精选草稿，approved含隐藏/撤下。
- 云端排序越小越靠前；前三篇归Android本地，不覆盖本地目录。
- 不自动重试/轮询。会话/密码不持久化；401/403、退出/卸载页面清空私密内容。

契约见[CONTRACT](../CONTRACT.md)，文件、验证及恢复见[本地交接](../../docs/SUIJI_ADMIN.md)。

## 离线测试

项目根 `node cloudbase/scripts/verify-suiji-admin.js`：后端、管理页Node测试、12项原Edge回归及2项实际Mock+Edge流程。临时独立数据库自动清理。Node测试外网由preloader拒绝，新浏览器测试拒绝全部非回环请求，不运行云端命令。

运行文件共七项：index.html、config.js、api.js、app.js、editor.js、main.js、styles.css。现有local-admin.js仅补editor.js静态白名单；本轮**未启动真实云API模式**。旧云缺新接口时不换服务，旧投稿审核仍保持兼容。

tests/verify.ps1、smoke及早期workflow为历史入口，本轮不用其历史报告。当前证据只认cloudbase/verification/suiji-admin-20261111/commands.json，无需安装依赖、修改真实配置、执行Gradle、提交、推送或部署。
