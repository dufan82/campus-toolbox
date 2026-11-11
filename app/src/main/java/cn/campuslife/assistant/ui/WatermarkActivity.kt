package cn.campuslife.assistant.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.content.FileProvider
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.assistant.image.*
import cn.campuslife.watermark.*
import java.io.File

class WatermarkActivity : Activity() {
    private lateinit var session: EditorSession
    private val store get()=session.store
    private lateinit var preview: WatermarkPreview
    private lateinit var panel: ScrollView
    private lateinit var panelHost: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button
    private lateinit var photoControls: LinearLayout
    private var exportDialog: ExportDialog?=null
    private val handler = Handler(Looper.getMainLooper())
    private var profile: CampusProfile get()=session.profile; set(value) { session.profile=value }
    private var details: ActivityDetails get()=session.details; set(value) { session.details=value }
    private var config: EditorConfig get()=session.config; set(value) { session.config=value }
    private var format: ExportFormat get()=session.format; set(value) { session.format=value }
    private var photo: PhotoAsset? get()=session.photo; set(value) { session.photo=value }
    private val busy get()=session.work.busy
    private var pendingCamera: File? get()=session.pendingCamera; set(value) { session.pendingCamera=value }
    private var pendingSave: File? get()=session.pendingSave; set(value) { session.pendingSave=value }
    private val saveDraft = Runnable { persist() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session=EditorSessions.acquire(applicationContext,savedInstanceState?.getString("session"))
        if(savedInstanceState!=null) {
            format=ExportFormat.entries.find { it.name==savedInstanceState.getString("format") } ?: format
            pendingCamera=pendingCamera ?: safeCacheFile(savedInstanceState.getString("camera"),"capture")
            pendingSave=pendingSave ?: safeCacheFile(savedInstanceState.getString("pendingSave"),"share")
            session.manualExportName=savedInstanceState.getString("manualExportName",session.manualExportName)
            savedInstanceState.getStringArrayList("namingFields")?.let { names ->
                session.namingFields=ExportNaming.fields(names.mapNotNull { name->FieldKey.entries.find { it.name==name } })
            }
            session.exportDialogOpen=savedInstanceState.getBoolean("exportDialogOpen",session.exportDialogOpen)
            session.exportShare=savedInstanceState.getBoolean("exportShare",session.exportShare)
        }
        buildPage()
        session.work.bind(this) { active,label-> setBusy(active,label) }
        if(photo==null && !session.restoreStarted) {
            session.restoreStarted=true
            store.draftName()?.let { name -> work("正在恢复上次照片…", {
                PhotoLoader(applicationContext).restore(name)
            },enqueue=true) { asset ->
                if (asset == null) { store.saveDraftName(null); updatePreview() } else applyPhoto(asset,false)
            } }
        }
        store.recoveryMessage?.let { Ui.toast(this,it) }
        preview.post { restoreExportDialog() }
    }

