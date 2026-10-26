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
