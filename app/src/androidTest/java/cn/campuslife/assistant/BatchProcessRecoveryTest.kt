package cn.campuslife.assistant

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.batch.*
import cn.campuslife.assistant.ui.MainActivity
import cn.campuslife.watermark.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Run seed, force-stop the process, then verify retired jobs stay untouched on app startup. */
class BatchProcessRecoveryTest {
    @Test fun explicitProcessDeathCannotResumeRetiredBatchOnAppStartup() {
        val phase=InstrumentationRegistry.getArguments().getString("batchPhase")
        assumeTrue(phase in setOf("seed","verify") && Build.HARDWARE in setOf("ranchu","goldfish") && Build.VERSION.SDK_INT>=29)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val file=File(context.filesDir,"batch_watermark_v1.json")
        val backup=File(context.filesDir,"batch-process-fixture-backup.txt")
        val store=BatchJobStore(context)
        if(phase=="seed") {
            check(!backup.exists())
            backup.writeText(if(file.exists()) file.readText() else "absent")
            val sources=(1..2).map { index -> File(context.cacheDir,"capture/batch-process-$index.png").also { source ->
                source.parentFile!!.mkdirs()
                val bitmap=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            } }
            val options=BatchOptions.freeze(CampusProfile(mapOf(FieldKey.NAME to "进程恢复测试")),ActivityDetails(),EditorConfig(enabled=setOf(FieldKey.NAME)),listOf(FieldKey.NAME),ExportFormat.PNG,null,false)
            var job=BatchJob.create(sources.map { BatchInput(FileProvider.getUriForFile(context,"${context.packageName}.files",it).toString(),it.name,false) },options).start()
            store.save(job);job=BatchProcessor(context,store).process(job,0) {}
            assertEquals(BatchItemState.SUCCESS,job.items[0].state)
            assertEquals(BatchItemState.PENDING,job.items[1].state)
        } else {
            val saved=store.load()!!
            val bytes=file.readBytes()
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    instrumentation.waitForIdleSync()
                    scenario.recreate()
                    instrumentation.waitForIdleSync()
                    assertEquals(1,store.load()!!.counts().success)
                    assertEquals(BatchItemState.PENDING,store.load()!!.items[1].state)
                    assertEquals(saved,store.load())
                    assertArrayEquals(bytes,file.readBytes())
                }
            } finally {
                store.load()?.items?.mapNotNull { it.outputUri }?.forEach { context.contentResolver.delete(Uri.parse(it),null,null) }
                (1..2).forEach { File(context.cacheDir,"capture/batch-process-$it.png").delete() }
                if(backup.isFile) { if(backup.readText()=="absent") file.delete() else file.writeText(backup.readText());backup.delete() }
            }
        }
    }
}
