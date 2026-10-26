package cn.campuslife.assistant.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.io.IOException

class ApkVerifier(context: Context) {
    private val context = context.applicationContext
    private val flags get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    fun identity(file: File): ApkIdentity {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("无法读取安装包")
        val app = info.applicationInfo ?: throw IOException("安装包信息不完整")
        return ApkIdentity(info.packageName, version(info), app.minSdkVersion, signers(info), app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
    }
    fun verify(file: File, info: UpdatePackage) {
        if (!file.isFile || file.length() != info.size) throw IOException("安装包大小不符，请重新下载")
        file.inputStream().use { input -> UpdateRules.copyVerified(input, object : OutputStream() { override fun write(b: Int) = Unit; override fun write(b: ByteArray, off: Int, len: Int) = Unit }, info) }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        if (!UpdateRules.compatible(info, identity(file), version(installed), Build.VERSION.SDK_INT, signers(installed))) {
            throw IOException("安装包的签名、版本或设备要求不符，已停止安装")
        }
    }
    private fun signers(info: PackageInfo): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return certificates?.map { UpdateRules.digest(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
    }
    private fun version(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}
