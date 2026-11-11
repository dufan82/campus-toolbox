package cn.campuslife.assistant.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.os.Bundle
import android.view.animation.AnimationUtils
import android.view.animation.Transformation
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Intents/resources, intercepted only at the launch boundary: no app, URL,
 * school login, update request, storage write or network is started by these tests. */
@RunWith(AndroidJUnit4::class)
class MainNavigationDeviceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()

    private class RecordingActivity(context: Context) : Activity() {
        val events=mutableListOf<String>()
        var launched: Intent?=null
        var options: Bundle?=null
        var rejectLaunch=false
        init { attachBaseContext(context) }
        override fun startActivity(intent: Intent) {
            fail("Navigation must supply platform custom options, including for REORDER_TO_FRONT")
        }
        override fun startActivity(intent: Intent,options: Bundle?) {
            events.add("startWithOptions")
            if(rejectLaunch) throw ActivityNotFoundException("NAV-001 test launch rejected")
            launched=intent;this.options=options
        }
        @Suppress("DEPRECATION","OVERRIDE_DEPRECATION")
        override fun overridePendingTransition(enterAnim: Int,exitAnim: Int) {
            fail("Navigation must attach animations to the launch options")
        }
        override fun finish() { fail("Navigation must not finish its source Activity") }
        override fun recreate() { fail("Navigation must not recreate its source Activity") }
    }

    private data class Route(val from: String,val to: String,val target: String,val flags: Int,val enter: Int,val exit: Int)
    private fun routes()=listOf(
        Route("home","schedule","cn.campuslife.assistant.schedule.ScheduleActivity",0x00020000,R.anim.main_nav_enter_right,R.anim.main_nav_exit_left),
        Route("home","settings","cn.campuslife.assistant.ui.SettingsActivity",0x00020000,R.anim.main_nav_enter_right,R.anim.main_nav_exit_left),
        Route("schedule","settings","cn.campuslife.assistant.ui.SettingsActivity",0x00020000,R.anim.main_nav_enter_right,R.anim.main_nav_exit_left),
        Route("settings","schedule","cn.campuslife.assistant.schedule.ScheduleActivity",0x00020000,R.anim.main_nav_enter_left,R.anim.main_nav_exit_right),
        Route("settings","home","cn.campuslife.assistant.ui.MainActivity",0x00020000,R.anim.main_nav_enter_left,R.anim.main_nav_exit_right),
        Route("schedule","home","cn.campuslife.assistant.ui.MainActivity",0x00020000,R.anim.main_nav_enter_left,R.anim.main_nav_exit_right)
    )

    @Test fun sixRoutesKeepExplicitComponentsAndOriginalStackFlagsWithPlatformCustomOptions() {
        instrumentation.runOnMainSync {
            for(route in routes()) {
                val activity=RecordingActivity(instrumentation.targetContext)
                val original=Intent(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("nav_test_state","keep me")
                activity.intent=original
                assertTrue("${route.from} -> ${route.to}",MainNavigation.navigate(activity,route.from,route.to))
                val intent=activity.launched!!
                assertEquals(route.target,intent.component!!.className)
                assertEquals(instrumentation.targetContext.packageName,intent.component!!.packageName)
                // Exact masks: All tabs reuse REORDER_TO_FRONT; selecting home must not destroy sibling tabs.
                assertEquals(route.flags,intent.flags)
                assertNull(intent.action);assertNull(intent.data);assertNull(intent.categories);assertNull(intent.extras)
                val expectedOptions=ActivityOptions.makeCustomAnimation(activity,route.enter,route.exit).toBundle()
                val options=activity.options!!
                assertEquals(expectedOptions.keySet(),options.keySet())
                @Suppress("DEPRECATION")
                for(key in expectedOptions.keySet()) assertEquals(key,expectedOptions.get(key),options.get(key))
                assertEquals(listOf("startWithOptions"),activity.events)
                assertSame(original,activity.intent)
                assertEquals("keep me",original.getStringExtra("nav_test_state"))
                assertEquals(Intent.ACTION_MAIN,original.action)
                assertEquals(0x20000000,original.flags)
            }
        }
    }

    @Test fun selectedAndUnknownIdsDoNotLaunchAnimateOrChangeTheSourceIntent() {
        instrumentation.runOnMainSync {
            val activity=RecordingActivity(instrumentation.targetContext)
            val original=Intent().putExtra("nav_test_state",42);activity.intent=original
            for(id in listOf("home","schedule","settings")) assertFalse(MainNavigation.navigate(activity,id,id))
            val unknown=listOf("","unknown","Home","my"," home","schedule ","watermark")
            for(id in unknown) for(other in listOf("home","schedule","settings")+unknown) {
                assertFalse(MainNavigation.navigate(activity,id,other))
                assertFalse(MainNavigation.navigate(activity,other,id))
            }
            assertTrue(activity.events.isEmpty());assertNull(activity.launched);assertNull(activity.options)
            assertSame(original,activity.intent);assertEquals(42,original.getIntExtra("nav_test_state",0))
        }
    }

    @Test fun rejectedLaunchDoesNotOverrideTheCurrentWindowTransitionOrSwallowTheError() {
        instrumentation.runOnMainSync {
            val activity=RecordingActivity(instrumentation.targetContext).apply { rejectLaunch=true }
            try {
                MainNavigation.navigate(activity,"settings","home")
                fail("The original startActivity failure must propagate")
            } catch(expected: ActivityNotFoundException) {
                assertEquals("NAV-001 test launch rejected",expected.message)
            }
            assertEquals(listOf("startWithOptions"),activity.events);assertNull(activity.options)
        }
    }

    @Test fun platformAnimationsTranslateInTheExpectedPhysicalDirectionAndFinishIn200ms() {
        val fixtures=listOf(
            Triple(R.anim.main_nav_enter_left,-320f,0f),
            Triple(R.anim.main_nav_exit_right,0f,320f),
            Triple(R.anim.main_nav_enter_right,320f,0f),
            Triple(R.anim.main_nav_exit_left,0f,-320f)
        )
        for((resource,from,to) in fixtures) {
            val animation=AnimationUtils.loadAnimation(instrumentation.targetContext,resource)
            assertEquals(200L,animation.duration);assertEquals(0L,animation.startOffset)
            assertEquals(0,animation.repeatCount)
            animation.initialize(320,640,320,640);animation.startTime=1000L
            val transformation=Transformation();val matrix=FloatArray(9)
            animation.getTransformation(1000L,transformation);transformation.matrix.getValues(matrix)
            assertEquals(from,matrix[Matrix.MTRANS_X],.01f);assertEquals(0f,matrix[Matrix.MTRANS_Y],.01f)
            transformation.clear();animation.getTransformation(1100L,transformation);transformation.matrix.getValues(matrix)
            assertTrue("Animation must move between its endpoints",matrix[Matrix.MTRANS_X]>minOf(from,to) && matrix[Matrix.MTRANS_X]<maxOf(from,to))
            transformation.clear();animation.getTransformation(1200L,transformation);transformation.matrix.getValues(matrix)
            assertEquals(to,matrix[Matrix.MTRANS_X],.01f);assertEquals(0f,matrix[Matrix.MTRANS_Y],.01f)
        }
    }
}
