package org.user14923929.partforge

import android.content.Context

data class Settings(
    val expert: Boolean = false,
    val autoBackup: Boolean = true,
    val verifyAfterFlash: Boolean = true,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("partforge", Context.MODE_PRIVATE)

    fun load() = Settings(
        expert = sp.getBoolean("expert", false),
        autoBackup = sp.getBoolean("auto_backup", true),
        verifyAfterFlash = sp.getBoolean("verify", true),
    )

    fun save(s: Settings) {
        sp.edit()
            .putBoolean("expert", s.expert)
            .putBoolean("auto_backup", s.autoBackup)
            .putBoolean("verify", s.verifyAfterFlash)
            .apply()
    }
}
