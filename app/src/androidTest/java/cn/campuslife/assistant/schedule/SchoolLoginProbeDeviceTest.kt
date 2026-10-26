package cn.campuslife.assistant.schedule

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SchoolLoginProbeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun publishedSchoolStoreUsesTokenInfoRatherThanUserProfileToken() {
        val setup = "localStorage.setItem('user',JSON.stringify({userInfo:{realname:'Synthetic',token:''},tokenInfo:{token:'school_token_123456789',refresh_token:''}}));"
        assertEquals("\"Bearer school_token_123456789\"", evaluate("https://m.fjpit.com/", setup))
    }

    @Test fun realWebViewReadsOnlySchoolUserTokenFromPersistedPiniaAndUniWrappers() {
        val scripts = listOf(
            "localStorage.setItem('user',JSON.stringify({tokenInfo:{token:'synthetic_token_123456'},userInfo:{realname:'not-to-export'}}));",
            "localStorage.setItem('user',JSON.stringify({type:'string',data:JSON.stringify({tokenInfo:{token:'synthetic_token_123456'}})}));",
            "window.uni={getStorageSync:function(key){if(key!=='user')throw Error();return {tokenInfo:{token:'synthetic_token_123456'}};}};"
        )
        scripts.forEach { setup ->
            assertEquals("\"Bearer synthetic_token_123456\"", evaluate("https://m.fjpit.com/", setup))
        }
        assertEquals("null", evaluate("https://m.fjpit.com/", "localStorage.removeItem('user');"))
        assertEquals("null", evaluate("https://m.fjpit.com/", "localStorage.setItem('user',JSON.stringify({tokenInfo:{token:''},userInfo:{token:'stale_profile_token'}}));"))
        assertEquals("null", evaluate("https://untrusted.invalid/", scripts.first()))
    }

    private fun evaluate(origin: String, setup: String): String {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        var web: WebView? = null
        instrumentation.runOnMainSync {
            web = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript("(function(){try { $setup } catch(e){} return null;})()") {
                            view.evaluateJavascript(SchoolLoginProbe.script) { value -> result.set(value); done.countDown() }
                        }
                    }
                }
                loadDataWithBaseURL(origin, "<html><body>Offline fixture</body></html>", "text/html", "UTF-8", null)
            }
        }
        try { assertTrue("WebView probe did not return", done.await(8, TimeUnit.SECONDS)); return result.get() }
        finally { instrumentation.runOnMainSync { web?.evaluateJavascript("localStorage.clear()", null); web?.destroy() } }
    }
}
