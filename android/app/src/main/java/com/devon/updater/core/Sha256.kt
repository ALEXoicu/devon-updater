package com.devon.updater.core

import java.io.File
import java.security.MessageDigest

fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { s ->
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

class ShaMismatch(what: String, expected: String, actual: String) :
    Exception("$what: sha256 $actual, expected $expected")

fun verifySha256(file: File, expected: String, what: String = file.name) {
    val actual = sha256(file)
    if (!actual.equals(expected, ignoreCase = true)) throw ShaMismatch(what, expected, actual)
}
