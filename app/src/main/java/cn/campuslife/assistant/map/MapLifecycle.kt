package cn.campuslife.assistant.map

/** A partially loaded native provider must not block consent withdrawal or other cleanup. */
object MapLifecycle {
    fun release(vararg actions: ()->Unit) {
        actions.forEach { action ->
            try { action() }
            catch(_: Exception) { /* Continue independent cleanup; never log provider/user data. */ }
            catch(_: LinkageError) { /* Native initialization may have failed halfway through. */ }
        }
    }

    fun withdraw(persist: ()->Boolean,vararg releaseActions: ()->Unit): Boolean {
        var saved=false
        release({ saved=persist() },*releaseActions)
        return saved
    }
}
