package cn.campuslife.assistant.batch

import android.content.Intent
import android.net.Uri
import cn.campuslife.watermark.ExportFormat

internal object BatchIntents {
    fun photos(multiple: Boolean) = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png"))
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
    fun directory() = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    fun view(uri: Uri, format: ExportFormat) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, if (format == ExportFormat.PNG) "image/png" else "image/jpeg")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
