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

地图模块的 `AMAP_ANDROID_KEY` 是可选配置，CI没有这项凭据。CI编译出的地图页会提示未配置，不能用来验证真实底图或定位。校园原图也不在公开仓库里；缺图不影响构建。每次推送的结果以Actions对应提交为准，旧运行不代表新代码通过。

## 安全和安装边界

- 仓库 token 仅 contents:read，checkout 不保留凭据；没有 pull_request_target，也不配置项目私钥或签名 secrets。
- 外部 Action 固定到已核对的完整提交 SHA，升级时需检查上游；Gradle 分发同时使用官方校验值。
- PR 只读 Gradle 缓存；不上传用户照片、应用私有数据、SDK 或签名密钥。
- 只上传明确的测试/检查报告与生成的 debug APK，不打包整个工作区。
- 云端 debug 签名通常与用户已安装版本不同，并且可能随运行改变。CI APK 只用于独立测试，不要为覆盖安装而卸载已有应用或清除资料。正式签名和稳定发行另行决定。

没有在本流程中开启付费服务或第三方云端业务；如果 GitHub 拒绝运行或提示额度/计费要求，应如实记录，不自行购买。

## 实际验收（2026-10-23）

[代码基线 5f7fc46 的成功运行](https://github.com/dufan82/campus-toolbox/actions/runs/39894551766)：Ubuntu 24.04，10 项脚本回归和 44 项 JVM 全部通过，0 失败/错误/跳过；lint 0 错误、45 警告；APK 与报告上传成功。XML 和 APK 均已下载核对，测试样图不在主 APK 中。

首次运行 39894075600 曾因预期拒绝用例残留非零退出码而假红，Android 构建未执行；已修复测试全通过时显式 exit 0，再完整跑通，没有跳过门禁。Windows PS5.1 / PS7 也按同类 CI 调用方式验证返回 0。
