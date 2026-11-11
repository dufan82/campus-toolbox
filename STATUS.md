# 开发进度

## REL-183 · GitHub与国内源同步完成（2026-11-11 08:36 +08:00）

校伴1.8.3/code30已作为最新稳定版同步发布。应用源码bec140d已推送main；Actions [42931475426](https://github.com/dufan82/campus-toolbox/actions/runs/42931475426)成功。GitHub Release 441089990、CNB Release 2120313940453847040，均非草稿/非预发布。两源APK/update.json/SHA256SUMS.txt分别匿名完整下载，大小与SHA逐一匹配本机原件。APK 79,027,906字节，比上版增加77,560字节；SHA256 2a9d7ad70d7d45ea7536b5e8c89732b788058f4f2e06f9f157b182dbd00bdf94。

350 JVM、后端48、管理页15通过；Debug/Release Lint均0错误（75/77警告），离线Release构建成功。包名、原兼容证书、非调试、双ARM库和地图素材已核对，未新增Android生产依赖/权限。桌面校园工具箱安装包及项目交付/安装包各有“校伴-1.8.3.apk”，副本摘要一致，旧包未删除。系统安装仍需用户确认，不卸载旧版。

本轮窗口未显示的原因是后台Windows PowerShell找不到Get-FileHash；发布器已改用.NET SHA256，独立进程校验与实际窗口显示、真实上传均通过。按用户新增要求，CNB令牌在用户输入后保存到本机Windows凭据管理器，后续同账户复用，不写记忆原文/源码/App/日志；脚本读写/删除/目标限制在当前PowerShell与PS5.1均通过，无真实项目凭据删除。未新增付费资源。

仍待ARM手机投稿、跨设备在线/离线阅读、腾讯反馈后台接收、真实学校07:00/地图与翻页手感验收。源码保护核心未改。发布证据位于verification/release183-20261110；[国内下载](https://cnb.cool/campus-toolbox/campus-toolbox/-/releases/tag/v1.8.3) · [GitHub备用](https://github.com/dufan82/campus-toolbox/releases/tag/v1.8.3)。

## CLOUD-002 · 真实云端投稿与人工审核联调通过（2026-11-10 18:51 +08:00）

上海原体验环境已接通：五集合ADMINONLY、五索引、计数初始化及真实SDK事务回滚验证通过。实际采用`story-service`事件云函数＋HTTPS `/api`，Nodejs20.19/256MB/3秒，避免HTTP函数长期凭据问题；匿名身份、管理员UID白名单及系统返回值日志关闭。没有改套餐或购买域名。HTTP试部署`story-api`保留但不路由，试建的服务端Key已移除并撤销，不用于App。

真实HTTPS联调：匿名投稿成功、同ID重试幂等、越权审核403、伪造审核状态400、待审列表/详情不可公开；用户用自己设置的密码实际登录本机后台，完成通过和撤下。独立匿名请求确认通过后可读、撤下后列表消失且详情404。测试投稿/审核审计留档，不删用户资料。管理员密码不进入聊天/文件，UID不入APK。

默认静态域名六文件上传验证成功，但HTML响应强制attachment，不能冒充网页可用。已提供127.0.0.1:8791本机审核：只读静态文件，密码/Token直接通过HTTPS交云端，不经本机服务器；防Host/Origin伪造，未购买域名。首版3QPS限制影响浏览器预检，已调整全站20QPS/每IP10QPS；业务投稿限额仍为60秒/5篇每日/全站50篇每日。

本轮修复官方匿名`user/me`省略status导致401；仅已验证anonymous组且非管理员可缺省，封禁/未知/管理员仍严格检查。后端48、管理页15、隔离浏览器12通过；真实登录页＋HTTPS跨域读取验证通过。Android JVM321、Debug Lint0错75警告、完整双ARM Debug构建通过；首次默认缓存构建缺AGP失败，切回现有E盘Gradle缓存后成功，原日志保留。

新的本地Debug包“校伴-1.8.2-云投稿联调测试-20261110.apk”已配置真实API，项目交付/测试包及桌面校园工具箱安装包各一份并核对；80,641,540字节，较同配置Debug基线80,445,471增加196,069字节，压缩内容仅+181字节、其余为APK封装差异，四个so和素材不变。签名仍f6b966…eb1，code29/1.8.2，debuggable测试包，不是正式发布。没有提交、推送或双源发布。待ARM手机投稿/双端离线阅读；旧无云配置预览包不会自动启用。

[接入与使用](docs/CLOUD_CONNECT.md) · [本轮交付](交付/说明/校伴-真实云投稿接通-20261110.md)。源码与配置原件、红绿日志、云API响应和APK摘要保留于verification/cloudbase-connect-20261110，私有备份不得上传。

## CLOUD-002 · 云端基础配置完成，业务部署待继续（2026-11-10 17:38 +08:00）

CloudBase官方CLI3.9.2已通过用户网页授权，实际读取到现有上海NORMAL/baas_trial环境；关闭CLI使用数据收集。未删除/重建用户环境或升级套餐。已用官方EnablePGDocumentExt确认Enabled=true，PG文档插件开通不另收费；五个业务集合新建并逐一DescribeDatabaseACL确认为ADMINONLY。已实际核对五个计划索引，其中submissionId为唯一索引。没有使用PG不支持的CUSTOM安全规则。

一条索引创建API超时，但随后DescribeTable已证实三项实际完成，未盲目重试/重建。管理端RunCommands读命令返回UnauthorizedOperation，未重试或扩张IAM；后续用文档明确支持的云函数Node SDK路径单独验证，不能把元数据成功当业务事务已通过。

目前仍无云函数/公开路由、应用管理员账号、计数初始化或匿名登录开关变更；当前APK仍仅本机草稿。下一阶段配置管理员、部署与真实SDK/事务/权限/双端投稿验收；账号密码需用户本机设置。不购买套餐/域名、不改正式版本、不提交推送发布。官方CLI及命令/返回/RequestId在verification/cloudbase-connect-20261110，原件保留。现有App代码未变，不重复构建或冒充本轮App测试。

## CLOUD-001 · 本地开发与Mock验收完成（2026-11-10 16:38 +08:00）

现有1.8.2/code29上完成CloudBase投稿、官方匿名身份/管理员白名单、事务审核与发布/撤下、原生分页/来源隔离缓存、轻量Web后台。生产配置为空，尚未创建资源或部署；预览只存草稿，不假提交。AGENTS、包名、正式版本、签名方案、更新渠道与已有用户数据不变。

本轮350 JVM、68项普通断网模拟器、27项Mock存储/阅读边界、5阶段真实本机HTTP投稿流程通过；旧故事预览覆盖seed/verify各1通过。Node43、后台14+12、15项真实服务端拒绝及UI批准/拒绝/撤下通过。双Lint0错75/77警告，完整双ARM Debug/兼容签名非调试Release构建通过。已修慢响应头截止、阅读旧回调、撤下清理失败、缓存其他页和来源隔离。真实CloudBase及ARM手机未验收。

同配置Release 79,011,522→79,027,906字节（+16,384）；SHA d4d7e5d1b35664df32736f3a3074de389c8537b955f9b287fd4b893c2f2a8118。无新增Android生产依赖/权限，原生库和内置素材字节保留。APK在交付/测试包及桌面校园工具箱安装包，名为“校伴-1.8.2-云投稿接入预览-20261110.apk”。[说明](交付/说明/校伴-小故事云投稿-20261110.md) · [截图](交付/预览/小故事云投稿-20261110/index.html) · [接入清单](docs/STORY_CLOUD.md)。原件、差异、恢复与日志见verification/story-cloud-20261110。无提交/推送/发布，HEAD5e39609。

下一步CLOUD-002：提供真实环境/地域/API/管理员身份，控制台应用五集合deny-all与索引、部署与云端闭环；免费平台256M/3秒需实测，正式后台自定义域名待配置，不擅自付费。原学校、ARM地图和翻页真机事项保留。

## READ-001 / NAV-004 · 本地预览完成（2026-11-10 10:46 +08:00）

首页改为14.5sp诗句及独立右对齐10.5sp出处，保留设置、固定三快递及原工具；272条可追溯MIT数据、冷启动抽签/最近40条去重、复制/换句及弹窗同步。新增独立“小故事”，首批12篇项目创作短篇，分类/字号/收藏/阅读进度与投稿草稿本地保存；投稿URL为空，未发出真实投稿，未借用反馈问卷。

首页/课表/我的统一REORDER_TO_FRONT和方向动画，首页课表卡片复用标签页；两项实际竞态红测后转绿。学校检查新增独立历史，显示时间、成功/无变化/失败与前后日期、教室、教师、节次；不改每日去重键及SchoolAutoMerge/SchoolFutureMerge/ScheduleStore（三文件SHA不变）。普通页面重复提示已精简。

314 App JVM及29 watermark-core通过。43项主流程通过；另72项回归中71通过、1原有安装条件用例跳过；大字体/横竖屏7项、375dp常规6项和最终干净截图4项通过。冷进程两轮各1项，1.8.1→当前兼容签名非调试模拟器覆盖seed/verify各1项，五组业务文件及旧布局/密文凭据/Cookie保留。最终字体显示微调另有专用大字体记录。双Lint0错、73/75警告；完整双ARM Debug/Release构建通过，真实ARM地图、学校登录/07:00后台与翻页手感仍待手机。

交付/测试包及E:\Desktop\校园工具箱安装包已放“校伴-1.8.2-诗词与小故事预览-20261110.apk”，79,011,522字节，较上份同配置预览+61,176字节；SHA256 ed24a2db89de24c253ef3dea131c2af173defb73cb29c1fd79aa1fccf426bbb6。版本1.8.2/code29、包名、签名和更新源不变，无提交/推送/发布；本地预览不会通过线上自动更新发放。截图与说明位于交付/预览和交付/说明，原件/差异/离线日志在verification/reading-20261110，恢复仅在额外源码副本验证。

## SCH-012 / FB-002 · 本地预览完成（2026-11-10 08:02 +08:00）

课程表管理精简为“检查更新 / 备份与恢复”；右上角＋新增，低频学期/节次/全部课程移到我的→课表设置。学校检查预览只显示实际安全差异，手动检查复用SchoolAutoMerge与ScheduleStore.applyAutomatic，旧来源/手动修改/墓碑/历史保留，不再开放学校整表替换或旧课程接管。首次空表只初始化未来课程；备份恢复仍需覆盖确认，CAS和旧schema保留。

每日07:00开关放检查页次要位置；修正CHECKING空summary误报“尚未执行”，按实际状态显示并监听完成刷新。腾讯三题问卷已发布并配置https://wj.qq.com/s2/28616104/3ezx/，匿名HTTP200、外部Intent及回退通过；无真实答卷提交，后台接收见FB-003。

328JVM通过，最终72断网API36设备用例全过，8项双倍字体横屏通过。双Lint0错误、Debug68/Release70警告；完整双ARM Debug与同兼容证书非调试Release构建通过。当前1.8.2/code29、签名、权限、依赖及三合并/存储核心SHA不变。课表未变化的内容、mtime及备份、旧schema、手动/删除/历史/CAS均有本次回归；另实际1.8.1→当前模拟器覆盖seed/verify各1项，五组业务文件SHA保留。早期旧UI测试和状态fallback红测已保留原日志，不计为最终通过；真实学校/ARM手机仍待人工验收。

交付：交付/测试包及桌面校园工具箱安装包目录内“校伴-1.8.2-课表精简与反馈预览-20261110.apk”，78,950,346字节，较线上同配置增加0；SHA256 e275af73420e07256cad5963ee3fc666f410f20a23d44b27e3b1f355746cd382。用户数据不迁移，旧包保留，未提交/推送/发布APK，线上仍原1.8.2；只有反馈问卷按本轮接入请求公开启用。证据verification/schedule-lean-20261109，说明交付/说明/校伴-课表精简与反馈预览-20261110.md。


## SCH-012 / FB-002 · 进行中（2026-11-09 23:30 +08:00）

本轮精简课程表管理、学校更新和备份路径，保留已有翻页优化与全部未提交修改。复用原生Views及SchoolAutoMerge/SchoolFutureMerge/ScheduleStore；不改1.8.2/code29、签名或发布渠道。腾讯问卷三题草稿已编辑保存，接入须核验真实公开填写URL。仅本地预览，不提交/推送/发布APK。原件SHA和源码备份在verification/schedule-lean-20261109。


## NAV-002本地预览完成 / FB-002等待问卷（2026-11-09 22:36 +08:00）

翻页已定位并减少重复UI重建：首页相同数据每轮3→0，课表4→2，最终各测10轮一致；普通渲染同帧合并，编辑/详情/弹窗保留同步以免时序回归。只改MainActivity、ScheduleActivity并新增NavigationRenderPerformanceTest，不改导航flags、方向或动画，数据/更新/课表读取仍执行。

最终319JVM通过，专用断网设备40项中39通过/1原有安装权限用例跳过/0失败；双Lint0错67/69警告，完整双ARM Debug和同证书非调试Release构建通过。中间3项编辑态失败已修复并重跑。应用帧p95未稳定改善，不能用软件GPU数据保证ARM真机帧率，实际手感见NAV-003。

交付/测试包和桌面校园工具箱安装包均有“校伴-1.8.2-翻页优化预览-20261109.apk”；78,950,346字节，较线上同配置增量0，SHA256 `3b37dd5026c1d54a6317afb8aa4992d292ec2ce0d8d3ddf19c3211ce1a8b27e1`。本地预览同code29，没有覆盖历史或发布包，没有新功能提交/推送/发布；报告交付/说明/校伴-翻页优化预览-20261109.md，证据verification/nav-smooth-20261109与feedback-nav-20261109。

反馈接收地址仍为空；用户选择腾讯问卷，已给三题创建及匿名设置步骤，等待真实发布后的填写链接，不能标为已开放。README和线上反馈说明尚未改变。

## REL-182 · 1.8.2双源发布完成（2026-11-09 22:17 +08:00）

校伴1.8.2/code29已同步GitHub和CNB最新稳定版。源码14b544e7389ca09060b2f6bb93af394057526897已推main，代码Actions42629033639成功；GitHub Release ID439191222、CNB Release ID2119793560773419008，均非草稿/非预发布。两个渠道三个附件均匿名完整下载核对大小/SHA一致，CNB公共latest及元数据code29有效，证据verification/brand-release-20261109。

APK78,950,346字节，比1.8.1同配置增加25,884字节；SHA256 `529a61248cf34571bc8a08441b56bb96ed0c1c19be4c725c58f4644604a8aef3`。校伴显示名、原包名/兼容证书、双ARM四个so/校园原图、非debuggable已复核。工程交付/安装包/校伴-1.8.2.apk及桌面E:\Desktop\校园工具箱安装包\校伴-1.8.2.apk摘要一致，旧包保留。可从我的→检查更新手动读取；自动检查有24小时缓存，刚检查过需等待约1分钟再手动重试，不声称手机已安装。

发布前本次1.8.2构建319JVM通过、双Lint0错误/67和69警告；此前品牌功能61断网设备、显示条件和覆盖测试是历史证据，本轮没有冒充重跑设备测试。内置浏览器页面读取超时后未再转用桌面控制；按用户批准的本机临时安全输入，通过既有CNB官方API函数发布，令牌未写入文件、App或GitHub。在线反馈仍未配置，NAV-002/FB-002是下一轮本地工作，不包含在本发布包中。

[国内下载](https://cnb.cool/campus-toolbox/campus-toolbox/-/releases/tag/v1.8.2) · [GitHub下载](https://github.com/dufan82/campus-toolbox/releases/tag/v1.8.2)。

## BRAND-001 / NAV-001 · 本地完成（2026-11-09 17:58 +08:00）

按参考图完成“校伴”本地品牌升级：独立SVG/Android矢量、传统/Adaptive/单色/通知图标、原生启动屏、品牌色和真实字标、关于页及本地README；我的→首页改为左侧进入。保持现有三个快递入口与四工具布局/ID，不加回已撤功能。版本1.8.1/code28、包名、签名、权限、生产依赖与更新/存储/登录核心保持；未提交、推送或发布。

实际319JVM、61断网API36设备回归、5显示条件×5项、旧版seed与覆盖verify各1项通过。Debug Lint0错误67警告，Release0错误69警告；完整双ARM Debug/同证书非调试Release构建通过。原代码→新代码同证书模拟器覆盖保留资料、课表、倒数日、地图/首页/快递设置、加密课表凭据和WebView测试Cookie；不是ARM手机验收。实际启动录屏和导航帧均保存，系统暗色设置下保持浅色品牌界面。

Release基线78,924,462→78,950,346字节（+25,884），Debug同冻结源码配置79,965,489→79,975,914（+10,425）；四个地图so与校园原图哈希不变。APK SHA256 `e837cd9a78e75d8918d37878424c8d64e9891dee0337772fffc91a9b9075be53`。工程交付/测试包和桌面校园工具箱安装包内均为“校伴-1.8.1-品牌预览-20261109.apk”，两份已校验，不覆盖历史包。

报告[本地品牌验收](docs/BRAND_UPGRADE.md)，资源docs/brand，交付品牌ZIP/原生截图在交付/品牌资源；原件SHA、源码备份、差异和全部实际日志在verification/brand-xiaoban-20261109。首轮旧更新UI测试定位失效已修正并完整重跑。剩余：实际手机全ARM覆盖、真实学校会话/地图/GPS、厂商桌面；确认外观后再授权双源发布，当前线上仍原1.8.1。

## 1.8.1 双源发布完成 · 2026-11-09 14:10 +08:00

GitHub与CNB均已设为v1.8.1最新稳定版：versionCode28，源码8d573a20c092bd727c7d53c60a877ac14d522342，代码Actions 42568323856成功。CNB Release ID2119669458318352384，匿名latest返回draft=false/prerelease=false/is_latest=true；三个附件匿名整文件下载大小和SHA256全部一致。GitHub三附件服务端摘要与本机一致，公开APK整包匿名下载一致；网络验证记录见verification/daily-sync-20261109。CNB直接创建新内置浏览器标签后恢复正常，沿用登录态，无新令牌，不再操作Edge窗口。

发布包78,924,462字节，较1.8.0同配置增长17,000字节；SHA256 `dffaf667cfc11203c5b3bfecff807db5f90b16ad9177dc1c6fafb8f8201f67aa`。apksigner验证通过，证书仍为既有f6b966...eb1；aapt确认code28/1.8.1、非debuggable、双ARM，四个地图so及校园图保留。准备器和已存在的构建证据复核，不为发布文档重跑应用测试；本日功能开发309JVM/47断网设备/2大字体与重启恢复记录仍见下方，真实学校和手机升级待验收。

交付/安装包/校园工具箱-1.8.1.apk与E:\Desktop\校园工具箱安装包\校园工具箱-1.8.1.apk均已创建并校验摘要，未覆盖旧包。手机可在“我的→检查更新”手动读取；自动检查有24小时缓存，刚请求过时手动检查可能需等1分钟。本轮完成双源发布及文档同步，不宣称已在用户手机收到提示或安装成功。在线反馈问卷未配置，自动课表检查默认关闭。

[国内下载](https://cnb.cool/campus-toolbox/campus-toolbox/-/releases/tag/v1.8.1) · [GitHub下载](https://github.com/dufan82/campus-toolbox/releases/tag/v1.8.1)。发布截图verification/daily-sync-20261109/cnb181-published.png。以下为发版前开发记录。

## SCH-010 / FB-001 · 本地开发完成（2026-11-09 13:19 +08:00）

未发布功能：每日07:00可选自动课表检查（默认关闭），平台持久网络Job、重启/时区恢复、同日去重/并发和CAS；正常学校导入明确确认后加密保存后台课表Token，关闭或退出登录即清除。只更新今日及未来，保留历史/手动/未知来源课程，冲突逐组跳过；有删除记录且无法识别的新名称保守跳过。无变化不写课表及撤销备份。我的页右下新增反馈，真实问卷未配置，空入口明确未开放，本地草稿不上传。

本次实际JVM App280/core29共309通过；最终断网API36模拟器47通过，双倍字体横屏另2项通过，实际模拟器重启后持久Job恢复通过。双Lint均0错误/64警告（原63，新增1处需要同步commit结果的UseKtx建议）。正常双ARM Debug及未签名Release干净构建通过；Debug基线79915662→79965493（+49831字节），未签名Release78903366→78920366（+17000）。地图四个so和原资源保留，无生产依赖或uses-permission增加。

本地开发调试包在交付/测试包及桌面校园工具箱安装包目录，同名“校园工具箱-1.8.0-每日课表与反馈-开发调试-20261109.apk”，SHA256 `8bf759a7e45b9b20758d14f7360ef136f3b76a05b28181fb9701bec64a03e905`。不是正式更新包，不承诺覆盖正式安装；没有修改1.8.0/code27、签名、已发布安装包或更新源，无提交推送发布。旧正式APK SHA核对未变。保留开工前两处未跟踪历史备份和gate-web。

主要变更：schedule/DailySync*、DailySchoolSync、SchoolSyncVault、SchoolAutoMerge、ScheduleStore/学校导入及提醒，feedback包、我的/首页小入口与Manifest。完整报告[每日课表同步与反馈](docs/DAILY_SCHEDULE_SYNC.md)，问卷配置见[feedback-form](docs/feedback-form.md)。原始SHA/源码副本、差异、测试日志、同配置体积与原生截图在verification/daily-sync-20261109。初期组合设备测试曾因新夹具遗留成功时间使旧未知时间提示用例失败，已隔离后连同旧用例重跑通过。

剩余SCH-011：真实学校账号/过期响应、ARM手机跨日与厂商省电、真实旧数据及正式覆盖升级验收；FB-002：用户创建并提供真实HTTPS问卷后配置，验证实际接收与匿名设置。模拟测试不冒充线上学校同步或真实问卷提交。

## UI-010 / UI-011 · 1.8.0 界面更新

2026-11-09 10:15 +08:00：用户已确认六屏预览，已完成原生Views改造：固定快递区、紧凑工具、首页真实本地课表摘要、首页/课表/我的底部导航；课表详情和管理、地图浮动搜索/单地点导航、水印图片优先和三栏样式、倒数日主卡/列表及集中设置。原包名、四个工具ID、本地存储、已标坐标、登录/合并/备份、原图导出和更新逻辑保留，不加回进出校/收藏/待办/批量入口。

本次JVM：core29、app251通过；普通字体27项专用断网模拟器测试通过，双倍字体3项检查通过（含横竖屏）；Debug和Release Lint均0错误/63警告，正常签名Release构建通过。初期新增测试暴露的窗口坐标、日期选择事件时序与旧按钮文案断言已修正后重跑通过；未关闭测试。模拟器只验证非SDK流程，真实ARM地图、学校登录/课表差异、REDMI相册和真实旧包覆盖仍待手机验收。没有实现每周自动联网同步课表。

交付：1.8.0/code27，78,907,462字节，比1.7.9-test同配置增加32,792字节。SHA256 `dc27a776737860fe5e10f211f8ede601e5838292aaa05a65cd1223ee4827b10d`。旧兼容证书、非调试、包名/minSdk、双ARM四个so、校园原图和无微信SDK已检查，无新增生产依赖/权限。项目 `交付/安装包/校园工具箱-1.8.0.apk` 与桌面 `E:\Desktop\校园工具箱安装包\校园工具箱-1.8.0.apk` 哈希一致。三发布附件在 `交付/发布附件/v1.8.0-r1`。

GitHub/CNB已更新（2026-11-09 10:24 +08:00）：源码6913cabf93772bc1ffd28acc66256a5184f65d03已推main，GitHub Release与CNB均为v1.8.0稳定最新版，两个渠道APK以及CNB三附件均匿名完整下载/摘要比对一致。CNB通过内置浏览器沿用登录态，未索取新令牌；先预发布、三附件核验后再转稳定latest。GitHub代码提交的Actions 42549986500成功，https://github.com/dufan82/campus-toolbox/actions/runs/42549986500 。公开介绍已整理为学生功能说明，未添加项目许可证；私有签名、Key、原图输入文件、交付与备份未推源仓库。CNB自动源码压缩包仍是国内入口页仓库，Android源码以GitHub为准。发布截图verification/ui-native-20261108/cnb180-published.png。原始源码及SHA备份、实际日志和原生运行截图均在该verification目录。

## 上轮记录 · 照片水印和课表更新

1.7.9-test/code26本机试用：水印优先系统照片选择器，拖动和黑白字、横竖、透明或半透明背景、字号可选；同一渲染器预览导出。保留已保存样式和图片草稿。旧系统或选择器不可用时回退系统文件选择器；JPEG/PNG以外仍有格式限制。

课表保留WebView会话并提供清除入口，成功学校导入记录时间；进入课表按情况提醒检查。手动读取学校课表后可预览并仅更新今日及未来，历史与受保护课程保留、写前备份；无来源旧课默认保护，需用户逐项确认。已被修改/删除过的学校课程、学期或作息不一致时安全拒绝合并。尚未实现每周自动联网检查或后台自动更新，学校登录、真机课表来源与合并需现场验证。

2026-11-07 本机执行 :watermark-core:test 29项、:app:testDebugUnitTest 246项通过；本轮专用断网模拟器33项通过；Release Lint 0错63警告、签名Release构建通过。试用APK `交付/测试包/校园工具箱-1.7.9-本机试用.apk`，78,874,670字节，较1.7.8增长16,392字节；包名、versionCode26、原兼容签名、非调试及双ARM库已检查。手机真实覆盖安装、登录会话、相册与用户数据保留未验证。未推送、未公开发布，国内源仍为1.7.8。原文件及SHA备份在verification/watermark-20261107-170937和verification/schedule-*-backup-20261107；模拟器结果verification/watermark-schedule-20261107-device-results.xml。

## GATE-009 · 1.7.8 国内更新

已移除首页和普通工具列表的快捷进出校入口；保留原代码、其他工具和用户数据。本机1.7.8/code25沿用历史兼容证书、关闭调试，大小78,858,278字节，与1.7.7同配置增长0。SHA256 `7f792ade6cebacc7a2c7bee9382653009ee2017d7040517c9e426f240db2ec7b`。

App JVM 226项通过，Release Lint 0错61警告，Release构建与Android测试代码编译通过；watermark-core测试复用缓存。无设备，未进行真机升级和数据保留验收。CNB三附件匿名完整下载并与本机SHA一致，已设为稳定最新版，latest接口返回1.7.8。源码未提交或推送GitHub。

本机备份和原始SHA在 `verification/rollback-20261030-194556/`，若恢复本轮前文件应从该目录逐项复制。CNB Release的源代码压缩包是国内入口页仓库，不是Android源码快照。

更新时间：2026-11-07 17:55 +08:00

## GATE-003 微信平台实查 · 2026-10-30 11:18 +08:00

- 已在用户登录的微信开放平台「管理中心 → 移动应用」检查：列表为「暂无数据」，创建按钮帮助提示「个人主体暂不支持创建新应用，如需创建，请新注册非个人类型的平台账号」。当前账号不能按既有个人账号申请路径新建本App；10月27日从通用文档推导的“个人账号可直接新建”不适用于当前平台实际状态，已更正接入说明。
- 当前仍无可用的本App移动AppID；OpenWebview.Req 对本App的准入条件和真实 Android 微信网页打开结果仍未确认。非个人主体账号也不等于该接口必然开放，不以付费认证作为已证实解决办法。
- 本轮只更新交接文档，保留1.7.7/code24及现有分享/扫码功能；未创建应用、付费、访问学校登记接口、修改业务源码、生成APK、提交、推送或发布。未运行应用测试（无代码变更）。
- 下一步：仅在有真实、获授权的非个人主体账号或本App已有合法移动AppID时继续核对准入，再进行固定普通网页的微信真机验证。不能以其他AppID、接口返回true或分享页代替直达验收。

## 1.7.7 国内更新 · 2026-10-30

- 已发布 CNB 稳定版 1.7.7/code24，包含东门、北门四入口；沿用历史证书，非调试包，未推送 GitHub。发布前预发布上传三附件并匿名完整下载逐个比对 SHA256，随后设为最新版。CNB latest 返回 200，版本、稳定标志和三个附件信息正确，APK Range 返回 206。
- 签名 Release 78,858,278 字节，较 1.7.6 同配置增长 0；SHA256 498bf292acb5c2f315458dd59e204c6654e4c57d29fe35ae681a66936c3aa75d。本轮 app JVM 225 通过、Release Lint 0 错误/61 警告、构建成功；watermark-core 测试由 Gradle 复用缓存。手机更新弹窗、下载、覆盖安装及现场通行尚未验证。
- CNB tag 指向国内入口页面仓库提交，并不是 Android 源码快照；本地源码尚未提交。历史 1.7.6 和用户数据保留。

## 当前版本与分支

- 此前CNB稳定版为1.7.6/code23，Release ID 2115015918551482368；匿名latest及三个附件完整下载、大小/SHA均已核验。该版只提供微信分享/现场扫码备用方案，不代表已实现一键直达学校通行页。
- 19:52另生成本机Debug网页验证包，未发布；20:16只核查申请条件、整理接入说明，没有重新构建或修改1.7.6/code23稳定包、版本或签名配置。没有提交或推送Git源码；GitHub最后核实为1.7.4，本轮未同步或重新查询。手机更新、微信能力和实际通行仍待用户验收。
- 本机分支feature/v1.6.0。生产源码提交`647dfaf9d2331df48edf0058a1183baaf78cae77`已推送GitHub main，tag v1.7.4精确指向此提交；后续仅进度文档提交不改变安装包。
- 1.7.4为78,841,838字节 / 75.189 MiB，SHA256 `7a9888fe941921b63a0f8fadcccd8cb5e9ee514a443c3ca9982448bc1ee7b2a9`，相对1.7.3同配置增长0。保留历史证书、双ARM、地图配置及校园图，关闭调试，无存储变更。GitHub三附件服务器摘要与CNB一致，不重新构建或签名。
- [GitHub下载页](https://github.com/dufan82/campus-toolbox/releases/tag/v1.7.4)；[源码提交的自动检查](https://github.com/dufan82/campus-toolbox/actions/runs/40429235039)已成功：242JVM、Lint0错误/65警告、构建入口10项、发布准备46项、Debug及未签名Release构建均通过，实际报告已下载核对。
- [当前国内下载页](https://cnb.cool/campus-toolbox/campus-toolbox/-/releases/tag/v1.7.6)。支持国内源的旧版可在设置点“检查更新”，安装时不要先卸载；已安装本机1.7.6/code23的不重复提示同版。

### 旧版本记录
- CNB 1.7.5/code22为上一公开版本，Release ID 2114930916815982592；历史附件及本机文件保留。
- 1.7.3 / versionCode 20为上一国内公开版本，由用户上传三附件并完成当时匿名下载和客户端核验；保留历史附件和本地交付，不覆盖。
- 1.7.3为78,841,838字节 / 75.189 MiB，SHA256 `746c1529090543101770d9564536784748c7052f29805e84e1f304aab47ea364`。默认绑定campus-toolbox/campus-toolbox，国内源优先、GitHub备用；当前CNB附件和元数据有效。断VPN手机/系统安装全程尚未验收。
- 已发布1.7.1 / versionCode 18，包名cn.campuslife.assistant，安装名仍为“校园生活助手”。
- 1.7.1当时的发布源码253e150547b19e338841f445d58ed1b82562c971已同步main；tag v1.7.1精确指向它，后续文档提交不改变APK。
- [下载页](https://github.com/dufan82/campus-toolbox/releases/tag/v1.7.1)：已公开、非Draft、非Prerelease，Release ID 423918487。
- APK：campus-toolbox-1.7.1.apk，78,841,838字节 / 75.189 MiB。
- SHA256：dd9c8b09ce287cad39135417709f91f1e7f04a28413495498fb2d98ca6a8b95a。
- 同Release的APK、update.json、SHA256SUMS.txt已发布；服务器digest和匿名实际下载逐文件核对一致。
- 本机安装包：交付/安装包/校园生活助手-1.7.1-兼容更新版.apk，与公开附件相同；历史文件保留。

## 当前目标与正在执行

GATE-008已完成本地开发、离线验收及用户批准的CNB入口页同步。其后已发布1.7.7/code24到CNB；未提交本地App仓库或推送GitHub。

### 东门、北门四入口 · 2026-10-29

- GateRoute固定四条用户提供的URL：东入10066/1、东出10067/2、北入10062/1、北出10063/2。GateActivity按东门/北门分组，入校绿、出校蓝；GateLauncher保持原微信分享和现场扫码备用流程。首页gate ID和既有排序/隐藏数据不变。
- gate-web同步四个原生链接；离线夹具发送前替换全部四条地址，未知残留即拒绝，测试不请求学校。CNB保留east-gate.md原路径兼容收藏：https://cnb.cool/campus-toolbox/campus-toolbox/-/blob/main/east-gate.md 。只提交这份文档，提交b9f870650314b97eb083cdab3bae761d3005449b；本机匿名无代理HTTP200，170649字节，0.507560秒，四目标与标签核对一致。Sites站点本轮未发布。
- 先建立失败证据：App新精确映射测试3项中1失败；网页新增四入口检查10项中6失败。实现后253 JVM（App225/Core28）和11网页检查通过。专用emulator-5582断网、拦截外部Intent运行15项相关设备测试通过；font_scale=2另2项通过，含横屏滚动、四按钮、返回重建不重发。非真机通行验收。
- Debug/Release Lint各0错误、61警告（现有兼容API等告警保留）。完整双ARM Debug构建成功；无新增依赖、权限或存储迁移。APK从79,833,722变为79,850,106字节，净增16,384字节（16KiB），代码压缩增量和打包对齐共同影响净值；原生库、校园图、Manifest与基线逐项一致。SHA256 f9da4689b27723bafb8ba95e847ee8dc963f3453fe22c9f75abd20aa999110f8。
- 本机测试包：交付/测试包/校园工具箱-东门北门四入口-Debug-20261029.apk。是Debug测试包，非国内源正式更新，不保证覆盖现有正式安装；不要卸载旧版。截图verification/gate008-page.png已查看，四按钮显示完整。
- 浏览器先因Edge连接无法核实而中止；用户改用内置浏览器并完成登录后恢复。首次会话绑定异常重置后恢复；Monaco普通fill只替换可视输入，预览发现旧尾部残留，改为编辑器全选粘贴，确认恰好四入口后才提交。无需新增插件或提取凭据。
- 初次Gradle默认缓存未配置，Wrapper试图下载；已取消并切到现有E:/skill/gradle-cache完成离线构建。第一次截图时设备测试清理了App，后重新在该专用模拟器安装去ARM测试构建取图；交付始终使用完整双ARM包。
- 验证日志gate008-check.log、gate008-device.log、gate008-device-large-font.log、gate008-web-green.log、gate008-final-package.log；汇总verification/gate008-result.json。本轮只复核已有测试证据与未变代码，没有把它们写成本次重新运行。备份历史归档/gate008-20261029-182635，差异verification/gate008-source.diff。
- 待验证：微信内北门认证与真实方向/通行结果；CNB外链确认仍在。未修改学校流程或实际访问任何通行登记地址。

### 上轮状态

GATE-006已完成本地修复、网页发布和本机国内直连检查；GATE-007等待手机微信现场验收。本轮不改Android源码、版本、签名或APK。用户已授权公开测试页及修复国内可达性，发布范围只有两条公开入口与必要说明。

### 微信入口修复和国内入口 · 2026-10-28

- 用户明确反馈“微信里按钮没反应”。代码确认旧版两个按钮初始disabled、依赖两份JS解锁，并有UA/isTrusted/onLine门槛；脚本缺失时不存在原生导航。这是已验证设计缺陷，但没有手机运行日志，不能声称唯一根因已证实。
- v2改成两个固定HTTPS原生链接，移除脚本依赖和客户端环境阻断，CSS失败仍有文字链接。保持绿色入校/蓝色出校，静态提示微信内现场使用。不再有脚本防连点；只点一次并等待，不自动请求/预取/重试，也不显示通行成功。
- 国内简洁入口：https://cnb.cool/campus-toolbox/campus-toolbox/-/blob/main/east-gate.md 。现有登录发布仅新增east-gate.md，CNB提交fe3a2e7a15db4ffa721d7ae0df356d33d1e5d5f3；README、APK Release、更新元数据不变。页面带CNB外框，外链经cnb.cool/110提示，不是完整HTML托管，不绕过提示。
- 国内实测：curl.exe --noproxy '*' 匿名请求上述页面HTTP200，164504字节，0.524秒；HTML含两条正确URL及10066/1、10067/2参数。未访问或预检这两条学校接口，也没有加载CNB外链跳转页。手机网络及微信中转表现待用户确认，不保证所有运营商/地区。
- 原Sites地址也已更新：https://campus-toolbox-east-gate.leafymite67.chatgpt.site/?v=2 。部署appgdep_6ae19f50af2081919bf684336c9808b5 succeeded；版本appgprj_6ae0c3ab890481919958e9cefff0ef6c~appgver_6018fc8071f081919e5cebc25631ac34；源提交ea3d9e5a3fe139329d690487140c4cd0714d55d3。此域名仍不作为国内主入口。经现有代理匿名获取HTML/CSS为200，作者HTML前缀一致、CSS哈希一致；托管商仍可能附加自己的脚本。
- 实测node --test gate-web/tests/*.test.cjs：9通过，0失败/跳过。修改前新增回归检查7项中5失败/2通过，日志已保留；旧17项测试和JS实现一起备份归档，因新版本完全无JS控制器而替换为原生链接和隔离夹具检查。Edge在/offline-check分别点击入/出，实际到达对应本机结果页，返回保持入口页；没有以DOM静态检查冒充真实点击。
- 网页仅HTML/CSS两文件共6263字节，上一版11324字节；App相关187个已记录文件SHA全部未变，APK体积/依赖/权限无变化。未跑无关Android构建。git diff --check通过。未提交/推送Android或GitHub仓库，未创建Release。
- Edge网页操作每次约20秒导致短超时、随后旧标签调试脱离；已用更长调用窗口完成CNB发布，不能因此声称浏览器全局问题已修复。Sites短期源凭据过期后按同一站点正常更新；一次工具非JSON错误后重试成功，凭据未写入源或日志。
- 证据：verification/gate006-regression-before.log、gate006-tests.log、gate006-browser-clicks.txt、gate006-cnb-entry.html、gate006-cnb-entry.headers、gate006-cnb-published.png、gate006-result.json。备份：历史归档/gate006-20261028-143716。交付说明：交付/微信东门入口/使用说明.md。

### 上一轮记录

GATE-005在本次前完成v1脚本版公开网页，17项离线检查通过，但本机Sites直连连接重置、代理200；证据gate005-*保留。用户随后反馈微信内按钮无反应，已由本轮v2替换。不能再把v1的普通浏览器禁止跳转、防连点测试当作v2行为。

补记后续研究：登录后已读2019年iOS OpenWebviewReq讨论，微信官方用户回复当时未开放；这是历史iOS证据，不能直接断言当前Android情况。2025年另一iOS讨论仍无明确解决方案。官方咨询尚未成功发布，草稿保留；不再以申请AppID为已经证实的解决办法。以下20:16“未读正文”保留为当时记录，现已被本条补充。

GATE-004本地开发和离线验收已完成：仅Debug可见的“微信网页验证”，固定普通腾讯首页，缺AppID不创建SDK或发送。现有东门两按钮、Release依赖、版本/签名与用户数据不变。GATE-003真实通行仍待自有AppID、平台能力和真机链路，未冒充完成。

### 微信申请条件核查 · 2026-10-27 20:16

- 读取[创建移动应用](https://developers.weixin.qq.com/doc/oplatform/Mobile_App/guideline/create.html)、[应用审核规范](https://developers.weixin.qq.com/doc/oplatform/Mobile_App/operation.html)、[开发者须知](https://developers.weixin.qq.com/doc/oplatform/Mobile_App/FAQ/Required_Developer_Knowledge.html)官方正文。腾讯客服注册示例含个人主体；官方说明未认证账号也可创建移动应用，常规创建与付费资质认证不能混为一步，不要求用户先付费。认证帮助页配图显示300元审核费用，不能据旧截图保证当前费用或开通网页接口。
- 常规申请需准备官网、图标、流程材料以及准确包名/签名；未在应用商店上架应如实选未上架。GitHub/CNB的APK直链不符合官方应用商店下载链接要求。未上架应用有能力限制，不能冒充已上架；是否通过最终由微信审核。
- 官方[移动应用微信登录指南](https://developers.weixin.qq.com/doc/oplatform/Mobile_App/WeChat_Login/Development_Guide.html)要求独立申请微信登录权限；给本App授权不等于学校认证。尚未找到证明OpenWebview.Req对本App可用的官方说明。OpenBusinessWebview支付权限文档不是该接口的通用授权证明。被GitHub作者引用的社区帖子实际重定向到扫码登录/协议页面，未读取正文，未接受协议或绕过登录。
- 仅更新本机接入说明和STATUS/TASKS，无业务源码/依赖/权限/版本/签名修改，无学校接口请求、新APK、测试执行、提交推送或发布。保留19:52测试包；既有测试数字是历史结果，不算本轮重跑。下一步是先核实平台对OpenWebview.Req的准入条件，再准备自有移动AppID与无副作用网页的Android真机验证；不承诺注册即成功。申请材料及可复制的官方咨询问题见本机`交付/说明/微信接入准备.md`。

### Debug验证入口交付 · 2026-10-27 19:52

- `app/src/debug`新增验证页、主线程控制器和腾讯SDK适配器；Gradle仅增加debugImplementation。GateActivity只加BuildConfig.DEBUG条件入口，没有替换既有学校URL或改动首页规则。测试页只打开https://www.qq.com/，不接受任意URL、不请求校门接口。无配置/未安装/注册调用失败时不发网页请求，返回true只显示SDK调用结果；有20秒未知状态、重复点击/陈旧回调保护，重建不重发。
- 先运行新增入口设备用例，实际因旧版无按钮失败；实现后当前20项断网设备用例通过（新增5、原有回归15），0失败/错误/跳过。专用API36 read-only模拟器，无微信安装；外部校门Intent被拦截，SDK适配测试使用注入接口，不访问真实微信或学校服务。
- 实际运行App JVM224项通过（新增8），Debug/Release Lint均0错误62警告，完整双ARM Debug和未签名Release构建通过。没有重跑watermark-core单元测试，不把其历史结果算本轮。ReleaseRuntimeClasspath无微信SDK、Release Manifest无验证Activity；不是已发布新版本。
- 同配置完整Debug基线79,755,899字节，最终干净构建79,833,722字节（76.135 MiB），增长77,823字节（约76 KiB）。增量打包原包81,934,183字节中存在额外空隙；保存包/报告后核实app/build路径，仅清理生成目录并重建，去除2,100,461字节空隙。四个地图.so SHA与基线一致，无新图片/生产依赖/权限。未测同配置签名Release体积增量。
- 本机`交付/测试包/校园工具箱-微信网页验证-Debug.apk`，SHA256 `b4083d6289ce7a3d690ed1e4ea4615c34059fbfda0fe40b77825967a662aef30`，code23/debuggable=true/双ARM，签名SHA与历史兼容版一致。真实手机覆盖安装未验证，不要求卸载清数据；不上传稳定更新源。已查看实际模拟器截图，布局和缺AppID提示正常。
- 命令/日志：本机E:/skill/campus-official-app-analysis内gate004-red.log、gate004-build.log、gate004-device-final.log、gate004-final-build.log、gate004-delivery-build.log、gate004-clean-build.log；XML副本在gate004-reports，图为gate004-page.png。最初Wrapper误用空缓存触发下载，已只停止该次进程并复用已有缓存；截图首次因Gradle卸载测试包取不到缓存，改为shell临时截图后成功，不是业务成功证据。
- 本轮无Git提交/推送/Release、无版本/正式签名配置变化、无用户数据迁移。下一步见本机`交付/说明/微信网页验证测试包说明.md`：取得本App合适AppID并核实平台能力后，先真人测试普通网页，再由用户现场主动验收东门官方认证与通行，不承诺注册即成功。

UPD-007已完成：用户确认先更新试用后，复用现有1.7.6/code23兼容包发布CNB，先建草稿上传三附件，服务器摘要一致后公开为latest。匿名latest/元数据、三个附件完整下载及APK Range预检均通过；没有重跑应用测试、改签名、推送GitHub或请求学校登记接口。

GATE-003阻塞（原因已更新）：GitHub原生源码和腾讯SDK6.8.40实际制品确认有`OpenWebview.Req`，此前误查`WXOpenWebview.Req`而漏掉该API，相关否定结论已纠正。现有两个学校网址已够确定目标；用户确认尚无本App微信开放平台移动应用AppID。当前待办是本应用接入配置、网页打开能力是否获准及Android微信真机验证，不是再索要学校网址。1.7.6/code23仍只是分享/扫码备用版，不算实现。无数字福信依赖，不借用其他AppID、不改签名、不伪装认证。先用无副作用页面验证SDK，再由用户在东门主动验收真实学校流程，不能承诺注册即成功。

### GitHub与SDK纠错验证 · 2026-10-27 18:54

- 已读[expo-wechat原生Kotlin](https://github.com/likeSo/expo-wechat/blob/b9e2dfb536c342ae205d8d1bd6c3d8efdd32a573/android/src/main/java/expo/modules/wechat/ExpoWechatModule.kt#L597-L618)及[fluwx原生Kotlin](https://github.com/OpenFlutter/fluwx/blob/aa89699635f24947f036b9139336beaedb7a8271/packages/_shared/android/src/main/kotlin/com/jarvan/fluwx/FluwxPlugin.kt)：真实类名OpenWebview.Req，url字段、type12，IWXAPI.sendReq。无需引入它们的Expo/Flutter框架。
- [expo-wechat Issue10](https://github.com/likeSo/expo-wechat/issues/10)记录iOS bad_param及作者未找到可用结论；这是风险证据，不是本项目Android验收。SDK接口存在不证明平台对本App开放；不建议为未核实能力先付费认证。
- 本机API36只读专用模拟器断网运行SDK请求对象探针，EAST_IN/EAST_OUT两例通过（精确URL、HTTPS、域名/参数、type12/checkArgs），0次dispatch。初次JDK直接运行因SDK StackMap不兼容失败，改为Android DEX/ART正常运行；未禁用校验。模拟器已关闭，无APK安装、微信启动、OAuth或学校通行测试。
- apksigner重新验证现有1.7.6签名退出0；公开包名/证书MD5和后续步骤写入本机`交付/说明/微信接入准备.md`，无私钥/AppSecret。SDK仅放E:/skill分析目录，未加生产依赖，无新APK或实际体积增量测试。
- 本轮新增离线探针和核查记录，维护STATUS/TASKS并纠正此前结论。没有生产源码/权限/版本/签名改动，没有提交推送或发布。详情`docs/东门直达核查-20261027.md`是本机说明，受既有gitignore排除；根目录摘要保留GitHub源码链接供后续同步。

### 官方App直达线索核查 · 2026-10-27 18:24

- 从学校官网实际链接找到“数字福信”官方下载页，并按页面公开配置下载官方APK作只读静态分析。确认com.supwisdom.fjpit、1.0.44/code10054、129814741字节，SHA256 `7ca8fe7f6c343a4eff30428aa95046f5bb99be5cfa19f5f0e3748223f88e2a2e`。
- Manifest确认有公开Scheme及通用路由处理，但未找到东门业务入口或服务ID；未验证它与微信通行的认证兼容性。不能把打开官方App当成东门通行完成，也不猜路由或越过官方权限检查。
- 用户随后明确不能要求同学另装数字福信，已排除该产品依赖，不再要求安装或为此提供截图。核查详情和命令结果见`docs/东门直达核查-20261027.md`。
- 本轮仅新增核查记录并维护STATUS/TASKS；业务源码、版本、签名及1.7.6安装包均未更改。无新构建/测试/发布，不复用历史测试冒充本轮通过。未请求学校登记/登录/通行结果接口，未安装或运行官方APK。
- 18:35的SDK检查误用了WXOpenWebview类名；该类确实不存在，但不能据此断言真正的OpenWebview API不存在。此处原否定结论及“缺学校业务入口”的阻塞描述已由18:54的GitHub/SDK核查纠正，见上节；保留历史检查证据，不作为当前结论。

## 1.7.6国内发布验证 · 2026-10-27 17:37—17:49

- 用户明确要求先更新试用；仅公开固定仓库campus-toolbox/campus-toolbox的APK、update.json、SHA256SUMS.txt，不公开本地配置、私钥或用户资料，不提交/推送GitHub。发布说明明确微信分享是备用方案，尚非一键通行。
- 发布前重新核对现有三个文件摘要、APK包名/code23/minSdk26、双ARM及固定历史签名，未改变包字节或签名。复用现有Publish-CnbPlan官方API流程：创建草稿、上传、核对服务器三摘要，再设为非draft/非prerelease的latest。Release ID 2115015918551482368，发布助手正常退出；没有覆盖旧版本附件。
- 匿名完整下载三附件到`E:/skill/campus-toolbox-public/cnb-v1.7.6`，文件大小/SHA与本机准备文件、元数据及服务器记录逐项一致。APK为78,858,278字节，SHA256 `d23b4a9efcbc9423c39291148107f85a0b85fbcab114d3d2c39a6f669d62424a`；较1.7.5同配置增加28字节，属此前构建增量，本轮发布增量为0。
- 无认证读取latest返回v1.7.6、id和稳定标志正确；update.json为code23且大小/SHA正确。以客户端User-Agent请求公开APK Range 0-0，返回HTTP206、Content-Range bytes 0-0/78858278、Content-Length 1，最终主机asset.cnb.cool。此为公开链路验证，不冒充手机系统下载/安装或App自动更新弹窗测试。
- 本轮只核对既有产物和实际发布链路，不重跑历史216项app JVM、15项断网模拟器或双Lint，也不把旧记录当成新测试。证据见`verification/cnb-publish-176-public-check.json`。临时发布进程已退出，JS会话已重置，凭据未写入源码、App、命令参数、环境变量或本机文件。
- 下一步：旧版在设置手动检查更新并直接覆盖，不卸载清资料；同为code23不会重复提示。用户在东门按校规验证微信分享和官方结果，GATE-003一键直达研究仍未完成。

## 微信入口备用方案 · 2026-10-27

- `GateLauncher.kt`改为标准ACTION_SEND/text/plain，限定微信包名、仅携带对应原始URL，由用户自行选择接收方并确认发送；不调用微信内部Activity、不伪装UA、不读登录资料。另以公开Launcher Intent打开微信，供现场扫码。不回退普通浏览器。
- `GateActivity.kt`增加操作说明、分享失败时由用户确认复制并打开微信、未安装微信提示；保留重复点击/恢复/重建防护。Manifest只增加微信可见性查询，没有新增权限或生产依赖；首页三处快递入口和旧布局ID不变。
- 用户两张现场二维码在本机离线解码，分别为xmdm=10066&crlx=1入校、xmdm=10067&crlx=2出校，与配置完全一致；未上传照片、未请求学校登记地址。
- 回归先运行新Intent断言，旧代码因ACTION_VIEW而失败（`verification/gate176-red.log`），改为分享后通过。实际运行 `gradlew.bat --offline --no-daemon -PmapUiTestWithoutNative=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` 成功，app 216项JVM通过、0失败/跳过。本轮未重跑watermark-core测试，不将历史28项算作本轮通过。
- 专用只读API36模拟器开启飞行模式并关闭Wi-Fi/数据，执行 `:app:connectedDebugAndroidTest`，参数为 `-PmapUiTestWithoutNative=true` 和带引号的 `-Pandroid.testInstrumentationRunnerArguments.class=cn.campuslife.assistant.gate.GateFlowTest,cn.campuslife.assistant.HomeAndFavoritesFlowTest,cn.campuslife.assistant.HomeRepositoryDeviceTest,cn.campuslife.assistant.ToolboxReleaseTest`：15项通过、0失败/错误/跳过。外部VIEW/SEND及微信MAIN均被拦截；覆盖URL、无微信/启动受限、恢复/重建、连续点击、横屏、旧布局和固定快递入口。首次未给含点Gradle参数加引号而失败，纠正参数后通过，非业务故障。
- 手动从首页打开新页并查看运行截图`verification/gate176-page.png`，未安装微信时查看实际备用弹窗`gate176-fallback.png`。这不是已安装微信的真机测试；不证明分享界面、学校认证、权限认可或通行成功。
- 完整双ARM同证书非debuggable构建 `gradlew.bat --offline --no-daemon :app:assembleRelease :app:lintRelease` 成功；Debug/Release Lint均0错误、62警告，未屏蔽警告。发布准备脚本核对包名、code23/minSdk26及固定历史证书后生成本地三附件，不联网发布。
- 本机交付 `交付/安装包/校园生活助手-1.7.6-微信入口修正版.apk` 为78,858,278字节（75.205 MiB），较1.7.5同配置78,858,250字节增加28字节；SHA256 `d23b4a9efcbc9423c39291148107f85a0b85fbcab114d3d2c39a6f669d62424a`。四个地图.so与校园图逐文件SHA与1.7.5一致；净增量受DEX和打包对齐共同影响，不等于新增代码仅28字节。没有存储迁移或清数据操作。
- 本段为1.7.6发布前的本机验证记录，后续国内发布见上方UPD-007。仍需先确认学校是否提供允许外部App唤起的官方入口，再决定直达方案；仅凭当前二维码URL不能承诺一键进入微信网页。真实手机应在东门按学校规定测试，北门仍未接入。

UPD-006已完成：1.7.5/code22已公开为CNB最新稳定版，发布助手核对服务器三附件摘要，匿名latest和三附件完整下载核验通过。真机更新和学校东门现场仍待用户验证，未访问学校登记地址。

GATE-001已完成本地开发和离线验证：新增“快捷进出校”，只提供平潭校区东门两个原始二维码入口，用户点击后交Android系统处理；旧首页布局和三个固定快递入口保留。1.7.5/code22已发布CNB，签名配置未变，源码未提交或推送。GATE-002待现场确认：学校是否允许直接打开、浏览器/微信登录状态和实际通行结果；若要求微信扫码，继续扫描现场二维码。北门等待用户提供二维码。

## 1.7.5 更新准备 · 2026-10-27

- 本机兼容包 `交付/发布准备/1.7.5/campus-toolbox-1.7.5.apk` 为78,858,250字节，较1.7.4增加16,412字节；包名、code22、minSdk26、非debuggable、双ARM及历史证书经发布准备器核验。三附件已上传CNB。
- 本轮 `gradlew.bat --offline --no-daemon :watermark-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` 成功；应用216项测试实际执行，水印28项沿用缓存；Lint 0错误、63警告。上一轮断网模拟器13项通过，不代表本轮真机安装验证。
- 浏览器连接超时后，用户运行 `交付/发布工具/Publish-1.7.5.cmd` 并在本机隐藏输入令牌，发布助手报告CNB Release 2114930916815982592已发布且服务器附件摘要匹配；匿名latest与update.json现已确认1.7.5。手机更新尚未测试，未运行学校通行网络请求。
- 匿名完整下载三个附件到 `E:/skill/campus-toolbox-public/cnb-v175-check-d613fb00d6f54f0693285080ab4a3581`，APK 78,858,250字节，SHA256 `f0ecce03a646c2276b54106682d4bfa53c6b80c4525023c5b7b4d679adc7db79`；文件大小、元数据和SHA256SUMS均一致。

## 东门快捷入口 · 2026-10-27

- `gate/GateRoute.kt`固定两条用户提供的学校HTTPS原始地址；`GateLauncher.kt`仅创建系统ACTION_VIEW，处理无应用或系统拒绝；`GateActivity.kt`提供入校/出校、连续点击保护和现场扫码提示，不自动触发或声称通行成功。
- `home/ToolCatalog.kt`、`ToolNavigation.kt`、`ui/ToolGlyph.kt`及Manifest接入普通工具；复用旧布局归一化，新增ID为gate，不修改其他ID/存储。`GateRouteTest.kt`、`GateFlowTest.kt`、`HomeLayoutTest.kt`及两个首页设备测试覆盖链接、迁移和交互。
- 本轮实际运行 `gradlew.bat --offline --no-daemon :watermark-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` 成功；另执行 `:watermark-core:test --rerun :app:assembleDebug` 确保水印模块测试实际重跑。JVM共244项（app 216、水印28），0失败/错误/跳过。最终又运行 `:app:lintDebug :app:packageDebug --rerun :app:assembleDebug` 成功；Lint 0错误、63警告，其中新入口1项是Uri.parse的KTX建议。
- 专用只读emulator-5582启用飞行模式、关闭Wi-Fi和移动数据后，运行 `:app:connectedDebugAndroidTest -PmapUiTestWithoutNative=true -Pandroid.testInstrumentationRunnerArguments.class=cn.campuslife.assistant.gate.GateFlowTest,cn.campuslife.assistant.HomeAndFavoritesFlowTest,cn.campuslife.assistant.HomeRepositoryDeviceTest,cn.campuslife.assistant.ToolboxReleaseTest`：13项通过，0失败/跳过。测试拦截所有ACTION_VIEW，未请求学校通行地址；覆盖入/出各自Intent、无处理应用、快速点击、恢复/重建、横屏、旧布局及三个快递入口。去ARM的模拟器构建不作为交付或地图真机验证。
- 实际从首页进入页面并查看模拟器截图 `verification/gate-ui-check.png`；首次使用run-as直启非导出页面被系统拒绝，随后通过首页正常打开。未连接或测试真实手机。
- 同配置完整双ARM Debug基线79,751,647字节，最终79,751,726字节，增加79字节；新源码DEX有变化，APK打包对齐会影响净差值。增量打包曾留下填充，最终重打包；四个地图原生库和校园图与基线内容一致，无新增依赖或权限。基线全仓归档时Windows tar无法处理中文文档路径，随后使用ASCII应用/构建文件重新生成基线并成功构建。
- 本地测试包：`交付/安装包/校园生活助手-1.7.4-东门快捷入口-Debug测试.apk`，SHA256 `9172f138dbb9a1fd8deeecbf5c6d6f4406fcf11f8f6b5791495d95e260fedd1f`。这是Debug包，同versionCode21；未做真机覆盖安装，不应先卸载旧版。过程中的增量草稿移入`交付/历史记录`，历史发布包未改。

GIT-005已完成：源码已推送main，GitHub v1.7.4已公开为最新稳定版，tag与源码提交一致；main源码CI通过并核对实际报告。三个附件均已匿名完整下载，大小/SHA与准备文件、GitHub服务器及CNB一致。未上传Key、私钥、校园图源文件、用户数据或交付历史，未修改许可证。后续只补进度文档，不重签APK；手机验收仍归UPD-003/004/005。

## GitHub同步验证 · 2026-10-27

- 公开源码快照222个文件（含15个新增文件），不含本机配置、签名文件、原图、交付或验证记录。针对凭据和敏感路径的检查未发现候选；不是全量安全审计。
- 干净快照实际执行JVM、Debug Lint及Debug/未签名Release构建：242项JVM通过，0失败/错误/跳过；Lint0错误/61警告；两种APK构建成功。日志`verification/github174-public-build.log`。此构建没有私有地图Key和原图，产物不作为公开安装包。
- 同一快照的构建入口10项、发布准备46项检查通过，日志`verification/github174-build-script.log`及`verification/github174-release-script.log`。正常正式证书正例未验证，仍按用户同意的历史证书兼容过渡版发布。
- 独立只读审查了更新下载链路及倒计时相关改动，未发现阻止发布的问题。本轮没有重跑设备测试，也不改变此前手机验收缺口。
- 国内发布助手离线检查25项通过；首次补跑误传Markdown说明给要求JSON的参数，检查失败，改用原有JSON后通过，没有修改脚本或联网发布。失败与正确重跑日志分别保留为`github174-cnb-script-argument-error.log`和`github174-cnb-script.log`。
- 三附件重新核对后上传到GitHub草稿，确认服务器大小/SHA一致才公开并设为最新。tag v1.7.4指向647dfaf；main CI 40429235039成功且实际报告已核对，242JVM/0失败错误跳过、Lint0错误65警告、脚本10+46通过。CI与本机Lint警告数分别记录，不混为相同环境；未关闭任何检查。
- 三个GitHub附件已免登录完整下载到`E:/skill/campus-toolbox-public/github-v1.7.4`，大小/SHA与准备文件、服务器及CNB逐项一致；下载的update.json包名/code21/版本/tag/大小与APK摘要一致。证据`verification/github174-public-assets-check.json`。同字节APK沿用此前已核对的非调试/双ARM/旧证书，不冒充新一轮真机安装测试。
- 网络诊断：Git默认连接重置，单次HTTP/1.1推送成功；GitHub草稿不能按公开tag入口读取，改用已登录的Release列表按tag确认后核对三附件。curl匿名下载部分连接重置/超时，update.json重试成功，其余两个文件改用无认证Invoke-WebRequest完整下载成功；未更改全局代理或网络设置。

UPD-005：国内1.7.4发布、匿名文件核验和实际旧1.7.3发现更新已完成；余下用户ARM手机关闭VPN后的系统下载/安装及资料保留验收，继续与UPD-003/004跟踪。新令牌具备Release读写后已成功，不再要求用户重复创建或调整权限。凭据仅用于本次固定仓库API，不写入源码、App、环境变量或交付文件；未上传私钥或用户资料。

## 1.7.4发布后验证 · 2026-10-27 08:23—08:31

- 获得用户明确授权后通过CNB官方API创建草稿，顺序上传固定APK/update.json/SHA256SUMS.txt，确认三个服务器摘要一致后才发布并设latest。没有覆盖旧1.7.3附件，没有推送Git源文件；CNB源码自动压缩包仍只对应其README初始化提交，不冒充本机完整源码。
- 三附件免登录完整下载到`E:/skill/campus-toolbox-public/cnb-v1.7.4`，大小/SHA与准备文件及服务器逐项一致。APK包名cn.campuslife.assistant、code21/1.7.4、minSdk26、非debuggable、双ARM及固定历史签名均通过；元数据与真实APK一致。证据`verification/release174-cnb-public-check.json`。
- emulator-5582/Campus_Map_API36恢复到历史1.6.0/code12快照，未清数据，以`adb install -r`覆盖冻结的真实1.7.3/code20去ARM测试副本和已准备的仪器测试包；实查实际包为20/1.7.3。
- 显式`liveCnb174Check=true`运行`PreparedUpdateSmokeTest#installed173DiscoversActualPublished174WithoutGithub`，1项通过，0失败，见`verification/release174-live-on173-device.log`。旧App实际读取CNB、offer=true/code21/tag与摘要正确；GitHub请求一旦发生即断言失败，实际未发生。
- 随后从实际旧版首页启动：自动显示“新版本1.7.4 · 查看更新”；点击弹窗包含当前1.7.3/最新1.7.4、稍后再说和立即更新。保留`release174-home.xml/png`与`release174-offer.xml/png`，截图时再次核对实际安装仍为code20。只查看提示，没有让模拟器安装正常ARM新版。
- APK与既有冻结候选完全相同，本轮不因发布而重建或冒充重跑242JVM/双Lint；这些仍是下方发布前历史验证。本轮新增的是公开文件核验、1项旧App线上测试和两种实际UI状态。
- 尚未验证：用户ARM手机关闭VPN、系统DownloadManager整包下载/权限/取消/覆盖安装及新旧资料保留。准备包兼容、匿名下载和旧版提示通过，不等于手机系统安装全程通过。
- 收尾再次匿名确认latest为1.7.4/ID2114875750033051648，并核对报告、下载文件及UI/测试证据均存在；文档差异检查通过。专用模拟器已正常关闭，临时发布JS会话已重置，没有保存发布凭据。

本轮07:47运行发布助手的无网络预检查，1.7.4三个文件及固定摘要全部通过；没有重构、重新签名、重跑构建/JVM/Lint或冒充新的设备验收。公开1.7.4及旧code20发现新版的检查尚未执行。

## 1.7.4发布前验证（历史记录）

- 正常双ARM Debug、同证书非debuggable Release、JVM和双Lint构建成功：`verification/release174-normal-arm-build.log`。实际JVM242项，0失败/错误/跳过；双Lint均0错误/62警告。无新生产依赖或存储变更。
- Release为78,841,838字节 / 75.189 MiB，相对1.7.3同配置增长0。SHA256 `7a9888fe941921b63a0f8fadcccd8cb5e9ee514a443c3ca9982448bc1ee7b2a9`；三附件在`交付/发布附件/v1.7.4-r1`，交付APK与冻结候选哈希一致。4个.so及校园PNG与1.7.3一致；包名/code21/min26/双ABI和历史证书已核对。
- 专用API36模拟器实际安装旧code20，调用旧App的ApkVerifier验证准备好的正常ARM code21 APK，1项通过：`verification/release174-prepared-on173-device.log`。这是发布前本地兼容验证，不是线上发现或系统覆盖安装。
- 新增`Publish-CnbRelease.ps1`及`Test-CnbPublish.ps1`，此前PS7和WindowsPS5.1各21项离线测试通过。输入窗口故障后补4项安全错误记录用例，先21通过/4失败，再两环境各25通过/0失败，见`release174-safe-failure-red.log`和`release174-safe-failure-green-*.log`。10月27日本轮只读取这些既有记录，没有冒充重跑。失败记录仅保存白名单步骤、原因和HTTP码，不保留远端正文、令牌或签名上传链接；原来直接启动PowerShell导致exit关闭窗口，交付CMD入口本身有pause。
- `aapt`直接读取中文路径报Illegal byte sequence；同哈希英文路径冻结副本读取成功，未修改APK或换签名。此为本机工具路径限制，不是App安装包损坏。
- 当时未完成的发布、匿名新三附件核验和实际旧code20发现新版现已完成，见上方“1.7.4发布后验证”；手机系统下载安装仍待用户验收。此前401/403不再是当前阻塞，历史记录保留。未提交、推送或发布GitHub。
- 本轮文档差异/JSON读取检查通过，交付APK摘要未变；未重跑构建/JVM/Lint。查询原专用模拟器emulator-5582返回连接拒绝，未运行新的设备检查；取得有效授权后再启动专用环境，不使用用户手机替代。

CD-001已完成并保留在1.7.3。UPD-004国内发布/读取验证完成，剩断VPN真机验收：用户本人已初始化README、创建v1.7.3并上传APK/update.json/SHA256SUMS.txt；匿名latest核对ID2114724021547225088，draft=false、prerelease=false、is_latest=true，三个附件完整。无需继续重试浏览器上传，Agent只读验证公开API/下载，没有推送GitHub或上传私钥/个人数据。

下一步：已安装1.7.3/code20的手机在设置点“检查更新”测试1.7.4；自动检查有24小时缓存，手动重复约一分钟防抖，不卸载清数据。无CNB来源的更早旧包才需先手动覆盖支持国内源的版本。真实手机验收系统下载、权限/安装取消和覆盖保留资料后再关闭UPD-003/004/005；未来每次稳定发布仍须递增code并提供正确三附件，不仅改版本名。

## CNB公开后验证 · 22:38—22:46

- 三附件免登录完整下载到`E:/skill/campus-toolbox-public/cnb-v1.7.3`，逐文件大小/SHA与本地准备文件及CNB服务器摘要一致。APK的aapt包名/code20/min26/双ARM和apksigner固定历史证书通过，未重新签名或更改公开APK。
- 新增显式线上`CnbLiveReleaseSmokeTest.kt`，只在专用模拟器且传`liveCnb173Check=true`时联网。实际CnbReleaseClient读取同Release元数据、官方附件Range预检、公开APK整包哈希/包名/版本/签名兼容比较通过；UpdateRepository实际CNB读取成功、GitHub请求为0、同版不提示通过。19→20为规则比较，不称旧版本实际覆盖安装。
- emulator-5582/API36：2项真实线上检查+7协议+6缓存共15项通过，见`verification/release173-cnb-live-device.log`；对应测试构建成功，Debug Lint0错误/62警告，见`release173-cnb-live-build.log`、`release173-cnb-live-lint.log`。未为此重跑/冒充新的JVM242或历史43项全组结果。
- 本轮只增加设备烟测及README/STATUS/TASKS/CHANGELOG/国内源说明/本机使用说明；无生产代码、依赖、签名或APK字节变化，公开包体积增长0字节。GitHub未提交、推送或运行新CI。
- 未验证：断VPN真实ARM手机、真实系统DownloadManager整包下载和系统覆盖安装、ARM地图/GPS。浏览器控制故障未宣称修复；上传由用户完成，未提取登录凭据。专用模拟器在验证完成后关闭。

## 1.7.3发布前验证（本轮读取历史记录）

- 242项JVM强制重跑，0失败/错误/跳过：`verification/release173-jvm-fresh.log`。
- 专用API36 x86模拟器43项相关设备回归通过：`release173-device-regression.log`。仅模拟器，不是ARM真机或国内实际下载验收。
- 新增国内来源缓存用例先在未接入依赖的旧逻辑失败，修复后通过；其他GitHub缓存用例显式隔离CNB，避免默认配置启用后误连真实站点。
- 正常双ARM Debug、同证书非debuggable Release、双Lint构建成功；两种Lint均0错误/62警告：`release173-normal-arm-build.log`。无新增依赖或存储迁移。
- 发布准备器检查code20、包名、非debuggable、固定旧证书和两ABI通过；元数据大小/SHA与冻结候选及交付APK一致。4个.so和校园PNG共5项哈希与1.7.2相同，实际交付DEX包含固定CNB仓库。
- 同配置Release相对1.7.2增长0字节；Debug为79,755,840字节，相对79,751,647字节增长4,193字节。未裁剪地图库或降低图片质量。
- 主要修改：`app/build.gradle.kts`的20/1.7.3和默认仓库、`UpdateRepository.kt`的可注入来源、`UpdateCacheTest.kt`的隔离与优先/缓存用例，以及管理文档。
- 本地交付：`交付/安装包/校园生活助手-1.7.3-国内源配置版.apk`；公开附件对应文件在`交付/发布附件/v1.7.3-r1`；操作说明在`交付/使用说明/1.7.3-CNB发布说明.txt`。1.7.2及历史交付保留。
- 当时未做的CNB上传/匿名下载现已完成，见上方“CNB公开后验证”；断VPN手机/系统安装和ARM地图/GPS仍待验收。

## 1.7.2历史验证

- 242项JVM强制重新执行，0失败/错误/跳过：`verification/release172-jvm-fresh.log`。
- 正常双ARM Debug、同证书非debuggable Release和双Lint构建成功，均0错误/62警告：`release172-normal-arm-build.log`。无新依赖或存储schema迁移。
- 专用API36 x86模拟器emulator-5582：42项相关设备回归通过（预设/日历/时间/保存/取消/重建/旧数据、首页、更新协议/缓存/下载/安装提示）：`release172-device-regression.log`。375dp、字体2、横竖屏另6项通过：`countdown172-matrix-font2.log`。去ARM测试包不用于交付。
- 3项UI红测试先在旧版失败。编译曾出现控件上下文引用错误，已修复；首轮测试夹具错误使用列表行performClick、布局未等待滚动结束，分别改为真实item点击/条件等待后通过。截图增加等待绘制帧，已查看列表及编辑页，不用旧画面冒充新界面。
- 本地发布准备器以显式LegacyCompatibility验证code19、固定旧证书、非debuggable、两ABI、包名及元数据成功；未上传。1.7.1→1.7.2同配置签名Release增长0字节，Debug增长16,384字节；原生库/校园图不裁剪。
- 最近修改：planner界面和预设；update客户端/来源缓存/下载地址校验及对应测试；构建增加可选CNB固定仓库和19/1.7.2；README/AGENTS/TASKS/CHANGELOG及本状态。
- 本地交付：`交付/安装包/校园生活助手-1.7.2-试用版.apk`、`交付/使用说明/1.7.2-使用说明.txt`和`交付/界面预览/1.7.2`两张合成示例实机界面截图。交付APK哈希与冻结候选、发布准备器输出一致；4个.so与校园PNG共5项哈希和1.7.1相同。专用模拟器已关闭。
- 独立只读UI审查无已确认P0/P1；确认一项P2：日历/时间弹窗中尚未按确定的新选择在旋转后需重选（CD-002），不影响已确认草稿或已保存记录。更新新增客户端/缓存/规则/下载差异已静态阅读，尚不称完整安全审计；协议、边界和下载回归已实跑。
- 尚未验证：本项目CNB真实附件、断VPN下手机检查/完整下载/系统覆盖安装、真实ARM地图/GPS。Edge浏览器连接超时，已给用户注册网址；未收集密码/验证码，没有代购、充值或替用户完成认证。

下面是1.7.1的历史发布验证，不是本轮重跑结果。

## 已完成的功能

- 首页固定三个快递入口，普通工具可排序/隐藏。
- 平潭校区地图搜索、前台定位、导航、个人地点编辑/隐藏和校园图。本机草稿在非调试包也保留，不上传。
- 单张照片水印：所选资料/时间、顺序、内容/标签、位置、命名及高清另存，不覆盖原图。
- 个人课表：手动管理、学校当前学期导入、离线查看/详情、备份恢复。入口在“个人课程表 → 管理 → 学校导入 / 备份与恢复”。
- 倒计时及可选本机提醒。批量水印、收藏、待办入口不加回，旧数据不清空。
- 更新：稳定Release、整数code、同版元数据、缓存/忽略、系统下载/取消/重试、大小/SHA/包名/版本/签名核对和系统确认安装。旧1.7.0实际检测到1.7.1；更早版本先手动安装。

## 最近修改与发布策略

app构建配置增加四项环境变量显式签名、可选release设备测试、LOCAL_MAP_EDITING；未提供签名仍为未签名分析包，无CI调试签名兜底。map三文件修复非调试构建忽略本机草稿和关掉编辑的问题，无存储schema迁移。发布脚本增加显式LegacyCompatibility，仅接受固定历史证书；默认正式模式继续拒绝Debug，客户端校验不降低。新增兼容/升级/显式线上用例，维护README、AGENTS、TASKS、CHANGELOG、BUILD/MAP/RELEASING及第三方说明，无新生产依赖。

用户明确批准同证书、关闭调试的兼容过渡包，并确认高德针对本App的公开使用授权和校园图的APK分发许可。这是用户确认，不冒充已从控制台/学校文件独立核验；不代购、不承诺永久免费或无限配额。私钥、口令和Key不在Git源码、CI配置或更新元数据公开；Android客户端Key按获准的SDK方式含于APK。私钥备份仅在忽略的本机交付/私有签名，无新证书或许可证变更。

## 本轮实际验证

2026-10-26，JDK21 / SDK36 / Build Tools35.0.0；设备仅为专用API36 x86模拟器emulator-5582。

- 强制重跑216JVM，0失败/错误/跳过：release171-jvm-fresh.log。
- 正常双ARM Debug、显式签名非debuggable Release及双Lint构建成功；两种Lint均0错误/62警告，无baseline或关闭检查：release171-normal-arm-build.log。
- PowerShell7/5.1发布脚本各46通过，构建入口10通过。显式旧证书例外先红后绿，真实兼容APK准备正例通过，不冒充新正式证书正例。
- 非调试草稿有真实红测试后修复。首组36项中35通过/1安装权限前提跳过，对应分支另1通过；地图/课表/倒计时26通过，学校WebView隔离组9通过。
- 旧1.7.0/code17 seed1通过→同证书覆盖1.7.1/code18→verify1通过，开新版首页再verify1通过。资料/模板/快递设置/课表/倒计时/个人地图文件哈希不变。只在夹具准备前重置模拟器合成数据，seed到verify间没有清数据；去ARM副本不用于交付。
- 正常ARM候选aapt/apksigner/minSdk26/双ABI及3项课表导入DEX标记通过；4个.so和校园PNG共5项哈希与旧版相同，获准地图配置存在而未打印。
- 独立只读审查无Critical/Important。Minor留待后续：升级夹具内部加版本断言、补实际ARM地图入口断言；本轮另有命令行实际版本证据和非调试自定义地点控件回归。
- [发布源码CI 40279993473](https://github.com/dufan82/campus-toolbox/actions/runs/40279993473)成功；下载报告核对216JVM、0失败/错误/跳过、Lint0错误/65警告；日志核对脚本10+46。CI临时Debug产物不是Release兼容APK。
- 匿名HTTP读取latest并下载全部3附件，大小/SHA与元数据/服务器digest一致。下载在E盘skill/campus-toolbox-public/v1.7.1。
- 旧code17真实GitHub API/元数据和公开APK签名完整性检查两次通过，第二次使用独立匿名下载文件。旧首页实际显示“新版本1.7.1”，弹窗显示当前/最新版本及更新按钮；证据release171-live-metadata-green.log、release171-live-anonymous-apk-green.log、release171-home.xml/png、release171-offer.xml。

本机日志/截图在忽略的verification中，未上传用户数据。此前详细状态保存在本机交付/历史记录/STATUS-1.7.1-发布前验证.md，历史Git记录和验证不删除。

## 体积

同SDK/ABI/原图/构建类型，对比历史1.7.0：

| 配置 | 1.7.0 | 1.7.1 | 差值 |
| --- | ---: | ---: | ---: |
| Debug | 79,854,144字节 | 79,735,263字节 | -118,881字节 |
| 未签名Release | 78,837,742字节 | 78,837,742字节 | 0 |

公开签名Release为78,841,838字节。无新依赖，不裁剪地图库或降低图片质量，不把跨构建类型差值当功能优化。

## 已知问题、阻塞与下一步

- 这是沿用历史证书的兼容过渡版，不是新正式证书发行；CI旧包临时证书不同，不承诺覆盖，不让用户卸载清数据绕过校验。新证书迁移另行规划。
- 真实系统DownloadManager任务已启动，但模拟器环境等待网络、未完成整包下载，不能宣称下载→系统安装全程通过。首次线上用例因模拟器飞行模式DNS失败，关闭后API/完整性通过；虚拟WiFi后已连接，系统下载仍待核对。
- 下载页持续刷新使uiautomator无法取得idle，已保留截图。shell直接启动未导出更新页、直接查询下载Provider被系统拒绝；未扩大导出/权限，不把诊断入口拒绝当业务漏洞。
- ARM地图/GPS、鉴权、厂商安装权限/实际覆盖仍待真机；学校完整学期/平台关闭后离线重开继续SCH-003。历史WebView长组原生崩溃未凭隔离组通过宣称修复。
- 自动检查有24小时缓存，1.7.0可设置中手动检查，短时重复有防抖；code18不再提示自身。
- 下一步UPD-003：指定ARM手机验收公开包的系统下载、取消/失败重试、安装权限/取消、覆盖和数据保留；不增加非官方代理或放宽TLS/签名。
