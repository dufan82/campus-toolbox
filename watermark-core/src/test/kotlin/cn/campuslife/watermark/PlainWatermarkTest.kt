package cn.campuslife.watermark

import org.junit.Assert.*
import org.junit.Test

class PlainWatermarkTest {
    private val metrics=object: TextMeasurer {
        override fun width(text: String,fontSize: Double,bold: Boolean)=text.codePointCount(0,text.length)*fontSize
        override fun metrics(fontSize: Double,bold: Boolean)=FontMetrics(-fontSize*.8,fontSize*.2)
    }
    @Test fun contentOnlyKeepsSelectionOrderSkipsBlanksAndDoesNotRewriteValues() {
        val profile=CampusProfile(mapOf(FieldKey.NAME to " 测试同学 ",FieldKey.CLASS_NAME to " ",FieldKey.SEAT_NUMBER to "05",FieldKey.COLLEGE to "数字：示例学院",FieldKey.STUDENT_ID to "PRIVATE_TEST"))
        val details=ActivityDetails(mapOf(FieldKey.CAPTURED_AT to "2026-10-23"))
        val order=listOf(FieldKey.NAME,FieldKey.CLASS_NAME,FieldKey.SEAT_NUMBER,FieldKey.COLLEGE,FieldKey.CAPTURED_AT)
        val config=EditorConfig(order=order,enabled=order.toSet())
        assertEquals(listOf("测试同学","05","数字：示例学院","2026-10-23"),formattedFields(profile,details,config))
        assertEquals("测试同学_05",ExportNaming.compose(profile,details,listOf(FieldKey.NAME,FieldKey.SEAT_NUMBER),"fixture"))
    }
    @Test fun defaultLayoutUsesBottomRightForLandscapePortraitAndSquarePhotos() {
        for((width,height) in listOf(4000.0 to 3000.0,3000.0 to 4000.0,2400.0 to 2400.0)) {
            val layout=LayoutEngine.layout(width,height,listOf("测试同学","05"),WatermarkStyle(),metrics)
            val margin=minOf(width,height)*.028
            assertEquals(width-margin,layout.box.right,.001)
            assertEquals(height-margin,layout.box.bottom,.001)
        }
    }
    @Test fun disablingContentOnlyAddsLabelsWithoutChangingSelectionOrNaming() {
        val profile=CampusProfile(mapOf(FieldKey.NAME to " 测试同学 ",FieldKey.CLASS_NAME to " ",FieldKey.SEAT_NUMBER to "05",FieldKey.STUDENT_ID to "PRIVATE_TEST"))
        val config=EditorConfig(order=listOf(FieldKey.NAME,FieldKey.CLASS_NAME,FieldKey.SEAT_NUMBER),enabled=setOf(FieldKey.NAME,FieldKey.CLASS_NAME,FieldKey.SEAT_NUMBER),contentOnly=false)
        assertEquals(listOf("姓名：测试同学","座号：05"),formattedFields(profile,ActivityDetails(),config))
        assertEquals(listOf("测试同学","05"),formattedFields(profile,ActivityDetails(),config.copy(contentOnly=true)))
        assertEquals("测试同学_05",ExportNaming.compose(profile,ActivityDetails(),listOf(FieldKey.NAME,FieldKey.SEAT_NUMBER),"fixture"))
    }
}
