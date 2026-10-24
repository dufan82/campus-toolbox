package cn.campuslife.assistant.map

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import cn.campuslife.assistant.BuildConfig
import cn.campuslife.assistant.ui.Ui
import java.util.UUID

/** Maintainer-only one-time coordinate review/export. Not part of ordinary users' setup. */
class CampusMapMaintenance(
    private val activity: Activity,
    private val store: CampusCoordinateStore,
    private var data: CampusCoordinateData,
    private val onUpdated: (CampusCoordinateData)->Unit,
    private val onTargetChanged: (CampusLandmark?)->Unit,
    private val onPositionRequested: (String)->Unit = {}
) {
    private var editor: CampusPlaceEditor?=null
    var target: CampusLandmark?=null
        private set

    fun menu() {
        if(!BuildConfig.DEBUG) return
        AlertDialog.Builder(activity).setTitle("校园地点管理")
            .setItems(arrayOf("新增自定义地点","管理自定义地点","显示或隐藏地点","退出核对模式")) { _,index ->
                when(index) { 0->edit(null);1->customMenu();2->visibility();else->setTarget(null) }
            }.setNegativeButton("关闭",null).show()
    }



    private fun setTarget(item: CampusLandmark?) { target=item;onTargetChanged(item) }

    fun select(id: String) {
        if(BuildConfig.DEBUG) data.catalog.firstOrNull { it.id==id }?.let { setTarget(it) }
    }

    private fun persist(next: CampusCoordinateData): Boolean {
        val saved=try { store.save(next) } catch(_: Exception) { false }
        if(!saved) return false
        data=next
        runCatching { onUpdated(next) }.onFailure { Ui.toast(activity,"地点已保存，地图刷新失败，请重新进入地图。") }
        return true
    }

    private fun edit(item: CampusLandmark?,draftName: String=item?.name.orEmpty(),draftCategory: MapCategory=item?.category ?: MapCategory.STUDY) {
        if(!BuildConfig.DEBUG) return
        editor?.dismiss()
        editor=CampusPlaceEditor(activity,item?.id,draftName,draftCategory) { raw,category ->
            val name=raw.trim()
            val others=data.catalog.filterNot { it.id==item?.id }
            when {
                name.isEmpty()->"请输入地点名称"
                name.length>40 || name.any { it.isISOControl() }->"名称最多40字，不能含换行或控制字符"
                CampusCatalog.hasExactName(name,others)->"名称已存在，请修改名称或编辑已有地点"
                else->{
                    val place=CampusLandmark(item?.id ?: "custom-${UUID.randomUUID()}",name,category,item?.aliases.orEmpty())
                    val next=runCatching { data.withCustomPlace(place) }.getOrNull()
                    if(next==null) "地点设置无效，请检查名称和分类"
                    else if(!persist(next)) "保存失败，原有地点未改变，请重试"
                    else {
                        if(item==null) { setTarget(place);onPositionRequested(place.id) }
                        else if(target?.id==place.id) setTarget(place)
                        Ui.toast(activity,if(item==null) "已新增，请在地图上长按真实位置。" else "名称与分类已保存，原坐标保留。")
                        null
                    }
                }
            }
        }.also { it.show() }
    }

    private fun customMenu() {
        val items=data.customPlaces
        if(items.isEmpty()) { Ui.toast(activity,"还没有自定义地点，先选择新增自定义地点。");return }
        AlertDialog.Builder(activity).setTitle("管理自定义地点").setItems(items.map { it.name }.toTypedArray()) { _,index ->
            val item=items[index]
            AlertDialog.Builder(activity).setTitle(item.name).setItems(arrayOf("修改名称和分类","调整位置","删除自定义地点")) { _,action ->
                when(action) {
                    0->edit(item)
                    1->{ setTarget(item);onPositionRequested(item.id) }
                    else->AlertDialog.Builder(activity).setTitle("删除 ${item.name}？")
                        .setMessage("将删除这个自定义地点的名称、分类和坐标，其他地点不变。")
                        .setPositiveButton("确认删除") { _,_->
                            if(persist(data.withoutCustomPlace(item.id))) { if(target?.id==item.id) setTarget(null);Ui.toast(activity,"自定义地点已删除。") }
                            else Ui.toast(activity,"删除未能保存，原地点仍保留。")
                        }.setNegativeButton("取消",null).show()
                }
            }.setNegativeButton("取消",null).show()
        }.setNegativeButton("取消",null).show()
    }

    private fun visibility() {
        val items=data.catalog
        val checked=BooleanArray(items.size) { items[it].id !in data.hiddenIds }
        AlertDialog.Builder(activity).setTitle("勾选需要显示的地点")
            .setMultiChoiceItems(items.map { it.name }.toTypedArray(),checked) { _,index,value -> checked[index]=value }
            .setPositiveButton("保存显示设置") { _,_->
                val hidden=items.filterIndexed { index,_->!checked[index] }.map { it.id }.toSet()
                if(persist(data.withHiddenIds(hidden))) { if(target?.id in hidden) setTarget(null);Ui.toast(activity,"显示设置已保存，隐藏不删除坐标。") }
                else Ui.toast(activity,"显示设置未能保存，请重试。")
            }.setNegativeButton("取消",null).show()
    }



    fun stop() { setTarget(null) }

    fun review(point: GeoPoint) {
        if(!BuildConfig.DEBUG) return
        val item=target ?: return
        if(MapRules.distanceMeters(data.center,point)>1000) { Ui.toast(activity,"该点超出校园核对范围，请重新选择。");return }
        AlertDialog.Builder(activity).setTitle("确认 ${item.name} 的位置？")
            .setMessage("仅记录刚才在地图上长按的位置，不使用手机当前位置。请核实建筑及可到达入口；只保存在本机。")
            .setPositiveButton("确认并保存") { _,_->
                try {
                    val next=data.withPosition(item.id,point)
                    if(persist(next)) { setTarget(null);Ui.toast(activity,"地点位置已保存。") }
                    else Ui.toast(activity,"保存失败，原有地点没有改变。")
                } catch(_: Exception) { Ui.toast(activity,"地点未能保存，请检查坐标与存储。") }
            }.setNegativeButton("取消",null).show()
    }



    fun saveState(out: Bundle) {
        out.putString("campus_review_target",target?.id)
        editor?.takeIf { it.isShowing }?.let {
            out.putBoolean("campus_editor_open",true);out.putString("campus_editor_id",it.placeId)
            out.putString("campus_editor_name",it.draftName);out.putString("campus_editor_category",it.draftCategory.name)
        }
    }
    fun restoreState(saved: Bundle?) {
        if(!BuildConfig.DEBUG) return
        saved?.getString("campus_review_target")?.let { id -> setTarget(data.catalog.firstOrNull { it.id==id }) }
        if(saved?.getBoolean("campus_editor_open")==true) {
            val id=saved.getString("campus_editor_id")
            val category=runCatching { MapCategory.valueOf(saved.getString("campus_editor_category").orEmpty()) }.getOrDefault(MapCategory.STUDY)
            val item=id?.let { value -> data.customPlaces.firstOrNull { it.id==value } }
            if(id==null || item!=null) edit(item,saved.getString("campus_editor_name").orEmpty(),category)
        }
    }
    fun close() { editor?.dismiss();editor=null }
}
