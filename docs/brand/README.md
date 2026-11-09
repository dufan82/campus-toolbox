# 校伴品牌资源

校伴的轻量矢量母版及 Android 资源。已在本地应用接入；保持原包名、版本、签名和数据，本轮没有提交或发布。完整集成与验收见 `../BRAND_UPGRADE.md`。

## 参考与造型

参考依据为主任务可见的内嵌参考图及本轮明确提供的几何说明。磁盘文件 `E:\Desktop\Snipaste_2026-11-09_17-04-08.png` 未取得，未直接读取该文件，也没有裁切或重绘位图。按说明重建为可编辑路径：青绿左上起点 → 向右 → 向下 → 向右 → 向下 → 底部向左短收笔，橙色节点位于右下转角。没有书本、学士帽、波浪或图标文字。

统一母版为 108×108：路径 `M32,37H54V54H76V71H65`，线宽 5.5，圆角连接与圆头；节点 `(32,37)`、`(76,71)`，半径 5。放在 84×84 雾白方块时，路径中心线约 34.2×26.4、线宽约 4.28，与提供的约 36×28、约 4px 描述对应。

## 颜色与字符串公开接口

| Android ID | 值 |
| --- | --- |
| `R.color.brand_navy` | `#172B49` |
| `R.color.brand_teal` | `#35B6A0` |
| `R.color.brand_orange` | `#F2A65A` |
| `R.color.brand_mist` | `#F5F7FA` |
| `R.string.app_name` | 校伴 |
| `R.string.brand_english` | CAMPUS COMPANION |
| `R.string.brand_tagline` | 让校园生活更简单 |
| `R.string.brand_positioning` | 校园效率工具箱 |

应用保留原 `Ui.blue` API，值统一为深海蓝；`Ui.ink` 同色。界面使用少量青绿、暖橙浅色底，按钮正文保持深海蓝/白色的可读对比。

## Android 文件与接入边界

以下路径均相对于工程根目录 `E:\Desktop\CodexProjects\CampusLifeAssistant`：

| 文件 | 用途 |
| --- | --- |
| `app/src/main/res/drawable/ic_app.xml` | 唯一替换的旧资源；圆角雾白底的无文字兼容图标 |
| `app/src/main/res/drawable/logo_symbol.xml` | 透明符号母版，108dp |
| `app/src/main/res/drawable/logo_primary.xml` | Android 组合标志的**符号部分**；中文名、英文和标语另用真实 TextView 组合，不烘焙字形 |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | 108dp 透明 adaptive 前景 |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | 108dp 单色 alpha 轮廓，供 API 33 主题图标染色 |
| `app/src/main/res/drawable/splash_logo.xml` | 288dp 透明系统启动符号，无文字、额外背景或等待逻辑 |
| `app/src/main/res/drawable/ic_notification.xml` | 24dp 通知小图标，单白色 alpha，透明底，图形横向占 x=2..22 |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | API 26 adaptive：雾白背景 + 透明彩色前景 |
| `app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml` | 同上，增加 monochrome 层 |
| `app/src/main/res/values/brand_colors.xml` | 四个品牌颜色 ID |
| `app/src/main/res/values/brand_strings.xml` | 四个名称/文案 ID，无字体包 |

Manifest 已接入 `@string/app_name`、`@mipmap/ic_launcher` ，通知已使用专用白色 alpha 图标；roundIcon 复用同一 mipmap。BrandLaunch 主题使用 `@drawable/splash_logo` 和 `@color/brand_mist`，仅平台启动页，不增加 SplashActivity、强制等待或文字。原生页头/关于页使用系统字体的 TextView 和上述字符串；不把 SVG 作为 Android 字标图片。

### 安全区

彩色前景及单色图标均为 108dp 画布，中心 `(54,54)`。包含线宽、圆头及节点后的包围盒为 `[27,32]..[81,76]`，即 54×44dp。最远节点外缘距中心 `sqrt(22²+17²)+5 = 32.802878dp`，整图位于中心直径 66dp 的圆内；不是仅把中心线放进 66dp 方形。前景不预烘焙背景和遮罩，系统可自由裁成圆形、圆角方形或其他 adaptive 形状。

288dp 无背景系统启动图标使用相同 108 单位 viewport，图形外缘半径约 87.47dp，小于其中心 192dp 安全圆半径 96dp。已完成全量构建及 API36 原生启动录屏；不同厂商桌面仍需手机观察。

## 可编辑 SVG（本目录）

- `logo_symbol.svg`：透明符号。
- `logo_primary.svg`：横向组合标志；符号 + 真实中文、英文与标语文本。
- `logo_wordmark.svg`：纯文字标志，真实 `<text>`，系统 sans-serif 回退，不带字形路径或嵌入字体。
- `launcher.svg`：带圆角雾白底的应用图标预览；adaptive 使用分层 XML。
- `splash_logo.svg`：透明系统启动符号。
- `launcher_monochrome.svg`、`notification.svg`：透明单白色轮廓，浅底预览时需另加深色衬底。
- `palette.svg`：四个色块、色号及名称，可直接编辑。

SVG 不含图片、外部资源链接或字体下载。字标在不同系统的具体字形可能略有变化，中文内容保持真实、可编辑；不使用未知字体转曲。

## 验证与恢复

不运行 Gradle，避免和共享工程的主任务争用构建。独立检查命令：

```powershell
python E:\Desktop\CodexProjects\CampusLifeAssistant\docs\brand\verify_resources.py --aapt2 E:\skill\android-sdk\build-tools\36.0.0\aapt2.exe
```

脚本只使用 Python 标准库和已有 aapt2，检查 XML/SVG、资源 ID、共用几何、66dp 安全圆、live text、通知单色及 adaptive 版本，并在系统临时目录单独编译这 11 个 XML；不链接或构建 App，不安装设备。`baseline-verification.log` 保留改前缺资源的失败证据和旧图标独立编译结果；`validation.log` 保留本轮最终命令、原始输出及退出状态。完整 App 链接、主题/Manifest 接入和设备表现归主任务验证，本记录不替代它们。

本轮实际结果（2026-11-09，Asia/Shanghai）：9 项检查通过、0 失败；11 个 Android XML 使用已有 aapt2 36.0.0 独立编译成功，退出 0，临时编译包 13,794 字节。已用本机 Edge 将实际 SVG 渲染为临时预览并打开检查：84px 图标、圆形 adaptive 遮罩、单色图标、通知轮廓、真实中文组合标志和四个色块均可见。临时 PNG 仅是 SVG 验收预览，不是参考图编辑，也不作为交付源素材。Edge 退出 0，其旧浏览器资料导入诊断保留在原始日志中。

已存在的完整改前备份：`verification/brand-xiaoban-20261109/before.zip`。`baseline.json` 保存该压缩包及旧 `ic_app.xml` 的 SHA256；压缩包内旧图标与改前工作区已逐字节核对一致。`asset-manifest.json` 列出本轮资源/文档及 SHA256，`changes.patch` 提供 11 个 Android XML 和 8 个 SVG 相对于改前备份的精确文本差异；主任务在共享工程中的其他编辑不包含在该补丁内。

需要回退时：先由主任务确认没有后续编辑，再仅从上述 ZIP 的 `app/src/main/res/drawable/ic_app.xml` 条目恢复该文件；其他 10 个本轮新 Android 资源按上表逐项移除，品牌 SVG/说明可整体保留作历史。不要整包覆盖共享工程，不恢复主任务的 Manifest、主题、Ui 或业务文件。主任务已接入新 ID 时应同时回退其引用，避免悬空资源。
