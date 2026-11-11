package cn.campuslife.assistant.poetry

data class Poem(val id:String,val text:String,val author:String,val work:String) {
    val credit get()="$author · $work"
}
object PoemPicker {
    fun choose(ids:List<String>,current:String?,recent:List<String>,index:(Int)->Int):String {
        require(ids.isNotEmpty())
        val different=ids.distinct().filterNot {it==current}.ifEmpty {ids.distinct()}
        val fresh=different.filterNot {it in recent}.ifEmpty {different}
        return fresh[index(fresh.size)]
    }
}
