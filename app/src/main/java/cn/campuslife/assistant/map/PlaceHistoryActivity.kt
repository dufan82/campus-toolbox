package cn.campuslife.assistant.map

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import cn.campuslife.assistant.ui.Ui
import java.util.concurrent.Executors

/** Favorites remain manageable without map consent, a Key, or a network connection. */
class PlaceHistoryActivity: Activity() {
    private val worker=Executors.newSingleThreadExecutor()
    private var data: CampusCoordinateData?=null
    private var history=PlaceHistory()
    private var recent=false
    private var error: String?=null
    private var loading=true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);recent=savedInstanceState?.getBoolean("recent") ?: false;render();reload()
    }
    private fun reload() {
        loading=true
        worker.execute {
            val result=runCatching { CampusCoordinateStore(this).load() to PlaceHistoryStore(this).load() }
            runOnUiThread { if(!isDestroyed) { loading=false;result.fold({data=it.first;history=it.second;error=null},{error="地点记录无法读取，原文件已保留"});render() } }
        }
    }
    private fun render() {
        val root=Ui.page(this);root.addView(Ui.header(this,"地点收藏"))
        val body=Ui.column(this).apply { setPadding(Ui.dp(context,18),0,Ui.dp(context,18),Ui.dp(context,24)) }
        val tabs=Ui.row(this)
        tabs.addView(Ui.button(this,"收藏",!recent) { recent=false;render() },LinearLayout.LayoutParams(0,-2,1f))
        tabs.addView(Ui.button(this,"最近访问",recent) { recent=true;render() },LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=Ui.dp(this@PlaceHistoryActivity,8) })
        body.addView(tabs);body.addView(Ui.space(this,12));root.addView(Ui.scroll(this,body),LinearLayout.LayoutParams(-1,0,1f))
        if(loading) { body.addView(Ui.text(this,"正在读取…"));return }
        error?.let { body.addView(Ui.text(this,it));body.addView(Ui.button(this,"重试") { reload();render() });return }
        val ids=if(recent) history.recent else history.favorites
        if(ids.isEmpty()) { body.addView(Ui.text(this,if(recent) "还没有访问记录。" else "还没有收藏，选中地图地点后点收藏。",15f,Ui.muted));body.addView(Ui.button(this,"打开地图") { startActivity(Intent(this,CampusMapActivity::class.java)) });return }
        val current=data ?: return
        ids.forEach { id ->
            val item=current.catalog.find { it.id==id };val availability=PlaceHistory.availability(id,current)
            val card=Ui.card(this);card.addView(Ui.text(this,item?.name ?: "地点已移除",17f,bold=true))
            val message=when(availability) { PlaceAvailability.AVAILABLE->"点击查看位置";PlaceAvailability.HIDDEN->"地点已隐藏，暂不可定位";PlaceAvailability.MISSING_POSITION->"位置待核对，暂不可导航";PlaceAvailability.REMOVED->"原地点已删除，不会恢复旧坐标" }
            card.addView(Ui.text(this,message,13f,Ui.muted))
            val row=Ui.row(this)
            row.addView(Ui.button(this,"查看位置",true) {
                if(intent.getBooleanExtra("choose",false)) { setResult(RESULT_OK,Intent().putExtra("campus_place_id",id));finish() }
                else startActivity(Intent(this,CampusMapActivity::class.java).putExtra("campus_place_id",id))
            }.apply { tag="history_open_$id";isEnabled=availability==PlaceAvailability.AVAILABLE },LinearLayout.LayoutParams(0,-2,1f))
            if(id in history.favorites) row.addView(Ui.button(this,"取消收藏") {
                AlertDialog.Builder(this).setTitle("取消收藏？").setPositiveButton("取消收藏") { _,_-> update { it.removeFavorite(id) } }.setNegativeButton("保留",null).show()
            },LinearLayout.LayoutParams(-2,-2).apply { marginStart=Ui.dp(this@PlaceHistoryActivity,8) })
            card.addView(row);body.addView(card);body.addView(Ui.space(this,10))
        }
    }
    private fun update(change: (PlaceHistory)->PlaceHistory) {
        loading=true;render()
        worker.execute {
            val result=runCatching { PlaceHistoryStore(this).update(change) }
            runOnUiThread { if(!isDestroyed) { loading=false;result.fold({history=it},{Ui.toast(this,it.message ?: "未保存")});render() } }
        }
    }
    override fun onResume() { super.onResume();if(!loading) reload() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("recent",recent);super.onSaveInstanceState(outState) }
    override fun onDestroy() { worker.shutdown();super.onDestroy() }
}
