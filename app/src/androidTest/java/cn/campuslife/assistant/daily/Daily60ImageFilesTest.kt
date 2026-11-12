package cn.campuslife.assistant.daily

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Entirely offline. Every file/row is created by this test and only those exact objects are deleted. */
class Daily60ImageFilesTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val savedAt=1794441600000L

    @Test fun tinyPngRemainsByteExactAndRepeatedTimestampDoesNotOverwrite() {
        val bytes=tinyPng()
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("Invalid synthetic PNG")
        try {assertEquals(1,bitmap.width);assertEquals(1,bitmap.height)} finally {bitmap.recycle()}
        val helper=Daily60ImageFiles(context)
        val first=helper.toSharedFile(bytes,"image/png",savedAt)
        var second:File?=null
        try {
            second=helper.toSharedFile(bytes,"image/png",savedAt)
            assertNotEquals(first.canonicalPath,second.canonicalPath)
            assertEquals(File(context.cacheDir,"share").canonicalFile,first.canonicalFile.parentFile)
            assertTrue(first.name.startsWith("daily60_"));assertTrue(first.name.endsWith(".png"))
            assertArrayEquals(bytes,first.readBytes());assertArrayEquals(bytes,second.readBytes())
        } finally {first.delete();second?.delete()}
    }

    @Test fun pngJpegAndWebpKeepTheirOriginalBytesAndMimeInViewIntent() {
        val helper=Daily60ImageFiles(context)
        for ((mime,extension) in listOf("image/png" to "png","image/jpeg" to "jpg","image/webp" to "webp")) {
            val bytes=if(mime=="image/png") tinyPng() else encodedPixel(mime)
            val file=helper.toSharedFile(bytes,mime,savedAt)
            try {
                assertEquals(extension,file.extension);assertArrayEquals(bytes,file.readBytes())
                val intent=helper.openIntent(file,mime)
                assertEquals(mime,intent.type)
                context.contentResolver.openInputStream(intent.data!!)!!.use {assertArrayEquals(bytes,it.readBytes())}
            } finally {file.delete()}
        }
    }

    @Test fun viewIntentCarriesReadOnlyContentUriAndClipDataWithoutLaunchingAnything() {
        var starts=0
        val noLaunch=object:ContextWrapper(context) {
            override fun startActivity(intent:Intent) {starts++;error("Helper must not launch a viewer")}
        }
        val helper=Daily60ImageFiles(noLaunch)
        val bytes=tinyPng();val file=helper.toSharedFile(bytes,"image/png",savedAt)
        try {
            val intent=helper.openIntent(file,"image/png")
            assertEquals(0,starts);assertEquals(Intent.ACTION_VIEW,intent.action)
            assertEquals("image/png",intent.type);assertEquals("content",intent.data!!.scheme)
            assertEquals("${context.packageName}.files",intent.data!!.authority)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertEquals(0,intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertNull(intent.component);assertNull(intent.`package`)
            assertEquals(1,intent.clipData!!.itemCount);assertEquals(intent.data,intent.clipData!!.getItemAt(0).uri)
            val info=context.packageManager.resolveContentProvider("${context.packageName}.files",0)!!
            assertFalse(info.exported);assertTrue(info.grantUriPermissions)
            context.contentResolver.query(intent.data!!,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)!!.use {
                assertTrue(it.moveToFirst());assertEquals(file.name,it.getString(0));assertEquals(bytes.size.toLong(),it.getLong(1))
            }
            assertArrayEquals(bytes,file.readBytes())
        } finally {file.delete()}
    }

    @Test fun unsupportedMimeAndEmptyImageAreRejectedBeforeCreatingSharedFiles() {
        val directory=File(context.cacheDir,"daily60-test-${UUID.randomUUID()}").apply {mkdirs()}
        val isolated=object:ContextWrapper(context) {override fun getCacheDir()=directory}
        try {
            val helper=Daily60ImageFiles(isolated)
            for (mime in listOf("text/html","image/gif","image/jpg","image/png; charset=utf-8",""))
                assertThrows(IllegalArgumentException::class.java) {helper.toSharedFile(tinyPng(),mime,savedAt)}
            assertThrows(IllegalArgumentException::class.java) {helper.toSharedFile(byteArrayOf(),"image/png",savedAt)}
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally {directory.delete()}
    }

    @Test fun cacheDirectoryFailureDoesNotRemoveExistingFiles() {
        val directory=File(context.cacheDir,"daily60-test-${UUID.randomUUID()}").apply {mkdirs()}
        val blocker=File(directory,"share").apply {writeText("synthetic existing file")}
        val isolated=object:ContextWrapper(context) {override fun getCacheDir()=directory}
        try {
            assertThrows(IOException::class.java) {Daily60ImageFiles(isolated).toSharedFile(tinyPng(),"image/png",savedAt)}
            assertEquals("synthetic existing file",blocker.readText())
        } finally {blocker.delete();directory.delete()}
    }

    @Test fun documentCopyTruncatesOnlyChosenOwnDestinationAndPreservesOriginal() {
        val helper=Daily60ImageFiles(context)
        val bytes=tinyPng();val source=helper.toSharedFile(bytes,"image/png",savedAt)
        var destination:File?=null
        try {
            destination=helper.toSharedFile(ByteArray(bytes.size+100) {42},"image/png",savedAt)
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",destination)
            helper.copyToDocument(source,uri)
            assertArrayEquals(bytes,destination.readBytes());assertArrayEquals(bytes,source.readBytes())
        } finally {source.delete();destination?.delete()}
    }

    @Test fun missingSourceCannotTruncateTheChosenDocument() {
        val helper=Daily60ImageFiles(context)
        val bytes=tinyPng();val destination=helper.toSharedFile(bytes,"image/png",savedAt)
        val missing=File(destination.parentFile,"daily60_missing_${UUID.randomUUID()}.png")
        try {
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",destination)
            assertThrows(IOException::class.java) {helper.copyToDocument(missing,uri)}
            assertArrayEquals(bytes,destination.readBytes())
        } finally {destination.delete()}
    }

    @Test @SdkSuppress(minSdkVersion=29)
    fun gallerySavePublishesOneByteExactPngWithNoPendingFlag() {
        val helper=Daily60ImageFiles(context)
        val bytes=tinyPng();val file=helper.toSharedFile(bytes,"image/png",savedAt)
        var created:Uri?=null
        try {
            val uri=helper.saveToGallery(file,"image/png");created=uri
            context.contentResolver.openInputStream(uri)!!.use {assertArrayEquals(bytes,it.readBytes())}
            val columns=arrayOf(MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.RELATIVE_PATH,MediaStore.Images.Media.IS_PENDING)
            context.contentResolver.query(uri,columns,null,null,null)!!.use {
                assertTrue(it.moveToFirst());assertEquals(file.name,it.getString(0));assertEquals("image/png",it.getString(1))
                assertEquals("Pictures/校伴/",it.getString(2).trimEnd('/')+"/");assertEquals(0,it.getInt(3))
            }
            assertArrayEquals(bytes,file.readBytes())
        } finally {
            try {created?.let {context.contentResolver.delete(it,null,null)}} finally {file.delete()}
        }
    }

    @Test @SdkSuppress(minSdkVersion=29)
    fun galleryFailureDeletesOnlyNewRowWithoutChangingTheSharedOriginal() = withProvider {helper,provider,file ->
        provider.failOpen=true
        assertThrows(IOException::class.java) {helper.saveToGallery(file,"image/png")}
        assertEquals(listOf(provider.created),provider.deleted)
        assertEquals(1,provider.inserted!!.getAsInteger(MediaStore.Images.Media.IS_PENDING).toInt())
        assertEquals(0,provider.finishes);assertTrue(provider.existingUntouched)
        assertArrayEquals(tinyPng(),file.readBytes())
    }

    @Test @SdkSuppress(minSdkVersion=29)
    fun finishingFailureAlsoRollsBackOnlyTheNewRow() = withProvider {helper,provider,file ->
        provider.finishCount=0
        assertThrows(IOException::class.java) {helper.saveToGallery(file,"image/png")}
        assertEquals(listOf(provider.created),provider.deleted);assertEquals(1,provider.finishes)
        assertArrayEquals(tinyPng(),provider.output.readBytes());assertArrayEquals(tinyPng(),file.readBytes())
        assertTrue(provider.existingUntouched)
    }

    @Test @SdkSuppress(minSdkVersion=29)
    fun cleanupFailureDoesNotHideOriginalOutputError() = withProvider {helper,provider,file ->
        provider.failOpen=true;provider.failDelete=true
        val error=assertThrows(IOException::class.java) {helper.saveToGallery(file,"image/png")}
        assertTrue(error.message.orEmpty().contains("synthetic output denied"))
        assertEquals(1,error.suppressed.size);assertEquals(listOf(provider.created),provider.deleted)
    }

    @Test @SdkSuppress(minSdkVersion=29)
    fun insertionFailureAndInvalidMimeDoNotDeleteAnyRow() = withProvider {helper,provider,file ->
        provider.nullInsert=true
        assertThrows(IOException::class.java) {helper.saveToGallery(file,"image/png")}
        assertTrue(provider.deleted.isEmpty());assertEquals(0,provider.opens)
        provider.inserted=null
        assertThrows(IllegalArgumentException::class.java) {helper.saveToGallery(file,"text/html")}
        assertThrows(IllegalArgumentException::class.java) {helper.openIntent(file,"image/gif")}
        assertNull(provider.inserted);assertTrue(provider.deleted.isEmpty())
    }

    @Test @SdkSuppress(minSdkVersion=29)
    fun documentOutputFailureIsPropagatedWithoutDeletingCallersDestination() = withProvider {helper,provider,file ->
        provider.failOpen=true
        assertThrows(IOException::class.java) {helper.copyToDocument(file,provider.created)}
        assertTrue(provider.deleted.isEmpty());assertArrayEquals(tinyPng(),file.readBytes())
    }

    @Test @SdkSuppress(minSdkVersion=29)
    fun documentBrokenPipeCannotBeReportedAsSuccessfulCopy() = withProvider {helper,provider,file ->
        provider.brokenPipe=true
        assertThrows(IOException::class.java) {helper.copyToDocument(file,provider.created)}
        assertTrue(provider.deleted.isEmpty());assertArrayEquals(tinyPng(),file.readBytes())
    }

    @SdkSuppress(minSdkVersion=29)
    private fun withProvider(run:(Daily60ImageFiles,RecordingProvider,File)->Unit) {
        val helper=Daily60ImageFiles(context);val file=helper.toSharedFile(tinyPng(),"image/png",savedAt)
        val output=File.createTempFile("daily60_provider_",".tmp",context.cacheDir)
        try {
            val provider=RecordingProvider(output)
            provider.attachInfo(context,ProviderInfo().apply {authority=provider.created.authority;exported=false})
            val resolver=ContentResolver.wrap(provider)
            val isolated=object:ContextWrapper(context) {override fun getContentResolver()=resolver}
            run(Daily60ImageFiles(isolated),provider,file)
        } finally {file.delete();output.delete()}
    }

    private class RecordingProvider(val output:File):ContentProvider() {
        // Same authority as the incoming gallery collection, but used ONLY by the local wrapped resolver.
        // This provider is not registered with Android and cannot touch actual MediaStore rows.
        val created:Uri=Uri.parse("content://media/external/images/media/60")
        val deleted=mutableListOf<Uri>()
        var inserted:ContentValues?=null;var opens=0;var finishes=0
        var failOpen=false;var failDelete=false;var nullInsert=false;var brokenPipe=false;var finishCount=1
        var existingUntouched=true
        override fun onCreate()=true
        override fun getType(uri:Uri)="image/png"
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
        override fun insert(uri:Uri,values:ContentValues?):Uri? {inserted=ContentValues(values);return if(nullInsert) null else created}
        override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int {
            assertEquals(created,uri);assertNull(selection);assertNull(selectionArgs)
            assertEquals(0,values!!.getAsInteger(MediaStore.Images.Media.IS_PENDING).toInt());finishes++;return finishCount
        }
        override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int {
            deleted.add(uri)
            if(uri!=created || selection!=null || selectionArgs!=null) {existingUntouched=false;error("Must only delete the newly inserted row")}
            if(failDelete) throw SecurityException("synthetic cleanup denied")
            return 1
        }
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            opens++;assertEquals(created,uri);assertEquals("wt",mode)
            if(failOpen) throw FileNotFoundException("synthetic output denied")
            if(brokenPipe) return ParcelFileDescriptor.createPipe().let {it[0].close();it[1]}
            return ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
    }

    /** Own 1x1 RGBA PNG, including correct chunk CRCs; no network/asset/private content. */
    private fun tinyPng():ByteArray {
        val bytes=ByteArrayOutputStream()
        val output=DataOutputStream(bytes)
        output.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(type:String,data:ByteArray) {
            val name=type.toByteArray(Charsets.US_ASCII)
            output.writeInt(data.size);output.write(name);output.write(data)
            val crc=CRC32().apply {update(name);update(data)}
            output.writeInt(crc.value.toInt())
        }
        val header=ByteArrayOutputStream().also {buffer ->
            DataOutputStream(buffer).use {it.writeInt(1);it.writeInt(1);it.write(byteArrayOf(8,6,0,0,0))}
        }.toByteArray()
        val pixels=ByteArrayOutputStream().also {buffer ->
            DeflaterOutputStream(buffer).use {it.write(byteArrayOf(0,18,52,86,255.toByte()))}
        }.toByteArray()
        chunk("IHDR",header);chunk("IDAT",pixels);chunk("IEND",byteArrayOf())
        output.flush();return bytes.toByteArray()
    }

    @Suppress("DEPRECATION")
    private fun encodedPixel(mime:String):ByteArray {
        val bitmap=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888).apply {eraseColor(0xff123456.toInt())}
        try {
            return ByteArrayOutputStream().also {
                assertTrue(bitmap.compress(if(mime=="image/jpeg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.WEBP,100,it))
            }.toByteArray()
        } finally {bitmap.recycle()}
    }
}
