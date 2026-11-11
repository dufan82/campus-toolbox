package cn.campuslife.assistant.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import cn.campuslife.assistant.data.LocalStore
import cn.campuslife.watermark.*

class ProfileActivity : Activity() {
    private lateinit var store: LocalStore
    private val inputs = linkedMapOf<FieldKey,EditText>()
    private val selected = linkedMapOf<FieldKey,CheckBox>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalStore(this)
        val profile = store.profile(); val config = store.config()
        val root = Ui.page(this)
        root.addView(Ui.header(this,"水印资料"))
        val fields = Ui.column(this).apply { setPadding(Ui.dp(context,20),Ui.dp(context,12),Ui.dp(context,20),Ui.dp(context,20)) }
        fields.addView(Ui.text(this,"勾选“展示”后加入水印，空项跳过。",14f,Ui.muted))
        fields.addView(Ui.space(this,12))
        val form=Ui.column(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(Ui.dp(context,14),0,Ui.dp(context,14),Ui.dp(context,12))
            background=Ui.shape(context,Color.WHITE,16,Ui.line)
        }
        fields.addView(form)
        val scroller=Ui.scroll(this,fields).apply { tag="profile_fields" }
        root.addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
        defaultOrder.filter { it.personal }.forEach { field ->
            if(inputs.isNotEmpty()) {
                form.addView(Ui.space(this,12))
                form.addView(View(this).apply { setBackgroundColor(Ui.line) },LinearLayout.LayoutParams(-1,Ui.dp(this,1)))
            }
            val heading=Ui.row(this)
            heading.addView(Ui.text(this,field.label,15f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
            val check = CheckBox(this).apply {
                text = "展示"; textSize = 14f;setTextColor(Ui.muted)
                isChecked = if(savedInstanceState?.containsKey("visible_${field.name}")==true) savedInstanceState.getBoolean("visible_${field.name}") else field in config.enabled
                minHeight = Ui.dp(context,48)
                tag = "profile_visible_${field.name}"
                contentDescription="在水印中展示${field.label}"
                buttonTintList=ColorStateList.valueOf(Ui.blue)
            }
            val input = Ui.input(this, savedInstanceState?.getString("input_${field.name}") ?: profile.values[field].orEmpty(),"填写${field.label}（选填）") {}
            input.tag = "profile_${field.name}";input.contentDescription=field.label
            if (field == FieldKey.STUDENT_ID || field == FieldKey.SEAT_NUMBER) {
                input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            heading.addView(check)
            form.addView(heading);form.addView(input)
            inputs[field] = input; selected[field] = check
        }
        fields.addView(Ui.space(this,12))
        fields.addView(Ui.text(this,"学号仅在勾选后显示。",13f,Ui.muted))
        fields.addView(Ui.space(this,8))
        val footer = Ui.column(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(Ui.dp(context,18),Ui.dp(context,10),Ui.dp(context,18),Ui.dp(context,12))
        }
        footer.addView(Ui.button(this,"保存资料",true) {
            try {
                store.saveProfile(CampusProfile(profile.values+inputs.mapValues { it.value.text.toString() }))
                val enabled = store.config().enabled.filterNot { it.personal }.toSet() + selected.filterValues { it.isChecked }.keys
                store.saveConfig(store.config().copy(enabled=enabled))
                Ui.toast(this,"资料已保存"); finish()
            } catch (error: Exception) { Ui.toast(this,error.message ?: "资料保存失败，请重试") }
        }.apply { tag="save_profile";minimumHeight=Ui.dp(context,52) },LinearLayout.LayoutParams(-1,-2))
        root.addView(footer)
        // Keep a focused lower field visible when Android resizes above the IME.
        inputs.values.forEach { input -> input.setOnFocusChangeListener { _,focused ->
            if(focused) input.post { input.requestRectangleOnScreen(android.graphics.Rect(0,0,input.width,input.height),true) }
        } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        inputs.forEach { (key,input)-> outState.putString("input_${key.name}",input.text.toString()) }
        selected.forEach { (key,check)-> outState.putBoolean("visible_${key.name}",check.isChecked) }
        super.onSaveInstanceState(outState)
    }
}
