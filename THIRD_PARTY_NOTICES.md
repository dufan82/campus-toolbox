# 第三方与参考说明

## Gradle Wrapper

`gradlew`、`gradlew.bat` 和 `gradle/wrapper/gradle-wrapper.jar` 为 Gradle Wrapper，保留原有版权声明，按 Apache License 2.0 提供，不因本项目的许可证而重新授权。

- 项目：https://github.com/gradle/gradle
- 版本：8.14.3
- 许可全文：[Apache-2.0](LICENSES/Apache-2.0.txt)
- 官方校验值：https://services.gradle.org/distributions/gradle-8.14.3-wrapper.jar.sha256
- 本仓库 JAR SHA-256：`7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172`（2026-10-23 与官方校验值比对一致）。

## 测试照片

`app/src/androidTest/assets/campus-sample.jpg` 是公开示例照片，仅用于设备测试，不随生产 APK 安装。

- 来源：https://images.unsplash.com/photo-1541339907198-e08756dedf3f
- 许可：https://unsplash.com/license
- 保留原有 Unsplash 许可，不纳入项目源码许可证。不是用户相册照片，不代表学校、学生或项目成果。
- 本轮检查：900 × 600，未发现 EXIF GPS/拍摄身份信息；可见图像为公开毕业场景，不含用户身份码截图。

## QuickJump 参考边界

快递快捷入口参考 [QuickJump](https://github.com/chuhe0719/QuickJump) 的公开入口思路与 URI 字符串。校园工具箱的目录、链接校验、存储、启动和界面自行实现；没有分发 QuickJump 的 APK、源码、图标、应用身份或签名文件。

这里不声明取得 QuickJump 源码的再许可，不将参考项目的材料套用本项目许可证。后续如需复制第三方实现，须先另行核对授权。

## 用户提供的校园导览图

本机可选的 `app/src/main/assets/pingtan-reference.png` 为用户提供的福建信息职业技术学院平潭校区导览图，4822×3470，SHA-256：`9694290a55c2b318048448ffc00a8db6ec6ff3118f499dbe6dd967434d5706f2`。2026-10-26用户明确确认已经获得公开放入安装包的许可，1.7.1兼容过渡包据此包含原图；图片仍不单独提交公开Git源码，有相应权限的构建者可自行补入。

原图版权仍归原权利人，本项目不宣称原创、不据此向其他人授予开源图片许可。上述许可状态来自用户明确确认，不冒充已经独立取得或核验学校书面文件。图面点为示意图锚点，不是测绘/GPS数据。

## 公共地点坐标

1.5.1使用维护者提供的35处公共地点坐标（30手动记录、5条保留来源的供应方记录）及2个自定义名称。它们不代表专业测绘、学校官方信息或入口通行保证，不包含学生个人位置和轨迹。原始附件不覆盖，已撤掉的记录不补回。供应方记录仍受其条款约束；这里没有对高德底图、整个校园地图或其他POI作再许可，不能据此批量抓取、公开运营服务或承诺商业发行权。

## 构建依赖

AndroidX、Kotlin、JUnit 等依赖由 Gradle 按各自坐标获取，版本见构建文件，受各自许可证约束。本仓库不打包 Maven 缓存或重新授权这些依赖；对外发行应用时仍需核对随 APK 分发的依赖许可与通知。

## 高德地图服务与 SDK

地图测试版使用高德开放平台组合 SDK：`com.amap.api:3dmap-location-search:11.3.100_loc11.3.101_sea9.8.1`，地图和 POI 数据受供应方条款约束，不属于本项目自有开源数据。SDK 通过 Maven 获取，不将下载的 JAR 另存入源码；地图水印、审图信息等供应方标识不得遮挡或移除。

- [官方下载与说明](https://developer.amap.com/api/android-sdk/download)
- [服务协议](https://lbs.amap.com/home/terms/)
- [隐私政策](https://lbs.amap.com/pages/privacy/)

2026-10-26用户明确确认已经通过针对本App的技术服务许可／公开使用授权，并授权发布包含内置地图的兼容安装包。此为用户确认，不冒充已从控制台独立核验，也不承诺免费、无限配额或永久许可；未代用户购买、充值或开通付费服务。Android客户端Key绑定原包名及原证书，不随源码、CI配置或更新元数据提交，但按SDK客户端鉴权方式会嵌入获准发布的APK。原证书保留只为兼容覆盖，不代表新正式证书。

## 首页诗词数据

本地精选272条古诗词摘自[chinese-poetry/chinese-poetry](https://github.com/chinese-poetry/chinese-poetry/tree/b8594f81a89752241442f2ce267d6f66f96704ee)，固定来源版本，保留古代作者与作品署名，不包含现代译文或点评。数据库整理采用MIT，Copyright (c) 2016 JackeyGao；[完整条款](LICENSES/chinese-poetry-MIT.txt)另随APK置于`assets/licenses/chinese-poetry-MIT.txt`。来源、筛选与逐条校验见[POETRY_SOURCES.md](docs/POETRY_SOURCES.md)。这不是项目整体许可证。

## 随记内容

《背影》保留朱自清署名、1925年作品信息及[维基文库转录原文链接](https://zh.wikisource.org/wiki/背影)，仅收录公版原作，不含现代译注。未对照初刊影印本。

其他精选的作者、来源、许可与核实程度见[SUIJI_CONTENT.md](docs/SUIJI_CONTENT.md)。除已具备全文许可的正文外，公开资产仅含独立介绍、必要短摘录和原文链接。用户提供的第一篇完整稿仅在本机显式启用的私人Debug预览，不进入Release。Eltrac《一个人的生日》完整文字正文现已按CC BY-SA 4.0收录，保留原文及许可链接，详见下节。

历史12篇项目创作示例以原ID归档，供旧收藏继续阅读，不再列入新精选，不冒充学生投稿。项目整体许可证不变。

## 随记完整版与简体字（本地修正）

Eltrac《一个人的生日》完整文字版按作者站点声明的CC BY-SA 4.0收录，保留署名、原文及许可链接，不含配图；对应文本整理沿用该许可，不变更项目整体许可。《背影》改为简体字形。许可和来源随包保存在assets/licenses/suiji-content.txt，阅读页底部可打开许可。构建时使用官方OpenCC的Apache-2.0字典，App不添加转换库或字体。

本地全文精选补充：平川（文心）《我的母亲：礼物》《我的母亲：最后的时光》原页采用[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/)，本项目当前按非商业阅读使用，保留署名和原文链接，整理版依同一许可提供；商业使用须另获许可。鲁迅《阿长与〈山海经〉》《五猖会》、朱自清《儿女》及丰子恺1927年《给我的孩子们》收录公版原作，不含现代注释或配图。逐篇出处及范围见[内容表](docs/SUIJI_CONTENT.md)，APK内附`assets/licenses/suiji-content.txt`。不改变整个项目的许可证。
