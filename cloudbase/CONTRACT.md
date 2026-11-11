# 随记 API contract v1（本地扩展，2026-11-11）

本文件描述**本地新版源码/Mock**。真实云端仍为旧版，本写集未调用、修改或部署云服务。CloudBase任务不修改Android，其适配/最终验证由主会话负责；原action、ID、集合和匿名投稿请求保持兼容。新增管理接口不能据此视为线上可用。

## 传输与鉴权（保持）

POST固定API，JSON `{action,payload}`；响应 `{ok:true,data}` / `{ok:false,error:{code,message}}`，最大256KiB，Cache-Control:no-store。
匿名登录由官方POST /auth/v1/signin/anonymously签发，随机UUID作x-device-id，不是硬件标识；Token只保留内存。
adminLogin代理官方signin，再验证user/me可信sub及服务端UID白名单，返回{accessToken,expiresIn}；每次管理请求重新校验。客户端UID/角色不是权限依据。
Mock使用本机独立认证和同一业务服务，不调用官方认证。
分类：LIFE,GROWTH,WARMTH,TWIST,OTHER；consentVersion=2026-11-10-v1。

## 来源/授权字段

| 字段 | 契约 |
|---|---|
| sourceType | douyin_post,douyin_image,douyin_comment,other_platform,user_submission；旧submission读取归一为user_submission，不迁移旧数据 |
| sourceUrl | 完整公网HTTPS原文链接，最多2048码点；拒绝凭据、非443端口、本机/IP/保留后缀域名。不抓取/解析分享口令；离线只核验语法，原文存在性由管理员核对。用户投稿允许空链接 |
| publisherName | 作品发布者，最多80码点，与正文/评论作者authorName分离（管理端authorName最多80，普通投稿仍30） |
| sourceWorkTitle | 来源作品标题，最多200码点 |
| commentLocator | 公开评论定位，最多500码点，不填写审稿/身份资料 |
| rightsStatus | unverified,excerpt_only,authorized,public_domain,user_authorized,revoked |
| rightsNote | 授权凭据备注，最多2000码点，**仅管理员详情**，不写入公开文档/DTO |
| editorialRank | 整数[-1000000000,1000000000]，默认0，越小越靠前 |
| excerpt | 必要短摘录，最多80个Unicode码点，默认空；公开投影再次截断上限 |
| hidden | boolean，默认false；隐藏保留管理记录，但列表/详情均不公开 |

管理字段只接受纯文本。管理员精选创建/编辑的title为2–120码点、content为0–20000、独立summary为0–240；普通学生submitStory仍为title 2–30/content 100–5000，不随管理端放宽。外部来源必须有链接，未获全文授权时须提供独立介绍或必要短摘录，不能自动从完整草稿截取。

只有authorized/public_domain/user_authorized可公开全文。其余状态详情content=""；列表content**永远为空**，只返回独立summary、必要excerpt和来源信息。未授权完整草稿只存story_submissions，公开集合也不保留全文。编辑已公开作品在同一事务按当前授权重算公开副本，撤销授权后全文同时清空。更改正文或来源/作者、但未显式重新声明rightsStatus时，授权降为unverified。

旧sourceType=submission/user_submission或带原投稿同意版本的正常投稿，缺授权字段兼容user_authorized。新精选recordType=editorial，缺授权始终unverified，即使sourceType选择user_submission也不默认全文。

## 公开接口（沿用action）

**getPublishedStories** `{cursor?,limit?:20,category?}` → `{items:[PublicStory],nextCursor:string|null}`。limit为1–50。

**getStoryDetail** `{id}` → PublicStory。只有允许全文的状态才返回非空content；隐藏/撤下/未发布返回HTTP404 STORY_NOT_FOUND。

PublicStory固定白名单：
```text
id,title,category,authorName,publishedAt,summary,content,readingMinutes,
sourceType,sourceUrl,publisherName,sourceWorkTitle,commentLocator,
rightsStatus,editorialRank,excerpt,hidden:false
```
绝不返回UID、fingerprint、同意/审稿信息、reviewerNote、rightsNote、内部recordType/revision或审计。未授权readingMinutes只依据可公开介绍/摘录，不依据完整草稿长度。

### 稳定排序/分页

