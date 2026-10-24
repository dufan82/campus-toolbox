package cn.campuslife.assistant.map

import android.app.Activity
import android.app.Dialog
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import cn.campuslife.assistant.ui.Ui
import java.util.Locale

class CampusReferenceDialog(private val activity: Activity,initialId: String?,private val canMark: Boolean,
    private val items: List<CampusLandmark> = CampusCatalog.entries,private val onMark: (String)->Unit): Dialog(activity) {
    var selectedId: String?=initialId?.takeIf { id -> items.any { it.id==id } }
        private set
    private val selection=Ui.text(activity,"",14f,Ui.ink,bold=true).apply { tag="ref_selection" }
    private val scale=Ui.text(activity,"1.0× · 双指缩放、拖动或点选绿点",12f,Ui.muted).apply { tag="ref_scale" }
    private val image=CampusReferenceView(activity,{ select(it) },{ zoom -> scale.text=String.format(Locale.ROOT,"%.1f× · 双指缩放、拖动或点选绿点",zoom) },items)
    private lateinit var mark: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root=Ui.column(activity).apply { tag="campus_reference_dialog";setBackgroundColor(Ui.background) }
        val pad=Ui.dp(activity,12)
        root.setPadding(pad,pad,pad,pad)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(pad+bars.left,pad+bars.top,pad+bars.right,pad+bars.bottom);insets
        }
        val header=Ui.row(activity)
        header.addView(Ui.text(activity,"校园原图对照",19f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(Ui.button(activity,"关闭") { dismiss() }.apply { tag="ref_close";minimumHeight=Ui.dp(context,48) })
        root.addView(header)
        val controls=Ui.column(activity)
        controls.addView(Ui.text(activity,"仅作原图对照，不是实时定位；原图红色“当前位置”为标牌原标记。",12f,Ui.muted).apply { tag="ref_notice" })
        controls.addView(Ui.space(activity,8));controls.addView(selection)
        controls.addView(Ui.space(activity,8))
        controls.addView(Ui.button(activity,"选择地点") { choose() }.apply { tag="ref_choose";minimumHeight=Ui.dp(context,48) },LinearLayout.LayoutParams(-1,-2))
        val zoomRow=Ui.row(activity)
        val actions=listOf("适应全图" to { image.fit() },"放大" to { image.zoom(1.4) },"缩小" to { image.zoom(1/1.4) })
        actions.forEachIndexed { index,(text,action) ->
            zoomRow.addView(Ui.button(activity,text) { action() }.apply { minimumHeight=Ui.dp(context,48);tag="ref_zoom_$index" },
                LinearLayout.LayoutParams(0,-2,1f).apply { if(index>0) marginStart=Ui.dp(activity,8) })
        }
        controls.addView(Ui.space(activity,8));controls.addView(zoomRow);controls.addView(scale)
        mark=Ui.button(activity,if(canMark) "去高德标注此地点" else "返回高德地图",true) {
            if(canMark) selectedId?.let { onMark(it) }
            dismiss()
        }.apply { tag="ref_mark";minimumHeight=Ui.dp(context,48) }
        if(activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE) {
            controls.addView(Ui.space(activity,8));controls.addView(mark,LinearLayout.LayoutParams(-1,-2))
            val body=Ui.row(activity)
            body.addView(Ui.scroll(activity,controls),LinearLayout.LayoutParams(Ui.dp(activity,280),-1))
            body.addView(image,LinearLayout.LayoutParams(0,-1,1f).apply { marginStart=Ui.dp(activity,8) })
            root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        } else {
            root.addView(controls)
            root.addView(image,LinearLayout.LayoutParams(-1,0,1f).apply { topMargin=Ui.dp(activity,8);bottomMargin=Ui.dp(activity,8) })
            root.addView(mark,LinearLayout.LayoutParams(-1,-2))
        }
        setContentView(root)
        window?.setBackgroundDrawable(ColorDrawable(Ui.background))
        window?.let { WindowCompat.setDecorFitsSystemWindows(it,true) }
        updateSelection()
    }

    override fun show() { super.show();window?.setLayout(-1,-1);ViewCompat.requestApplyInsets(window!!.decorView) }

    private fun choose() {
        AlertDialog.Builder(activity).setTitle("选择原图对照地点")
            .setItems(items.map { it.name+(if(it.id in CampusReference.points) "" else "（原图暂未关联）") }.toTypedArray()) { _,index -> select(items[index].id) }
            .setNegativeButton("取消",null).show()
    }

    private fun select(id: String) { selectedId=id;updateSelection() }
    private fun updateSelection() {
        val item=items.firstOrNull { it.id==selectedId }
        selection.text=when { item==null->"选择地点或放大后点击图中绿点";item.id !in CampusReference.points->"${item.name}：原图暂未关联，以高德和现场为准";else->"已选：${item.name} · 原图对应位置已高亮" }
        mark.isEnabled=!canMark || item!=null
        image.select(selectedId)
    }
}
