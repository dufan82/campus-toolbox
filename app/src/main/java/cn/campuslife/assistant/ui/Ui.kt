package cn.campuslife.assistant.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
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
import android.view.Window
import android.view.WindowManager
import android.widget.*

object Ui {
    // Keep the existing API names so a visual change cannot alter feature/storage identities.
    val blue = Brand.navy
    val ink = Brand.navy
    val muted = Color.rgb(92,106,122)
    val background = Brand.mist
    val line = Color.rgb(225,230,236)
    val pale = Color.rgb(235,240,245)
    val teal = Brand.teal
    val orange = Brand.orange
    val tealTint = Color.rgb(231,246,242)
    val orangeTint = Color.rgb(255,243,230)
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
        background = RippleDrawable(ColorStateList.valueOf(0x22172b49), shape(context, if (primary) blue else pale, 12), null)
        setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
        setOnClickListener { onClick() }
    }
    fun textButton(context: Context,label: String,onClick: ()->Unit) = button(context,label,onClick=onClick).apply {
        background=RippleDrawable(ColorStateList.valueOf(0x18172b49),shape(context,Color.TRANSPARENT,10),null)
        setPadding(dp(context,8),dp(context,6),dp(context,8),dp(context,6))
    }
    fun card(context: Context) = column(context).apply {
        background = shape(context, Color.WHITE, 16, line)
        setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18))
    }
    fun space(context: Context, size: Int = 12) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(context, size)) }
    fun header(activity: Activity, title: String, subtitle: String? = null): LinearLayout = column(activity).apply {
        setBackgroundColor(Color.WHITE)
        setPadding(dp(activity, 12), dp(activity, 5), dp(activity, 16), dp(activity, 10))
        val heading = row(activity)
        heading.addView(iconButton(activity,"back","返回") { activity.onBackPressed() },LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)))
        heading.addView(text(activity,title,21f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        addView(heading)
        subtitle?.let { addView(space(activity, 8)); addView(text(activity, it, 12f, muted)) }
    }
    fun page(activity: Activity) = column(activity).apply {
        setBackgroundColor(Ui.background)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window,false)
        activity.window.statusBarColor=Color.WHITE
        if(android.os.Build.VERSION.SDK_INT>=27) activity.window.navigationBarColor=Color.WHITE
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
        minHeight = dp(context, 48); backgroundTintList = null
        background=shape(context,Color.WHITE,10,line)
        setPadding(dp(context,12),dp(context,10),dp(context,12),dp(context,10))
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
        background=RippleDrawable(ColorStateList.valueOf(0x2235b6a0),shape(context,Color.TRANSPARENT,12),null)
        setCompoundDrawablesWithIntrinsicBounds(ToolGlyph(context,kind,ink),null,null,null)
        setPadding(dp(context,12),dp(context,12),dp(context,12),dp(context,12))
    }
    /** The three main destinations. Tool IDs and saved home layout remain independent. */
    fun bottomNavigation(activity: Activity,active: String): View = row(activity).apply {
        tag="main_bottom_navigation";setBackgroundColor(Color.WHITE)
        setPadding(dp(context,8),dp(context,5),dp(context,8),dp(context,4))
        listOf(Triple("home","home","首页"),Triple("schedule","calendar","课表"),Triple("settings","user","我的")).forEach { (id,glyph,label) ->
            val selected=id==active
            val item=column(context).apply {
                gravity=Gravity.CENTER;minimumHeight=dp(context,56);isClickable=true;isFocusable=true
                tag="bottom_nav_$id";contentDescription=label;isSelected=selected
                background=RippleDrawable(ColorStateList.valueOf(0x18172b49),shape(context,if(selected)tealTint else Color.WHITE,12),null)
                setOnClickListener {
                    if(!selected) MainNavigation.navigate(activity,active,id)
                }
            }
            item.addView(ImageView(context).apply {
                setImageDrawable(ToolGlyph(context,glyph,if(selected) blue else muted));setPadding(dp(context,13),dp(context,3),dp(context,13),dp(context,3))
                if(selected) background=shape(context,pale,14)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(dp(context,50),dp(context,28)))
            item.addView(space(context,3));item.addView(text(context,label,11f,if(selected) blue else muted,bold=selected).apply { gravity=Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            addView(item,LinearLayout.LayoutParams(0,-2,1f))
        }
    }
    /** Platform Dialog, not a new UI dependency; tall content remains scrollable. */
    fun bottomSheet(activity: Activity,title: String,build: (LinearLayout,Dialog)->Unit): Dialog {
        val dialog=Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body=column(activity).apply { setPadding(dp(context,20),dp(context,9),dp(context,20),dp(context,20)) }
        body.addView(View(activity).apply { background=shape(context,line,3) },LinearLayout.LayoutParams(dp(activity,32),dp(activity,4)).apply { gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(activity,12) })
        val heading=row(activity)
        heading.addView(text(activity,title,20f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(iconButton(activity,"close","关闭") { dialog.dismiss() },LinearLayout.LayoutParams(dp(activity,48),dp(activity,48)))
        body.addView(heading);body.addView(space(activity,10))
        val content=column(activity);body.addView(content,LinearLayout.LayoutParams(-1,-2));build(content,dialog)
        val scroller=object: ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                val cap=(resources.displayMetrics.heightPixels*.76f).toInt()
                val offered=View.MeasureSpec.getSize(heightMeasureSpec).takeIf { it>0 } ?: cap
                super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(minOf(offered,cap),View.MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport=false;addView(body);background=shape(activity,Color.WHITE,22);clipToOutline=true }
        dialog.setContentView(scroller)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM);setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);setDimAmount(.25f)
        }
        dialog.show();dialog.window?.setLayout(-1,-2)
        return dialog
    }
}
