package com.devon.updater

import android.content.Context
import com.devon.updater.core.PendingInstall
import kotlinx.serialization.json.Json

class Prefs(ctx: Context) : PendingStore {
    private val sp = ctx.getSharedPreferences("devon-updater", Context.MODE_PRIVATE)

    var manifestUrl: String
        get() = sp.getString("manifestUrl", null) ?: BuildConfig.MANIFEST_URL
        set(v) = sp.edit().putString("manifestUrl", v.trim()).apply()

    var lastCheck: Long
        get() = sp.getLong("lastCheck", 0)
        set(v) = sp.edit().putLong("lastCheck", v).apply()

    var lastCheckResult: String
        get() = sp.getString("lastCheckResult", "") ?: ""
        set(v) = sp.edit().putString("lastCheckResult", v).apply()

    var lastNotified: String
        get() = sp.getString("lastNotified", "") ?: ""
        set(v) = sp.edit().putString("lastNotified", v).apply()

    var successes: Int
        get() = sp.getInt("successes", 0)
        set(v) = sp.edit().putInt("successes", v).apply()

    var lastResult: String
        get() = sp.getString("lastResult", "") ?: ""
        set(v) = sp.edit().putString("lastResult", v).apply()

    override var pending: PendingInstall?
        get() = sp.getString("pending", null)?.let { runCatching { Json.decodeFromString(PendingInstall.serializer(), it) }.getOrNull() }
        set(v) {
            // commit(): this record must survive a crash right after it's written
            sp.edit().apply {
                if (v == null) remove("pending") else putString("pending", Json.encodeToString(PendingInstall.serializer(), v))
            }.commit()
        }
}
