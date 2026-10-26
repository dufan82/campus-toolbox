package cn.campuslife.assistant.batch

import cn.campuslife.watermark.*
import java.util.Collections
import java.util.UUID

data class BatchInput(val uri: String, val displayName: String, val persisted: Boolean, val bytes: Long? = null)
enum class BatchItemState { PENDING, PROCESSING, CREATING, WRITING, WRITTEN, SUCCESS, FAILED, SKIPPED, REVIEW }
enum class BatchRunState { READY, RUNNING, PAUSED, CANCELLED, COMPLETED }
data class BatchItem(
    val input: BatchInput, val state: BatchItemState = BatchItemState.PENDING, val attempt: Int = 0,
    val outputUri: String? = null, val outputName: String? = null, val error: String? = null,
    val importedName: String? = null
)
/** failed includes review; review is also shown separately so it cannot look safely retryable. */
data class BatchCounts(val success: Int, val failed: Int, val skipped: Int, val unprocessed: Int, val review: Int)

data class BatchOptions(
    val profile: CampusProfile, val details: ActivityDetails, val config: EditorConfig,
    val namingFields: List<FieldKey>, val format: ExportFormat, val maxEdge: Int?, val unifiedTime: Boolean,
    val timestamp: String = "batch"
) {
    fun detailsFor(capturedAt: String?) = if (unifiedTime) details else
        details.copy(values = details.values + (FieldKey.CAPTURED_AT to capturedAt.orEmpty()))

    companion object {
        fun freeze(profile: CampusProfile, details: ActivityDetails, config: EditorConfig, namingFields: List<FieldKey>,
            format: ExportFormat, maxEdge: Int?, unifiedTime: Boolean, timestamp: String = "batch"): BatchOptions {
            require(maxEdge == null || maxEdge in 256..12000)
            return BatchOptions(CampusProfile(Collections.unmodifiableMap(profile.values.toMap())),
                ActivityDetails(Collections.unmodifiableMap(details.values.toMap())),
                config.copy(order = Collections.unmodifiableList(config.order.toList()),
                    enabled = Collections.unmodifiableSet(config.enabled.toSet()), style = config.style.copy()),
                Collections.unmodifiableList(ExportNaming.fields(namingFields).toList()), format, maxEdge, unifiedTime, timestamp)
        }
    }
}

