package cn.campuslife.assistant.daily

internal object Daily60TestImage {
    val bytes get()=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMw3bYAAAKvAYz0kzj8AAAAAElFTkSuQmCC")
    fun image()=Daily60Rules.image(bytes,"image/png")
}
