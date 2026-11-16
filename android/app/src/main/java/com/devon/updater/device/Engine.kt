package com.devon.updater.device

/** update_engine status codes (UpdateEngine.UpdateStatusConstants). */
object EngineStatus {
    const val IDLE = 0
    const val CHECKING_FOR_UPDATE = 1
    const val UPDATE_AVAILABLE = 2
    const val DOWNLOADING = 3
    const val VERIFYING = 4
    const val FINALIZING = 5
    const val UPDATED_NEED_REBOOT = 6
    const val REPORTING_ERROR_EVENT = 7
    const val ATTEMPTING_ROLLBACK = 8
    const val DISABLED = 9
    const val CLEANUP_PREVIOUS_UPDATE = 11

    fun inProgress(s: Int) = s in 1..5 || s == CLEANUP_PREVIOUS_UPDATE
    fun name(s: Int) = when (s) {
        IDLE -> "idle"; DOWNLOADING -> "writing"; VERIFYING -> "verifying"; FINALIZING -> "finalizing"
        UPDATED_NEED_REBOOT -> "updated, needs reboot"; CLEANUP_PREVIOUS_UPDATE -> "cleaning up previous update"
        else -> "status $s"
    }
}

sealed interface EngineEvent {
    data class Status(val status: Int, val percent: Float) : EngineEvent
    data class Payload(val offset: Long, val size: Long) : EngineEvent
    data class Done(val errorCode: Int) : EngineEvent
    data class Error(val message: String) : EngineEvent
}

/** Line protocol printed by helper.EngineMain. */
fun parseEngineLine(line: String): EngineEvent? {
    val p = line.trim().split(' ', limit = 3)
    return when (p.getOrNull(0)) {
        "status" -> EngineEvent.Status(p[1].toInt(), p[2].toFloat())
        "payload" -> EngineEvent.Payload(p[1].toLong(), p[2].toLong())
        "done" -> EngineEvent.Done(p[1].toInt())
        "error" -> EngineEvent.Error(line.trim().removePrefix("error").trim())
        else -> null
    }
}

class EngineException(msg: String) : Exception(msg)

interface Engine {
    fun status(): Int
    /** Applies the OTA at [zipPath] (root-readable, update_engine-readable). Returns update_engine error code (0 = success). */
    fun apply(zipPath: String, onStatus: (Int, Float) -> Unit): Int
    /** Follows an update already in progress. Returns error code, or -1 if nothing was running. */
    fun watch(onStatus: (Int, Float) -> Unit): Int
    fun reset()
}

/** Talks to update_engine through the root helper (app_process running helper.EngineMain from our APK). */
class HelperEngine(private val shell: Shell, private val apkPath: String) : Engine {
    private fun cmd(args: String) =
        "CLASSPATH=${q(apkPath)} /system/bin/app_process /system/bin com.devon.updater.helper.EngineMain $args"

    private fun stream(args: String, onStatus: (Int, Float) -> Unit): Int {
        val p = shell.spawn(cmd(args))
        p.outputStream.close()
        val err = StringBuilder()
        val t = Thread { err.append(p.errorStream.bufferedReader().readText()) }.apply { start() }
        var done: Int? = null
        var error: String? = null
        p.inputStream.bufferedReader().forEachLine { line ->
            when (val e = parseEngineLine(line)) {
                is EngineEvent.Status -> onStatus(e.status, e.percent)
                is EngineEvent.Done -> done = e.errorCode
                is EngineEvent.Error -> error = e.message
                else -> {}
            }
        }
        p.waitFor(); t.join()
        error?.let { throw EngineException(it) }
        return done ?: throw EngineException("helper exited without result: ${err.toString().take(400)}")
    }

    override fun status(): Int {
        var s = -1
        stream("status") { st, _ -> s = st }
        if (s < 0) throw EngineException("no status from update_engine")
        return s
    }

    override fun apply(zipPath: String, onStatus: (Int, Float) -> Unit) = stream("apply ${q(zipPath)}", onStatus)
    override fun watch(onStatus: (Int, Float) -> Unit) = stream("watch", onStatus)
    override fun reset() { stream("reset") { _, _ -> } }
}
