package com.devon.updater.core

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Where payload.bin sits inside an A/B OTA zip, plus the update_engine headers. */
data class Payload(val offset: Long, val size: Long, val headers: List<String>)

fun findPayload(zip: File): Payload {
    ZipFile(zip).use { z ->
        val entry = z.getEntry("payload.bin") ?: throw IllegalArgumentException("no payload.bin in ${zip.name}")
        require(entry.method == ZipEntry.STORED) { "payload.bin is compressed" }
        val props = z.getEntry("payload_properties.txt") ?: throw IllegalArgumentException("no payload_properties.txt")
        val headers = z.getInputStream(props).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        require(headers.any { it.startsWith("FILE_HASH=") }) { "payload_properties.txt has no FILE_HASH" }
        val offset = dataOffset(zip, entry)
        return Payload(offset, entry.size, headers)
    }
}

/** ZipEntry doesn't expose the data offset; read the local file header found via the central directory. */
private fun dataOffset(zip: File, entry: ZipEntry): Long {
    RandomAccessFile(zip, "r").use { f ->
        val localHeader = localHeaderOffset(f, entry.name)
        f.seek(localHeader)
        val h = ByteArray(30)
        f.readFully(h)
        require(le32(h, 0) == 0x04034b50L) { "bad local header" }
        val nameLen = le16(h, 26)
        val extraLen = le16(h, 28)
        return localHeader + 30 + nameLen + extraLen
    }
}

private fun localHeaderOffset(f: RandomAccessFile, name: String): Long {
    val len = f.length()
    val tailSize = minOf(len, 65_557L).toInt()
    val tail = ByteArray(tailSize)
    f.seek(len - tailSize)
    f.readFully(tail)
    var eocd = -1
    for (i in tailSize - 22 downTo 0) if (le32(tail, i) == 0x06054b50L) { eocd = i; break }
    require(eocd >= 0) { "no end of central directory" }
    var cdOffset = le32(tail, eocd + 16)
    var cdSize = le32(tail, eocd + 12)
    if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) {
        // zip64: locator sits right before EOCD
        val loc = eocd - 20
        require(loc >= 0 && le32(tail, loc) == 0x07064b50L) { "zip64 locator missing" }
        val z64 = le64(tail, loc + 8)
        val rec = ByteArray(56)
        f.seek(z64); f.readFully(rec)
        cdSize = le64(rec, 40); cdOffset = le64(rec, 48)
    }
    val cd = ByteArray(cdSize.toInt())
    f.seek(cdOffset); f.readFully(cd)
    var p = 0
    while (p + 46 <= cd.size && le32(cd, p) == 0x02014b50L) {
        val n = le16(cd, p + 28); val x = le16(cd, p + 30); val c = le16(cd, p + 32)
        val entryName = String(cd, p + 46, n, Charsets.UTF_8)
        var off = le32(cd, p + 42)
        if (entryName == name) {
            if (off == 0xFFFFFFFFL) {
                // zip64 extra field: take the last 8-byte value for the local header offset
                var e = p + 46 + n
                val end = e + x
                while (e + 4 <= end) {
                    val id = le16(cd, e); val sz = le16(cd, e + 2)
                    if (id == 1) off = le64(cd, e + 4 + sz - 8)
                    e += 4 + sz
                }
            }
            return off
        }
        p += 46 + n + x + c
    }
    throw IllegalArgumentException("$name not in central directory")
}

private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
private fun le32(b: ByteArray, i: Int): Long = le16(b, i).toLong() or (le16(b, i + 2).toLong() shl 16)
private fun le64(b: ByteArray, i: Int): Long = le32(b, i) or (le32(b, i + 4) shl 32)
