package cn.campuslife.assistant.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import cn.campuslife.watermark.FieldKey

/** Ordered multi-selection shared by watermark content and export naming. */
class FieldChips(context: Context, available: List<FieldKey>, initial: List<FieldKey>,
    tagPrefix: String, accessibilityPrefix: String, compact: Boolean = false,
    onChanged: (List<FieldKey>)->Unit
) : LinearLayout(context) {
    private var selection=initial.filter { it in available }.distinct()
    private val choices=linkedMapOf<FieldKey,CheckBox>()
    private var updating=false
    init {
        orientation=if(compact) HORIZONTAL else VERTICAL
        val columns=if(resources.configuration.fontScale>=1.3f || resources.configuration.screenWidthDp<360) 2 else 3
        available.chunked(if(compact) available.size.coerceAtLeast(1) else columns).forEach { keys ->
            val row=if(compact) this else Ui.row(context)
            keys.forEachIndexed { index,key ->
                val check=CheckBox(context).apply {
                    tag="${tagPrefix}_${key.name}";buttonDrawable=null;gravity=Gravity.CENTER;textSize=14f
                    minHeight=Ui.dp(context,48);minimumHeight=Ui.dp(context,48)
                    minWidth=0;minimumWidth=0
                    setPadding(Ui.dp(context,10),Ui.dp(context,8),Ui.dp(context,10),Ui.dp(context,8))
                    contentDescription="$accessibilityPrefix${key.label}"
                    setOnCheckedChangeListener { _,selected ->
                        if(!updating) {
                            selection=if(selected) selection+key else selection-key
                            refresh();onChanged(selection.toList())
                        }
                    }
                }
                choices[key]=check
                row.addView(check,(if(compact) LayoutParams(-2,-2) else LayoutParams(0,-2,1f)).apply {
                    if(index<keys.lastIndex) marginEnd=Ui.dp(context,8)
                })
            }
            if(!compact) { addView(row);addView(Ui.space(context,8)) }
        }
        refresh()
    }
    private fun refresh() {
        updating=true
        choices.forEach { (key,check) ->
            val index=selection.indexOf(key);check.isChecked=index>=0
            check.text=if(index>=0) "${index+1}  ${key.label}" else "+  ${key.label}"
            check.setTextColor(if(index>=0) Ui.blue else Ui.muted)
            check.background=RippleDrawable(ColorStateList.valueOf(0x223159d8),
                Ui.shape(context,if(index>=0) Ui.pale else Ui.background,10,if(index>=0) Ui.blue else Ui.line),null)
        }
        updating=false
    }
}
