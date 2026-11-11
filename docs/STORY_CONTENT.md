# 随记模块交接

## 当前状态（2026-11-11）

小故事已更名随记；来源与许可、正文分离，列表固定前三篇，阅读页提供作者/查看原文、收藏、字号与前后篇。十条选文与核实缺口见[SUIJI_CONTENT.md](SUIJI_CONTENT.md)。旧12篇生成示例仅归档，原ID/收藏/进度/草稿不变。真实投稿沿用已有CloudBase配置；新增精选后台本轮仅本机Mock，未部署。

下文保留2026-11-10模块开发历史，其中“服务未配置”和原始12篇目录不再代表当前状态。

---

## 历史模块交接

本轮仅新增 `story/` 源码、`stories.json`、对应 JVM/设备测试及本文档。首页、Manifest、ToolCatalog、ToolNavigation、Ui/ToolGlyph、根文档、版本和其他业务数据由主会话负责，本模块未修改。无提交、推送或发布，无新增依赖、权限、网络 SDK 或后台任务。

## 集成接口

完整包名均为 `cn.campuslife.assistant.story`：

```xml
<activity android:name=".story.StoryActivity" android:exported="false" />
<activity android:name=".story.StoryReaderActivity" android:exported="false" />
<activity android:name=".story.StorySubmissionActivity" android:exported="false" />
```

```kotlin
// 首页或工具入口，不要求额外参数。
startActivity(Intent(this, StoryActivity::class.java))
// 稳定 ID，不传正文或诗词索引。
startActivity(StoryReaderActivity.intent(this, "story-001"))
// 必须通过 launcher，配置专用 HTTPS 时直接开浏览器；否则进入本机草稿。
StorySubmissionLauncher.open(this)
```

阅读参数：`StoryReaderActivity.EXTRA_STORY_ID`，值为 `cn.campuslife.assistant.story.STORY_ID`。
投稿回退参数：`StorySubmissionLauncher.EXTRA_UNAVAILABLE_REASON`；内部值 `browser_unavailable` / `invalid_configuration`。正常入口无需设置。

独立配置：`StorySubmissionConfig.PUBLIC_FORM_URL = ""`。没有真实投稿地址，不拿意见反馈问卷作为故事接收页。上线前另行配置并核验真实 HTTPS 专用投稿表、审核与撤稿方式；URL 解析通过不等于已经验证服务。浏览器仅收到配置 URL，不附草稿、昵称或其他用户数据。空配置与启动失败都不提交，不自动公开，也不把草稿加入故事列表。

## 页面与本机状态

- 列表只有标题、顶右投稿、分类/收藏筛选和故事卡片；正常态没有介绍段和篇数提示。未读卡片不显示“尚未阅读”，收藏或有进度时才增加一行。空分类一句空态。
- 分类稳定枚举：`LIFE/人生`、`GROWTH/成长`、`WARMTH/温暖`、`TWIST/反转`、`OTHER/其他`。其他分类保留，不凑内容。
- 阅读：16/17/18sp，系统字体缩放照常生效；滚动、收藏、返回列表、顺序循环换一篇。未知故事 ID 显示不可用和返回列表，不误开第一篇。
- 投稿：标题2–30、必选分类、正文100–5000及计数、昵称最多30（空值显示匿名）；字数按去首尾空白后的 Unicode 码点计算。输入上限按原始码点控制，不截断超长粘贴；校验不通过也允许保存未完成草稿。
- 两项权益确认分别为“本人原创，或已取得合法投稿与展示权限…”和正式投稿后审核、非独占展示许可。默认未勾选，不因勾选发送内容。
- 必要提示仅保留未开放/本机保存、简短审核及撤稿说明；本机可清除草稿（须确认）。撤稿/版权问题指向“我的→意见反馈”，仅提供标题/ID与处理请求，不发送故事正文，模块没有调用反馈传输接口。

唯一私有文件：`filesDir/stories-local-v1.json`，schema `version=1`。复用 `AtomicJsonFile` 原子写入/失败保留能力，保存收藏 ID 集合、各 ID 的0–1进度、16–18sp字号及一份未完成草稿。模型、ID、文件均与诗句及其他业务独立；合法但未知的 `story-*` ID 原样保留，目录更新不清空收藏。未知 schema、无效 ID、坏字段、损坏 JSON 均报错保留原文件；仓库每次先读最新状态再变更，不用默认值覆盖损坏数据。

