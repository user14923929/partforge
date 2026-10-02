package org.user14923929.partforge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.user14923929.partforge.core.EraseMode
import org.user14923929.partforge.core.LogLevel
import org.user14923929.partforge.core.Partition
import org.user14923929.partforge.core.Risk
import org.user14923929.partforge.core.VarReader
import org.user14923929.partforge.core.formatSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TAB_TITLES = listOf("Partitions", "Variables", "Log", "Settings")
private val TAB_ICONS: List<ImageVector> = listOf(
    Icons.AutoMirrored.Filled.List,
    Icons.Default.Info,
    Icons.Default.Menu,
    Icons.Default.Settings,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartForgeApp(vm: AppViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    val view = LocalView.current
    SideEffect { view.keepScreenOn = state.op != null }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.onImagePicked(uri)
    }
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> vm.onDumpTargetPicked(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(TAB_TITLES[tab]) },
                actions = {
                    when (tab) {
                        0 -> IconButton(onClick = { vm.refreshPartitions() }, enabled = !state.scanning) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rescan partitions")
                        }
                        1 -> {
                            IconButton(onClick = { clipboard.setText(AnnotatedString(VarReader.export(state.vars))) }) {
                                Icon(Icons.Default.Share, contentDescription = "Copy variables")
                            }
                            IconButton(onClick = { vm.loadVars() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reload variables")
                            }
                        }
                        2 -> {
                            IconButton(onClick = { clipboard.setText(AnnotatedString(logText(state))) }) {
                                Icon(Icons.Default.Share, contentDescription = "Copy log")
                            }
                            IconButton(onClick = { vm.clearLog() }) {
                                Icon(Icons.Default.Delete, contentDescription = "Clear log")
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                TAB_TITLES.forEachIndexed { i, title ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(TAB_ICONS[i], contentDescription = null) },
                        label = { Text(title) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.op?.let { OperationCard(it, onCancel = { vm.cancelOp() }) }
            val needsRoot = tab == 0 || tab == 1
            when {
                needsRoot && state.root == RootState.CHECKING -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                needsRoot && state.root == RootState.DENIED -> NoRoot(onRetry = { vm.checkRoot() })
                tab == 0 -> PartitionsScreen(state, onSelect = { vm.select(it) })
                tab == 1 -> VariablesScreen(state, onSetSlot = { vm.setActiveSlot(it) })
                tab == 2 -> LogScreen(state)
                else -> SettingsScreen(state, vm)
            }
        }
    }

    state.selected?.let { p ->
        PartitionSheet(
            part = p,
            busy = state.op != null,
            onDismiss = { vm.select(null) },
            onDump = {
                vm.target = p
                vm.select(null)
                createLauncher.launch("${p.name}.img")
            },
            onFlash = {
                vm.target = p
                vm.select(null)
                openLauncher.launch(arrayOf("*/*"))
            },
            onErase = {
                vm.select(null)
                vm.requestErase(p)
            },
        )
    }
    state.pendingFlash?.let { pf ->
        FlashDialog(pf, state.settings.autoBackup, onConfirm = { vm.confirmFlash() }, onDismiss = { vm.cancelFlash() })
    }
    state.pendingErase?.let { pe ->
        EraseDialog(
            pe,
            hasDiscard = "blkdiscard" in state.caps,
            backup = state.settings.autoBackup,
            onConfirm = { vm.confirmErase(it) },
            onDismiss = { vm.cancelErase() },
        )
    }
}

private fun logText(state: UiState): String {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    return state.log.reversed().joinToString("\n") { "${fmt.format(Date(it.time))} ${it.level} ${it.text}" }
}

// ---------- shared pieces ----------

@Composable
private fun NoRoot(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text("Root access is required", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "PartForge talks to block devices directly through su. Grant root to this app in your root manager, then try again.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun OperationCard(op: Operation, onCancel: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(op.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (op.total > 0) {
                val fraction = (op.done.toFloat() / op.total).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatSize(op.done)} / ${formatSize(op.total)}  (${(fraction * 100).toInt()}%)",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Do not close the app, lock the device or pull the battery while this runs.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (op.cancellable) {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

// ---------- partitions ----------

@Composable
private fun PartitionsScreen(state: UiState, onSelect: (Partition) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val list = remember(state.partitions, query) {
        state.partitions.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search partitions") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            "${state.partitions.size} partitions  ·  active slot: ${state.activeSlot ?: "n/a"}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        when {
            state.scanning && state.partitions.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No partitions found") }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.name }) { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(formatSize(p.sizeBytes))
                                    if (p.logical) append("  ·  logical")
                                    if (p.slot != null) append("  ·  slot ${p.slot}")
                                },
                            )
                        },
                        trailingContent = { RiskBadge(p.risk) },
                        modifier = Modifier.selectable(selected = false, role = Role.Button, onClick = { onSelect(p) }),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RiskBadge(risk: Risk) {
    when (risk) {
        Risk.CRITICAL -> Text("CRITICAL", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        Risk.SENSITIVE -> Text("sensitive", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
        Risk.NORMAL -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartitionSheet(
    part: Partition,
    busy: Boolean,
    onDismiss: () -> Unit,
    onDump: () -> Unit,
    onFlash: () -> Unit,
    onErase: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(part.name, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            InfoLine("Size", "${formatSize(part.sizeBytes)} (${part.sizeBytes} bytes)")
            InfoLine("Device", part.devicePath)
            InfoLine("Node", part.resolvedPath)
            InfoLine("Type", if (part.logical) "logical (dynamic) partition" else "physical partition")
            InfoLine("Slot", part.slot?.toString() ?: "none")
            InfoLine("Risk", part.risk.name.lowercase())
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDump, enabled = !busy) { Text("Dump") }
                Button(onClick = onFlash, enabled = !busy) { Text("Flash") }
                OutlinedButton(onClick = onErase, enabled = !busy) { Text("Erase") }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

// ---------- dialogs ----------

@Composable
private fun FlashDialog(pf: PendingFlash, backup: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val blocked = pf.verdict.blocked
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (blocked == null) "Flash ${pf.part.name}?" else "Cannot flash ${pf.part.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "File: ${pf.fileName}\nType: ${pf.kind.label}\n" +
                        "Write size: ${formatSize(pf.writeSize)}\nPartition size: ${formatSize(pf.part.sizeBytes)}",
                )
                if (blocked != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(blocked, color = MaterialTheme.colorScheme.error)
                } else {
                    pf.verdict.warnings.forEach {
                        Spacer(Modifier.height(8.dp))
                        Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (backup) "The current contents will be backed up first (when the partition is small enough)."
                        else "Auto-backup is OFF. The current contents will be lost.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        label = { Text("Type \"${pf.part.name}\" to confirm") },
                    )
                }
            }
        },
        confirmButton = {
            if (blocked == null) {
                TextButton(onClick = onConfirm, enabled = typed.trim() == pf.part.name) { Text("Flash") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (blocked == null) "Cancel" else "Close") } },
    )
}

@Composable
private fun EraseDialog(
    pe: PendingErase,
    hasDiscard: Boolean,
    backup: Boolean,
    onConfirm: (EraseMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(EraseMode.QUICK) }
    val modes = EraseMode.entries.filter { it != EraseMode.DISCARD || hasDiscard }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Erase ${pe.part.name}?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                modes.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = mode == m, role = Role.RadioButton, onClick = { mode = m })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == m, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(m.label)
                            Text(m.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                pe.warnings.forEach {
                    Spacer(Modifier.height(8.dp))
                    Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (backup) "A backup is made first when the partition is small enough."
                    else "Auto-backup is OFF. The data will be lost.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    label = { Text("Type \"${pe.part.name}\" to confirm") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(mode) }, enabled = typed.trim() == pe.part.name) { Text("Erase") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------- variables ----------

@Composable
private fun VariablesScreen(state: UiState, onSetSlot: (Int) -> Unit) {
    var confirmSlot by remember { mutableStateOf<Int?>(null) }
    val grouped = remember(state.vars) { state.vars.groupBy { it.group } }
    LazyColumn(Modifier.fillMaxSize()) {
        if ("bootctl" in state.caps && state.activeSlot != null) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Boot slot", style = MaterialTheme.typography.titleMedium)
                        Text("Current slot: ${state.activeSlot}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { confirmSlot = 0 }, enabled = state.op == null) { Text("Activate A") }
                            OutlinedButton(onClick = { confirmSlot = 1 }, enabled = state.op == null) { Text("Activate B") }
                        }
                    }
                }
            }
        }
        if (state.vars.isEmpty()) {
            item { Text("No variables loaded yet.", modifier = Modifier.padding(16.dp)) }
        }
        grouped.forEach { (group, entries) ->
            item {
                Text(
                    group,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
                )
            }
            items(entries.size) { i ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(entries[i].key, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(entries[i].value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
    confirmSlot?.let { idx ->
        val letter = 'a' + idx
        AlertDialog(
            onDismissRequest = { confirmSlot = null },
            title = { Text("Activate slot $letter?") },
            text = {
                Text(
                    "The bootloader will boot slot $letter on the next restart. " +
                        "Make sure that slot contains a working system, otherwise the device may not boot.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSlot = null
                    onSetSlot(idx)
                }) { Text("Activate") }
            },
            dismissButton = { TextButton(onClick = { confirmSlot = null }) { Text("Cancel") } },
        )
    }
}

// ---------- log ----------

@Composable
private fun LogScreen(state: UiState) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    if (state.log.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("The log is empty") }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(state.log.size) { i ->
            val e = state.log[i]
            val color = when (e.level) {
                LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                LogLevel.ERROR -> MaterialTheme.colorScheme.error
            }
            Text(
                "${fmt.format(Date(e.time))}  ${e.text}",
                color = color,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
}

// ---------- settings ----------

@Composable
private fun SettingsScreen(state: UiState, vm: AppViewModel) {
    var confirmExpert by remember { mutableStateOf(false) }
    var showLibs by remember { mutableStateOf(false) }
    val s = state.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SwitchRow(
            title = "Expert mode",
            subtitle = "Allows writing critical bootloader/firmware partitions and mounted partitions. Can permanently brick the device.",
            checked = s.expert,
            onChange = { if (it) confirmExpert = true else vm.setExpert(false) },
        )
        SwitchRow(
            title = "Auto-backup before writing",
            subtitle = "Dumps the partition to the app's backups folder first (partitions up to 2 GiB).",
            checked = s.autoBackup,
            onChange = { vm.setAutoBackup(it) },
        )
        SwitchRow(
            title = "Verify after flashing",
            subtitle = "Reads the partition back and compares its SHA-256 with what was written.",
            checked = s.verifyAfterFlash,
            onChange = { vm.setVerify(it) },
        )
        Spacer(Modifier.height(16.dp))
        Text("Detected tools", style = MaterialTheme.typography.titleSmall)
        Text(
            if (state.caps.isEmpty()) "none (root not granted)" else state.caps.sorted().joinToString(", "),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(16.dp))
        Text("About", style = MaterialTheme.typography.titleSmall)
        Text("PartForge 1.0.0  ·  GPL-3.0  ·  needs root", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        ListItem(
            headlineContent = { Text("Open-source libraries") },
            supportingContent = { Text("${OPEN_SOURCE_LIBRARIES.size} libraries used by this app") },
            modifier = Modifier.clickable { showLibs = true },
        )
    }
    if (showLibs) OpenSourceDialog(onDismiss = { showLibs = false })
    if (confirmExpert) {
        AlertDialog(
            onDismissRequest = { confirmExpert = false },
            title = { Text("Enable Expert mode?") },
            text = {
                Text(
                    "Expert mode removes the safety blocks. A wrong write to a bootloader or firmware " +
                        "partition can permanently brick your device.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmExpert = false
                    vm.setExpert(true)
                }) { Text("I understand") }
            },
            dismissButton = { TextButton(onClick = { confirmExpert = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun OpenSourceDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-source libraries") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "PartForge itself is licensed under GPL-3.0. It is built with the following open-source libraries:",
                    style = MaterialTheme.typography.bodySmall,
                )
                OPEN_SOURCE_LIBRARIES.forEach { lib ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable { runCatching { uriHandler.openUri(lib.url) } }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(lib.name, style = MaterialTheme.typography.titleSmall)
                        Text(lib.description, style = MaterialTheme.typography.bodySmall)
                        Text(
                            lib.license,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
