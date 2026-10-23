# Campus Toolbox · 当前开发状态

最后更新时间：2026-10-23 11:15 +08:00（Asia/Shanghai）

## 当前版本与分支

- 对外项目名：Campus Toolbox（校园工具箱）；现有应用名称仍为“校园生活助手”，本轮不改名。
- Android 版本：1.3.1，versionCode 5；包名 `cn.campuslife.assistant`。
- 分支：`main`，跟踪 `origin/main`（首次发布前由无提交的 feature/campus-watermark 改名，未改写历史）。
- Git：首个源码基线提交 `224e4cdacc6e1bab0b24e4b3837f9882ce664d2a`，后续提交以 git log / 远程 main 为准。
- GitHub：https://github.com/dufan82/campus-toolbox ，已创建并推送公开仓库；未创建 Pull Request、GitHub Release 或安装附件。

## 当前开发目标

BUILD-001 / CI-001：移除构建入口对本机盘符/缓存的强制覆盖，接入 GitHub Actions 的 JVM、lint、APK 构建及报告。QA-003 只读分类现有警告。用户已明确授权验证后提交、推送并检查实际 CI 结果；不改业务功能、依赖版本或安装包版本，不代替真机验收。

## 正在执行的任务

- BUILD-001 / CI-001 进行中。开工本地/远程均为 b793063，工作区干净，线上尚无 workflow；用户本轮已授权推送与检查云端结果。
- 通用入口、镜像开关、Wrapper 校验、脚本回归与 workflow 已实现；PowerShell 7 / Windows PowerShell 5.1 各 10 项通过，actionlint 通过。实际 Wrapper 调用已完成，完整重跑 JVM / lint / APK 也通过；正在准备推送并验证云端。
- QA-003 只读分类已完成，见 docs/LINT.md；不等于修复所有警告。不上传私钥，不配置正式签名/Release/商店发布。

## 技术栈与目录

原生 Android，不是 Web、微信小程序、uni-app、React Native 或网页壳。

- Kotlin 2.0.21；Android Views / Canvas，Activity 页面与自绘预览。
- Gradle Wrapper 8.14.3；Android Gradle Plugin 8.13.0。
- compileSdk 36、targetSdk 35、minSdk 26（Android 8.0+）；Java / Kotlin 编译目标 17。
- 本机开发环境使用 JDK 21；源码构建仍以 Gradle 配置为准。
- 运行依赖：AndroidX Core KTX 1.13.1、ExifInterface 1.4.1；JUnit 4 / AndroidX Test 用于测试。
- SharedPreferences 中保存版本化 JSON；没有后端、账号系统、云同步或上传接口。

```text
app/src/main/.../ui/       工具箱、资料、编辑器、共用标签、预览和会话
app/src/main/.../image/    读取、EXIF 方向、Canvas 渲染、相册/文件导出
app/src/main/.../data/     本地配置、草稿与历史模板存储
app/src/main/.../express/  三个快递入口、校验、包名固定跳转与诊断
watermark-core/           纯 Kotlin 模型、布局、坐标、字段格式化和导出策略
app/src/test/             快递规则、异步任务和草稿测试
app/src/androidTest/      图片、界面、生命周期与覆盖升级测试
docs/                     历次实施和验证说明
verification/             仅本机验证脚本、日志和原生截图，不上传 Git
交付/                     仅本机安装包、源码 ZIP、说明与运行截图，不上传 Git
历史归档/                 仅本机旧版说明和设计参考，不上传 Git
```

## 已完成的任务与功能

- GIT-001：公开仓库建立，首次提交 74 个审查后的源码/测试/文档文件；不包含本机交付历史或用户数据，提交署名使用 GitHub 隐私邮箱。
- OSS-001 / OSS-002：用户选择无项目许可证；第三方说明、忽略规则、相对链接/路径检查和干净副本构建完成。

- WM-003：标题旁“只显示内容”复选框，默认纯值，取消显示字段名；记住编辑器/模板选择，旧配置缺少字段默认纯内容，不影响位置/命名。

- WM-001：六标签下方紧凑位置单选，四角和底部居中、默认右下；拖动后显示自由移动，重开记住选择。
- WM-002：1.3.0 实现纯内容，现由 WM-003 改为可切换；保留顺序、空值跳过、长文本换行和独立命名。
- 配置写入 v2，读取 v1/v2。仅旧编辑器未改过的默认左下迁移到右下；旧模板、自定义/已调样式位置及新版主动选的左下保留。

