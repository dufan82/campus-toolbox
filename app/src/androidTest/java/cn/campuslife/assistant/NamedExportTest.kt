package cn.campuslife.assistant

import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.image.PhotoExporter
import cn.campuslife.assistant.image.PhotoLoader
import cn.campuslife.watermark.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NamedExportTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun sameNamedExportsHaveIndependentCachesAndGalleryEntries() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=29)
        val source=File(context.cacheDir,"capture/named-source.png").apply { parentFile!!.mkdirs() }
        val bitmap=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        val original=source.readBytes();val asset=PhotoLoader(context).loadFile(source)
        val baseName="测试班_测试同学_05_${System.nanoTime()}"
        val outputs=mutableListOf<android.net.Uri>()
        val caches=mutableListOf<File>()
        try {
            val exporter=PhotoExporter(context)
            val first=exporter.toCache(asset,listOf("姓名：测试同学"),WatermarkStyle(),ExportFormat.PNG,baseName=baseName).also { caches+=it }
            val firstBytes=first.readBytes()
            val second=exporter.toCache(asset,listOf("姓名：另一位测试同学"),WatermarkStyle(),ExportFormat.PNG,baseName=baseName).also { caches+=it }
            assertEquals("$baseName.png",first.name);assertEquals(first.name,second.name)
            assertNotEquals(first.canonicalPath,second.canonicalPath);assertArrayEquals(firstBytes,first.readBytes())
            val one=exporter.saveToGallery(first).also { outputs+=it };val two=exporter.saveToGallery(second).also { outputs+=it };assertNotEquals(one,two)
            for(uri in listOf(one,two)) context.contentResolver.query(uri,arrayOf(MediaStore.Images.Media.WIDTH,MediaStore.Images.Media.HEIGHT,MediaStore.Images.Media.MIME_TYPE),null,null,null)!!.use {
                assertTrue(it.moveToFirst());assertEquals(600,it.getInt(0));assertEquals(400,it.getInt(1));assertEquals("image/png",it.getString(2))
            }
            assertArrayEquals(original,source.readBytes())
        } finally {
            outputs.forEach { context.contentResolver.delete(it,null,null) }
            caches.forEach { it.delete();it.parentFile?.delete() }
            asset.preview.recycle();source.delete()
        }
    }
    @Test fun namingPreferencesStoreOnlyTheOrderedFieldIdentifiers() {
        val name="test_naming_${System.nanoTime()}";val store=LocalStore(context,name)
        store.saveNamingFields(listOf(FieldKey.NAME,FieldKey.SEAT_NUMBER,FieldKey.NAME,FieldKey.EVENT_NAME))
        assertEquals(listOf(FieldKey.NAME,FieldKey.SEAT_NUMBER),LocalStore(context,name).namingFields())
        assertEquals(setOf("naming_fields"),context.getSharedPreferences(name,0).all.keys)
        store.saveNamingFields(emptyList());assertTrue(LocalStore(context,name).namingFields().isEmpty())
    }
}
