package cn.campuslife.assistant.schedule

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

class SchoolHttpException(val status:Int) : IOException("School HTTP response: $status")

/** Fixed, non-sensitive diagnostics. Never display a response body, token, cookie or raw exception. */
object SchoolReadFailure {
    fun message(error:Throwable, subject:String):String {
        val reason=when(error) {
            is SchoolAuthenticationException -> if(error.responseCode==403)
                "学校拒绝了请求（403），请在学校页面重新登录后重试。"
                else "学校登录或设备会话已失效，请重新登录后重试。"
            is SocketTimeoutException -> "连接学校平台超时，请稍后重试。"
            is SSLException -> "学校连接证书验证失败，请稍后重试。"
            is SchoolHttpException -> if(error.status in 100..599)
                "学校平台返回异常（HTTP ${error.status}），请稍后重试。"
                else "学校连接失败，请稍后重试。"
            is InterruptedIOException -> "读取已中断，请重试。"
            is IOException -> "无法连接学校平台，请检查网络后重试。"
            else -> "学校返回的${if(subject=="课表")"课表" else "学期"}数据格式暂不兼容。"
        }
        return reason+"本地课表未改。"
    }
}