    private fun buildPage() {
        val root=Ui.page(this)
        val header=Ui.header(this,"照片水印")
        photoControls=Ui.row(this)
        photoControls.addView(Ui.textButton(this,"更换照片") {
            if(!busy) AlertDialog.Builder(this).setTitle("导入照片").setItems(arrayOf("从相册选择","拍照")) { _,index ->if(index==0)selectPhoto() else capturePhoto() }.setNegativeButton("取消",null).show()
        }.apply { tag="change_photo" })
        (header.getChildAt(0) as LinearLayout).addView(photoControls)
        root.addView(header)
        preview=WatermarkPreview(this).apply { tag="watermark_preview" }
        preview.onMoved={ x,y -> config=config.copy(style=config.style.copy(position=Position.CUSTOM,anchorX=x,anchorY=y)); changed() }
        val photoFrame=FrameLayout(this).apply {
            background=Ui.shape(context,Ui.line,16);clipToOutline=true
            addView(preview,FrameLayout.LayoutParams(-1,-1))
        }
        root.addView(photoFrame,LinearLayout.LayoutParams(-1,0,1f).apply {
            setMargins(Ui.dp(this@WatermarkActivity,16),Ui.dp(this@WatermarkActivity,12),Ui.dp(this@WatermarkActivity,16),0)
        })
        status=Ui.text(this,"选择照片和水印内容",12f,Ui.muted).apply {
            setPadding(Ui.dp(context,18),Ui.dp(context,8),Ui.dp(context,18),Ui.dp(context,8)); maxLines=2
        }
        root.addView(status)
        panelHost=Ui.column(this)
        // The photo owns the flexible area. Only the compact controls scroll on
        // short screens or with larger fonts; save/share never leave the footer.
        panel=object: ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                val cap=minOf(View.MeasureSpec.getSize(heightMeasureSpec),(resources.displayMetrics.heightPixels*.30f).toInt())
                super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(cap,View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(panelHost);isFillViewport=false }
        root.addView(panel,LinearLayout.LayoutParams(-1,-2))
        val footer=Ui.column(this).apply {
            tag="watermark_export_actions"
            setPadding(Ui.dp(context,18),Ui.dp(context,10),Ui.dp(context,18),Ui.dp(context,12))
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate=true; visibility=View.GONE }
        footer.addView(progress,LinearLayout.LayoutParams(-1,Ui.dp(this,4)))
        val actions=Ui.row(this)
        saveButton=Ui.button(this,"保存照片",true) { export(false) }.apply { tag="save_photo" }
        shareButton=Ui.iconButton(this,"share","分享照片") { export(true) }.apply { tag="share_photo";background=Ui.shape(context,android.graphics.Color.WHITE,12,Ui.line) }
        saveButton.minimumHeight=Ui.dp(this,52)
        actions.addView(saveButton,LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(shareButton,LinearLayout.LayoutParams(Ui.dp(this,52),Ui.dp(this,52)).apply { marginStart=Ui.dp(this@WatermarkActivity,10) })
        footer.addView(actions)
        root.addView(footer)
        showPanel(); updatePreview()
    }

    private fun showPanel() {
        if (!::panel.isInitialized) return
        panelHost.removeAllViews()
        panelHost.addView(ContentPanel(this,details,config,
            { details=it; changed() },{ config=it; changed() },{ openProfile() },{ config }))
        panel.scrollTo(0,0)
        if(busy) Ui.enabled(panelHost,false)
    }

    private fun changed() {
        updatePreview(); handler.removeCallbacks(saveDraft); handler.postDelayed(saveDraft,500)
    }
    private fun persist() {
        if (!::session.isInitialized) return
        try { store.saveConfig(config); store.saveDetails(details);store.saveProfile(profile) }
        catch(error: Exception) { Ui.toast(this,error.message ?: "本地设置保存失败，请重试") }
    }
    private fun texts()=formattedFields(profile,details,config)
    private fun updatePreview() {
        if (!::preview.isInitialized) return
        preview.exportFormat=format
        preview.update(photo,texts(),config)
        if(!busy) {
            val asset=photo
            status.setTextColor(if(preview.failure!=null) 0xffb91c1c.toInt() else Ui.muted)
            status.text=preview.failure ?: when {
                asset==null -> "选择照片后添加水印"
                texts().isEmpty() -> "${asset.width} × ${asset.height} px · 请添加水印内容"
                preview.layout?.reduced==true -> "内容较多，字号已缩小"
                else -> "${asset.width} × ${asset.height} px · 拖动调整位置"
            }
        }
        saveButton.isEnabled=!busy && photo!=null && preview.failure==null
        shareButton.isEnabled=saveButton.isEnabled
    }

    private fun selectPhoto() {
        if(busy) return
        try { startActivityForResult(PhotoPickerIntents.primary(Build.VERSION.SDK_INT),100) }
        catch(_: android.content.ActivityNotFoundException) {
            try { startActivityForResult(PhotoPickerIntents.fallback(),100) }
            catch(_: Exception) { Ui.toast(this,"未找到系统相册或文件选择器，请检查设备设置") }
        } catch(_: SecurityException) { Ui.toast(this,"无法打开系统照片选择器，请检查设备设置") }
    }
    private fun capturePhoto() {
        if(busy) return
        try {
            pendingCamera=File.createTempFile("capture_",".jpg",File(cacheDir,"capture").apply { mkdirs() })
            val uri=FileProvider.getUriForFile(this,"$packageName.files",pendingCamera!!)
            val intent=Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT,uri)
                clipData=ClipData.newRawUri("拍摄照片",uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            startActivityForResult(intent,101)
        } catch(_: SecurityException) { Ui.toast(this,"无法访问系统相机，请检查相机应用权限"); pendingCamera?.delete(); pendingCamera=null
        } catch(_: Exception) { Ui.toast(this,"未找到可用相机，请从相册选择照片"); pendingCamera?.delete(); pendingCamera=null }
    }
    private fun openProfile() { if(!busy) { persist(); startActivity(Intent(this,ProfileActivity::class.java)) } }

    @Deprecated("Uses system activity result API on Android 8+")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        when(requestCode) {
            100 -> if(resultCode==RESULT_OK && data?.data!=null) import(data.data!!) else Ui.toast(this,"已取消选择")
            101 -> {
                val captured=pendingCamera; pendingCamera=null
                if(resultCode==RESULT_OK && captured?.isFile==true) {
                    val uri=FileProvider.getUriForFile(this,"$packageName.files",captured)
                    work("正在读取拍摄照片…",{ try { PhotoLoader(applicationContext).importPhoto(uri) } finally { captured.delete() } },enqueue=true) { applyPhoto(it,true) }
                } else { captured?.delete(); Ui.toast(this,"已取消拍照") }
            }
            102 -> {
                val file=pendingSave; pendingSave=null
                if(resultCode==RESULT_OK && data?.data!=null && file!=null) {
                    work("正在保存照片…",{ try { PhotoExporter(applicationContext).copyToDocument(file,data.data!!) } finally { file.delete() } },enqueue=true) { Ui.toast(this,"照片已保存") }
                } else { file?.delete(); Ui.toast(this,"已取消保存") }
            }
        }
    }
    private fun import(uri: Uri) { work("正在读取照片…",{ PhotoLoader(applicationContext).importPhoto(uri) },enqueue=true) { applyPhoto(it,true) } }
    private fun applyPhoto(asset: PhotoAsset,newPhoto: Boolean) {
        val old=photo
        if(newPhoto) {
            details=details.copy(values=details.values+(FieldKey.CAPTURED_AT to asset.capturedAt.orEmpty()))
            try { DraftFiles.commit(asset.file,old?.file,PhotoLoader(this).draftFolder) { store.saveDraftName(it) } }
            catch(error: Exception) { Ui.toast(this,error.message ?: "草稿保存失败；先前草稿仍保留") }
        }
        photo=asset
        changed(); showPanel()
        restoreExportDialog()
        old?.preview?.takeIf { it !== asset.preview && !it.isRecycled }?.recycle()
    }

