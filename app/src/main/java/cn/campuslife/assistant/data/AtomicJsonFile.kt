package cn.campuslife.assistant.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Private, versioned module documents. Callers own their background I/O dispatcher. */
class AtomicJsonFile(context: Context,name: String) {
    init { require(name.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}")) && !name.contains("..")) }
    private val file=AtomicFile(File(context.applicationContext.filesDir,name))
    private val lock=locks.getOrPut(file.baseFile.absolutePath) { Any() }
    fun read(): JSONObject? = synchronized(lock) {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return@synchronized null
        try {
            val text=file.openRead().use { input ->
                val bytes=input.readBytesBounded()
                String(bytes,Charsets.UTF_8)
            }
            JSONObject(text)
        } catch(error: Exception) { throw IOException("本机数据无法读取，原文件已保留；请勿卸载应用",error) }
    }
    fun write(value: JSONObject): Unit = synchronized(lock) {
        val bytes=value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=8*1024*1024) { "本机记录过多，请先整理后重试" }
        val output=file.startWrite()
        try { output.write(bytes);file.finishWrite(output) }
        catch(error: Exception) { file.failWrite(output);throw IOException("本机保存失败，原记录已保留，请检查存储空间",error) }
    }
    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output=java.io.ByteArrayOutputStream()
        val buffer=ByteArray(8192)
        while(true) {
            val size=read(buffer);if(size<0) break
            if(output.size()+size>8*1024*1024) throw IOException("Local data exceeds size limit")
            output.write(buffer,0,size)
        }
        return output.toByteArray()
    }
    companion object { private val locks=ConcurrentHashMap<String,Any>() }
}
