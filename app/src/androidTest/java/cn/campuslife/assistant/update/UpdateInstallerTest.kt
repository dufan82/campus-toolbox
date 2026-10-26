package cn.campuslife.assistant.update

import android.content.Intent
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UpdateInstallerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun installerUsesScopedReadOnlyContentUriAndPermissionIsPerApp() {
        val folder = File(context.filesDir, "updates"); folder.mkdirs()
        val file = File(folder, "update_18_${"a".repeat(64)}.apk"); file.writeText("synthetic")
        try {
            val intent = UpdateInstaller.installIntent(context, file)
            assertEquals("content", intent.data!!.scheme)
            assertEquals(context.packageName + ".files", intent.data!!.authority)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val permission = UpdateInstaller.permissionIntent(context)
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permission.action)
            assertEquals("package:${context.packageName}", permission.data.toString())
            assertThrows(Exception::class.java) { UpdateInstaller.installIntent(context, File(context.filesDir, "outside.apk")) }
        } finally { file.delete() }
    }
}
