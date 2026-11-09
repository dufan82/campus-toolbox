# 校伴 · 本地品牌升级验收

> 以下记录为1.8.1/code28品牌预览的本地验收。1.8.2/code29发布复用这套实现；发布状态以根目录STATUS.md为准。

日期：2026-11-09 17:58 +08:00。BRAND-001、NAV-001 本地完成，未正式发布。

## 安装与查看

- 测试安装包：`E:\Desktop\校园工具箱安装包\校伴-1.8.1-品牌预览-20261109.apk`。
- 工程原件：`E:\Desktop\CodexProjects\CampusLifeAssistant\交付\测试包\校伴-1.8.1-品牌预览-20261109.apk`。
- 矢量资源：`E:\Desktop\CodexProjects\CampusLifeAssistant\交付\品牌资源\校伴-品牌资源-20261109.zip`。
- 原生界面截图：`E:\Desktop\CodexProjects\CampusLifeAssistant\交付\品牌资源\校伴-界面预览-20261109`。

直接打开 APK 安装，不要先卸载。包名仍为 `cn.campuslife.assistant`，版本仍为 1.8.1 / code28，非 debuggable，沿用既有兼容证书。两份 APK 已校验大小与 SHA 相同；它是本地品牌预览，不是新的公开更新版本。若系统提示签名不一致或禁止同版安装，保留原应用，不要通过卸载清数据强装。需要确认手机当前证书后再处理。

SHA-256：`e837cd9a78e75d8918d37878424c8d64e9891dee0337772fffc91a9b9075be53`。

## 已实现

1. 手机桌面名称改为“校伴”，提供独立彩色图形、传统图标、Adaptive Icon、Android 13 单色图层、专用通知 alpha 小图标。
2. 按参考重画圆角阶梯路径和双节点，不裁切展示板。深海蓝 `#172B49`、青绿 `#35B6A0`、暖橙 `#F2A65A`、雾白 `#F5F7FA`；灰色仅作文字与分割线。
3. 英文 CAMPUS COMPANION、中文名称和标语使用系统字体与真实文字，不打包字体或汉字图片。
4. Android 12+ 原生启动屏为雾白底和独立符号；更早系统使用轻量 windowBackground。名称和标语在紧接的首页真实文字中呈现，没有新增 SplashActivity、倒计时或人工等待。实际冷启动录屏已取得。
5. 首页保留三个固定快递入口、普通工具的位置/自定义和今日课程逻辑，只换品牌与配色。没有加回已撤下的进出校、待办、收藏、批量工具。
6. 课表、快递、倒数日、水印、地图原生控件、我的、更新和反馈统一使用品牌控件；不强行在每页放 Logo。地图底图和用户选定的水印颜色不被品牌色覆盖。
7. 我的 → 关于校伴显示定位、标语、版本、原 GitHub 仓库和 CNB 渠道。README 已本地改名、加入 SVG 标志和原有真实功能说明；历史版本记录保留，远程 README 尚未改变。
8. 底部三标签采用固定空间顺序：首页 ← 课表 ← 我的。向右切换的新页面从右边进入；“我的 → 首页”从左边进入，不再沿用一律右进的系统默认效果；重用原 Activity/返回栈规则，不改工具 ID。

## 品牌源文件

- `docs/brand/logo_primary.svg`：横向组合，真实可编辑文字。
- `docs/brand/logo_symbol.svg`：透明独立符号。
- `docs/brand/logo_wordmark.svg`：中文/英文文字标志。
- `docs/brand/launcher.svg`、`launcher_monochrome.svg`：彩色与单色图标母版。
- `docs/brand/splash_logo.svg`、`notification.svg`、`palette.svg`：启动、通知、颜色。
- Android 对应文件位于 `app/src/main/res/drawable`、`mipmap-anydpi-v26/v33`、`values/brand_*.xml`，启动样式使用版本限定资源。

母版 108×108，路径 `M32,37H54V54H76V71H65`，线宽5.5，节点半径5。含线宽的图形在中心直径66dp安全圆内，48像素实际绘制仍可识别两个彩色节点；前景不烘焙遮罩，适应不同桌面形状。24项资源打包到 ZIP，并重新打开校验通过。

