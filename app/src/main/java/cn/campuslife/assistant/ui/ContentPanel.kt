package cn.campuslife.assistant.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import cn.campuslife.watermark.*

/** Compact controls beneath the photo. Forms and style tools live in bounded sheets. */
class ContentPanel(context: Context, initialDetails: ActivityDetails, initialConfig: EditorConfig,
    private val onDetails: (ActivityDetails)->Unit, private val onConfig: (EditorConfig)->Unit,
    private val onProfile: ()->Unit, private val currentConfig: ()->EditorConfig = { initialConfig }
) : LinearLayout(context) {
    private val available=listOf(FieldKey.CLASS_NAME,FieldKey.NAME,FieldKey.SEAT_NUMBER,FieldKey.COLLEGE,FieldKey.STUDENT_ID,FieldKey.CAPTURED_AT)
    private var details=initialDetails
    private val selectedHost=Ui.column(context)
    private val styleButton: Button
    private var sheet: Dialog?=null

    init {
        tag="watermark_content"
        orientation=VERTICAL
        setPadding(Ui.dp(context,18),Ui.dp(context,2),Ui.dp(context,18),Ui.dp(context,8))
        background=Ui.shape(context,Color.WHITE,18)
        val heading=Ui.row(context)
        heading.addView(Ui.text(context,"水印内容",15f,bold=true),LayoutParams(0,-2,1f))
        heading.addView(Ui.textButton(context,"更多字段") { showFields() }.apply { tag="watermark_more_fields" })
        addView(heading)
        addView(selectedHost)
        addView(Ui.space(context,8))
        addView(View(context).apply { setBackgroundColor(Ui.line) },LayoutParams(-1,Ui.dp(context,1)))
        val actions=if(resources.configuration.fontScale>=1.3f) Ui.column(context) else Ui.row(context)
        actions.addView(CheckBox(context).apply {
            text="只显示内容";textSize=14f;setTextColor(Ui.ink)
            tag="content_only";minHeight=Ui.dp(context,48)
            buttonTintList=ColorStateList.valueOf(Ui.blue)
            isChecked=initialConfig.contentOnly
            contentDescription="只显示内容，取消勾选则显示字段名称"
            setOnCheckedChangeListener { _,checked -> change(currentConfig().copy(contentOnly=checked)) }
        },if(actions.orientation==HORIZONTAL) LayoutParams(-2,-2) else LayoutParams(-1,-2))
        styleButton=Ui.textButton(context,"水印样式") { showStyles() }.apply {
            tag="watermark_styles";gravity=Gravity.END or Gravity.CENTER_VERTICAL
            contentDescription="调整水印样式，文字、排版和背景"
        }
        actions.addView(styleButton,if(actions.orientation==HORIZONTAL) LayoutParams(0,-2,1f) else LayoutParams(-1,-2))
        addView(actions)
        refreshSelection()
    }

    private fun change(value: EditorConfig) {
        onConfig(value)
        refreshSelection()
    }

    private fun refreshSelection() {
        val config=currentConfig()
        val selected=config.orderedFields().filter { it in config.enabled }
        selectedHost.removeAllViews()
        if(selected.isEmpty()) {
            selectedHost.addView(Ui.text(context,"还没有选择字段，点“更多字段”添加。",14f,Ui.muted).apply {
                minHeight=Ui.dp(context,48);gravity=Gravity.CENTER_VERTICAL
            })
        } else {
            selectedHost.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled=false
                addView(FieldChips(context,selected,selected,"visible","水印包含",compact=true) { fields ->
                    val current=currentConfig()
                    change(current.copy(order=fields+(current.orderedFields()-fields.toSet()),enabled=fields.toSet()))
                })
            })
        }
        val style=config.style
        val color=when(style.textColor) { Color.WHITE->"白字";Color.BLACK->"黑字";else->"自定字色" }
        val background=if(style.backgroundOpacity==0.0) "无底色" else "有底色"
        styleButton.text="水印样式  ›"
        styleButton.contentDescription="调整水印样式，$color，${if(style.multiline) "竖排" else "横排"}，$background"
    }

    private fun showFields() {
        val activity=context as? Activity ?: return
        if(sheet?.isShowing==true) return
        sheet=Ui.bottomSheet(activity,"选择水印内容") { body,dialog ->
            body.addView(Ui.text(context,"按勾选顺序排列，空项跳过。",14f,Ui.muted))
            body.addView(Ui.space(context,12))
            val timeRow=Ui.column(context)
            timeRow.addView(Ui.text(context,"拍摄时间",14f,Ui.muted))
            timeRow.addView(Ui.space(context,8))
            timeRow.addView(Ui.input(context,details.values[FieldKey.CAPTURED_AT].orEmpty(),"可留空或自行填写") { value ->
                details=details.copy(values=details.values+(FieldKey.CAPTURED_AT to value));onDetails(details)
            }.apply { tag="detail_CAPTURED_AT";contentDescription="拍摄时间" })
            timeRow.visibility=if(FieldKey.CAPTURED_AT in currentConfig().enabled) View.VISIBLE else View.GONE
            body.addView(FieldChips(context,available,currentConfig().orderedFields().filter { it in currentConfig().enabled },"visible","水印包含") { fields ->
                val current=currentConfig()
                change(current.copy(order=fields+(current.orderedFields()-fields.toSet()),enabled=fields.toSet()))
                timeRow.visibility=if(FieldKey.CAPTURED_AT in fields) View.VISIBLE else View.GONE
            })
            body.addView(timeRow)
            body.addView(Ui.textButton(context,"编辑资料") { dialog.dismiss();onProfile() }.apply { tag="edit_profile" },LayoutParams(-1,-2))
            body.addView(Ui.text(context,"学号仅在勾选后显示。",13f,Ui.muted))
            body.addView(Ui.space(context,12))
            body.addView(Ui.button(context,"完成",true) { dialog.dismiss() },LayoutParams(-1,-2))
        }.also { it.setOnDismissListener { sheet=null } }
    }

    private fun showStyles() {
        val activity=context as? Activity ?: return
        if(sheet?.isShowing==true) return
        sheet=Ui.bottomSheet(activity,"调整水印") { body,dialog ->
            val tabs=Ui.row(context)
            val content=Ui.column(context)
            val tabButtons=mutableListOf<Button>()
            fun style(update: (WatermarkStyle)->WatermarkStyle) {
                val current=currentConfig();change(current.copy(style=update(current.style)))
            }
            fun render(index: Int) {
                tabButtons.forEachIndexed { i,button -> markChoice(button,i==index) }
                content.removeAllViews()
                when(index) {
                    0 -> {
                        content.addView(Ui.text(context,"文字颜色",14f,Ui.muted))
                        content.addView(Ui.space(context,8))
                        choices(content,listOf("白色","黑色"),"style_text_color",{
                            when(currentConfig().style.textColor) { Color.WHITE->0;Color.BLACK->1;else->-1 }
                        }) { style { previous -> WatermarkChoices.text(previous,it==1) } }
                        content.addView(Ui.space(context,12))
                        val size=Ui.text(context,"水印字号 · 短边的 ${"%.1f".format(currentConfig().style.fontRatio*100)}%",14f,Ui.muted)
                        content.addView(size)
                        content.addView(SeekBar(context).apply {
                            tag="watermark_font_size";contentDescription="水印字号"
                            max=55;progress=((currentConfig().style.fontRatio*1000).toInt()-15).coerceIn(0,55)
                            minimumHeight=Ui.dp(context,48)
                            progressTintList=ColorStateList.valueOf(Ui.blue);thumbTintList=ColorStateList.valueOf(Ui.blue)
                            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(seek: SeekBar?,value: Int,fromUser: Boolean) {
                                    if(fromUser) {
                                        val ratio=(value+15)/1000.0
                                        size.text="水印字号 · 短边的 ${"%.1f".format(ratio*100)}%"
                                        style { it.copy(fontRatio=ratio) }
                                    }
                                }
                                override fun onStartTrackingTouch(seek: SeekBar?) {}
                                override fun onStopTrackingTouch(seek: SeekBar?) {}
                            })
                        })
                    }
                    1 -> {
                        content.addView(Ui.text(context,"排列方式",14f,Ui.muted))
                        content.addView(Ui.space(context,8))
                        choices(content,listOf("竖向多行","横向排列"),"style_direction",{ if(currentConfig().style.multiline) 0 else 1 }) {
                            style { previous -> WatermarkChoices.horizontal(previous,it==1) }
                        }
                        content.addView(Ui.space(context,12))
                        content.addView(Ui.text(context,"快捷位置",14f,Ui.muted))
                        content.addView(Ui.space(context,8))
                        val positions=Position.entries.filter { it!=Position.CUSTOM }
                        choices(content,positions.map { it.label },"style_position",{ positions.indexOf(currentConfig().style.position) }) { selected ->
                            style { it.copy(position=positions[selected]) }
                        }
                    }
                    else -> {
                        content.addView(Ui.text(context,"水印底色",14f,Ui.muted))
                        content.addView(Ui.space(context,8))
                        choices(content,listOf("透明","半透明黑底","半透明白底"),"style_background",{
                            val value=currentConfig().style
                            when {
                                value.backgroundOpacity==0.0->0
                                value.backgroundColor==Color.BLACK && value.backgroundOpacity==.64->1
                                value.backgroundColor==Color.WHITE && value.backgroundOpacity==.75->2
                                else->-1
                            }
                        }) { selected -> style { WatermarkChoices.background(it,WatermarkChoices.Background.entries[selected]) } }
                    }
                }
            }
            listOf("文字","排版","背景").forEachIndexed { index,label ->
                val button=Ui.button(context,label) { render(index) }.apply {
                    tag=listOf("style_tab_text","style_tab_layout","style_tab_background")[index]
                    contentDescription=when(index) { 0->"文字，文字颜色";1->"排版，排列方式，快捷位置";else->"背景" }
                }
                tabButtons.add(button)
                tabs.addView(button,LayoutParams(0,-2,1f).apply { if(index<2) marginEnd=Ui.dp(context,6) })
            }
            body.addView(tabs)
            body.addView(Ui.space(context,12))
            // Only a single tab is shown. Bound the scrolling tools so the upper
            // part of the real photo remains visible even with larger text.
            body.addView(object: ScrollView(context) {
                override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                    val cap=(resources.displayMetrics.heightPixels*.60f-Ui.dp(context,240)).toInt()
                        .coerceIn(Ui.dp(context,56),Ui.dp(context,230))
                    super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(cap,View.MeasureSpec.AT_MOST))
                }
            }.apply { tag="watermark_style_controls";addView(content) },LayoutParams(-1,-2))
            body.addView(Ui.space(context,8))
            body.addView(Ui.text(context,"拖动水印可调整位置",13f,Ui.muted))
            body.addView(Ui.space(context,8))
            body.addView(Ui.button(context,"完成",true) { dialog.dismiss() },LayoutParams(-1,-2))
            render(0)
        }.also { dialog ->
            dialog.setOnDismissListener { sheet=null }
            dialog.window?.setDimAmount(.10f)
            dialog.window?.decorView?.post {
                if(dialog.isShowing) {
                    val height=minOf(dialog.window!!.decorView.height,(resources.displayMetrics.heightPixels*.60f).toInt())
                    if(height>0) dialog.window?.setLayout(-1,height)
                }
            }
        }
    }

    private fun choices(parent: LinearLayout,labels: List<String>,tagPrefix: String,selected: ()->Int,apply: (Int)->Unit) {
        val buttons=mutableListOf<Button>()
        val columns=if(resources.configuration.fontScale>=1.3f || resources.configuration.screenWidthDp<360) 2 else 3
        labels.withIndex().chunked(minOf(columns,labels.size)).forEach { group ->
            val row=Ui.row(context)
            group.forEachIndexed { column,item ->
                val button=Ui.button(context,item.value) {
                    apply(item.index)
                    buttons.forEachIndexed { index,choice -> markChoice(choice,index==selected()) }
                }.apply { tag="${tagPrefix}_${item.index}" }
                buttons.add(button)
                row.addView(button,LayoutParams(0,-2,1f).apply { if(column<group.lastIndex) marginEnd=Ui.dp(context,8) })
            }
            parent.addView(row)
            if(group.last().index<labels.lastIndex) parent.addView(Ui.space(context,8))
        }
        buttons.forEachIndexed { index,button -> markChoice(button,index==selected()) }
    }

    private fun markChoice(button: Button,selected: Boolean) {
        button.isSelected=selected
        button.setTextColor(if(selected) Ui.blue else Ui.muted)
        button.background=Ui.shape(context,if(selected) Ui.pale else Ui.background,10,if(selected) Ui.blue else Ui.line)
    }

    override fun onDetachedFromWindow() {
        sheet?.dismiss();sheet=null
        super.onDetachedFromWindow()
    }
}