- DOC-001：四个根目录进度文件建立完成；已明确更新时机、测试证据规则和隐私/发布边界，当时记录的两个水印需求现已在 1.3.0 完成。
- 首页直接展示拼多多包裹、拼多多身份码、抖音订单；成功启动链接不弹成功 Toast，失败/首页回退保留说明。支持链接设置、诊断与抖音版本选择。
- 本机校园资料：学院、班级、姓名、座号、学号；可独立编辑、清空和保存，学号默认不展示。
- 系统相册选择 / 相机拍摄入口；JPEG / PNG、文件与尺寸检查、EXIF 1–8 方向处理、草稿恢复。
- 水印六字段编号标签：学院、班级、姓名、座号、学号、拍摄时间；按点选顺序排列，空值跳过。
- 编辑选项在上、照片预览在下；只保留当前一种设置，不显示多模板管理与四分页；旧模板数据不删除。
- 实时预览和拖动定位，与导出共用原图坐标下的布局/渲染；横屏和真实软键盘已有回归测试。
- 高清 JPEG / PNG、新文件保存与系统分享；导出命名默认班级_姓名_座号，可独立点选或手动改名，取消不输出，原图不覆盖。
- 会话重建、工作中禁用重复导出、命名弹窗旋转恢复及同名分享缓存隔离。
- 1.3.1 APK 已本地交付，签名沿用旧版；没有应用商店发布。

## 最近修改的文件

本轮工程维护：build.ps1、scripts/Test-Build.ps1、.github/workflows/android.yml、settings.gradle.kts、gradle/wrapper/gradle-wrapper.properties、.gitignore、README、AGENTS / STATUS / TASKS / CHANGELOG、docs/BUILD.md、docs/CI.md、docs/LINT.md。业务源码、应用/库版本和现有安装包不变。

历史首次发布整理：.gitignore、.gitattributes、README、根目录进度文件、THIRD_PARTY_NOTICES.md、LICENSES/Apache-2.0.txt、docs/GITHUB.md。

1.3.1 业务轮：Models.kt 的 contentOnly 配置、LocalStore.kt 开关读写、ContentPanel.kt 复选框、app/build.gradle.kts 版本；新增 ContentModeTest，补 PlainWatermarkTest 和 ExportDialogFlowTest，调整旧 APK 升级夹具构造方式；README/三份进度文件、1.3.1 说明与交付验证同步。

上一轮 1.2.0：`ui/FieldChips.kt`、`ContentPanel.kt`、`WatermarkActivity.kt`、`ExportDialog.kt`、`MainActivity.kt`，`express/ExpressLauncher.kt`、`ExpressActivity.kt`，`app/build.gradle.kts`，以及对应设备测试、README、版本说明和交付验证文件。UI / express 文件均位于 `app/src/main/java/cn/campuslife/assistant/`。

## 构建和测试结果

### 本轮 BUILD-001 / CI-001 / QA-003 实际执行

- 新 scripts/Test-Build.ps1 在旧脚本下 6 项全部失败（未调用项目 Wrapper）；改为通用入口后，补正单任务数组和附加参数转发，PowerShell 7 / Windows PowerShell 5.1 各 6 项通过。夹具在 verification/build-script-tests/，不上传 Git。
- 提交前独立审阅确认带引号/空白值会被 Windows 批处理改写、PS7 原生命令偏好会丢失失败退出码。新增 3 项回归先失败，再局部关闭该偏好并明确拒绝复杂参数，不实现多层 shell 转义；另补 shell 字符拒绝。修正 PS5.1 测试捕获 stderr、测试输入跨原生进程先丢引号的问题后，最终两种 Windows PowerShell 各 10 项全部通过；包含实际 caller 目录恢复检查。
- actionlint 1.7.12 校验 workflow 通过，工具下载后对比上游 SHA-256 一致；未新增应用依赖。Action 使用已核对的上游完整 SHA，Gradle 分发校验与官方一致。
- 本机首次官方 Gradle 下载极慢，一次 curl 尝试连接超时；只终止经 PID/路径/参数确认属于本任务的慢下载。通过镜像取得相同分发，SHA-256 与官方一致后预填本机 Wrapper ZIP 缓存，不改仓库官方 URL、不伪造缓存校验标记；新的实际 Wrapper 调用成功（1 执行 / 54 up-to-date）。
- 同版本已缓存工具链另行 --rerun-tasks 完整执行 :watermark-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline -PuseChinaMirrors=true，55 项 Gradle task 执行成功；44 JVM（28+16）通过，0 失败/错误，本轮 lint 0 错误、42 警告。APK SHA-256 仍为 c69c232e0ffc52ae5c0ea4747a124797765a73aa0666bf89e873da4792c47411。既有 5 条编译期废弃 API 提示未因维护而隐藏。
- QA-003 核对历史两份 XML：42 与 41 仅差一条 Gradle 更新提示，其余一致；没有证据据此屏蔽全部 Warning 或改业务。分类不是本轮 Lint 重新运行。
- 线上 workflow 尚待本轮实际推送、启动和核对；不会把语法校验视为云端构建成功。

