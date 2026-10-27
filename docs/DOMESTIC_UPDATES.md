# 国内更新源

当前国内最新版为1.7.4/code21，2026-10-27通过用户授权的官方API发布；Release ID 2114875750033051648，非草稿、非预发布、已设latest。三个附件已免登录完整下载，大小/SHA与本地准备文件及服务器摘要一致；包名、非调试、双ARM及历史签名通过。实际旧1.7.3已检测到1.7.4并显示首页入口和版本弹窗，检查不需要GitHub。断VPN真机的系统下载和覆盖安装仍待验收，本次未推送GitHub。

1.7.3开始默认绑定 https://cnb.cool/campus-toolbox/campus-toolbox ，国内源优先、GitHub备用。1.7.3及其旧附件保留，不覆盖。

## 已核对的下载

- [当前国内发行版1.7.4](https://cnb.cool/campus-toolbox/campus-toolbox/-/releases/tag/v1.7.4)
- [1.7.4 APK](https://cnb.cool/campus-toolbox/campus-toolbox/-/releases/download/v1.7.4/campus-toolbox-1.7.4.apk)：78,841,838字节，code21，SHA256 `7a9888fe941921b63a0f8fadcccd8cb5e9ee514a443c3ca9982448bc1ee7b2a9`。相对1.7.3同配置增长0。
- 同Release的update.json为276字节，SHA256SUMS.txt为169字节；三个文件匿名下载到`E:/skill/campus-toolbox-public/cnb-v1.7.4`。见`verification/release174-cnb-public-check.json`。
- 冻结的实际旧1.7.3/code20，在专用模拟器显式传`liveCnb174Check=true`执行`PreparedUpdateSmokeTest#installed173DiscoversActualPublished174WithoutGithub`，1项通过。随后旧版首页自动显示新版入口，弹窗显示当前1.7.3/最新1.7.4；证据为`release174-live-on173-device.log`、`release174-home.xml/png`和`release174-offer.xml/png`。没有冒充手机系统安装验收。

### 1.7.3历史核验

- [国内发行版](https://cnb.cool/campus-toolbox/campus-toolbox/-/releases/tag/v1.7.3)
- [1.7.3 APK](https://cnb.cool/campus-toolbox/campus-toolbox/-/releases/download/v1.7.3/campus-toolbox-1.7.3.apk)：78,841,838字节，code20，SHA256 `746c1529090543101770d9564536784748c7052f29805e84e1f304aab47ea364`。
- 同版`update.json`为276字节、`SHA256SUMS.txt`为169字节。源码自动压缩包只对应CNB的README初始化，不代表本机尚未推送的APK源码。
- API36专用模拟器实跑`CnbLiveReleaseSmokeTest`两项加协议/缓存13项，共15项通过；实际读取CNB、预检官方附件地址和整包哈希/包名/版本/签名，不需要访问GitHub；同版本不提示升级。旧code19只是兼容规则比较，不冒充实际旧包覆盖安装。

当时的线上烟测必须显式传`-e liveCnb173Check true`，且只在专用模拟器运行。该组固定断言latest是1.7.3，当前latest已为1.7.4，不再把它对当前线上运行或把默认跳过算通过。1.7.4使用上方实际code20的专用用例。模拟器为去ARM库测试构建，公开APK保留双ARM库。历史证据在本机`verification/release173-cnb-live-device.log`及`E:/skill/campus-toolbox-public/cnb-v1.7.3`。

## 配置与发布

### 1.7.4本机发布助手

1.7.4/code21已公开，不需要用户再执行发布命令。用户明确授权官方API发布这次三个附件，不涉及GitHub推送。发布凭据只用于负责人操作，App免登录读取公开文件，不需要用户提供Token；撤销负责人发布令牌不会影响App的检查和下载。

`交付/发布工具/Publish-1.7.4.cmd`及脚本保留为本机发布工具，固定本次版本、包名和APK摘要，不是自动跟踪任意新包的后台发布器。先建草稿、上传APK/update.json/SHA256SUMS.txt并确认服务器摘要，再设最新稳定版。补充安全诊断后PS7/WindowsPS5.1各25项离线测试通过；本次另已完成真实发布、匿名下载和旧App线上发现。以后新版须更新准备文件和明确发布授权，不能只改App里的版本名称。

此前401/403为历史问题：先前提供的内容未能鉴权，之后的令牌缺少`repo-release:rw`。用户按指定仓库和Release读写重新创建后，本次发布成功；没有要求全选权限、开放源码写入或账号管理。Edge未连接不再阻塞发布，无需继续浏览器重连或手动上传。

API需Bearer访问令牌，不能猜测、拼接或把Git用户名当令牌；参见[官方访问令牌说明](https://docs.cnb.cool/zh/guide/access-token.html)和[API认证说明](https://docs.cnb.cool/zh/develops/openapi.html)。默认推荐本机隐藏输入；用户本轮明确授权Agent使用其提供的凭据，不将其写入源码或App。

手机保留1.7.3，在设置点“检查更新”即可测试1.7.4。自动检查有24小时缓存，手动短时重复约一分钟防抖。不要提前手动装1.7.4，否则同版本不会再提示自身。最终安装仍需用户在Android系统确认，不静默安装。

### 常规配置

1. 负责人在Edge打开 https://cnb.cool ，本人用微信注册、认证，创建校园工具箱的公开项目。不开通付费预算，不将密码、验证码或发布Token交给App。
2. 默认仓库已在构建配置中固定为`campus-toolbox/campus-toolbox`。需要覆盖时在本机忽略的`local.properties`写入`CAMPUS_CNB_REPOSITORY=实际组织名/实际仓库名`，或提供同名环境变量。显式留空禁用CNB；地址不从远端元数据更换。
3. 按RELEASING.md准备同证书、非debuggable APK和真实`update.json`、`SHA256SUMS.txt`，确认发布授权后放在CNB同一个正式非预发布Release，标记latest。两个平台使用相同APK字节和版本元数据，不重复签名。不要把安装包提交进Git源文件。
4. 发布前免登录读取版本文件、完整下载APK，对比SHA256、包名、code和签名；再在关闭VPN的实际手机上测试检测、下载进度/取消/重试、系统权限与覆盖保留资料。公开示例或合成夹具通过不能代替此步骤。
5. 已安装GitHub-only旧版的用户需先手动覆盖安装一次配置好国内源的新包；不要卸载清空资料。

## 实现边界

- CNB使用`https://cnb.cool/固定仓库/-/releases/latest`公开入口。独立`api.cnb.cool`匿名返回401，不在App中嵌入账号令牌解决。
- 初始附件严格属于固定仓库及同一tag；仅允许HTTPS官方附件主机跳转。CNB预检最终地址后交给系统DownloadManager传输，App不冒充能拦截系统后续每次跳转。APK仍做整包大小、SHA256、包名、版本、minSdk和本机签名核对。
- 按来源/仓库隔离ETag和缓存，保留旧GitHub缓存及已忽略版本。CNB缺少正式Release、请求失败或限流时回退GitHub；两者都不可用不影响其他工具。
- 1.7.3由用户本人完成建仓库和手动上传。1.7.4由Agent在明确授权后使用官方API发布固定三个附件，凭据不写入项目或App；此前认证失败只作诊断，不扩大授权或猜测凭据。未新增付费服务或安装无关插件。
