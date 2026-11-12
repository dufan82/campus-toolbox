package cn.campuslife.assistant.daily
import org.junit.Assert.*
import org.junit.Test
class Daily60RulesTest {
 @Test fun pngDimensionsAreBoundedBeforeDecode() {
  val image=Daily60TestImage.bytes
  java.nio.ByteBuffer.wrap(image,16,4).putInt(9000)
  assertThrows(Exception::class.java){Daily60Rules.image(image,"image/png")}
 }
 @Test fun mismatchedMimeOrIncompletePngCannotPassAsImage() {
  assertThrows(Exception::class.java){Daily60Rules.image(Daily60TestImage.bytes,"image/jpeg")}
  assertThrows(Exception::class.java){Daily60Rules.image(Daily60TestImage.bytes.dropLast(12).toByteArray(),"image/png")}
 }
 @Test fun failedReasonDoesNotExposeInternalExceptionMessage() {
  val detail="synthetic-secret-path"
  assertFalse(Daily60Rules.failure(java.io.IOException(detail)).contains(detail))
  assertTrue(Daily60Rules.failure(java.net.SocketTimeoutException()).contains("超时"))
  assertTrue(Daily60Rules.failure(Daily60HttpException(429)).contains("429"))
 }
}
