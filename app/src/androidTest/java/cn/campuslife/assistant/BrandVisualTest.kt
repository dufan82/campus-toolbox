package cn.campuslife.assistant

import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.campuslife.assistant.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Native resources and synthetic/no-account UI; offline emulator only. */
class BrandVisualTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))}else emptyList()
    @Test fun visibleIdentityPaletteAndAdaptiveIconAreCorrect() {
        assertEquals("校伴",context.getString(R.string.app_name))
        assertEquals(Brand.navy,context.getColor(R.color.brand_navy))
        assertEquals(Brand.teal,context.getColor(R.color.brand_teal))
        assertEquals(Brand.orange,context.getColor(R.color.brand_orange))
        assertEquals(Brand.mist,context.getColor(R.color.brand_mist))
        val icon=context.packageManager.getApplicationIcon(context.applicationInfo)
        assertTrue(icon is AdaptiveIconDrawable)
        if(android.os.Build.VERSION.SDK_INT>=33)assertNotNull((icon as AdaptiveIconDrawable).monochrome)
        assertTrue(ColorUtils.calculateContrast(Color.WHITE,Ui.blue)>=4.5)
        assertTrue(ColorUtils.calculateContrast(Ui.muted,Ui.background)>=4.5)
    }
    @Test fun legacyAndAdaptive48PixelIconsKeepBothNodesAndNavyPath() {
        val dir=File(context.getExternalFilesDir("verification"),"brand").apply{mkdirs()}
        for((name,res)in listOf("legacy" to R.drawable.ic_app,"adaptive" to R.mipmap.ic_launcher)) {
            val drawable=context.getDrawable(res)!!
            val bitmap=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888);drawable.setBounds(0,0,48,48);drawable.draw(Canvas(bitmap))
            val pixels=IntArray(48*48);bitmap.getPixels(pixels,0,48,0,0,48,48)
            assertTrue("$name navy",pixels.count {it==Brand.navy}>10)
            assertTrue("$name teal",pixels.any {it==Brand.teal});assertTrue("$name orange",pixels.any {it==Brand.orange})
            File(dir,"icon-$name-48.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
    @Test fun compactHomeAndFixedExpressEntriesRemainVisible() {
        ActivityScenario.launch(MainActivity::class.java).use {s->
            s.onActivity {a->
                assertNotNull(a.window.decorView.findViewWithTag<View>("home_poem"))
                assertNull(a.window.decorView.findViewWithTag<View>("brand_name"))
                val nodes=views(a.window.decorView)
                listOf("拼多多包裹","拼多多身份码","抖音订单").forEach {label->assertTrue(nodes.filterIsInstance<TextView>().any {it.text.toString()==label})}
                val shortcuts=nodes.filter {it.tag?.toString()?.startsWith("shortcut_")==true}
                assertEquals(3,shortcuts.size)
                shortcuts.forEach {v->val bounds=Rect();assertTrue(v.getGlobalVisibleRect(bounds));assertEquals(v.height,bounds.height())}
                assertNotNull(a.window.decorView.findViewWithTag<View>("main_bottom_navigation"))
            };save("home")
        }
    }
    @Test fun aboutHasRealTextBrandVersionAndUnchangedRepositories() {
        ActivityScenario.launch(SettingsActivity::class.java).use {s->
            s.onActivity {a->a.window.decorView.findViewWithTag<View>("settings_about").performClick()}
            inst.waitForIdleSync()
            inst.runOnMainSync {
                val labels=WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<TextView>().map{it.text.toString()}
                listOf("校伴",Brand.tagline,Brand.positioning,Brand.english,"版本 ${BuildConfig.VERSION_NAME}",Brand.github,"https://cnb.cool/campus-toolbox/campus-toolbox").forEach {assertTrue(it,labels.contains(it))}
            };save("about")
        }
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion=31)
    @Test fun android12LaunchThemeAndMonochromeNotificationAreConfiguredWithoutDelay() {
        ActivityScenario.launch(MainActivity::class.java).use {scenario->scenario.onActivity {a->
            val launchTheme=a.resources.newTheme().apply { applyStyle(R.style.BrandLaunchTheme,true) }
            assertEquals(R.style.BrandLaunchTheme,a.packageManager.getActivityInfo(a.componentName,0).theme)
            val value=android.util.TypedValue()
            assertTrue(launchTheme.resolveAttribute(android.R.attr.windowSplashScreenAnimatedIcon,value,true))
            assertEquals(R.drawable.splash_logo,value.resourceId)
            assertTrue(launchTheme.resolveAttribute(android.R.attr.windowSplashScreenBackground,value,true))
            assertEquals(Brand.mist,value.data)
        }}
        val d=context.getDrawable(R.drawable.ic_notification)!!
        val bitmap=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888);d.setBounds(0,0,48,48);d.draw(Canvas(bitmap))
        val pixels=IntArray(48*48);bitmap.getPixels(pixels,0,48,0,0,48,48)
        assertTrue(pixels.any {Color.alpha(it)>0});assertEquals(0,Color.alpha(bitmap.getPixel(0,0)))
        assertTrue(pixels.filter{Color.alpha(it)>0}.all{(it and 0xffffff)==0xffffff})
        bitmap.recycle()
    }
    private fun save(name:String) {
        inst.waitForIdleSync();Thread.sleep(650)
        val mode=if(context.resources.configuration.fontScale>1.5f)"large"else"normal"
        val night=context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val dir=File(context.getExternalFilesDir("verification"),"brand").apply{mkdirs()};val bitmap=inst.uiAutomation.takeScreenshot()
        File(dir,"$name-$mode-$night-${bitmap.width}x${bitmap.height}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
