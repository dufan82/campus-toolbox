package cn.campuslife.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

object Ui {
    val blue = Color.rgb(12,107,88)
    val ink = Color.rgb(24,40,37)
    val muted = Color.rgb(83,99,94)
    val background = Color.rgb(245,247,246)
    val line = Color.rgb(226,232,229)
    val pale = Color.rgb(232,243,238)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun text(context: Context, value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding=false
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setLineSpacing(dp(context, 3).toFloat(), 1f)
    }
    fun shape(context: Context, color: Int, radius: Int = 16, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
        border?.let { setStroke(dp(context, 1), it) }
    }
    fun button(context: Context, label: String, primary: Boolean = false, onClick: () -> Unit) = Button(context).apply {
        text = label; textSize = 14f; isAllCaps = false; minHeight = dp(context, 48); minimumHeight = dp(context, 48)
        setTextColor(if (primary) Color.WHITE else blue)
        minWidth=0; minimumWidth=0
        background = RippleDrawable(ColorStateList.valueOf(0x220c6b58), shape(context, if (primary) blue else pale, 12), null)
        setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
        setOnClickListener { onClick() }
    }
    fun card(context: Context) = column(context).apply {
        background = shape(context, Color.WHITE, 16, line)
        setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18))
    }
    fun space(context: Context, size: Int = 12) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(context, size)) }
    fun header(activity: Activity, title: String, subtitle: String? = null): LinearLayout = column(activity).apply {
        setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 12))
        val heading = row(activity)
        heading.addView(iconButton(activity,"back","返回") { activity.onBackPressed() },LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)))
        heading.addView(text(activity,title,19f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        addView(heading)
        subtitle?.let { addView(space(activity, 8)); addView(text(activity, it, 12f, muted)) }
    }
    fun page(activity: Activity) = column(activity).apply {
        setBackgroundColor(Ui.background)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window,false)
        activity.window.statusBarColor=Ui.background
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view,insets ->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        if(android.os.Build.VERSION.SDK_INT<27) activity.window.navigationBarColor=ink
        activity.setContentView(this)
        androidx.core.view.WindowCompat.getInsetsController(activity.window,this).apply {
            isAppearanceLightStatusBars=true; isAppearanceLightNavigationBars=true
        }
        androidx.core.view.ViewCompat.requestApplyInsets(this)
    }
    fun scroll(context: Context, content: View) = ScrollView(context).apply {
        isFillViewport = false; clipToPadding = false; addView(content)
    }
    fun input(context: Context, value: String, hintText: String, multiline: Boolean = false, changed: (String) -> Unit): EditText = EditText(context).apply {
        setText(value); hint = hintText; textSize = 15f; setTextColor(ink); setHintTextColor(muted)
        setSingleLine(!multiline); if (multiline) { minLines = 2; maxLines = 4; gravity = Gravity.TOP }
        minHeight = dp(context, 48); backgroundTintList = ColorStateList.valueOf(line)
        filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
    fun enabled(view: View, value: Boolean) {
        view.isEnabled = value
        if (view is ViewGroup) for (index in 0 until view.childCount) enabled(view.getChildAt(index), value)
    }
    fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    fun iconButton(context: Context,kind: String,label: String,onClick: ()->Unit)=button(context,"",onClick=onClick).apply {
        contentDescription=label
        background=RippleDrawable(ColorStateList.valueOf(0x220c6b58),shape(context,Color.TRANSPARENT,12),null)
        setCompoundDrawablesWithIntrinsicBounds(ToolGlyph(context,kind,ink),null,null,null)
        setPadding(dp(context,12),dp(context,12),dp(context,12),dp(context,12))
    }
}
