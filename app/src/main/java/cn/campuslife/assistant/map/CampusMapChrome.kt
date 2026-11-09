package cn.campuslife.assistant.map

import android.app.Activity
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import cn.campuslife.assistant.ui.ToolGlyph
import cn.campuslife.assistant.ui.Ui

/** Repositions the existing native controls; MapScreen still owns search/result state and tags. */
class CampusMapChrome(private val activity: Activity,private val screen: MapScreen) {
    private val root=screen.root
    private val stage=FrameLayout(activity)
    private val landscape=activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
    private val searchArea=Ui.column(activity)
    private val results=root.findViewWithTag<ScrollView>("map_results")
    private val choices=root.findViewWithTag<ScrollView>("map_search_choices")
    private val resultBody=results.getChildAt(0) as LinearLayout
    private val statusRow=screen.status.parent as LinearLayout
    private val searchButton=descendants(root).filterIsInstance<Button>().first { it.text.toString()=="搜索" }
    private val destination=cappedFrame(if(landscape) .48f else .4f)
    private var optionsDialog: Dialog?=null

    init {
        // Keep the original objects and listeners: no SDK, location, request or storage work here.
        listOf(screen.viewport,screen.query,searchButton,choices,results,statusRow,screen.locationStatus).forEach(::detach)
        root.removeAllViews()
        stage.addView(screen.viewport,FrameLayout.LayoutParams(-1,-1))
        root.addView(stage,LinearLayout.LayoutParams(-1,0,1f))
        val searchRow=Ui.row(activity).apply { gravity=Gravity.TOP }
        searchRow.addView(Ui.iconButton(activity,"back","返回") { activity.onBackPressed() }.apply {
            background=Ui.shape(context,Color.WHITE,14);elevation=dp(2).toFloat()
        },LinearLayout.LayoutParams(dp(48),dp(48)).apply { marginEnd=dp(8) })
        val searchField=Ui.row(activity).apply {
            background=Ui.shape(context,Color.WHITE,14);elevation=dp(2).toFloat()
        }
        screen.query.apply {
            hint="搜楼栋、食堂、广场";background=Ui.shape(context,Color.TRANSPARENT,12)
            setPadding(dp(12),dp(10),dp(4),dp(10))
        }
        searchButton.apply {
            text="";tag="map_search";contentDescription="搜索";minimumWidth=0;minWidth=0
            background=Ui.shape(context,Color.TRANSPARENT,12)
            setCompoundDrawablesWithIntrinsicBounds(ToolGlyph(context,"next",Ui.blue),null,null,null)
            setPadding(dp(12),dp(12),dp(12),dp(12))
        }
        searchField.addView(screen.query,LinearLayout.LayoutParams(0,-2,1f))
        searchField.addView(searchButton,LinearLayout.LayoutParams(dp(48),-2))
        searchRow.addView(searchField,LinearLayout.LayoutParams(0,-2,1f))
        searchArea.addView(searchRow)
        choices.apply { background=Ui.shape(context,Color.WHITE,14,Ui.line);elevation=dp(4).toFloat();clipToOutline=true }
        searchArea.addView(cappedFrame(.28f).apply { addView(choices,FrameLayout.LayoutParams(-1,-2)) },
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8);marginStart=dp(56) })
        listOf(statusRow,screen.locationStatus).forEach { message ->
            message.background=Ui.shape(activity,Color.WHITE,12)
            message.setPadding(dp(12),dp(10),dp(12),dp(10))
            searchArea.addView(message,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4) })
        }
        screen.status.textSize=13f
        screen.locationStatus.apply { tag="map_location_status";textSize=13f;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        searchArea.elevation=dp(5).toFloat()
        stage.addView(searchArea,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply {
            topMargin=dp(10);marginStart=dp(14);marginEnd=dp(14)
        })
        results.background=Ui.shape(activity,Color.WHITE,18,Ui.line)
        results.clipToOutline=true;destination.elevation=dp(4).toFloat()
        destination.addView(results,FrameLayout.LayoutParams(-1,-2))
        stage.addView(destination,FrameLayout.LayoutParams(
            if(landscape) dp(minOf(380,activity.resources.configuration.screenWidthDp-104)) else -1,
            -2,Gravity.BOTTOM or Gravity.START).apply {
            marginStart=dp(16);marginEnd=dp(16);bottomMargin=dp(16)
        })
        resultBody.setOnHierarchyChangeListener(object: ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewRemoved(parent: View?,child: View?) {}
            override fun onChildViewAdded(parent: View?,child: View?) {
                if(child?.tag=="map_navigate") styleDestination(child as Button)
            }
        })
    }

    private fun styleDestination(navigate: Button) {
        resultBody.setPadding(dp(18),dp(18),dp(18),dp(18))
        val heading=resultBody.getChildAt(0) as LinearLayout
        val title=heading.getChildAt(0) as TextView
        title.textSize=19f
        heading.addView(ImageView(activity).apply {
            setImageDrawable(ToolGlyph(context,"pin",Ui.blue));background=Ui.shape(context,Ui.pale,13)
            setPadding(dp(10),dp(10),dp(10),dp(10));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },0,LinearLayout.LayoutParams(dp(44),dp(44)).apply { marginEnd=dp(12) })
        navigate.text="步行导航";navigate.contentDescription="用高德步行导航到${title.text}"
    }

    fun addMapControls() {
        val buttons=Ui.column(activity)
        buttons.addView(Ui.iconButton(activity,"pin","我的位置") { screen.onLocate() }.apply {
            tag="map_locate";background=Ui.shape(context,Color.WHITE,14);elevation=dp(2).toFloat()
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        buttons.addView(Ui.space(activity,8))
        buttons.addView(Ui.textButton(activity,"全校") { screen.onCampus() }.apply {
            tag="map_campus";contentDescription="查看全校";background=Ui.shape(context,Color.WHITE,14);elevation=dp(2).toFloat()
            setPadding(dp(4),dp(8),dp(4),dp(8))
        },LinearLayout.LayoutParams(dp(48),-2))
        buttons.addView(Ui.space(activity,8))
        buttons.addView(Ui.iconButton(activity,"info","地图原图、说明与隐私设置") { options() }.apply {
            tag="map_options";background=Ui.shape(context,Color.WHITE,14);elevation=dp(2).toFloat()
        },LinearLayout.LayoutParams(dp(48),dp(48)))
        stage.addView(buttons,FrameLayout.LayoutParams(-2,-2,Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd=dp(16) })
        destination.addOnLayoutChangeListener { _,_,top,_,_,_,_,_,_ ->
            buttons.translationY=if(!landscape && results.visibility==View.VISIBLE)
                minOf(0,top-dp(12)-(buttons.top+buttons.height)).toFloat() else 0f
        }
    }

    private fun options() {
        optionsDialog?.dismiss()
        optionsDialog=Ui.bottomSheet(activity,"校园地图") { body,dialog ->
            body.addView(Ui.textButton(activity,"校园原图") { dialog.dismiss();screen.onReference() }.apply {
                tag="map_reference";contentDescription="校园原图对照与选楼"
            },LinearLayout.LayoutParams(-1,-2))
            screen.onMaintenance?.let { action ->
                body.addView(Ui.textButton(activity,"地点") { dialog.dismiss();action() }.apply {
                    tag="map_maintenance";contentDescription="新增、管理和隐藏地点"
                },LinearLayout.LayoutParams(-1,-2))
            }
            body.addView(Ui.textButton(activity,"地图说明与隐私设置") { dialog.dismiss();screen.onPrivacy() },LinearLayout.LayoutParams(-1,-2))
        }
    }

    fun close() { optionsDialog?.dismiss();optionsDialog=null }

    private fun cappedFrame(fraction: Float)=object: FrameLayout(activity) {
        override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
            val cap=(resources.displayMetrics.heightPixels*fraction).toInt()
            val offered=View.MeasureSpec.getSize(heightMeasureSpec).takeIf { it>0 } ?: cap
            super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(minOf(cap,offered),View.MeasureSpec.AT_MOST))
        }
    }
    private fun detach(view: View) { (view.parent as? ViewGroup)?.removeView(view) }
    private fun descendants(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun dp(value: Int)=Ui.dp(activity,value)
}
