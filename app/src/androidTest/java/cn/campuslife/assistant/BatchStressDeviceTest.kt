package cn.campuslife.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Debug
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.batch.*
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.watermark.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class BatchStressDeviceTest {
    @Test fun twelveMegapixelSequenceReleasesImportsAndCancelKeepsFinishedOutput() {
        assumeTrue(Build.HARDWARE in setOf("ranchu","goldfish") && Build.VERSION.SDK_INT>=29)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File(context.cacheDir,"capture/stress-${System.nanoTime()}.jpg").apply { parentFile!!.mkdirs() }
        val bitmap=Bitmap.createBitmap(3000,4000,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,96,it)) };bitmap.recycle()
        fun digest()=MessageDigest.getInstance("SHA-256").digest(source.readBytes())
        val original=digest()
        val sources=(1..3).map { index -> File(source.parentFile,"${source.nameWithoutExtension}-$index.jpg").also { source.copyTo(it) } }
        val draft=LocalStore(context).draftName()
        val beforeImports=File(context.filesDir,"photos").list()?.toSet().orEmpty()
        val name="batch-stress-${System.nanoTime()}.json"
        val store=BatchJobStore(context,name)
        val outputs=mutableListOf<Uri>()
        try {
            val options=BatchOptions.freeze(CampusProfile(mapOf(FieldKey.NAME to "批量回归用例")),ActivityDetails(),
                EditorConfig(enabled=setOf(FieldKey.NAME)),listOf(FieldKey.NAME),ExportFormat.JPEG,null,false)
            var job=BatchJob.create(sources.map { file -> BatchInput(FileProvider.getUriForFile(context,"${context.packageName}.files",file).toString(),file.name,false) },options).start()
            val processor=BatchProcessor(context,store)
            val pss=mutableListOf(Debug.getPss())
            for(index in 0..1) {
                job=processor.process(job,index) { pss+=Debug.getPss() }
                job.items[index].outputUri?.let { outputs+=Uri.parse(it) }
                assertEquals(BatchItemState.SUCCESS,job.items[index].state)
                assertEquals(beforeImports,File(context.filesDir,"photos").list()?.toSet().orEmpty())
            }
            job=job.requestStop(true).settle();store.save(job)
            assertEquals(BatchRunState.CANCELLED,job.state)
            assertNull(job.nextIndex())
            assertEquals(2,job.counts().success)
            assertEquals(BatchItemState.PENDING,job.items[2].state)
            val recovered=store.load()!!.recover()
            assertNull(recovered.nextIndex())
            assertEquals(outputs.map(Uri::toString),recovered.items.take(2).map { it.outputUri })
            for(output in outputs) {
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                context.contentResolver.openInputStream(output)!!.use { BitmapFactory.decodeStream(it,null,bounds) }
                assertEquals(3000,bounds.outWidth);assertEquals(4000,bounds.outHeight)
            }
            assertArrayEquals(original,digest());sources.forEach { assertArrayEquals(original,MessageDigest.getInstance("SHA-256").digest(it.readBytes())) };assertEquals(draft,LocalStore(context).draftName())
            val evidence=File(context.getExternalFilesDir("verification"),"batch-memory.txt")
            evidence.parentFile!!.mkdirs();evidence.writeText("fixture=12MP, sequential 2 of 3, emulator\npssKiB=${pss.joinToString(",")}\npeakObservedKiB=${pss.maxOrNull()}\n")
        } finally {
            outputs.forEach { context.contentResolver.delete(it,null,null) }
            source.delete();sources.forEach { it.delete() };File(context.filesDir,name).delete()
        }
    }
}
