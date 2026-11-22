package cn.campuslife.assistant.schedule

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SchoolRoomReadDeviceTest {
    private fun row(id:Int,room:String,task:Int?=null)=JSONObject().put("id",id).put("course","C4D三维设计")
        .put("spaceName",room).put("teacherName","合成教师").put("DayIndex",5).put("startNode",7).put("endNode",10)
        .also {if(task!=null)it.put("mergeTaskId",task)}
    private fun raw(vararg rows:JSONObject)=JSONObject().put("code",200)
        .put("data",JSONObject().put("list",org.json.JSONArray(rows.toList())))
    @Test fun mergedSchoolTaskUsesTheFirstOfficialClassroomNotAnOverlaidOlderRow() {
        val lessons=SchoolPortalJson.lessons(raw(row(1,"文科楼205",99),row(2,"文科楼303",99)),10)
        assertEquals(1,lessons.size);assertEquals("文科楼205",lessons.single().classroom)
        val reversed=SchoolPortalJson.lessons(raw(row(2,"文科楼303",99),row(1,"文科楼205",99)),10)
        assertEquals("文科楼303",reversed.single().classroom)
    }
    @Test fun differentDaysPeriodsAndIndependentTaskIdsAreNotCollapsed() {
        val lessons=SchoolPortalJson.lessons(raw(row(1,"205"),row(2,"303"),row(1,"206").put("DayIndex",4),row(1,"207").put("startNode",1).put("endNode",2)),10)
        assertEquals(4,lessons.size)
    }
    @Test fun laterMalformedRowsCannotBeHiddenByMergedTaskSelection() {
        assertThrows(IllegalArgumentException::class.java) {SchoolPortalJson.lessons(raw(row(1,"205",99),row(2,"303",99).put("startNode",0)),10)}
    }
}
