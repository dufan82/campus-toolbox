package cn.campuslife.assistant

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.batch.*
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.watermark.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class BatchOutputRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun publishingFailurePausesAndExplicitRetryFinishesTheSameUriWithoutInput() = fixture { file, store ->
        var calls = 0
        val processor = BatchProcessor(context, store) { throw IOException("Fixture publish failure").also { calls++ } }
        val job = processor.process(initial(file), 0) { }
        val output = Uri.parse(job.items[0].outputUri!!)
        try {
            assertEquals(1, calls)
            assertEquals(BatchRunState.PAUSED, job.state)
            assertEquals(BatchItemState.WRITTEN, job.items[0].state)
            assertEquals(1, pending(output))
            assertTrue(job.hasUnresolvedOutputs())
            assertNull(job.nextIndex())
            file.delete()
            val result = BatchProcessor(context, store).process(store.load()!!.recover().start(), 0) { }
            assertEquals(BatchItemState.SUCCESS, result.items[0].state)
            assertEquals(output.toString(), result.items[0].outputUri)
            assertEquals(1, result.items[0].attempt)
            assertEquals(0, pending(output))
            assertFalse(result.hasUnresolvedOutputs())
        } finally { context.contentResolver.delete(output, null, null) }
    }

    @Test fun interruptedWritingKeepsOwnershipUntilConfirmedCleanupAndSkipOrRetry() {
        for (retry in listOf(false, true)) fixture { file, store ->
            val source = file.readBytes()
            val draft = LocalStore(context).draftName()
            assertThrows(BatchPersistenceException::class.java) {
                BatchProcessor(context, store).process(initial(file), 0) {
                    if (it.items[0].state == BatchItemState.WRITING)
                        throw BatchPersistenceException(IOException("Fixture interruption"))
                }
            }
            val recovered = store.load()!!.recover()
            val output = Uri.parse(recovered.items[0].outputUri!!)
            var replacement: Uri? = null
            try {
                assertEquals(BatchItemState.REVIEW, recovered.items[0].state)
                assertTrue(recovered.hasUnresolvedOutputs())
                assertThrows(IllegalArgumentException::class.java) { recovered.resolveReview(0, retry) }
                assertThrows(IllegalArgumentException::class.java) { BatchProcessor(context, store).preservePublished(recovered, 0) }
                assertEquals(1, pending(output))
                val resolved = BatchProcessor(context, store).resolveReviewed(recovered, 0, retry)
                assertFalse(resolved.hasUnresolvedOutputs())
                assertNull(resolved.items[0].outputUri)
                context.contentResolver.query(output, arrayOf(MediaStore.Images.Media._ID), null, null, null)!!.use {
                    assertFalse(it.moveToFirst())
                }
                if (retry) {
                    val result = BatchProcessor(context, store).process(resolved.start(), 0) { }
                    replacement = Uri.parse(result.items[0].outputUri!!)
                    assertNotEquals(output, replacement)
                    assertEquals(BatchItemState.SUCCESS, result.items[0].state)
                } else assertEquals(BatchItemState.SKIPPED, resolved.items[0].state)
                assertArrayEquals(source, file.readBytes())
                assertEquals(draft, LocalStore(context).draftName())
            } finally {
                // Successful cleanup has already deleted this row; recent Android rejects a second delete.
                runCatching { context.contentResolver.delete(output, null, null) }
                replacement?.let { context.contentResolver.delete(it, null, null) }
            }
        }
    }

    @Test fun cleanupRefusesPublishedForeignRenamedAndInputUrisWithoutDroppingTheRecord() = fixture { file, store ->
        val result = BatchProcessor(context, store).process(initial(file), 0) { }
        val output = Uri.parse(result.items[0].outputUri!!)
        try {
            val review = result.copy(items = result.items.map { it.copy(state = BatchItemState.REVIEW) })
            store.save(review)
            assertThrows(IllegalArgumentException::class.java) { BatchProcessor(context, store).resolveReviewed(review, 0, false) }
            assertEquals(review, store.load())
            assertEquals(0, pending(output))
            val kept = BatchProcessor(context, store).preservePublished(review, 0)
            assertEquals(BatchItemState.SKIPPED, kept.items[0].state)
            assertEquals(output.toString(), kept.items[0].outputUri)
            val foreign = review.copy(items = review.items.map { it.copy(outputUri = it.input.uri) })
            assertThrows(IllegalArgumentException::class.java) { BatchProcessor(context, store).resolveReviewed(foreign, 0, false) }
            val renamed = review.copy(items = review.items.map { it.copy(outputName = "different-name.png") })
            assertThrows(IllegalArgumentException::class.java) { BatchProcessor(context, store).resolveReviewed(renamed, 0, false) }
            assertTrue(file.exists())
            assertEquals(0, pending(output))
        } finally { context.contentResolver.delete(output, null, null) }
    }

    @Test fun cleanupIsIdempotentAfterDeleteBeforeCheckpointCrash() = fixture { file, store ->
        assertThrows(BatchPersistenceException::class.java) {
            BatchProcessor(context, store).process(initial(file), 0) {
                if (it.items[0].state == BatchItemState.WRITING) throw BatchPersistenceException(IOException("Fixture"))
            }
        }
        val recovered = store.load()!!.recover()
        context.contentResolver.delete(Uri.parse(recovered.items[0].outputUri!!), null, null)
        val resolved = BatchProcessor(context, store).resolveReviewed(recovered, 0, false)
        assertEquals(BatchItemState.SKIPPED, resolved.items[0].state)
        assertNull(resolved.items[0].outputUri)
    }

    @Test fun legacyManualAcknowledgementDoesNotRequireAReadableDocument() {
        assumeTrue(Build.VERSION.SDK_INT in 26..28)
        val name = "batch-legacy-output-${System.nanoTime()}.json"
        val store = BatchJobStore(context, name)
        try {
            for (written in listOf(false, true)) {
                var job = BatchJob.create(listOf(BatchInput("content://fixture/source", "source.png", false)),
                    BatchOptions.freeze(CampusProfile(), ActivityDetails(), EditorConfig(), emptyList(), ExportFormat.PNG, null, false))
                    .start().begin(0).created(0, "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Fmissing-fixture.png", "missing-fixture.png")
                if (written) job = job.written(0)
                job = job.recover(); store.save(job)
                val ended = BatchProcessor(context, store).acknowledgeDocumentOutput(job, 0)
                assertEquals(BatchItemState.SKIPPED, ended.items[0].state)
                assertEquals(job.items[0].outputUri, ended.items[0].outputUri)
                assertEquals(ended, store.load())
                assertFalse(ended.hasUnresolvedOutputs())
            }
        } finally { File(context.filesDir, name).delete() }
    }

    private fun initial(file: File) = BatchJob.create(listOf(BatchInput(FileProvider.getUriForFile(context,
        "${context.packageName}.files", file).toString(), file.name, false)), BatchOptions.freeze(CampusProfile(),
        ActivityDetails(), EditorConfig(), emptyList(), ExportFormat.PNG, null, false)).start()

    private fun pending(uri: Uri): Int = context.contentResolver.query(uri,
        arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use { assertTrue(it.moveToFirst()); it.getInt(0) }

    private fun fixture(block: (File, BatchJobStore) -> Unit) {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val name = "batch-output-recovery-${System.nanoTime()}.json"
        val file = File(context.cacheDir, "capture/$name.png").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        try { block(file, BatchJobStore(context, name)) }
        finally { file.delete(); File(context.filesDir, name).delete() }
    }
}
