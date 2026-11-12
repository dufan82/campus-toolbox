package cn.campuslife.assistant.daily

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.net.UnknownHostException

class Daily60ImageRulesTest {
    private val png get()=Daily60TestImage.bytes
    @Test fun pictureProtocolKeepsOriginalPngBytesAndMime() {
        val image=Daily60Rules.image(png,"image/png")
        assertArrayEquals(png,image.bytes);assertEquals("image/png",image.mime)
        assertTrue(Daily60Http.ENDPOINT.endsWith("?encoding=image-proxy"))
    }
    @Test fun wrongMimeHtmlTruncationAndOversizeNeverBecomeCachedPictures() {
        for((bytes,mime) in listOf("<html>error</html>".toByteArray() to "image/png",png to "text/html",png.copyOf(22) to "image/png",ByteArray(5*1024*1024+1) to "image/png"))
            assertThrows(Exception::class.java){Daily60Rules.image(bytes,mime)}
    }
    @Test fun labelNamesFetchTimeWithoutPretendingItIsTheIssueDate() {
        val s=Daily60Snapshot(Daily60Rules.image(png,"image/png"),1794441600000)
        val label=Daily60Rules.readingLabel(s,true)
        assertTrue(label.contains("缓存"));assertTrue(label.contains("11-12"));assertFalse(label.contains("今日"))
        assertTrue(Daily60Rules.failure(UnknownHostException()).contains("网络"))
    }
}
