package org.user14923929.partforge.core

object VarReader {
    private val GROUPS: List<Pair<String, List<String>>> = listOf(
        "Device" to listOf(
            "ro.product.manufacturer", "ro.product.model", "ro.product.device", "ro.product.name",
            "ro.build.fingerprint", "ro.build.version.release", "ro.build.version.sdk",
            "ro.build.version.security_patch", "ro.product.cpu.abi", "ro.hardware", "ro.board.platform",
        ),
        "Bootloader" to listOf(
            "ro.bootloader", "ro.boot.bootloader", "ro.boot.hardware", "ro.boot.hwversion",
            "ro.boot.baseband", "gsm.version.baseband", "ro.boot.flash.locked", "ro.boot.secure_boot",
            "ro.boot.warranty_bit", "ro.boot.write_protect",
        ),
        "Verified boot" to listOf(
            "ro.boot.verifiedbootstate", "ro.boot.vbmeta.device_state", "ro.boot.veritymode",
            "ro.boot.vbmeta.avb_version", "ro.boot.avb_version", "ro.boot.vbmeta.hash_alg",
            "ro.boot.vbmeta.size", "ro.boot.vbmeta.digest",
        ),
        "A/B and partitions" to listOf(
            "ro.boot.slot_suffix", "ro.build.ab_update", "ro.boot.dynamic_partitions",
            "ro.boot.dynamic_partitions_retrofit", "ro.virtual_ab.enabled", "ro.virtual_ab.retrofit",
            "ro.boot.first_stage_console", "ro.boot.fstab_suffix",
        ),
    )

    private fun parseGetprop(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val re = Regex("^\\[(.*?)]: \\[(.*)]$")
        text.lines().forEach { line -> re.find(line.trim())?.let { map[it.groupValues[1]] = it.groupValues[2] } }
        return map
    }

    suspend fun read(caps: Set<String>): List<VarEntry> {
        val out = mutableListOf<VarEntry>()
        val props = parseGetprop(RootExecutor.run("getprop").out)

        val shown = HashSet<String>()
        for ((group, keys) in GROUPS) {
            for (k in keys) {
                val v = props[k]
                if (!v.isNullOrEmpty()) {
                    out += VarEntry(group, k, v)
                    shown += k
                }
            }
        }
        props.filterKeys { it.startsWith("ro.boot.") && it !in shown }
            .forEach { (k, v) -> if (v.isNotEmpty()) out += VarEntry("Other ro.boot.*", k, v) }

        val cmdline = RootExecutor.run("cat /proc/cmdline").out.trim()
        if (cmdline.isNotEmpty()) out += VarEntry("Kernel cmdline", "/proc/cmdline", cmdline)

        if ("bootctl" in caps) {
            val n = RootExecutor.run("bootctl get-number-slots").out.trim()
            if (n.isNotEmpty()) out += VarEntry("Boot control HAL", "slot-count", n)
            val cur = RootExecutor.run("bootctl get-current-slot").out.trim()
            if (cur.isNotEmpty()) out += VarEntry("Boot control HAL", "current-slot", cur)
            val count = n.toIntOrNull() ?: 0
            for (i in 0 until count.coerceAtMost(4)) {
                val bootable = RootExecutor.run("bootctl is-slot-bootable $i")
                val ok = RootExecutor.run("bootctl is-slot-marked-successful $i")
                val suffix = RootExecutor.run("bootctl get-suffix $i").out.trim()
                out += VarEntry("Boot control HAL", "slot $i suffix", suffix.ifEmpty { "?" })
                out += VarEntry("Boot control HAL", "slot $i bootable", (bootable.out + bootable.err).trim().ifEmpty { "exit ${bootable.code}" })
                out += VarEntry("Boot control HAL", "slot $i marked successful", (ok.out + ok.err).trim().ifEmpty { "exit ${ok.code}" })
            }
        }
        return out
    }

    /** Plain-text export of all variables. */
    fun export(vars: List<VarEntry>): String =
        vars.groupBy { it.group }.entries.joinToString("\n\n") { (g, list) ->
            "# $g\n" + list.joinToString("\n") { "${it.key}: ${it.value}" }
        }
}
