package cn.campuslife.assistant.batch

import cn.campuslife.watermark.*
import org.junit.Assert.*
import org.junit.Test

class BatchNamesTest {
    @Test fun selectedFieldsHaveStableSequenceSuffixAndUniqueAttemptToken() {
        val options = BatchOptions.freeze(CampusProfile(mapOf(FieldKey.NAME to "Fixture")), ActivityDetails(), EditorConfig(), listOf(FieldKey.NAME), ExportFormat.PNG, null, false)
        assertEquals("Fixture_002_abcdef12.png", BatchNames.fileName(options, null, 2, "abcdef12"))
        assertNotEquals(BatchNames.fileName(options, null, 2, "abcdef12"), BatchNames.fileName(options, null, 2, "abcdef13"))
        assertFalse(BatchNames.fileName(options, null, 2, "abcdef12").contains("STUDENT_ID"))
    }

    @Test fun suffixSurvivesLongUnicodeFieldsAndFilenameRemainsValid() {
        val options = BatchOptions.freeze(CampusProfile(mapOf(FieldKey.NAME to "图".repeat(100))), ActivityDetails(), EditorConfig(), listOf(FieldKey.NAME), ExportFormat.JPEG, null, false)
        val result = BatchNames.fileName(options, null, 50, "12345678")
        assertTrue(result.endsWith("_050_12345678.jpg"))
        assertNull(ExportNaming.error(ExportNaming.baseName(result)))
        assertTrue(ExportNaming.baseName(result).toByteArray().size <= 180)
    }
}
