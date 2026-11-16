package com.devon.updater

import android.content.Context
import com.devon.updater.core.Decision
import com.devon.updater.core.Installed
import com.devon.updater.core.decide
import com.devon.updater.core.newestLineageBuild
import com.devon.updater.core.parseLineageVersion
import com.devon.updater.core.parseManifest
import com.devon.updater.device.Shell
import com.devon.updater.device.hasRoot

const val LINEAGE_API = "https://download.lineageos.org/api/v2/devices/devon/builds"

fun getprop(name: String): String =
    ProcessBuilder("getprop", name).start().inputStream.bufferedReader().readText().trim()

/**
 * ro.lineage.version is labelled custom_version_prop, which SELinux hides from normal apps,
 * so read it as root; plain getprop is only a fallback.
 */
fun lineageVersion(): String {
    val viaSu = com.devon.updater.device.SuShell().run("getprop ro.lineage.version")
    return viaSu.out.trim().ifEmpty { getprop("ro.lineage.version") }
        .ifEmpty { throw IllegalStateException("can't read ro.lineage.version — allow devon-updater in SukiSU manager") }
}

fun installed(): Installed = parseLineageVersion(lineageVersion())

data class CheckResult(val installed: Installed, val decision: Decision, val manifestError: String?)

fun checkForUpdate(ctx: Context, shell: Shell): CheckResult {
    val prefs = Prefs(ctx)
    val inst = installed()
    val latest = newestLineageBuild(Net.getText(LINEAGE_API))
    var err: String? = null
    val manifest = runCatching { parseManifest(Net.getText(prefs.manifestUrl)) }.getOrElse { err = it.message; null }
    val d = decide(inst, latest, manifest, hasRoot(shell))
    prefs.lastCheck = System.currentTimeMillis()
    prefs.lastCheckResult = describe(d)
    return CheckResult(inst, d, err)
}

fun describe(d: Decision): String = when (d) {
    Decision.None -> "Up to date"
    is Decision.Offer -> "Lineage ${d.manifest.version}-${d.manifest.date} + kernel ready"
    is Decision.NoKernelYet -> "Lineage ${d.date} is out; its kernel isn't built yet"
    is Decision.MajorUpgrade -> "Major upgrade to Lineage ${d.version} (${d.date}) — install manually"
    is Decision.KernelOnly -> "Stock kernel running — flash boot-${d.manifest.date}-sukisu.img with fastboot"
}
