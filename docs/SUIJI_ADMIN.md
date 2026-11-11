# 随记管理 · 本地交接（2026-11-11）

## 本轮结果与边界

基于原CloudBase投稿/审核服务增加管理员精选草稿创建、编辑、来源/授权管理、整数稳定排序、隐藏/恢复和撤下。公开字段严格投影，未授权完整草稿/rightsNote仅管理员可读；公开编辑在事务重新判权，撤销授权清空公开全文。复用原五集合、repository、匿名投稿、幂等、服务端管理员校验、事务、计数与审计，无新CMS/依赖。

管理页栏目改随记，内部action/ID/集合保持；公开ID仍story-cloud-*，管理ID仍submission-*。前三篇仍归Android本地管理，不由云端覆盖。

**真实云端仍旧版本，本轮无commit/push/部署、真实云调用或真实数据库操作。** 未修改Android、根STATUS/TASKS/CHANGELOG、原配置/权限/索引/package/lockfile，不构建APK。旧API保留，新管理接口仅本地/Mock可验。

### 120 / 20000 限额跟进已完成

管理员精选创建/编辑：title为2–120码点，content为0–20000码点。服务端editorial校验、editor.js、表单maxlength（120/20000）、可见提示与CONTRACT均已同步。普通学生submitStory仍为title 2–30/content 100–5000；原学生校验文件SHA256未变。

此补充修改7个cloudbase文件：src/editorial.js、admin/editor.js、admin/index.html、admin/app.js、test/editorial.test.js、admin/tests/editorial.test.mjs、CONTRACT.md。新增管理员创建/编辑边界、超限1拒绝、BMP/补充平面字符及学生投稿限制不放宽回归；审核/授权/隐私逻辑不变。原件、红绿日志和该次差异在cloudbase/verification/suiji-admin-limits-20261111/。

最终交接复核上述7项内容摘要与限额测试版本一致，再仅复跑Node Backend/Admin；本轮不重跑未改的浏览器全套，也不重复验证Android。Android适配及最终设备验收由主会话负责，本交接不代报其结果。

## 新接口速查

完整字段、约束、响应和错误码以[CONTRACT](../cloudbase/CONTRACT.md)为准。均POST {action,payload}，管理员Bearer逐次验证。

- `createEditorialStory {editorialId:UUID,title,category,...管理字段}` → {id,status:"pending",duplicate}。默认未核实授权，只建草稿；同owner+UUID+输入精确重试幂等。
- `updateManagedStory {id,revision,patch,confirmPublic?}` → {id,revision,published,hidden}。可编辑原投稿/精选，patch为管理字段子集；版本冲突409；仍公开的修改需confirmPublic=true。
- 复用 `approveStory {id,revision?}` 发布，新网页绑定当前revision，旧id-only兼容；拒绝和撤下仍原action。撤下不可重发。

管理字段：title/category/content/summary/authorName，以及sourceType/sourceUrl/publisherName/sourceWorkTitle/commentLocator/rightsStatus/rightsNote/editorialRank/excerpt/hidden。
来源类型为douyin_post/douyin_image/douyin_comment/other_platform/user_submission；旧submission归一兼容。
全文仅authorized/public_domain/user_authorized可公开，其余详情content为空、列表content永远为空。新editorial缺授权不会继承user_authorized。sourceUrl只做公网HTTPS语法检查，不抓取外网；excerpt≤80码点。
未授权外部作品须独立介绍/必要摘录和来源链接，绝不从私密草稿自动摘要。评论作者与作品发布者分离。
公开按rank升序、初次publishedAt/ID降序；沿用旧sortKey索引，不迁移。公开变化使v2游标失效返回INVALID_CURSOR，需从首页刷新。

## 文件清单

修改：
- cloudbase/src/service.js、validation.js、http.js：服务端管理动作、严格校验、版本并发控制、白名单DTO和稳定分页。
- cloudbase/admin/app.js、api.js、index.html、styles.css：沿用审核页扩展来源/授权编辑及公开确认。
- cloudbase/scripts/local-admin.js：仅补editor.js静态白名单。
- cloudbase/test/service.test.js、local-admin-server.test.js、cloudbase/admin/tests/api.test.mjs：扩展原契约/资源回归。
- cloudbase/CONTRACT.md、README.md、admin/README.md：当前接口与本地边界。

