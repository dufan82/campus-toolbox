package cn.campuslife.assistant.batch

import cn.campuslife.watermark.*
import org.junit.Assert.*
import org.junit.Test

class BatchQueueTest {
    private fun queue(count: Int = 3) = BatchJob.create((1..count).map {
        BatchInput("content://test/$it", "photo-$it.jpg", true)
    }, BatchOptions.freeze(CampusProfile(), ActivityDetails(), EditorConfig(), listOf(FieldKey.NAME), ExportFormat.JPEG, null, false), "test-job")

    @Test fun cancellationFinishesCurrentAndLeavesOtherPhotosUnprocessed() {
        var job = queue().start().begin(0)
        job = job.requestStop(true)
        assertEquals(BatchItemState.PROCESSING, job.items[0].state)
        job = job.created(0, "content://output/1", "output_001.jpg").written(0).succeeded(0).settle()
        assertEquals(BatchRunState.CANCELLED, job.state)
        assertNull(job.nextIndex())
        assertEquals(BatchCounts(1, 0, 0, 2, 0), job.counts())
    }

    @Test fun retryOnlyReschedulesFailuresNeverSuccessOrSkipped() {
        var job = queue().start().begin(0).created(0, "content://output/1", "one.jpg").written(0).succeeded(0)
        job = job.begin(1).failed(1, "decode").skip(2).settle().retryFailures()
        assertEquals(listOf(BatchItemState.SUCCESS, BatchItemState.PENDING, BatchItemState.SKIPPED), job.items.map { it.state })
        assertEquals(1, job.start().nextIndex())
        assertEquals(1, job.items[0].attempt)
    }

    @Test fun recoveryPausesAndNeverBlindlyRepeatsAnUncertainOutput() {
        val base = queue().start().begin(0).created(0, "content://output/1", "one.jpg").written(0).succeeded(0).begin(1)
        val recovered = base.creating(1, "two.jpg").recover()
        assertEquals(BatchRunState.PAUSED, recovered.state)
        assertEquals(BatchItemState.REVIEW, recovered.items[1].state)
        assertEquals(2, recovered.start().nextIndex())
        assertEquals("one.jpg", recovered.items[0].outputName)
        assertEquals(BatchCounts(1, 1, 0, 1, 1), recovered.counts())
    }

    @Test fun recoveryRetriesImportButFinalizesWrittenOutputWithoutCreatingAnother() {
        val importing = queue().start().begin(0).recover()
        assertEquals(BatchItemState.PENDING, importing.items[0].state)
        val written = queue().start().begin(0).created(0, "content://output/1", "one.jpg").written(0).recover()
        assertEquals(BatchItemState.WRITTEN, written.items[0].state)
        assertEquals("content://output/1", written.items[0].outputUri)
        assertEquals(0, written.start().nextIndex())
    }

    @Test fun pausedQueueDoesNotScheduleAndRestartDoesNotRepeatSuccess() {
        val job = queue().start().begin(0).failed(0, "no access").requestStop(false).settle()
        assertEquals(BatchRunState.PAUSED, job.state)
        assertNull(job.nextIndex())
        assertEquals(1, job.start().nextIndex())
    }

    @Test fun freezeCopiesMutableFieldsAndUsesEachPhotosExifUnlessExplicitlyUnified() {
        val values = mutableMapOf(FieldKey.NAME to "Fixture")
        val order = mutableListOf(FieldKey.NAME, FieldKey.CAPTURED_AT)
        val names = mutableListOf(FieldKey.NAME)
        val details = ActivityDetails(mapOf(FieldKey.CAPTURED_AT to "unified"))
        val frozen = BatchOptions.freeze(CampusProfile(values), details, EditorConfig(order = order), names, ExportFormat.PNG, 2048, false)
        values[FieldKey.NAME] = "Changed"; order.reverse(); names.clear()
        assertEquals("Fixture", frozen.profile.values[FieldKey.NAME])
        assertEquals(FieldKey.NAME, frozen.config.order.first())
        assertEquals(listOf(FieldKey.NAME), frozen.namingFields)
        assertEquals("2024-02-03 04:05:06", frozen.detailsFor("2024-02-03 04:05:06").values[FieldKey.CAPTURED_AT])
        assertEquals("", frozen.detailsFor(null).values[FieldKey.CAPTURED_AT])
        assertEquals("unified", frozen.copy(unifiedTime = true).detailsFor(null).values[FieldKey.CAPTURED_AT])
    }

