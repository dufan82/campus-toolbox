package cn.campuslife.assistant.daily

/** Same anonymous PNG proxy as the mailer; no email/account configuration. */
internal class Daily60ApiClient: Daily60Client {
    override fun fetch():Daily60Image=Daily60Http().read().also {Daily60Bitmap.decode(it).recycle()}
}
