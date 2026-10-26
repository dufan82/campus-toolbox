package cn.campuslife.assistant.schedule

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import cn.campuslife.assistant.map.CampusCoordinateData
import cn.campuslife.assistant.map.CampusCoordinateStore
import cn.campuslife.assistant.map.CampusMapActivity
import cn.campuslife.assistant.ui.RetainedWork
import cn.campuslife.assistant.ui.Ui
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import org.json.JSONObject

/** Local manual timetable. Registration and home/map routing are owned by the controller. */
class ScheduleActivity : Activity() {
    private class Session(context: Context) {
        val store = ScheduleStore(context)
        val mapStore = CampusCoordinateStore(context)
        val work = RetainedWork<ScheduleActivity>({ Handler(Looper.getMainLooper()).post(it) })
        var started = false
        var data: ScheduleData? = null
        var map: CampusCoordinateData? = null
        var readError = false
        var notice = ""
    }
    private lateinit var session: Session
    private lateinit var page: LinearLayout
    private var draft: Bundle? = null
    // A narrow baseline: settings plus only the course being edited, never unrelated records.
    private var draftBase: ScheduleData? = null
    private var editor: AlertDialog? = null
    private var prompt: AlertDialog? = null
    private var editorBody: LinearLayout? = null
    private var editorError: TextView? = null
    private var mode = "week"
    private var selectedWeek = 0
    private var detailCourseId: String? = null
    private var displayedCourse: Course? = null
    private var platformBack: android.window.OnBackInvokedCallback? = null
    private val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val ready get() = session.data != null && !session.readError && !session.work.busy

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = lastNonConfigurationInstance as? Session ?: Session(applicationContext)
        draft = savedInstanceState?.getBundle("schedule.editor")
        draftBase = savedInstanceState?.getString("schedule.editorBase")?.let { encoded ->
            runCatching { ScheduleStore.decode(JSONObject(encoded)) }.getOrNull()
        }
        mode = savedInstanceState?.getString("schedule.mode") ?: "week"
        selectedWeek = savedInstanceState?.getInt("schedule.week", 0) ?: 0
        detailCourseId = savedInstanceState?.getString("schedule.detail")
        page = Ui.page(this)
        if (Build.VERSION.SDK_INT >= 33) {
            platformBack = android.window.OnBackInvokedCallback { requestBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
        session.work.bind(this) { _, _ -> render() }
        if (!session.started) {
            session.started = true
            val store = session.store; val mapStore = session.mapStore
            session.work.submit("正在读取课程表…", {
                store.load() to runCatching { mapStore.load() }
            }, { result ->
                result.onSuccess { (data, map) ->
                    session.data = data; session.map = map.getOrNull()
                    if (map.isFailure) session.notice = "校园地点读取失败，课程仍可编辑，地点导航暂不可用。"
                }.onFailure { session.readError = true }
                render()
            })
        }
    }

    override fun onResume() {
        super.onResume()
        if (session.data != null && !session.work.busy) {
            val store = session.store; val mapStore = session.mapStore
            session.work.submit("正在刷新课程表…", { store.load() to runCatching { mapStore.load() } }, { result ->
                result.onSuccess { (data, map) ->
                    if (draft != null && data != session.data) session.notice = "其他页面的更新已载入，正在编辑的草稿已保留。"
                    session.data = data; session.readError = false; session.map = map.getOrNull()
                    if (map.isFailure) session.notice = "校园地点读取失败，课程仍可编辑，地点导航暂不可用。"
                    if (draft?.getString("type") == "list") dismissEditor()
                }.onFailure { session.readError = true }
                render()
                updateNavigation()
            })
        }
    }

    override fun onRetainNonConfigurationInstance(): Any = session
    @Deprecated("Platform compatibility entry point")
    override fun onBackPressed() = requestBack()
    private fun requestBack() {
        if (session.work.busy) { Ui.toast(this, "正在处理课程表，请稍候再返回"); return }
        if (prompt != null) prompt?.dismiss() else if (editor != null) closeEditor() else finish()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        draft?.let { outState.putBundle("schedule.editor", Bundle(it)) }
        draftBase?.let { outState.putString("schedule.editorBase", ScheduleStore.encode(it).toString()) }
        outState.putString("schedule.mode", mode); outState.putInt("schedule.week", selectedWeek)
        detailCourseId?.let { outState.putString("schedule.detail", it) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) platformBack?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        session.work.detach(this)
        prompt?.setOnDismissListener(null); prompt?.dismiss()
        editor?.setOnDismissListener(null); editor?.dismiss()
        if (!isChangingConfigurations) session.work.close()
        super.onDestroy()
    }

    private fun render() {
        page.removeAllViews()
        val header = Ui.header(this, "课程表")
        (header.getChildAt(0) as LinearLayout).addView(quietButton("管理") { openSettings() }.apply { isEnabled = ready }, LinearLayout.LayoutParams(-2, -2))
        page.addView(header)
        val body = Ui.column(this).apply { setPadding(dp(12), 0, dp(12), dp(24)) }
        page.addView(Ui.scroll(this, body), LinearLayout.LayoutParams(-1, 0, 1f))
        val data = session.data
        val status = when {
            session.readError -> "课程表读取失败，原文件已保留。请勿卸载应用；可退出后重试。"
            data == null -> "正在读取课程表…"
            session.work.busy -> session.work.label
            else -> session.notice
        }
        if (status.isNotEmpty()) body.addView(Ui.text(this, status, 14f, Ui.muted))
        val direct = Ui.row(this)
        direct.addView(Ui.button(this, "周课表", mode == "week") { mode = "week"; render() }.apply { isEnabled = data?.semester != null },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        direct.addView(Ui.button(this, "今日", mode == "today") {
            mode = "today"; render()
        }.apply { isEnabled = data?.semester != null }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(direct)
        body.addView(Ui.space(this, 10))
        if (data != null) {
            val semester = data.semester
            if (semester == null) body.addView(Ui.text(this, "请先设置学期", 17f, bold = true))
            else {
                val position = ScheduleCalendar.position(semester, LocalDate.now())
                body.addView(Ui.text(this, when (position.phase) {
                    SemesterPhase.BEFORE -> "学期尚未开始"
                    SemesterPhase.ACTIVE -> "今天是第${position.week}周 · ${days[LocalDate.now().dayOfWeek.value - 1]}"
                    SemesterPhase.AFTER -> "本学期已结束"
                }, 13f, Ui.muted))
                if (selectedWeek == 0) selectedWeek = position.week ?: if (position.phase == SemesterPhase.BEFORE) 1 else semester.totalWeeks
                selectedWeek = selectedWeek.coerceIn(1, semester.totalWeeks)
                if (mode == "today") {
                    val courses = ScheduleCalendar.today(data, LocalDate.now())
                    if (courses.isEmpty()) body.addView(Ui.text(this, "今日暂无课程", 17f))
                    courses.forEach { course -> courseCard(body, course, data) }
                } else {
                    val paging = Ui.row(this)
                    paging.addView(quietButton("上一周") { selectedWeek--; render() }.apply { isEnabled = selectedWeek > 1 },
                        LinearLayout.LayoutParams(0, -2, 1f))
                    paging.addView(Ui.text(this, "第${selectedWeek}周", 19f, bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
                    paging.addView(quietButton("下一周") { selectedWeek++; render() }.apply { isEnabled = selectedWeek < semester.totalWeeks },
                        LinearLayout.LayoutParams(0, -2, 1f))
                    body.addView(paging)
                    val weekInfo = Ui.row(this)
                    val from = semester.startMonday.plusWeeks((selectedWeek - 1).toLong()); val to = from.plusDays(6)
                    weekInfo.addView(Ui.text(this, "${from.monthValue}/${from.dayOfMonth} — ${to.monthValue}/${to.dayOfMonth}", 12f, Ui.muted), LinearLayout.LayoutParams(0, -2, 1f))
                    weekInfo.addView(quietButton("回本周") { selectedWeek = position.week ?: if (position.phase == SemesterPhase.BEFORE) 1 else semester.totalWeeks; render() })
                    body.addView(weekInfo)
                    val courses = ScheduleCalendar.week(data, selectedWeek)
                    if (courses.isEmpty()) body.addView(Ui.text(this, "本周暂无课程", 17f))
                    weekGrid(body, data)
                }
            }
        }
        editorBody?.let { Ui.enabled(it, ready) }
        editor?.let { dialog ->
            val canCommit = ready && (draft?.getString("type") == "list" || draftBase != null)
            listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL).forEach { dialog.getButton(it)?.isEnabled = canCommit }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = !session.work.busy
            dialog.setCancelable(!session.work.busy)
        }
        if (editor == null && draft != null && ready) showEditor()
        if (editor == null && detailCourseId != null && ready) {
            val course = session.data!!.courses.find { it.id == detailCourseId }
            if (course == null) {
                val previous = prompt
                prompt = null; displayedCourse = null; detailCourseId = null
                previous?.setOnDismissListener(null); previous?.dismiss()
                session.notice = "该课程已被删除，详情已关闭。"
                render(); return
            } else if (prompt == null || (displayedCourse != null && displayedCourse != course)) openCourseDetails(course)
        }
        updateNavigation()
    }

    private fun openSettings() {
        if (!ready) return
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (session.data?.semester != null) actions.add("新增课程" to { openCourse(null) })
        actions.add("学校导入 / 备份与恢复" to { startActivity(Intent(this, ScheduleTransferActivity::class.java)) })
        actions.add("学期设置" to { openSemester() })
        actions.add("节次设置" to { openPeriods() })
        actions.add("全部课程" to { draft = Bundle().apply { putString("type", "list") }; showEditor() })
        showPrompt(AlertDialog.Builder(this).setTitle("课程表管理")
            .setItems(actions.map { it.first }.toTypedArray()) { _, index ->
                if (ready) actions[index].second()
            }.setNegativeButton("取消", null))
    }

    private fun weekGrid(body: LinearLayout, data: ScheduleData) {
        body.addView(ScheduleWeekView(this, data, selectedWeek, ready) { choices ->
            if (choices.size == 1) openCourseDetails(choices.single())
            else showPrompt(AlertDialog.Builder(this).setTitle("选择重叠的课程")
                .setItems(choices.map { "${it.name} · ${SchedulePresentation.periodRange(it)}" }.toTypedArray()) { _, index -> openCourseDetails(choices[index]) }
                .setNegativeButton("取消", null))
        })
    }
    private fun openCourseDetails(course: Course) {
        if (!ready) return
        if (displayedCourse != null) {
            val previous = prompt
            prompt = null
            previous?.setOnDismissListener(null); previous?.dismiss()
        }
        detailCourseId = course.id
        displayedCourse = course
        val body = Ui.column(this).apply { setPadding(dp(22), dp(10), dp(22), dp(12)) }
        body.addView(Ui.text(this, course.name, 21f, bold = true))
        body.addView(Ui.space(this, 10)); body.addView(Ui.text(this, SchedulePresentation.periodRange(course), 15f, Ui.blue, true))
        body.addView(Ui.space(this, 22)); body.addView(Ui.text(this, "教室", 12f, Ui.muted)); body.addView(Ui.space(this, 4))
        body.addView(Ui.text(this, course.classroom.ifBlank { "未填写" }, 17f))
        body.addView(Ui.space(this, 18)); body.addView(Ui.text(this, "教师", 12f, Ui.muted)); body.addView(Ui.space(this, 4))
        body.addView(Ui.text(this, course.teacher.ifBlank { "未填写" }, 17f))
        showPrompt(AlertDialog.Builder(this).setTitle("课程详情").setView(Ui.scroll(this, body))
            .setPositiveButton("关闭", null).setNeutralButton("编辑课程") { _, _ ->
                detailCourseId = null
                session.data?.courses?.find { it.id == course.id }?.let { openCourse(it) }
            })
        val dialog = prompt!!
        dialog.setOnDismissListener {
            if (prompt === dialog) {
                prompt = null; displayedCourse = null
                if (detailCourseId == course.id) detailCourseId = null
            }
        }
    }
    private fun courseCard(body: LinearLayout, course: Course, data: ScheduleData) {
        if (draft?.getString("type") != "list") {
            action(body, "${course.name}\n${SchedulePresentation.periodRange(course)} · ${course.classroom.ifBlank { "教室未填写" }}", ready) { openCourseDetails(course) }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(Ui.ink); setPadding(dp(16), dp(14), dp(16), dp(14))
                background = Ui.shape(this@ScheduleActivity, android.graphics.Color.WHITE, 14, Ui.line)
            }
            return
        }
        val card = Ui.card(this)
        card.addView(Ui.text(this, "${course.name} · ${days[course.weekday - 1]}", 17f, bold = true))
        card.addView(Ui.text(this, "第${course.startPeriod}–${course.endPeriod}节 · 第${course.startWeek}–${course.endWeek}周 · ${patternLabel(course.pattern)}"))
        if (course.teacher.isNotEmpty()) card.addView(Ui.text(this, "教师：${course.teacher}"))
        if (course.classroom.isNotEmpty()) card.addView(Ui.text(this, "教室：${course.classroom}"))
        if (ScheduleCalendar.conflicts(data, course).isNotEmpty()) card.addView(Ui.text(this, "⚠ 该课程存在时间重叠，可分别编辑"))
        action(card, "编辑：${course.name}", ready) { openCourse(course) }
        body.addView(card); body.addView(Ui.space(this, 8))
    }

    private fun openCourse(course: Course?) {
        if (!ready) return
        val data = session.data!!
        if (data.semester == null) return
        dismissEditor()
        draftBase = data.copy(courses = listOfNotNull(course))
        draft = Bundle().apply {
            putString("type", "course"); putString("id", course?.id ?: UUID.randomUUID().toString())
            putBoolean("existing", course != null)
            putString("name", course?.name.orEmpty()); putString("teacher", course?.teacher.orEmpty()); putString("classroom", course?.classroom.orEmpty())
            putString("weekday", (course?.weekday ?: LocalDate.now().dayOfWeek.value).toString())
            putString("startPeriod", (course?.startPeriod ?: 1).toString()); putString("endPeriod", (course?.endPeriod ?: 1).toString())
            putString("startWeek", (course?.startWeek ?: 1).toString()); putString("endWeek", (course?.endWeek ?: data.semester.totalWeeks).toString())
            putString("pattern", (course?.pattern ?: WeekPattern.EVERY).name); putString("placeId", course?.placeId.orEmpty())
        }
        showEditor()
    }
    private fun openSemester() {
        draftBase = session.data!!.copy(courses = emptyList())
        draft = Bundle().apply {
            putString("type", "semester"); putString("monday", session.data?.semester?.startMonday?.toString().orEmpty())
            putString("weeks", (session.data?.semester?.totalWeeks ?: 20).toString())
        }; showEditor()
    }
    private fun openPeriods() {
        draftBase = session.data!!.copy(courses = emptyList())
        draft = Bundle().apply {
            putString("type", "periods")
            putString("lines", session.data!!.periods.joinToString("\n") { "${it.label}|${it.start ?: ""}|${it.end ?: ""}" })
        }; showEditor()
    }

    private fun showEditor() {
        if (editor != null || !ready) return
        val state = draft ?: return
        val body = Ui.column(this).apply { setPadding(dp(18), dp(8), dp(18), dp(12)) }
        editorBody = body
        val type = state.getString("type")
        val title = when (type) { "course" -> if (state.getBoolean("existing")) "编辑课程" else "新增课程"; "semester" -> "学期设置"; "periods" -> "节次设置"; else -> "全部课程" }
        val saveLabel = when (type) { "course" -> "保存课程"; "semester" -> "保存学期"; "periods" -> "保存节次"; else -> "关闭" }
        when (type) {
            "course" -> {
                field(body, state, "name", "课程名称", 80)
                field(body, state, "teacher", "教师（可选）", 80)
                field(body, state, "classroom", "教室（可选）", 120)
                field(body, state, "weekday", "星期（1为周一，7为周日）", 1, true)
                field(body, state, "startPeriod", "开始节次", 2, true)
                field(body, state, "endPeriod", "结束节次", 2, true)
                field(body, state, "startWeek", "开始周", 2, true)
                field(body, state, "endWeek", "结束周", 2, true)
                body.addView(Ui.text(this, "上课周规则"))
                val patterns = WeekPattern.entries
                body.addView(Spinner(this).apply {
                    contentDescription = "上课周规则"; minimumHeight = dp(48)
                    adapter = ArrayAdapter(this@ScheduleActivity, android.R.layout.simple_spinner_dropdown_item, patterns.map(::patternLabel))
                    setSelection(patterns.indexOf(WeekPattern.valueOf(state.getString("pattern")!!)))
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { state.putString("pattern", patterns[position].name) }
                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    }
                })
                body.addView(Ui.text(this, "校园地点（可选）"))
                val placeLabel = Ui.text(this, placeStatus(state.getString("placeId").orEmpty()), 14f, Ui.muted)
                body.addView(placeLabel)
                action(body, "选择上课地点", session.map != null) { choosePlace(state, placeLabel) }
                action(body, "清除地点关联", true) { state.putString("placeId", ""); placeLabel.text = "未关联校园地点"; updateNavigation() }
                action(body, "查看上课地点", false) { navigate(state.getString("placeId").orEmpty()) }.tag = "schedule.navigate"
                body.addView(Ui.text(this, "失效或隐藏的地点不会删除课程；请重新关联有效地点。", 13f, Ui.muted))
            }
            "semester" -> {
                field(body, state, "monday", "起始周一", 10, hint = "YYYY-MM-DD")
                field(body, state, "weeks", "总周数", 2, true)
                body.addView(Ui.text(this, "调整周数若使现有课程超出范围，会提示先修改课程。", 13f, Ui.muted))
            }
            "periods" -> {
                body.addView(Ui.text(this, "每行一节：名称|开始时间|结束时间\n时间格式 HH:mm，可同时留空。最多24节。\n示例：第1节||\n这里没有预设学校铃声时间。", 14f, Ui.muted))
                field(body, state, "lines", "节次列表", 2000, multiline = true)
            }
            else -> {
                val data = session.data!!
                if (data.courses.isEmpty()) body.addView(Ui.text(this, "尚未添加课程"))
                data.courses.sortedWith(compareBy({ it.weekday }, { it.startPeriod }, { it.name })).forEach { course -> courseCard(body, course, data) }
            }
        }
        editorError = Ui.text(this, "", 14f, 0xffa32727.toInt()).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        body.addView(editorError)
        val builder = AlertDialog.Builder(this).setTitle(title).setView(Ui.scroll(this, body)).setPositiveButton(saveLabel, null)
        if (type != "list") builder.setNegativeButton("取消", null)
        if (type == "course" && state.getBoolean("existing")) builder.setNeutralButton("删除课程", null)
        val dialog = builder.create(); editor = dialog
        dialog.setOnCancelListener { draft = null; draftBase = null }
        dialog.setOnDismissListener { if (editor === dialog) { editor = null; editorBody = null; editorError = null } }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                minHeight = dp(48)
                isEnabled = type == "list" || draftBase != null
                setOnClickListener { if (type == "list") closeEditor() else saveEditor(state) }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { minHeight = dp(48); setOnClickListener { closeEditor() } }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                minHeight = dp(48); isEnabled = draftBase != null
                setOnClickListener { confirmDelete(state.getString("id")!!) }
            }
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.88f).toInt())
            updateNavigation()
            if (type != "list" && draftBase == null) showError("草稿参考版本不可用，填写内容已保留。请先复制内容，再取消并重新打开表单。")
        }
        dialog.show()
    }

    private fun field(body: LinearLayout, state: Bundle, key: String, label: String, limit: Int, numeric: Boolean = false, multiline: Boolean = false, hint: String = "") {
        body.addView(Ui.text(this, label, 14f, bold = true))
        body.addView(Ui.input(this, state.getString(key).orEmpty(), hint, multiline) { state.putString(key, it) }.apply {
            contentDescription = label; filters = arrayOf(InputFilter.LengthFilter(limit))
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            if (multiline) { minLines = 6; maxLines = Int.MAX_VALUE; isVerticalScrollBarEnabled = false }
        }, LinearLayout.LayoutParams(-1, -2))
        body.addView(Ui.space(this, 6))
    }
    private fun saveEditor(state: Bundle) {
        if (!ready) return
        try {
            val data = draftBase ?: throw IllegalArgumentException("草稿参考版本不可用，请保留填写内容并重新打开表单")
            fun value(key: String) = state.getString(key).orEmpty().trim()
            fun number(key: String) = value(key).toIntOrNull() ?: throw IllegalArgumentException("请完整填写星期、节次与周数，使用整数")
            when (state.getString("type")) {
                "course" -> {
                    val course = Course(value("id"), value("name"), value("teacher"), value("classroom"), number("weekday"), number("startPeriod"), number("endPeriod"), number("startWeek"), number("endWeek"), WeekPattern.valueOf(value("pattern")), value("placeId").ifBlank { null })
                    val updated = if (state.getBoolean("existing")) data.update(course) else data.add(course)
                    val conflicts = ScheduleCalendar.conflicts(session.data!!, course)
                    if (conflicts.isEmpty()) persist(updated)
                    else showPrompt(AlertDialog.Builder(this).setTitle("课程时间有重叠")
                        .setMessage("与以下课程有重叠：\n${conflicts.joinToString("\n") { it.name }}\n保存后仍可在全部课程中分别编辑。")
                        .setNegativeButton("返回修改", null).setPositiveButton("仍然保存") { _, _ -> if (ready) persist(updated) })
                }
                "semester" -> {
                    val date = try { LocalDate.parse(value("monday")) } catch (_: Exception) { throw IllegalArgumentException("起始周一请填写有效日期 YYYY-MM-DD") }
                    persist(data.copy(semester = Semester(date, number("weeks"))).checked(), resetWeek = true)
                }
                "periods" -> {
                    val lines = value("lines").lines()
                    require(lines.size in 1..24) { "节次数量应为1–24" }
                    val periods = lines.map { line ->
                        val parts = line.split('|')
                        require(parts.size == 3) { "每行请使用：名称|开始时间|结束时间" }
                        fun time(text: String): LocalTime? {
                            if (text.isBlank()) return null
                            require(text.trim().matches(Regex("[0-9]{2}:[0-9]{2}"))) { "时间格式应为HH:mm" }
                            return try { LocalTime.parse(text.trim()) } catch (_: Exception) { throw IllegalArgumentException("时间超出有效范围") }
                        }
                        SchedulePeriod(parts[0].trim(), time(parts[1]), time(parts[2]))
                    }
                    persist(data.copy(periods = periods).checked())
                }
            }
        } catch (error: IllegalArgumentException) { showError(error.message ?: "课程表填写有误，请检查后重试") }
    }
    private fun confirmDelete(id: String) {
        showPrompt(AlertDialog.Builder(this).setTitle("删除课程？").setMessage("确认删除这条课程记录？")
            .setNegativeButton("取消", null).setPositiveButton("确认删除") { _, _ ->
                if (ready) {
                    val before = draftBase
                    if (before == null) showError("草稿参考版本不可用，请保留填写内容并重新打开表单")
                    else try { persist(before.delete(id)) } catch (_: IllegalArgumentException) { showError("课程已不存在，请关闭编辑后重试") }
                }
            })
    }
    private fun persist(data: ScheduleData, resetWeek: Boolean = false) {
        if (!ready) return
        val before = draftBase ?: return
        val store = session.store
        session.work.submit("正在保存课程表…", { store.commit(before, data) }, { result ->
            result.onSuccess {
                session.data = it; session.notice = "已保存到本机"
                if (resetWeek) selectedWeek = 0
                closeEditor()
            }.onFailure { error ->
                // Do not display exception text, which may include user supplied values.
                if (error is ScheduleConflictException) {
                    session.data = error.latest
                    session.notice = error.message ?: "其他页面已更新课程表，草稿已保留，请重新核对"
                } else session.notice = "保存失败，原记录已保留，请检查存储空间后重试。"
                showError(session.notice)
            }
            render()
        })
    }
    private fun dismissEditor() {
        val previous = editor
        // Dialog posts OnDismiss asynchronously. Clear ownership before another editor opens.
        editor = null; editorBody = null; editorError = null
        previous?.setOnDismissListener(null)
        previous?.dismiss()
    }
    private fun closeEditor() { draft = null; draftBase = null; dismissEditor() }

    private fun showPrompt(builder: AlertDialog.Builder) {
        prompt?.dismiss()
        val dialog = builder.create()
        prompt = dialog
        dialog.setOnDismissListener { if (prompt === dialog) prompt = null }
        dialog.show()
    }
    private fun showError(message: String) {
        editorError?.let { view ->
            view.text = message
            view.post { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true) }
        }
    }

    private fun choosePlace(state: Bundle, label: TextView) {
        val places = session.map?.visiblePlaces ?: return
        if (places.isEmpty()) { showError("暂无可用校园地点"); return }
        showPrompt(AlertDialog.Builder(this).setTitle("选择上课地点")
            .setItems(places.map { it.name + if (it.id !in session.map!!.points) "（坐标待核对）" else "" }.toTypedArray()) { _, index ->
                state.putString("placeId", places[index].id); label.text = placeStatus(places[index].id); updateNavigation()
            }.setNegativeButton("取消", null))
    }
    private fun placeStatus(id: String): String {
        if (id.isBlank()) return "未关联校园地点"
        val map = session.map ?: return "地点暂不可用，关联已保留"
        val place = map.visiblePlaces.find { it.id == id } ?: return "地点已隐藏或不存在，关联已保留"
        return place.name + if (id !in map.points) "（坐标待核对，无法导航）" else ""
    }
    private fun updateNavigation() {
        val id = draft?.getString("placeId").orEmpty()
        val map = session.map
        editorBody?.findViewWithTag<View>("schedule.navigate")?.isEnabled = ready && map != null &&
            map.visiblePlaces.any { it.id == id } && id in map.points
    }
    private fun navigate(id: String) {
        if (!ready || id.isBlank()) return
        val store = session.mapStore
        session.work.submit("正在核对地点…", { store.load() }, { result ->
            session.map = result.getOrNull(); updateNavigation()
            val map = session.map
            if (map == null || map.visiblePlaces.none { it.id == id } || id !in map.points) {
                showError("地点已失效、隐藏或缺少坐标，无法导航；课程已保留。")
            } else try {
                startActivity(Intent(this, CampusMapActivity::class.java).putExtra("campus_place_id", id))
            } catch (_: Exception) { showError("地图暂时无法打开，请稍后重试") }
        })
    }
    private fun action(body: LinearLayout, label: String, enabled: Boolean, primary: Boolean = false, click: () -> Unit): Button =
        Ui.button(this, label, primary, click).apply {
            isEnabled = enabled
            body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(6) })
        }
    private fun quietButton(label: String, click: () -> Unit) = Ui.button(this, label, onClick = click).apply {
        stateListAnimator = null; elevation = 0f
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x220c6b58), Ui.shape(this@ScheduleActivity, android.graphics.Color.TRANSPARENT, 10), null)
    }
    private fun patternLabel(pattern: WeekPattern) = when (pattern) { WeekPattern.EVERY -> "每周"; WeekPattern.ODD -> "单周"; WeekPattern.EVEN -> "双周" }
    private fun dp(value: Int) = Ui.dp(this, value)
}