### 历史首次 GitHub 发布准备实际执行

- 明确暂存 74 个文件；检查禁止路径、凭据模式、真实身份信息及长数字/邮箱候选。候选逐项为公开照片 URL、明确标记的测试资料和恶意链接测试，不是用户数据；未输出真实候选值。两个二进制文件分别为官方 Wrapper JAR 和公开测试照片，已核对来源/许可与图像元数据。
- 17 个相对 Markdown 链接可解析，git diff --cached --check 通过；gradlew 已设置 100755 可执行位。
- 从暂存区导出干净源码，无 local.properties、旧构建输出或本机 build.ps1；使用本机已缓存 Gradle 8.14.3 / SDK 36、JDK 21，在离线模式执行 :watermark-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug，BUILD SUCCESSFUL。
- 44 JVM（核心 28、应用 16）通过，0 失败/错误/跳过；本轮干净副本 lint 0 错误、41 警告（与历史在线检查的 42 不混记）。未在本轮重跑设备测试，也未验证全新电脑联网下载或 macOS / Linux。
- 干净副本 APK SHA-256 与已交付 1.3.1 完全一致：c69c232e0ffc52ae5c0ea4747a124797765a73aa0666bf89e873da4792c47411。
- 本机证据：verification/github-source-check-20261023-1032 下的两个模块 test-results、app/build/reports/lint-results-debug.xml 和 app/build/outputs/apk/debug/app-debug.apk。不随仓库上传。
- 首次推送后实际检查：GitHub API 返回 private=false、default_branch=main、license=null；远程根目录包含四份交接文件和两个模块，不含本机目录；git ls-remote 与本机首个 SHA 一致，匿名网页 HTTP 200。

### 历史 1.3.1 功能交付实际执行

- 执行 JVM + lint + APK + connectedDebugAndroidTest，BUILD SUCCESSFUL；44 JVM（核心 28+应用 16）通过，设备 34 项中 33 通过、1 项发布夹具按设计跳过，0 失败/错误。
- Android 16 / API 36 专用 emulator-5580；lint 0 错误、42 警告。证据 verification/final-suite-verified-v1.3.1.log。
- 启动时一次 offline 导致未执行测试；boot_completed=1 后，旧版缺少选项的回归预期失败，再实现并全套通过，见 red-mode-v1.3.1.log 与 red-mode-ready-v1.3.1.log。
- 验证两种文本、旋转与存储/模板记忆、旧缺省值、命名/位置独立；标签模式实际 PNG 保存，纯内容 PNG 由原有图片测试覆盖。截图等待原生勾选动画结束，避免捕获瞬态。
- 旧 1.3.0→1.3.1 覆盖升级私有偏好哈希一致、seed/verify 各 1 项通过；主 APK 不含测试图片。见 verification/release-verification-v1.3.1.json。
- 交付 APK 与实际安装验证的主应用 APK 哈希一致、签名沿用旧版，原生 editor/labels/export 截图及 docs/1.3.1-实施与验证.md 已保存。本轮未新增依赖或改动 AGENTS。

### 历史 1.3.0 验证

