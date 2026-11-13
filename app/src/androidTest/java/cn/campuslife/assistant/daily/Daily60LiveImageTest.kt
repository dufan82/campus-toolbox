package cn.campuslife.assistant.daily
import android.content.Context
import android.content.ContextWrapper
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
/** Explicitly opted-in public image GET only; never a school endpoint or a real account. */
class Daily60LiveImageTest {
 @Test fun livePublicPictureLoadsAndPersistsWithoutAJsonRequest() {
  assumeTrue(InstrumentationRegistry.getArguments().getString("daily60Live")=="true")
  val app=InstrumentationRegistry.getInstrumentation().targetContext
  val dir=File(app.cacheDir,"daily60-live-${java.util.UUID.randomUUID()}").apply{check(mkdirs())}
  val c=object:ContextWrapper(app){override fun getApplicationContext():Context=this;override fun getFilesDir()=dir}
  val store=Daily60Store(c);val calls=AtomicInteger();val tests=Daily60ScreenTest()
  try {
   tests.withClient(Daily60Client{
    calls.incrementAndGet()
    try{Daily60ApiClient().fetch()}catch(error:Exception){
     val evidence=File(app.getExternalFilesDir(null),"daily60-image-screens").apply{mkdirs()}
     File(evidence,"live-error.txt").writeText(error.stackTraceToString());throw error
    }
   },store){
    ActivityScenario.launch(Daily60Activity::class.java).use{s->
     tests.await(s){it.findViewWithTag<ImageView>("daily60_image")?.drawable!=null || it.findViewWithTag<android.widget.TextView>("daily60_status")?.text?.contains("暂无缓存图片")==true}
     s.onActivity{assertNotNull(it.window.decorView.findViewWithTag<android.widget.TextView>("daily60_status").text.toString(),it.window.decorView.findViewWithTag<ImageView>("daily60_image").drawable)}
     val snapshot=store.read()!!;assertTrue(snapshot.image.bytes.size>1000)
     s.recreate();tests.await(s){it.findViewWithTag<ImageView>("daily60_image")?.drawable!=null};assertEquals(1,calls.get())
     val evidence=File(app.getExternalFilesDir(null),"daily60-image-screens").apply{mkdirs()}
     File(evidence,"live-info.txt").writeText("mime=${snapshot.image.mime} bytes=${snapshot.image.bytes.size} sha256="+MessageDigest.getInstance("SHA-256").digest(snapshot.image.bytes).joinToString(""){"%02x".format(it)})
     tests.capture("image-live.png")
    }
   }
  } finally {assertTrue(dir.deleteRecursively())}
 }
 @Test fun actualImageColdAndCachedPageTimings() {
  assumeTrue(InstrumentationRegistry.getArguments().getString("daily60Live")=="true")
  val inst=InstrumentationRegistry.getInstrumentation();val app=inst.targetContext
  val dir=File(app.cacheDir,"daily60-speed-${java.util.UUID.randomUUID()}").apply{check(mkdirs())}
  val context=object:ContextWrapper(app){override fun getApplicationContext():Context=this;override fun getFilesDir()=dir}
  val store=Daily60Store(context);val calls=AtomicInteger();val tests=Daily60ScreenTest();val times=mutableListOf<Long>()
  try {tests.withClient(Daily60Client{calls.incrementAndGet();Daily60ApiClient().fetch()},store){
   repeat(2){val start=android.os.SystemClock.uptimeMillis()
    ActivityScenario.launch(Daily60Activity::class.java).use{s->
     tests.await(s){it.findViewWithTag<ImageView>("daily60_image")?.drawable!=null}
     times+=android.os.SystemClock.uptimeMillis()-start
     if(it==1){assertEquals(1,calls.get());assertTrue("Cached real image should open within one second",times.last()<1000)}
     tests.capture(if(it==0)"speed-cold-live.png" else "speed-cached-live.png")
    }
   }
  }
   val output=File(app.getExternalFilesDir(null),"daily60-image-screens").apply{mkdirs()}
   File(output,"speed-live.txt").writeText("coldFirstImageMs=${times[0]}\ncachedFirstImageMs=${times[1]}\nfetches=${calls.get()}\n")
  } finally {check(dir.deleteRecursively())}
 }

}
