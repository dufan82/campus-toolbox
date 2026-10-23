package cn.campuslife.assistant.ui

import android.app.Activity
import android.os.Bundle
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
        root.addView(Ui.header(this,"我的校园资料","按需填写。勾选的字段会出现在水印中，空字段自动跳过。"))
        val fields = Ui.column(this).apply { setPadding(Ui.dp(context,18),0,Ui.dp(context,18),Ui.dp(context,16)) }
        root.addView(Ui.scroll(this,fields),LinearLayout.LayoutParams(-1,0,1f))
        defaultOrder.filter { it.personal }.forEach { field ->
            val card = Ui.card(this)
            val check = CheckBox(this).apply {
                text = "${field.label} · 在水印中显示"; textSize = 14f
                isChecked = if(savedInstanceState?.containsKey("visible_${field.name}")==true) savedInstanceState.getBoolean("visible_${field.name}") else field in config.enabled
                minHeight = Ui.dp(context,48)
                tag = "profile_visible_${field.name}"
            }
            val input = Ui.input(this, savedInstanceState?.getString("input_${field.name}") ?: profile.values[field].orEmpty(),"填写${field.label}，也可以留空") {}
            input.tag = "profile_${field.name}"
            if (field == FieldKey.STUDENT_ID || field == FieldKey.SEAT_NUMBER) {
                input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            card.addView(check); card.addView(input)
            if (field == FieldKey.STUDENT_ID) card.addView(Ui.text(this,"学号默认不展示。仅在你明确勾选后加入水印。",12f,Ui.muted))
            fields.addView(card); fields.addView(Ui.space(this,12))
            inputs[field] = input; selected[field] = check
        }
        val footer = Ui.column(this).apply { setPadding(Ui.dp(context,18),Ui.dp(context,10),Ui.dp(context,18),Ui.dp(context,12)) }
        footer.addView(Ui.button(this,"保存资料",true) {
            try {
                store.saveProfile(CampusProfile(profile.values+inputs.mapValues { it.value.text.toString() }))
                val enabled = store.config().enabled.filterNot { it.personal }.toSet() + selected.filterValues { it.isChecked }.keys
                store.saveConfig(store.config().copy(enabled=enabled))
                Ui.toast(this,"资料已保存在本机"); finish()
            } catch (error: Exception) { Ui.toast(this,error.message ?: "资料保存失败，请重试") }
        },LinearLayout.LayoutParams(-1,Ui.dp(this,52)))
        root.addView(footer)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        inputs.forEach { (key,input)-> outState.putString("input_${key.name}",input.text.toString()) }
        selected.forEach { (key,check)-> outState.putBoolean("visible_${key.name}",check.isChecked) }
        super.onSaveInstanceState(outState)
    }
}
