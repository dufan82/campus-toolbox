# 开发进度

更新时间：2026-10-27 09:02 +08:00

## 当前版本与分支

- 1.7.4 / versionCode 21已在CNB和GitHub发布为最新稳定版，均非草稿或预发布。CNB Release ID 2114875750033051648；GitHub Release ID 424923845。实际旧1.7.3已通过国内源检测到新版并显示首页入口和版本弹窗，手机系统安装全程仍待用户验收。
- 本机分支feature/v1.6.0。生产源码提交`647dfaf9d2331df48edf0058a1183baaf78cae77`已推送GitHub main，tag v1.7.4精确指向此提交；后续仅进度文档提交不改变安装包。
- 1.7.4为78,841,838字节 / 75.189 MiB，SHA256 `7a9888fe941921b63a0f8fadcccd8cb5e9ee514a443c3ca9982448bc1ee7b2a9`，相对1.7.3同配置增长0。保留历史证书、双ARM、地图配置及校园图，关闭调试，无存储变更。GitHub三附件服务器摘要与CNB一致，不重新构建或签名。
- [GitHub下载页](https://github.com/dufan82/campus-toolbox/releases/tag/v1.7.4)；[源码提交的自动检查](https://github.com/dufan82/campus-toolbox/actions/runs/40429235039)已成功：242JVM、Lint0错误/65警告、构建入口10项、发布准备46项、Debug及未签名Release构建均通过，实际报告已下载核对。
- [当前国内下载页](https://cnb.cool/campus-toolbox/campus-toolbox/-/releases/tag/v1.7.4)。用户手机保持1.7.3，在设置中点“检查更新”验证国内下载与系统覆盖安装，不要先卸载。

### 旧版本记录
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
