# GitHub Actions 自动检查

配置：.github/workflows/android.yml。实际执行结果以对应 Actions run 为准，不把配置文件存在当成运行成功。

## 触发和检查

每次 push、pull_request 或 Actions 页面手动运行时，在 Ubuntu 24.04 执行：

1. 获取代码，配置 JDK 21，校验 Gradle Wrapper。
2. 安装 SDK Platform 36 和 Build Tools 35.0.0。
3. 运行通用构建脚本回归。
4. 运行核心 JVM、应用 JVM、Android lint 和 debug APK 构建。
5. 上传测试 / lint 报告；失败时也尝试保留报告。全部成功后保存 CI debug APK。

同一分支的新推送会取消仍运行的旧任务，单次最多 25 分钟。报告和 APK 构建产物保留 7 天，不产生 GitHub Release 或商店发行版。

## 如何判断状态

打开仓库的 Actions → Android checks → 具体运行：绿色表示这些自动检查通过，红色则打开失败步骤查看原因。报告在运行页面的 Artifacts 中；部分下载操作可能要求登录 GitHub。

JVM 测试通过不等于真机验收；云端不执行 connectedDebugAndroidTest，不运行旧 APK 覆盖升级夹具。设备权限、厂商相册、相机和第三方应用最终页面仍需人工验证。

## 安全和安装边界

- 仓库 token 仅 contents:read，checkout 不保留凭据；没有 pull_request_target，也不配置项目私钥或签名 secrets。
- 外部 Action 固定到已核对的完整提交 SHA，升级时需检查上游；Gradle 分发同时使用官方校验值。
- PR 只读 Gradle 缓存；不上传用户照片、应用私有数据、SDK 或签名密钥。
- 只上传明确的测试/检查报告与生成的 debug APK，不打包整个工作区。
- 云端 debug 签名通常与用户已安装版本不同，并且可能随运行改变。CI APK 只用于独立测试，不要为覆盖安装而卸载已有应用或清除资料。正式签名和稳定发行另行决定。

没有在本流程中开启付费服务或第三方云端业务；如果 GitHub 拒绝运行或提示额度/计费要求，应如实记录，不自行购买。
