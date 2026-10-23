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
