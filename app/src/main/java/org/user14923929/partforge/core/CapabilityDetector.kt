package org.user14923929.partforge.core

data class Capabilities(val root: Boolean, val tools: Set<String>)

object CapabilityDetector {
    private val TOOLS = listOf("dd", "head", "sync", "blkdiscard", "bootctl", "blockdev", "readlink", "getprop")

    suspend fun detect(): Capabilities {
        val script = "id -u; for t in ${TOOLS.joinToString(" ")}; do command -v \$t >/dev/null 2>&1 && echo \$t; done"
        val r = RootExecutor.run(script)
        if (!r.ok && r.out.isEmpty()) return Capabilities(false, emptySet())
        val lines = r.out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val root = lines.firstOrNull() == "0"
        return Capabilities(root, lines.drop(1).toSet())
    }
}
