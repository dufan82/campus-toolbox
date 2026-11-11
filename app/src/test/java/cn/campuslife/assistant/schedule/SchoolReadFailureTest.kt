package cn.campuslife.assistant.schedule

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class SchoolReadFailureTest {
    @Test fun expiryAndForbiddenHaveActionableMessagesInsteadOfGenericSemesterFailure() {
        assertTrue(SchoolReadFailure.message(SchoolAuthenticationException(303),"学期").contains("重新登录"))
        assertTrue(SchoolReadFailure.message(SchoolAuthenticationException(409),"学期").contains("设备会话"))
        assertTrue(SchoolReadFailure.message(SchoolAuthenticationException(403),"学期").contains("拒绝了请求（403）"))
    }
    @Test fun timeoutNetworkTlsAndPlatformFailuresAreDifferent() {
        assertTrue(SchoolReadFailure.message(SocketTimeoutException(),"学期").contains("超时"))
        assertTrue(SchoolReadFailure.message(IOException(),"学期").contains("无法连接"))
        assertTrue(SchoolReadFailure.message(SSLHandshakeException("private"),"学期").contains("证书验证失败"))
        assertTrue(SchoolReadFailure.message(SchoolHttpException(503),"学期").contains("HTTP 503"))
    }
    @Test fun rawSchoolErrorsAndIdentityAreNeverEchoedToTheUserOrHistory() {
        val sensitive="private-token; private-cookie; student-private"
        val message=SchoolReadFailure.message(IllegalArgumentException(sensitive),"学期")
        assertFalse(message.contains(sensitive));assertTrue(message.contains("学期数据格式"))
        assertTrue(message.endsWith("本地课表未改。"))
        assertTrue(SchoolReadFailure.message(IllegalArgumentException(sensitive),"课表").contains("课表数据格式"))
    }
}
