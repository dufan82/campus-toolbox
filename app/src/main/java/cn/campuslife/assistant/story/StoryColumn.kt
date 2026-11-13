package cn.campuslife.assistant.story

/** Source-based navigation only; never changes IDs, ownership, rights or stored reading data. */
enum class StoryColumn(val key:String,val label:String) {
    NETWORK("network","网络"), SUBMISSIONS("submissions","投稿"), LITERATURE("literature","文学作品");
    companion object {
        fun fromKey(key:String?)=entries.firstOrNull {it.key==key} ?: NETWORK
        fun of(story:Story)=when(story.provenance.sourceType) {
            "douyin_post","douyin_image","douyin_comment"->NETWORK
            "user_submission"->SUBMISSIONS
            else->LITERATURE
        }
    }
}
