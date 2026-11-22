package cn.campuslife.assistant.testing

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Instrumentation-only helpers: no production dependency or persisted App settings. */
object DeviceTestUi {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    fun <T> main(action:()->T):T {
        if(Looper.myLooper()==Looper.getMainLooper())return action()
        var result:Result<T>?=null
        inst.runOnMainSync {result=runCatching(action)}
        return checkNotNull(result).getOrThrow()
    }
    fun find(activity:Activity,tag:String,timeoutMs:Long=8000):View {
        require(timeoutMs>=0)
        if(Looper.myLooper()==Looper.getMainLooper())return activity.window.decorView.findViewWithTag<View>(tag)
            ?: throw AssertionError("Missing native view on main thread (no blocking wait): $tag")
        val end=SystemClock.uptimeMillis()+timeoutMs
        do {
            main {activity.window.decorView.findViewWithTag<View>(tag)}?.let {return it}
            if(SystemClock.uptimeMillis()>=end)break
            SystemClock.sleep(30)
        }while(true)
        throw AssertionError("Missing native view after ${timeoutMs}ms: $tag")
    }
    fun recreate(activity:Activity,timeoutMs:Long=8000):Activity {
        check(Looper.myLooper()!=Looper.getMainLooper()) {"Recreation must be awaited off the main thread"}
        val monitor=inst.addMonitor(activity.javaClass.name,null,false)
        try {
            main {activity.recreate()}
            return inst.waitForMonitorWithTimeout(monitor,timeoutMs) ?: throw AssertionError("Recreation timed out: ${activity.javaClass.simpleName}")
        }finally {inst.removeMonitor(monitor)}
    }
}

/** Restore only explicitly named files under the owning test directory, never a directory tree. */
class TestFilesSnapshot private constructor(private val root:File,private val originals:Map<String,ByteArray?>) {
    private fun owned(name:String):File {
        require(name.isNotBlank() && !File(name).isAbsolute && name.split('/','\\').none {it==".."}) {"Unsafe test-owned path"}
        val file=File(root,name).canonicalFile
        require(file.path.startsWith(root.path+File.separator) && (!file.exists() || file.isFile)) {"Path is outside test-owned files"}
        return file
    }
    fun restore() {
        val files=originals.mapValues {owned(it.key)} // validate every destination before writing any
        originals.forEach {(name,bytes)->val file=files.getValue(name)
            if(bytes==null) {if(file.exists())check(file.delete()) {"Cannot remove test-created file"}}
            else {file.parentFile?.mkdirs();file.writeBytes(bytes)}
        }
    }
    companion object {
        fun capture(root:File,names:List<String>):TestFilesSnapshot {
            val owner=root.canonicalFile
            require(owner.isDirectory && names.distinct().size==names.size)
            val snapshot=TestFilesSnapshot(owner,emptyMap())
            return TestFilesSnapshot(owner,names.associateWith {name->val file=snapshot.owned(name);if(file.exists())file.readBytes()else null})
        }
    }
}
