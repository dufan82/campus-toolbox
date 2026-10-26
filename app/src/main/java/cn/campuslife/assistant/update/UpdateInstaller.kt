package cn.campuslife.assistant.update

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object UpdateInstaller {
    fun installIntent(context: Context, file: File): Intent {
        require(file.isFile && file.canonicalFile.parentFile == File(context.filesDir, "updates").canonicalFile &&
            file.name.matches(Regex("update_[0-9]+_[a-f0-9]{64}\\.apk"))) { "安装文件不属于本次更新" }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("update", uri) }
    }
    fun permissionIntent(context: Context): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
}
