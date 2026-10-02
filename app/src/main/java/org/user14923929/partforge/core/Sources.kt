package org.user14923929.partforge.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A stream of bytes with a known final length, written to a partition. */
interface ByteSource {
    val totalBytes: Long
    val kind: String
    fun copyTo(out: OutputStream)
}

private const val CHUNK = 1 shl 20

internal fun readFully(input: InputStream, n: Int): ByteArray {
    val buf = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = input.read(buf, off, n - off)
        if (r < 0) throw EOFException("Unexpected end of image")
        off += r
    }
    return buf
}

/** Reads up to [buf].size bytes; returns the number actually read. */
fun readUpTo(input: InputStream, buf: ByteArray): Int {
    var off = 0
    while (off < buf.size) {
        val r = input.read(buf, off, buf.size - off)
        if (r < 0) break
        off += r
    }
    return off
}

private fun copyExact(input: InputStream, out: OutputStream, count: Long) {
    val buf = ByteArray(CHUNK)
    var left = count
    while (left > 0) {
        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        if (n < 0) throw EOFException("Image ended ${left} bytes early")
        out.write(buf, 0, n)
        left -= n
    }
}

private fun writeZeros(out: OutputStream, count: Long) {
    val buf = ByteArray(CHUNK)
    var left = count
    while (left > 0) {
        val n = minOf(buf.size.toLong(), left).toInt()
        out.write(buf, 0, n)
        left -= n
    }
}

class RawSource(private val input: InputStream, override val totalBytes: Long) : ByteSource {
    override val kind = "raw"
    override fun copyTo(out: OutputStream) {
        input.use { copyExact(it, out, totalBytes) }
    }
}

class ZeroSource(override val totalBytes: Long) : ByteSource {
    override val kind = "zeros"
    override fun copyTo(out: OutputStream) = writeZeros(out, totalBytes)
}

/**
 * Android sparse image (as produced by img2simg) expanded on the fly.
 * "Don't care" chunks are written as zeros.
 */
class SparseSource private constructor(
    private val input: InputStream,
    private val blockSize: Int,
    private val totalBlocks: Long,
    private val chunks: Long,
    private val chunkHeaderSize: Int,
) : ByteSource {
    override val totalBytes: Long get() = totalBlocks * blockSize
    override val kind = "Android sparse (expanded on the fly)"

    override fun copyTo(out: OutputStream) {
        input.use { ins ->
            var written = 0L
            var i = 0L
            while (i < chunks) {
                val hdr = le(readFully(ins, chunkHeaderSize))
                val type = hdr.getShort(0).toInt() and 0xFFFF
                val chunkBlocks = hdr.getInt(4).toLong() and 0xFFFFFFFFL
                val totalSz = hdr.getInt(8).toLong() and 0xFFFFFFFFL
                val outBytes = chunkBlocks * blockSize
                when (type) {
                    CHUNK_RAW -> {
                        if (totalSz - chunkHeaderSize != outBytes) throw IOException("Corrupt raw chunk #$i")
                        copyExact(ins, out, outBytes)
                    }
                    CHUNK_FILL -> {
                        val pattern = readFully(ins, 4)
                        val buf = ByteArray(CHUNK)
                        for (k in buf.indices) buf[k] = pattern[k and 3]
                        var left = outBytes
                        while (left > 0) {
                            val n = minOf(buf.size.toLong(), left).toInt()
                            out.write(buf, 0, n)
                            left -= n
                        }
                    }
                    CHUNK_DONT_CARE -> writeZeros(out, outBytes)
                    CHUNK_CRC -> readFully(ins, 4)
                    else -> throw IOException("Unknown sparse chunk type 0x${type.toString(16)}")
                }
                written += outBytes
                i++
            }
            if (written != totalBytes) throw IOException("Sparse image size mismatch ($written vs $totalBytes)")
        }
    }

    companion object {
        private const val MAGIC = 0xED26FF3AL
        private const val CHUNK_RAW = 0xCAC1
        private const val CHUNK_FILL = 0xCAC2
        private const val CHUNK_DONT_CARE = 0xCAC3
        private const val CHUNK_CRC = 0xCAC4
        const val HEADER_SIZE = 28

        private fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

        fun isSparse(head: ByteArray): Boolean =
            head.size >= 4 && (le(head).getInt(0).toLong() and 0xFFFFFFFFL) == MAGIC

        /** Expanded size declared by a sparse header, or -1 if [head] is not a valid sparse header. */
        fun expandedSize(head: ByteArray): Long {
            if (head.size < HEADER_SIZE || !isSparse(head)) return -1
            val b = le(head)
            val blk = b.getInt(12).toLong() and 0xFFFFFFFFL
            val blocks = b.getInt(16).toLong() and 0xFFFFFFFFL
            return blk * blocks
        }

        fun open(input: InputStream): SparseSource {
            val head = readFully(input, HEADER_SIZE)
            if (!isSparse(head)) throw IOException("Not an Android sparse image")
            val b = le(head)
            val major = b.getShort(4).toInt() and 0xFFFF
            val fileHdr = b.getShort(8).toInt() and 0xFFFF
            val chunkHdr = b.getShort(10).toInt() and 0xFFFF
            val blk = b.getInt(12)
            val blocks = b.getInt(16).toLong() and 0xFFFFFFFFL
            val chunks = b.getInt(20).toLong() and 0xFFFFFFFFL
            if (major != 1) throw IOException("Unsupported sparse major version $major")
            if (fileHdr < HEADER_SIZE || chunkHdr < 12) throw IOException("Bad sparse header sizes")
            if (blk <= 0 || blk % 4 != 0) throw IOException("Bad sparse block size $blk")
            if (fileHdr > HEADER_SIZE) readFully(input, fileHdr - HEADER_SIZE)
            return SparseSource(input, blk, blocks, chunks, chunkHdr)
        }
    }
}

enum class ImageKind(val label: String) {
    ANDROID_BOOT("Android boot image"),
    VENDOR_BOOT("Vendor boot image"),
    VBMETA("AVB vbmeta image"),
    SPARSE("Android sparse image"),
    UNKNOWN("Unrecognized image"),
}

object ImageInspector {
    fun kind(head: ByteArray): ImageKind {
        fun starts(s: String) =
            head.size >= s.length && s.indices.all { head[it] == s[it].code.toByte() }
        return when {
            SparseSource.isSparse(head) -> ImageKind.SPARSE
            starts("ANDROID!") -> ImageKind.ANDROID_BOOT
            starts("VNDRBOOT") -> ImageKind.VENDOR_BOOT
            starts("AVB0") -> ImageKind.VBMETA
            else -> ImageKind.UNKNOWN
        }
    }

    /** Returns a warning if the image type does not fit the partition, otherwise null. */
    fun mismatchWarning(baseName: String, kind: ImageKind): String? {
        val wanted: ImageKind = when {
            baseName == "boot" || baseName == "init_boot" || baseName == "recovery" -> ImageKind.ANDROID_BOOT
            baseName == "vendor_boot" -> ImageKind.VENDOR_BOOT
            baseName.startsWith("vbmeta") -> ImageKind.VBMETA
            else -> return null
        }
        if (kind == wanted || kind == ImageKind.SPARSE) return null
        return "Image type mismatch: \"$baseName\" expects ${wanted.label}, but this file looks like ${kind.label}."
    }
}
