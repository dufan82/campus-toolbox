# 每日课表检查与意见反馈 · 本地开发报告

> 2026-11-13 SCH-015本地修复：先比较全部同名本地未来课程与学校的日期/课名/教师/教室/节次多重集，而不是只比较registry.managed。来源未知或受保护但完全相同不误报、不写入、不认领来源；真实差异独立记录本地/学校值及来源未知、手改、删除、可能改名和具体冲突课程等原因。详情不再只比较已应用部分；旧冲突统计缺少明细时提示重新核验，不能把安装新版当作重新检查。数据、CAS、原子备份、历史与保护机制不变；本轮没有访问真实学校账号，手机重新检查仍待验收。未知来源的真实变化仍不能自动覆盖，逐门编辑可用；原有selected认领参数没有生产UI入口，本轮未增加强制认领流程。证据与验收见verification/school-conflict-20261113及verification/story-columns-20261113。

> 2026-11-12 SCH-014本地预览：当前入口是课表→管理→检查更新。检查页分开显示检查完成状态、学校差异与本机应用计数；有冲突但其余课程已更新时，两种结果同时显示。新结果只增加可选历史字段，旧记录能保守回退，不改变SchoolAutoMerge/SchoolFutureMerge/ScheduleStore。165项相关JVM及28项模拟器离线验证通过；真实学校与07:00手机验收尚未执行，未发布。

> 发布补记：2026-11-09已获用户授权以1.8.1/code28同步GitHub和CNB，非调试兼容签名安装包及三附件核对通过。下文保留发版前本地开发阶段的原始构建与测试说明；当前发布状态以STATUS和CHANGELOG为准。

## 本次结果

已实现 SCH-010 / FB-001。现有 Kotlin / Android Views、包名、1.8.0 / versionCode 27、签名配置、正式交付和旧数据保留。没有提交、推送、创建 Release 或发布正式更新。已发布 1.8.0 安装包 SHA256 核对未变。

### 每日课表检查

入口：**课表 → 管理 → 每日自动检查课表**。默认关闭。开启时说明后台只读用途，正常登录学校后读取完整学期，并确认导入；已有本地课表也可在完整预览中点 **确认本次登录用于每日检查（不改课表）**，不必整表替换。确认成功后从下一次07:00开始。

