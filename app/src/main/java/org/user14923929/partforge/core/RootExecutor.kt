package org.user14923929.partforge.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

data class ExecResult(val code: Int, val out: String, val err: String) {
    val ok: Boolean get() = code == 0
}

/** A long-running `su -c <command>` process with its stderr drained in the background. */
class RootProcess(command: String) {
    private val process: Process = ProcessBuilder("su", "-c", command).start()
    private val errBuf = StringBuffer()
    private val errThread = thread(isDaemon = true, name = "partforge-stderr") {
        runCatching {
            process.errorStream.bufferedReader().forEachLine { errBuf.append(it).append('\n') }
        }
    }

    val stdin: OutputStream get() = process.outputStream
    val stdout: InputStream get() = process.inputStream
    val stderr: String get() = errBuf.toString().trim()

    fun waitFor(): Int {
        val code = process.waitFor()
        errThread.join(2000)
        return code
    }

    fun destroy() {
        process.destroy()
    }
}

object RootExecutor {
    /** Single-quote a string for safe use inside a shell command. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Run a short command as root and collect its output. */
    suspend fun run(command: String): ExecResult = withContext(Dispatchers.IO) {
        try {
            val p = RootProcess(command)
            p.stdin.close()
            val out = p.stdout.bufferedReader().readText()
            val code = p.waitFor()
            ExecResult(code, out.trim(), p.stderr)
        } catch (e: IOException) {
            ExecResult(-1, "", e.message ?: "su is not available")
        }
    }
}
