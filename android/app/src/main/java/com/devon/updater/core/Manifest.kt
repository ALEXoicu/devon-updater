package com.devon.updater.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** manifest.json published by the kernel workflow with each k-<date> release. */
@Serializable
data class Manifest(
    val device: String,
    val date: String,
    val version: String,
    val android: Int,
    val ota_url: String,
    val ota_sha256: String,
    val ota_size: Long,
    val kernel_hash: String,
    val boot_url: String,
    val boot_sha256: String,
    val sukisu: Int,
    val susfs: String,
    val nomount: String,
)

private val json = Json { ignoreUnknownKeys = true }

fun parseManifest(text: String): Manifest = json.decodeFromString(Manifest.serializer(), text)

fun encodeManifest(m: Manifest): String = json.encodeToString(Manifest.serializer(), m)

/** Newest build from the Lineage API response (`[{"date":"2026-11-14","version":"24.0",...}]`). */
fun newestLineageBuild(apiJson: String): LineageBuild? =
    json.parseToJsonElement(apiJson).jsonArray
        .map { it.jsonObject }
        .map { LineageBuild(it["date"]!!.jsonPrimitive.content.replace("-", ""), it["version"]!!.jsonPrimitive.content) }
        .maxByOrNull { it.date }
