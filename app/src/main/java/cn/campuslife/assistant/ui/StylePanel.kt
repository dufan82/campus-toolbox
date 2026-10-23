package cn.campuslife.assistant.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.*
import cn.campuslife.watermark.*
import kotlin.math.roundToInt

class StylePanel(context: Context, initial: WatermarkStyle, format: ExportFormat, shortEdge: Int,
    private val onStyle: (WatermarkStyle) -> Unit, onFormat: (ExportFormat) -> Unit,
    private val currentStyle: ()->WatermarkStyle = { initial }) : LinearLayout(context) {
    private val style get()=currentStyle()
    init {
        orientation=VERTICAL
        setPadding(Ui.dp(context,18),Ui.dp(context,12),Ui.dp(context,18),Ui.dp(context,20))
        val card = Ui.card(context)
        card.addView(Ui.text(context,"文字与背景",17f,bold=true))
        slider(card,"字号",15,70,(style.fontRatio*1000).roundToInt(), { "约 ${(shortEdge*it/1000.0).roundToInt()} px" }) {
            change(style.copy(fontRatio=it/1000.0))
        }
        val bold = CheckBox(context).apply {
            text="加粗文字"; minHeight=Ui.dp(context,48); isChecked=style.bold
            setOnCheckedChangeListener { _, value -> change(style.copy(bold=value)) }
        }
        card.addView(bold)
        card.addView(colorButton("文字颜色",style.textColor) { change(style.copy(textColor=it)) })
        card.addView(Ui.space(context,8))
        card.addView(colorButton("背景颜色",style.backgroundColor) { change(style.copy(backgroundColor=it)) })
        slider(card,"背景不透明度",0,100,(style.backgroundOpacity*100).roundToInt(), { "$it%" }) { change(style.copy(backgroundOpacity=it/100.0)) }
        slider(card,"行间距",100,200,(style.lineSpacing*100).roundToInt(), { "${it/100.0} 倍" }) { change(style.copy(lineSpacing=it/100.0)) }
        selector(card,"文字对齐",listOf("左对齐","居中","右对齐"),style.alignment.ordinal) { change(style.copy(alignment=TextAlignment.entries[it])) }
        val multiline = CheckBox(context).apply {
            text="多行排列（关闭后单行串联）"; minHeight=Ui.dp(context,48); isChecked=style.multiline
            setOnCheckedChangeListener { _, value -> change(style.copy(multiline=value)) }
        }
        card.addView(multiline)
        card.addView(Ui.text(context,"内容过长时会自动换行，保证完整显示。",12f,Ui.muted))
        addView(card); addView(Ui.space(context,12))
        val position = Ui.card(context)
        position.addView(Ui.text(context,"位置与边距",17f,bold=true))
        selector(position,"水印位置",Position.entries.map { it.label },style.position.ordinal) { change(style.copy(position=Position.entries[it])) }
        position.addView(Ui.text(context,"也可以直接拖动照片上的水印。",12f,Ui.muted))
        slider(position,"外边距",0,150,(style.marginRatio*1000).roundToInt(), { "短边的 ${it/10.0}%" }) { change(style.copy(marginRatio=it/1000.0)) }
        slider(position,"背景内边距",0,60,(style.paddingRatio*1000).roundToInt(), { "短边的 ${it/10.0}%" }) { change(style.copy(paddingRatio=it/1000.0)) }
        addView(position); addView(Ui.space(context,12))
        val output = Ui.card(context)
        selector(output,"保存格式",listOf("JPEG · 高清照片","PNG · 无损编码，文件较大"),format.ordinal) { onFormat(ExportFormat.entries[it]) }
        output.addView(Ui.text(context,"按原照片方向和尺寸生成新图片，不覆盖原文件。",12f,Ui.muted))
        addView(output)
    }

    private fun change(value: WatermarkStyle) { onStyle(value) }
    private fun slider(parent: LinearLayout, label: String, min: Int, max: Int, current: Int, display: (Int)->String, changed: (Int)->Unit) {
        parent.addView(Ui.space(context,12))
        val text=Ui.text(context,"$label：${display(current)}",14f)
        parent.addView(text)
        parent.addView(SeekBar(context).apply {
            this.max=max-min; progress=(current-min).coerceIn(0,max-min); minimumHeight=Ui.dp(context,48)
            contentDescription=label
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { val value=progress+min; text.text="$label：${display(value)}"; changed(value) }
                }
                override fun onStartTrackingTouch(seek: SeekBar?) {}
                override fun onStopTrackingTouch(seek: SeekBar?) {}
            })
        })
    }
    private fun selector(parent: LinearLayout, label: String, values: List<String>, initial: Int, changed: (Int)->Unit) {
        parent.addView(Ui.space(context,12)); parent.addView(Ui.text(context,label,13f,Ui.muted))
        parent.addView(Spinner(context).apply {
            adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,values)
            setSelection(initial); minimumHeight=Ui.dp(context,48)
            onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
                private var last=initial
                override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) {
                    if (position != last) { last=position; changed(position) }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        })
    }
    private fun colorButton(label: String, initial: Int, changed: (Int)->Unit): Button {
        var selected=initial
        val button=Ui.button(context,"$label  ${hex(selected)}") {}
        fun use(color: Int) { selected=color; button.text="$label  ${hex(color)}"; changed(color) }
        button.setOnClickListener {
            val labels=arrayOf("白色","深灰","深蓝","蓝色","红色","自定义颜色…")
            val colors=listOf(Color.WHITE,0xff1f2937.toInt(),0xff0f172a.toInt(),0xff2563eb.toInt(),0xffdc2626.toInt())
            AlertDialog.Builder(context).setTitle(label).setItems(labels) { _, index ->
                if (index < colors.size) use(colors[index]) else {
                    val input=Ui.input(context,hex(selected),"例如 #FFFFFF") {}
                    val dialog=AlertDialog.Builder(context).setTitle("输入颜色").setView(input).setPositiveButton("使用",null).setNegativeButton("取消",null).create()
                    dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        try {
                            val value=input.text.toString().trim()
                            require(value.matches(Regex("#[0-9a-fA-F]{6}"))) { "请输入 # 加 6 位十六进制颜色" }
                            use(Color.parseColor(value)); dialog.dismiss()
                        } catch (error: Exception) { input.error=error.message ?: "颜色格式不正确" }
                    } }; dialog.show()
                }
            }.show()
        }
        return button
    }
    private fun hex(color: Int) = String.format("#%06X",color and 0xffffff)
}
