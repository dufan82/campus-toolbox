# 开发进度

更新时间：2026-10-26 13:21 +08:00

## 版本与范围

- 本机交付：1.7.0 / code17，包名 `cn.campuslife.assistant`，安装后仍叫“校园生活助手”。
- 开发分支：`feature/v1.6.0`，基于 `bbc9611`；本轮已授权同步源码到远程main，推送和CI核对进行中。
- 仓库：https://github.com/dufan82/campus-toolbox
- 安装包：`交付/安装包/校园生活助手-1.7.0-精简与更新版.apk`，79,854,144字节 / 76.155 MiB。
- SHA256：`b388d42e112e3d526a4a11909ebf363ed1d5309b3235bceac4f56f9e9f4bd536`。
- 保留历史Android Debug证书以便覆盖旧包；不是正式发行包。未更换签名、创建Release、上传APK或修改许可证。

## 当前功能

- 首页固定拼多多包裹、拼多多身份码、抖音订单；普通工具可排序和隐藏。
- 平潭校区地图：搜索楼栋/公共地点、前台定位、高德导航、自定义地点；收藏和最近访问入口已撤下。
- 单张照片水印：资料/字段顺序、内容模式/标签、位置、命名及高清另存，原图不覆盖。
- 本地课程表：手动管理、学校当前学期导入、离线周表/今日、课程四项详情及备份恢复。
- 倒计时：重要日期、过去天数、每年重复及可选本机提醒；待办入口和旧待办通知已停用。
- 应用更新：固定GitHub仓库稳定Release及同Release的update.json、整数版本号、缓存、忽略版本、系统下载/进度/取消/重试、SHA/包名/版本/签名核对与系统确认安装。
- 批量水印已撤下；旧待办、收藏、批量任务和输出保留，没有清空用户记录。

## 本轮任务与文件

UPD-001、LEAN-001本机实现和针对性验证完成；GIT-003正在做公开清单和远程自动检查。

