package cn.campuslife.assistant.daily

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException

internal object Daily60Bitmap {
    /** Worker only. Allocation is bounded; saving/opening still uses the untouched original bytes. */
    fun decode(image:Daily60Image):Bitmap {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.size,bounds)
        require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..24000 && bounds.outWidth.toLong()*bounds.outHeight<=16_000_000)
        var sample=1
        while(bounds.outWidth/sample>1800 || bounds.outHeight/sample>8192 || bounds.outWidth.toLong()/sample*(bounds.outHeight/sample)>4_000_000)sample*=2
        return try {
            BitmapFactory.decodeByteArray(image.bytes,0,image.bytes.size,BitmapFactory.Options().apply {inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})
                ?:throw IOException("图片解码失败")
        }catch(error:OutOfMemoryError){throw IOException("图片内存不足",error)}
    }
}
