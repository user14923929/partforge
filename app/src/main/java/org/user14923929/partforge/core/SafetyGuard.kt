package org.user14923929.partforge.core

enum class Action { READ, WRITE }

data class GuardContext(
    val expert: Boolean,
    val activeSlot: Char?,
    val mounted: Set<String>,
)

data class Verdict(val blocked: String?, val warnings: List<String>)

object SafetyGuard {
    private val CRITICAL = setOf(
        "xbl", "xbl_config", "xbl_ramdump", "abl", "sbl1", "sbl2", "sbl3", "aboot", "tz", "hyp", "rpm",
        "devcfg", "keymaster", "cmnlib", "cmnlib64", "qupfw", "uefisecapp", "imagefv", "shrm", "aop",
        "bootloader", "preloader", "lk", "lk2", "tee1", "tee2", "trustzone", "pmic", "multiimgoem",
        "storsec", "sec1", "secro",
    )
    private val SENSITIVE = setOf(
        "modem", "modemst1", "modemst2", "fsg", "fsc", "persist", "persistent", "efs", "nvdata", "nvram",
        "nvcfg", "protect1", "protect2", "protect_f", "protect_s", "frp", "misc", "metadata", "userdata",
        "bluetooth", "dsp", "apdp", "msadp", "seccfg", "proinfo", "oem", "cdt", "ddr",
    )

    fun classify(baseName: String): Risk {
        val n = baseName.lowercase()
        return when {
            n in CRITICAL -> Risk.CRITICAL
            n in SENSITIVE -> Risk.SENSITIVE
            else -> Risk.NORMAL
        }
    }

    fun evaluate(part: Partition, action: Action, ctx: GuardContext, imageWarning: String? = null): Verdict {
        if (action == Action.READ) return Verdict(null, emptyList())
        val warnings = mutableListOf<String>()
        var blocked: String? = null

        if (part.risk == Risk.CRITICAL) {
            if (!ctx.expert) {
                blocked = "\"${part.name}\" is a critical bootloader/firmware partition. Enable Expert mode in Settings to modify it."
            } else {
                warnings += "CRITICAL partition: a bad write can permanently brick the device."
            }
        }
        if (part.risk == Risk.SENSITIVE) {
            warnings += "Sensitive partition (calibration, identity or user data). It may be impossible to recreate."
        }

        val mounted = part.resolvedPath in ctx.mounted || part.devicePath in ctx.mounted
        if (mounted) {
            if (!ctx.expert && blocked == null) {
                blocked = "\"${part.name}\" is currently mounted. Writing to a mounted partition corrupts it. Enable Expert mode to override."
            } else if (ctx.expert) {
                warnings += "This partition is mounted right now; the running system may break."
            }
        }
        if (part.slot != null && part.slot == ctx.activeSlot) {
            warnings += "This is the ACTIVE slot (${part.slot}). If the write is interrupted the device may not boot."
        }
        if (part.logical) {
            warnings += "Logical (dynamic) partition: the image must not exceed its current size."
        }
        if (imageWarning != null) warnings += imageWarning
        return Verdict(blocked, warnings)
    }
}
