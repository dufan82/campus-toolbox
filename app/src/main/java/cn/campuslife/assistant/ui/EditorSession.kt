package cn.campuslife.assistant.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.image.PhotoAsset
import cn.campuslife.watermark.*
import java.io.File
import java.util.UUID

class EditorSession(context: Context,val id: String) {
    val store=LocalStore(context.applicationContext)
    private val handler=Handler(Looper.getMainLooper())
    val work=RetainedWork<WatermarkActivity>({ handler.post(it) })
    var profile=store.profile()
    var details=store.details()
    var config=store.config()
    var format=ExportFormat.JPEG
    var photo: PhotoAsset?=null
    var selectedTab=0
    var pendingCamera: File?=null
    var pendingSave: File?=null
    var namingFields=store.namingFields()
    var manualExportName: String?=null
    var exportDialogOpen=false
    var exportShare=false
    var restoreStarted=false
}

object EditorSessions {
    private val sessions=mutableMapOf<String,EditorSession>()
    private var current: EditorSession?=null
    fun acquire(context: Context,id: String?): EditorSession {
        val existing=id?.let { sessions[it] } ?: current?.takeIf { it.work.busy }
        return (existing ?: EditorSession(context,id ?: UUID.randomUUID().toString()).also { sessions[it.id]=it }).also { current=it }
    }
    fun release(session: EditorSession) {
        if(session.work.busy) return
        session.work.close(); session.photo?.preview?.takeIf { !it.isRecycled }?.recycle()
        sessions.remove(session.id); if(current===session) current=null
    }
}
