package com.devon.updater.core

/** What is installed now, parsed from ro.lineage.version (e.g. "24.0-20261107-NIGHTLY-devon"). */
data class Installed(val version: String, val date: String, val major: Int, val device: String)

fun parseLineageVersion(prop: String): Installed {
    val m = Regex("""^(\d+)\.(\d+)-(\d{8})-[A-Z]+-([A-Za-z0-9_]+)$""").matchEntire(prop.trim())
        ?: throw IllegalArgumentException("unrecognised ro.lineage.version: '$prop'")
    val (maj, min, date, device) = m.destructured
    return Installed("$maj.$min", date, maj.toInt(), device)
}

fun majorOf(version: String): Int = version.substringBefore('.').toInt()

/** A build as listed by the Lineage API. */
data class LineageBuild(val date: String, val version: String)