    private fun export(share: Boolean) {
        val asset=photo ?: return
        if(busy || preview.failure!=null || exportDialog?.isShowing==true) return
        val lines=texts()
        if(lines.isEmpty()) {
            AlertDialog.Builder(this).setTitle("尚未填写水印内容").setMessage("请在“更多字段”中选择内容，或填写资料和拍摄时间。")
                .setPositiveButton("去填写") { _,_-> openProfile() }.setNegativeButton("取消",null).show()
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(preview.windowToken,0)
        session.exportShare=share;session.exportDialogOpen=true
        showExportDialog()
    }

    private fun restoreExportDialog() {
        if(session.exportDialogOpen && photo!=null && !busy && exportDialog==null)showExportDialog()
    }
    private fun showExportDialog() {
        val asset=photo ?: return
        if(busy || exportDialog?.isShowing==true)return
        exportDialog=ExportDialog(this,asset,profile,details,session.namingFields,session.manualExportName,format,session.exportShare,
            { fields,manual,selectedFormat ->
                if(fields!=session.namingFields) {
                    session.namingFields=fields
                    try { store.saveNamingFields(fields) } catch(error: Exception) { Ui.toast(this,error.message ?: "命名选择保存失败") }
                }
                session.manualExportName=manual;format=selectedFormat;updatePreview()
            },
            { base,selectedFormat ->
                if(!busy) { format=selectedFormat;chooseExportSize(asset,texts(),session.exportShare,base) }
            }).also { dialog ->
            dialog.setOnDismissListener {
                if(!isChangingConfigurations)session.exportDialogOpen=false
                exportDialog=null
            }
            dialog.show()
        }
    }

    private fun chooseExportSize(asset: PhotoAsset,lines: List<String>,share: Boolean,baseName: String) {
        val fullSize=runCatching { ExportPolicy.plan(asset.rawWidth,asset.rawHeight,asset.orientation,format,(Runtime.getRuntime().maxMemory()*.65).toLong()) }
        if(fullSize.isSuccess) { generateExport(asset,lines,share,null,baseName); return }
        val options=listOf<Int?>(null,4000,2560,1600).distinctBy { edge -> if(edge==null) maxOf(asset.width,asset.height) else minOf(edge,maxOf(asset.width,asset.height)) }
        val labels=options.map { edge ->
            val factor=if(edge==null) 1.0 else minOf(1.0,edge.toDouble()/maxOf(asset.width,asset.height))
            val width=(asset.width*factor).toInt(); val height=(asset.height*factor).toInt()
            val available=runCatching { ExportPolicy.plan(asset.rawWidth,asset.rawHeight,asset.orientation,format,(Runtime.getRuntime().maxMemory()*.65).toLong(),edge) }.isSuccess
            "${if(edge==null) "原尺寸" else "适当缩小"} · $width × $height${if(available) "" else "（本机内存不足）"}"
        }
        AlertDialog.Builder(this).setTitle(if(share) "分享图片尺寸" else "保存图片尺寸").setItems(labels.toTypedArray()) { _,index ->
            generateExport(asset,lines,share,options[index],baseName)
        }.setNegativeButton("取消",null).show()
    }

    private fun generateExport(asset: PhotoAsset,lines: List<String>,share: Boolean,maxEdge: Int?,baseName: String) {
        if(busy) return
        val style=config.style; val selectedFormat=format
        work(if(share) "正在生成分享图片…" else "正在生成照片…",{
            val file=PhotoExporter(applicationContext).toCache(asset,lines,style,selectedFormat,maxEdge,baseName)
            if(!share && Build.VERSION.SDK_INT>=29) try { PhotoExporter(applicationContext).saveToGallery(file) } finally { file.delete() }
            file
        }) { file ->
            if(share) {
                try {
                    val uri=FileProvider.getUriForFile(this,"$packageName.files",file)
                    val intent=Intent(Intent.ACTION_SEND).apply {
                        type=if(file.extension=="png") "image/png" else "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM,uri); clipData=ClipData.newRawUri("水印照片",uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent,"分享水印照片"))
                } catch(_: Exception) { Ui.toast(this,"未找到可用的分享应用，请先保存照片") }
            } else if(Build.VERSION.SDK_INT>=29) {
                Ui.toast(this,"已保存到相册“校园生活助手”")
            } else {
                pendingSave=file
                try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type=if(file.extension=="png") "image/png" else "image/jpeg"
                    putExtra(Intent.EXTRA_TITLE,file.name)
                },102) } catch(_: Exception) { Ui.toast(this,"无法打开保存位置，请检查文件应用") }
            }
        }
    }

    private fun <T> work(label: String,action: ()->T,enqueue: Boolean=false,success: WatermarkActivity.(T)->Unit) {
        session.work.submit(label,action,{ result ->
                val activity=this
                result.fold({ success(activity,it) }) { error ->
                    val message=when(error) {
                        is OutOfMemoryError -> "本机可用内存不足，请选择较小照片后重试"
                        is SecurityException -> "文件访问或保存权限不足，请重新选择照片或保存位置"
                        else -> error.message ?: "图片处理失败，请重试"
                    }
                    Ui.toast(this,message); status.text=message; status.setTextColor(0xffb91c1c.toInt())
                }
        },enqueue)
    }
    private fun setBusy(value: Boolean,label: String="") {
        progress.visibility=if(value) View.VISIBLE else View.GONE
        Ui.enabled(photoControls,!value); Ui.enabled(panelHost,!value); preview.isEnabled=!value
        updatePreview()
        if(value) { status.text=label; status.setTextColor(Ui.blue) }
    }
    private fun safeCacheFile(path: String?,folder: String): File? = try {
        path?.let { File(it) }?.takeIf {
            val root=File(cacheDir,folder).canonicalFile
            val parent=it.canonicalFile.parentFile
            it.isFile && (parent==root || (folder=="share" && parent?.parentFile==root && parent.name.matches(Regex("job_[a-f0-9-]{36}"))))
        }
    } catch(_: Exception) { null }
    override fun onResume() {
        super.onResume()
        if(::session.isInitialized) { profile=store.profile(); config=store.config(); updatePreview(); showPanel() }
    }
    override fun onPause() { handler.removeCallbacks(saveDraft); persist(); super.onPause() }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("format",format.name)
        out.putString("session",session.id)
        out.putString("camera",pendingCamera?.absolutePath); out.putString("pendingSave",pendingSave?.absolutePath)
        out.putString("manualExportName",session.manualExportName)
        out.putStringArrayList("namingFields",ArrayList(session.namingFields.map { it.name }))
        out.putBoolean("exportDialogOpen",session.exportDialogOpen);out.putBoolean("exportShare",session.exportShare)
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() {
        handler.removeCallbacks(saveDraft)
        exportDialog?.dismiss()
        session.work.detach(this)
        if(isFinishing) EditorSessions.release(session)
        super.onDestroy()
    }
    @Deprecated("Handles both legacy back and the app toolbar")
    override fun onBackPressed() {
        if(busy) Ui.toast(this,"正在处理照片，请稍等片刻") else super.onBackPressed()
    }
}