    @Test fun selectionLimitRejectsWholeOversizedSelectionRatherThanSilentlyDroppingPhotos() {
        assertThrows(IllegalArgumentException::class.java) { queue(51) }
        assertThrows(IllegalArgumentException::class.java) { queue(0) }
        assertEquals(50, queue(50).items.size)
    }

    @Test fun uncertainOutputsRequireExplicitResolutionAndAreNotOrdinaryRetries() {
        val job = queue(1).start().begin(0).created(0, "content://output/1", "one.jpg").recover()
        assertEquals(BatchItemState.REVIEW, job.retryFailures().items[0].state)
        assertTrue(job.hasUnresolvedOutputs())
        assertThrows(IllegalArgumentException::class.java) { job.resolveReview(0, false) }
        assertThrows(IllegalArgumentException::class.java) { job.resolveReview(0, true) }
        assertThrows(IllegalArgumentException::class.java) { job.skip(0) }
        val skipped = job.resolveReview(0, false, outputRemoved = true)
        assertEquals(BatchItemState.SKIPPED, skipped.items[0].state)
        assertNull(skipped.items[0].outputUri)
        assertFalse(skipped.hasUnresolvedOutputs())
        assertEquals(BatchItemState.PENDING, job.resolveReview(0, true, outputRemoved = true).items[0].state)
    }

    @Test fun publishingFailureKeepsTheWrittenUriAndPausesInsteadOfLooping() {
        val written = queue(1).start().begin(0).created(0, "content://output/1", "one.jpg").written(0)
        val failed = written.failed(0, "publish failed").settle()
        assertEquals(BatchItemState.WRITTEN, failed.items[0].state)
        assertEquals("content://output/1", failed.items[0].outputUri)
        assertEquals(BatchRunState.PAUSED, failed.state)
        assertNull(failed.nextIndex())
        assertEquals(0, failed.start().nextIndex())
        assertTrue(failed.hasUnresolvedOutputs())
    }

    @Test fun uncertainUnknownCreationRequiresAcknowledgementButNoInventedDeletion() {
        val job = queue(1).start().begin(0).creating(0, "unknown.jpg").recover()
        assertTrue(job.hasUnresolvedOutputs())
        assertNull(job.items[0].outputUri)
        assertFalse(job.resolveReview(0, false).hasUnresolvedOutputs())
    }

    @Test fun manuallyHandledDocumentCanEndWithoutReadingOrDroppingItsUri() {
        for (written in listOf(false, true)) {
            val created = queue(1).start().begin(0).created(0,
                "content://documents/tree/export/document/missing", "one.jpg")
            val job = (if (written) created.written(0) else created).recover()
            val ended = job.keepOutputAndSkip(0)
            assertEquals(BatchItemState.SKIPPED, ended.items[0].state)
            assertEquals(job.items[0].outputUri, ended.items[0].outputUri)
            assertEquals("one.jpg", ended.items[0].outputName)
            assertFalse(ended.hasUnresolvedOutputs())
            assertEquals(0, ended.counts().unprocessed)
        }
        val success = queue(1).start().begin(0).created(0, "content://output/1", "one.jpg").written(0).succeeded(0)
        assertThrows(IllegalArgumentException::class.java) { success.keepOutputAndSkip(0) }
    }

    @Test fun outputMustNotAliasAnyInputInTheBatch() {
        val job = queue(2).start().begin(0)
        assertThrows(IllegalArgumentException::class.java) { job.created(0, "content://test/2", "two.jpg") }
    }

    @Test fun replacementCannotDuplicateAnotherQueuedInputAndCorruptTheNextRestore() {
        val job = queue(2)
        assertThrows(IllegalArgumentException::class.java) {
            job.reselect(0, BatchInput("content://test/2", "replacement.jpg", true))
        }
    }

    @Test fun restoringAnUnstartedSelectionStillAllowsConfiguration() {
        assertEquals(BatchRunState.READY, queue().recover().state)
    }

    @Test fun newlyCreatedOutputCannotReuseAnEarlierSuccessfulOutputUri() {
        val job = queue(2).start().begin(0).created(0, "content://output/1", "one.jpg").written(0).succeeded(0).begin(1)
        assertThrows(IllegalArgumentException::class.java) { job.created(1, "content://output/1", "two.jpg") }
    }
}
