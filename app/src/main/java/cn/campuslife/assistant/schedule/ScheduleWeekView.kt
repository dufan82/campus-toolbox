package cn.campuslife.assistant.schedule

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import cn.campuslife.assistant.ui.Ui
import java.time.LocalDate

/** A lightweight native grid. A block spans its real period range instead of repeating per row. */
class ScheduleWeekView(context: Context, data: ScheduleData, week: Int, enabled: Boolean, choose: (List<Course>) -> Unit) : HorizontalScrollView(context) {
    init {
        isFillViewport = false
        isHorizontalScrollBarEnabled = false
        val scale = resources.configuration.fontScale.coerceAtLeast(1f)
        val timeWidth = Ui.dp(context, (34 * scale).toInt())
        val available = resources.displayMetrics.widthPixels - Ui.dp(context, 24) - timeWidth
        val columnWidth = maxOf(Ui.dp(context, (48 * scale).toInt()), available / 7)
        val rowHeight = Ui.dp(context, (84 * scale).toInt())
        val gridWidth = timeWidth + columnWidth * 7
        val start = data.semester!!.startMonday.plusWeeks((week - 1).toLong())
        val today = LocalDate.now()
        val todayIndex = (0..6).find { start.plusDays(it.toLong()) == today }
        val days = listOf("一", "二", "三", "四", "五", "六", "日")
        val root = Ui.column(context)
        val heading = LinearLayout(context)
        heading.addView(Ui.text(context, "节次", 10.5f, Ui.muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(timeWidth, Ui.dp(context, (52 * scale).toInt())))
        days.forEachIndexed { index, day ->
            val date = start.plusDays(index.toLong())
            heading.addView(Ui.text(context, "周$day\n${date.monthValue}/${date.dayOfMonth}", 11.5f,
                if (date == today) Ui.blue else Ui.muted, date == today).apply {
                gravity = Gravity.CENTER
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
                if (todayIndex != null) {
                    paint.color = 0xfff0f7f3.toInt()
                    canvas.drawRect((timeWidth + todayIndex * columnWidth).toFloat(), 0f, (timeWidth + (todayIndex + 1) * columnWidth).toFloat(), height.toFloat(), paint)
                }
                paint.color = Ui.line; paint.strokeWidth = resources.displayMetrics.density * 0.5f
                for (row in 0..data.periods.size) canvas.drawLine(0f, (row * rowHeight).toFloat(), width.toFloat(), (row * rowHeight).toFloat(), paint)
                for (column in 0..7) canvas.drawLine((timeWidth + column * columnWidth).toFloat(), 0f, (timeWidth + column * columnWidth).toFloat(), height.toFloat(), paint)
            }
        }, FrameLayout.LayoutParams(gridWidth, gridHeight))
        data.periods.forEachIndexed { index, period ->
            grid.addView(Ui.text(context, SchedulePresentation.periodLabel(index + 1), 10.5f, Ui.muted).apply {
                gravity = Gravity.CENTER
                contentDescription = "${SchedulePresentation.periodLabel(index + 1)}，${period.start ?: "时间未设置"}"
            }, FrameLayout.LayoutParams(timeWidth, rowHeight).apply { topMargin = index * rowHeight })
        }
        val palette = intArrayOf(0xffe8f3ee.toInt(), 0xffeaf1fb.toInt(), 0xfffbf2e4.toInt(), 0xfff0ebf8.toInt(), 0xfff9ecef.toInt(), 0xffe7f4f3.toInt())
        for (block in SchedulePresentation.blocks(ScheduleCalendar.week(data, week))) {
            val course = block.courses.first()
            val multiple = block.courses.size > 1
            val label = if (multiple) "重叠${block.courses.size}门\n" + block.courses.joinToString("\n") { it.name }
                else course.name + if (course.classroom.isNotBlank()) "\n\n${course.classroom}" else ""
            val button = Ui.button(context, label) { choose(block.courses) }.apply {
                isEnabled = enabled; textSize = 12f; gravity = Gravity.TOP or Gravity.START
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = (block.endPeriod - block.startPeriod + 1) * 5; ellipsize = TextUtils.TruncateAt.END
                setTextColor(Ui.ink)
                stateListAnimator = null; elevation = 0f
                background = android.graphics.drawable.InsetDrawable(RippleDrawable(ColorStateList.valueOf(0x220c6b58), Ui.shape(context, if (multiple) 0xfff5eee5.toInt() else palette[SchedulePresentation.colorIndex(course.name)], 9), null), Ui.dp(context, 2), Ui.dp(context, 3), Ui.dp(context, 2), Ui.dp(context, 3))
                setPadding(Ui.dp(context, 5), Ui.dp(context, 9), Ui.dp(context, 4), Ui.dp(context, 6))
                tag = if (multiple) "schedule.overlap" else "schedule.course.${course.id}"
                contentDescription = block.courses.joinToString("；") { "${it.name}，周${it.weekday}，${SchedulePresentation.periodRange(it)}，教室：${it.classroom.ifBlank { "未填写" }}，教师：${it.teacher.ifBlank { "未填写" }}" } + "，点击查看详情"
            }
            grid.addView(button, FrameLayout.LayoutParams(columnWidth, rowHeight * (block.endPeriod - block.startPeriod + 1)).apply {
                leftMargin = timeWidth + (block.weekday - 1) * columnWidth
                topMargin = (block.startPeriod - 1) * rowHeight
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(gridWidth, gridHeight))
        addView(root)
    }
}
