package cn.campuslife.assistant.schedule

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import cn.campuslife.assistant.ui.Ui
import java.time.LocalDate

/** Presentation only: retain every real period and the existing overlap grouping and test tags. */
internal fun redesignedScheduleWeekView(
    context: Context, data: ScheduleData, week: Int, enabled: Boolean, choose: (List<Course>) -> Unit
): ScheduleWeekView = ScheduleWeekView(context, data, week, enabled, choose).apply {
        // Keep the existing view type for accessibility/device tests; replace its visual content only.
        removeAllViews()
        tag = "schedule.week.grid"
        isFillViewport = false
        isHorizontalScrollBarEnabled = true
        setBackgroundColor(android.graphics.Color.WHITE)
        val scale = resources.configuration.fontScale.coerceAtLeast(1f)
        val timeWidth = Ui.dp(context, (34 * scale).toInt())
        val available = resources.displayMetrics.widthPixels - Ui.dp(context, 24) - timeWidth
        val columnWidth = maxOf(Ui.dp(context, (48 * scale).toInt()), available / 7)
        val rowHeight = Ui.dp(context, (58 * scale).toInt())
        val gridWidth = timeWidth + columnWidth * 7
        val start = data.semester!!.startMonday.plusWeeks((week - 1).toLong())
        val today = LocalDate.now()
        val todayIndex = (0..6).find { start.plusDays(it.toLong()) == today }
        val root = Ui.column(context)
        val heading = Ui.row(context)
        heading.addView(Ui.text(context, "节次", 10.5f, Ui.muted).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(timeWidth, Ui.dp(context, (52 * scale).toInt())))
        listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, day ->
            val date = start.plusDays(index.toLong())
            heading.addView(Ui.text(context, "$day\n${date.dayOfMonth}", 12f,
                if (date == today) Ui.blue else Ui.ink, true).apply {
                gravity = Gravity.CENTER
                contentDescription = "$date，周$day" + if (date == today) "，今天" else ""
                if (date == today) background = Ui.shape(context, Ui.pale, 10)
            }, LinearLayout.LayoutParams(columnWidth, -1))
        }
        root.addView(heading, LinearLayout.LayoutParams(gridWidth, -2))
        val grid = FrameLayout(context)
        val gridHeight = rowHeight * data.periods.size
        grid.addView(object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                paint.color = Ui.background
                val gap = Ui.dp(context, 2).toFloat()
                for (day in 0..6) {
                    val left = (timeWidth + day * columnWidth).toFloat()
                    canvas.drawRoundRect(left + gap, gap, left + columnWidth - gap, height - gap,
                        Ui.dp(context, 8).toFloat(), Ui.dp(context, 8).toFloat(), paint)
                }
                if (todayIndex != null) {
                    paint.color = Ui.tealTint
                    val left = (timeWidth + todayIndex * columnWidth).toFloat()
                    canvas.drawRoundRect(left + gap, gap, left + columnWidth - gap, height - gap,
                        Ui.dp(context, 8).toFloat(), Ui.dp(context, 8).toFloat(), paint)
                }
                paint.color = android.graphics.Color.WHITE
                paint.strokeWidth = Ui.dp(context, 3).toFloat()
                for (row in 1 until data.periods.size)
                    canvas.drawLine(timeWidth.toFloat(), (row * rowHeight).toFloat(), width.toFloat(), (row * rowHeight).toFloat(), paint)
            }
        }, FrameLayout.LayoutParams(gridWidth, gridHeight))
        data.periods.forEachIndexed { index, period ->
            val label = SchedulePresentation.periodLabel(index + 1)
            grid.addView(Ui.text(context, label + (period.start?.let { "\n$it" } ?: ""), 10.5f, Ui.muted).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(0, Ui.dp(context, 10), 0, 0)
                contentDescription = "$label，${period.label}，${period.start ?: "时间未设置"}"
                tag = "schedule.period.${index + 1}"
            }, FrameLayout.LayoutParams(timeWidth, rowHeight).apply { topMargin = index * rowHeight })
        }
        val palette = intArrayOf(Ui.pale, Ui.tealTint, Ui.orangeTint, 0xffdff0eb.toInt(), 0xfffbead7.toInt(), 0xffe6ebf1.toInt())
        for (block in SchedulePresentation.blocks(ScheduleCalendar.week(data, week))) {
            val course = block.courses.first()
            val multiple = block.courses.size > 1
            val label = if (multiple) "重叠${block.courses.size}门\n" + block.courses.joinToString("\n") { it.name }
                else course.name + if (course.classroom.isNotBlank()) "\n\n${course.classroom}" else ""
            grid.addView(Ui.button(context, label) { choose(block.courses) }.apply {
                isEnabled = enabled
                textSize = 12f
                gravity = Gravity.TOP or Gravity.START
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = (block.endPeriod - block.startPeriod + 1) * 4
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Ui.blue)
                stateListAnimator = null
                elevation = 0f
                background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(0x22172b49),
                    Ui.shape(context, if (multiple) Ui.orangeTint else palette[SchedulePresentation.colorIndex(course.name)], 8), null),
                    Ui.dp(context, 2), Ui.dp(context, 2), Ui.dp(context, 2), Ui.dp(context, 2))
                setPadding(Ui.dp(context, 5), Ui.dp(context, 9), Ui.dp(context, 5), Ui.dp(context, 6))
                tag = if (multiple) "schedule.overlap" else "schedule.course.${course.id}"
                contentDescription = block.courses.joinToString("；") {
                    "${it.name}，周${it.weekday}，${SchedulePresentation.periodRange(it)}，教室：${it.classroom.ifBlank { "未填写" }}，教师：${it.teacher.ifBlank { "未填写" }}"
                } + "，点击查看详情"
            }, FrameLayout.LayoutParams(columnWidth, rowHeight * (block.endPeriod - block.startPeriod + 1)).apply {
                leftMargin = timeWidth + (block.weekday - 1) * columnWidth
                topMargin = (block.startPeriod - 1) * rowHeight
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(gridWidth, gridHeight))
        addView(root)
}