引用的桌面参考图文件未在当前磁盘取得，本次依据用户消息中实际可见的参考图重建；未改动用户原图。完整修改文件列表见本报告末尾的 `brand-changed-files.json`。

## 保留与兼容

- `app/build.gradle.kts` 与开工前字节一致：applicationId、code28、版本、ABI、签名配置、生产依赖未变。
- Manifest 权限集合未变。无新相机/定位权限、后台服务、字体或 SDK；地图四个 so 与校园原图 SHA 均与旧正式包相同。
- LocalStore、ScheduleStore、SchoolSyncVault、学校登录探针/客户端、每日同步引擎和排程、更新规则/仓库/CNB/GitHub/HTTP 客户端、PhotoExporter 源文件均与原件一致。
- 不改数据库、文件格式、SharedPreferences Key、相册输出目录、仓库名和地址。水印照片仍沿用旧“校园生活助手”相册目录，避免升级后相册分裂。
- CNB 优先更新、24小时缓存、忽略版本与下载校验保持原实现。code未递增，本地预览不会通过更新检查被当成新版本推送。
- 系统深色设置下仍使用统一浅色界面，禁止系统强制反色，避免照片/地图颜色改变；本轮没有实现第二套深色主题。

## 本次实际测试

以下均使用本次源码/构建，不复用历史通过结果。JDK21、Android SDK36，专用断网 API36 x86 模拟器 emulator-5582；没有操作个人手机或真实学校账号。

| 检查 | 命令/方法 | 结果 |
| --- | --- | --- |
| JVM/构建/Lint | `gradlew.bat :app:clean :watermark-core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease --offline` | 54秒构建通过；App290 + core29 =319项，零失败/跳过；正常双ARM Debug及同证书非调试 Release |
| Lint | 两份 XML 报告解析 | Debug 0错误/67警告，Release 0错误/69警告。包含既有建议及新矢量尺寸、独立备用资源提示；没有关闭检查 |
| 原生设备回归 | `adb -s emulator-5582 shell am instrument -w -r -e class <本轮12类列表> cn.campuslife.assistant.test/androidx.test.runner.AndroidJUnitRunner` | 61项全部通过；覆盖品牌、导航、快递/工具首页、课表、地图/倒数日非SDK流程、水印、反馈、更新/CNB协议及真实界面截图 |
| 不同显示条件 | BrandVisualTest，普通竖屏、双倍字竖屏/横屏、320dp小屏、系统夜间模式 | 5组×5项=25项通过，包含三个固定快递入口完整可见、配色对比、真实字标/版本/地址、Adaptive/单色图层、启动主题、通知alpha。实际尺寸记录在visual-configurations.json：1080×1920、1920×1080、880×1760，密度440 |
| 旧版覆盖 | frozen before.zip的原版1.8.1/code28安装→seed→`adb install -r`同证书新版→verify | seed与verify各1项通过；资料、水印配置/模板、课表、倒数日、地图、首页设置、快递配置、每日同步设置、加密凭据原字节及解密、WebView测试Cookie保留 |
| 底部方向 | 8项JVM + 4项Intent/动画设备测试 + 实际重用页面点击流与合成器帧 | 我的→首页真实画面由左侧进入；同标签不重复启动，导航flags保持原规则 |
| 启动屏 | 断网冷启动录屏与 `am start -W` | 原生Logo正确；单次模拟器TotalTime761ms，仅为本次观察，不当作真机性能基准 |
| 图标/资源 | SVG本地预览、9项静态检查、11 XML独立编译、全App链接、48px实际绘制 | 通过；API33+实际资源monochrome非空，通知透明底白色alpha |
| APK核验 | `apksigner verify --print-certs` / `aapt dump badging` / ZIP条目SHA | 通过：label校伴，包名/版本正确，非debuggable，armeabi-v7a与arm64-v8a，既有证书一致 |

同证书覆盖采用原始代码和新版代码的专用x86测试打包（去除无法在x86运行的ARM原生地图库），保持相同包名/code28/证书及真实存储与KeyStore；不是把去库包交给用户，也不是宣称完整ARM正式APK已在手机覆盖。交付的APK已恢复完整双ARM库和校园图。

