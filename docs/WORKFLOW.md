# 开发与版本发布

本文管流程；业务安全与兼容规则仍按 [AGENTS](../AGENTS.md#改到哪一块就先读哪一块) 的主题链接执行。任务只涉及哪一块，就读哪一块，不每轮全读历史。

## 两种工作方式

| 方式 | 何时使用 | 做什么 |
| --- | --- | --- |
| 日常开发 | 继续开发、修复、文档、增量预览；默认方式 | git status＋源码版本、STATUS 顶部摘要、rg 定位；相关 tests/lint 和必要增量预览，不改版本、不跑正式发布。 |
| 更新版本 | 用户明确要求更新应用版本/发版 | 默认 GitHub＋CNB；明确单源就只做该源，说明另一源保持什么状态，不擅自补发。 |

含糊的“更新”不算发布授权。目标已明确时复用当次授权，不分步骤重复询问；发布不自动授权 Git 提交/推送、签名变更、真实数据迁移、付费或云部署。文档限定任务不顺带改进度文件或构建 App。

## 统一发布入口

约定使用 `scripts/Publish-Stable.ps1`，不再为每次发布拼一套临时流程。入口已完成本地验证与模拟故障测试，真实上传/公开仍须获授权后验收。**接口以脚本 Get-Help 为准**，不绕过门禁手工公开。

| 模式 | 边界 |
| --- | --- |
| Check | 默认只读源码版本和Git提交，不联网/构建；加`-InspectRemote`才只读查询两源稳定版本和元数据。 |
| Prepare | 只做本地发布准备：读取当前版本、必要的增量构建/测试/签名，不编辑版本号，核对并整理三附件与输入收据；不自动 Git、上传或公开。 |
| Publish | 只按已核对的输入和收据处理获授权渠道；须有当次明确发布授权，并显式传 `-PublishAllowed`，缺任一条件即停止。 |

旧准备器与渠道脚本的具体检查仍见 [RELEASING](RELEASING.md)、[DOMESTIC_UPDATES](DOMESTIC_UPDATES.md)；它们不是放宽统一入口授权与验收的捷径。

## 输入收据与复用

- 输入收据记录：源码范围/提交及未提交改动、相关输入清单与 SHA256（含构建配置/资源）、工具链/构建参数、目标版本/渠道、固定证书依据、APK 和三附件的大小/摘要，以及各检查结果与证据路径。不能只凭 HEAD 相同认定输入相同。
- 只有相关输入 hash、配置和验证范围一致，且证据可重新打开，才能复用已确认的测试/构建/附件；输入变了就补跑受影响检查。写“复用哪次、哪些范围”，不写成本轮重跑。
- 本地证据可复用，远端当前版本、附件和 latest 状态仍须在发布时重新读取；不能把旧远端收据当当前事实。复用构建前也要重算实际文件摘要。
- 双源复用同一个已验证 APK、update.json、SHA256SUMS.txt；三附件逐份同大小、同 SHA256，版本/tag 相同，不分渠道重建或重签。显式单源不伪报双源同步。

## 发布验收与远端顺序

1. 先只读核对指定仓库、目标源码/提交/tag、各源上一稳定版 code、可信固定证书及授权范围；不从候选 APK 或远端 JSON 自动采信证书。公开源码/附件先审允许清单、敏感内容、素材许可和署名；不自动加项目许可证。
2. 发布所需证据仍包括相关完整 JVM、Debug/Release Lint、正常双 ARM 非调试发行构建、签名/包名/minSdk/版本增长、更新协议与三附件校验及升级数据保留回归。输入未变可按收据复用；必需检查失败或缺失就停止，不靠跳过断言或改签名放行。
3. applicationId 保持 `cn.campuslife.assistant`、minSdk 26，versionCode 严格递增；双 ARM 原生库完整，签名有效且与可信预期相符。默认拒绝 Android Debug 证书，历史固定证书仅按显式兼容例外处理；仍拒绝 debuggable、错包、降级、无效签名和摘要不符。
4. 在获授权渠道先建草稿、上传同 Release 三附件，核对远端源码/tag、资产名、大小、SHA256 和元数据；没有可信远端摘要就下载重算。**远端核对通过后才公开或设 latest**，不是先发布再补验。
5. 公开后核对非 draft/非 prerelease、latest 和免登录读取；完整下载三附件核对大小/摘要，确认更新元数据与 APK 一致。设备安装、更新检测、真实学校/地图/定位按实际验收范围记录；离线夹具、模拟器、Intent 启动和 CI 成功不能替代真实落地验收。
6. 双源分别记录结果；一源完成、另一源失败/未做，或匿名下载/CI/手机验收未完成，就报告具体状态与缺项，不概括为“双源完成”或“全部通过”。单源发布说明另一源版本和客户端选源影响。

## 失败恢复与日志

- 同名同内容可核验后复用；同名不同内容立即停止，保留已有附件、历史包和原始日志，不覆盖、不删后重传，不用 force/清数据/卸载绕过。
- 重试先读已有收据并重新核对远端实际状态，只补未完成步骤；本地部分产物放新的独立目录重做，先验证再使用，不覆盖原件。远端冲突或需要删除/撤回时先说明并取得对应授权。
- 不把跨平台发布描述成原子事务；部分成功就分别保留 Release/附件 ID、版本、摘要、公开状态和下一步，不偷偷补发未授权渠道。
- 日志落到本机 `verification/<任务或版本>/` 等独立证据目录，保留实际命令、输入摘要、时间/时区、原始输出、退出码、失败与恢复记录；不记录密钥、Token、Cookie 或真实个人资料，也不默认上传证据目录。
- 测试写实际通过/失败/跳过数及范围，未运行就写未运行；历史复用注明来源，模拟/合成结果标明范围。配置存在不等于执行成功，构建成功不等于安装/发布完成，等待中的 CI 不能冒称通过。

## 业务规则按题读取

[AGENTS 的主题索引](../AGENTS.md#改到哪一块就先读哪一块)要求改动前读取并落实相关规范。归档中的首页固定入口、旧数据/撤下入口、学校只读和完整取数、凭据/备份隔离、地图权限/Key/地点 schema、水印原图、更新源与兼容证书等保护继续有效，不因移出根 AGENTS 而取消。

公开源码或交接时，须检查本页、归档和必读主题链接可达；若被忽略规则排除，先报告主会话处理，不只交付根 AGENTS 留下断链。本次不改忽略规则或自动暂存。

归档里的旧“每轮全读/先改 TASKS”和笼统“更新即双源”流程由本文件替代；具体校方 API、认证字段和版本差异按任务用 rg 定位，再核对当前源码、测试及 STATUS 对应摘要。后续明确批准的每日同步加密凭据方案见 [DAILY_SCHEDULE_SYNC](DAILY_SCHEDULE_SYNC.md)，不能把旧“仅内存”描述误用为删除既有合法数据，或反向扩大为任意保存登录态。

CNB 凭据复用已有本机 `scripts/CnbCredentialStore.ps1` / `Get-CampusCnbToken`，仅限已授权的 `campus-toolbox/campus-toolbox` Release，令牌只在内存，不进命令参数/环境变量/源码/App/日志；只在观察到过期、撤销或缺权限时补授权，401 与权限不足的 403 分开报告。不得因旧文“短期输入”反复索要同一有效授权。

## 原文归档与恢复

- 原件：[AGENTS-before-release-workflow-20261112.md](archive/AGENTS-before-release-workflow-20261112.md)，完整逐字节复制，不加前言或改编码/换行。
- 原 AGENTS：90 行、6482 字符、15551 字节；归档 SHA256：`572b779206343717ce5c745b317a5b3942187ec416a279ddc6102a82d67d8ad8`。
- 需要回退本次文档压缩时，先保护当前修改，经明确要求后用归档字节替换根 AGENTS.md，再用 `Get-FileHash -Algorithm SHA256` 核对上值；不回退代码、不清理工作区，不把旧流程重新当作最新授权。

## 实际调用

```powershell
pwsh -NoProfile -File scripts/Publish-Stable.ps1 -Mode Check -InspectRemote
pwsh -NoProfile -File scripts/Publish-Stable.ps1 -Mode Prepare -LegacyCompatibility -BuildToolsPath E:/skill/android-sdk/build-tools/36.0.0 -Offline
# 只有本次已获发布授权才执行（默认GitHub和CNB，可显式-Channels github）：
pwsh -NoProfile -File scripts/Publish-Stable.ps1 -Mode Publish -PublishAllowed -LegacyCompatibility -BuildToolsPath E:/skill/android-sdk/build-tools/36.0.0
```

首次为当前输入建立收据时，沿用已有`CAMPUS_SIGNING_*`安全环境配置；不提示输入或保存签名口令，不改Gradle签名策略。常规新证书不传`-LegacyCompatibility`，且须显式设置新的可信证书摘要；当前历史证书必须明确使用该开关。默认输出在忽略的`verification/stable-release/v<版本>/`，保存`receipt.json`、验证日志、三附件及`publish-state.json`；这些不提交。收据只适用于当前机器/输入/工具链，不能盲目拷贝CI成功标记。

新输入或失败检查不会产生可复用的成功收据；同一APK和输入命中后不改成功收据的原验证时间，不复写三附件。测试代码改变只重验证，不重建同一Release。初次本机签名拒绝测试需 unsigned 夹具时，仅重打包额外副本，不再构建/签名第二个发布APK。

两源均先查最新versionCode及目标Release；全部附件hash、大小、状态确认后才放行公开。两个服务不支持跨站原子事务，若第二边公开失败，检查点如实保留单边成功；下次重查后补剩余步骤，不撤回或删除已成功Release。缺服务端可靠SHA时停止，不把缺摘要当作匹配；需另行完整下载验收，当前不自动下载75MB APK。

真实与模拟计时见[PUBLISH_BENCHMARK](PUBLISH_BENCHMARK.md)。CI保持完整检查并新增脚本离线回归，发布收据不会伪造CI通过；不为等待相同提交的重复CI而反复构建，但CI失败必须单独报告。

Git连接超时但gh API正常时，先核对Windows已有本地系统代理；可为本次Git命令传`-c http.proxy=<已核对代理>`，不改全局设置、不关闭TLS验证、不反复盲试直连。2026-11-12已据此恢复正常Git读写路径。

调整未发布GitHub草稿的`target_commitish`时须同时保留`tag_name`并回读确认；只改目标可能使GitHub把草稿改名为untagged，后续按版本查找就无法续传。任何遗留草稿不自动删除。

## 补齐已发布版本而不重建APK

AGP的Git元信息可能让同源码重建后整包SHA变化；不能把新SHA覆盖到已公开版本。已有官方三附件可用`-ExistingAssets`：

```powershell
# 只读官方锚点＋本地签名验证，不上传，不需签名私钥：
pwsh -NoProfile -File scripts/Publish-Stable.ps1 -Mode Prepare -ExistingAssets 交付/发布附件/v1.8.5 -LegacyCompatibility -BuildToolsPath E:/skill/android-sdk/build-tools/36.0.0
# 获授权后才改成-Mode Publish -PublishAllowed，仍指向同一ExistingAssets。
```

此模式须有一个所选官方渠道已经公开、且三份摘要/大小完全一致；未公开的新包不能借此跳过完整验证。每次仍读取真实远端，签名收据按原APK字节和验证策略复用；它不冒充本次源码的构建收据。当前v1.8.5原安装包已实查可用于将来补源，但本轮未上传。
