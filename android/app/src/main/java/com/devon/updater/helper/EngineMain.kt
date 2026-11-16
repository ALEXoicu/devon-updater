package com.devon.updater.helper

import android.os.Handler
import android.os.HandlerThread
import android.os.UpdateEngine
import android.os.UpdateEngineCallback
import com.devon.updater.core.findPayload
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Root helper, run as: CLASSPATH=<apk> app_process /system/bin com.devon.updater.helper.EngineMain <cmd>
 * Commands: status | apply <zip> | watch | reset. Prints lines: status <code> <pct>, payload <off> <size>,
 * done <errorCode>, error <message>.
 */
object EngineMain {
    private const val NEED_REBOOT = 6

    private fun out(s: String) { println(s); System.out.flush() }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
            exitProcess(0)
        } catch (t: Throwable) {
            out("error ${t.javaClass.simpleName}: ${t.message}".replace('\n', ' '))
            exitProcess(1)
        }
    }

    private fun run(args: Array<String>) {
        val thread = HandlerThread("engine").apply { start() }
        val handler = Handler(thread.looper)
        val engine = UpdateEngine()
        val finished = CountDownLatch(1)
        var result = -1
        var first = true
        val mode = args.getOrNull(0) ?: throw IllegalArgumentException("usage: status|apply <zip>|watch|reset")
        val cb = object : UpdateEngineCallback() {
            override fun onStatusUpdate(status: Int, percent: Float) {
                out("status $status $percent")
                val wasFirst = first
                first = false
                when (mode) {
                    "status" -> finished.countDown()
                    "watch" -> if (status == NEED_REBOOT) { result = 0; finished.countDown() }
                        else if (status == 0 && wasFirst) { result = -1; finished.countDown() }
                }
            }

            override fun onPayloadApplicationComplete(errorCode: Int) {
                result = errorCode
                finished.countDown()
            }
        }
        when (mode) {
            "reset" -> {
                engine.resetStatus()
                out("done 0")
                return
            }
            "status", "watch" -> engine.bind(cb, handler)
            "apply" -> {
                val zip = File(args[1])
                val p = findPayload(zip)
                out("payload ${p.offset} ${p.size}")
                engine.bind(cb, handler)
                engine.applyPayload("file://${zip.absolutePath}", p.offset, p.size, p.headers.toTypedArray())
            }
            else -> throw IllegalArgumentException("unknown command $mode")
        }
        if (!finished.await(if (mode == "status") 30 else 7200, TimeUnit.SECONDS))
            throw IllegalStateException("timed out waiting for update_engine")
        engine.unbind()
        if (mode != "status") out("done $result")
        else out("done 0")
    }
}
