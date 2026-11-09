package cn.campuslife.assistant.schedule

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore AES-GCM; ciphertext is excluded from backup and from timetable export/undo. */
class SchoolSyncVault(context:Context) {
    private val file=AtomicFile(File(context.applicationContext.noBackupFilesDir,"school-sync-credential-v1"))
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(ALIAS,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun write(credential:SchoolSyncCredential) {
        require(valid(credential.authorization));credential.semester.checked()
        val plain=JSONObject().put("authorization",credential.authorization).put("start",credential.semester.startMonday.toString())
            .put("weeks",credential.semester.totalWeeks).toString().toByteArray(Charsets.UTF_8)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key())}
        val encrypted=try {cipher.doFinal(plain)}finally{plain.fill(0)}
        val json=JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .put("data",Base64.encodeToString(encrypted,Base64.NO_WRAP)).toString().toByteArray(Charsets.UTF_8)
        val out=file.startWrite()
        try{out.write(json);file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
    }
    fun read():SchoolSyncCredential? {
        if(!file.baseFile.exists())return null
        return runCatching {
            val bytes=file.openRead().use {it.readBytes()};require(bytes.size<=20000)
            val json=JSONObject(String(bytes,Charsets.UTF_8))
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(json.getString("iv"),Base64.NO_WRAP)))
            }
            val plain=cipher.doFinal(Base64.decode(json.getString("data"),Base64.NO_WRAP))
            val decoded=try{JSONObject(String(plain,Charsets.UTF_8))}finally{plain.fill(0)}
            val auth=decoded.getString("authorization");require(valid(auth))
            SchoolSyncCredential(auth,Semester(LocalDate.parse(decoded.getString("start")),decoded.getInt("weeks")).checked())
        }.getOrNull()
    }
    fun clear() {file.delete()}
    private fun valid(auth:String)=SchoolPortalPolicy.timetableAuthorization("https://jw-api.fjpit.com/api/semesters",mapOf("ba-token" to auth.removePrefix("Bearer ")))==auth
    companion object{private const val ALIAS="campus.school-timetable.sync.v1"}
}