`StoryRepository` 的独立串行 `story-io` 线程异步读取资产和存储，串行合并变更；首页不调用它就不安排工作。读取成功前不保存默认空状态。正文按重新布局后的滚动比例恢复；草稿和字号同时有 Bundle 与本机保存，旋转/重建时优先恢复内存快照，退出前排队保存。字体和进度分支、草稿分支各只修改本模块相应字段。

## 内容与来源许可

以下12篇均为本次面向本项目新写的虚构短篇，不是实际人物纪实、现代网文转载或用户投稿；无“精选转载”条目。`authorLabel=原创短篇` 是内容标记而非真实作者，`source=本项目创作`。没有伪造真实作者、投稿人、出版物或来源链接。

| 稳定ID | 标题 | 分类 | 正文字数 | 叙事方式 |
|---|---|---|---:|---|
| story-001 | 末班渡船的空座 | 人生 | 499 | 渡口停航与旧搭档和解，第一人称 |
| story-002 | 鞋盒里的第三份简历 | 人生 | 529 | 修鞋工失业、社区试工，生活场景 |
| story-003 | 雨天营业的照相馆 | 人生 | 528 | 三格胶片与家庭旧照，物件串联 |
| story-004 | 替补席上的录音机 | 成长 | 534 | 乐队替补面对嫉妒与节奏失误 |
| story-005 | 地图上没有的台阶 | 成长 | 507 | 无障碍路线实测与作业纠错 |
| story-006 | 不会响的风铃 | 成长 | 522 | 架空山城工匠，触觉与反光试作 |
| story-007 | 冰箱门上的欠条 | 温暖 | 509 | 合租误会与互助记账 |
| story-008 | 寄往山里的声音 | 温暖 | 518 | 修理邮包、旧磁带与隔代相处 |
| story-009 | 夜班窗口的半碗粥 | 温暖 | 523 | 夜班店员与清扫工，克制对话 |
| story-010 | 失物招领处的红雨伞 | 反转 | 541 | 三人认领同物，多线索释疑 |
| story-011 | 钟楼慢了四分钟 | 反转 | 531 | 校钟调查，老人反向揭破善意 |
| story-012 | 最后一页的差评 | 反转 | 549 | 童话批注与缺页，书中书 |

表中字数为去首尾空白后的正文 Unicode 码点数（含标点与段落换行），不是外部出版统计。阅读时间按300字/分钟向上取整，为估算。

编辑验收保持本批12篇、每篇约400–900字；该选编约束只在内容测试中。运行时 `StoryAsset.decode` 接受100–20000字正文及非空署名/来源，便于后续审核长稿或公版作品；仍严格校验 schema、唯一稳定 ID、合法分类。将来添加公版或授权作品时应逐篇另记作者、来源、依据与必要署名，不能沿用“本项目创作”。

本轮不为工程擅加 MIT、CC 等许可证，不声称存在第三方授权书或已取得真实投稿许可。新写内容随本项目交付使用；对外许可沿用项目权利人决定，本文不构成项目整体开源许可或对外再授权。

## 新增文件

`app/src/main/java/cn/campuslife/assistant/story/`：

- `StoryModels.kt`：独立内容、分类、草稿、本机状态、校验错误模型。
- `StoryRules.kt`：码点计数、边界校验、筛选、换篇、ID/HTTPS/字号/进度规则。
- `StoryRepository.kt`：资产编解码、独立原子存储、异步仓库。
- `StorySubmissionConfig.kt`：独立空投稿 URL。
- `StorySubmissionLauncher.kt`：外部专用投稿入口及原生回退。
- `StoryScreen.kt`：仅本模块使用的 Ui 辅助，不改公共组件。
- `StoryActivity.kt`、`StoryReaderActivity.kt`、`StorySubmissionActivity.kt`：三页面。

