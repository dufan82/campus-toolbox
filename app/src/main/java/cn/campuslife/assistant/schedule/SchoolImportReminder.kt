package cn.campuslife.assistant.schedule

import android.content.Context

/** Reminder only; never starts network work. A missing legacy timestamp is not guessed. */
object SchoolImportReminder {
    private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
    private const val PREFS = "schedule-school-import-history"
    private const val LAST_SUCCESS = "last-success-ms"

    fun isDue(lastSuccess: Long?, now: Long): Boolean =
        lastSuccess != null && lastSuccess > 0 && now >= lastSuccess && now - lastSuccess >= WEEK_MILLIS

    fun needsInitialCheck(lastSuccess: Long?, hasSchedule: Boolean): Boolean = hasSchedule && (lastSuccess == null || lastSuccess <= 0)
    fun needsInitialCheck(context: Context, hasSchedule: Boolean): Boolean =
        needsInitialCheck(latestSuccess(context), hasSchedule)
    fun mayRecordFutureSuccess(blockedOccurrences: Int): Boolean = blockedOccurrences == 0

    fun isDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val timestamp = latestSuccess(context)
        return isDue(timestamp, now)
    }
    private fun latestSuccess(context:Context) = maxOf(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getLong(LAST_SUCCESS,0),
        DailySyncPreferences(context).read().lastSuccess)

    fun recordSuccess(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(LAST_SUCCESS, now).commit()
}
