package com.devon.updater.core

sealed interface Decision {
    /** Up to date. */
    data object None : Decision
    /** Newer nightly + matching verified kernel: offer install. */
    data class Offer(val manifest: Manifest) : Decision
    /** Running build has a kernel release but the stock kernel is booted (no root): reflash by hand. */
    data class KernelOnly(val manifest: Manifest) : Decision
    /** A newer nightly exists but its kernel isn't built yet. */
    data class NoKernelYet(val date: String) : Decision
    /** A newer nightly is a different Lineage major: never installed by the app. */
    data class MajorUpgrade(val version: String, val date: String) : Decision
}

fun decide(installed: Installed, latest: LineageBuild?, manifest: Manifest?, hasRoot: Boolean): Decision {
    val m = manifest?.takeIf { it.device == installed.device }
    if (latest != null && latest.date > installed.date && majorOf(latest.version) != installed.major)
        return Decision.MajorUpgrade(latest.version, latest.date)
    if (m != null && m.date > installed.date) {
        if (majorOf(m.version) != installed.major) return Decision.MajorUpgrade(m.version, m.date)
        return Decision.Offer(m)
    }
    if (latest != null && latest.date > installed.date) return Decision.NoKernelYet(latest.date)
    if (m != null && m.date == installed.date && !hasRoot) return Decision.KernelOnly(m)
    return Decision.None
}
