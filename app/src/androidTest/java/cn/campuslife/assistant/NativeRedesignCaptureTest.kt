package cn.campuslife.assistant

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.image.PhotoLoader
import cn.campuslife.assistant.planner.*
import cn.campuslife.assistant.schedule.*
import cn.campuslife.assistant.ui.*
import cn.campuslife.watermark.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Disposable emulator fixtures for visual review, not demo data shipped inside the app. */
class NativeRedesignCaptureTest {
    @Test fun captureActualNativeScreens() {
        assumeTrue(Build.HARDWARE in setOf("ranchu","goldfish"))
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        val originals=listOf("schedule-v1.json","schedule-v1.json.bak","schedule-v1.json.new","planner-v1.json","planner-v1.json.bak","planner-v1.json.new").associate { name ->
            val f=File(ctx.filesDir,name);name to if(f.isFile) f.readBytes() else null
        }
        val store=LocalStore(ctx);val config=store.config();val profile=store.profile();val oldDraft=store.draftName()
        val photo=File(PhotoLoader(ctx).draftFolder,"redesign-capture.jpg")
        try {
            val now=LocalDate.now();val start=now.with(DayOfWeek.MONDAY)
            val times=listOf("08:30","09:20","10:25","11:15","12:10","13:05","14:00","14:50","15:55","16:45","19:00","19:50")
            val periods=times.mapIndexed { i,t -> val time=LocalTime.parse(t);SchedulePeriod("第${i+1}节",time,time.plusMinutes(45)) }
            fun course(id: Int,name: String,room: String,day: Int,first: Int,last: Int)=Course("00000000-0000-0000-0000-${id.toString().padStart(12,'0')}",name,"示例教师",room,day,first,last,1,20,WeekPattern.EVERY,null)
            val data=ScheduleData(Semester(start,20),periods,listOf(
                course(801,"视觉设计项目制作","实训3楼302",1,1,2),course(802,"高等数学","工科2楼205",2,1,2),
                course(803,"字体和版式设计","实训3楼401",3,3,4),course(804,"视觉设计项目制作","实训3楼302",4,1,2),
                course(805,"体育","体育馆",1,7,8),course(806,"数字绘画","实训2楼301",3,7,8)))
            File(ctx.filesDir,"schedule-v1.json").writeText(ScheduleStore.encode(data).toString())
            PlannerStore(ctx).write(PlannerState(countdowns=listOf(
                CountdownItem(PlannerRules.newId(),"体测",now.plusDays(12)),
                CountdownItem(PlannerRules.newId(),"生日",now.plusDays(37),yearly=true),
                CountdownItem(PlannerRules.newId(),"跨年",now.plusDays(54),yearly=true))))
            val input=File(ctx.getExternalFilesDir("verification"),"sample.jpg")
            if(input.isFile) input.copyTo(photo,overwrite=true) else Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(183,207,224));photo.outputStream().use { compress(Bitmap.CompressFormat.JPEG,95,it) };recycle()
            }
            store.saveDraftName(photo.name);store.saveProfile(CampusProfile(mapOf(FieldKey.CLASS_NAME to "示例班级",FieldKey.NAME to "同学")))
            store.saveConfig(EditorConfig(enabled=setOf(FieldKey.CLASS_NAME,FieldKey.NAME)))
            capture(MainActivity::class.java,"首页")
            capture(ScheduleActivity::class.java,"课程表")
            capture(PlannerActivity::class.java,"倒数日")
            capture(SettingsActivity::class.java,"我的")
            ActivityScenario.launch(WatermarkActivity::class.java).use { scenario ->
                var ready=false
                repeat(80) {
                    scenario.onActivity { a -> ready=a.window.decorView.findViewWithTag<WatermarkPreview>("watermark_preview").photo!=null }
                    if(!ready) SystemClock.sleep(50)
                }
                assertTrue(ready);save("照片水印")
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("watermark_styles").performClick() }
                inst.waitForIdleSync();SystemClock.sleep(250);save("水印样式")
            }
        } finally {
            for((name,bytes) in originals) { val f=File(ctx.filesDir,name);if(bytes==null) f.delete() else f.writeBytes(bytes) }
            store.saveConfig(config);store.saveProfile(profile);store.saveDraftName(oldDraft);photo.delete()
        }
    }
    private fun <T:Activity> capture(type:Class<T>,name:String) {
        ActivityScenario.launch(type).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(650);save(name)
        }
    }
    private fun save(name:String) {
        val inst=InstrumentationRegistry.getInstrumentation()
        val folder=File(inst.targetContext.getExternalFilesDir("verification"),"ui180").apply { mkdirs() }
        val bitmap=requireNotNull(inst.uiAutomation.takeScreenshot())
        File(folder,"$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) };bitmap.recycle()
    }
}
