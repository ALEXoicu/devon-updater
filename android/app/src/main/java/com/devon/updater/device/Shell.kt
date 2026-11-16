package com.devon.updater.device

data class ShellResult(val code: Int, val out: String, val err: String) {
    val ok get() = code == 0
}

class ShellException(cmd: String, r: ShellResult) :
    Exception("`$cmd` failed (${r.code}): ${r.err.ifBlank { r.out }.trim().take(400)}")

interface Shell {
    fun run(cmd: String): ShellResult
    /** Long-running command with streamed stdin/stdout. */
    fun spawn(cmd: String): Process
}

fun Shell.check(cmd: String): String {
    val r = run(cmd)
    if (!r.ok) throw ShellException(cmd, r)
    return r.out.trim()
}

fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

/** Runs commands as root through KernelSU's `su`. */
class SuShell : Shell {
    override fun run(cmd: String): ShellResult {
        val p = try {
            ProcessBuilder("su", "-c", cmd).start()
        } catch (e: java.io.IOException) {
            return ShellResult(127, "", "su not available: ${e.message}")
        }
        p.outputStream.close()
        val err = StringBuilder()
        val t = Thread { err.append(p.errorStream.bufferedReader().readText()) }.apply { start() }
        val out = p.inputStream.bufferedReader().readText()
        t.join()
        return ShellResult(p.waitFor(), out, err.toString())
    }

    override fun spawn(cmd: String): Process = ProcessBuilder("su", "-c", cmd).start()
}

fun hasRoot(shell: Shell): Boolean = shell.run("id -u").let { it.ok && it.out.trim() == "0" }
