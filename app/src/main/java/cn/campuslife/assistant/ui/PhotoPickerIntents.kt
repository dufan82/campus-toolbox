package cn.campuslife.assistant.ui

import android.content.Intent
import android.os.Build
import android.provider.MediaStore

/** Prefer the system media picker and retain a document-provider fallback. */
object PhotoPickerIntents {
    fun primary(sdk: Int): Intent = Intent(if (sdk >= Build.VERSION_CODES.TIRAMISU) MediaStore.ACTION_PICK_IMAGES else Intent.ACTION_GET_CONTENT).apply {
        type="image/*"
        putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("image/jpeg","image/png"))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (sdk < Build.VERSION_CODES.TIRAMISU) addCategory(Intent.CATEGORY_OPENABLE)
    }
    fun fallback(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type="image/*"
        putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("image/jpeg","image/png"))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
