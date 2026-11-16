package com.devon.updater

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.devon.updater.core.Decision
import com.devon.updater.device.SuShell
import java.util.concurrent.TimeUnit

class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val r = runCatching { checkForUpdate(applicationContext, SuShell()) }.getOrElse { return Result.retry() }
        val prefs = Prefs(applicationContext)
        val key = when (val d = r.decision) {
            is Decision.Offer -> "offer-${d.manifest.date}"
            is Decision.MajorUpgrade -> "major-${d.date}"
            else -> null
        }
        if (key != null && key != prefs.lastNotified) {
            val title = if (r.decision is Decision.Offer) "Update ready" else "Major Lineage upgrade"
            Notifications.update(applicationContext, title, describe(r.decision))
            prefs.lastNotified = key
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<CheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("check", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
