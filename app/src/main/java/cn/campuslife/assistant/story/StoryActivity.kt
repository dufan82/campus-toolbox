package cn.campuslife.assistant.story

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.core.graphics.drawable.toDrawable
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
        val root=Ui.page(this); heading(root,"随记",true)
        if(StoryCloudConfig.configured) {
            refresh=Ui.textButton(this,"刷新") {refreshCloud(force=true)}.apply {tag="story_cloud_refresh"}

        }
        val body=body()
        val controls=Ui.row(this).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
        controls.addView(Spinner(this).apply {
            tag="story_filter"; contentDescription="随记分类"
            minimumHeight=Ui.dp(context,48)
            adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,listOf("全部")+StoryCategory.entries.map { it.label })
            setSelection(category?.ordinal?.plus(1) ?: 0)
            onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) { category=StoryCategory.entries.getOrNull(position-1); render() }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        },LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(CheckBox(this).apply {
            text="收藏"; tag="story_favorites_only"; textSize=15f; setTextColor(Ui.ink)
            minimumHeight=Ui.dp(context,48); isChecked=favoritesOnly
            setOnCheckedChangeListener { _,checked -> favoritesOnly=checked; render() }
        })
        if(::refresh.isInitialized)controls.addView(refresh)
        body.addView(controls)
        message=status("正在读取…").apply { tag="story_list_status" }; body.addView(message)
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
            result.fold({(items,next)->entries=items;nextCursor=next;render()}, {error(message,it);message.text="刷新失败，请重试"})
        }}
    }
    private fun render() {
        if (!::cards.isInitialized) return
        cards.removeAllViews()
        val visible=StoryRules.filter(entries,category,favoritesOnly,state.favorites)
        message.text=if(visible.isEmpty())"没有符合条件的随记" else ""
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
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("story_filter",category?.name); outState.putBoolean("story_favorites_only",favoritesOnly)
        super.onSaveInstanceState(outState)
    }
}
