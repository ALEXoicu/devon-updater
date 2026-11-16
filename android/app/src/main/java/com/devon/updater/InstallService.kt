package com.devon.updater

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.devon.updater.core.Manifest
import com.devon.updater.core.encodeManifest
import com.devon.updater.core.parseManifest
import com.devon.updater.device.BootWriter
import com.devon.updater.device.HelperEngine
import com.devon.updater.device.Slots
import com.devon.updater.device.SuShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class InstallUi(val running: Boolean = false, val step: Step? = null, val text: String = "", val fraction: Float? = null, val ready: Boolean = false, val error: String? = null)

object InstallState {
    val flow = MutableStateFlow(InstallUi())
    val state: StateFlow<InstallUi> get() = flow
}

fun buildInstaller(ctx: Context, report: (Step, String, Float?) -> Unit): Installer {
    val shell = SuShell()
    val slots = Slots(shell)
    val engine = HelperEngine(shell, ctx.applicationInfo.sourceDir)
    return Installer(
        shell, engine, slots, BootWriter(shell, slots), HttpDownloads(shell), Prefs(ctx),
        File(ctx.filesDir, "downloads"), { preflightProblems(ctx, shell, engine) }, report,
    )
}

/** Foreground service running an install (or resuming one) so it survives leaving the app. */
class InstallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (InstallState.flow.value.running) return START_NOT_STICKY
        val manifest = intent?.getStringExtra(EXTRA_MANIFEST)?.let { parseManifest(it) }
        startForeground(Notifications.ID_PROGRESS, Notifications.progress(this, "Starting", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        InstallState.flow.value = InstallUi(running = true, text = "Starting")
        Thread {
            val nm = getSystemService(NotificationManager::class.java)
            var last = 0L
            val installer = buildInstaller(this) { step, text, f ->
                InstallState.flow.value = InstallState.flow.value.copy(step = step, text = text, fraction = f, ready = step == Step.Ready)
                val now = System.currentTimeMillis()
                if (now - last > 1000) { last = now; nm.notify(Notifications.ID_PROGRESS, Notifications.progress(this, text, f)) }
            }
            val prefs = Prefs(this)
            try {
                val ready = if (manifest != null) { installer.install(manifest); true } else installer.resume()
                InstallState.flow.value = InstallState.flow.value.copy(running = false, ready = ready)
                if (ready) prefs.lastResult = "Installed to inactive slot, waiting for reboot"
            } catch (e: Exception) {
                prefs.lastResult = "Failed: ${e.message}"
                InstallState.flow.value = InstallState.flow.value.copy(running = false, step = Step.Failed, error = e.message)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (InstallState.flow.value.ready) Notifications.update(this, "Update installed", "Open devon-updater and tap Reboot")
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_MANIFEST = "manifest"
        fun install(ctx: Context, m: Manifest) =
            ctx.startForegroundService(Intent(ctx, InstallService::class.java).putExtra(EXTRA_MANIFEST, encodeManifest(m)))
        fun resume(ctx: Context) = ctx.startForegroundService(Intent(ctx, InstallService::class.java))
    }
}
