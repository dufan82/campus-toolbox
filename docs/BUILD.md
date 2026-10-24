# 构建与路径兼容

适用于当前 1.3.1 源码；这次只维护工程工具，不更换技术栈、依赖版本或应用签名。

## 环境

- JDK 17+，本项目开发与 CI 使用 Temurin / JDK 21。
- Android SDK Platform 36、Build Tools 35.0.0；SDK 位置通过 ANDROID_HOME 或本机 local.properties 的 sdk.dir 指定。
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

设备测试需专用模拟器和明确 serial，另见 README。不要把升级夹具默认运行在真实手机上。

## 地图测试构建

1.4.0 新增固定版本高德 SDK；构建不要求提供 Key。需要真实地图时，在本机忽略的 `local.properties` 填 `AMAP_ANDROID_KEY` 或配置同名环境变量，不在 Gradle 命令行传值，不提交私有配置。环境变量优先，空值生成无地图 Key 的 APK，其余工具仍可用。

SDK 目前只有 ARMv7 / ARM64 原生库。x86 专用模拟器可追加 `-PmapUiTestWithoutNative=true` 做非 SDK 的 UI 回归；这个去库产物不能交付。正常 `assembleDebug` 不带该参数，须在交付前核对两个 ARM ABI 和原签名。真实在线地图与定位仍须 ARM 设备验证，详见 [地图说明](MAP.md)。
