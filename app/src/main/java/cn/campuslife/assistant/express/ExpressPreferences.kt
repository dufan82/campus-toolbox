package cn.campuslife.assistant.express

import android.content.Context

/** Private device storage only. No account, clipboard, SMS, network, or analytics access. */
class ExpressPreferences(context: Context) : ExpressSettings {
    private val preferences = context.applicationContext.getSharedPreferences("express_entries_v1", Context.MODE_PRIVATE)
    override fun read(key: String): String? = try { preferences.getString(key, null) } catch (_: ClassCastException) { null }
    override fun write(key: String, value: String?) {
        val edit = preferences.edit()
        if (value == null) edit.remove(key) else edit.putString(key, value)
        check(edit.commit()) { "无法保存入口设置，请稍后重试。" }
    }
}
