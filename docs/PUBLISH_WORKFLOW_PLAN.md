# 发布优化执行范围

1. scripts/Publish-Stable.ps1统一Check/Prepare/Publish入口。BuildFingerprint与ValidationFingerprint分离；相同输入/同签名APK复用收据，输入变化重新验证。
2. scripts/StableReleaseCore.ps1负责三个附件身份、状态机、不可覆盖/远端重查、双源公开屏障、JSON阶段/计时与互斥锁。
3. scripts/StableReleaseTransport.ps1官方GitHub/CNB适配器，保留旧1.7.4脚本。主流程先创建草稿，可并行各渠道上传，再核对两边摘要，最后公开。
4. 先补离线失败测试，再实现与运行故障恢复、无权限、冲突、部分公开、篡改等；计时区分真实本地和模拟传输。保留现有签名/准备/凭据测试。
5. AGENTS精简、STATUS顶部短摘要，原文归档；日常开发不触发发布。无新增正式版本/真实上传/公开/Git提交。
