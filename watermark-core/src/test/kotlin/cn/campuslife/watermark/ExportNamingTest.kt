package cn.campuslife.watermark

import org.junit.Assert.*
import org.junit.Test

class ExportNamingTest {
    private val profile=CampusProfile(mapOf(FieldKey.CLASS_NAME to "测试班级",FieldKey.NAME to "测试同学",FieldKey.SEAT_NUMBER to "05",FieldKey.STUDENT_ID to "IDTEST001"))

    @Test fun defaultsUseClassNameAndSeatWithoutStudentId() {
        assertEquals("测试班级_测试同学_05",ExportNaming.compose(profile,ActivityDetails(),ExportNaming.defaultFields,"20000101_000000"))
    }
    @Test fun selectedOrderIsIndependentOfWatermarkVisibilityAndSkipsEmpty() {
        assertEquals("05_测试同学",ExportNaming.compose(profile,ActivityDetails(),listOf(FieldKey.SEAT_NUMBER,FieldKey.COLLEGE,FieldKey.NAME),"20000101_000000"))
        assertEquals("测试同学",ExportNaming.compose(profile,ActivityDetails(),listOf(FieldKey.EVENT_NAME,FieldKey.NAME,FieldKey.NAME),"20000101_000000"))
    }
    @Test fun emptyChoicesGetANeutralFallback() {
        assertEquals("水印照片_20000101_000000",ExportNaming.compose(CampusProfile(),ActivityDetails(),ExportNaming.defaultFields,"20000101_000000"))
    }
    @Test fun invalidPathCharactersAreSanitizedAndReservedNamesArePrefixed() {
        assertEquals("测试_一班",ExportNaming.compose(CampusProfile(mapOf(FieldKey.CLASS_NAME to "测试/一班: ?")),ActivityDetails(),listOf(FieldKey.CLASS_NAME),"20000101_000000"))
        assertEquals("水印照片_CON",ExportNaming.compose(CampusProfile(mapOf(FieldKey.NAME to "CON")),ActivityDetails(),listOf(FieldKey.NAME),"20000101_000000"))
    }
    @Test fun generatedNamesFitUtf8LimitsAndNeverSplitEmoji() {
        val value=ExportNaming.compose(CampusProfile(mapOf(FieldKey.NAME to "同学🌱".repeat(80))),ActivityDetails(),listOf(FieldKey.NAME),"20000101_000000")
        assertTrue(value.toByteArray(Charsets.UTF_8).size<=180)
        assertFalse(value.last().isHighSurrogate())
        assertNull(ExportNaming.error(value))
    }
    @Test fun manualNamesAreValidatedAndOnlyRecognizedImageSuffixIsRemoved() {
        for(value in listOf("","bad/name","CON","name.","name ","a".repeat(181))) assertNotNull(ExportNaming.error(value))
        assertEquals("我的照片",ExportNaming.baseName("我的照片.JPEG"))
        assertEquals("我的记录.v2",ExportNaming.baseName("我的记录.v2"))
        assertEquals("我的照片.png",ExportNaming.fileName("我的照片.jpg",ExportFormat.PNG))
    }
}
