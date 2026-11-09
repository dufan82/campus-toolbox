package cn.campuslife.assistant

import android.content.Intent
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.campuslife.assistant.ui.PhotoPickerIntents
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoPickerIntentTest {
    @Test fun modernAndFallbackPickersOnlyAskForSupportedImages() {
        val modern=PhotoPickerIntents.primary(33)
        assertEquals(MediaStore.ACTION_PICK_IMAGES,modern.action)
        assertEquals("image/*",modern.type)
        assertArrayEquals(arrayOf("image/jpeg","image/png"),modern.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        val old=PhotoPickerIntents.primary(26)
        assertEquals(Intent.ACTION_GET_CONTENT,old.action)
        assertEquals("image/*",old.type)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,PhotoPickerIntents.fallback().action)
    }
}
