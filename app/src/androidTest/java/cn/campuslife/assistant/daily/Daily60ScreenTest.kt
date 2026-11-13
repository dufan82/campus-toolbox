package cn.campuslife.assistant.daily
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
@SdkSuppress(minSdkVersion=29)
class Daily60ScreenTest {
 private val inst get()=InstrumentationRegistry.getInstrumentation()
 private class Cache(var value:Daily60Snapshot?):Daily60Cache{override fun read()=value;override fun write(snapshot:Daily60Snapshot){value=snapshot}}
 @Test fun imageIgnoresTapButLongPressOffersSaveAndOpenAndRotationDoesNotRefetch() {
  val count=AtomicInteger();val image=Daily60DeviceImage.image()
  withClient(Daily60Client{count.incrementAndGet();image},Cache(null)){
   ActivityScenario.launch(Daily60Activity::class.java).use {s->
    await(s){root->root.findViewWithTag<ImageView>("daily60_image")?.drawable!=null}
    val intents=mutableListOf<Intent>()
    val monitor=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
     if(intent.action==Intent.ACTION_VIEW){intents.add(intent);return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)};return null
    }}
    inst.addMonitor(monitor)
    try {
     s.onActivity {a->val pic=a.window.decorView.findViewWithTag<ImageView>("daily60_image");assertFalse(pic.isClickable);pic.performClick();assertTrue(intents.isEmpty());assertTrue(pic.performLongClick())}
     waitMenu()
     inst.runOnMainSync {
      val list=windows().filterIsInstance<ListView>().single {it.isShown}
      assertEquals(listOf("保存图片","打开图片"),(0 until list.adapter.count).map{list.adapter.getItem(it).toString()})
      list.performItemClick(null,1,list.adapter.getItemId(1))
     }
     val until=android.os.SystemClock.uptimeMillis()+5000
     var zoom=false
     while(!zoom && android.os.SystemClock.uptimeMillis()<until){inst.runOnMainSync{zoom=windows().any{it.tag=="daily60_zoom" && it.isShown}};android.os.SystemClock.sleep(20)}
     assertTrue("Native zoom viewer must open",zoom);assertTrue(intents.isEmpty())
     s.recreate();await(s){it.findViewWithTag<ImageView>("daily60_image")?.drawable!=null};assertEquals(1,count.get());assertTrue(intents.isEmpty())
     inst.runOnMainSync{assertTrue(windows().any{it.tag=="daily60_zoom" && it.isShown})}
     capture("image-synthetic.png")
    }finally{inst.removeMonitor(monitor)}
   }
  }
 }
 @Test fun offlineStillDisplaysCachedImageAndFailureDoesNotClaimUpdated() {
  val old=Daily60Snapshot(Daily60DeviceImage.image(),1586052000000)
  withClient(Daily60Client{throw IOException("synthetic offline")},Cache(old)){
   ActivityScenario.launch(Daily60Activity::class.java).use{s->
    await(s){r->text(r,"daily60_status").contains("已缓存图片") && r.findViewWithTag<ImageView>("daily60_image")?.drawable!=null}
    s.onActivity{assertTrue(text(it.window.decorView,"daily60_dates").contains("缓存图片"));assertFalse(text(it.window.decorView,"daily60_status").contains("已更新"))}
    capture("image-offline-synthetic.png")
   }
  }
 }
 @Test fun emptyFailureShowsReasonAndRapidRefreshDoesNotSpawnRepeatedRequests() {
  val count=AtomicInteger()
  withClient(Daily60Client{count.incrementAndGet();throw java.net.UnknownHostException()},Cache(null)){
   ActivityScenario.launch(Daily60Activity::class.java).use{s->
    await(s){text(it,"daily60_status").contains("网络连接失败")}
    s.onActivity{a->assertNull(a.window.decorView.findViewWithTag<ImageView>("daily60_image").drawable);repeat(6){a.window.decorView.findViewWithTag<View>("daily60_refresh").performClick()}}
    assertEquals(1,count.get())
   }
  }
 }
 internal fun withClient(client:Daily60Client,cache:Daily60Cache,run:()->Unit) {
  val cb=ActivityLifecycleCallback{a,stage->if(a is Daily60Activity && stage==Stage.PRE_ON_CREATE)a.configureClient(client,cache)}
  inst.runOnMainSync{ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(cb)}
  try{run()}finally{inst.runOnMainSync{ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(cb)}}
 }
 private fun waitMenu(){val until=android.os.SystemClock.uptimeMillis()+5000;while(android.os.SystemClock.uptimeMillis()<until){var ready=false;inst.runOnMainSync{ready=windows().filterIsInstance<ListView>().any{it.isShown}};if(ready)return;android.os.SystemClock.sleep(30)};fail("Image menu missing")}
 private fun windows()=WindowInspector.getGlobalWindowViews().flatMap(::nodes)
 private fun nodes(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{nodes(v.getChildAt(it))} else emptyList()
 private fun text(v:View,tag:String)=v.findViewWithTag<TextView>(tag)?.text?.toString().orEmpty()
 internal fun await(s:ActivityScenario<Daily60Activity>,condition:(View)->Boolean){val end=android.os.SystemClock.uptimeMillis()+30000;var last="";while(android.os.SystemClock.uptimeMillis()<end){var yes=false;s.onActivity{yes=condition(it.window.decorView);last=text(it.window.decorView,"daily60_status")};if(yes)return;android.os.SystemClock.sleep(30)};fail("Image not ready: $last")}
 internal fun capture(name:String){if(InstrumentationRegistry.getArguments().getString("daily60Capture")!="true")return;inst.waitForIdleSync();android.os.SystemClock.sleep(250);val bmp=inst.uiAutomation.takeScreenshot()?:error("No screenshot");val dir=File(inst.targetContext.getExternalFilesDir(null),"daily60-image-screens").apply{check(isDirectory||mkdirs())};File(dir,name).outputStream().use{assertTrue(bmp.compress(Bitmap.CompressFormat.PNG,100,it))};bmp.recycle()}
}
