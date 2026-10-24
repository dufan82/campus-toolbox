# QA-003 · 既有 Lint 警告分类

2026-10-23 只读分析，业务源码未修改；下表来自已有 XML，不冒充本轮重新执行 Lint。

证据：本机 app/build/reports/lint-results-debug.xml（10:13，0 错误 / 42 警告）及 verification/github-source-check-20261023-1032/app/build/reports/lint-results-debug.xml（10:30，0 错误 / 41 警告）。这些本机原始文件不上传 Git。

| Issue ID | 根报告 / 干净副本 | 代表位置 | 判断与后续优先级 |
| --- | ---: | --- | --- |
| DataExtractionRules | 1 / 1 | AndroidManifest.xml:9 | 提示补低版本备份规则。当前 allowBackup=false，新版提取规则排除了数据；未证实泄露。P2，后续结合低版本/OEM 实机复核，不为消提示改变隐私设置。 |
| OldTargetApi | 1 / 1 | app/build.gradle.kts:11 | targetSdk 35、compileSdk 36 的版本跟进提醒。P2，结合兼容测试安排升级，不在工程维护中直接改目标版本。 |
| DrawAllocation | 2 / 2 | WatermarkPreview.kt:41、50 | 绘制时分配 Paint / RectF，有性能改进空间；没有卡顿或崩溃测量证据。P2，先测量再决定缓存绘制对象。 |
| ViewConstructor | 3 / 3 | ContentPanel.kt:13 | 也涉及 FieldChips、StylePanel；当前通过代码创建，未发现 XML 引用，不是已证实的加载失败。低优先级。 |
| SetTextI18n | 12 / 12 | ContentPanel.kt:68 | 中文字面量与拼接影响未来翻译，未证实当前中文内容错误。低优先级。 |
| UseKtx | 21 / 21 | LocalStore.kt:53 | API 简写建议；不能为简写丢掉 commit 失败检查或 Canvas 恢复操作。低优先级。 |
| GradleDependency | 1 / 1 | app/build.gradle.kts:25 | Core KTX 更新提示，不是漏洞证明；本轮不做依赖升级或完整漏洞审计。低优先级。 |
| AndroidGradlePluginVersion | 1 / 0 | gradle-wrapper.properties:3 | 名称容易误导，实际历史消息是 Gradle 8.14.3 有 8.14.6 更新，并非报告 AGP 不兼容。低优先级。 |
| 合计 | 42 / 41 | | 全部是 Warning。 |

按 ID、严重级别、消息、文件、行列归一化项目路径后比较，差异只在最后一条更新提示，其余 41 条（含重复次数）一致。两个报告均来自 lint 8.13.0；缺少更新检测器日志，不能断定离线检查就是差异的唯一原因。

静态核对未发现必须把上述 Warning 全部提升为 CI 阻断项的已证实问题。继续保持 Error / Fatal 使构建失败，不增加基线隐藏警告、不全量 suppress，也不把“分类完成”写成“警告全部修复”。真机、厂商迁移与依赖安全尚未完整验收。

## 后续本轮实际重跑

