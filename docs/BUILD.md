# 构建、验证与交付限制

适用于本仓库当前源码和 1.7.x 更新准备流程。应用版本以 `app/build.gradle.kts` 为准；文档中的命令和示例不代表已发布版本。功能用途见根目录 README，技术环境、构建、发布准备和未验收事项保存在开发文档中。

## 工程

- 原生 Kotlin 2.0.21、Android Views / Canvas，没有 Web 前端或云端构建要求。
- `app` 负责 Android 界面、存储和系统集成；`watermark-core` 保存纯 Kotlin 水印模型与布局逻辑。
- Gradle 8.14.3、Android Gradle Plugin 8.13.0；Java / Kotlin 编译目标 17。
- Android `minSdk=26`、`targetSdk=35`、`compileSdk=36`。这些不是应用的 versionCode。

## 环境

- JDK 17+，本项目开发与 CI 使用 Temurin / JDK 21。
- Android SDK Platform 36、Build Tools 35.0.0（CI 固定版本）；SDK 位置通过 ANDROID_HOME、ANDROID_SDK_ROOT 或本机 local.properties 的 sdk.dir 指定。
- Gradle 8.14.3，由仓库内 Wrapper 获取，已配置官方分发 SHA-256 校验。第一次下载需要联网；--offline 不能跳过 Wrapper 首次下载。
- Windows 可用 Windows PowerShell 5.1 或 PowerShell 7；Linux 可直接用 ./gradlew，使用 build.ps1 时需要 PowerShell 7。

不要提交 local.properties、SDK、Gradle 缓存或签名文件。建议使用 ASCII 工程路径，允许空格；脚本路径回归不等于已解决 Kotlin 对所有中文路径的兼容问题。

## 通用入口

Windows 在项目根目录运行：

```powershell
.\build.ps1 -Task build
.\build.ps1 -Task test
.\build.ps1 -Task check
.\build.ps1 -Task install
```

- build：构建 debug APK。
- test：核心与应用 JVM 测试，不运行设备测试。
- check：JVM、Android lint、debug APK。
- install：安装到已连接设备；只在你明确要安装时使用。

脚本始终调用同目录的 gradlew.bat / gradlew；不会改写 JAVA_HOME、ANDROID_HOME、ANDROID_SDK_ROOT、GRADLE_USER_HOME、TEMP 或 TMP，也不再寻找固定 E 盘缓存。它在项目目录执行 Gradle，向调用者返回真实 Gradle 退出码，即使调用者开启 PowerShell 的原生命令异常偏好也不会把非零退出码改成 1。

通用入口只转发不含空白、双引号或 shell 特殊字符的常规 Gradle 参数。为避免 Windows 批处理静默拆分/删除字符，复杂值会明确拒绝，不回显参数值；请放入本机 gradle.properties 等合适配置，不提交密钥。需要连续构建时使用 --continuous，短选项 -t 会与 -Task 缩写冲突，不支持由此入口转发；完整 Gradle 命令仍可直接使用 Wrapper。

需要对本次进程指定缓存或临时目录时，由使用者设置环境变量，而不是改源码。原开发机可继续指定 E 盘缓存；其他电脑不必建立相同目录。

```powershell
# 依赖已完整缓存时使用；首次 Wrapper 下载仍需联网。
.\build.ps1 -Task check --offline

# 默认使用官方源，国内网络可按需显式启用镜像。
.\build.ps1 -Task check -PuseChinaMirrors=true
```

镜像选项只改变依赖仓库顺序，不上传照片、不替换库版本。企业网络限制或 Maven / Gradle 服务不可达仍会导致下载失败，不通过屏蔽测试伪装成功。

不用脚本时，Windows 使用 gradlew.bat，Linux / macOS 使用 ./gradlew，任务相同：

```sh
./gradlew :watermark-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK：app/build/outputs/apk/debug/app-debug.apk。JVM XML：两个模块的 build/test-results；lint XML / HTML：app/build/reports/lint-results-debug.*。

## 脚本回归

```powershell
pwsh -NoProfile -File scripts/Test-Build.ps1
# Windows PowerShell 5.1 也可执行：
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Build.ps1
```

回归在 verification/build-script-tests 下建立独立的有空格目录，用记录型 Wrapper 替身隔离外部 Gradle，实际执行构建入口：检查四种任务、常规参数转发、环境保留、工作目录恢复、原生命令异常偏好及失败退出码、复杂参数的安全拒绝。不会安装手机应用或联网下载依赖；这些回归不是完整 APK 构建的替代品。临时夹具保留供排查，不纳入 Git。

设备测试需专用模拟器和明确 serial。它会安装应用和测试包、写入夹具，不能默认在用户手机运行。例如，只在已经指定的测试设备上执行：

```powershell
$env:ANDROID_SERIAL = '<专用测试设备的serial>'
.\gradlew.bat :app:connectedDebugAndroidTest
```

设置 serial 不等于授权使用真实手机；不要把升级夹具默认运行在真实手机上。JVM 检查、CI 和模拟器结果不等于真机覆盖安装、厂商权限、相机或在线地图验收。

## 地图测试构建

1.4.0 新增固定版本高德 SDK；构建不要求提供 Key。需要真实地图时，在本机忽略的 `local.properties` 填 `AMAP_ANDROID_KEY` 或配置同名环境变量，不在 Gradle 命令行传值，不提交私有配置。环境变量优先，空值生成无地图 Key 的 APK，其余工具仍可用。

SDK 目前只有 ARMv7 / ARM64 原生库。x86 专用模拟器可追加 `-PmapUiTestWithoutNative=true` 做非 SDK 的 UI 回归；这个去库产物不能交付。正常 `assembleDebug` 不带该参数，须在交付前核对两个 ARM ABI 和原签名。真实在线地图与定位仍须 ARM 设备验证，详见 [地图说明](MAP.md)。

公开源码不含许可尚未确认的校园原图；需要原图的本地功能不能据此宣称在干净公开副本中已完整验收。不上传用户图片、登录态、身份资料或本机地图 Key。

## Release 与本地准备

当前仓库没有生产签名配置。直接执行下面的任务只会产生未签名 Release 分析包，不能当成可安装正式版：

```powershell
.\gradlew.bat :app:assembleRelease
# app/build/outputs/apk/release/app-release-unsigned.apk
```

现有本机交付使用 Android Debug 证书，CI 的 `campus-toolbox-ci-debug-*` 同样是临时调试产物，均不是正式发行包。不能把 Debug 包重命名后放入稳定 Release，也不能用调试签名兜底。正式证书与旧调试证书不同，Android 和应用内的签名校验会阻止直接覆盖；同一把调试证书仅用于本机过渡兼容。正式签名与用户数据迁移须另行获得授权，不能让用户先卸载旧应用来绕过校验。

已有正式签名 APK 后，使用本地准备器生成三个附件；该步骤不运行 Gradle、不签名、不联网、不上传：

```powershell
.\scripts\Prepare-Release.ps1 `
  -ApkPath 'E:\Desktop\CodexProjects\CampusLifeAssistant\交付\signed-input.apk' `
  -OutputDirectory 'E:\Desktop\CodexProjects\CampusLifeAssistant\交付\release-1.7.1' `
  -ExpectedSignerSha256 '<事先核对并固定的正式证书64位SHA256>' `
  -PreviousVersionCode 17
