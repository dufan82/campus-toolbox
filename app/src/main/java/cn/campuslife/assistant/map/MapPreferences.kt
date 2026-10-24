package cn.campuslife.assistant.map

import android.content.Context

/** A separate choice: photo/profile storage is never passed to the map SDK. */
class MapPreferences(context: Context) {
    private val preferences=context.getSharedPreferences("campus_map",Context.MODE_PRIVATE)
    fun hasConsent(): Boolean = preferences.getBoolean("privacy_consent_v1",false)
    fun setConsent(agreed: Boolean): Boolean = preferences.edit().putBoolean("privacy_consent_v1",agreed).commit()
}
