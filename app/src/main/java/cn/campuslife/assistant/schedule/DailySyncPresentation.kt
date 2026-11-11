package cn.campuslife.assistant.schedule

/** Prefer the actual state over the old, sometimes empty or stale summary string. */
object DailySyncPresentation {
    fun summary(state: DailySyncState): String {
        if (!state.enabled) return "自动检查已关闭"
        return when (state.result) {
            SyncResult.CHECKING -> "正在检查…"
            SyncResult.NEEDS_LOGIN -> "需要登录学校平台"
            SyncResult.NO_CHANGE -> "课表没有变化"
            SyncResult.UPDATED -> "课表已更新"
            SyncResult.CONFLICT -> "部分课程已保留，请手动检查"
            SyncResult.NETWORK_ERROR -> "检查失败，请稍后重试"
            SyncResult.INVALID_DATA -> "课表未更新，请重新检查"
            SyncResult.INTERRUPTED -> "检查已中断"
            SyncResult.SKIPPED -> "本次未检查"
            SyncResult.READY -> if (state.lastAttempt > state.lastSuccess) "上次检查未完成" else if (state.lastSuccess > 0) "等待下次检查" else "等待首次检查"
        }
    }
}