- 执行 :watermark-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:connectedDebugAndroidTest，BUILD SUCCESSFUL。
- 43 JVM（核心 27+应用 16）通过；Android 16 / API 36 专用模拟器中 32 项设备测试有 31 通过、1 项发布夹具按设计跳过，0 失败/错误。
- lint 0 错误、42 警告。日志 verification/final-suite-v1.3.log；三种比例、位置/拖动/重建、命名、原图保护、高清保存、横屏与真实软键盘均覆盖。
- 先运行新失败测试。首轮全套因 EditorFlowTest 的旧时间前缀断言失败，定位后更新两处字面预期，保留保存/恢复回归，再全套通过。red-core-v1.3.log、red-native-v1.3.log、suite-v1.3.log 保留过程。
- 旧 1.2.0 中写样本，覆盖 1.3.0 后非空私有设置哈希一致，seed/verify 各 1 项另行通过；新版确认右下和纯内容。见 verification/release-verification-v1.3.json。
- APK 实际安装、原生截图/PNG 保存已检查。交付 APK 与测试报告哈希相同，签名沿用旧版，主 APK 不含测试照片；独立只读审阅未发现已证实的 Critical/Important 问题。
- 详细记录 docs/1.3.0-实施与验证.md；不宣称所有厂商/平台均已验收。

### 已有 1.2.0 证据（本轮读取核对，不冒充本轮重跑）

- 2026-10-23 09:21 +08:00：`:watermark-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:connectedDebugAndroidTest`，日志记录 `BUILD SUCCESSFUL`。
- JVM：核心 25 + 应用 16 = 41 项，0 失败、0 错误。
- Android 16 / API 36 专用模拟器：28 项中 27 通过、1 项按设计跳过（显式发布升级夹具），0 失败、0 错误。
- lint：0 错误、41 警告，不等于“零警告”。
- 2026-10-23 09:22 +08:00：旧 1.1.0 APK 中写入测试夹具、覆盖 1.2.0 后读取验证，私有设置哈希保持一致；seed / verify 各 1 项另行通过。
- 证据：`verification/final-suite-v1.2.log`、`verification/release-verification-v1.2.json`、`docs/1.2.0-实施与验证.md`；当前本机 XML 结果也已核对。
- 本轮核对交付 APK SHA-256 与发布报告一致；测试样图不随主 APK 安装。

### DOC-001 历史文档检查

已执行并通过：四文件内容/UTF-8 可读性、7 个相对链接、所需状态栏目、任务编号、版本/分支/远程状态检查。

DOC-001 当时只改四文档，221 个既有文件指纹一致，未重跑应用测试或打包；这是历史结果，不用于声称本轮没有业务改动。

## 已知问题、技术债务及阻塞因素

1. **同步边界**：首次公开推送已完成，另一个会话需主动读取仓库/交接文件；后续本地更改仍需提交和推送，不是自动双向实时同步。
2. **项目许可暂缺是用户选择**：本轮已确认暂不添加 MIT 等项目许可证，不能描述为已完成开源授权。第三方许可与参考边界已单独说明；公开文件采用明确清单、隐私模式检查与二进制来源核对，不保证能发现所有未知形式的敏感信息，未来新增文件仍须审查。
3. **构建与测试可移植性**：本机 build.ps1 和专用升级脚本不上传；公开说明使用 Gradle Wrapper。干净副本使用缓存工具链已构建成功，但全新环境联网下载和通用 CI 尚未验证。
4. **设备兼容性边界**：Android 8–9、各厂商相机/相册、极大系统字体及第三方 App 最终落地页未完整真机验收；启动 Intent 成功不代表验证了目标页面。
5. **发布范围**：本轮只上传源码/测试/交接说明，不发布商店版本、GitHub Release 或安装附件；现有本地安装包及历史归档均保留。
6. **保留而未清理的历史逻辑**：旧 StylePanel、五种模板定义及模板读写模型仍在源码；界面未提供多模板入口。保留数据可读性，本轮不擅自删除或重构。
7. lint 警告仍待分类（历史在线检查 42，本轮离线干净副本 41），没有为发布进行无关重构。

本轮发布无剩余阻塞。无业务功能变更；许可证暂缺是用户选择，不擅自补全。

## 下一步计划

1. 后续任务先读四份管理文件，完成时更新并按用户授权推送。
2. 真机兼容、第三方目标页面、lint 分类和 CI 另行按优先级推进，不扩大到地图、批量或后台系统。
3. 后续如需 GitHub 安装附件或项目许可证，再按用户明确选择处理；本轮不擅自发布。

## 会话交接与更新规则

先读 AGENTS，再读本文件与 TASKS；版本历史查 CHANGELOG，详细证据再按需读 docs / verification。`docs/开发进度.md` 等原有说明保留为历史参考，本文件是当前交接入口。任务开始、里程碑、阻塞和收尾时同步更新；每条结果标清“本轮执行”或“历史证据”。不抄录完整聊天、个人资料或敏感日志。
