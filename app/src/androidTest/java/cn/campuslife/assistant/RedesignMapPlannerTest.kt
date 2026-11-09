package cn.campuslife.assistant

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.map.*
import cn.campuslife.assistant.planner.*
import cn.campuslife.assistant.ui.Ui
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Dedicated-emulator, native Views only. No SDK initialization or external navigation. */
class RedesignMapPlannerTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun tagged(activity: Activity,tag: String): View = requireNotNull(activity.window.decorView.findViewWithTag<View>(tag)) { "Missing $tag" }
    private fun waitFor(message: String,condition: ()->Boolean) {
        val until=System.nanoTime()+8_000_000_000L
        while(System.nanoTime()<until) { if(condition()) return;Thread.sleep(40) }
        fail(message)
    }
    private fun emulatorOnly() { assumeTrue(Build.VERSION.SDK_INT>=29 && Build.HARDWARE in setOf("ranchu","goldfish")) }
    private fun <T: Activity> awaitTag(scenario: ActivityScenario<T>,tag: String) = waitFor("Missing enabled $tag") {
        var ready=false
        scenario.onActivity { ready=it.window.decorView.findViewWithTag<View>(tag)?.isEnabled==true }
        ready
    }
    private fun field(activity: Activity,name: String,value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible=true }.set(activity,value)
    }
    private fun dialogView(match: (View)->Boolean): View = requireNotNull(
        WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull { all(it).firstOrNull(match) })

    private fun mapFixture(block: (CampusMapActivity,MapScreen)->Unit) {
        emulatorOnly()
        val prefs=context.getSharedPreferences("campus_map",0)
        val existed=prefs.contains("privacy_consent_v1");val consent=prefs.getBoolean("privacy_consent_v1",false)
        try {
            assertTrue(prefs.edit().putBoolean("privacy_consent_v1",false).commit())
            ActivityScenario.launch<CampusMapActivity>(Intent(context,CampusMapActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    assertNull(activity.javaClass.getDeclaredField("mapView").apply { isAccessible=true }.get(activity))
                    val screen=MapScreen(activity)
                    val chrome=CampusMapChrome(activity,screen);chrome.addMapControls()
                    field(activity,"screen",screen);field(activity,"mapChrome",chrome)
                    block(activity,screen)
                }
            }
        } finally {
            val editor=prefs.edit()
            if(existed) editor.putBoolean("privacy_consent_v1",consent) else editor.remove("privacy_consent_v1")
            assertTrue(editor.commit())
        }
    }

    @Test fun mapFloatingSearchUsesRealLocalResultsAndOnlyOneSelectedNavigation() = mapFixture { activity,screen ->
        fun search() { activity.javaClass.getDeclaredMethod("search").apply { isAccessible=true }.invoke(activity) }
        screen.onSearch={ search() }
        screen.onChoose={ place ->
            activity.javaClass.getDeclaredMethod("selectPlace",MapPlace::class.java,VisitReason::class.java)
                .apply { isAccessible=true }.invoke(activity,place,VisitReason.USER_SELECTION)
        }
        screen.query.setText("食堂");tagged(activity,"map_search").performClick()
        val rows=all(screen.root).filter { it.tag?.toString()?.startsWith("campus_place_")==true && it.isClickable }
        assertTrue("Use the real local campus directory",rows.isNotEmpty())
        assertEquals(View.GONE,tagged(activity,"map_results").visibility)
        assertTrue(generateSequence(rows.first().parent) { it.parent }.any { it===screen.query.parent.parent.parent })
        rows.first().performClick()
        val bottom=tagged(activity,"map_results")
        assertEquals(View.VISIBLE,bottom.visibility)
        assertEquals(1,all(bottom).filterIsInstance<Button>().size)
        assertNotNull(bottom.findViewWithTag<View>("map_navigate"))
        assertTrue(all(screen.root).none { it.tag?.toString()?.startsWith("campus_place_")==true })
        var navigated: MapPlace?=null
        screen.onNavigate={ navigated=it };tagged(activity,"map_navigate").performClick()
        assertNotNull(navigated)
        screen.query.setText("");tagged(activity,"map_search").performClick()
        assertEquals(View.GONE,tagged(activity,"map_results").visibility)
        assertEquals(View.GONE,tagged(activity,"map_search_choices").visibility)
        val removed=setOf("附近","全部","教学","宿舍","食堂","运动","服务","收藏","店铺")
        assertTrue(all(screen.root).filterIsInstance<Button>().none { it.text.toString() in removed })
        assertNull(screen.root.findViewWithTag<View>("map_favorites"))
        assertNull(activity.javaClass.getDeclaredField("sdk").apply { isAccessible=true }.get(activity))
    }

    @Test fun mapControlsAndReferencePrivacyCallbacksRemainAvailableWithoutSdk() = mapFixture { activity,screen ->
        var located=0;var campus=0;var reference=0;var privacy=0;var maintenance=0
        screen.onLocate={ located++ };screen.onCampus={ campus++ };screen.onReference={ reference++ };screen.onPrivacy={ privacy++ }
        tagged(activity,"map_locate").performClick();tagged(activity,"map_campus").performClick()
        assertEquals(1,located);assertEquals(1,campus)
        tagged(activity,"map_options").performClick()
        assertTrue(dialogView { it.tag=="map_reference" }.performClick());assertEquals(1,reference)
        screen.onMaintenance={ maintenance++ }
        tagged(activity,"map_options").performClick()
        assertTrue(dialogView { it.tag=="map_maintenance" }.performClick());assertEquals(1,maintenance)
        tagged(activity,"map_options").performClick()
        assertTrue(dialogView { it is TextView && it.text.toString()=="地图说明与隐私设置" }.performClick());assertEquals(1,privacy)
        for(tag in listOf("map_locate","map_campus","map_options","map_search")) {
            val button=tagged(activity,tag)
            assertTrue(button.minimumHeight>=Ui.dp(activity,48))
        }
    }

    private fun plannerFixture(initial: PlannerState,block: (ActivityScenario<PlannerActivity>,PlannerStore)->Unit) {
        emulatorOnly()
        val files=listOf("",".bak",".new").map { File(context.filesDir,"planner-v1.json$it") }
        val backup=files.associateWith { if(it.isFile) it.readBytes() else null }
        try {
            val store=PlannerStore(context);store.write(initial)
            ActivityScenario.launch<PlannerActivity>(Intent(context,PlannerActivity::class.java)).use { scenario ->
                awaitTag(scenario,"planner-add-header");block(scenario,store)
            }
        } finally { backup.forEach { (file,bytes) -> if(bytes==null) file.delete() else file.writeBytes(bytes) } }
    }

    @Test fun nearestUpcomingOwnsHeroAndEveryOtherDateAppearsOnceWithoutWriting() {
        val now=LocalDate.now()
        val past=CountdownItem(PlannerRules.newId(),"已过去的日期",now.minusDays(30))
        val soon=CountdownItem(PlannerRules.newId(),"体测",now.plusDays(2))
        val later=CountdownItem(PlannerRules.newId(),"生日",now.plusDays(15),yearly=true)
        val legacy=TodoItem(PlannerRules.newId(),"Retired fixture, never shown")
        val initial=PlannerState(todos=listOf(legacy),countdowns=listOf(past,later,soon))
        plannerFixture(initial) { scenario,store ->
            val bytes=File(context.filesDir,"planner-v1.json").readBytes()
            scenario.onActivity { activity ->
                val views=all(activity.window.decorView)
                assertEquals(1,views.count { it.tag=="planner-hero-days" })
                assertTrue(all(tagged(activity,"countdown-${soon.id}")).any { it.tag=="planner-hero-days" })
                for(item in initial.countdowns) assertEquals(1,views.count { it.tag=="countdown-${item.id}" })
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString()=="2 个" })
                assertTrue(views.filterIsInstance<TextView>().none { it.text.toString() in setOf("待办",legacy.title,"编辑","删除") })
                val add=tagged(activity,"planner-add-header");val visible=Rect()
                assertTrue(add.getGlobalVisibleRect(visible));assertEquals(add.height,visible.height())
            }
            scenario.recreate();awaitTag(scenario,"planner-add-header")
            assertEquals(initial,store.read());assertArrayEquals(bytes,File(context.filesDir,"planner-v1.json").readBytes())
        }
    }

    private fun pickDate(activity: Activity,date: LocalDate) {
        tagged(activity,"planner-date").performClick()
        val picker=dialogView { it is DatePicker } as DatePicker
        picker.updateDate(date.year,date.monthValue-1,date.dayOfMonth)
        dialogView { it.id==android.R.id.button1 }.performClick()
    }

    @Test fun dateTimePickersAndDraftSurviveRecreationAndCancelDoesNotWrite() {
        val item=CountdownItem(PlannerRules.newId(),"我的纪念日",LocalDate.now().plusDays(7),yearly=true,reminder=true)
        plannerFixture(PlannerState(countdowns=listOf(item))) { scenario,store ->
            val bytes=File(context.filesDir,"planner-v1.json").readBytes()
            scenario.onActivity { activity ->
                tagged(activity,"countdown-${item.id}").performClick()
                assertNotNull(tagged(activity,"planner-preset"))
                assertFalse(tagged(activity,"planner-date") is EditText)
                assertTrue((tagged(activity,"planner-yearly") as CheckBox).isChecked)
                assertTrue((tagged(activity,"planner-reminder") as CheckBox).isChecked)
                pickDate(activity,LocalDate.of(2032,2,29))
                tagged(activity,"planner-time").performClick()
                val picker=dialogView { it is TimePicker } as TimePicker
                picker.hour=7;picker.minute=45
                dialogView { it.id==android.R.id.button1 }.performClick()
                (tagged(activity,"planner-title") as EditText).setText("未保存的标题")
            }
            scenario.recreate();awaitTag(scenario,"planner-save")
            scenario.onActivity { activity ->
                assertTrue((tagged(activity,"planner-date") as TextView).text.contains("2032年2月29日"))
                assertTrue((tagged(activity,"planner-time") as TextView).text.contains("07:45"))
                assertEquals("未保存的标题",(tagged(activity,"planner-title") as EditText).text.toString())
                assertTrue((tagged(activity,"planner-yearly") as CheckBox).isChecked)
                tagged(activity,"planner-cancel").performClick()
            }
            awaitTag(scenario,"planner-add-header")
            assertEquals(listOf(item),store.read().countdowns)
            assertArrayEquals(bytes,File(context.filesDir,"planner-v1.json").readBytes())
        }
    }

    @Test fun existingReminderCanBeEditedSavedAndDeletedWithoutChangingRetiredRecords() {
        val item=CountdownItem(PlannerRules.newId(),"原有事项",LocalDate.now().plusDays(7),yearly=true,reminder=true)
        val legacy=TodoItem(PlannerRules.newId(),"Keep legacy data")
        plannerFixture(PlannerState(todos=listOf(legacy),countdowns=listOf(item))) { scenario,store ->
            scenario.onActivity { activity ->
                tagged(activity,"countdown-${item.id}").performClick()
                (tagged(activity,"planner-title") as EditText).setText("修改后的事项")
                tagged(activity,"planner-time").performClick()
                val picker=dialogView { it is TimePicker } as TimePicker
                picker.hour=8;picker.minute=35;dialogView { it.id==android.R.id.button1 }.performClick()
                tagged(activity,"planner-save").performClick()
            }
            awaitTag(scenario,"planner-add-header")
            val saved=store.read().countdowns.single()
            assertEquals(item.id,saved.id);assertEquals("修改后的事项",saved.title)
            assertTrue(saved.yearly);assertTrue(saved.reminder);assertEquals(LocalTime.of(8,35),saved.reminderTime)
            assertEquals(listOf(legacy),store.read().todos)
            scenario.onActivity { activity ->
                tagged(activity,"countdown-${item.id}").performClick();tagged(activity,"planner-delete").performClick()
                dialogView { it.id==android.R.id.button1 }.performClick()
            }
            awaitTag(scenario,"planner-add-header")
            assertTrue(store.read().countdowns.isEmpty());assertEquals(listOf(legacy),store.read().todos)
            scenario.onActivity { assertNotNull(tagged(it,"planner-empty")) }
        }
    }

    @Test fun presetDateCreationUsesCalendarAndKeepsRealRecurrenceDefaults() = plannerFixture(PlannerState()) { scenario,store ->
        scenario.onActivity { activity ->
            tagged(activity,"planner-add-header").performClick();tagged(activity,"planner-preset").performClick()
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val list=dialogView { it is ListView } as ListView
            assertEquals(CountdownPreset.entries.map { it.label },(0 until list.adapter.count).map { list.adapter.getItem(it).toString() })
            val position=CountdownPreset.entries.indexOf(CountdownPreset.FITNESS)
            assertTrue(list.performItemClick(null,position,list.adapter.getItemId(position)))
            assertFalse((tagged(activity,"planner-yearly") as CheckBox).isChecked)
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity { tagged(it,"planner-date").performClick() }
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val picker=activity.javaClass.getDeclaredField("picker").apply { isAccessible=true }.get(activity) as android.app.DatePickerDialog
            picker.datePicker.updateDate(2032,1,29)
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val picker=activity.javaClass.getDeclaredField("picker").apply { isAccessible=true }.get(activity) as android.app.DatePickerDialog
            picker.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            assertTrue((tagged(activity,"planner-date") as Button).text.toString().contains("2032"))
            tagged(activity,"planner-save").performClick()
        }
        awaitTag(scenario,"planner-add-header")
        val saved=store.read().countdowns.single()
        assertEquals("体测",saved.title);assertEquals(LocalDate.of(2032,2,29),saved.date);assertFalse(saved.yearly)
    }
}
