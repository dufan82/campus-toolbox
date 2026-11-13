package cn.campuslife.assistant.story

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.core.graphics.drawable.toDrawable
import cn.campuslife.assistant.ui.Ui

class StoryActivity : StoryScreen() {
    private var favoritesOnly=false
    private var entries=emptyList<Story>()
    private var state=StoryLocalState()
    private var generation=0
    private var nextCursor:String?=null
    private var cloudLoading=false
    private lateinit var refresh:Button
    private lateinit var pager:StoryPager
    private val cards=mutableListOf<LinearLayout>()
    private val messages=mutableListOf<TextView>()
    private val scrolls=mutableListOf<ScrollView>()
    private val tabLabels=mutableListOf<TextView>()
    private val tabLines=mutableListOf<View>()
    private var initialScrolls:IntArray?=null
    private var cloudError:String?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        favoritesOnly=savedInstanceState?.getBoolean("story_favorites_only") ?: false
        initialScrolls=savedInstanceState?.getIntArray("story_column_scrolls")
        val root=Ui.page(this); heading(root,"随记",true)
        if(StoryCloudConfig.configured) {
            refresh=Ui.textButton(this,"刷新") {refreshCloud(force=true)}.apply {tag="story_cloud_refresh"}

        }
        pager=StoryPager(this)
        val tabs=Ui.row(this).apply {tag="story_tabs"}
        StoryColumn.entries.forEachIndexed {index,column->
            val tab=Ui.column(this).apply {
                tag="story_tab_${column.key}";contentDescription=column.label;isClickable=true;isFocusable=true
                setOnClickListener {pager.select(index)}
            }
            val label=Ui.text(this,column.label,16f,bold=true).apply {
                gravity=android.view.Gravity.CENTER;minimumHeight=Ui.dp(context,48)
            }
            val line=View(this)
            tab.addView(label,LinearLayout.LayoutParams(-1,-2));tab.addView(line,LinearLayout.LayoutParams(-1,Ui.dp(this,3)))
            tabLabels.add(label);tabLines.add(line);tabs.addView(tab,LinearLayout.LayoutParams(0,-2,1f))
            val body=body()
            messages.add(status("正在读取…").apply {tag="story_list_status_${column.key}";body.addView(this)})
            cards.add(Ui.column(this).apply {body.addView(this)})
            val scroll=Ui.scroll(this,body).apply {tag="story_scroll_${column.key}";isSaveEnabled=false}
            scrolls.add(scroll);pager.addPage(scroll)
        }
        pager.onSelected={index->StoryColumn.entries.indices.forEach {i->
            tabs.getChildAt(i).isSelected=i==index
            tabLabels[i].setTextColor(if(i==index)Ui.ink else Ui.muted)
            tabLines[i].setBackgroundColor(if(i==index)Ui.teal else android.graphics.Color.TRANSPARENT)
        }}
        root.addView(tabs,LinearLayout.LayoutParams(-1,-2))
        pager.select(StoryColumn.fromKey(savedInstanceState?.getString("story_column") ?: intent.getStringExtra(EXTRA_COLUMN)).ordinal,false)
        val controls=Ui.row(this).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
        controls.gravity=android.view.Gravity.END
        controls.setPadding(Ui.dp(this,16),0,Ui.dp(this,16),0)
        controls.addView(CheckBox(this).apply {
            text="收藏"; tag="story_favorites_only"; textSize=15f; setTextColor(Ui.ink)
            minimumHeight=Ui.dp(context,48); isChecked=favoritesOnly
            setOnCheckedChangeListener { _,checked -> favoritesOnly=checked; render() }
        })
        if(::refresh.isInitialized)controls.addView(refresh)
        root.addView(controls,LinearLayout.LayoutParams(-1,-2))
        root.addView(pager,LinearLayout.LayoutParams(-1,0,1f))
    }
    override fun onResume() {
        super.onResume(); val request=++generation
        repository.load { result -> onUi {
            if (request==generation) result.fold({ (stories,saved) -> entries=stories; state=saved; render();if(StoryCloudConfig.configured)refreshCloud() },{cause->messages.forEach {error(it,cause)} })
        } }
    }
    override fun onNewIntent(intent:android.content.Intent) {
        super.onNewIntent(intent);setIntent(intent)
        intent.getStringExtra(EXTRA_COLUMN)?.let {pager.select(StoryColumn.fromKey(it).ordinal,false)}
    }
    private fun refreshCloud(cursor:String?=null,force:Boolean=false) {
        if(cloudLoading)return
        cloudLoading=true;if(::refresh.isInitialized)refresh.isEnabled=false
        repository.refresh(cursor,force) {result->onUi {
            cloudLoading=false;if(::refresh.isInitialized)refresh.isEnabled=true
            result.fold({(items,next)->entries=items;nextCursor=next;cloudError=null;render()}, {cloudError="刷新失败，请重试";render()})
        }}
    }
    private fun render() {
        if(cards.isEmpty())return
        StoryColumn.entries.forEachIndexed {index,column->renderColumn(index,column)}
        initialScrolls=null
    }
    private fun renderColumn(index:Int,column:StoryColumn) {
        val cards=cards[index];val message=messages[index];val scroll=scrolls[index]
        val savedY=initialScrolls?.getOrNull(index) ?: scroll.scrollY
        cards.removeAllViews()
        val visible=StoryRules.filter(entries.filter {StoryColumn.of(it)==column},null,favoritesOnly,state.favorites)
        message.text=when {
            visible.isNotEmpty()->""
            favoritesOnly->"还没有收藏"
            column==StoryColumn.SUBMISSIONS->cloudError ?: "还没有已发布的投稿"
            else->"暂时没有文章"
        }
        message.visibility=if (message.text.isEmpty()) View.GONE else View.VISIBLE
        visible.forEachIndexed { index, story ->
            if(index>0)cards.addView(View(this).apply {setBackgroundColor(Ui.line)},LinearLayout.LayoutParams(-1,Ui.dp(this,1)))
            val progress=((state.progress[story.id] ?: 0.0)*100).toInt()
            val card=Ui.column(this).apply {
                setPadding(0,Ui.dp(context,18),0,Ui.dp(context,18))
                background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x16172b49),android.graphics.Color.TRANSPARENT.toDrawable(),null)
                tag="story_card_${story.id}"; isClickable=true; isFocusable=true
                contentDescription="${story.title}，${story.category.label}，${story.readingTimeLabel}"+if(story.source==StoryCloudRules.CACHED_SOURCE)"，离线缓存" else ""
                setOnClickListener { startActivity(StoryReaderActivity.intent(this@StoryActivity,story.id)) }
            }
            card.addView(Ui.text(this,story.title,19f,bold=true)); card.addView(Ui.space(this,9))
            card.addView(Ui.text(this,story.summary,14f,Ui.muted).apply {maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END}); card.addView(Ui.space(this,12))
            card.addView(Ui.text(this,"${story.authorLabel} · ${story.readingTimeLabel}",12f,Ui.muted))
            val markers=buildList {
                if(story.source==StoryCloudRules.CACHED_SOURCE)add("离线缓存")
                if(story.provenance.archived)add("历史归档")
                if (story.id in state.favorites) add("已收藏")
                if (progress>0) add("读至${progress}%")
            }
            if (markers.isNotEmpty()) {
                card.addView(Ui.space(this,8))
                card.addView(Ui.text(this,markers.joinToString(" · "),12f,Ui.teal))
            }
            cards.addView(card,LinearLayout.LayoutParams(-1,-2).apply { topMargin=Ui.dp(this@StoryActivity,0) })
        }
        nextCursor?.let {cursor->cards.addView(Ui.textButton(this,if(cloudLoading)"正在加载…" else "加载更多") {refreshCloud(cursor)}.apply {tag="story_load_more";isEnabled=!cloudLoading})}
        scroll.post {scroll.scrollTo(0,savedY)}
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("story_column",StoryColumn.entries[pager.selected].key)
        outState.putIntArray("story_column_scrolls",scrolls.map {it.scrollY}.toIntArray())
        outState.putBoolean("story_favorites_only",favoritesOnly)
        super.onSaveInstanceState(outState)
    }
    companion object { const val EXTRA_COLUMN="cn.campuslife.assistant.story.COLUMN" }
}