测试中先发现启动主题属性需API29限定，以及新增测试字符串语法问题，均修正后完成全量构建。第一次61项回归中旧更新测试仍找已经撤下的ListView菜单，1项失败；改为定位现有settings_update行后完整61项重跑通过，未修改更新业务或跳过测试。所有原始日志保留。

## 同配置体积

| 配置 | 原1.8.1 | 校伴本地预览 | 增量 |
| --- | ---: | ---: | ---: |
| 非调试、同兼容签名、双ARM Release | 78,924,462字节 | 78,950,346字节 | +25,884字节，约25.28KiB |
| 双ARM Debug，原始源码冻结副本重新构建 | 79,965,489字节 | 79,975,914字节 | +10,425字节 |

增量来自矢量、品牌字符串、轻量关于页和导航动画；测试代码/截图/SVG文档不会打包进生产APK。没有拿Debug与Release混比，没有删除地图库来做小体积交付。

## 主要代码位置

工程根：`E:\Desktop\CodexProjects\CampusLifeAssistant`。

| 文件/区域 | 用途 |
| --- | --- |
| `ui/Brand.kt`、`ui/Ui.kt` | 品牌身份、四主色、共用按钮/文字/导航、关于页 |
| `ui/MainActivity.kt`、`ui/SettingsActivity.kt` | 原首页接入标志与标语、关于校伴入口 |
| `ui/MainNavigation.kt`、`res/anim/main_nav_*.xml` | 按三个标签左右关系选择切换方向 |
| `res/drawable/*`、`res/mipmap-anydpi-*`、`res/values*/styles.xml`、`brand_*.xml` | 桌面/自适应/通知/启动资源及品牌文本 |
| `AndroidManifest.xml` | 仅显示名、图标和首页启动主题 |
| `schedule/*WeekView.kt`、`planner/PlannerActivity.kt`、`express/ExpressActivity.kt`、`map/CampusMapActivity.kt`、`ui/WatermarkPreview.kt` | 现有原生界面/定位圈颜色；不改各核心算法 |
| `reminder/ReminderScheduler.kt`、`schedule/DailySchoolSync.kt`、`update/Update*.kt` | 通知小图标与展示名，排程/下载核心不变 |
| `Brand*Test.kt`、`MainNavigation*Test.kt` | 品牌、动画、升级和真实界面验证 |
| `README.md`、`AGENTS.md`首条产品名称、`STATUS.md`、`TASKS.md`、`CHANGELOG.md` | 本地品牌和交接记录，旧发布历史保留 |

完整逐文件清单：`E:\Desktop\CodexProjects\CampusLifeAssistant\verification\brand-xiaoban-20261109\brand-changed-files.json`。

## 手机验收与剩余事项

1. 不卸载原应用，直接安装桌面预览APK。查看桌面图标/名称、冷启动、我的→关于校伴，资料与课表是否仍在。
2. 首页三个快递入口均可直接点；切换首页/课表/我的，重点看我的回首页方向。
3. 检查学校课表导入和实际登录保留，水印从相册选图/拖动/保存；查看地图真实SDK加载、自己的位置和导航。不要为验收产生无关学校请求。
4. 设置中检查更新，预览与线上1.8.1同code，没有新增版本提示属于正常行为；新品牌正式发布需另行批准递增code。
5. REDMI/其他厂商桌面遮罩、ARM地图/GPS、真实学校会话及真实用户数据、完整ARM APK覆盖仍待手机验收；模拟器结果不代替这些。

本轮不增加新的Git提交/推送/Release。HEAD保留7e94ef0ac6ee2a64e6d99531f07f04727b8478f4；GitHub/CNB公开安装包仍是原1.8.1，等确认效果后另行发布。

## 原件和恢复

`verification/brand-xiaoban-20261109/before.zip`与`before-sha256.json`保存开工前已跟踪源码及SHA。`brand-source.patch`记录本轮差异；原来两个未跟踪的课表备份目录与gate-web保留，未覆盖。恢复时先备份现在改动，再把before.zip解到额外目录，对照SHA和差异选择恢复本轮文件；新增资源可按清单停用。不要对整个工作区执行reset/clean，不回退用户资料或删除历史APK。
