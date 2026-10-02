package org.user14923929.partforge.core

enum class Risk { NORMAL, SENSITIVE, CRITICAL }

data class Partition(
    val name: String,
    /** Path used for all I/O (the by-name symlink). */
    val devicePath: String,
    /** Real block device node, as it appears in /proc/mounts. */
    val resolvedPath: String,
    val sizeBytes: Long,
    /** True for dynamic (logical) partitions living inside `super`. */
    val logical: Boolean,
    val risk: Risk,
) {
    /** 'a' or 'b' for A/B partitions, null otherwise. */
    val slot: Char? =
        if (name.endsWith("_a")) 'a' else if (name.endsWith("_b")) 'b' else null

    val baseName: String = if (slot != null) name.dropLast(2) else name
}

data class VarEntry(val group: String, val key: String, val value: String)

enum class LogLevel { INFO, WARN, ERROR }

data class LogEntry(val time: Long, val level: LogLevel, val text: String)

enum class EraseMode(val label: String, val description: String) {
    QUICK("Quick", "Zero the first 4 MiB (destroys filesystem / image headers)"),
    DISCARD("Discard", "Issue a block discard for the whole partition (needs blkdiscard)"),
    FULL("Full", "Overwrite the entire partition with zeros (slow on big partitions)"),
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return String.format(java.util.Locale.US, "%.2f %s", v, units[i])
}
