package cn.campuslife.assistant.map

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import cn.campuslife.assistant.ui.Ui

/** Native, keyboard-friendly metadata form. Persistence remains with the maintenance controller. */
class CampusPlaceEditor(private val activity: Activity,val placeId: String?,name: String,category: MapCategory,private val onSave: (String,MapCategory)->String?) {
    private val input=Ui.input(activity,name,"例如：实训2楼北门") {}.apply {
        tag="custom_place_name";contentDescription="地点名称";filters=arrayOf(InputFilter.LengthFilter(40));minimumHeight=Ui.dp(context,48)
        setOnFocusChangeListener { _,focused -> if(!focused && text.isBlank()) error="请输入地点名称" }
    }
    private val picker=Spinner(activity).apply {
        contentDescription="地点分类";minimumHeight=Ui.dp(context,48)
        adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,CampusPlaceRules.categories.map { it.label })
        setSelection(CampusPlaceRules.categories.indexOf(category).coerceAtLeast(0))
    }
    val draftName: String get()=input.text.toString()
    val draftCategory: MapCategory get()=CampusPlaceRules.categories[picker.selectedItemPosition]
    private val body=Ui.column(activity).apply {
        val pad=Ui.dp(activity,20);setPadding(pad,Ui.dp(activity,8),pad,Ui.dp(activity,12))
        addView(Ui.text(activity,"只记录公共地点，不填姓名、电话等个人资料。",13f,Ui.muted));addView(Ui.space(activity,12))
        addView(Ui.text(activity,"地点名称",14f,bold=true));addView(input,LinearLayout.LayoutParams(-1,-2))
        addView(Ui.space(activity,12));addView(Ui.text(activity,"地点分类",14f,bold=true));addView(picker,LinearLayout.LayoutParams(-1,-2))
        addView(Ui.space(activity,8));addView(Ui.text(activity,"名称最多40字。保存后在高德长按真实位置；不会使用手机当前位置替代。",12f,Ui.muted))
    }
    private val dialog=AlertDialog.Builder(activity).setTitle(if(placeId==null) "新增自定义地点" else "修改地点")
        .setView(Ui.scroll(activity,body)).setNegativeButton("取消",null)
        .setPositiveButton(if(placeId==null) "保存并标位置" else "保存修改",null).create()
    val isShowing get()=dialog.isShowing
    fun show() {
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                minimumHeight=Ui.dp(context,48);setTextColor(Ui.blue)
                setOnClickListener {
                    val error=onSave(draftName,draftCategory)
                    if(error==null) dialog.dismiss() else { input.error=error;input.requestFocus() }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).minimumHeight=Ui.dp(activity,48)
        }
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    fun dismiss() { dialog.dismiss() }
}
