package cn.campuslife.assistant.daily

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import cn.campuslife.assistant.ui.RetainedWork
import cn.campuslife.assistant.ui.Ui
import java.io.File

class Daily60Activity:Activity() {
    private class Screen(val feed:Daily60Session) {
        val media=RetainedWork<Daily60Activity>({Handler(Looper.getMainLooper()).post(it)})
        var latest=Daily60State();var bitmap:Bitmap?=null;var shown:Daily60Snapshot?=null
        var requested:Daily60Image?=null;var document:File?=null
        var viewerOpen=false;var viewerFrame:Daily60ZoomView.Frame?=null
    }
    private var configured:Daily60Repository?=null
    private var created=false
    private lateinit var screen:Screen
    private lateinit var refresh:Button
    private lateinit var status:TextView
    private lateinit var dates:TextView
    private lateinit var picture:ImageView
    private var menu:AlertDialog?=null
    private var viewer:android.app.Dialog?=null
    private var zoomView:Daily60ZoomView?=null
    private var useRuntime=false

    internal fun configureClient(client:Daily60Client,cache:Daily60Cache) {
        check(!created);configured=Daily60Repository(client,cache)
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);created=true
        useRuntime=configured==null
        val repository=configured ?: Daily60Runtime.repository(applicationContext)
        screen=lastNonConfigurationInstance as? Screen ?: Screen(Daily60Session(
            repository,
            {action->Handler(Looper.getMainLooper()).post {action()}}))
        if(useRuntime && screen.shown==null)repository.peek()?.let {snapshot->
            Daily60Runtime.preview(snapshot.image)?.let {bitmap->screen.bitmap=bitmap;screen.shown=snapshot}
        }
        configured=null
        val root=Ui.page(this);root.addView(Ui.header(this,"每日60秒"))
        val body=Ui.column(this).apply {setPadding(Ui.dp(context,16),Ui.dp(context,8),Ui.dp(context,16),Ui.dp(context,24))}
        val top=Ui.row(this)
        dates=Ui.text(this,"",13f,Ui.muted).apply {tag="daily60_dates"}
        top.addView(dates,LinearLayout.LayoutParams(0,-2,1f))
        refresh=Ui.textButton(this,"刷新") {if(screen.bitmap==null)screen.requested=null;screen.feed.refresh()}.apply {tag="daily60_refresh"}
        top.addView(refresh);body.addView(top)
        status=Ui.text(this,"",13f,Ui.muted).apply {tag="daily60_status";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
        body.addView(status);body.addView(Ui.space(this,10))
        picture=ImageView(this).apply {
            tag="daily60_image";adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER
            contentDescription="每日60秒图片，长按可保存或打开"
            isClickable=false;setOnLongClickListener {showImageActions();true}
        }
        body.addView(picture,LinearLayout.LayoutParams(-1,-2))
        screen.bitmap?.let {picture.setImageBitmap(it)}
        root.addView(Ui.scroll(this,body),LinearLayout.LayoutParams(-1,0,1f))
    }
    override fun onStart() {
        super.onStart()
        screen.media.bind(this){busy,_->refresh.isEnabled=!busy && !screen.latest.loading}
        screen.feed.bind(::render);screen.feed.open()
        if(screen.viewerOpen)showViewer()
    }
    private fun render(state:Daily60State) {
        if(isFinishing || isDestroyed)return
        screen.latest=state;refresh.isEnabled=!state.loading && !screen.media.busy
        status.text=if(state.loading)state.result.message ?: "正在获取图片…" else state.result.message.orEmpty()
        val snapshot=state.result.snapshot
        if(snapshot==null) {dates.text="";return}
        if(screen.shown?.image===snapshot.image) {
            dates.text=Daily60Rules.readingLabel(snapshot,state.result.cached);return
        }
        if(screen.requested===snapshot.image)return
        screen.requested=snapshot.image
        screen.media.submit("打开图片…",{(if(useRuntime)Daily60Runtime.preview(snapshot.image) else null) ?: Daily60Bitmap.decode(snapshot.image)},{result->
            result.onSuccess {bitmap->
                if(screen.latest.result.snapshot?.image===snapshot.image) {
                    screen.bitmap=bitmap;screen.shown=snapshot;picture.setImageBitmap(bitmap)
                    if(useRuntime)Daily60Runtime.remember(snapshot.image,bitmap)
                    render(screen.latest)
                }
            }.onFailure {status.text="图片无法打开，请点刷新重试"}
        },enqueue=true)
    }
    private fun showImageActions() {
        val snapshot=screen.shown ?: return
        if(screen.media.busy)return
        menu=AlertDialog.Builder(this).setItems(arrayOf("保存图片","打开图片")){_,which->
            imageAction(snapshot,save=which==0)
        }.show()
    }
    private fun imageAction(snapshot:Daily60Snapshot,save:Boolean) {
        if(!save){showViewer();return}
        val app=applicationContext
        screen.media.submit(if(save)"保存图片…" else "打开图片…",{
            val files=Daily60ImageFiles(app)
            val file=files.toSharedFile(snapshot.image.bytes,snapshot.image.mime,snapshot.savedAtMillis)
            val uri=if(save && Build.VERSION.SDK_INT>=29)files.saveToGallery(file,snapshot.image.mime) else null
            file to uri
        },{result->
            result.onSuccess {(file,uri)->
                if(save && uri!=null)Ui.toast(this,"已保存到相册")
                else if(save) {
                    screen.document=file
                    try {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(snapshot.image.mime).putExtra(Intent.EXTRA_TITLE,file.name),406)
                    }catch(_:Exception){screen.document=null;Ui.toast(this,"没有可用的保存位置选择器")}
                }
            }.onFailure {Ui.toast(this,if(save)"保存失败，请检查存储空间后重试" else "图片暂时无法打开")}
        })
    }
    private fun showViewer() {
        val bitmap=screen.bitmap ?: return
        if(viewer?.isShowing==true)return
        screen.viewerOpen=true
        val dialog=android.app.Dialog(this,android.R.style.Theme_Material_Light_NoActionBar)
        val root=Ui.column(this).apply {setBackgroundColor(android.graphics.Color.WHITE)}
        val toolbar=Ui.row(this).apply {setPadding(Ui.dp(context,8),0,Ui.dp(context,8),0)}
        val zoom=Daily60ZoomView(this,bitmap,screen.viewerFrame);zoomView=zoom
        toolbar.addView(Ui.textButton(this,"返回"){dialog.dismiss()})
        toolbar.addView(Ui.text(this,"图片",16f,Ui.blue),LinearLayout.LayoutParams(0,-2,1f))
        toolbar.addView(Ui.textButton(this,"−"){zoom.zoomBy(1/1.5f)}.apply {contentDescription="缩小图片"},LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)))
        toolbar.addView(Ui.textButton(this,"+"){zoom.zoomBy(1.5f)}.apply {contentDescription="放大图片"},LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)))
        root.addView(toolbar,LinearLayout.LayoutParams(-1,Ui.dp(this,56)))
        root.addView(zoom,LinearLayout.LayoutParams(-1,0,1f))
        dialog.setContentView(root)
        dialog.setOnDismissListener {screen.viewerFrame=zoom.frame();if(!isChangingConfigurations)screen.viewerOpen=false;viewer=null;zoomView=null}
        viewer=dialog;dialog.show();dialog.window?.setLayout(-1,-1)
    }
    @Deprecated("Platform document picker")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=406)return
        val file=screen.document ?: return;screen.document=null
        val uri=data?.data
        if(resultCode!=RESULT_OK || uri==null){Ui.toast(this,"已取消保存");return}
        val app=applicationContext
        screen.media.submit("保存图片…",{Daily60ImageFiles(app).copyToDocument(file,uri)},
            {result->Ui.toast(this,if(result.isSuccess)"图片已保存" else "保存失败，请换一个位置重试")},enqueue=true)
    }
    override fun onStop(){screen.feed.detach();screen.media.detach(this);super.onStop()}
    override fun onRetainNonConfigurationInstance():Any=screen
    override fun onDestroy() {
        menu?.dismiss();zoomView?.let {screen.viewerFrame=it.frame()};viewer?.dismiss();picture.setImageDrawable(null);screen.feed.detach();screen.media.detach(this)
        if(!isChangingConfigurations){screen.feed.close();screen.media.close()}
        super.onDestroy()
    }
}
