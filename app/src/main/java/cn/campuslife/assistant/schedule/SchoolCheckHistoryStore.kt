package cn.campuslife.assistant.schedule
import android.content.Context
/** Independent UI history: never advances the daily job's deduplication day or schedule backup. */
class SchoolCheckHistoryStore(context:Context) {
 private val prefs=context.applicationContext.getSharedPreferences("school-manual-check-v1",Context.MODE_PRIVATE)
 fun read():SchoolCheckRecord? {val at=prefs.getLong("at",0);return if(at==0L)null else SchoolCheckRecord(at,prefs.getString("result","").orEmpty(),prefs.getString("details","").orEmpty(),SchoolCheckOutcome.decode(prefs.getString("outcome","").orEmpty()))}
 fun write(record:SchoolCheckRecord){check(prefs.edit().putLong("at",record.at).putString("result",record.result).putString("details",SchoolCheckHistory.boundedDetails(record.details)).putString("outcome",record.outcome?.encode().orEmpty()).commit())}
 /** Worker-only: keep the original failure and never start another UI job or school request. */
 fun <T> recordingFailure(message:String,action:()->T):T = recordingFailure({message},action)
 fun <T> recordingFailure(message:(Exception)->String,action:()->T):T = try {action()} catch(error:Exception) {
  runCatching {write(SchoolCheckRecord(System.currentTimeMillis(),message(error),"",SchoolCheckOutcome.failed(untouched=true)))}
  throw error
 }
}