其他：`app/src/main/assets/stories.json`、`app/src/test/java/cn/campuslife/assistant/story/StoryRulesTest.kt`、同目录 `verify_story.py`、`app/src/androidTest/java/cn/campuslife/assistant/story/StoryStorageDeviceTest.kt`、同目录 `StoryScreensDeviceTest.kt`、本文档。验证过程文件只在 JVM 测试目录的 `.verification/` 中。

## 本次验证与待验

没有运行 Gradle，不与主会话构建抢占。先新增核心失败测试，再实现；首次直接调用缓存 Kotlin 编译器，因 `StoryCategory/StoryDraft/StoryRules` 等尚不存在而编译失败（exit 1），原输出为 `.verification/red.txt`。这是编译级红测，不宣称已经观察到设备行为失败。

已直接调用本机缓存 Kotlin 2.0.21 编译器及 JUnit 4.13.2，5个 `StoryRulesTest` 通过；12篇内容的唯一 ID、来源标记、分类与400–900字静态检查通过。独立 Android 源码也曾对既有宿主 classes.jar 和 API36 android.jar 编译成功；不等同于 APK 构建或设备验收。最终命令、退出码、stdout/stderr、时间及关联源码SHA256见 `.verification/results.json`，以其中最终记录为准。

最终独立复验：上述5个JVM、12篇内容检查通过，9个模块生产源码文件及两个设备测试类均编译成功（所有命令exit 0）；设备测试源码有1项 `scaledDensity` 弃用警告，无编译错误。该编译检查使用已有测试monitor AAR，未安装或升级任何依赖，未运行设备用例或生成真实截图。

复验脚本不下载、不使用 Gradle：

```powershell
python app/src/test/java/cn/campuslife/assistant/story/verify_story.py `
  --compiler-lib <现有Gradle发行包lib目录> `
  --app-jar <已有宿主classes.jar> --android-jar <已有API36/android.jar> `
  --monitor-aar <已有androidx.test.monitor的aar文件>
```

`--monitor-aar` 只做设备测试源码编译，不执行设备测试；脚本会在自身 `.verification/` 下解出测试用 classes.jar，不改依赖。编译后的临时 jar 可删除，源码与原始日志保留。

设备用例本会话未执行：`StoryStorageDeviceTest` 6项覆盖本批内容、长内容/非固定署名来源、未知ID与草稿往返、损坏保护、串行合并、旧文件不受影响；`StoryScreensDeviceTest` 3项覆盖列表去多余提示、草稿/确认/其他分类重建恢复、阅读字号/进度及未知ID回退。后者会拍真实界面图，不生成模拟截图；必须在主会话完成 Manifest 注册后的专用可丢弃模拟器上运行，不在用户真机跑。

Flow截图位置（执行成功才有）：设备应用缓存 `cache/story-flow-screenshots/` 下的 `story-list.png`、`story-submission.png`、`story-submission-rights.png`、`story-submission-restored.png`、`story-reader.png`。测试只快照/恢复本模块的私有文件及其原子备份，不访问课表、水印等存储；截图不会自动上传。读取测试图可由主会话使用专用模拟器 `run-as` 导出。

仍由主会话完成：整合后的 JVM/设备实际结果、页面截图目检、大字体/横屏与交互验收、lint、APK完整构建。真实投稿服务尚未配置，真实收件/审核/撤稿接收不在本轮完成项。

## 恢复

全部交付都是新文件，无被本模块覆盖的旧源文件，也没有对旧业务数据做迁移。源码SHA256随验证日志保存。回退时由主会话先撤回自己添加的入口与三个 Manifest 注册，再仅移除上述新文件；保留 `stories-local-v1.json` 即可保留用户故事数据。不要卸载、清除应用数据或删除其他模块文件。不提供会清空用户数据的恢复脚本。

## 主会话整合结果（2026-11-10）

已注册并接入普通工具，字号与收藏固定在阅读页顶部；默认显示“较小/标准/较大”，不会在界面写技术字号单位。最终主会话实际执行资产/存储、阅读/收藏/滚动/旋转、投稿草稿、空配置不外发及浏览器失败回退测试，均通过；原始日志及最终APK版本见`verification/reading-20261110`和本轮交付说明。以上独立编译记录属于模块开发阶段，不替代整合验收。
