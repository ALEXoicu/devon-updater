package com.devon.updater

import android.Manifest.permission.POST_NOTIFICATIONS
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.devon.updater.core.Decision
import com.devon.updater.device.SuShell
import com.devon.updater.device.hasRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(POST_NOTIFICATIONS), 1)
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }
}

data class DeviceInfo(val build: String, val kernel: String, val root: Boolean, val updaterDisabled: Boolean?, val gapps: Boolean)

private fun readDevice(ctx: android.content.Context): DeviceInfo {
    val sh = SuShell()
    val root = hasRoot(sh)
    val kernel = sh.run("cat /proc/version").out.trim().ifEmpty { "unknown" }
        .let { Regex("""Linux version (\S+) \((\S+)\)""").find(it)?.let { m -> "${m.groupValues[1]} (${m.groupValues[2]})" } ?: it }
    val disabled = if (root) sh.run("pm list packages -d org.lineageos.updater").out.contains("org.lineageos.updater") else null
    val gapps = runCatching { ctx.packageManager.getPackageInfo("com.google.android.gms", 0); true }.getOrDefault(false)
    return DeviceInfo(runCatching { lineageVersion() }.getOrElse { "unknown (needs root)" }, kernel, root, disabled, gapps)
}

/** After a reboot into the new slot: confirm root works and record a success. */
private fun postBootCheck(ctx: android.content.Context, info: DeviceInfo): String? {
    val prefs = Prefs(ctx)
    val p = prefs.pending ?: return null
    if (!p.kernelWritten) return null
    val inst = runCatching { installed() }.getOrNull() ?: return null
    if (inst.date != p.date) return null // not rebooted yet (or rolled back — resume() sorts that out)
    prefs.pending = null
    val msg = if (info.root) {
        prefs.successes = prefs.successes + 1
        "Updated to ${inst.version}-${inst.date}: root OK" + if (info.gapps) ", Google Play services OK" else ", Google Play services MISSING"
    } else "Booted ${inst.date} but root is not working"
    prefs.lastResult = msg
    return msg
}

@Composable
fun MainScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val install by InstallState.state.collectAsState()
    var info by remember { mutableStateOf<DeviceInfo?>(null) }
    var decision by remember { mutableStateOf<Decision?>(null) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf(prefs.manifestUrl) }
    var lastResult by remember { mutableStateOf(prefs.lastResult) }

    fun refreshDevice() = scope.launch {
        info = withContext(Dispatchers.IO) { readDevice(ctx) }
        withContext(Dispatchers.IO) { postBootCheck(ctx, info!!) }?.let { lastResult = it }
    }

    fun check() = scope.launch {
        checking = true; message = null
        val r = withContext(Dispatchers.IO) { runCatching { checkForUpdate(ctx, SuShell()) } }
        r.onSuccess { decision = it.decision; if (it.manifestError != null && it.decision !is Decision.Offer) message = "Kernel releases: ${it.manifestError}" }
            .onFailure { message = "Check failed: ${it.message}" }
        checking = false
    }

    LaunchedEffect(Unit) {
        refreshDevice().join()
        if (prefs.pending != null && !InstallState.flow.value.running) InstallService.resume(ctx)
        check()
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("devon-updater", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Installed", style = MaterialTheme.typography.labelMedium)
                Text(info?.build ?: "…")
                Text(info?.kernel ?: "", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(if (info?.root == true) "Root: yes" else "Root: no — allow devon-updater in SukiSU manager", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("Available", style = MaterialTheme.typography.labelMedium)
                Text(if (checking) "Checking…" else decision?.let { describe(it) } ?: "—")
                Text("Last check: " + if (prefs.lastCheck > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(prefs.lastCheck)) else "never",
                    style = MaterialTheme.typography.bodySmall)
                if (lastResult.isNotBlank()) Text("Last result: $lastResult", style = MaterialTheme.typography.bodySmall)
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (install.running || install.step != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(install.text)
                    if (install.running) {
                        val f = install.fraction
                        if (f == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                    }
                    install.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val d = decision
            if (install.ready && !install.running) {
                Button(onClick = { scope.launch(Dispatchers.IO) { SuShell().run("/system/bin/svc power reboot") } }) { Text("Reboot now") }
            } else if (d is Decision.Offer) {
                Button(enabled = !install.running && info?.root == true, onClick = {
                    InstallState.flow.value = InstallUi(); InstallService.install(ctx, d.manifest)
                }) { Text("Install update") }
            }
            OutlinedButton(enabled = !checking && !install.running, onClick = { check() }) { Text("Check now") }
        }

        HorizontalDivider()
        Text("Settings", style = MaterialTheme.typography.titleMedium)
        val disabled = info?.updaterDisabled
        Text("Lineage updater: " + when (disabled) { true -> "disabled"; false -> "enabled"; null -> "unknown (no root)" })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = disabled == false && prefs.successes > 0, onClick = {
                scope.launch(Dispatchers.IO) { SuShell().run("pm disable-user --user 0 org.lineageos.updater"); refreshDevice() }
            }) { Text("Disable") }
            OutlinedButton(enabled = disabled == true, onClick = {
                scope.launch(Dispatchers.IO) { SuShell().run("pm enable --user 0 org.lineageos.updater"); refreshDevice() }
            }) { Text("Re-enable") }
        }
        if (prefs.successes == 0) Text("Disabling unlocks after one successful update through this app.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Kernel manifest URL") }, singleLine = true)
        OutlinedButton(onClick = { prefs.manifestUrl = url; check() }) { Text("Save URL") }
    }
}
