package cn.campuslife.assistant.daily
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ImageView
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
@SdkSuppress(minSdkVersion=29)
class Daily60ViewerTest {
 private val inst get()=InstrumentationRegistry.getInstrumentation()
 private fun nodes(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{nodes(v.getChildAt(it))} else emptyList()
 private fun shown(tag:String):View?=WindowInspector.getGlobalWindowViews().flatMap(::nodes).firstOrNull{it.tag==tag && it.isShown}
 private fun await(tag:String):View {val end=SystemClock.uptimeMillis()+4000;while(SystemClock.uptimeMillis()<end){var v:View?=null;inst.runOnMainSync{v=shown(tag)};if(v!=null)return v!!;SystemClock.sleep(20)};error("Missing native image viewer: $tag")}
 @Test fun longPressOpenStaysInsideAppInsteadOfAnExternalViewerOrShareSheet() {
  val helper=Daily60ScreenTest();var external=0
  val monitor=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult?{
   if(intent.action==Intent.ACTION_VIEW || intent.action==Intent.ACTION_SEND){external++;return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)};return null
  }}
  val cache=object:Daily60Cache{override fun read():Daily60Snapshot?=null;override fun write(snapshot:Daily60Snapshot){}}
  inst.addMonitor(monitor)
  try {helper.withClient(Daily60Client{Daily60DeviceImage.image()},cache){
   ActivityScenario.launch(Daily60Activity::class.java).use{s->
    helper.await(s){it.findViewWithTag<ImageView>("daily60_image")?.drawable!=null}
    s.onActivity{it.window.decorView.findViewWithTag<View>("daily60_image").performLongClick()}
    inst.waitForIdleSync()
    inst.runOnMainSync{val list=WindowInspector.getGlobalWindowViews().flatMap(::nodes).filterIsInstance<ListView>().single{it.isShown};list.performItemClick(null,1,list.adapter.getItemId(1))}
    await("daily60_zoom");assertEquals(0,external)
   }
  }} finally {inst.removeMonitor(monitor)}
 }
 @Test fun nativeViewerAcceptsRealTwoFingerZoomAndKeepsZoomAcrossRotation() {
  val helper=Daily60ScreenTest()
  val cache=object:Daily60Cache{override fun read():Daily60Snapshot?=null;override fun write(snapshot:Daily60Snapshot){}}
  helper.withClient(Daily60Client{Daily60DeviceImage.image()},cache){ActivityScenario.launch(Daily60Activity::class.java).use{s->
   helper.await(s){it.findViewWithTag<ImageView>("daily60_image")?.drawable!=null}
   s.onActivity{it.window.decorView.findViewWithTag<View>("daily60_image").performLongClick()}
   inst.waitForIdleSync();inst.runOnMainSync{val l=WindowInspector.getGlobalWindowViews().flatMap(::nodes).filterIsInstance<ListView>().single{it.isShown};l.performItemClick(null,1,l.adapter.getItemId(1))}
   val view=await("daily60_zoom") as Daily60ZoomView;var before=0f;var after=0f
   inst.runOnMainSync {
    before=view.zoom
    val time=SystemClock.uptimeMillis();val x=view.width/2f;val y=view.height/2f
    val props=Array(2){android.view.MotionEvent.PointerProperties().apply{id=it;toolType=android.view.MotionEvent.TOOL_TYPE_FINGER}}
    fun send(action:Int,span:Float,t:Long,count:Int=2){
     val coords=Array(2){android.view.MotionEvent.PointerCoords().apply{this.x=x+(if(it==0)-span else span);this.y=y;pressure=1f;size=1f}}
     val event=android.view.MotionEvent.obtain(time,time+t,action,count,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
     view.dispatchTouchEvent(event);event.recycle()
    }
    send(android.view.MotionEvent.ACTION_DOWN,30f,0,1)
    send(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),30f,20)
    repeat(8){send(android.view.MotionEvent.ACTION_MOVE,35f+it*15,50L+it*30)}
    send(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),140f,320)
    send(android.view.MotionEvent.ACTION_UP,140f,350,1)
    after=view.zoom;assertTrue("Pinch must enlarge the actual canvas",after>before*1.2f)
    val frame=view.frame();view.zoomBy(0.8f);assertTrue(view.zoom<after);view.zoomBy(1.25f);assertEquals(frame.zoom,view.zoom,0.01f)
   }
   s.recreate();val rotated=await("daily60_zoom") as Daily60ZoomView
   inst.runOnMainSync{assertEquals(after,rotated.zoom,0.1f)}
   helper.capture("native-zoom-synthetic.png")
  }}
 }
 @Test fun todaysCachedImageShowsWithoutNetworkAndOpeningAgainIsFast() {
  val helper=Daily60ScreenTest();var calls=0
  val cached=Daily60Snapshot(Daily60DeviceImage.image(),System.currentTimeMillis())
  val cache=object:Daily60Cache{override fun read()=cached;override fun write(snapshot:Daily60Snapshot){error("No rewrite")}}
  helper.withClient(Daily60Client{calls++;error("Fresh cache must avoid network")},cache){
   repeat(2){val start=SystemClock.uptimeMillis();ActivityScenario.launch(Daily60Activity::class.java).use{s->
    helper.await(s){it.findViewWithTag<ImageView>("daily60_image")?.drawable!=null}
    assertTrue("Cached synthetic first image should appear within one second",SystemClock.uptimeMillis()-start<1000)
   }}
  };assertEquals(0,calls)
 }

}
