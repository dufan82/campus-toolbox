# 本地发布准备

## 1.7.1兼容过渡发布

2026-10-26用户已明确批准同证书、关闭调试的兼容过渡版，以及GitHub安装包/元数据公开；同时确认本App高德公开使用和校园导览图分发许可。以下例外不是新正式证书配置，也不是允许任意调试包发行。

签名构建读取四个环境变量`CAMPUS_SIGNING_STORE_FILE`、`CAMPUS_SIGNING_STORE_PASSWORD`、`CAMPUS_SIGNING_KEY_ALIAS`、`CAMPUS_SIGNING_KEY_PASSWORD`。必须全部提供或全部不提供；缺项报错，未提供仍生成未签名Release，CI不自动取得私钥。`deviceTestBuildType=release`仅显式选非调试设备测试目标，默认仍为debug；模拟器去ARM参数不能用于交付。

准备已有签名包时显式传`-LegacyCompatibility`，且`-ExpectedSignerSha256`必须固定为历史证书`f6b9669399e0f791f16542dff896474f803408645e84a07650bdcd98e0b83eb1`。实际证书必须相同、签名有效且APK非debuggable；包名、整数版本增长、完整性、附件防覆盖等原有检查不变。默认不传此开关时继续拒绝所有Android Debug证书。客户端仍信任已安装证书，不从元数据取证书、不绕过系统安装。

发布时先用审核源码创建draft，在同一Release上传APK、update.json、SHA256SUMS.txt并核对大小和哈希，再公开为非draft/非prerelease版本。Release标题及说明写“兼容过渡版”，说明可覆盖本机旧包、CI临时证书包不在兼容保证内，不要求用户卸载。下方正式证书策略适用于未启用兼容例外的正式模式，不再表示本次发布未获授权。

`scripts/Prepare-Release.ps1` 仅把已经签好的正式 APK 验证并整理为本地附件。它没有网络、Gradle、Git、签名、密钥生成或上传步骤，不改变生产签名配置。环境、常规构建命令和实测边界见 [BUILD.md](BUILD.md)。

## 先区分现有过渡包和正式包

现有本机交付 APK 使用 `CN=Android Debug` 证书；2026-10-26 实查本地 `app-debug.apk` 签名有效且为该证书，`app-release-unsigned.apk` 则未通过签名验证。包的文件名含 release、非 debuggable 或签名工具报告“Verifies”，都不足以证明它是正式包。准备器同时拒绝 Android Debug 的证书名称和本项目已知的旧调试证书 SHA256，即使调用者把该指纹传为预期证书也拒绝。

旧调试证书只用于保持本机过渡包的覆盖兼容。换成正式证书后，Android 及应用内安装前的签名检查会拒绝直接覆盖旧调试版；元数据不能改变这一点。不自动卸载、不清空数据、不降低签名检查、不用调试签名补救。正式证书选择、签名配置、备份/导入与兼容过渡方案另行获得人的确认。本轮没有创建任何生产或测试密钥。

CI 的 Debug APK 仅供检查和测试，不能作为稳定频道附件。源码提交/推送的授权也不包含创建 GitHub Release、APK 上传或签名配置变更。

## 输入和工具

必填参数：

| 参数 | 含义 |
| --- | --- |
| `-ApkPath` | 已签名的正式 APK；原文件只读，1 字节至 512 MiB |
| `-OutputDirectory`（也可用 `-OutputDir`） | 本地附件目录，支持中文、空格与相对路径 |
| `-ExpectedSignerSha256` | 从可信渠道事先核对并固定的正式证书 SHA256，64 位十六进制，不带冒号；允许大小写输入 |
| `-PreviousVersionCode` | 人工核对的上一已公开版本 code，0..2100000000 整数；仅首次正式发布前用 0 |

不要从本次候选 APK 或下载来的 update.json 自动采信期望证书；固定指纹由发布负责人维护。脚本不联网，无法核实上一公开 code 是否填写真实，负责人须先核对。

工具选择顺序：

1. `-BuildToolsPath` 直接指定包含 `aapt.exe` / `aapt` 和 `lib/apksigner.jar` 的 Build Tools 目录。
2. 否则按 `-SdkPath`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、仓库忽略的 `local.properties` 中 `sdk.dir`、Windows 的 `%LOCALAPPDATA%/Android/Sdk` 查找 SDK。在其 `build-tools` 下选择同时包含所需工具的最高已安装数字稳定版本，不下载新工具。显式 SDK 路径无效时报错，不悄悄改用其他 SDK。
3. Java 使用 `-JavaHome`、`JAVA_HOME`，最后才是 PATH 中的 Java。指定 JDK 无效会报错。通过 Java 直接运行 apksigner.jar，避免 Windows `.bat` 对空格/中文路径的拆分；Gradle 环境要求见 BUILD。
4. `-TempRoot` 默认服从当前系统临时目录，须已存在且路径为可打印 ASCII。aapt 不可靠地支持中文 APK 路径，因此每次在此目录创建 `campus-release-<GUID>`，复制为 `input.apk`，所有验证、哈希及输出使用同一份副本。TEMP 含中文时显式指定其他 ASCII 目录，不改写 TEMP / TMP。

