package cn.campuslife.assistant.daily

import android.content.Context
import android.util.AtomicFile
import java.io.*
import java.util.concurrent.ConcurrentHashMap

/** One atomic image+timestamp envelope; old text JSON is left untouched. Worker only. */
internal class Daily60Store(context:Context):Daily60Cache {
    private val file=AtomicFile(File(context.applicationContext.filesDir,"daily60-image-v1.bin"))
    private val lock=locks.getOrPut(file.baseFile.absolutePath){Any()}
    override fun read():Daily60Snapshot?=synchronized(lock){
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())return@synchronized null
        DataInputStream(BufferedInputStream(file.openRead())).use {input->
            require(input.readInt()==MAGIC && input.readInt()==1)
            val at=input.readLong();require(at>0)
            val mime=input.readUTF();require(mime.length<=20)
            val size=input.readInt();require(size in 24..Daily60Rules.MAX_BYTES)
            val bytes=ByteArray(size);input.readFully(bytes);require(input.read()==-1)
            val image=Daily60Rules.image(bytes,mime);Daily60Bitmap.decode(image).recycle()
            Daily60Snapshot(image,at)
        }
    }
    override fun write(snapshot:Daily60Snapshot):Unit=synchronized(lock){
        val image=Daily60Rules.image(snapshot.image.bytes,snapshot.image.mime);require(snapshot.savedAtMillis>0)
        val stream=file.startWrite()
        try {
            val out=DataOutputStream(stream)
            out.writeInt(MAGIC);out.writeInt(1);out.writeLong(snapshot.savedAtMillis);out.writeUTF(image.mime)
            out.writeInt(image.bytes.size);out.write(image.bytes);out.flush();file.finishWrite(stream)
        }catch(error:Exception){file.failWrite(stream);throw error}
    }
    companion object {private const val MAGIC=0x44363049;private val locks=ConcurrentHashMap<String,Any>()}
}
