package cn.campuslife.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.batch.*
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.watermark.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BatchWatermarkFlowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun finishPublishesRealPngAfterBoundsOnlyDecodeReturnsNull() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val outputs = BatchOutputs(context)
        val uri = outputs.create(null, "batch-finish-${System.nanoTime()}.png", ExportFormat.PNG)
        try {
            val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            try { context.contentResolver.openOutputStream(uri)!!.use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            outputs.finish(uri)
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun pickerUsesOpenDocumentAndViewingAnOutputRetainsBothUriAndMime() {
        val picker = BatchIntents.photos(true)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action)
        assertTrue(picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertArrayEquals(arrayOf("image/jpeg", "image/png"), picker.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertTrue(picker.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(picker.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
        assertFalse(BatchIntents.photos(false).getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true))
        val uri = Uri.parse("content://fixture/output/1")
        val view = BatchIntents.view(uri, ExportFormat.PNG)
        assertEquals(uri, view.data); assertEquals("image/png", view.type)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun interruptedAfterWrittenResumesTheSameOutputWithoutImportingAgain() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val file = File(context.cacheDir, "capture/batch-recovery-${System.nanoTime()}.png").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val storeName = "batch-recovery-${System.nanoTime()}.json"
        val store = BatchJobStore(context, storeName)
        var output: Uri? = null
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val options = BatchOptions.freeze(CampusProfile(), ActivityDetails(), EditorConfig(), emptyList(), ExportFormat.PNG, null, false)
            val job = BatchJob.create(listOf(BatchInput(uri.toString(), "fixture.png", false)), options).start()
            store.save(job)
            assertThrows(BatchPersistenceException::class.java) {
                BatchProcessor(context, store).process(job, 0) { saved ->
                    if (saved.items[0].state == BatchItemState.WRITTEN) throw BatchPersistenceException(IOException("Fixture interruption"))
                }
            }
            val recovered = store.load()!!.recover().start()
            output = Uri.parse(recovered.items[0].outputUri!!)
            assertEquals(BatchItemState.WRITTEN, recovered.items[0].state)
            file.delete() // Resuming must not need the input or create a second output.
            val finished = BatchProcessor(context, store).process(recovered, 0) { }
            assertEquals(BatchItemState.SUCCESS, finished.items[0].state)
            assertEquals(output.toString(), finished.items[0].outputUri)
            assertEquals(1, finished.items[0].attempt)
        } finally {
            output?.let { context.contentResolver.delete(it, null, null) }
            file.delete(); File(context.filesDir, storeName).delete()
        }
    }

    @Test fun recoveringImportsCannotDeleteTheCurrentSingleEditorDraft() {
        val local = LocalStore(context)
        val oldDraft = local.draftName()
        val file = File(context.filesDir, "photos/photo_${UUID.randomUUID()}.img").apply { parentFile!!.mkdirs(); writeText("fixture") }
        try {
            local.saveDraftName(file.name)
            val options = BatchOptions.freeze(CampusProfile(), ActivityDetails(), EditorConfig(), emptyList(), ExportFormat.PNG, null, false)
            val job = BatchJob.create(listOf(BatchInput("content://fixture/1", "fixture.png", true)), options).imported(0, file.name)
            BatchProcessor(context, BatchJobStore(context, "batch-cleanup-fixture.json")).cleanupRecordedImports(job)
            assertTrue("Recovery must preserve the editor's current photo", file.exists())
            assertEquals(file.name, local.draftName())
        } finally { local.saveDraftName(oldDraft); file.delete() }
    }

    @Test fun sequentialExportUsesOwnExifOriginalSizeAndRestoresWithoutRepeatingSuccess() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val files = (1..2).map { index ->
            File(context.cacheDir, "capture/batch-fixture-${System.nanoTime()}-$index.jpg").also { file ->
                file.parentFile!!.mkdirs()
                val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }; bitmap.recycle()
                ExifInterface(file).apply {
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:02:0$index 04:05:06")
                    setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes()
                }
            }
        }
        val originals = files.map { it.readBytes() }
        val store = BatchJobStore(context, "batch-flow-${System.nanoTime()}.json")
        val outputs = mutableListOf<Uri>()
        val draft = LocalStore(context).draftName()
        val importsBefore = File(context.filesDir, "photos").list()?.toSet().orEmpty()
        try {
            val options = BatchOptions.freeze(CampusProfile(mapOf(FieldKey.NAME to "Fixture")), ActivityDetails(),
                EditorConfig(enabled = setOf(FieldKey.CAPTURED_AT)), listOf(FieldKey.CAPTURED_AT), ExportFormat.PNG, null, false)
            // Use FileProvider fixture URIs, as the production picker supplies content URIs.
            val inputs = files.map { file -> BatchInput(androidx.core.content.FileProvider.getUriForFile(context,
                "${context.packageName}.files", file).toString(), file.name, false) }
            var job = BatchJob.create(inputs, options).start()
            store.save(job)
            val processor = BatchProcessor(context, store)
            job = processor.process(job, 0) { }
            outputs += Uri.parse(job.items[0].outputUri)
            assertEquals(BatchItemState.SUCCESS, job.items[0].state)
            assertTrue(job.items[0].outputName!!.startsWith("2024-02-01 04_05_06_001_"))
            job = store.load()!!.recover().start()
            assertEquals(1, job.nextIndex())
            job = processor.process(job, 1) { }.settle()
            outputs += Uri.parse(job.items[1].outputUri)
            assertEquals(2, job.counts().success)
            assertTrue(job.items[1].outputName!!.startsWith("2024-02-02 04_05_06_002_"))
            for (uri in outputs) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
                assertEquals(480, bounds.outWidth); assertEquals(640, bounds.outHeight)
            }
            assertEquals(draft, LocalStore(context).draftName())
            assertEquals(importsBefore, File(context.filesDir, "photos").list()?.toSet().orEmpty())
            files.forEachIndexed { index, file -> assertArrayEquals(originals[index], file.readBytes()) }
        } finally {
            outputs.forEach { context.contentResolver.delete(it, null, null) }
            files.forEach { it.delete() }
        }
    }

    @Test fun snapshotRoundTripPreservesFrozenStyleAndRejectsUnknownOrCorruptStoreWithoutReplacingIt() {
        val name = "batch-codec-${System.nanoTime()}.json"
        val store = BatchJobStore(context, name)
        val options = BatchOptions.freeze(CampusProfile(), ActivityDetails(),
            EditorConfig(style = WatermarkStyle(position = Position.CUSTOM, anchorX = .4, anchorY = .7), contentOnly = false),
            listOf(FieldKey.NAME), ExportFormat.PNG, 2048, true)
        val job = BatchJob.create(listOf(BatchInput("content://fixture/1", "fixture.png", true)), options)
        store.save(job)
        assertEquals(job, store.load())
        val file = File(context.filesDir, name)
        file.writeText("{broken fixture")
        val before = file.readBytes()
        assertThrows(Exception::class.java) { store.load() }
        assertArrayEquals(before, file.readBytes())
        file.delete()
    }
}
