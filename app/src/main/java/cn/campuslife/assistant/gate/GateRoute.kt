package cn.campuslife.assistant.gate

/** Fixed official QR destinations. No external input can change the destination. */
enum class GateRoute(val campus: String, val gate: String, val title: String, val url: String) {
    EAST_IN("平潭校区", "东门", "东门入校", "https://wx.fjpit.com/wechat/api.security/accessGate?xmdm=10066&crlx=1"),
    EAST_OUT("平潭校区", "东门", "东门出校", "https://wx.fjpit.com/wechat/api.security/accessGate?xmdm=10067&crlx=2"),
    NORTH_IN("平潭校区", "北门", "北门入校", "https://wx.fjpit.com/wechat/api.security/accessGate?xmdm=10062&crlx=1"),
    NORTH_OUT("平潭校区", "北门", "北门出校", "https://wx.fjpit.com/wechat/api.security/accessGate?xmdm=10063&crlx=2")
}
