package com.devon.updater

import com.devon.updater.core.ShaMismatch
import com.devon.updater.core.verifySha256
import com.devon.updater.device.Shell
import com.devon.updater.device.check
import com.devon.updater.device.q
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getText(url: String): String {
        client.newCall(Request.Builder().url(url).header("User-Agent", "devon-updater").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GET $url: HTTP ${r.code}")
            return r.body.string()
        }
    }
}

class HttpDownloads(private val shell: Shell) : Downloads {
    override fun toFile(url: String, dest: File, sha256: String, onProgress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        if (dest.exists() && runCatching { verifySha256(dest, sha256) }.isSuccess) return
        fetch(url, if (dest.exists()) dest.length() else 0L, onProgress) { append, body ->
            java.io.FileOutputStream(dest, append).use { copy(body, it) }
        }
        try {
            verifySha256(dest, sha256)
        } catch (e: ShaMismatch) {
            dest.delete(); throw e
        }
    }

    override fun toRootFile(url: String, path: String, size: Long, sha256: String, onProgress: (Long, Long) -> Unit) {
        shell.check("mkdir -p ${q(path.substringBeforeLast('/'))}")
        var have = shell.run("stat -c %s ${q(path)}").out.trim().toLongOrNull() ?: 0L
        if (have > size) { shell.run("rm -f ${q(path)}"); have = 0 }
        if (have < size) {
            fetch(url, have, onProgress) { append, body ->
                val p = shell.spawn((if (append) "cat >> " else "cat > ") + q(path))
                try {
                    p.outputStream.use { copy(body, it) }
                } finally {
                    if (p.waitFor() != 0) throw IOException("writing $path as root failed")
                }
            }
        }
        val actual = shell.check("sha256sum ${q(path)}").substringBefore(' ')
        if (!actual.equals(sha256, ignoreCase = true)) {
            shell.run("rm -f ${q(path)}")
            throw ShaMismatch(path, sha256, actual)
        }
    }

    private fun fetch(url: String, from: Long, onProgress: (Long, Long) -> Unit, sink: (Boolean, ProgressStream) -> Unit) {
        val req = Request.Builder().url(url).header("User-Agent", "devon-updater")
            .apply { if (from > 0) header("Range", "bytes=$from-") }.build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GET $url: HTTP ${r.code}")
            val resumed = r.code == 206
            val start = if (resumed) from else 0L
            val total = start + r.body.contentLength()
            sink(resumed, ProgressStream(r.body.byteStream(), start, total, onProgress))
        }
    }

    private fun copy(i: InputStream, o: OutputStream) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            o.write(buf, 0, n)
        }
    }
}

class ProgressStream(
    private val inner: InputStream,
    private var done: Long,
    private val total: Long,
    private val onProgress: (Long, Long) -> Unit,
) : InputStream() {
    private var lastReport = 0L
    override fun read(): Int = inner.read().also { if (it >= 0) tick(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) tick(it) }
    private fun tick(n: Int) {
        done += n
        if (done - lastReport > (1 shl 20) || done == total) { lastReport = done; onProgress(done, total) }
    }
    override fun close() = inner.close()
}
