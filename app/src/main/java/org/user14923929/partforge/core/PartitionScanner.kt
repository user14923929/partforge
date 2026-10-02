package org.user14923929.partforge.core

data class ScanResult(val partitions: List<Partition>, val activeSlot: Char?)

object PartitionScanner {
    private const val FIND_DIR =
        "for d in /dev/block/by-name /dev/block/bootdevice/by-name " +
            "/dev/block/platform/*/by-name /dev/block/platform/*/*/by-name; do " +
            "[ -d \"\$d\" ] && echo \"\$d\" && break; done"

    private fun listScript(dir: String): String =
        "for f in ${RootExecutor.quote(dir)}/*; do [ -e \"\$f\" ] || continue; " +
            "r=\$(readlink -f \"\$f\"); " +
            "s=\$(blockdev --getsize64 \"\$r\" 2>/dev/null); " +
            "if [ -z \"\$s\" ]; then s=\$(( \$(cat /sys/class/block/\$(basename \"\$r\")/size 2>/dev/null || echo 0) * 512 )); fi; " +
            "echo \"\$(basename \"\$f\")|\$f|\$r|\$s\"; done"

    private suspend fun scanDir(dir: String, logical: Boolean): List<Partition> {
        val r = RootExecutor.run(listScript(dir))
        return r.out.lines().mapNotNull { line ->
            val p = line.split('|')
            if (p.size != 4) return@mapNotNull null
            val size = p[3].trim().toLongOrNull() ?: return@mapNotNull null
            if (size <= 0 || p[0] == "control") return@mapNotNull null
            val base = if (p[0].endsWith("_a") || p[0].endsWith("_b")) p[0].dropLast(2) else p[0]
            Partition(p[0], p[1], p[2], size, logical, SafetyGuard.classify(base))
        }
    }

    suspend fun scan(): ScanResult {
        val physDir = RootExecutor.run(FIND_DIR).out.lines().firstOrNull { it.isNotBlank() }
        val physical = if (physDir != null) scanDir(physDir, false) else emptyList()
        val names = physical.map { it.name }.toSet()
        val logical = scanDir("/dev/block/mapper", true).filter { it.name !in names }
        val all = (physical + logical).sortedBy { it.name }
        val suffix = RootExecutor.run("getprop ro.boot.slot_suffix").out.trim()
        val slot = if (suffix.length == 2 && suffix[0] == '_') suffix[1] else null
        return ScanResult(all, slot)
    }

    /** Device nodes currently listed as mount sources. */
    suspend fun mountedDevices(): Set<String> =
        RootExecutor.run("cat /proc/mounts").out.lines()
            .mapNotNull { it.split(' ').firstOrNull() }
            .filter { it.startsWith("/dev/") }
            .toSet()
}