```

上述版本和 code 仅为示例，需替换输入文件、证书指纹和实际上一公开版本 code。首次正式发布之前才可将 previous code 设为 0。版本名和版本号从 APK 提取，不通过重命名文件或编辑 JSON 伪造。准备器要求包名 `cn.campuslife.assistant`、code 为 1..2100000000 的严格递增整数、1..40 位安全 ASCII 版本名、minSdk 恰为 26、非 debuggable、有效且唯一的固定正式签名；Android Debug 证书也单独拒绝。

可用 `-SdkPath`、`-BuildToolsPath`、`-JavaHome` 和 `-TempRoot` 指定本次工具位置，不修改环境变量或签名配置。APK 和输出可有中文/空格；aapt 始终读取单独的 ASCII 临时副本。若使用者的 TEMP 含中文，需要指定一个已存在的 ASCII `-TempRoot`。详细解析顺序、JSON 协议、防覆盖行为和人工发布前置条件见 [发布准备说明](RELEASING.md)。

CI的发布检查显式使用Build Tools35.0.0，避免被Runner预装的其他版本改变输出格式。若自动检测时提示无法完整识别签名，先显式指定已验证的工具版本，不修改证书或放宽校验。Windows35.0.0/36.0.0及Ubuntu24.04的35.0.0已有实际结果，不保证所有后续SDK工具的文本格式都兼容。

创建稳定公开 GitHub Release、签名配置变更、生成密钥和上传 APK 需要人的单独授权；源码同步授权不包含这些操作。更新只读取固定公开仓库，不在 APK 放 GitHub PAT，不需要付费服务。学校课表与照片仍按应用自己的本机处理规则工作，发布准备脚本不会读取这些数据。

## 发布脚本验证与边界

已有本地 Debug 和未签名 Release APK 时运行：

```powershell
pwsh -NoProfile -File scripts/Test-Release.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Release.ps1
# 非默认夹具位置可传 -DebugApkPath 和 -UnsignedApkPath；工具路径参数与准备器相同。
```

测试不构建、不生成任何密钥、不签名、不上传。真实工具验证现有调试证书与未签名包的拒绝，包括中文输入路径。其余分支只替换外部 aapt / Java 命令边界，实际运行解析、复制、SHA256、精确 JSON 输出、相对目录、重复运行、冲突前置检查、环境保留和临时目录清理。合成输入不是 APK，临时产物会删除，不能拿来安装或发布。

2026-10-26 本轮实际验证：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Release.ps1` 为 42 通过、0 失败；`pwsh -NoProfile -File scripts/Test-Release.ps1 -BuildToolsPath 'E:\skill\android-sdk\build-tools\35.0.0' -JavaHome 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'` 同样 42 通过、0 失败。前者自动发现本机 Build Tools 36.0.0，后者验证显式 35.0.0 与含空格 JDK 路径，两者均使用现有 JDK 21；这些绝对路径仅为本机测试记录，不是脚本默认值。`pwsh -NoProfile -File scripts/Test-Build.ps1` 为 10 通过、0 失败。先写测试时准备器尚不存在而失败；随后修复了测试夹具的变量遮蔽、5.1 空路径断言，以及相对输出误按进程目录解析的问题，最终两种 PowerShell 均通过。证据为命令 stdout / exit code，临时夹具不保留。本轮未运行 Gradle、模拟器或远端 CI，不据此声称应用构建或线上更新通过。

没有可用的正式签名正例 APK：真实正式签名成功路径尚未验证。合成命令边界的成功测试只证明本地输出流程，不证明真实正式 APK 的签名或安装。未生成生产或测试密钥。后续CI run40273645052在Ubuntu24.04/JDK21/SDK35.0.0实际完成42项发布脚本检查且全部通过；Windows PowerShell5.1修正后重跑同样42通过。此前云端自动工具选择产生3项失败，记录保留，详见CI.md。macOS、磁盘写入失败及其他工具组合仍未验证。首次启用更新代码仍需先手动安装过渡版本；已有旧应用不会自动获得检查代码。没有稳定公开Release时的404应提示“暂无正式版本”，不能当成“已是最新版”。
