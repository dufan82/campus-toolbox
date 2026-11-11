package cn.campuslife.assistant.story

import android.os.Bundle
import android.view.View
import android.widget.*
import cn.campuslife.assistant.ui.Ui

class StoryActivity : StoryScreen() {
    private var category: StoryCategory?=null
    private var favoritesOnly=false
    private var entries=emptyList<Story>()
    private var state=StoryLocalState()
    private var generation=0
    private var nextCursor:String?=null
    private var cloudLoading=false
    private lateinit var refresh:Button
    private lateinit var cards: LinearLayout
    private lateinit var message: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        category=StoryCategory.entries.firstOrNull { it.name==savedInstanceState?.getString("story_filter") }
        favoritesOnly=savedInstanceState?.getBoolean("story_favorites_only") ?: false
        val root=Ui.page(this); heading(root,"小故事",true)
        if(StoryCloudConfig.configured) {
            refresh=Ui.textButton(this,"刷新") {refreshCloud(force=true)}.apply {tag="story_cloud_refresh"}
            ((root.getChildAt(0) as LinearLayout).getChildAt(0) as LinearLayout).addView(refresh,2)
        }
        val body=body()
        body.addView(Spinner(this).apply {
            tag="story_filter"; contentDescription="故事分类"
            minimumHeight=Ui.dp(context,48)
            adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,listOf("全部分类")+StoryCategory.entries.map { it.label })
            setSelection(category?.ordinal?.plus(1) ?: 0)
            onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) { category=StoryCategory.entries.getOrNull(position-1); render() }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        },LinearLayout.LayoutParams(-1,-2))
        body.addView(CheckBox(this).apply {
            text="只看收藏"; tag="story_favorites_only"; textSize=15f; setTextColor(Ui.ink)
            minimumHeight=Ui.dp(context,48); isChecked=favoritesOnly
            setOnCheckedChangeListener { _,checked -> favoritesOnly=checked; render() }
        })
        message=status("正在读取故事…").apply { tag="story_list_status" }; body.addView(message)
        cards=Ui.column(this); body.addView(cards)
        root.addView(Ui.scroll(this,body),LinearLayout.LayoutParams(-1,0,1f))
    }
    override fun onResume() {
        super.onResume(); val request=++generation
        repository.load { result -> onUi {
            if (request==generation) result.fold({ (stories,saved) -> entries=stories; state=saved; render();if(StoryCloudConfig.configured)refreshCloud() },{ error(message,it) })
        } }
    }
    private fun refreshCloud(cursor:String?=null,force:Boolean=false) {
        if(cloudLoading)return
        cloudLoading=true;if(::refresh.isInitialized)refresh.isEnabled=false
        repository.refresh(cursor,force) {result->onUi {
            cloudLoading=false;if(::refresh.isInitialized)refresh.isEnabled=true
            result.fold({(items,next)->entries=items;nextCursor=next;render()}, {error(message,it);message.text="暂时无法刷新，已保留本机故事"})
        }}
    }
    private fun render() {
        if (!::cards.isInitialized) return
        cards.removeAllViews()
        val visible=StoryRules.filter(entries,category,favoritesOnly,state.favorites)
        val cachedCount=visible.count {it.source==StoryCloudRules.CACHED_SOURCE}
        message.text=when {visible.isEmpty()->"暂无符合条件的故事。";cachedCount>0->"保留 $cachedCount 篇已缓存故事供离线阅读，不代表本次在线分页结果。";else->""}
        message.visibility=if (message.text.isEmpty()) View.GONE else View.VISIBLE
        visible.forEach { story ->
            val progress=((state.progress[story.id] ?: 0.0)*100).toInt()
            val card=Ui.card(this).apply {
                tag="story_card_${story.id}"; isClickable=true; isFocusable=true
                contentDescription="${story.title}，${story.category.label}，${story.readingMinutes}分钟"+if(story.source==StoryCloudRules.CACHED_SOURCE)"，已缓存，非当前在线分页结果" else ""
                setOnClickListener { startActivity(StoryReaderActivity.intent(this@StoryActivity,story.id)) }
            }
            card.addView(Ui.text(this,story.title,18f,bold=true)); card.addView(Ui.space(this,9))
            card.addView(Ui.text(this,story.summary,14f,Ui.muted)); card.addView(Ui.space(this,12))
            card.addView(Ui.text(this,"${story.category.label} · ${story.authorLabel} · 约${story.readingMinutes}分钟",12f,Ui.muted))
            val markers=buildList {
                if(story.source==StoryCloudRules.CACHED_SOURCE)add(StoryCloudRules.CACHED_SOURCE)
                if (story.id in state.favorites) add("已收藏")
                if (progress>0) add("读至${progress}%")
            }
            if (markers.isNotEmpty()) {
                card.addView(Ui.space(this,8))
                card.addView(Ui.text(this,markers.joinToString(" · "),12f,Ui.teal))
            }
            cards.addView(card,LinearLayout.LayoutParams(-1,-2).apply { topMargin=Ui.dp(this@StoryActivity,12) })
        }
        nextCursor?.let {cursor->cards.addView(Ui.textButton(this,if(cloudLoading)"正在加载…" else "加载更多") {refreshCloud(cursor)}.apply {tag="story_load_more";isEnabled=!cloudLoading})}
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("story_filter",category?.name); outState.putBoolean("story_favorites_only",favoritesOnly)
        super.onSaveInstanceState(outState)
    }
}