不修改 `JAVA_HOME`、SDK 变量、Gradle 缓存或调用者的工作目录。相对输出目录按 PowerShell 当前目录解析。临时目录清理只针对本次创建并再次核对父目录和 GUID 名称的子目录，不递归删除用户指定的 TempRoot、源码、输入或整个输出目录。

示例（各值需按实际情况替换，不包含签名步骤）：

```powershell
.\scripts\Prepare-Release.ps1 `
  -ApkPath 'E:\Desktop\CodexProjects\CampusLifeAssistant\交付\signed-input.apk' `
  -OutputDirectory 'E:\Desktop\CodexProjects\CampusLifeAssistant\交付\release-1.7.1' `
  -ExpectedSignerSha256 '<固定正式证书64位SHA256>' `
  -PreviousVersionCode 17 `
  -SdkPath 'E:\skill\android-sdk' `
  -JavaHome 'C:\Program Files\Java\jdk-21' `
  -TempRoot 'E:\skill'
```

脚本可在 Windows PowerShell 5.1 或 PowerShell 7 调用。失败会抛出错误，独立 `-File` 运行返回非零；不会转为调试签名或继续上传。

## 验证和输出协议

要求包名精确为 `cn.campuslife.assistant`；`versionCode` 为 1..2100000000 的整数，严格大于 previous code；`versionName` 匹配 `[A-Za-z0-9][A-Za-z0-9._-]{0,39}`；`minSdk` 恰为 26；禁止 `debuggable`。签名必须由本地 apksigner 验证成功，只有一个完整证书身份，匹配固定的 SHA256 且不为 Android Debug。未签名、签名无效、多个签名者、证书不匹配和版本倒退全部拒绝。不支持由这份脚本更换证书或安排签名轮换。

成功输出恰好三个目标文件（不清理目录内其他文件）：

- `campus-toolbox-VERSION.apk`：使用 APK 自身的安全 ASCII versionName 命名，字节保持不变。
- `update.json`：UTF-8 无 BOM、数字字段为整数；没有下载地址、证书、Token、额外业务字段或私有资料。
- `SHA256SUMS.txt`：两行小写 SHA256，分别为 APK 和 update.json；哈希与文件名间有两个空格，不包含校验文件自身。

以 APK 的 versionName 为 `1.7.1`、versionCode 为 `18` 举例，update.json 字段集合及类型如下。`size` 和 `sha256` 的示意值不能用于实际发布，必须使用准备器计算的真实值：

```json
{
  "schemaVersion": 1,
  "packageName": "cn.campuslife.assistant",
  "channel": "stable",
  "versionCode": 18,
  "versionName": "1.7.1",
  "tag": "v1.7.1",
  "minSdk": 26,
  "assetName": "campus-toolbox-1.7.1.apk",
  "size": 123456,
  "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
}
```

不从文件名推断版本，不允许任意附件名或手动指定 tag。size 是 APK 字节数，sha256 是同一验证副本的 64 位小写十六进制 SHA256。

准备器先暂存三个文件，再检查全部目标。已有同名文件内容完全相同时复用，不重新写入；任一不同则在写入任何目标前拒绝。没有强制覆盖参数。后续版本须选择单独目录，避免 update.json 冲突。磁盘故障或另一个进程并发写入仍可能留下部分新文件，脚本使用“不覆盖”的复制操作保护已有内容；这种情况应排查后换新目录，不把文件系统级原子发布当成已保证。

## 人工授权后的公开发布

1.7.0时只完成本地工具，未获APK发布授权。1.7.1已获得本页开头所列的兼容过渡发布授权，实际进度与验收见STATUS；下列流程继续用于固定公开仓库 `dufan82/campus-toolbox`：

1. 从可信历史核对 previous code 和固定正式证书，独立实跑准备器，核对三份附件、APK 版本和 ARM 库；不用去 ARM 原生库的模拟器包。
2. Release tag 与 JSON 的 `tag`、APK versionName 精确对应，例如 `v1.7.1`；稳定版应为已公开的非 Draft、非 Prerelease Release，而不是只推一个 Git tag。
3. 将本地生成的 APK、update.json、SHA256SUMS.txt 放在同一个 Release，APK 资产名与 JSON 精确一致；更新客户端下载固定仓库中该 tag 的附件，不由 JSON 指向任意站点。
4. 按当次授权核对公开附件下载和应用内更新，再记录实际结果。不能根据本地脚本通过或 CI Debug 构建成功宣称线上升级已完成。

公开仓库的读取与本地脚本不需要把 PAT 放入 APK，也不引入付费服务。发布者凭据不得写入 APK、源码、JSON、日志或文档。未提供实际正式签名正例时，成功解析合成命令输出也不能代替真实签名、覆盖安装或线上下载验收；本轮测试和未验证分支详见 [BUILD.md](BUILD.md#发布脚本验证与边界)。
