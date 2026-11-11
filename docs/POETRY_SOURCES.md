# 首页诗词本地精选：来源、许可与验收

核验日期：2026-11-10（Asia/Shanghai）。本文件仅说明诗词数据，不改变项目整体许可证。

## 交付与范围

- 数据：`E:\Desktop\CodexProjects\CampusLifeAssistant\app\src\main\assets\poems.json`，UTF-8、根为数组，共 **272 条**，**54 位作者**，96,235 字节。
- 第三方许可：`E:\Desktop\CodexProjects\CampusLifeAssistant\LICENSES\chinese-poetry-MIT.txt`，逐字节复制上游 LICENSE，含版权声明及完整 MIT 条款。
- 原件、选择白名单、转换对照、校验脚本与记录：`E:\Desktop\CodexProjects\CampusLifeAssistant\verification\reading-20261110\poetry-source`。
- 开工已确认这三个交付文件均不存在；均以独占创建方式新增。保留已有修改；未修改 Kotlin、其他资产、根文档、Manifest/Gradle 或整体许可证，未安装生产依赖、运行 Gradle、提交、推送或发布。

## 1. 已联网核验的主来源

主来源为 [chinese-poetry/chinese-poetry 官方 GitHub 仓库](https://github.com/chinese-poetry/chinese-poetry)。实际读取 GitHub commit/tree API、仓库 README、LICENSE 和下列 JSON；不是凭记忆录入诗句。

- 固定 commit：[`b8594f81a89752241442f2ce267d6f66f96704ee`](https://github.com/chinese-poetry/chinese-poetry/commit/b8594f81a89752241442f2ce267d6f66f96704ee)。
- 上游 commit 时间：`2026-06-17T01:56:08Z`。
- 首次下载时间：`2026-11-10T01:04:15.550690+00:00`。
- 二次在线核验：`2026-11-10T01:12:48.479930+00:00`，11 个实际使用的数据/说明/许可文件均 HTTP 200；重新下载的 SHA256、大小与保存原件一致，Git blob SHA1 也与固定 commit 的 tree 一致。
- [上游根 README](https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/b8594f81a89752241442f2ce267d6f66f96704ee/README.md) 声明该数据库按 JSON 分发并采用 MIT；[上游 LICENSE](https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/b8594f81a89752241442f2ce267d6f66f96704ee/LICENSE) 实际声明 `Copyright (c) 2016 JackeyGao`。
- 上游唐诗目录说明承认数据仍有错误；本选集按白名单择句，未将整个数据集不加检查搬入。仓库名“三百首”不是实际记录数保证：本次唐诗文件有 366 条、宋词文件有 280 条、水墨唐诗文件有 176 条。

### 实际采纳的数据文件与原件 SHA256

| 固定版本下载地址 | 精选条数 | 原件 SHA256 |
| --- | ---: | --- |
| [水墨唐诗/shuimotangshi.json](https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/b8594f81a89752241442f2ce267d6f66f96704ee/%E6%B0%B4%E5%A2%A8%E5%94%90%E8%AF%97/shuimotangshi.json) | 138 | `e175adf044f015cd7283d96a793eedde89a2847f567ecd58cd8d605a7ce69f8a` |
| [全唐诗/唐诗三百首.json](https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/b8594f81a89752241442f2ce267d6f66f96704ee/%E5%85%A8%E5%94%90%E8%AF%97/%E5%94%90%E8%AF%97%E4%B8%89%E7%99%BE%E9%A6%96.json) | 63 | `d77a145f7470da2ed2f79515944563c062ffc43fceda07f195a36ead66b7e5c7` |
| [宋词/宋词三百首.json](https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/b8594f81a89752241442f2ce267d6f66f96704ee/%E5%AE%8B%E8%AF%8D/%E5%AE%8B%E8%AF%8D%E4%B8%89%E7%99%BE%E9%A6%96.json) | 60 | `ca5d74f7fdb9d5a6acc22a7c8b4395228cad4bb8be53df44c19ba83ab6983b20` |
| [宋词/ci.song.1000.json](https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/b8594f81a89752241442f2ce267d6f66f96704ee/%E5%AE%8B%E8%AF%8D/ci.song.1000.json) | 8 | `5f79a2d9011e6d414f04e51176c5d4f4c762fd784dce98b035968367b6f2ef4e` |
| [宋词/ci.song.6000.json](https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/b8594f81a89752241442f2ce267d6f66f96704ee/%E5%AE%8B%E8%AF%8D/ci.song.6000.json) | 2 | `8ff2b21b4e92a435f72da04fcb935a6a3498e839cde5c837ea418871f133c248` |
| [宋词/ci.song.9000.json](https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/b8594f81a89752241442f2ce267d6f66f96704ee/%E5%AE%8B%E8%AF%8D/ci.song.9000.json) | 1 | `bd6a4d90b17ef195f32e38dfa483b0d39d671db3cdd5b462b292aac6916f26a6` |

### README / LICENSE 原件 SHA256

| 固定版本原件 | SHA256 |
| --- | --- |
| [README.md](https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/b8594f81a89752241442f2ce267d6f66f96704ee/README.md) | `2c5dff386efd9eaa08a1b64489538fc96637b9e530e9815a1563e6ee7f880020` |
| [LICENSE](https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/b8594f81a89752241442f2ce267d6f66f96704ee/LICENSE) | `c195319aeaa3ffcbe16aa5d26eec19eae5a42f84337dd2b3dc3c9d5ccbbd6507` |
| [全唐诗/README.md](https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/b8594f81a89752241442f2ce267d6f66f96704ee/%E5%85%A8%E5%94%90%E8%AF%97/README.md) | `336a785b4e9796d090f93e65beeeeaa260a2599e881066cc257c163267dabfd1` |
| [宋词/README.md](https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/b8594f81a89752241442f2ce267d6f66f96704ee/%E5%AE%8B%E8%AF%8D/README.md) | `b65627199627ffc2be08d5ef8e3c4d495de0fb2db2f1a618274e039f96482655` |
| [水墨唐诗/README.md](https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/b8594f81a89752241442f2ce267d6f66f96704ee/%E6%B0%B4%E5%A2%A8%E5%94%90%E8%AF%97/README.md) | `409136554fc7c6f4c5b932374edfd27a0afc4ee51ceb37d0452fdb003a8d2fa7` |

全部 18 个候选/说明原件的 URL、字节数与 SHA256 在 `E:\Desktop\CodexProjects\CampusLifeAssistant\verification\reading-20261110\poetry-source\manifest.json`；原始字节在 `E:\Desktop\CodexProjects\CampusLifeAssistant\verification\reading-20261110\poetry-source\originals`。额外下载的宋词候选分片没有全部进入 App，不把候选数当成精选数。实际采纳 173 个来源记录、166 个作者/题名组合；同一作品可能在不同文件出现，不同词也可能同词牌，这两个数都不等于“独立作品首数”。

## 2. 公版古文、数据 MIT 与工程许可的区别

1. **古代原文**：本选集只取唐宋及唐末五代诗人的古诗词原句，作者均已去世数百年，原文财产权保护期已届满，按公版古文使用；不纳入现代译文、点评、作者介绍或水墨数据中的 `prologue`。署名、修改和作品完整权不能因为“公版”而被忽略，仍保留古代作者及作品信息。[国家版权局刊载《著作权法》第22、23条](https://www.ncac.gov.cn/xxfb/flfg/flfg_532/202103/t20210309_50530.html)说明人身权与财产权保护期的区别。
2. **现代数据整理**：古诗原文公版不等于可以删掉数据仓库的许可。现代整理、具有独创性的选择编排与原作品权利应分开看（同法第13、15条）。本次按仓库 README / LICENSE 对其数据整理采用 MIT，保留 JackeyGao 的版权声明与完整许可；这不是把古诗作者改成 JackeyGao，也不是声称其取得了古诗原文独占权。
3. **工程边界**：`E:\Desktop\CodexProjects\CampusLifeAssistant\LICENSES\chinese-poetry-MIT.txt` 仅用于这批第三方诗词数据，不将 CampusLifeAssistant 的代码、其他素材或整个项目改为 MIT。
4. **分发交接**：发布携带诗词数据的 App/数据包时，应同时提供上述 MIT 声明与完整条款，并保留作者/作品署名。本子任务只准备文件，不改打包逻辑，未核验最终 APK 是否携带许可；由主会话在最终分发环节确认。

## 3. 精选原则与准确性处理

- 先选名家熟悉作品中能独立阅读的诗联/短段，再补充山水四季、时间成长、人生自勉、亲情乡思与友情。没有用格言、网络励志文、现代改写或冷僻残句凑满 300。
- 每条是原记录的一个段落，或相邻段落的连续拼接；不跨作品拼句，不凭记忆补字，不把单条扩成整首/整阕。含标点最长 24 字。
- `author` 与 `work` 只读取同一来源记录中的 `author` 和 `title` / `rhythmic` 并作经核验的繁简转换。词源只有词牌时就保留词牌，不杜撰“咏梅”“元夕”等副题；词牌相同不等于作品相同，必须结合 `source` 定位。组诗题目保留原分首标记及空格，原文件中的通用题名/别名不扩写。
- 逐条查看了最终正文和作者/题目转换对照。对来源中存在明显错字或归属冲突的候选，优先不用，而非猜测修正。例如未采用唐诗文件把《赤壁》归给李商隐的记录、把“松下问童子”归给孙革的重复记录，以及宋词文件中欧阳修名下与柳永词重叠的记录。
- 《山行》只取“停车坐爱枫林晚”一联，不取水墨原件中明显异常的首句；《送灵澈》只取无异常的一联。保留原文版本字样，不把不同版本掺成新句。
- 输出正文不加引号、书名号、朝代标签或解说；标点沿用来源，仅去掉原件在问号/叹号后重复的句号（如 `？。` → `？`）。短诗可分别选两联，但每条不等于整首全文。

## 4. 繁简转换与复核

使用可追溯工具 [opencc-python-reimplemented 0.1.7](https://pypi.org/pypi/opencc-python-reimplemented/0.1.7/json)（[源码及配置说明](https://github.com/yichen0831/opencc-python)），配置 `t2s`。这是 OpenCC 的 Python 重实现，不冒称官方 C++ 二进制。

- wheel 下载地址：[固定包](https://files.pythonhosted.org/packages/30/6b/055b7806f320cc8f2cdf23c5f70221c0dc1683fca9ffaf76dfc2ad4b91b6/opencc_python_reimplemented-0.1.7-py2.py3-none-any.whl)。
- wheel SHA256：`41b3b92943c7bed291f448e9c7fad4b577c8c2eae30fcfe5a74edf8818493aa6`，与 PyPI 元数据核对一致。
- 仅在 `E:\Desktop\CodexProjects\CampusLifeAssistant\verification\reading-20261110\poetry-source\conversion-tool` 解包用于离线校验；未 pip 安装到环境、未加入 Android/生产依赖、未打入 App。工具自身为 Apache-2.0，wheel 内保留其原始 LICENSE；不是诗词数据 MIT 的替代许可证。
- 每次校验先验证 wheel SHA256，再逐文件比对解包内容，才调用转换器。正文、作者、作品题名使用相同规则。
- 完整作者/题名原文与输出对照在 `E:\Desktop\CodexProjects\CampusLifeAssistant\verification\reading-20261110\poetry-source\conversion-review.tsv`；每条原始正文及段落坐标在 `E:\Desktop\CodexProjects\CampusLifeAssistant\verification\reading-20261110\poetry-source\selection-audit.json`。已复核王維→王维、張九齡→张九龄、李頎→李颀、王之渙→王之涣、賈島→贾岛等，没有自行推断作者名。
- 转换器未覆盖的异体/专名（如“隣”、僧名“濬”）保留来源原字，不猜字替换；“谢朓”不误改为“谢眺”。《杜少府之任蜀州》等题名按源记录保留，不凭常见书名补字。

## 5. Schema 与稳定定位

根为 JSON 数组；每条恰有五个非空字符串字段：`id`、`text`、`author`、`work`、`source`。

- `source` = 固定完整 commit 的 raw JSON URL + `#/数组下标`（从 0 开始的 JSON Pointer）。下载时片段不发送给服务器；拿到数组后按指针定位记录，不依赖会改变的 master 分支。
- 选择了哪些段落、来源自带 ID（若有）另存审计文件；没有把审计字段塞进 App schema。
- 稳定 ID 算法：`poem-` + `SHA256(UTF8(author + "\n" + work + "\n" + text))` 的前 24 个小写十六进制字符。没有末尾换行；不包含数组顺序、时间戳、来源 URL 或随机数。
- 同一正文/作者/题名在重新排列或更换数据镜像后 ID 不变；正文/题名真正修正时有意产生新 ID。不把 ID 当作上游作品的永久编号。

## 6. 本次实际校验

`E:\Desktop\CodexProjects\CampusLifeAssistant\verification\reading-20261110\poetry-source\validation.json` 和 `E:\Desktop\CodexProjects\CampusLifeAssistant\verification\reading-20261110\poetry-source\validation-output.txt` 为本次输出；以下均已实际运行通过，不是历史测试：

| 检查 | 结果 |
| --- | --- |
| JSON 严格解析、重复 key、根数组、精确五字段、非空字符串 | 通过，272 条 |
| 作者/作品/正文与白名单来源逐条重建一致 | 通过 |
| ID 公式、格式、唯一性、重排不变性 | 通过，0 冲突 |
| 正文精确去重、去标点/空白后的 NFKC 去重 | 均 0 重复 |
| 正文无引号装饰/HTML/控制符/替换字符、结尾标点 | 通过 |
| 保存原件 SHA256 / 大小 | 18 个全部通过 |
| 完整 MIT 与上游原件逐字节相等 | 通过 |
| 单条包含整首/整阕来源全文 | 0 条 |
| 实际采纳数据及 README/许可在线复查 | 11 个 HTTP 200，SHA256 / Git blob 全部一致 |

- 含标点：**8–24 字**，均值 **14.27**；不含标点：**7–21 字**，均值 **12.15**。
- 含标点字数分布：`{"8":3,"11":4,"12":114,"13":3,"14":7,"15":7,"16":117,"17":4,"18":2,"19":4,"20":2,"21":2,"22":1,"24":2}`。
- 不含标点字数分布：`{"7":3,"9":4,"10":115,"11":2,"12":12,"13":13,"14":110,"15":2,"16":5,"17":2,"18":1,"19":1,"20":1,"21":1}`。
- 数据 SHA256：`d86b615251ff71d96031ed0e28be71e177115a51758e0fd3bb60dec3cbe9e902`。
- 许可 SHA256：`c195319aeaa3ffcbe16aa5d26eec19eae5a42f84337dd2b3dc3c9d5ccbbd6507`。

复查命令（Python 3；不运行 Gradle、不需要生产依赖）：

```powershell
python "E:\Desktop\CodexProjects\CampusLifeAssistant\verification\reading-20261110\poetry-source\verify_poems.py"
python "E:\Desktop\CodexProjects\CampusLifeAssistant\verification\reading-20261110\poetry-source\verify_online_sources.py"
```

`E:\Desktop\CodexProjects\CampusLifeAssistant\verification\reading-20261110\poetry-source\curate_poems.py` 无参数只预览；`--write` 仅在数据文件不存在时独占创建，绝不覆盖已有文件。撤回本子任务时，仅移走本页列出的三个新增交付文件并保留验证目录；不要回滚主会话或其他 Agent 的文件。UI 接入后的撤回需由主会话同步处理引用。

## 7. 未包含的验收

本子任务未修改/验收首页 UI、随机切换、故事、投稿或最终 APK 许可展示，也没有构建/运行 App；这些由主会话验收。本次来源与数据格式校验不能替代这些功能测试。

## 主会话打包复核（2026-11-10）

本地预览APK内已实际检查272条诗词及`assets/licenses/chinese-poetry-MIT.txt`。许可字节与本目录上游原件SHA一致；未改变工程整体许可。实际APK摘要、大小、冷进程/复制换句测试和截图见`verification/reading-20261110/delivery-verification.json`及本轮交付说明。
