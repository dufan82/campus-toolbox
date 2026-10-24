# 校园工具箱

把校园里常用的几件小事放在一起：给活动照片加水印、打开快递页面、找校内地点。

目前是 Android 应用，安装后的名字叫“校园生活助手”。支持 Android 8.0 及以上，当前版本 **1.5.1**。

## 能做什么

### 活动照片水印

从相册选照片，也可以拍照。填写学院、班级、姓名、座号、学号和拍摄时间，选哪些就印哪些，顺序也能自己排。

默认在右下角加白字和半透明底色，可以换位置或直接拖动。“只显示内容”关掉后，会带上“姓名：”这类字段名。保存时可选 JPEG 或 PNG，文件名默认按班级、姓名、座号组合，也能自己改。

资料和编辑设置会记住，不用注册。照片在本机处理，保存的是新文件，不覆盖原图；学号默认不显示，也不放进文件名。

### 快递快捷入口

首页直接放了三个按钮：拼多多包裹、拼多多身份码、抖音订单。

它们只负责打开对应 App，不读取账号、短信或身份码。第三方 App 改版后可能影响跳转，设置里可以调整链接。

### 平潭校区地图

按福建信息职业技术学院平潭校区整理。可以搜楼名、旧简称或宿舍楼号，例如“生2”“二号楼”；点地图上的圆点也能选地点。

搜索结果多时在输入框下选一个，底部只留当前地点的导航按钮。步行导航会打开官方高德 App。“我的位置”要点了才申请定位权限，不记录行走轨迹。

目前整理了35处坐标，包含2个自定义地点；不用的9项默认隐藏。没有可靠位置的地点只显示待核对，不会随便放个点。自定义地点可以新增、改名、移动或删除，但改动只保存在本机，不会自动同步给别人。

## 安装和编译

目前没有在Releases发布安装包。Actions只保留不带地图Key的临时测试构建。自行编译需要 Android Studio，或以下命令行环境：

- JDK 17以上，本机使用JDK 21。
- Android SDK Platform 36、Build Tools 35.0.0。
- 在本机 `local.properties` 设置 `sdk.dir`，或配置 `ANDROID_HOME`。

Windows：

```powershell
.\gradlew.bat :watermark-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Linux / macOS：

```sh
./gradlew :watermark-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK在 `app/build/outputs/apk/debug/app-debug.apk`。第一次构建会下载依赖；网络较慢时可加 `-PuseChinaMirrors=true`。更多说明见[构建文档](docs/BUILD.md)。

不同电脑的调试签名通常不同，自己编译的包不一定能覆盖已有安装。不要为了试装先卸载旧版，否则本机资料和草稿会丢。

### 地图配置

水印和快递不需要地图 Key。要用地图，请在自己的高德账号申请 Android Key，绑定包名 `cn.campuslife.assistant` 和实际签名SHA-1，再写入本机配置：

```properties
AMAP_ANDROID_KEY=你的AndroidKey
```

也可以用同名环境变量。不要提交Key或 `local.properties`；Key会进入编译后的APK，不适合当作服务端秘密。

校园导览原图暂未放进仓库，公开使用许可还没确认。如有使用权限，可放到 `app/src/main/assets/pingtan-reference.png` 后编译；未放图时“原图”页会提示读取失败，其他功能不受影响。原图尺寸测试在有这份素材时执行，缺图时会明确跳过。

## 还有哪些没做好

- 真实手机的地图、定位误差和不同厂商兼容，还需要继续试用。当前地图SDK只有ARMv7/ARM64，x86模拟器不能验证真实底图。
- 部分地点没有坐标，也没有室内导航或完整校内步行路网。
- 高德服务的长期配额和公开使用许可还要确认，目前按少量测试使用。

照片、校园资料默认不上传；地图底图和开启后的定位由高德处理。第三方材料及许可见[说明](THIRD_PARTY_NOTICES.md)。项目目前没有添加整体开源许可证。

## 开发记录

项目使用 Kotlin、Android Views 和 Canvas，分为 `app`、`watermark-core` 两个模块。没有后端或账号系统。

[更新记录](CHANGELOG.md) · [当前进度](STATUS.md) · [待办](TASKS.md) · [地图说明](docs/MAP.md) · [CI](docs/CI.md)

接手开发先看 [AGENTS.md](AGENTS.md)。安装包、私有配置、签名和本机测试日志不放进仓库。
