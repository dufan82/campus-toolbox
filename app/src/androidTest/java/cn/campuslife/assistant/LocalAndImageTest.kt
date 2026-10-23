package cn.campuslife.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.image.PhotoExporter
import cn.campuslife.assistant.image.PhotoLoader
import cn.campuslife.assistant.image.WatermarkRenderer
import cn.campuslife.watermark.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalAndImageTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun profileConfigAndPersonalTemplateSurviveNewStoreInstance() {
        val store = LocalStore(context, "instrumentation_store")
        store.saveProfile(CampusProfile(mapOf(FieldKey.NAME to "测试同学", FieldKey.STUDENT_ID to "2026000999")))
        val config = EditorConfig(enabled = setOf(FieldKey.NAME), style = WatermarkStyle(backgroundOpacity = 0.27))
        store.saveConfig(config)
        store.saveTemplates(listOf(WatermarkTemplate("local-test", "班级活动", config)))
        val reopened = LocalStore(context, "instrumentation_store")
        assertEquals("测试同学", reopened.profile().values[FieldKey.NAME])
        assertEquals(0.27, reopened.config().style.backgroundOpacity, 0.0001)
        assertEquals(setOf(FieldKey.NAME), reopened.templates().single().config.enabled)
        assertFalse(context.getSharedPreferences("instrumentation_store", 0).getString("templates", "")!!.contains("2026000999"))
    }

    @Test fun damagedConfigFallsBackWithoutLosingProfile() {
        val store = LocalStore(context, "instrumentation_damaged")
        store.saveProfile(CampusProfile(mapOf(FieldKey.NAME to "保留姓名")))
        context.getSharedPreferences("instrumentation_damaged", 0).edit().putString("config", "{broken").commit()
        assertEquals(EditorConfig(), store.config())
        assertEquals("保留姓名", store.profile().values[FieldKey.NAME])
        assertNotNull(store.recoveryMessage)
    }

    @Test fun exifRotationChangesDimensionsAndPreservesCornerColors() {
        val file = fixture("rotation.jpg", 800, 600)
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
        val asset = PhotoLoader(context).loadFile(file)
        try {
            assertEquals(600, asset.width); assertEquals(800, asset.height)
            val pixel = asset.preview.getPixel(30, 30)
            assertTrue(Color.blue(pixel) > 180 && Color.red(pixel) < 80)
        } finally { asset.preview.recycle() }
    }

    @Test fun importAndExportKeepInputUnchangedAndOriginalResolution() {
        val source = fixture("original.jpg", 2400, 1600)
        val original = source.readBytes()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", source)
        val asset = PhotoLoader(context).importPhoto(uri)
        try {
            val config = EditorConfig(enabled = setOf(FieldKey.NAME))
            val lines = formattedFields(CampusProfile(mapOf(FieldKey.NAME to "导出测试")), ActivityDetails(), config)
            val exported = PhotoExporter(context).toCache(asset, lines, config.style, ExportFormat.PNG)
            val bitmap = android.graphics.BitmapFactory.decodeFile(exported.absolutePath)
            assertEquals(2400, bitmap.width); assertEquals(1600, bitmap.height)
            val layout = WatermarkRenderer.layout(asset.width, asset.height, lines, config.style)
            val x = (layout.box.left + layout.padding / 2).toInt()
            val y = (layout.box.top + layout.padding / 2).toInt()
            assertNotEquals(asset.preview.getPixel((x * asset.preview.width.toDouble() / asset.width).toInt(), (y * asset.preview.height.toDouble() / asset.height).toInt()), bitmap.getPixel(x, y))
            assertArrayEquals(original, source.readBytes())
            bitmap.recycle(); exported.delete()
        } finally { asset.preview.recycle() }
    }

    @Test fun previewAndExportRenderTheSameLayoutAtDifferentScales() {
        val source = fixture("matching.jpg", 2400, 1600)
        val asset = PhotoLoader(context).loadFile(source)
        try {
            val style = WatermarkStyle(position = Position.TOP_RIGHT, backgroundOpacity = 0.5)
            val lines = listOf("活动名称：校园服务", "学院：" + "数字媒体学院".repeat(8))
            val layout = WatermarkRenderer.layout(asset.width, asset.height, lines, style)
            val preview = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
            val previewCanvas = Canvas(preview)
            previewCanvas.scale(0.25f, 0.25f)
            WatermarkRenderer.drawWatermark(previewCanvas, layout, style)
            val full = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
            WatermarkRenderer.drawWatermark(Canvas(full), layout, style)
            val x = (layout.box.left + layout.padding / 2).toInt()
            val y = (layout.box.top + layout.padding / 2).toInt()
            assertEquals(Color.alpha(full.getPixel(x,y)), Color.alpha(preview.getPixel(x/4,y/4)))
            assertTrue(layout.box.right <= 2400 && layout.box.bottom <= 1600)
            preview.recycle(); full.recycle()
        } finally { asset.preview.recycle() }
    }

    @Test fun unsupportedImportLeavesNoPartialDraft() {
        val source=File(context.cacheDir,"capture/unsupported.txt").apply { parentFile!!.mkdirs(); writeText("not an image") }
        val loader=PhotoLoader(context)
        val before=loader.draftFolder.listFiles()!!.map { it.name }.toSet()
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",source)
        try { loader.importPhoto(uri); fail("Unsupported input was accepted") }
        catch(error: IllegalArgumentException) { assertTrue(error.message!!.contains("JPEG")) }
        assertEquals(before,loader.draftFolder.listFiles()!!.map { it.name }.toSet())
        assertEquals("not an image",source.readText())
    }

    @Test fun galleryFailureDoesNotModifyThePreparedExportOrOriginal() {
        val source=fixture("failed-save.jpg",1200,800)
        val before=source.readBytes()
        val asset=PhotoLoader(context).loadFile(source)
        try {
            val output=PhotoExporter(context).toCache(asset,listOf("活动名称：保存失败测试"),WatermarkStyle(),ExportFormat.JPEG)
            try {
                PhotoExporter(context).copyToDocument(output,android.net.Uri.parse("content://cn.campuslife.nonexistent/output"))
                fail("Missing output provider unexpectedly accepted the file")
            } catch(_: java.io.FileNotFoundException) { assertTrue(output.length()>0) }
            assertArrayEquals(before,source.readBytes()); output.delete()
        } finally { asset.preview.recycle() }
    }

    @Test fun transparentPngPreviewUsesTheSameWhiteMatteAsJpegExport() {
        val source=File(context.cacheDir,"capture/transparent.png").apply { parentFile!!.mkdirs() }
        val bitmap=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888).apply { eraseColor(0x80ff0000.toInt()) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        val asset=PhotoLoader(context).loadFile(source)
        try {
            val viewBitmap=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val view=cn.campuslife.assistant.ui.WatermarkPreview(context)
                view.layout(0,0,400,300); view.update(asset,emptyList(),EditorConfig()); view.draw(Canvas(viewBitmap))
            }
            val output=PhotoExporter(context).toCache(asset,emptyList(),WatermarkStyle(),ExportFormat.JPEG)
            val result=android.graphics.BitmapFactory.decodeFile(output.absolutePath)
            val expected=viewBitmap.getPixel(100,100); val actual=result.getPixel(100,100)
            assertTrue(kotlin.math.abs(Color.red(expected)-Color.red(actual))<5)
            assertTrue(kotlin.math.abs(Color.green(expected)-Color.green(actual))<5)
            assertTrue(kotlin.math.abs(Color.blue(expected)-Color.blue(actual))<5)
            viewBitmap.recycle(); result.recycle(); output.delete()
        } finally { asset.preview.recycle() }
    }

    @Test fun explicitSmallerExportDoesNotUseAPreviewScreenshot() {
        val source=fixture("chosen-size.jpg",4000,3000)
        val asset=PhotoLoader(context).loadFile(source)
        try {
            val output=PhotoExporter(context).toCache(asset,listOf("活动名称：缩小尺寸测试"),WatermarkStyle(),ExportFormat.PNG,2000)
            val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            android.graphics.BitmapFactory.decodeFile(output.absolutePath,bounds)
            assertEquals(2000,bounds.outWidth); assertEquals(1500,bounds.outHeight)
            assertEquals(1000,asset.preview.width)
            output.delete()
        } finally { asset.preview.recycle() }
    }

    private fun fixture(name: String, width: Int, height: Int): File {
        val folder = File(context.cacheDir, "capture").apply { mkdirs() }
        val file = File(folder, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = android.graphics.Paint()
        for ((color, rect) in listOf(
            Color.RED to android.graphics.Rect(0,0,width/2,height/2),
            Color.GREEN to android.graphics.Rect(width/2,0,width,height/2),
            Color.BLUE to android.graphics.Rect(0,height/2,width/2,height),
            Color.YELLOW to android.graphics.Rect(width/2,height/2,width,height))) {
            paint.color = color; canvas.drawRect(rect, paint)
        }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        bitmap.recycle()
        return file
    }
}
