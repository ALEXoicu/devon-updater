package com.devon.updater

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.devon.updater.device.Engine
import com.devon.updater.device.EngineStatus
import com.devon.updater.device.Shell
import com.devon.updater.device.hasRoot

const val MIN_BATTERY = 40
const val MIN_FREE_BYTES = 3L * 1024 * 1024 * 1024

fun preflightProblems(ctx: Context, shell: Shell, engine: Engine): List<String> {
    val out = mutableListOf<String>()
    if (!hasRoot(shell)) return listOf("No root: allow devon-updater in the SukiSU manager (Superuser tab)")
    val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = bat?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } ?: 0
    val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    if (level < MIN_BATTERY && !plugged) out += "Battery $level% — charge to $MIN_BATTERY% or plug in"
    val free = StatFs(Environment.getDataDirectory().path).availableBytes
    if (free < MIN_FREE_BYTES) out += "Only ${free / (1 shl 20)} MB free, need 3 GB"
    val st = runCatching { engine.status() }.getOrElse { return out + "Can't reach update_engine: ${it.message}" }
    if (st != EngineStatus.IDLE) out += "update_engine is busy (${EngineStatus.name(st)})"
    return out
}