/** Immutable transitions. The worker persists each new value before exposing/scheduling it. */
data class BatchJob(val id: String, val options: BatchOptions, val items: List<BatchItem>,
    val treeUri: String? = null, val state: BatchRunState = BatchRunState.READY,
    val stopAfterCurrent: Boolean = false, val cancelRequested: Boolean = false) {
    fun counts() = BatchCounts(items.count { it.state == BatchItemState.SUCCESS },
        items.count { it.state in setOf(BatchItemState.FAILED, BatchItemState.REVIEW) },
        items.count { it.state == BatchItemState.SKIPPED },
        items.count { it.state in workable || it.state in active }, items.count { it.state == BatchItemState.REVIEW })

    fun start() = copy(state = BatchRunState.RUNNING, stopAfterCurrent = false, cancelRequested = false)
    fun nextIndex(): Int? = if (state != BatchRunState.RUNNING || stopAfterCurrent) null else
        items.indexOfFirst { it.state in workable }.takeIf { it >= 0 }
    fun begin(index: Int): BatchJob {
        require(state == BatchRunState.RUNNING && !stopAfterCurrent && items[index].state == BatchItemState.PENDING)
        return update(index) { it.copy(state = BatchItemState.PROCESSING, attempt = it.attempt + 1, error = null) }
    }
    fun imported(index: Int, name: String?) = update(index) { it.copy(importedName = name) }
    fun creating(index: Int, name: String) = update(index) {
        require(it.state == BatchItemState.PROCESSING)
        it.copy(state = BatchItemState.CREATING, outputName = name)
    }
    fun created(index: Int, uri: String, name: String) = update(index) {
        require(it.state in setOf(BatchItemState.PROCESSING, BatchItemState.CREATING))
        require(items.none { source -> source.input.uri == uri })
        require(items.withIndex().none { (i, other) -> i != index && other.outputUri == uri })
        it.copy(state = BatchItemState.WRITING, outputUri = uri, outputName = name)
    }
    fun written(index: Int) = update(index) {
        require(it.state == BatchItemState.WRITING && it.outputUri != null)
        it.copy(state = BatchItemState.WRITTEN)
    }
    fun succeeded(index: Int) = update(index) {
        require(it.state == BatchItemState.WRITTEN && it.outputUri != null)
        it.copy(state = BatchItemState.SUCCESS, error = null)
    }
    fun failed(index: Int, message: String) = update(index) {
        require(it.state !in setOf(BatchItemState.SUCCESS, BatchItemState.SKIPPED))
        // Once an output may exist, only an explicit review can authorize another attempt.
        it.copy(state = when {
            it.state == BatchItemState.WRITTEN -> BatchItemState.WRITTEN
            it.outputName != null -> BatchItemState.REVIEW
            else -> BatchItemState.FAILED
        }, error = message)
    }.let { if (items[index].state == BatchItemState.WRITTEN) it.requestStop(false).settle() else it }
    fun skip(index: Int) = update(index) {
        require(it.state in setOf(BatchItemState.PENDING, BatchItemState.FAILED, BatchItemState.REVIEW))
        require(it.outputUri == null) { "请先处理本项已创建的输出" }
        it.copy(state = BatchItemState.SKIPPED)
    }
    fun requestStop(cancel: Boolean) = copy(stopAfterCurrent = true, cancelRequested = cancelRequested || cancel)
    fun settle(): BatchJob = when {
        stopAfterCurrent -> copy(state = if (cancelRequested) BatchRunState.CANCELLED else BatchRunState.PAUSED)
        items.none { it.state in workable || it.state in active } -> copy(state = BatchRunState.COMPLETED)
        else -> this
    }
    fun retryFailures() = copy(state = BatchRunState.PAUSED, stopAfterCurrent = false, cancelRequested = false,
        items = items.map { if (it.state == BatchItemState.FAILED) it.copy(state = BatchItemState.PENDING, error = null) else it })
    fun hasUnresolvedOutputs() = items.any { it.state in setOf(BatchItemState.REVIEW,
        BatchItemState.CREATING, BatchItemState.WRITING, BatchItemState.WRITTEN) }
    /** The I/O boundary must verify removal before dropping a known output reference. */
    fun resolveReview(index: Int, retry: Boolean, outputRemoved: Boolean = false) = update(index) {
        require(it.state in setOf(BatchItemState.REVIEW, BatchItemState.WRITTEN))
        require(it.outputUri == null || outputRemoved) { "请先处理本项已创建的输出" }
        if (retry) it.copy(state = BatchItemState.PENDING, outputUri = null, outputName = null, error = null)
        else it.copy(state = BatchItemState.SKIPPED, outputUri = null, outputName = null, error = null)
    }.copy(state = BatchRunState.PAUSED)
    fun keepOutputAndSkip(index: Int) = update(index) {
        require(it.state in setOf(BatchItemState.REVIEW, BatchItemState.WRITTEN) && it.outputUri != null)
        it.copy(state = BatchItemState.SKIPPED, error = null)
    }.copy(state = BatchRunState.PAUSED)
    fun reselect(index: Int, replacement: BatchInput): BatchJob {
        require(replacement.uri.startsWith("content://"))
        require(items.withIndex().none { (i, item) -> i != index && item.input.uri == replacement.uri })
        return update(index) {
            require(it.state in setOf(BatchItemState.FAILED, BatchItemState.PENDING))
            it.copy(input = replacement, state = BatchItemState.PENDING, error = null)
        }.copy(state = BatchRunState.PAUSED)
    }

    fun recover(): BatchJob = copy(state = if (state in setOf(BatchRunState.READY, BatchRunState.COMPLETED)) state else BatchRunState.PAUSED,
        stopAfterCurrent = false, cancelRequested = false, items = items.map {
            when (it.state) {
                BatchItemState.PROCESSING -> it.copy(state = BatchItemState.PENDING)
                BatchItemState.CREATING, BatchItemState.WRITING -> it.copy(state = BatchItemState.REVIEW,
                    error = "上次写入中断，请在明细中处理未完成输出后再重试或跳过")
                else -> it
            }
        })
    private fun update(index: Int, transform: (BatchItem) -> BatchItem): BatchJob {
        require(index in items.indices)
        return copy(items = items.mapIndexed { i, item -> if (i == index) transform(item) else item })
    }
    companion object {
        private val workable = setOf(BatchItemState.PENDING, BatchItemState.WRITTEN)
        private val active = setOf(BatchItemState.PROCESSING, BatchItemState.CREATING, BatchItemState.WRITING)
        fun create(inputs: List<BatchInput>, options: BatchOptions, id: String = UUID.randomUUID().toString(), treeUri: String? = null): BatchJob {
            require(inputs.size in 1..50) { "每批请选择 1–50 张 JPEG / PNG 照片" }
            require(inputs.map { it.uri }.distinct().size == inputs.size) { "请选择不同的照片" }
            require(inputs.all { it.uri.startsWith("content://") }) { "请选择系统文件选择器中的照片" }
            return BatchJob(id, options, inputs.map { BatchItem(it.copy()) }, treeUri)
        }
    }
}

object BatchNames {
    fun fileName(options: BatchOptions, capturedAt: String?, sequence: Int, token: String): String {
        require(sequence in 1..50 && token.matches(Regex("[a-f0-9]{8}")))
        val composed = ExportNaming.compose(options.profile, options.detailsFor(capturedAt), options.namingFields, options.timestamp)
        val suffix = "_${sequence.toString().padStart(3, '0')}_$token"
        val result = StringBuilder()
        var i = 0
        while (i < composed.length) {
            val cp = composed.codePointAt(i)
            val next = String(Character.toChars(cp))
            if ((result.toString() + next + suffix).toByteArray(Charsets.UTF_8).size > 180 ||
                result.codePointCount(0, result.length) + 1 + suffix.length > 100) break
            result.append(next); i += Character.charCount(cp)
        }
        return ExportNaming.fileName(result.toString().trimEnd(' ', '.') + suffix, options.format)
    }
}
