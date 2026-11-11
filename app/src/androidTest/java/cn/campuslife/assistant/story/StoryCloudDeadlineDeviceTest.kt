package cn.campuslife.assistant.story

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.lang.reflect.InvocationTargetException

class StoryCloudDeadlineDeviceTest {
    @Test fun absoluteDeadlineCancelsSlowHeadersBeforeBodyReading() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use {server->
            val worker=Thread {
                runCatching {server.accept().use {socket->
                    socket.soTimeout=4000
                    val reader=socket.getInputStream().bufferedReader()
                    while(!reader.readLine().isNullOrEmpty()) { }
                    val output=socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nX-Slow: ".toByteArray());output.flush()
                    repeat(32) {Thread.sleep(50);output.write('x'.code);output.flush()}
                    output.write("\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray());output.flush()
                }}
            }.apply {isDaemon=true;start()}
            // Fallback lets the pre-fix build run the exact same slow-header regression.
            val client=try {StoryCloudClient::class.java.getConstructor(Context::class.java,Long::class.javaPrimitiveType).newInstance(context,250L)} catch(_:NoSuchMethodException){StoryCloudClient(context)}
            val method=StoryCloudClient::class.java.getDeclaredMethod("request",String::class.java,JSONObject::class.java,String::class.java,String::class.java).apply {isAccessible=true}
            val started=SystemClock.uptimeMillis()
            val error=try {method.invoke(client,"http://127.0.0.1:${server.localPort}/",JSONObject(),null,null);null} catch(e:InvocationTargetException){e.cause}
            val elapsed=SystemClock.uptimeMillis()-started
            assertTrue("Expected deadline, received $error after ${elapsed}ms",error is StoryCloudException && error.code=="TIMEOUT")
            assertTrue("Slow response headers held the I/O queue for ${elapsed}ms",elapsed<1200)
            worker.join(2000)
        }
    }
}