- 使用平台 JobScheduler。唯一 JOB_ID=20700 / `school-timetable-daily-0700-v1`；一次性任务执行完重新计算设备当地下一次07:00。网络约束、持久任务、重启/时间/时区/升级恢复；进程意外中断后可安全补查。不开精确闹钟，不用常驻轮询服务。普通退出App后可执行，系统强行停止后须重新打开；省电、关机、断网会延后，不保证准点。
- 已评估 WorkManager：现项目没有引入，最低API26，可直接使用系统调度完成当前单任务要求，故没有添加WorkManager或其传递依赖。[平台JobScheduler](https://developer.android.com/reference/android/app/job/JobScheduler)、[WorkManager接入说明](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started)。
- 原有原生Token仅在内存。本次新增**用户主动开启且确认完整学校课表后**的Android Keystore AES-GCM凭据存储，密文放noBackupFilesDir；只保存本应用正常学校课表登录取得的只读Token及学期绑定，不保存密码、微信Cookie或其他应用凭据。课表导出和备份不含Token。关闭开关或清除学校登录会删除后台密文。
- 后台继续使用已有四个白名单只读API，顺序读取完整学期。HTTP/响应401、403按需要重新登录处理，清除后台凭据并暂停调度；其他网络或异常响应保留数据，下次计划再检查。不把普通WebView保留会话当作后台可直接使用的凭据；没有凭据时零学校请求。真实平台过期响应及长期有效性仍需手机确认。
- 学校没有在现有解析字段中提供稳定课程ID。现转换器按长度前缀编码的完整字段生成确定性UUID，并非每次随机。自动合并进一步比较**按实际日期展开的课程指纹多重集**；教室、教师、星期/节次、周次变化只重建受影响同名课程组的未来部分，原有历史及完全没变的记录/ID保留，旧未来时段不会与新时段重复。
- 保留手动新增、修改、删除、地点关联和来源不明的旧课。新增`schoolProtectedCourses`原始指纹，旧`schoolProtectedIds`仍可读取。冲突逐课程组跳过，不让一处保护阻止其他已确认课程更新。
- **保守边界**：没有稳定学校ID时，存在本地撤销/删除记录的新课程名称可能是被删课程改名。因此这类无法匹配的新增会跳过，原有其他学校课程仍可更新；不会擅自恢复用户删课。同名多时段作为保守冲突组处理。未登记来源的旧课仍需通过既有手动预览明确确认，不自动认领。
- 整体学期、作息不一致，缺周/格式错误、作息读取警告，均不写。完整取数后先计划，再检查本地快照/日期/开关代次，写前备份并原子保存。进程级单飞锁、持久同日去重、存储CAS阻止重复/并发覆盖；关闭时取消任务和在途工作，不再新发下一条学校请求。
- 没变化不写课表和撤销备份、不通知，只更新检查记录。成功应用变化只发一条汇总；没有通知权限也正常保存。设置显示最近检查/成功时间/结果，登录失效时首页提供轻量重新登录入口，三个固定快递入口不变。已有每周手动检查提示会参考自动成功时间，避免重复催促。

### 意见反馈

**我的页面靠下、右对齐的小图标和文字**。目前没有真实问卷，显示“反馈入口暂未开放，敬请期待”。本地草稿含五类必选、必填内容最多1000个Android文本长度单元、可空QQ/邮箱；只检查草稿，不上传、不假报提交成功，离开后不保存，旋转可恢复临时输入。

启用真实问卷：修改 `app/src/main/java/cn/campuslife/assistant/feedback/FeedbackConfig.kt` 中 **FeedbackConfig.FEEDBACK_FORM_URL**，填写实际创建的HTTPS问卷地址并重新构建。配置有效后直接经系统ACTION_VIEW打开外部表单，不附加草稿、身份或课表。表单创建者还须配置相同五类、必填1000字限制和选填联系信息，关闭强制身份采集。没有浏览器时本地提示，不冒充提交成功。详见 `docs/feedback-form.md`。

## 主要代码

| 文件（项目根目录下） | 作用 |
|---|---|
| schedule/DailySyncEngine.kt | 调度时间规则、状态机、同日去重、并发锁、注入式只读源与通知 |
| schedule/DailySyncScheduler.kt | 唯一持久网络Job、JobService及系统时间/重启恢复Receiver |
| schedule/DailySchoolSync.kt / DailySyncPreferences.kt | 开关、认证代次、生产适配、单条更新通知及状态保存 |
| schedule/SchoolSyncVault.kt | Keystore AES-GCM密文，无密码和备份导出 |
| schedule/SchoolAutoMerge.kt | 未来出现次数差异、历史保留、逐课程组保护与统计 |
| schedule/ScheduleStore.kt | 旧保护记录兼容、原始保护指纹、CAS与备份原子提交 |
| schedule/SchoolPortalClient.kt / SchoolPortalJson.kt | 区分授权失效，避免作息请求吞掉登录失效/取消 |
| schedule/DailySyncSettingsActivity.kt / ScheduleActivity.kt | 每日检查设置页和课表管理入口 |
| schedule/ScheduleTransferActivity.kt / SchoolImportReminder.kt | 明确确认后台凭据、退出清除、旧提醒衔接 |
| ui/MainActivity.kt / SettingsActivity.kt | 登录失效小提示、右下反馈入口和隐私说明 |
| feedback/* / res/values/feedback_strings.xml | 空配置、HTTPS验证、原生草稿页与外部打开 |
| AndroidManifest.xml | 非导出页面/Receiver、仅系统可绑定的JobService；不增加uses-permission |

Java/Kotlin主文件均位于 `app/src/main/java/cn/campuslife/assistant/`。新增/扩展JVM测试为 `SchoolAutoMergeTest`、`DailySyncEngineTest`、`FeedbackRulesTest`；设备测试为 `DailySchoolSyncDeviceTest`、`DailySyncVisualDeviceTest`、`DailySyncRebootDeviceTest`、`FeedbackFlowTest`及既有学校协议回归。完整修改文件和原始SHA见verification。

## 实际测试与构建

本次Windows/JDK21，Gradle离线缓存；专用API36断网模拟器emulator-5582，不使用真实账号、学校登记或真实反馈表单。

```powershell
./gradlew.bat :app:clean :watermark-core:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease --offline
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PmapUiTestWithoutNative=true --offline
# 初次设备组：Gradle connectedDebugAndroidTest，指定ANDROID_SERIAL=emulator-5582
# 最终组：adb install -r 主APK、测试APK后执行
adb -s emulator-5582 shell am instrument -w -r -e class cn.campuslife.assistant.schedule.DailySchoolSyncDeviceTest,cn.campuslife.assistant.feedback.FeedbackFlowTest,cn.campuslife.assistant.schedule.SchoolFutureStoreDeviceTest,cn.campuslife.assistant.schedule.SchoolWireProtocolDeviceTest,cn.campuslife.assistant.schedule.SchoolImportDeviceTest,cn.campuslife.assistant.schedule.DailySyncVisualDeviceTest,cn.campuslife.assistant.schedule.RedesignScheduleTest,cn.campuslife.assistant.RedesignHomeTest cn.campuslife.assistant.test/androidx.test.runner.AndroidJUnitRunner
# 大字体：font_scale=2.0、wm size=2340x1080，同步/反馈两项布局操作
# 重启：DailySyncRebootDeviceTest的syncBootStage=seed → adb reboot → syncBootStage=verify
```

最终结果如下。实际原始输出均保留在 `verification/daily-sync-20261109`，并非历史测试替代。

- JVM：App280 + core29 = **309项通过**，0失败/0跳过。
- Debug与Release Lint：各**0错误、64警告**。原有63警告；新增一处UseKtx建议来自需要检查同步commit布尔结果的设置存储，不关闭检查来变绿。
- Debug、未签名Release构建成功。版本和签名配置未改；未签名Release仅作同配置体积测量，不能直接安装。
- 最终断网设备组：**47项通过**（verified-device.log）；双倍字体横屏另**2项通过**（verified-large.log），两页截图已实际检查。首轮35项通过。随后组合组曾有1项旧“未知导入时间”断言因新测试遗留自动成功时间而失败，已修正新测试的状态清理；不是删除或关闭旧测试。
- 模拟器实际重启前后持久任务恢复通过（seed/verify两次调用）。无Token，未发学校请求。
- 真实ARM地图、真实学生登录/授权失效、厂商省电和跨日07:00执行、真实问卷尚未手机验收。

## 依赖、权限与体积

生产依赖及uses-permission与开工基线一致，无WorkManager、Room、微信SDK新增、定位/相机/无障碍/精确闹钟权限新增。JobService只在系统安排工作时运行，不常驻。

采用开工源码隔离副本与当前源码各自**干净构建**，相同JDK/Gradle/双ARM资源与相同配置比较：

| 配置 | 基线字节 | 本次字节 | 增量 |
|---|---:|---:|---:|
| Debug | 79,915,662 | 79,965,493 | +49,831（约48.7KiB） |
| 未签名Release | 78,903,366 | 78,920,366 | +17,000（约16.6KiB） |

增加主要为同步/反馈Kotlin代码、Manifest与少量字符串；两种构建的打包对齐和代码分布不同，所以不混比。地图双ARM四个so保留。此前增量打包测量记录也保留，但以这张干净构建表为准。没有将Debug和已发布签名Release直接相减。

## 本地交付与手机验收

本地**开发调试包**：`交付/测试包/校园工具箱-1.8.0-每日课表与反馈-开发调试-20261109.apk`；桌面目录另有同名已核对SHA副本。它仍是1.8.0/code27，不是正式更新包，**不会通过国内源或GitHub弹出新版本**。不要为了安装它卸载或清空正式版；需要测试时使用合适的测试手机/用户环境，正式覆盖升级另行签名验收。

1. 课表→管理→每日自动检查，确认初始关闭，开启后显示等待登录。正常登录学校平台，完整读取后确认后台使用或正常确认保存；可只确认后台凭据，不替换已有课程。
2. 回设置看状态/固定07:00。允许应用按系统规则后台运行并联网，翌日查看最近检查时间。通知可拒绝，结果仍须保存在设置中。学校是否真正调整课程由真实学校课表核对，不能把假数据测试当作线上已同步。
3. 本机手动编辑一门课程或删除，下一次同步核对其保留，其他明确课程可更新；存在无法匹配的新增时查看冲突摘要，不强行覆盖。
4. 关闭开关后确认任务取消，课表及最近记录仍在；重新开启需重新明确确认学校登录。退出学校登录会暂停，首页/设置显示重新登录。
5. 我的→右下意见反馈，检查空配置提示、类型/内容/匿名草稿；当前没有提交功能。问卷创建并配置后，再验证手机浏览器打开、问卷后台接收和实际匿名设置。

## 差异、备份与恢复

原始源文件包及SHA：`verification/daily-sync-20261109/before.zip`、`before-sha256.json`；本轮差异和新增文件清单在同目录。已有两处untracked历史备份与gate-web保留，不纳入清理或提交。

如需回退代码，在额外副本先核对差异，只恢复清单中本轮修改的原文件，再移除清单标明的本轮新源码文件；不要覆盖后续他人修改，不接触App用户数据、私有配置或历史APK。不直接恢复整份工程覆盖现目录。应用内自动更新前的课表副本可经原“学校导入/备份”预览恢复，不能静默替换真实课表。
