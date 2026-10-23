package cn.campuslife.assistant.ui

import android.content.Context
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.Button
import android.widget.CheckBox
import cn.campuslife.watermark.*

class ContentPanel(context: Context, initialDetails: ActivityDetails, initialConfig: EditorConfig,
    onDetails: (ActivityDetails)->Unit, onConfig: (EditorConfig)->Unit, onProfile: ()->Unit,
    private val currentConfig: ()->EditorConfig = { initialConfig }
) : LinearLayout(context) {
    private lateinit var positionButton: Button
    init {
        orientation=VERTICAL
        setPadding(Ui.dp(context,18),0,Ui.dp(context,18),Ui.dp(context,8))
        setBackgroundColor(Color.WHITE)
        val heading=Ui.row(context)
        heading.addView(Ui.text(context,"水印内容",15f,bold=true),LayoutParams(0,-2,1f))
        heading.addView(CheckBox(context).apply {
            text="只显示内容";textSize=13f;setTextColor(Ui.ink)
            tag="content_only";minHeight=Ui.dp(context,48)
            buttonTintList=android.content.res.ColorStateList.valueOf(Ui.blue)
            isChecked=initialConfig.contentOnly
            contentDescription="只显示内容，取消勾选则显示字段名称"
            setOnCheckedChangeListener { _,checked -> onConfig(currentConfig().copy(contentOnly=checked)) }
        },LayoutParams(-2,Ui.dp(context,48)).apply { marginEnd=Ui.dp(context,8) })
        heading.addView(Ui.button(context,"编辑资料",onClick=onProfile).apply {
            tag="edit_profile";background=ColorDrawable(Color.TRANSPARENT)
        })
        addView(heading)
        val timeRow=Ui.row(context)
        timeRow.addView(Ui.text(context,"拍摄时间",13f,Ui.muted),LayoutParams(Ui.dp(context,74),-2))
        var details=initialDetails
        val input=Ui.input(context,details.values[FieldKey.CAPTURED_AT].orEmpty(),"可留空或自行填写") { value ->
            details=details.copy(values=details.values+(FieldKey.CAPTURED_AT to value));onDetails(details)
        }.apply { tag="detail_CAPTURED_AT";textSize=13f;contentDescription="拍摄时间" }
        timeRow.addView(input,LayoutParams(0,-2,1f))
        timeRow.visibility=if(FieldKey.CAPTURED_AT in initialConfig.enabled) View.VISIBLE else View.GONE
        val order=listOf(FieldKey.CLASS_NAME,FieldKey.NAME,FieldKey.SEAT_NUMBER,FieldKey.COLLEGE,FieldKey.STUDENT_ID,FieldKey.CAPTURED_AT)
        addView(FieldChips(context,order,initialConfig.orderedFields().filter { it in initialConfig.enabled },"visible","水印包含") { fields ->
            val current=currentConfig()
            onConfig(current.copy(order=fields+(current.orderedFields()-fields.toSet()),enabled=fields.toSet()))
            timeRow.visibility=if(FieldKey.CAPTURED_AT in fields) View.VISIBLE else View.GONE
        })
        val positionRow=Ui.row(context)
        positionRow.addView(Ui.text(context,"水印位置",13f,Ui.muted),LayoutParams(Ui.dp(context,84),-2))
        positionButton=Ui.button(context,"") {
            val options=Position.entries.filter { it!=Position.CUSTOM }
            AlertDialog.Builder(context).setTitle("水印位置")
                .setSingleChoiceItems(options.map { it.label }.toTypedArray(),options.indexOf(currentConfig().style.position)) { dialog,index ->
                    val current=currentConfig()
                    onConfig(current.copy(style=current.style.copy(position=options[index])))
                    refreshPosition();dialog.dismiss()
                }.setNegativeButton("取消",null).show()
        }.apply { tag="watermark_position" }
        positionRow.addView(positionButton,LayoutParams(0,Ui.dp(context,48),1f))
        addView(positionRow);refreshPosition();addView(Ui.space(context,8))
        addView(Ui.text(context,"按点选顺序排列，空值跳过；学号默认不选。",12f,Ui.muted))
        addView(timeRow)
    }
    fun refreshPosition() {
        val label=currentConfig().style.position.label
        positionButton.text="$label  ▾"
        positionButton.contentDescription="水印位置，$label，点按选择"
    }
}
