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

## 构建依赖

AndroidX、Kotlin、JUnit 等依赖由 Gradle 按各自坐标获取，版本见构建文件，受各自许可证约束。本仓库不打包 Maven 缓存或重新授权这些依赖；对外发行应用时仍需核对随 APK 分发的依赖许可与通知。
