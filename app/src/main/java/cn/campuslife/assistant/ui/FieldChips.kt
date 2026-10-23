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
    tagPrefix: String, accessibilityPrefix: String, onChanged: (List<FieldKey>)->Unit
) : LinearLayout(context) {
    private var selection=initial.filter { it in available }.distinct()
    private val choices=linkedMapOf<FieldKey,CheckBox>()
    private var updating=false
    init {
        orientation=VERTICAL
        available.chunked(3).forEach { keys ->
            val row=Ui.row(context)
            keys.forEachIndexed { index,key ->
                val check=CheckBox(context).apply {
                    tag="${tagPrefix}_${key.name}";buttonDrawable=null;gravity=Gravity.CENTER;textSize=13f
                    minHeight=Ui.dp(context,48);minimumHeight=Ui.dp(context,48)
                    setPadding(Ui.dp(context,4),Ui.dp(context,8),Ui.dp(context,4),Ui.dp(context,8))
                    contentDescription="$accessibilityPrefix${key.label}"
                    setOnCheckedChangeListener { _,selected ->
                        if(!updating) {
                            selection=if(selected) selection+key else selection-key
                            refresh();onChanged(selection.toList())
                        }
                    }
                }
                choices[key]=check
                row.addView(check,LayoutParams(0,-2,1f).apply { if(index<keys.lastIndex) marginEnd=Ui.dp(context,8) })
            }
            addView(row);addView(Ui.space(context,8))
        }
        refresh()
    }
    private fun refresh() {
        updating=true
        choices.forEach { (key,check) ->
            val index=selection.indexOf(key);check.isChecked=index>=0
            check.text=if(index>=0) "${index+1}  ${key.label}" else "+  ${key.label}"
            check.setTextColor(if(index>=0) Ui.blue else Ui.muted)
            check.background=RippleDrawable(ColorStateList.valueOf(0x220c6b58),
                Ui.shape(context,if(index>=0) Ui.pale else Ui.background,10,if(index>=0) Ui.blue else Ui.line),null)
        }
        updating=false
    }
}