- editorialRank升序，初次publishedAt降序，id降序；同毫秒以ID稳定决胜。旧rank缺省0。
- 复用原sortKey降序索引：rank0保持旧ISO|id；负rank编码“~<1000000000-rank的10位数>|ISO|id”，正rank编码“!<同式>|ISO|id”。管理列表仍按创建时间，不受公开rank影响。
- 公开游标v2绑定分类、last key、story_stats/counts.publicRevision（缺省0）。公开新增/编辑/隐藏/撤下在事务递增版本；翻页间公开集变化返回400 INVALID_CURSOR，客户端从第一页刷新，不静默跨排序漏项/重复。无变更时游标稳定。旧v1公开游标只在revision=0兼容，管理游标仍v1。
- 隐藏事务删除公开副本，取消隐藏须重新确认/判权。防御过滤遗留人工写入的hidden/withdrawn行，并补足分页。
- **只排序云作品**，不发送本地目录/排序指令；前三篇固定顺序归Android本地管理，不自动覆盖本地。

## 投稿接口（保持）

submitStory `{submissionId:UUID,title,category,content,authorName?,consent:true,consentVersion}` → `{submissionId,id,status,duplicate}`。标题2–30、正文100–5000、署名最多30码点不变。全局ID、同owner+原始输入指纹精确重放幂等；内容/owner冲突409 SUBMISSION_CONFLICT。服务端打同意/创建时间，原每分钟/每日/全站事务限额不变。
普通投稿附加sourceType、rightsStatus、hidden、rank、recordType、审稿/身份等字段一律400，不能伪造精选/授权。

## 管理接口

全部需服务端逐次验证的管理员Bearer；无登录401、非管理员403。

| action | payload | data/状态 |
|---|---|---|
| adminLogin | {username,password} | {accessToken,expiresIn} |
| getPendingStories | {status:"all"\|"pending"\|"approved"\|"rejected",cursor?,limit?:20} | 原items/nextCursor/counts；条目新增recordType/withdrawn（旧行可缺省）及hidden/editorialRank/rightsStatus，不带正文/rightsNote |
| getSubmissionDetail | {id} | 原管理详情+summary、所有来源/授权字段、recordType、revision、withdrawn（旧行可缺省）；完整草稿仅管理员 |
| **createEditorialStory** | {editorialId:UUID,title,category,...管理字段} | {id,status,duplicate}；创建pending精选草稿，不自动公开。默认other_platform/unverified/rank0/hiddenfalse。editorialId复用submissionId及submission-*管理ID；同owner精确重试幂等，冲突409 |
| **updateManagedStory** | {id,revision:整数,patch:{管理字段子集},confirmPublic?:boolean} | {id,revision,published,hidden}；patch不可空；revision须匹配（旧记录0），修改+1，冲突409 REVISION_CONFLICT |
| approveStory | {id,revision?:整数} | {id,status:"approved",publishedStoryId}；pending→approved，原事务发布/计数/审计。新版网页绑定revision，旧id-only兼容；hidden稿可通过但不露出 |
| rejectStory | {id,note?:string<=500} | {id,status:"rejected"}，仅pending，重复拒绝幂等 |
| deletePublishedStory | {id,note?:string<=500}，管理ID | {id,withdrawn:true}，删除公开副本、保留原稿/审计，approved状态保留，重复撤下幂等，不能重发 |

管理字段白名单：
`title,category,content,summary,authorName,sourceType,sourceUrl,publisherName,sourceWorkTitle,commentLocator,rightsStatus,rightsNote,editorialRank,excerpt,hidden`。
不可客户端设置owner/reviewer/status/published等字段。

updateManagedStory可编辑原投稿或精选；已拒绝/撤下记录不可编辑/复活（409 INVALID_STATE）。编辑后仍为已通过且未隐藏时必须confirmPublic=true，否则409 PUBLIC_CONFIRMATION_REQUIRED；隐藏API无需确认，网页仍二次提示。编辑/隐藏恢复不改变初次publishedAt。
网页：新增→存草稿→核对详情→独立确认发布；公开编辑→确认授权影响→二次保存。未保存修改不能发布。旧云不支持新API时不自动转投稿/换服务/重试。

## 持久化与边界

继续使用story_submissions,published_stories,story_rate_limits,story_audit_log,story_stats五集合。精选草稿/投稿共用S仓库和pending计数；approved含隐藏/撤下。公共revision仅在原counts文档新增字段，不需迁移。
审计沿用submit/approve/reject/withdraw，增加editorial-create及每版本唯一edit-N，保留操作者与时间，不覆盖历史编辑事件。
状态、公开副本、计数、revision、审计均原repository事务完成；直连权限规则不变。FileRepository Mock和CloudBase共用业务服务。不等于验证了真实SDK/索引/部署。

Android由主会话适配/验证，不以本CloudBase离线测试代报设备结果。保留旧API及空配置本地草稿约定；本写集不改变来源隔离缓存、404清理及离线阅读，不实现主动擦除断网设备的历史缓存全文。
