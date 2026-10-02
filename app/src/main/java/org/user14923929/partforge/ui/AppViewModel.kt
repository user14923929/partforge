package org.user14923929.partforge.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.user14923929.partforge.Prefs
import org.user14923929.partforge.Settings
import org.user14923929.partforge.core.Action
import org.user14923929.partforge.core.CapabilityDetector
import org.user14923929.partforge.core.EraseMode
import org.user14923929.partforge.core.GuardContext
import org.user14923929.partforge.core.ImageInspector
import org.user14923929.partforge.core.ImageKind
import org.user14923929.partforge.core.LogEntry
import org.user14923929.partforge.core.LogLevel
import org.user14923929.partforge.core.Partition
import org.user14923929.partforge.core.PartitionOps
import org.user14923929.partforge.core.PartitionScanner
import org.user14923929.partforge.core.RawSource
import org.user14923929.partforge.core.SafetyGuard
import org.user14923929.partforge.core.SparseSource
import org.user14923929.partforge.core.VarEntry
import org.user14923929.partforge.core.VarReader
import org.user14923929.partforge.core.Verdict
import org.user14923929.partforge.core.formatSize
import org.user14923929.partforge.core.readUpTo
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RootState { CHECKING, GRANTED, DENIED }

data class Operation(
    val title: String,
    val done: Long = 0,
    val total: Long = 0,
    val cancellable: Boolean = false,
)

data class PendingFlash(
    val part: Partition,
    val uri: Uri,
    val fileName: String,
    val fileSize: Long,
    val kind: ImageKind,
    val writeSize: Long,
    val verdict: Verdict,
)

data class PendingErase(val part: Partition, val warnings: List<String>)

