# FB-001 意见反馈

## 当前接入（2026-11-10，本地1.8.2预览）

- 腾讯问卷已发布，真实填写地址：https://wj.qq.com/s2/28616104/3ezx/ 。不是编辑页或试答地址。
- 三题：反馈类型必选（功能建议、功能异常、课表问题、界面建议、其他），问题或建议必填，QQ/邮箱选填。所有人可填、无需登录验证、不将填答者保存为联系人；平台按联系信息题自动附加填答隐私确认，未关闭平台保护。
- 「我的 → 意见反馈」以系统ACTION_VIEW打开该地址，不先要求写本地草稿，不附加身份、课表、日志或截图。网络故障由网页处理；没有可用浏览器或系统拒绝时回退本地说明/草稿。
- 本地备用草稿仍为五类、1000字及可选联系方式；在线表单按腾讯问卷实际设置，不把本地校验说成远端限制。
- 已核验投放页“暂停”状态和公开链接、匿名无Cookie GET 200；设备测试截获HTTPS Intent并验证回退。没有提交真实测试答卷，接收后台仍待用户手动验收。
- 无新增生产依赖、权限或后台发送，不上传本机学校登录态。当前仅本地APK包含新配置，GitHub/CNB安装包未发布。

## 隐私和范围

反馈包不读取个人资料、登录状态、Token、Cookie、学校账号、课表、设备标识、截图或日志。无新增依赖、权限、后台任务、网络客户端或数据迁移；无后台自动发送。

按主任务补充了「我的 → 隐私说明」：只有用户主动开启每日课表同步才通过Android Keystore/AES-GCM加密保存本机Token，不保存密码或其他应用Cookie，关闭同步或退出登录清除该Token。反馈本身不保存或读取此Token；同步实现和验收由主任务负责。

初版交付时不改Gradle、根目录STATUS/TASKS/CHANGELOG、同步核心、ScheduleActivity、ScheduleTransferActivity或JobService；不提交、推送、发布、改版本或改签名。完整APK体积变化尚未测量。

## 初版文件记录（历史）

新增：

1. `E:\Desktop\CodexProjects\CampusLifeAssistant\app\src\main\java\cn\campuslife\assistant\feedback\FeedbackConfig.kt`：唯一问卷配置，初始为空。
2. `E:\Desktop\CodexProjects\CampusLifeAssistant\app\src\main\java\cn\campuslife\assistant\feedback\FeedbackRules.kt`：五种类型、本地草稿校验、HTTPS地址校验。
3. `E:\Desktop\CodexProjects\CampusLifeAssistant\app\src\main\java\cn\campuslife\assistant\feedback\FeedbackLauncher.kt`：外部ACTION_VIEW及本地回退；无内容预填或附带数据。
4. `E:\Desktop\CodexProjects\CampusLifeAssistant\app\src\main\java\cn\campuslife\assistant\feedback\FeedbackActivity.kt`：使用既有Ui辅助方法的原生说明/草稿页。
5. `E:\Desktop\CodexProjects\CampusLifeAssistant\app\src\test\java\cn\campuslife\assistant\feedback\FeedbackRulesTest.kt`：8项纯JVM测试。
6. `E:\Desktop\CodexProjects\CampusLifeAssistant\app\src\androidTest\java\cn\campuslife\assistant\feedback\FeedbackFlowTest.kt`：7项离线设备测试，外部Intent均截获、不访问真实问卷。
7. `E:\Desktop\CodexProjects\CampusLifeAssistant\docs\feedback-form.md`：本说明。

修改：

8. `E:\Desktop\CodexProjects\CampusLifeAssistant\app\src\main\java\cn\campuslife\assistant\ui\SettingsActivity.kt`：仅小型右对齐反馈入口，以及主任务要求的Token隐私说明文字。
9. `E:\Desktop\CodexProjects\CampusLifeAssistant\app\src\main\AndroidManifest.xml`：本轮仅增加一条不可导出的 `.feedback.FeedbackActivity` 声明；保留主任务并行加入的Job/Service等声明。

## 验证记录（2026-11-09，本轮）

未启动Gradle、模拟器或真实浏览器，避免与主任务并行构建。测试先于实现写入：独立编译首次因尚无Feedback相关生产类报unresolved reference，退出码1；不是一次已执行的行为断言失败。实现后独立编译并执行JVM测试，退出码0：

```text
JUnit version 4.13.2
........
Time: 0.023

OK (8 tests)
```

独立编译新增反馈代码及SettingsActivity通过（退出码0、无输出）；7项androidTest源码独立编译通过（退出码0、无输出），但**未在设备运行**。没有将这些结果写成完整Gradle构建、Lint或手机验收成功。

本机原始日志：