新增：
- cloudbase/src/editorial.js：来源/授权验证、公开投影、兼容rank编码。
- cloudbase/admin/editor.js：纯表单映射与码点校验。
- cloudbase/test/editorial.test.js、test/offline-network-guard.cjs：业务/HTTP/持久化/隐私回归及测试专用外网阻断。
- cloudbase/admin/tests/editorial.test.mjs、tests/test_editorial_browser.py：表单与实际回环Mock+Edge流程。
- cloudbase/scripts/verify-suiji-admin.js：离线验收入口。
- 本文件；cloudbase/verification/suiji-admin-20261111/为本地原件、摘要、差异、原始日志、退出状态及截图，不作部署材料。
- cloudbase/verification/suiji-admin-limits-20261111/保存限额补充的7项文件清单、原件/差异、红绿测试与SHA；cloudbase/verification/suiji-admin-final-20261111/保存最终Node复跑命令/原始日志、当前完整文件摘要及本次文档差异。

## 离线命令与证据

当前限额跟进仅需Node测试，在cloudbase目录执行：
```powershell
node scripts/run-tests.js
node --test admin/tests/api.test.mjs admin/tests/static.test.mjs admin/tests/editorial.test.mjs
```
最终离线执行注入现有test/offline-network-guard.cjs，禁止Node外网连接；准确命令、环境、时间与退出状态以cloudbase/verification/suiji-admin-final-20261111/commands.json为准。最终Backend 59/59、Admin Node 20/20，退出均0；新增回归各2项。浏览器本轮未运行，不用历史数量冒充当前结果。

以下为基础功能阶段的全量入口（历史，此次未运行）：项目根node cloudbase/scripts/verify-suiji-admin.js，依次执行：
1. node scripts/run-tests.js
2. node --test admin/tests/api.test.mjs admin/tests/static.test.mjs admin/tests/editorial.test.mjs
3. python admin/tests/test_browser.py（已有Edge/Playwright、隔离API注入）
4. python admin/tests/test_editorial_browser.py（临时独立Mock、真实HTTP/Edge，结束清理）

基础阶段历史UTC起止、cwd、退出状态见cloudbase/verification/suiji-admin-20261111/commands.json；通过数见该目录final日志，不能把历史报告当当前测试。
基础阶段历史结果：后端57/57、管理页Node18/18、原Edge回归12/12、实际Mock+Edge新流程2/2，四条命令退出均0。覆盖正文授权六态、撤权清空、隐藏/撤下、混合旧投稿rank分页、并发版本/事务回滚、管理员身份及持久化。
初始基线后端48/管理页15通过。先补5项后端新功能失败和管理新action失败（缺接口/动作），再实现转绿。新浏览器测试发现表单校验错误以message抛出但提示按code读取，已修复回归。外网preloader首次Windows反斜杠解析错误也保留失败日志，改正斜杠路径后重跑。

## 恢复与未验证项

先备份当前文件，依original-sha256.json从originals/cloudbase/逐文件恢复本轮修改项，再按文件清单移走新增项。仅恢复对应文件，不执行git reset/clean，不还原整个工程，不碰SCH-013/其他并行任务。无数据库操作，不需数据回滚。原件SHA、changes.patch、最终SHA清单均保存在证据目录。
只撤回120/20000补充时，以suiji-admin-limits-20261111中的7项原件为准；只撤回最终交接文档时，以suiji-admin-final-20261111的originals逐文件恢复。各阶段原件不混用，不覆盖后续并行修改。
本文件和verification目录命中现有Git忽略规则，均已落盘可直接查看；本轮不更改忽略规则或强制暂存。

本写集未验证：真实云新接口、真实SDK/索引下rank顺序、真实来源存在性/授权依据；Android最终状态由主会话另报。旧App历史离线缓存不在本写集，不声称可远程擦除断网设备已有全文。部署/真实环境验收不在本轮授权范围。

## 主会话最终复跑（2026-11-11）

120/20000限额跟进后，主会话重新执行 `node cloudbase/scripts/verify-suiji-admin.js`，后端59、管理页Node20、原浏览器12及实际Mock浏览器2均通过；本次最终命令/时间/退出码已写入 `cloudbase/verification/suiji-admin-20261111/commands.json`。上文“未重跑浏览器”仅指代理限额跟进阶段，不是整轮结论。Android另有本机HTTP精选草稿/授权/撤权/同源合并回归，见主交付说明；仍无部署或真实云端调用。