data class UiState(
    val root: RootState = RootState.CHECKING,
    val caps: Set<String> = emptySet(),
    val partitions: List<Partition> = emptyList(),
    val scanning: Boolean = false,
    val activeSlot: Char? = null,
    val vars: List<VarEntry> = emptyList(),
    val log: List<LogEntry> = emptyList(),
    val op: Operation? = null,
    val settings: Settings = Settings(),
    val selected: Partition? = null,
    val pendingFlash: PendingFlash? = null,
    val pendingErase: PendingErase? = null,
    val message: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val _ui = MutableStateFlow(UiState(settings = prefs.load()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Partition chosen in the sheet while a file picker is open. */
    var target: Partition? = null

    private var opJob: Job? = null
    private val resolver get() = getApplication<Application>().contentResolver

    init {
        checkRoot()
    }

    // ---------- helpers ----------

    private fun log(level: LogLevel, text: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, text)
        _ui.update { it.copy(log = (listOf(entry) + it.log).take(500)) }
    }

    private fun toast(text: String) {
        _ui.update { it.copy(message = text) }
    }

    fun consumeMessage() {
        _ui.update { it.copy(message = null) }
    }

    fun select(p: Partition?) {
        _ui.update { it.copy(selected = p) }
    }

    fun clearLog() {
        _ui.update { it.copy(log = emptyList()) }
    }

    private fun progress(done: Long, total: Long) {
        _ui.update { it.copy(op = it.op?.copy(done = done, total = total)) }
    }

    private fun phase(title: String) {
        _ui.update { it.copy(op = it.op?.copy(title = title, done = 0, total = 0)) }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = runCatching {
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PartForge:operation").apply { acquire(6L * 60 * 60 * 1000) }
    }.getOrNull()

    private fun runOp(title: String, cancellable: Boolean = false, block: suspend () -> Unit) {
        if (_ui.value.op != null) {
            toast("Another operation is still running")
            return
        }
        _ui.update { it.copy(op = Operation(title, cancellable = cancellable)) }
        opJob = viewModelScope.launch {
            val wakeLock = acquireWakeLock()
            try {
                block()
            } catch (e: CancellationException) {
                log(LogLevel.WARN, "$title: cancelled")
                throw e
            } catch (e: Throwable) {
                log(LogLevel.ERROR, "$title: ${e.message ?: e.javaClass.simpleName}")
                toast("$title failed - see Log")
            } finally {
                if (wakeLock?.isHeld == true) wakeLock.release()
                _ui.update { it.copy(op = null) }
            }
        }
    }

    fun cancelOp() {
        if (_ui.value.op?.cancellable == true) opJob?.cancel()
    }

    private suspend fun guardContext() = GuardContext(
        expert = _ui.value.settings.expert,
        activeSlot = _ui.value.activeSlot,
        mounted = PartitionScanner.mountedDevices(),
    )

    // ---------- root / scanning ----------

    fun checkRoot() {
        viewModelScope.launch {
            _ui.update { it.copy(root = RootState.CHECKING) }
            val caps = CapabilityDetector.detect()
            if (caps.root) {
                _ui.update { it.copy(root = RootState.GRANTED, caps = caps.tools) }
                log(LogLevel.INFO, "Root granted. Tools: ${caps.tools.sorted().joinToString(", ")}")
                refreshPartitions()
                loadVars()
            } else {
                _ui.update { it.copy(root = RootState.DENIED) }
                log(LogLevel.ERROR, "Root access denied or su not found")
            }
        }
    }

    fun refreshPartitions() {
        viewModelScope.launch {
            _ui.update { it.copy(scanning = true) }
            try {
                val r = PartitionScanner.scan()
                _ui.update { it.copy(partitions = r.partitions, activeSlot = r.activeSlot, scanning = false) }
                log(LogLevel.INFO, "Found ${r.partitions.size} partitions, active slot: ${r.activeSlot ?: "n/a"}")
            } catch (e: Throwable) {
                _ui.update { it.copy(scanning = false) }
                log(LogLevel.ERROR, "Scan failed: ${e.message}")
            }
        }
    }

    fun loadVars() {
        viewModelScope.launch {
            try {
                val v = VarReader.read(_ui.value.caps)
                _ui.update { it.copy(vars = v) }
            } catch (e: Throwable) {
                log(LogLevel.ERROR, "Reading variables failed: ${e.message}")
            }
        }
    }

    fun setActiveSlot(index: Int) {
        viewModelScope.launch {
            val r = PartitionOps.setActiveSlot(index)
            if (r.ok) {
                log(LogLevel.INFO, "Active slot set to ${'a' + index}")
                toast("Active slot set to ${'a' + index}")
            } else {
                log(LogLevel.ERROR, "bootctl failed (exit ${r.code}): ${r.err}")
                toast("Could not change the active slot")
            }
            loadVars()
            refreshPartitions()
        }
    }

    // ---------- settings ----------

    private fun saveSettings(s: Settings) {
        prefs.save(s)
        _ui.update { it.copy(settings = s) }
    }

    fun setExpert(v: Boolean) {
        saveSettings(_ui.value.settings.copy(expert = v))
        log(if (v) LogLevel.WARN else LogLevel.INFO, "Expert mode ${if (v) "enabled" else "disabled"}")
    }

    fun setAutoBackup(v: Boolean) = saveSettings(_ui.value.settings.copy(autoBackup = v))

    fun setVerify(v: Boolean) = saveSettings(_ui.value.settings.copy(verifyAfterFlash = v))

    // ---------- dump ----------

    fun onDumpTargetPicked(uri: Uri?) {
        val p = target ?: return
        target = null
        if (uri == null) return
        runOp("Dumping ${p.name}", cancellable = true) {
            val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open the output file")
            val sha = out.use {
                PartitionOps.dump(p, BufferedOutputStream(it, 1 shl 20)) { d, t -> progress(d, t) }
            }
            log(LogLevel.INFO, "Dumped ${p.name} (${formatSize(p.sizeBytes)}), sha256=$sha")
            toast("Dump of ${p.name} finished")
        }
    }

    // ---------- flash ----------

    private class ImageInfo(val name: String, val size: Long, val head: ByteArray)

    private fun inspect(uri: Uri): ImageInfo {
        var name = "image"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val buf = ByteArray(32)
        val n = resolver.openInputStream(uri)?.use { readUpTo(it, buf) } ?: 0
        return ImageInfo(name, size, buf.copyOf(n))
    }

    fun onImagePicked(uri: Uri?) {
        val p = target ?: return
        target = null
        if (uri == null) return
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { inspect(uri) }
                val kind = ImageInspector.kind(info.head)
                val writeSize = if (kind == ImageKind.SPARSE) SparseSource.expandedSize(info.head) else info.size
                if (writeSize <= 0) {
                    toast("Cannot determine the image size")
                    return@launch
                }
                var verdict = SafetyGuard.evaluate(
                    p, Action.WRITE, guardContext(), ImageInspector.mismatchWarning(p.baseName, kind),
                )
                if (writeSize > p.sizeBytes && verdict.blocked == null) {
                    verdict = verdict.copy(
                        blocked = "The image (${formatSize(writeSize)}) is larger than ${p.name} (${formatSize(p.sizeBytes)}).",
                    )
                }
                _ui.update { it.copy(pendingFlash = PendingFlash(p, uri, info.name, info.size, kind, writeSize, verdict)) }
            } catch (e: Throwable) {
                log(LogLevel.ERROR, "Cannot read the image: ${e.message}")
                toast("Cannot read the image")
            }
        }
    }

    fun cancelFlash() {
        _ui.update { it.copy(pendingFlash = null) }
    }

    fun confirmFlash() {
        val pf = _ui.value.pendingFlash ?: return
        _ui.update { it.copy(pendingFlash = null) }
        if (pf.verdict.blocked != null) return
        runOp("Flashing ${pf.part.name}") { executeFlash(pf) }
    }

    private suspend fun executeFlash(pf: PendingFlash) {
        val p = pf.part
        log(LogLevel.INFO, "Flash ${pf.fileName} -> ${p.name} (${formatSize(pf.writeSize)})")
        backupIfEnabled(p)
        phase("Flashing ${p.name}")
        val raw = resolver.openInputStream(pf.uri) ?: throw IOException("Cannot open the image")
        val input = BufferedInputStream(raw, 1 shl 20)
        val source = if (pf.kind == ImageKind.SPARSE) SparseSource.open(input) else RawSource(input, pf.fileSize)
        val sha = PartitionOps.write(p, source) { d, t -> progress(d, t) }
        log(LogLevel.INFO, "Wrote ${formatSize(source.totalBytes)} (${source.kind}) to ${p.name}, sha256=$sha")

        if (_ui.value.settings.verifyAfterFlash) {
            phase("Verifying ${p.name}")
            val ok = PartitionOps.verify(p, source.totalBytes, sha) { d, t -> progress(d, t) }
            if (!ok) {
                log(LogLevel.ERROR, "VERIFICATION FAILED for ${p.name}: read-back does not match what was written")
                toast("Verification failed for ${p.name}!")
                return
            }
            log(LogLevel.INFO, "Verification OK for ${p.name}")
        }
        toast("Flashed ${p.name}")
    }

    // ---------- erase ----------

    fun requestErase(p: Partition) {
        viewModelScope.launch {
            val v = SafetyGuard.evaluate(p, Action.WRITE, guardContext())
            if (v.blocked != null) {
                log(LogLevel.WARN, v.blocked)
                toast(v.blocked)
                return@launch
            }
            _ui.update { it.copy(pendingErase = PendingErase(p, v.warnings)) }
        }
    }

    fun cancelErase() {
        _ui.update { it.copy(pendingErase = null) }
    }

    fun confirmErase(mode: EraseMode) {
        val pe = _ui.value.pendingErase ?: return
        _ui.update { it.copy(pendingErase = null) }
        val p = pe.part
        runOp("Erasing ${p.name}") {
            log(LogLevel.INFO, "Erase ${p.name}, mode ${mode.label}")
            backupIfEnabled(p)
            phase("Erasing ${p.name} (${mode.label})")
            PartitionOps.erase(p, mode) { d, t -> progress(d, t) }
            log(LogLevel.INFO, "Erased ${p.name} (${mode.label})")
            toast("Erased ${p.name}")
        }
    }

    // ---------- backup ----------

    private suspend fun backupIfEnabled(p: Partition) {
        if (!_ui.value.settings.autoBackup) return
        if (p.sizeBytes > AUTO_BACKUP_LIMIT) {
            log(LogLevel.WARN, "Auto-backup skipped: ${p.name} is larger than ${formatSize(AUTO_BACKUP_LIMIT)}")
            return
        }
        val app = getApplication<Application>()
        val dir = (app.getExternalFilesDir("backups") ?: File(app.filesDir, "backups")).apply { mkdirs() }
        if (StatFs(dir.path).availableBytes < p.sizeBytes + (64L shl 20)) {
            throw IOException(
                "Not enough free space for the pre-write backup of ${p.name}. " +
                    "Free some space or disable auto-backup in Settings.",
            )
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "${p.name}_$stamp.img")
        phase("Backing up ${p.name}")
        try {
            val sha = FileOutputStream(file).use {
                PartitionOps.dump(p, BufferedOutputStream(it, 1 shl 20)) { d, t -> progress(d, t) }
            }
            log(LogLevel.INFO, "Backup saved: ${file.absolutePath} (sha256=$sha)")
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    private companion object {
        const val AUTO_BACKUP_LIMIT = 2L shl 30
    }
}
