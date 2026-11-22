package cn.campuslife.assistant.schedule

import android.content.Context

class DailySyncPreferences(context:Context) {
    companion object { internal const val NAME="school-daily-sync-v1" }
    private val prefs=context.applicationContext.getSharedPreferences(NAME,Context.MODE_PRIVATE)
    fun read()=DailySyncState(prefs.getBoolean("enabled",false),prefs.getLong("generation",0),prefs.getLong("attempt",0),
        prefs.getLong("success",0),prefs.getString("day","").orEmpty(),
        runCatching {SyncResult.valueOf(prefs.getString("result",null)?:"READY")}.getOrDefault(SyncResult.READY),prefs.getString("summary","").orEmpty(),prefs.getString("details","").orEmpty(),SchoolCheckOutcome.decode(prefs.getString("outcome","").orEmpty()),prefs.getString("changeSummary","").orEmpty(),prefs.getInt("attemptsToday",0))
    fun write(state:DailySyncState) {
        check(prefs.edit().putBoolean("enabled",state.enabled).putLong("generation",state.generation).putLong("attempt",state.lastAttempt)
             .putLong("success",state.lastSuccess).putInt("attemptsToday",state.attemptsToday).putString("day",state.lastAttemptDay).putString("result",state.result.name)
            .putString("summary",state.summary).putString("details",SchoolCheckHistory.boundedDetails(state.details)).putString("outcome",state.outcome?.encode().orEmpty()).putString("changeSummary",SchoolCheckHistory.boundedDetails(state.changeSummary)).commit()) {"同步设置未保存"}
    }
}
