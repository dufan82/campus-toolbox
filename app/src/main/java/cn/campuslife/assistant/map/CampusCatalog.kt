package cn.campuslife.assistant.map

import java.text.Normalizer

data class CampusLandmark(val id: String,val name: String,val category: MapCategory,val aliases: List<String> = emptyList())
data class LocatedLandmark(val landmark: CampusLandmark,val point: GeoPoint?)

/** Shared public campus names. Coordinates are maintained independently, never inferred from image pixels. */
object CampusCatalog {
    private fun place(id: String,name: String,category: MapCategory,vararg aliases: String)=CampusLandmark(id,name,category,aliases.toList())
    private fun training(number: Int,vararg aliases: String)=place("training-$number","实训${number}楼",MapCategory.STUDY,
        "实训$number","实$number","实训${number}号楼","${number}号实训楼","实训楼$number","实训${number}栋",*aliases)

    val entries: List<CampusLandmark> = listOf(
        place("training-1-north","实训1楼北门",MapCategory.STUDY,"实训1北","实训1北楼","实训1北门","实1北门","实训楼1北门","1号实训楼北门","实训1号楼北门","建筑工程学院"),
        place("construction","建工馆",MapCategory.STUDY,"建筑工程学院"),
        place("training-1-south","实训1楼南门",MapCategory.STUDY,"实训1南","实训1南楼","实训1南门","实1南门","实训楼1南门","1号实训楼南门","实训1号楼南门","数字技术学院","数字产业学院"),
        training(2,"智能制造学院"),
        training(8,"智能制造学院"),
        training(3,"商贸管理学院"),
        training(6,"文化创意与旅游学院"),
        training(7,"文化创意与旅游学院"),
        place("science-1","工科1",MapCategory.STUDY,"工科一","工科1号楼","公共教学楼"),
        place("science-2","工科2",MapCategory.STUDY,"工科二","工科2号楼","公共教学楼"),
        place("science-3","工科3",MapCategory.STUDY,"工科三","工科3号楼","公共教学楼"),
        place("science-4","工科4",MapCategory.STUDY,"工科四","工科4号楼","公共教学楼"),
        place("humanities","文科楼",MapCategory.STUDY,"文科","公共教学楼"),
        place("canteen-1","食堂1",MapCategory.FOOD,"第一食堂","一食堂","1食堂","一餐","食1"),
        place("canteen-2","食堂2",MapCategory.FOOD,"第二食堂","二食堂","2食堂","二餐","食2"),
        place("museum","校史馆",MapCategory.SERVICE),
        place("lecture-hall","学术报告厅",MapCategory.SERVICE,"报告厅"),
        place("alumni-harbor","校友港湾",MapCategory.SERVICE),
        place("coffee","书语咖啡",MapCategory.SERVICE),
        place("service-hall","师生服务大厅",MapCategory.SERVICE,"服务大厅"),
        place("activity-center","师生活动中心",MapCategory.SERVICE,"活动中心"),
        place("mental-health","心理健康教育示范基地",MapCategory.SERVICE,"心理健康","心理中心"),
        place("express-station","快递驿站",MapCategory.EXPRESS,"快递","驿站","取快递"),
        place("fire-station","小型消防站",MapCategory.SERVICE,"消防站"),
        place("safety-center","安全体验馆",MapCategory.SERVICE,"安全体验"),
        place("training-store","商贸实训超市",MapCategory.SERVICE,"实训超市","超市"),
        place("startup-center","学生创业中心",MapCategory.SERVICE,"创业中心"),
        place("gym","体育馆",MapCategory.SPORT,"体"),
        *Array(10) { i -> val n=i+1;place("dorm-$n","${n}号楼",MapCategory.DORM,
            "生$n","学生公寓$n","学生宿舍$n","${n}号楼","${n}号宿舍","${n}号宿舍楼","${n}号公寓",
            "学生公寓${n}号楼","学生宿舍${n}号楼","宿舍$n","公寓$n","生公$n",
            "${n}栋","学生宿舍${n}栋","学生公寓${n}栋","宿舍楼$n","宿舍${n}号楼","第${n}宿舍楼") },
        *Array(3) { i -> val n=i+1;place("teacher-$n","教师公寓${n}号楼",MapCategory.DORM,
            "师$n","教师公寓$n","教师宿舍$n","教师公寓${n}栋","教师宿舍${n}号楼","教工宿舍${n}号楼") },
        place("staff-1","员1 · 员工宿舍",MapCategory.DORM,"员1","员工宿舍","员工宿舍1","员工宿舍楼"),
        place("sunken-plaza","下沉广场",MapCategory.SERVICE,"下沉","下沉式广场"),
        place("campus-gate-north","北门",MapCategory.SERVICE,"北","校区北门","校园北门","学校北门"),
        place("campus-gate-south","南门",MapCategory.SERVICE,"南","校区南门","校园南门","学校南门")
    )

    private val numerals=mapOf("十" to "10","一" to "1","二" to "2","三" to "3","四" to "4","五" to "5","六" to "6","七" to "7","八" to "8","九" to "9")
    private fun normalized(raw: String): String {
        var text=Normalizer.normalize(raw.take(80),Normalizer.Form.NFKC).trim().replace(Regex("\\s+"),"").lowercase()
        numerals.forEach { (ch,n)->text=text.replace(ch,n) }
        return text
    }

    fun hasExactName(query: String,items: List<CampusLandmark>): Boolean {
        val text=normalized(query)
        return items.any { item -> (listOf(item.name)+item.aliases).any { normalized(it)==text } }
    }

    fun search(query: String,category: MapCategory,items: List<CampusLandmark> = entries): List<CampusLandmark> {
        val text=normalized(query)
        val candidates=items.filter { category==MapCategory.ALL || it.category==category }
        if(text.isEmpty()) return candidates
        val exact=candidates.filter { item -> (listOf(item.name)+item.aliases).any { normalized(it)==text } }
        if(exact.isNotEmpty()) return exact
        // Without an exact place name, a bare number means a dorm, not a legend index.
        if(text.matches(Regex("(?:[1-9]|10)")) && category in listOf(MapCategory.ALL,MapCategory.DORM)) {
            return candidates.filter { it.id=="dorm-$text" }
        }
        return candidates.filter { item -> (listOf(item.name)+item.aliases).any { normalized(it).contains(text) } }
    }

    fun located(items: List<CampusLandmark>,coordinates: Map<String,GeoPoint>): List<LocatedLandmark> =
        items.map { LocatedLandmark(it,coordinates[it.id]) }
}