- `E:\skill\campus-build-temp\fb001-isolated-20261109\red.log`
- `E:\skill\campus-build-temp\fb001-isolated-20261109\green-compile.log`
- `E:\skill\campus-build-temp\fb001-isolated-20261109\green-tests.log`
- `E:\skill\campus-build-temp\fb001-isolated-20261109\android-main-compile.log`
- `E:\skill\campus-build-temp\fb001-isolated-20261109\android-tests-compile.log`

实际使用的独立JVM命令如下（PowerShell，工作目录为 `E:\Desktop\CodexProjects\CampusLifeAssistant`，全程只读现有缓存，不安装依赖）：

```powershell
$ErrorActionPreference='Stop'
$cache='E:\skill\gradle-cache\caches\modules-2\files-2.1'
function Jar($group,$name,$version) { (Get-ChildItem -LiteralPath (Join-Path $cache "$group\$name\$version") -Filter '*.jar' -Recurse | Select-Object -First 1).FullName }
$stdlib=Jar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.0.21'
$junit=Jar 'junit' 'junit' '4.13.2'
$hamcrest=Jar 'org.hamcrest' 'hamcrest-core' '1.3'
$annotations=Jar 'org.jetbrains' 'annotations' '13.0'
$compiler=@((Jar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.0.21'),$stdlib,(Jar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '2.0.21'),(Jar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.6.10'),(Jar 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'),(Jar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.6.4'),$annotations) -join ';'
$testCp=@($stdlib,$junit,$hamcrest,$annotations) -join ';'
$out='E:\skill\campus-build-temp\fb001-isolated-20261109'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$java=Join-Path $env:JAVA_HOME 'bin\java.exe'
# 首次执行时生产类尚不存在：退出1；原始输出见red.log。
& $java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $testCp -d "$out\red.jar" app/src/test/java/cn/campuslife/assistant/feedback/FeedbackRulesTest.kt 2>&1 | Tee-Object -FilePath "$out\red.log"
# 实现后：以下编译和JUnit均退出0。
$sources=@('app/src/main/java/cn/campuslife/assistant/feedback/FeedbackConfig.kt','app/src/main/java/cn/campuslife/assistant/feedback/FeedbackRules.kt','app/src/test/java/cn/campuslife/assistant/feedback/FeedbackRulesTest.kt')
& $java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $testCp -d "$out\green.jar" @sources 2>&1 | Tee-Object -FilePath "$out\green-compile.log"
& $java -cp "$out\green.jar;$testCp" org.junit.runner.JUnitCore cn.campuslife.assistant.feedback.FeedbackRulesTest 2>&1 | Tee-Object -FilePath "$out\green-tests.log"
```

Android独立编译另以现有 `android-36/android.jar`、项目已有Debug类目录作为classpath；androidTest加入缓存 `androidx.test` core1.6.1、monitor1.7.2、runner1.6.2 AAR中的classes.jar。该检查复用了旧Debug依赖类，不能代替主任务的完整构建。

交由主任务统一运行（以下尚未在本反馈任务执行）：

```powershell
# 工作目录：E:\Desktop\CodexProjects\CampusLifeAssistant
.\gradlew.bat :app:testDebugUnitTest --tests 'cn.campuslife.assistant.feedback.FeedbackRulesTest'
.\gradlew.bat :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
# 只选择专用离线测试设备；不得默认使用用户真机。
# 确认ANDROID_SERIAL后运行：
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.campuslife.assistant.feedback.FeedbackFlowTest
```

两项默认空配置测试用于本轮未开放入口。将来正式填入问卷地址时，应相应更新默认配置断言和入口跳转设备用例；其余HTTPS/草稿校验及捕获Intent测试不需要真实网络。

## 手机验收与恢复

1. 进入「我的」，确认底部说明下方右侧小入口，不是悬浮按钮；小屏/大字体可滚动触达。
2. 当前空配置：进入本地页，看到未开放提示、全部五类、1000字限制、选填联系信息。空类型或内容检查有明确提示，匿名内容检查仅显示未提交。
3. 旋转屏幕确认类别、内容、选填联系方式保留；返回后重新进入为空白。没有任何自动发送。
4. 将来配置真实HTTPS问卷后：主动点击入口才进入外部表单，检查地址没有自动增加私人数据；浏览器不可用时回到本地说明。当前尚无真实地址，未执行此真实联网验收。

已有主备份 `E:\Desktop\CodexProjects\CampusLifeAssistant\verification\daily-sync-20261109\before.zip` 在修改前逐字节比对一致。本轮前SettingsActivity SHA256为 `b1affbdc6fe659bbe55b95907ec6d6b621da5437a437177a3077951bcb406d0c`，Manifest为 `fc3a4a4ee9d2deda4a5561e0e4adfea1be7d050a5fd413c6e2c8d1b221456310`。

共享工程撤销反馈时，只逐项移除本清单的新增反馈文件、Settings反馈入口和Manifest的feedback activity一行；保留同步主任务代码与隐私说明。不要整份覆盖Manifest/Settings或使用git reset/clean，以免丢失主任务及其他既有修改。没有执行整项目恢复或修改历史备份。
