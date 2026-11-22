package cn.campuslife.assistant.camera

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import cn.campuslife.assistant.ui.Ui
import cn.campuslife.watermark.CampusProfile
import cn.campuslife.watermark.FieldKey
import cn.campuslife.watermark.Position

internal fun showCameraSettings(
    activity: Activity, settings: CameraSettings, profile: CampusProfile,
    save: (CameraSettings, Map<FieldKey, String>) -> Boolean
) {
    val sheet = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    val root = Ui.column(activity).apply {
        background = Ui.shape(context, Color.WHITE, 20)
        clipToOutline = true
        isFocusableInTouchMode = true
    }
    val padding = Ui.dp(activity, 18)
    root.addView(Ui.row(activity).apply {
        setPadding(padding, Ui.dp(context, 7), Ui.dp(context, 8), 0)
        addView(Ui.text(context, "水印设置", 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(Ui.iconButton(activity, "close", "关闭") { sheet.dismiss() },
            LinearLayout.LayoutParams(Ui.dp(context,48), Ui.dp(context,48)))
    })
    val body = Ui.column(activity).apply {
        setPadding(padding, Ui.dp(context, 6), padding, Ui.dp(context, 12))
    }
    val scroll = object : ScrollView(activity) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val cap = (resources.displayMetrics.heightPixels * .62f).toInt()
            val offered = View.MeasureSpec.getSize(heightSpec).takeIf { it > 0 } ?: cap
            super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(minOf(offered, cap), View.MeasureSpec.AT_MOST))
        }
    }.apply { addView(body); clipToPadding = false }
    root.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
    body.addView(Ui.text(activity, "显示字段", 15f, bold = true))
    val checkboxes = CameraField.entries.associateWith { field ->
        CheckBox(activity).apply {
            text = field.label
            textSize = 14f
            setTextColor(Ui.ink)
            minHeight = Ui.dp(context, 46)
            isChecked = field in settings.fields
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Ui.ink, Ui.muted)
            )
            tag = "camera_field_" + field.name
        }
    }
    CameraField.entries.chunked(2).forEach { pair ->
        body.addView(Ui.row(activity).apply {
            pair.forEach { field -> addView(checkboxes.getValue(field), LinearLayout.LayoutParams(0, -2, 1f)) }
        })
    }
    body.addView(Ui.space(activity, 8))
    val position = Spinner(activity).apply {
        tag = "camera_position"
        contentDescription = "水印位置"
        minimumHeight = Ui.dp(context, 48)
        backgroundTintList = ColorStateList.valueOf(Ui.ink)
        setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 8), 0)
        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, Position.entries.map { it.label }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setSelection(settings.position.ordinal)
    }
    body.addView(Ui.row(activity).apply {
        addView(Ui.text(activity, "水印位置", 14f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(position, LinearLayout.LayoutParams(0, -2, 1.35f))
    })
    body.addView(Ui.space(activity, 18))
    body.addView(Ui.text(activity, "资料与备注", 15f, bold = true))
    body.addView(Ui.space(activity, 4))
    body.addView(Ui.text(activity, "姓名、班级和学号与照片水印共用", 12f, Ui.muted))
    val inputs = listOf(FieldKey.NAME, FieldKey.CLASS_NAME, FieldKey.STUDENT_ID).associateWith { key ->
        Ui.input(activity, profile.values[key].orEmpty(), key.label) {}.apply {
            tag = "camera_profile_" + key.name
            contentDescription = key.label
            filters = arrayOf(InputFilter.LengthFilter(48))
        }
    }
    inputs.forEach { (key, input) ->
        body.addView(Ui.space(activity, 10))
        body.addView(Ui.text(activity, key.label, 12f, Ui.muted))
        body.addView(Ui.space(activity, 3))
        body.addView(input)
    }
    body.addView(Ui.space(activity, 8))
    val note = Ui.input(activity, settings.note, "备注（选填）", multiline = true) {}.apply {
        filters = arrayOf(InputFilter.LengthFilter(100))
        tag = "camera_note"
    }
    body.addView(note)
    val done = Ui.button(activity, "完成", true) {
        val updates = inputs.mapValues { (_, field) -> field.text.toString() }.filter { (key, value) ->
            value != profile.values[key].orEmpty()
        }.mapValues { (_, value) -> cameraText(value, 48) }
        if (save(settings.copy(fields = checkboxes.filterValues { it.isChecked }.keys,
                position = Position.entries[position.selectedItemPosition.coerceAtLeast(0)],
                note = cameraText(note.text.toString(), 100)), updates)) sheet.dismiss()
    }.apply { tag = "camera_settings_done"; gravity = Gravity.CENTER }
    root.addView(done, LinearLayout.LayoutParams(-1,-2).apply {
        setMargins(padding, Ui.dp(activity, 8), padding, Ui.dp(activity, 12))
    })
    sheet.setContentView(root)
    sheet.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setGravity(Gravity.BOTTOM)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(.25f)
    }
    sheet.show()
    sheet.window?.setLayout(-1, -2)
    root.requestFocus()
}
