package org.user14923929.partforge.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

typealias ProgressFn = (done: Long, total: Long) -> Unit

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

/** Counts and hashes everything passing through, and throttles progress callbacks. */
private class TrackingStream(
    private val target: OutputStream,
    private val md: MessageDigest,
    private val total: Long,
    private val progress: ProgressFn,
) : OutputStream() {
    var count = 0L
        private set
    private var lastReport = 0L

    override fun write(b: Int) {
        target.write(b)
        md.update(b.toByte())
        count++
        report(false)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        target.write(b, off, len)
        md.update(b, off, len)
        count += len
        report(false)
    }

    override fun flush() = target.flush()

    fun report(force: Boolean) {
        val now = System.nanoTime()
        if (force || now - lastReport > 100_000_000L) {
            lastReport = now
            progress(count, total)
        }
    }
}

object PartitionOps {
    /**
     * Reads [bytes] bytes from the partition as root, optionally into [sink].
     * Returns the SHA-256 of what was read.
     */
    suspend fun read(part: Partition, bytes: Long, sink: OutputStream?, progress: ProgressFn): String =
        withContext(Dispatchers.IO) {
            val proc = RootProcess("head -c $bytes ${RootExecutor.quote(part.devicePath)}")
            proc.stdin.close()
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastReport = 0L
            try {
                val buf = ByteArray(1 shl 20)
                val ins = proc.stdout
                while (true) {
                    ensureActive()
                    val n = ins.read(buf)
                    if (n < 0) break
                    sink?.write(buf, 0, n)
                    md.update(buf, 0, n)
                    done += n
                    val now = System.nanoTime()
                    if (now - lastReport > 100_000_000L) {
                        lastReport = now
                        progress(done, bytes)
                    }
                }
                sink?.flush()
                progress(done, bytes)
                val code = proc.waitFor()
                if (code != 0) throw IOException("Read failed (exit $code): ${proc.stderr}")
                if (done != bytes) throw IOException("Short read: got $done of $bytes bytes")
            } finally {
                proc.destroy()
            }
            hex(md.digest())
        }

    suspend fun dump(part: Partition, sink: OutputStream, progress: ProgressFn): String =
        read(part, part.sizeBytes, sink, progress)

    /** Streams [source] into the partition with dd. Returns the SHA-256 of the written bytes. */
    suspend fun write(part: Partition, source: ByteSource, progress: ProgressFn): String =
        withContext(Dispatchers.IO) {
            val total = source.totalBytes
            if (total > part.sizeBytes) {
                throw IOException("Image (${formatSize(total)}) is larger than the partition (${formatSize(part.sizeBytes)})")
            }
            val proc = RootProcess("dd of=${RootExecutor.quote(part.devicePath)} bs=1048576")
            val md = MessageDigest.getInstance("SHA-256")
            val track = TrackingStream(java.io.BufferedOutputStream(proc.stdin, 1 shl 20), md, total, progress)
            try {
                source.copyTo(track)
                track.flush()
                track.report(true)
            } catch (e: Throwable) {
                proc.destroy()
                throw if (e is CancellationException) e else IOException(
                    "Write interrupted after ${formatSize(track.count)}: ${e.message}. The partition may be partially written!", e
                )
            }
            proc.stdin.close()
            val code = proc.waitFor()
            if (code != 0) throw IOException("dd failed (exit $code): ${proc.stderr}")
            RootExecutor.run("sync")
            hex(md.digest())
        }

    /** Re-reads the partition and compares its hash with [expectedSha256]. */
    suspend fun verify(part: Partition, bytes: Long, expectedSha256: String, progress: ProgressFn): Boolean {
        RootExecutor.run("sync; echo 3 > /proc/sys/vm/drop_caches")
        return read(part, bytes, null, progress) == expectedSha256
    }

    suspend fun erase(part: Partition, mode: EraseMode, progress: ProgressFn) {
        when (mode) {
            EraseMode.QUICK -> write(part, ZeroSource(minOf(part.sizeBytes, 4L shl 20)), progress)
            EraseMode.FULL -> write(part, ZeroSource(part.sizeBytes), progress)
            EraseMode.DISCARD -> {
                val r = RootExecutor.run("blkdiscard ${RootExecutor.quote(part.devicePath)}")
                if (!r.ok) throw IOException("blkdiscard failed (exit ${r.code}): ${r.err}")
                RootExecutor.run("sync")
            }
        }
    }

    suspend fun setActiveSlot(index: Int): ExecResult = RootExecutor.run("bootctl set-active-boot-slot $index")
}
