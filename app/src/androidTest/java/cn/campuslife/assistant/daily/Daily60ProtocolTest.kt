package cn.campuslife.assistant.daily
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
internal object Daily60DeviceImage {
 fun image():Daily60Image {
  val b=Bitmap.createBitmap(8,12,Bitmap.Config.ARGB_8888);b.eraseColor(android.graphics.Color.rgb(53,182,160))
  val out=ByteArrayOutputStream();assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));b.recycle()
  return Daily60Rules.image(out.toByteArray(),"image/png")
 }
}
class Daily60ProtocolTest {
 private fun isolated(run:(Context,File)->Unit) {
  val base=InstrumentationRegistry.getInstrumentation().targetContext
  val dir=File(base.cacheDir,"daily60-image-test-${java.util.UUID.randomUUID()}").apply{check(mkdirs())}
  val c=object:ContextWrapper(base){override fun getApplicationContext():Context=this;override fun getFilesDir()=dir}
  try{run(c,dir)}finally{assertTrue(dir.deleteRecursively())}
 }
 @Test fun originalImageAndTimestampRoundTripWithoutTouchingOldTextCache()=isolated {c,dir->
  val legacy=File(dir,"daily60-cache-v1.json").apply{writeText("synthetic legacy sentinel")};val before=legacy.readBytes()
  val image=Daily60DeviceImage.image();val saved=Daily60Snapshot(image,1794441600000)
  val store=Daily60Store(c);assertNull(store.read());store.write(saved)
  val read=Daily60Store(c).read()!!;assertArrayEquals(image.bytes,read.image.bytes);assertEquals(saved.savedAtMillis,read.savedAtMillis)
  assertArrayEquals(before,legacy.readBytes())
 }
 @Test fun failedRefreshAndInvalidWriteKeepThePreviousAtomicImageBytes()=isolated {c,dir->
  val store=Daily60Store(c);val old=Daily60Snapshot(Daily60DeviceImage.image(),1234);store.write(old)
  val file=File(dir,"daily60-image-v1.bin");val before=file.readBytes()
  val result=Daily60Repository(Daily60Client{throw java.io.IOException()},store).refresh(store.read())
  assertTrue(result.cached);assertArrayEquals(old.image.bytes,result.snapshot!!.image.bytes)
  assertThrows(Exception::class.java){store.write(Daily60Snapshot(Daily60Image("bad".toByteArray(),"image/png"),4567))}
  assertArrayEquals(before,file.readBytes())
 }
 @Test fun corruptEnvelopeCannotPretendToBeAnImage()=isolated {c,dir->
  val file=File(dir,"daily60-image-v1.bin").apply{writeBytes(ByteArray(36){0})};val before=file.readBytes()
  assertThrows(Exception::class.java){Daily60Store(c).read()};assertArrayEquals(before,file.readBytes())
 }
 @Test fun decodeIsBoundedAndNeverChangesOriginalBytes() {
  val image=Daily60DeviceImage.image();val before=image.bytes.copyOf();val b=Daily60Bitmap.decode(image)
  assertEquals(8,b.width);assertEquals(12,b.height);b.recycle();assertArrayEquals(before,image.bytes)
 }
}
