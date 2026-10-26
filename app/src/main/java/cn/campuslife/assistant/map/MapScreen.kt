package cn.campuslife.assistant.map

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import cn.campuslife.assistant.ui.Ui

/** Search choices belong to the search area; the footer only owns the selected destination. */
class MapScreen(private val activity: Activity) {
    val root=Ui.page(activity)
    val viewport=FrameLayout(activity).apply { setBackgroundColor(Ui.pale);minimumHeight=Ui.dp(context,120) }
    val status=Ui.text(activity,"",12f,Ui.muted).apply { tag="map_status";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
    val locationStatus=Ui.text(activity,"",12f,Ui.muted).apply { visibility=View.GONE }
    val query=Ui.input(activity,"","搜索楼栋或地点") {}.apply {
        tag="map_query";contentDescription="搜索地点";imeOptions=EditorInfo.IME_ACTION_SEARCH
        filters=arrayOf(InputFilter.LengthFilter(80));background=Ui.shape(context,Color.WHITE,12,Ui.line)
        setPadding(Ui.dp(context,12),Ui.dp(context,8),Ui.dp(context,8),Ui.dp(context,8))
    }
    var onSearch: ()->Unit = {}
    var onLocate: ()->Unit = {}
    var onCampus: ()->Unit = {}
    var onPrivacy: ()->Unit = {}
    var onReference: ()->Unit = {}
    var onMaintenance: (()->Unit)? = null
        set(value) { field=value;maintenanceAction?.visibility=if(value==null) View.GONE else View.VISIBLE }
    var onChoose: (MapPlace)->Unit = {}
    var onNavigate: (MapPlace)->Unit = {}
    private var maintenanceAction: Button?=null
    private val landscape=activity.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
    private val resultBody=Ui.column(activity)
    private val resultScroll=Ui.scroll(activity,resultBody).apply { setBackgroundColor(Color.WHITE);visibility=View.GONE;tag="map_results" }
    private val suggestions=Ui.column(activity)
    private val suggestionScroll=Ui.scroll(activity,suggestions).apply { visibility=View.GONE;tag="map_search_choices";background=Ui.shape(context,Color.WHITE,12,Ui.line) }
    private val searchButton=Ui.button(activity,"搜索",true) { onSearch() }.apply { minimumWidth=Ui.dp(context,64);minimumHeight=Ui.dp(context,50) }
    private val spinner=ProgressBar(activity,null,android.R.attr.progressBarStyleSmall).apply { visibility=View.GONE }
    private val statusRow=Ui.row(activity).apply { visibility=View.GONE }

    init {
        val header=Ui.header(activity,"平潭校区")
        (header.getChildAt(0) as LinearLayout).addView(Ui.button(activity,"原图") { onReference() }.apply {
            tag="map_reference";contentDescription="校园原图对照与选楼";minimumHeight=Ui.dp(context,48)
        })
        maintenanceAction=Ui.button(activity,"地点") { onMaintenance?.invoke() }.apply {
            tag="map_maintenance";contentDescription="新增、管理和隐藏地点";minimumHeight=Ui.dp(context,48);visibility=View.GONE
        }
        (header.getChildAt(0) as LinearLayout).addView(maintenanceAction)
        (header.getChildAt(0) as LinearLayout).addView(Ui.iconButton(activity,"info","地图说明与隐私设置") { onPrivacy() },
            LinearLayout.LayoutParams(Ui.dp(activity,48),Ui.dp(activity,48)))
        root.addView(header)
        val controls=Ui.column(activity).apply { setPadding(Ui.dp(context,16),0,Ui.dp(context,16),Ui.dp(context,8)) }
        val inputRow=Ui.row(activity)
        query.minimumHeight=Ui.dp(activity,50)
        inputRow.addView(query,LinearLayout.LayoutParams(0,-2,1f))
        inputRow.addView(searchButton,LinearLayout.LayoutParams(-2,-2).apply { marginStart=Ui.dp(activity,8) })
        controls.addView(inputRow)
        controls.addView(suggestionScroll,LinearLayout.LayoutParams(-1,Ui.dp(activity,160)).apply { topMargin=Ui.dp(activity,8) })
        statusRow.addView(spinner,LinearLayout.LayoutParams(Ui.dp(activity,18),Ui.dp(activity,18)).apply { marginEnd=Ui.dp(activity,8) })
        statusRow.addView(status,LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(statusRow);controls.addView(locationStatus)
        if(landscape) {
            val body=Ui.row(activity)
            controls.addView(resultScroll,LinearLayout.LayoutParams(-1,-2).apply { topMargin=Ui.dp(activity,12) })
            body.addView(Ui.scroll(activity,controls),LinearLayout.LayoutParams(Ui.dp(activity,300),-1))
            body.addView(viewport,LinearLayout.LayoutParams(0,-1,1f))
            root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        } else {
            root.addView(controls)
            root.addView(viewport,LinearLayout.LayoutParams(-1,0,1f))
            root.addView(resultScroll,LinearLayout.LayoutParams(-1,-2))
        }
        query.setOnEditorActionListener { _,action,_ -> if(action==EditorInfo.IME_ACTION_SEARCH) { onSearch();true } else false }
    }

    fun busy(value: Boolean,message: String) {
        spinner.visibility=if(value) View.VISIBLE else View.GONE;status.text=message
        statusRow.visibility=if(value || message.isNotBlank()) View.VISIBLE else View.GONE
        searchButton.isEnabled=!value
    }

    fun locationMessage(message: String) {
        locationStatus.text=message;locationStatus.visibility=if(message.isBlank()) View.GONE else View.VISIBLE
    }

    fun addMapControls() {
        val buttons=Ui.column(activity)
        buttons.addView(Ui.button(activity,"我的位置") { onLocate() }.apply { tag="map_locate";minimumHeight=Ui.dp(context,48) },LinearLayout.LayoutParams(-1,-2))
        buttons.addView(Ui.space(activity,8))
        buttons.addView(Ui.button(activity,"查看全校") { onCampus() }.apply { minimumHeight=Ui.dp(context,48) },LinearLayout.LayoutParams(-1,-2))
        viewport.addView(buttons,FrameLayout.LayoutParams(Ui.dp(activity,100),-2,Gravity.TOP or Gravity.END).apply { topMargin=Ui.dp(activity,12);marginEnd=Ui.dp(activity,12) })
    }

    fun results(selected: MapPlace?,origin: GeoPoint?,originLabel: String) {
        resultBody.removeAllViews();suggestions.removeAllViews();suggestionScroll.visibility=View.GONE
        resultScroll.visibility=if(selected==null) View.GONE else View.VISIBLE
        if(selected==null) return
        resultBody.setPadding(Ui.dp(activity,16),Ui.dp(activity,12),Ui.dp(activity,16),Ui.dp(activity,12))
        val heading=Ui.row(activity)
        heading.addView(Ui.text(activity,selected.title,17f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        resultBody.addView(heading)
        origin?.let { resultBody.addView(Ui.text(activity,originLabel+" · "+MapRules.distanceLabel(MapRules.distanceMeters(it,selected.point)),12f,Ui.muted)) }
        resultBody.addView(Ui.space(activity,8))
        resultBody.addView(Ui.button(activity,"高德步行导航",true) { onNavigate(selected) }.apply { tag="map_navigate";minimumHeight=Ui.dp(context,48) },LinearLayout.LayoutParams(-1,-2))
    }

    fun campusResults(items: List<LocatedLandmark>,selected: MapPlace?,origin: GeoPoint?,originLabel: String,showChoices: Boolean=true) {
        results(selected,origin,originLabel)
        if(selected!=null || query.text.isBlank() || items.isEmpty() || !showChoices) return
        suggestionScroll.visibility=View.VISIBLE
        items.forEach { item ->
            val place=item.point?.let { MapPlace(item.landmark.id,item.landmark.name,item.landmark.category.label,it) }
            val row=Ui.column(activity).apply {
                tag="campus_place_"+item.landmark.id;minimumHeight=Ui.dp(context,64)
                setPadding(Ui.dp(context,12),Ui.dp(context,8),Ui.dp(context,12),Ui.dp(context,8))
                isClickable=place!=null;isFocusable=place!=null
                if(place!=null) {
                    background=RippleDrawable(ColorStateList.valueOf(0x220c6b58),Ui.shape(context,Color.WHITE,8),null)
                    setOnClickListener { onChoose(place) }
                }
                contentDescription=if(place==null) item.landmark.name+"，位置待核对，暂不可导航" else item.landmark.name+"，点击查看位置"
            }
            row.addView(Ui.text(activity,item.landmark.name,15f,bold=true))
            if(place==null) row.addView(Ui.text(activity,"位置待核对 · 暂不可导航",12f,Ui.muted))
            suggestions.addView(row,LinearLayout.LayoutParams(-1,-2))
        }
    }
}
