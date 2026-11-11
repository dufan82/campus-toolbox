package cn.campuslife.assistant.story

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*
import cn.campuslife.assistant.ui.Ui

class StoryReaderActivity : StoryScreen() {
    /** Instance-local seam; retained only with this reader, never a static/Intent override. */
    internal interface Loader {
        fun load(complete:(Result<Pair<List<Story>,StoryLocalState>>)->Unit)
        fun detail(id:String,complete:(Result<Story>)->Unit)
        fun change(transform:(StoryLocalState)->StoryLocalState,complete:(Result<StoryLocalState>)->Unit)
    }
    private class RepositoryLoader(private val source:StoryRepository):Loader {
        override fun load(complete:(Result<Pair<List<Story>,StoryLocalState>>)->Unit) {source.load(complete)}
        override fun detail(id:String,complete:(Result<Story>)->Unit) {source.detail(id,complete)}
        override fun change(transform:(StoryLocalState)->StoryLocalState,complete:(Result<StoryLocalState>)->Unit) {source.change(transform,complete)}
    }
    private lateinit var loader:Loader
    private var entries=emptyList<Story>()
    private var state=StoryLocalState()
    private var current: Story?=null
    private var position=0.0
    private var fontSp=17
    private var ready=false
    private var restoring=false
    private var layoutGeneration=0
    private var requestGeneration=0
    private var targetId:String?=null
    private var targetPosition:Double?=null
    private var loadingStory=false
    private var stateLoaded=false
    private var restored: Bundle?=null
    private lateinit var content: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var message: TextView
    private lateinit var bodyText: TextView
    private lateinit var favorite: Button
    private lateinit var fontChoice: Spinner
    private var nextStory:Button?=null
    private val saveTask=Runnable { savePosition() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); restored=savedInstanceState
        loader=(lastNonConfigurationInstance as? Loader) ?: RepositoryLoader(repository)
        targetId=savedInstanceState?.getString("story_current_id") ?: intent.getStringExtra(EXTRA_STORY_ID)
        targetPosition=if(savedInstanceState?.containsKey("story_position")==true) StoryRules.progress(savedInstanceState.getDouble("story_position")) else null
        val root=Ui.page(this)
        val header=Ui.header(this,"随记")
        val toolbar=header.getChildAt(0) as LinearLayout
        fontChoice=Spinner(this).apply {
            tag="story_font_size"; contentDescription="阅读字号"; minimumHeight=Ui.dp(context,48)
            adapter=object:ArrayAdapter<String>(context,android.R.layout.simple_spinner_dropdown_item,listOf("较小","标准","较大")) {
                override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View =
                    (super.getView(position,convertView,parent) as TextView).apply {
                        textSize=14f;setPadding(0,0,0,0);minimumHeight=Ui.dp(context,48);gravity=android.view.Gravity.CENTER_VERTICAL
                    }
            }
            setSelection(1);isEnabled=false
            onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?,view: View?,index: Int,id: Long) {
                    val next=index+16
                    if (ready && next!=fontSp && ::bodyText.isInitialized) {
                        fontSp=next;val savedPosition=position;restoring=true;bodyText.textSize=fontSp.toFloat()
                        restoreScroll(savedPosition)
                        val request=requestGeneration;val size=fontSp
                        loader.change({it.copy(fontSp=size)}) {result->onUi {if(request==requestGeneration && result.isFailure)error(message,result.exceptionOrNull())}}
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        toolbar.addView(fontChoice,LinearLayout.LayoutParams(Ui.dp(this,112),-2))
        favorite=Ui.textButton(this,"收藏") {
            if(!ready)return@textButton
            val selected=current ?: return@textButton
            val request=requestGeneration
            favorite.isEnabled=false
            loader.change({old->old.copy(favorites=if(selected.id in old.favorites)old.favorites-selected.id else old.favorites+selected.id)}) {result->onUi {
                if(request!=requestGeneration || !ready)return@onUi
                favorite.isEnabled=true
                result.fold({updated->state=updated;favorite.text=if(selected.id in updated.favorites)"已收藏" else "收藏"},{error(message,it)})
            }}
        }.apply {tag="story_favorite";isEnabled=false}
        toolbar.addView(favorite);root.addView(header)
        content=body(); message=status("正在读取…"); content.addView(message)
        scroller=Ui.scroll(this,content).apply { tag="story_reader_scroll"; isSaveEnabled=false }
        root.addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
        scroller.setOnScrollChangeListener { _: View, _: Int, y: Int, _: Int, _: Int ->
            if (ready && !restoring && current?.hasFullText==true) {
                val range=(content.height-scroller.height+scroller.paddingTop+scroller.paddingBottom).coerceAtLeast(0)
                position=if (range==0) 1.0 else StoryRules.progress(y.toDouble()/range)
                main.removeCallbacks(saveTask); main.postDelayed(saveTask,500)
            }
        }
        loadReader()
    }
    override fun onRetainNonConfigurationInstance():Any=loader
    internal fun useLoaderForTesting(loader:Loader) {
        check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
        if(ready)targetPosition=position
        this.loader=loader
        loadReader()
    }
    private fun loadReader() {
        val request=++requestGeneration
        ready=false;loadingStory=false;stateLoaded=false
        main.removeCallbacks(saveTask);layoutGeneration++
        nextStory?.isEnabled=false;fontChoice.isEnabled=false;favorite.isEnabled=false
        loader.load { result -> onUi {
            if(request!=requestGeneration)return@onUi
            result.fold({ (stories,saved) ->
                entries=stories; state=saved;stateLoaded=true
                fontSp=StoryRules.fontSize(restored?.getInt("story_font",saved.fontSp) ?: saved.fontSp)
                val id=targetId
                val ratio=targetPosition ?: saved.progress[id] ?: 0.0
                if(id==null)unavailable() else openStory(id,ratio)
            },{ error(message,it) })
        } }
    }
    private fun unavailable() {
        message.text="这篇随记暂不可用。"
        content.addView(Ui.button(this,"返回列表") {returnToList()}.apply {tag="story_back_list_unknown"})
    }
    internal fun openStory(id:String,ratio:Double) {
        savePosition()
        val request=++requestGeneration
        targetId=id;targetPosition=StoryRules.progress(ratio)
        ready=false;restoring=false;loadingStory=false;current=null
        main.removeCallbacks(saveTask);layoutGeneration++
        nextStory?.isEnabled=false;fontChoice.isEnabled=false;favorite.isEnabled=false
        content.removeAllViews()
        message=status("正在读取…").apply {tag="story_reader_status"};content.addView(message)
        val listed=entries.firstOrNull {it.id==id}
        val remote=listed?.remoteId ?: id
        if(!StoryCloudRules.cloudId(remote)) {
            entries.firstOrNull {it.id==id}?.let {display(it,ratio)} ?: unavailable();return
        }
        loadingStory=true
        loader.detail(remote) {result->onUi {
            if(request!=requestGeneration || targetId!=id || !loadingStory)return@onUi
            loadingStory=false
            result.mapCatching {
                check(it.id==remote && !it.provenance.hidden) {"作品响应与请求不匹配"}
                it.copy(id=id,remoteId=remote.takeIf {key->key!=id},provenance=it.provenance.copy(editorialRank=listed?.provenance?.editorialRank ?: it.provenance.editorialRank))
            }.fold({story->
                entries=entries.map {if(it.id==id)story else it};display(story,ratio)
            },{error(message,it);content.addView(Ui.button(this,"返回列表") {returnToList()})})
        }}
    }
    private fun display(story: Story,ratio: Double) {
        ready=false; main.removeCallbacks(saveTask); current=story; position=StoryRules.progress(ratio)
        targetId=story.id;targetPosition=position
        content.removeAllViews()
        content.addView(Ui.text(this,story.title,24f,bold=true).apply { tag="story_reader_title" })
        content.addView(Ui.space(this,12))
        val attribution=Ui.row(this).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
        attribution.addView(Ui.text(this,story.authorLabel,13f,Ui.muted).apply {tag="story_reader_author"},LinearLayout.LayoutParams(0,-2,1f))
        story.provenance.link?.let {url->attribution.addView(Ui.textButton(this,"查看原文 ↗") {StorySourceLauncher.open(this,url)}.apply {tag="story_source_link"})}
        content.addView(attribution)
        content.addView(Ui.text(this,story.readingTimeLabel,12f,Ui.muted))
        if(story.provenance.commentLocator.isNotBlank())content.addView(Ui.text(this,story.provenance.commentLocator,12f,Ui.muted).apply {tag="story_comment_locator"})
        fontChoice.setSelection(fontSp-16);fontChoice.isEnabled=true
        favorite.text=if(story.id in state.favorites) "已收藏" else "收藏";favorite.isEnabled=true
        content.addView(Ui.space(this,22))
        message=status("").apply { tag="story_reader_status" }; content.addView(message)
        if(story.privateReview || !story.hasFullText) {
            content.addView(Ui.text(this,if(story.privateReview)"私人稿" else if(!story.provenance.sourceVerified)"原文待核实" else if(story.provenance.allowsFullText)"正文暂未收录" else "全文待授权",12f,Ui.muted).apply {tag="story_rights_notice"})
            content.addView(Ui.space(this,12))
        }
        bodyText=Ui.text(this,story.readableBody.ifBlank {story.summary},fontSp.toFloat()).apply {
            tag="story_reader_body"; setTextIsSelectable(true); setLineSpacing(Ui.dp(context,4).toFloat(),1.35f)
        }
        content.addView(bodyText); content.addView(Ui.space(this,24))
        if(story.provenance.licenseName.isNotBlank() && story.provenance.licenseUrl.isNotBlank()) {
            content.addView(Ui.textButton(this,story.provenance.licenseName) {StorySourceLauncher.open(this,story.provenance.licenseUrl)}.apply {tag="story_license_link"})
        }
        content.addView(Ui.text(this,StoryProvenance.COPYRIGHT,11f,Ui.muted).apply {tag="story_copyright"})
        content.addView(Ui.space(this,12))
        val navigation=Ui.row(this)
        val previous=Ui.button(this,"上一篇") {
            if(ready && !loadingStory)StoryRules.adjacent(entries,story.id,-1)?.let {openStory(it.id,state.progress[it.id] ?: 0.0)}
        }.apply {tag="story_previous";isEnabled=StoryRules.adjacent(entries,story.id,-1)!=null}
        navigation.addView(previous,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=Ui.dp(this@StoryReaderActivity,8)})
        nextStory=Ui.button(this,"下一篇") {
            if(!ready || loadingStory)return@button
            StoryRules.adjacent(entries,story.id,1)?.let { openStory(it.id,state.progress[it.id] ?: 0.0) }
        }.apply { tag="story_next";isEnabled=StoryRules.adjacent(entries,story.id,1)!=null }
        navigation.addView(nextStory,LinearLayout.LayoutParams(0,-2,1f));content.addView(navigation)
        content.addView(Ui.space(this,8))
        content.addView(Ui.textButton(this,"返回列表") { returnToList() }.apply { tag="story_back_list" })
        ready=true; restoreScroll(position)
    }
    private fun restoreScroll(ratio: Double) {
        restoring=true
        val generation=++layoutGeneration
        // Wait for text reflow, including large system-font and rotation changes.
        content.viewTreeObserver.addOnGlobalLayoutListener(object: ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (content.viewTreeObserver.isAlive) content.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (isDestroyed || generation!=layoutGeneration) return
                val range=(content.height-scroller.height+scroller.paddingTop+scroller.paddingBottom).coerceAtLeast(0)
                scroller.scrollTo(0,(range*ratio).toInt())
                position=if (range==0 && current?.hasFullText==true) 1.0 else StoryRules.progress(ratio)
                restoring=false
            }
        })
        content.requestLayout()
    }
    private fun savePosition() {
        if (!ready || current?.hasFullText!=true) return
        val id=current?.id ?: return
        val ratio=position; val size=fontSp
        val request=requestGeneration
        state=state.copy(progress=state.progress+(id to ratio),fontSp=size)
        loader.change({ old -> old.copy(progress=old.progress+(id to ratio),fontSp=size) }) { result -> onUi {
            if (request==requestGeneration && result.isFailure) error(message,result.exceptionOrNull())
        } }
    }
    private fun returnToList() {
        savePosition()
        startActivity(Intent(this,StoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
    override fun onPause() { main.removeCallbacks(saveTask); savePosition(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        if (!stateLoaded) restored?.let(outState::putAll)
        targetId?.let {id->
            outState.putString("story_current_id",id)
            val ratio=if(ready && current?.id==id)position else targetPosition
            ratio?.let {outState.putDouble("story_position",it)}
        }
        if(stateLoaded)outState.putInt("story_font",fontSp)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { requestGeneration++;layoutGeneration++;main.removeCallbacks(saveTask);super.onDestroy() }
    companion object {
        const val EXTRA_STORY_ID="cn.campuslife.assistant.story.STORY_ID"
        fun intent(context: Context,storyId: String)=Intent(context,StoryReaderActivity::class.java).putExtra(EXTRA_STORY_ID,storyId)
    }
}
