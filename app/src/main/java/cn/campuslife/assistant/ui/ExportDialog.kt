package cn.campuslife.assistant.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import cn.campuslife.assistant.image.PhotoAsset
import cn.campuslife.watermark.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A native export form. It never renders, uploads or saves the photo by itself. */
class ExportDialog(
    private val owner: Activity,private val photo: PhotoAsset,
    private val profile: CampusProfile,private val details: ActivityDetails,
    initialFields: List<FieldKey>,initialManualName: String?,initialFormat: ExportFormat,
    private val share: Boolean,
    private val onChanged: (List<FieldKey>,String?,ExportFormat)->Unit,
    private val onExport: (String,ExportFormat)->Unit
) : Dialog(owner) {
    private var fields=ExportNaming.fields(initialFields)
    private var manualName=initialManualName
    private var format=initialFormat
    private val timestamp=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.ROOT).format(Date())
    private lateinit var filename: EditText
    private lateinit var suffix: TextView
    private lateinit var error: TextView
    private lateinit var confirm: Button
    private val formats=linkedMapOf<ExportFormat,Button>()
    private var updating=false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=Ui.column(owner).apply {
            background=Ui.shape(context,Color.WHITE,24)
            setPadding(Ui.dp(context,20),Ui.dp(context,12),Ui.dp(context,20),Ui.dp(context,16))
            isFocusableInTouchMode=true
        }
        val heading=Ui.row(owner)
        heading.addView(Ui.text(owner,if(share) "分享照片" else "导出照片",21f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(Ui.button(owner,"取消") { dismiss() }.apply { background=ColorDrawable(Color.TRANSPARENT) })
        root.addView(heading)
        val form=Ui.column(owner)
        form.addView(Ui.space(owner,8));form.addView(Ui.text(owner,"命名内容",13f,Ui.muted));form.addView(Ui.space(owner,8))
        val order=(ExportNaming.defaultFields+defaultOrder).distinct()
        form.addView(FieldChips(owner,order,fields,"naming","命名包含") { selected ->
            fields=selected;manualName=null;replaceName(compose());notifyChanged()
        })
        form.addView(Ui.text(owner,"按点选顺序组合，空值跳过；学号默认不选。",12f,Ui.muted))
        val filenameHeading=Ui.row(owner)
        val label=Ui.text(owner,"文件名",13f,Ui.muted)
        filenameHeading.addView(label,LinearLayout.LayoutParams(0,-2,1f))
        filenameHeading.addView(Ui.button(owner,"重新生成") { manualName=null;replaceName(compose());notifyChanged() }.apply {
            tag="export_regenerate";background=ColorDrawable(Color.TRANSPARENT);textSize=12f
        })
        form.addView(filenameHeading)
        val nameRow=Ui.row(owner).apply {
            background=Ui.shape(context,Ui.background,10,Ui.line)
            setPadding(Ui.dp(context,12),0,Ui.dp(context,12),0)
        }
        filename=Ui.input(owner,manualName ?: compose(),"填写文件名") { value ->
            if(!updating){manualName=value;validate();notifyChanged()}
        }.apply {
            id=View.generateViewId();tag="export_filename";textSize=14f
            background=ColorDrawable(Color.TRANSPARENT)
            filters=arrayOf(android.text.InputFilter.LengthFilter(200))
            setSelectAllOnFocus(true)
            setOnFocusChangeListener { _,focused ->
                if(!focused && manualName!=null) {
                    val base=ExportNaming.baseName(text.toString())
                    if(base!=text.toString()){manualName=base;replaceName(base);notifyChanged()}
                }
            }
        }
        label.labelFor=filename.id
        nameRow.addView(filename,LinearLayout.LayoutParams(0,Ui.dp(owner,52),1f))
        suffix=Ui.text(owner,"",14f,Ui.muted).apply { tag="export_suffix" }
        nameRow.addView(suffix);form.addView(nameRow)
        error=Ui.text(owner,"",12f,0xffb7433e.toInt()).apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;visibility=View.GONE;tag="export_name_error" }
        form.addView(error);form.addView(Ui.space(owner,16));form.addView(Ui.text(owner,"格式",13f,Ui.muted));form.addView(Ui.space(owner,8))
        val formatRow=Ui.row(owner)
        ExportFormat.entries.forEach { selected ->
            val button=Ui.button(owner,selected.name) { format=selected;refreshFormat();notifyChanged() }.apply { tag="export_format_${selected.name}" }
            formats[selected]=button;formatRow.addView(button,LinearLayout.LayoutParams(0,Ui.dp(owner,48),1f).apply { marginEnd=Ui.dp(owner,4) })
        }
        form.addView(formatRow);form.addView(Ui.space(owner,18))
        val size=Ui.row(owner);size.addView(Ui.text(owner,"原图尺寸",14f),LinearLayout.LayoutParams(0,-2,1f))
        size.addView(Ui.text(owner,"${photo.width} × ${photo.height}",14f,Ui.muted));form.addView(size)
        form.addView(Ui.space(owner,8));form.addView(Ui.text(owner,"创建新文件，不覆盖原图；同名照片也不会被覆盖。",12f,Ui.muted));form.addView(Ui.space(owner,14))
        val scroll=object: ScrollView(owner) {
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                val available=minOf(View.MeasureSpec.getSize(heightMeasureSpec),(resources.displayMetrics.heightPixels*.66).toInt())
                super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(available,View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(form);isFillViewport=false }
        root.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        root.addView(Ui.space(owner,10));root.addView(Ui.text(owner,"文件名会随照片共享，不会印在水印中。",12f,Ui.muted));root.addView(Ui.space(owner,12))
        confirm=Ui.button(owner,if(share) "确认分享" else "确认保存",true) {
            if(validate()) {
                confirm.isEnabled=false
                val base=ExportNaming.baseName(filename.text.toString())
                dismiss();onExport(base,format)
            }
        }.apply { tag="export_confirm" }
        root.addView(confirm,LinearLayout.LayoutParams(-1,Ui.dp(owner,52)))
        setContentView(root);refreshFormat();validate();root.requestFocus()
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setDimAmount(.28f)
        }
    }
    private fun compose()=ExportNaming.compose(profile,details,fields,timestamp)
    private fun notifyChanged() { onChanged(fields,manualName,format) }
    private fun replaceName(value: String) { updating=true;filename.setText(value);filename.setSelection(value.length);updating=false;validate() }
    private fun refreshFormat() {
        suffix.text=if(format==ExportFormat.PNG) ".png" else ".jpg"
        formats.forEach { (value,button) -> button.background=Ui.shape(owner,if(value==format) Ui.pale else Ui.background,10,if(value==format)Ui.blue else Ui.line);button.isSelected=value==format }
    }
    private fun validate(): Boolean {
        if(!::confirm.isInitialized)return true
        val problem=ExportNaming.error(ExportNaming.baseName(filename.text.toString()))
        error.text=problem.orEmpty();error.visibility=if(problem==null)View.GONE else View.VISIBLE
        confirm.isEnabled=problem==null;return problem==null
    }
}
