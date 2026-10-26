package cn.campuslife.assistant.home

data class ToolboxTool(val id: String,val title: String,val subtitle: String,val glyph: String,val defaultVisible: Boolean=true)

object ToolCatalog {
    val tools=listOf(
        ToolboxTool("map","校园地图","平潭校区 · 搜楼栋","map"),
        ToolboxTool("schedule","个人课程表","本周安排与今日课程","calendar"),
        ToolboxTool("watermark","照片水印","单张制作 · 高清另存","image"),
        ToolboxTool("planner","倒计时","记录重要日期与纪念日","check")
    )
}

data class HomeLayout(val order: List<String>,val hidden: Set<String>) {
    fun normalized(): HomeLayout {
        val ids=ToolCatalog.tools.map { it.id }
        val known=order.filter { it in ids }.distinct()
        val added=ids-known.toSet()
        return HomeLayout(known+added,hidden.intersect(ids.toSet())+ToolCatalog.tools.filter { it.id in added && !it.defaultVisible }.map { it.id })
    }
    fun visible(): List<String> { val state=normalized();return state.order.filterNot { it in state.hidden } }
    fun move(id: String,delta: Int): HomeLayout {
        val state=normalized();val from=state.order.indexOf(id);if(from<0) return state
        val items=state.order.toMutableList();items.removeAt(from);items.add((from+delta).coerceIn(0,items.size),id)
        return state.copy(order=items)
    }
    fun hide(id: String,value: Boolean): HomeLayout {
        val state=normalized();if(id !in state.order) return state
        return state.copy(hidden=if(value) state.hidden+id else state.hidden-id)
    }
    companion object { fun defaults()=HomeLayout(ToolCatalog.tools.map { it.id },ToolCatalog.tools.filterNot { it.defaultVisible }.map { it.id }.toSet()) }
}
