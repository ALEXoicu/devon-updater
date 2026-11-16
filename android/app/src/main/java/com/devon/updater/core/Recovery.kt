package com.devon.updater.core

import kotlinx.serialization.Serializable

/** Install in progress, persisted so an app kill / restart can finish or undo it. */
@Serializable
data class PendingInstall(
    val date: String,
    val slot: String,
    val bootPath: String,
    val bootSha256: String,
    val kernelWritten: Boolean = false,
)

sealed interface RecoveryAction {
    /** Nothing to do. */
    data object None : RecoveryAction
    /** update_engine is still working on our update: follow it, then write the kernel. */
    data object Watch : RecoveryAction
    /** OTA finished but the kernel wasn't written yet: write it now. */
    data object WriteKernel : RecoveryAction
    /** OTA finished, kernel can't be written (image missing/bad): undo so a root-less slot never boots. */
    data object Reset : RecoveryAction
    /** Everything written; waiting for the user's reboot tap. */
    data object Ready : RecoveryAction
    /** Our record is stale (update was cancelled or failed): forget it. */
    data object ClearPending : RecoveryAction
    /** An update this app didn't start (e.g. Lineage updater) is staged: it would boot the stock kernel. */
    data object ForeignUpdate : RecoveryAction
}

fun recoveryAction(engineStatus: Int, pending: PendingInstall?, bootImageOk: Boolean): RecoveryAction {
    val needReboot = engineStatus == 6
    val busy = engineStatus in 1..5 || engineStatus == 11
    return when {
        pending == null && needReboot -> RecoveryAction.ForeignUpdate
        pending == null -> RecoveryAction.None
        busy -> RecoveryAction.Watch
        needReboot && pending.kernelWritten -> RecoveryAction.Ready
        needReboot && bootImageOk -> RecoveryAction.WriteKernel
        needReboot -> RecoveryAction.Reset
        else -> RecoveryAction.ClearPending
    }
}
