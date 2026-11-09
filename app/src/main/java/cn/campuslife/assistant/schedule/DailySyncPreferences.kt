package cn.campuslife.assistant.schedule

import android.content.Context

class DailySyncPreferences(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("school-daily-sync-v1",Context.MODE_PRIVATE)
    fun read()=DailySyncState(prefs.getBoolean("enabled",false),prefs.getLong("generation",0),prefs.getLong("attempt",0),
        prefs.getLong("success",0),prefs.getString("day","").orEmpty(),
        runCatching {SyncResult.valueOf(prefs.getString("result",null)?:"READY")}.getOrDefault(SyncResult.READY),prefs.getString("summary","").orEmpty())
    fun write(state:DailySyncState) {
        check(prefs.edit().putBoolean("enabled",state.enabled).putLong("generation",state.generation).putLong("attempt",state.lastAttempt)
            .putLong("success",state.lastSuccess).putString("day",state.lastAttemptDay).putString("result",state.result.name)
            .putString("summary",state.summary).commit()) {"同步设置未保存"}
    }
}
