package com.devon.updater

import com.devon.updater.core.Manifest
import com.devon.updater.core.PendingInstall
import com.devon.updater.core.RecoveryAction
import com.devon.updater.core.recoveryAction
import com.devon.updater.core.sha256
import com.devon.updater.device.BootWriter
import com.devon.updater.device.Engine
import com.devon.updater.device.EngineStatus
import com.devon.updater.device.Shell
import com.devon.updater.device.Slots
import com.devon.updater.device.check
import com.devon.updater.device.q
import java.io.File

enum class Step { Preflight, DownloadKernel, DownloadOta, ApplyOta, WriteKernel, Ready, Failed }

interface Downloads {
    /** Download [url] to an app-owned [dest], resuming if partial; verify sha256. */
    fun toFile(url: String, dest: File, sha256: String, onProgress: (Long, Long) -> Unit)
    /** Download [url] to a root-owned [path] (via su), resuming if partial; verify sha256 as root. */
    fun toRootFile(url: String, path: String, size: Long, sha256: String, onProgress: (Long, Long) -> Unit)
}

interface PendingStore {
    var pending: PendingInstall?
}

class InstallFailed(msg: String, cause: Throwable? = null) : Exception(msg, cause)

/**
 * Orchestrates one update. Order matters: the kernel is written only after update_engine is done,
 * and if writing it fails, the staged update is reset so the phone never boots a slot without root.
 */
class Installer(
    private val shell: Shell,
    private val engine: Engine,
    private val slots: Slots,
    private val boot: BootWriter,
    private val downloads: Downloads,
    private val store: PendingStore,
    private val workDir: File,
    private val preflight: () -> List<String>,
    private val report: (Step, String, Float?) -> Unit,
) {
    companion object {
        const val OTA_DIR = "/data/ota_package"
        fun otaPath(date: String) = "$OTA_DIR/devon-updater-$date.zip"
    }

    fun install(m: Manifest) {
        report(Step.Preflight, "Checking phone", null)
        val problems = preflight()
        if (problems.isNotEmpty()) throw InstallFailed(problems.joinToString("; "))
        val slot = slots.inactive()

        report(Step.DownloadKernel, "Downloading kernel", 0f)
        val bootFile = File(workDir, "boot-${m.date}-sukisu.img")
        downloads.toFile(m.boot_url, bootFile, m.boot_sha256) { d, t -> report(Step.DownloadKernel, "Downloading kernel", frac(d, t)) }
        store.pending = PendingInstall(m.date, slot, bootFile.path, m.boot_sha256)

        report(Step.DownloadOta, "Downloading Lineage ${m.date}", 0f)
        val ota = otaPath(m.date)
        downloads.toRootFile(m.ota_url, ota, m.ota_size, m.ota_sha256) { d, t ->
            report(Step.DownloadOta, "Downloading Lineage ${m.date}", frac(d, t))
        }
        shell.check("chmod 644 ${q(ota)} && restorecon ${q(ota)}")

        report(Step.ApplyOta, "Installing to slot $slot", 0f)
        val code = engine.apply(ota) { s, p -> report(Step.ApplyOta, "Installing to slot $slot: ${EngineStatus.name(s)}", p) }
        if (code != 0) {
            store.pending = null
            throw InstallFailed("update_engine failed with error $code")
        }
        writeKernel()
    }

    /** Called on app/service start: finish or undo an interrupted install. Returns true if ready for reboot. */
    fun resume(): Boolean {
        val pending = store.pending
        val status = engine.status()
        val bootOk = pending != null && File(pending.bootPath).let { it.exists() && sha256(it) == pending.bootSha256 }
        return when (recoveryAction(status, pending, bootOk)) {
            RecoveryAction.None -> false
            RecoveryAction.ClearPending -> { store.pending = null; false }
            RecoveryAction.ForeignUpdate -> {
                report(Step.Failed, "An update not installed by this app is waiting for reboot. It would boot without root.", null)
                false
            }
            RecoveryAction.Ready -> { report(Step.Ready, "Ready — reboot to finish", null); true }
            RecoveryAction.Reset -> {
                engine.reset(); store.pending = null
                report(Step.Failed, "Kernel image missing after OTA; update cancelled", null); false
            }
            RecoveryAction.WriteKernel -> { writeKernel(); true }
            RecoveryAction.Watch -> {
                val code = engine.watch { s, p -> report(Step.ApplyOta, "Installing: ${EngineStatus.name(s)}", p) }
                if (code != 0) { store.pending = null; throw InstallFailed("update_engine failed with error $code") }
                writeKernel(); true
            }
        }
    }

    private fun writeKernel() {
        val p = store.pending ?: throw InstallFailed("no pending install")
        report(Step.WriteKernel, "Writing kernel to boot${p.slot}", null)
        try {
            boot.write(File(p.bootPath), p.bootSha256, p.slot)
        } catch (e: Exception) {
            runCatching { engine.reset() }
            store.pending = null
            throw InstallFailed("Kernel write failed, update cancelled: ${e.message}", e)
        }
        store.pending = p.copy(kernelWritten = true)
        // only now: if anything above failed, the verified OTA stays for a quick retry
        shell.run("rm -f $OTA_DIR/devon-updater-*.zip")
        report(Step.Ready, "Ready — reboot to finish", null)
    }

    private fun frac(d: Long, t: Long) = if (t > 0) d.toFloat() / t else 0f
}
