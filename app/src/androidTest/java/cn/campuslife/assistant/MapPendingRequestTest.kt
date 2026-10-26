package cn.campuslife.assistant

import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.map.CampusMapActivity
import org.junit.Assert.*
import org.junit.Test

class MapPendingRequestTest {
    @Test fun unconsumedSelectionKeepsItsOriginAcrossPrivacyPageRecreation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val prefs=context.getSharedPreferences("campus_map",0)
        val before=prefs.getBoolean("privacy_consent_v1",false)
        try {
            prefs.edit().putBoolean("privacy_consent_v1",false).commit()
            val intent=Intent(context,CampusMapActivity::class.java).putExtra("campus_place_id","dorm-1")
            ActivityScenario.launch<CampusMapActivity>(intent).use { scenario ->
                scenario.recreate()
                scenario.onActivity { activity ->
                    val state=Bundle();instrumentation.callActivityOnSaveInstanceState(activity,state)
                    assertEquals("dorm-1",state.getString("pending_place_id"))
                    assertEquals("USER_SELECTION",state.getString("pending_place_reason"))
                }
            }
        } finally { prefs.edit().putBoolean("privacy_consent_v1",before).commit() }
    }
}
