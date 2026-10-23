package cn.campuslife.watermark

import org.junit.Assert.*
import org.junit.Test

class ToolboxFieldsTest {
    private val allowed=listOf(FieldKey.COLLEGE,FieldKey.CLASS_NAME,FieldKey.NAME,FieldKey.SEAT_NUMBER,FieldKey.STUDENT_ID,FieldKey.CAPTURED_AT)

    @Test fun legacyConfigCannotPrintRemovedFields() {
        val profile=CampusProfile(FieldKey.entries.filter { it.personal }.associateWith { "测试值" })
        val details=ActivityDetails(FieldKey.entries.filterNot { it.personal }.associateWith { "测试时间" })
        val config=EditorConfig(order=allowed,enabled=FieldKey.entries.toSet())
        assertEquals(listOf("测试值","测试值","测试值","测试值","测试值","测试时间"),formattedFields(profile,details,config))
        assertEquals(allowed,config.orderedFields())
    }

    @Test fun allTemplatesExposeOnlySixSupportedFields() {
        BuiltinTemplates.all.forEach { template ->
            assertEquals(allowed.toSet(),template.config.orderedFields().toSet())
            assertTrue(template.config.enabled.all { it in allowed })
            assertFalse(FieldKey.STUDENT_ID in template.config.enabled)
        }
    }
}