根报告路径会被新构建覆盖，前述时间是首次读取记录，不把当前文件当作不可变的原件。本轮 --rerun-tasks 重新执行后本机为 42 条 Warning；云端 [run 39894551766](https://github.com/dufan82/campus-toolbox/actions/runs/39894551766) 的 XML 已下载核对，为 0 错误、45 条 Warning。

云端仍是上述 8 类，GradleDependency 从 1 变为 4，其余数量与本机 42 条报告相同。新增的是 ExifInterface、AndroidX Test runner、Test JUnit 的更新提示；不是新增漏洞证明，不在本轮自动升级。不同更新检测结果不能相互冒充“新增业务缺陷”或“已修复警告”。

## MAP-002 / 1.4.0 本机实际结果

2026-10-23 `:app:lintDebug --offline -PuseChinaMirrors=true`（与 JVM/assemble 一起执行）成功，XML 为 0 错误、44 条 Warning，仍是上述 8 类。UseKtx 从 21 增至 23，其余数量与既有本机 42 相同；增加的是地图隐私存储的 API 简写建议，不是已证实安全缺陷。没有 baseline、全量 suppress 或为消除警告改变 commit 结果检查。证据为本机 `verification/map-002/production-build.log` 和 `app/build/reports/lint-results-debug.xml`；本轮未推送，因此没有 1.4.0 云端检查结果。
## 1.4.1 公共地点测试版补充（2026-10-23）

本轮最终正常ARM构建的 lintDebug 为0错误、45警告：UseKtx24、SetTextI18n12、ViewConstructor3、DrawAllocation2，以及AGP版本/依赖更新/OldTargetApi/DataExtractionRules各1。较MAP-002本机44警告增加1项UseKtx；没有新增错误、baseline或suppress。此45不是历史GitHub Actions的45，不代表本轮CI已经执行。证据：本机verification/map-006-final-arm-build.log及app/build/reports/lint-results-debug.xml。

## 1.4.2 原图对照与标点版补充（2026-10-24）

本轮无去库参数的 `:app:lintDebug` 实际成功，XML为0错误/47警告：UseKtx25、SetTextI18n12、ViewConstructor4、DrawAllocation2，以及AGP版本/依赖更新/OldTargetApi/DataExtractionRules各1。相比1.4.1增加2条：

- `CampusReferenceView` 的ViewConstructor：新Canvas视图使用带回调构造器，由代码创建，不从XML加载；不是已复现的加载崩溃。未为消除建议添加无调用方构造器或suppress。
- `CampusReferenceDialog` 的UseKtx：建议将ColorDrawable写法换成KTX简写，不是已证实业务缺陷，本轮不改动。

原DrawAllocation仍为水印预览的2项，没有新增原图onDraw分配警告。工具链还提示SDK XML版本不同及供应方.so无法strip，Gradle将其原样打包并正常完成。证据：本机verification/map-007-final-arm-build.log及app/build/reports/lint-results-debug.xml；未推送或重跑云端CI。

## 1.4.3 名称版（2026-10-24）

无去库参数实际执行lintDebug/assembleDebug成功，仍为0错误/47警告，未新增baseline或suppress。证据verification/map-008-final-arm-build.log及当前XML；没有新的云端CI结果。供应方.so继续原样打包。

## 1.5.0 自定义地图版（2026-10-24）

正常ARM构建实际执行lintDebug成功，0错误/48警告：UseKtx26、SetTextI18n12、ViewConstructor4、DrawAllocation2，以及AndroidGradlePluginVersion/GradleDependency/OldTargetApi/DataExtractionRules各1。相对1.4.3增加1项：CampusMarkerLayer.kt:19建议用KTX createBitmap代替Bitmap.createBitmap，非已复现故障。本轮未新增依赖、baseline或suppress，不为简写而改绘制/持久化语义。

现存onActivityResult、startActivityForResult及表单SOFT_INPUT_ADJUST_RESIZE等废弃API编译提示另行保留；供应方.so无法strip仍原样打包。最终审查修复后重建证据verification/map-009-reviewed-final-arm-build.log与app/build/reports/lint-results-debug.xml，首轮map-009-final-arm-build.log保留；未运行新云端CI。

## 1.5.1 地图精简版（2026-10-24）

实际正常ARM lintDebug/assembleDebug成功，仍0错误/48警告，未新增依赖、baseline或suppress。首轮证据verification/map-011-arm-build.log；审查修复后最终verification/map-011-final-arm-build.log及当前XML同为0/48。已随本轮移除地图文件导出的startActivityForResult/onActivityResult代码，不代表其他历史废弃API或48条警告均已修复；供应方.so继续原样打包。无本轮云端CI。
