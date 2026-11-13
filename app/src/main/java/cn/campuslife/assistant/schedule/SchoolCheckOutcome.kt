package cn.campuslife.assistant.schedule

enum class SchoolCheckPhase { CHECKING, COMPLETE, REVIEW, FAILED, INTERRUPTED }

/** Display evidence only; never controls ownership, merge or schedule writes. */
data class SchoolCheckOutcome(
    val phase:SchoolCheckPhase, val differences:Boolean?=null, val applied:Boolean?=null,
    val changes:SchoolChanges?=null, val protectedGroups:Int=0
) {
    fun encode()=listOf("1",phase.name,flag(differences),flag(applied),
        changes?.let {"${it.added},${it.adjusted},${it.cancelled}"} ?: "-",protectedGroups.toString()).joinToString("|")
    companion object {
        private fun flag(value:Boolean?)=when(value){true->"y";false->"n";null->"u"}
        fun decode(raw:String):SchoolCheckOutcome?=runCatching {
            require(raw.length in 1..150);val p=raw.split('|');require(p.size==6 && p[0]=="1")
            fun bool(s:String):Boolean?=when(s){"y"->true;"n"->false;"u"->null;else->error("Invalid flag")}
            fun number(s:String)=s.toInt().also {require(it in 0..10000)}
            val c=if(p[4]=="-")null else p[4].split(',').also {require(it.size==3)}.let {SchoolChanges(number(it[0]),number(it[1]),number(it[2]))}
            SchoolCheckOutcome(SchoolCheckPhase.valueOf(p[1]),bool(p[2]),bool(p[3]),c,number(p[5]))
        }.getOrNull()
        fun completed(changes:SchoolChanges,protected:Int)=SchoolCheckOutcome(SchoolCheckPhase.COMPLETE,
            changes.total>0 || protected>0,changes.total>0,changes,protected)
        fun preview(changes:SchoolChanges,protected:Int,different:Boolean)=
            if(!different && changes.total==0 && protected==0)completed(SchoolChanges(),0)
            else if(changes.total==0 && protected>0)completed(changes,protected)
            else SchoolCheckOutcome(SchoolCheckPhase.REVIEW,true,false,changes,protected)
        fun failed(untouched:Boolean)=SchoolCheckOutcome(SchoolCheckPhase.FAILED,applied=if(untouched)false else null)
        fun automatic(state:DailySyncState):SchoolCheckOutcome=state.outcome ?: when(state.result){
            SyncResult.CHECKING->SchoolCheckOutcome(SchoolCheckPhase.CHECKING)
            SyncResult.NO_CHANGE->completed(SchoolChanges(),0)
            SyncResult.UPDATED,SyncResult.CONFLICT->{
                // Only this exact numeric form was written after a successful old apply().
                val m=Regex("^新增 ([0-9]{1,5}) 门，调整 ([0-9]{1,5}) 门，取消 ([0-9]{1,5}) 门(?:；([0-9]{1,5})组冲突已保留)?$").matchEntire(state.summary)
                if(m!=null)completed(SchoolChanges(m.groupValues[1].toInt(),m.groupValues[2].toInt(),m.groupValues[3].toInt()),m.groupValues[4].toIntOrNull()?:0)
                else SchoolCheckOutcome(SchoolCheckPhase.COMPLETE,true,if(state.result==SyncResult.UPDATED)true else null)
            }
            SyncResult.INTERRUPTED->SchoolCheckOutcome(SchoolCheckPhase.INTERRUPTED)
            else->failed(untouched=false)
        }
        fun legacy(record:SchoolCheckRecord):SchoolCheckOutcome=record.outcome ?: when(record.result){
            "已检查，课表没有变化"->completed(SchoolChanges(),0)
            "已检查，课表已更新"->SchoolCheckOutcome(SchoolCheckPhase.COMPLETE,true,true)
            "正在检查…"->SchoolCheckOutcome(SchoolCheckPhase.CHECKING)
            "已检查，发现课表更新"->SchoolCheckOutcome(SchoolCheckPhase.REVIEW,true,false)
            "已检查，受保护课程已保留"->SchoolCheckOutcome(SchoolCheckPhase.COMPLETE,true,false)
            "课表未完整读取，未更新"->failed(untouched=true)
            else->failed(untouched=false)
        }
    }
}

data class SchoolCheckOutcomeText(val check:String,val school:String,val local:String,val note:String) {
    companion object {
        fun from(value:SchoolCheckOutcome):SchoolCheckOutcomeText {
            val check=when(value.phase){
                SchoolCheckPhase.CHECKING->"正在检查…"
                SchoolCheckPhase.COMPLETE->"已完成检查"
                SchoolCheckPhase.REVIEW->"已完成检查，等待确认"
                SchoolCheckPhase.FAILED->"检查未完成"
                SchoolCheckPhase.INTERRUPTED->"检查已中断"
            }
            val school=when(value.differences){true->"发现差异";false->"未发现变化";null->"未能确认"}
            val local=when {
                value.applied==true->"已更新"+(value.changes?.takeIf {it.total>0}?.let {"：${it.summary()}"}?:"")
                value.phase==SchoolCheckPhase.REVIEW->"待确认，尚未应用"
                value.applied==false && value.differences==true->"未应用差异，原课程已保留"
                value.applied==false && value.differences==false->"无需更新"
                value.applied==false->"未执行更新"
                else->"更新结果待确认"
            }
            val note=when {
                value.protectedGroups>0->"${value.protectedGroups}组差异未应用：受来源、手动修改/删除保护或时间冲突限制。"
                value.phase==SchoolCheckPhase.REVIEW && (value.changes?.total?:0)>0->"计划${value.changes!!.summary()}"
                else->""
            }
            return SchoolCheckOutcomeText(check,school,local,note)
        }
    }
}