源码6d27f3f已同步到远程main（常规Git线路失败后，通过官方Git数据库API同步并核对完全相同的tree和commit SHA，无强制覆盖）。[首次CI 40254382037](https://github.com/dufan82/campus-toolbox/actions/runs/40254382037)的应用测试/Lint/Debug及未签名Release构建成功；发布脚本39通过/3失败，失败为真实Debug证书输出未被完整识别，不能写成CI通过。当前把CI发布检查固定到明确安装并已在本机验证的Build Tools35.0.0，保留全部断言，并增加不含证书内容的解析计数以便进一步定位；修正后远端验证待执行。未修改应用代码或安装包。

主要变化：新增 `update/` 与对应JVM/设备测试；调整MainActivity、home、map、planner、reminder、Manifest和FileProvider路径。新增本地发布准备/验证脚本、docs/RELEASING.md，维护BUILD/CI/LINT及四个管理文件。README仅介绍功能用途。此前1.6.x课表等未提交源码一并保留、核对后同步，不覆写其他任务。

## 实际验证

2026-10-26，本机JDK21 / SDK36 / Build Tools35.0.0。设备只使用专用API36 x86模拟器emulator-5582，不是用户真机。

- JVM：强制重跑216项（app188 + watermark-core28），失败/错误/跳过0，`verification/v170-final-jvm.log`；复审后的正常ARM构建再次执行相关任务成功。
- 正常ARM：lintDebug、assembleDebug、assembleRelease成功，`verification/v170-reviewed-normal-arm-build.log`；Lint0错误/62警告，没有baseline或关闭检查。Release仍未签名，只用于体积分析。
- 干净公开文件副本：不含local.properties、地图Key、校园原图，JVM/Lint/Debug/未签名Release构建全部成功，`verification/v170-public-source-build.log`。这不代表无Key的CI包能显示真实地图。
- 初次整组173项：166通过、7按前提跳过、0失败，`v170-full-device.log`；含本地非debuggable同证书APK夹具正例。更新相关最终20项设备测试通过，`update-review-green-device.log`；安装权限拒绝另1项通过，`v170-denied-install-device.log`。
- 最后整组在第136项WebView测试中断，Chrome_IOThread / libwebviewchromium SIGTRAP，日志为`v170-reviewed-full-device.log`、`v170-webview-native-crash.log`，不能计为通过。随后完整用例按进程分组复测：非WebView167项中159通过/8前提跳过，学校导入7通过、登录读取2通过，合计168通过/8跳过/0失败；日志`v170-reviewed-nonwebview-device.log`、`v170-reviewed-school-isolated.log`、`v170-reviewed-login-isolated.log`。8项包括6个专用阶段/素材前提和2个权限反向分支；两种权限拒绝均另跑通过，没有修改或屏蔽学校断言。
- 普通字号及约375dp/字体2.0各2项通过，涵盖首页横竖屏三个快递入口完整可点、更新页面；`v170-layout-normal-device.log`、`v170-layout-font2-device.log`。实际查看原生截图，未用设计图代替运行结果。
- 1.6.4→1.7.0模拟器覆盖：seed2项、verify2项通过，`v170-upgrade-final-seed.log`、`v170-upgrade-final-verify.log`。课表/撤回副本/旧待办/倒计时文件字节不变，资料、模板和快递配置保留。旧包仅在独立副本去ARM库并重新用原证书签署以适配x86，不改原始交付包，不当作ARM真机验收。
- 先红后绿覆盖协议、限流/离线恢复、下载重复/恢复/取消、临时文件、升级清理、已校验包失效、错误反馈、长说明滚动及重试校验。准备夹具时的ZIP格式、权限切换杀进程和测试包未安装等失败均未算业务通过。
- 发布准备脚本42项、构建入口10项通过。真实debug/unsigned APK被拒绝；正式签名正例尚无，边界替身测试不能代替正式发行验收。
- 原ARMv7/ARM64的4个.so和校园PNG共5项哈希不变；私有Key保留在本机交付中，未打印或提交。安装包、正常构建与ASCII核对副本哈希一致。

## 体积

相同SDK、ABI、原图和签名配置，与1.6.4比较：

| 构建 | 1.6.4 | 1.7.0 | 净变化 |
| --- | ---: | ---: | ---: |
| Debug交付 | 79,821,376字节 | 79,854,144字节 | +32,768字节 |
| 未签名Release分析 | 78,804,934字节 | 78,837,742字节 | +32,808字节 |

本轮无新生产依赖，没有裁剪地图库或降低图片质量。上表是精简和更新模块合并后的净变化，不冒充更新模块单独增量。主要体积仍来自地图原生库及校园原图。

另外在两个不含Key/原图的独立测量副本，用同SDK/ABI/版本/签名构建，仅移除更新模块、首页更新接入、安装权限与Provider路径作对照；其他生产文件不变，原工程未删改。带/不带更新的Debug为72,155,041/72,105,777字节，更新单独增加49,264字节（0.047MiB）；未签名Release为71,257,520/71,224,644字节，增加32,876字节。这是同配置对照，不与含原图的交付包混比；低于原0.5MiB预算。证据`v170-public-source-build.log`、`v170-update-size-baseline-build.log`。

## 已知问题与下一步

- 当前仓库没有稳定Release，检查应提示“暂未发布正式版本”；旧App必须先手动安装本轮过渡版，不能远程补入检查代码。
- 正式证书与旧调试签名不能直接互换。正式签名/兼容过渡、创建Release和上传APK仍须另行确认；不让用户卸载清数据绕过校验。
- 真机地图/GPS、第三方快递最终页面、厂商安装权限/系统安装、完整学校账号学期及离线重开仍需实际手机验收。模拟器WebView长组崩溃尚不能归因或宣称已修复，独立复测不代替真机稳定性观察。
- 地图Key的公开服务许可、校园原图再分发许可及整体许可证仍未解决，不自动购买服务或补许可证。
- 本机开发/回归/APK已就绪，接下来同步源码并检查该提交的GitHub Actions。根目录旧详细记录留在本机`交付/历史记录/STATUS-1.6.x-开发记录.md`，版本变化见CHANGELOG。
