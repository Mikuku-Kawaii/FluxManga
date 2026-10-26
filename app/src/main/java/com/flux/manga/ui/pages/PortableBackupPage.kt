package com.flux.manga.ui.pages

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.*
import com.flux.manga.session.SwitchPhase
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.DateFormat
import java.util.Date

internal data class PortableBackupState(val busy: Boolean = false, val cancelling: Boolean = false,
    val picking: Boolean = false, val selected: Boolean = false, val restoring: Boolean = false,
    val backupCode: String = "", val restoreCode: String = "", val summary: BackupSummary? = null,
    val stage: String = "", val message: String? = null)

internal class PortableBackupModel(app: Application, private val session: WorldSession) : AndroidViewModel(app) {
    private val identity = session.identity.value
    val world = identity.world
    private val backup = PortableBackup(WorldDataStore(app), world)
    private val resolver = app.contentResolver
    val state = MutableStateFlow(PortableBackupState())
    private var uri: Uri? = null
    private var staged: File? = null
    private var job: Job? = null
    @Volatile private var closed = false
    fun active() = !closed && session.accepts(identity) && session.state.value.phase == SwitchPhase.Stable
    fun foreground() = active() && session.state.value.foreground
    init {
        viewModelScope.launch { session.state.collect { if (!foreground()) cancel() } }
    }
    fun generate() {
        if (!foreground() || state.value.busy || state.value.picking || state.value.selected) return
        state.update { it.copy(backupCode = newRecoveryCode(), message = null) }
    }
    fun code(value: String) {
        if (!state.value.busy && state.value.summary == null) state.update { it.copy(restoreCode = value.take(100)) }
    }
    fun preparePicker(restore: Boolean): Boolean {
        if (!foreground() || state.value.busy || state.value.picking || state.value.selected || state.value.summary != null ||
            (!restore && state.value.backupCode.isEmpty())) return false
        uri = null
        state.update { it.copy(picking = true, restoring = restore, message = null) }
        return true
    }
    fun receive(value: Uri?) {
        if (!active() || !state.value.picking) return
        uri = value?.takeIf { it.scheme == "content" }
        state.update { it.copy(picking = false, selected = uri != null,
            message = if (uri == null) "未选择文件，数据保持。" else "文件已选定，请确认下方操作。") }
    }
    fun pickerFailed() {
        if (active()) state.update { it.copy(picking = false, message = "无法打开文件选择器，请重试。") }
    }
    fun pickerUnavailable() { if (active()) state.update { it.copy(message = "请先结束尚未返回的文件选择，再重试。") } }
    private fun run(action: suspend () -> String) {
        if (!foreground() || state.value.busy) return
        state.update { it.copy(busy = true, cancelling = false, stage = "正在准备…", message = null) }
        job = viewModelScope.launch {
            try {
                val message = action()
                if (active()) state.update { it.copy(message = message) }
            } catch (_: CancellationException) {
                state.update { it.copy(message = "操作已停止。导出目标可能不完整，请另选新文件；恢复若已提交，可再次合并而不重复覆盖记录。") }
            } catch (failure: Exception) {
                val detail = generateSequence<Throwable>(failure) { it.cause }
                    .firstOrNull { it is IllegalArgumentException || it is IllegalStateException }?.message
                state.update { it.copy(message = detail ?: "操作失败，请核对恢复码、备份完整性、文件权限和可用空间。原有书库保持；导出目标可能不完整。") }
            } finally {
                state.update { it.copy(busy = false, cancelling = false, stage = "") }; job = null
            }
        }
    }
    private fun stage(value: String) { if (!state.value.cancelling) state.update { it.copy(stage = value) } }
    fun export() {
        val selected = uri ?: return
        if (!foreground() || state.value.busy || !state.value.selected || state.value.restoring) return
        uri = null; state.update { it.copy(selected = false) }
        val code = state.value.backupCode
        run {
            withContext(Dispatchers.IO) {
                val stream = resolver.openOutputStream(selected, "wt") ?: error("无法写入所选位置")
                stream.use { backup.write(it, code, ::foreground, ::stage) }
            }
            "加密备份已完成。请将恢复码与备份分开保存；丢失恢复码无法恢复。"
        }
    }
    fun preview() {
        val selected = uri ?: return
        if (!foreground() || state.value.busy || !state.value.selected || !state.value.restoring) return
        val code = try { recoveryCode(state.value.restoreCode) } catch (e: IllegalArgumentException) {
            state.update { it.copy(message = e.message) }; return
        }
        uri = null; state.update { it.copy(selected = false, summary = null) }
        run {
            withContext(Dispatchers.IO) {
                backup.clearStaging(); staged = null
                val file = backup.stagedFile(); staged = file
                val caller = currentCoroutineContext()
                resolver.openInputStream(selected)?.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytes = 0L
                        while (true) {
                            caller.ensureActive(); if (!foreground()) throw CancellationException()
                            val n = input.read(buffer); if (n < 0) break
                            output.write(buffer, 0, n); bytes += n
                            stage("正在读取加密文件：${bytes / (1024 * 1024)} MiB")
                        }
                    }
                } ?: error("无法读取所选备份")
                val summary = backup.preview(file, code, ::foreground, ::stage)
                if (foreground()) state.update { it.copy(summary = summary, restoreCode = code) }
            }
            "备份已读取，确认后合并到此空间。"
        }
    }
    fun restore() {
        val file = staged ?: return
        if (state.value.summary == null) return
        val code = state.value.restoreCode
        run {
            val result = backup.restore(file, code, ::foreground, ::stage)
            withContext(Dispatchers.IO) { check(file.delete() || !file.exists()) { "记录已合并，密文暂存文件清理失败，可点击清理暂存" } }
            staged = null
            state.update { it.copy(summary = null, restoreCode = "") }
            "恢复完成：新增 ${result.inserted} 条记录，保留或合并 ${result.retained} 条已有记录。账号需重新配置 / 登录，离线章节需重新下载。"
        }
    }
    fun discard() {
        if (state.value.busy || state.value.picking) return
        uri = null; state.update { it.copy(selected = false, summary = null) }
        run {
            withContext(Dispatchers.IO) { backup.clearStaging(); staged = null }
            "已清理备份密文暂存；书库、原文件和外部备份保持。"
        }
    }
    fun cancel() {
        if (!state.value.busy || state.value.cancelling) return
        state.update { it.copy(cancelling = true) }; job?.cancel()
    }
    override fun onCleared() { closed = true; job?.cancel(); uri = null; state.value = PortableBackupState() }
}

internal val LocalPortableBackupPicker = staticCompositionLocalOf<(PortableBackupModel, Boolean) -> Unit> { { _, _ -> } }

@Composable
internal fun PortableBackupPage(session: WorldSession, back: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val model: PortableBackupModel = viewModel(factory = viewModelFactory { initializer { PortableBackupModel(app, session) } })
    val state by model.state.collectAsStateWithLifecycle()
    val visibility by session.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val enabled = lifecycle == Lifecycle.State.RESUMED && visibility.foreground && model.active() && !state.busy && !state.picking
    val picker = LocalPortableBackupPicker.current
    var savedCode by remember(state.backupCode) { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val leave = { model.cancel(); back() }
    BackHandler(state.busy, leave)
    DisposableEffect(model, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.cancel() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); model.cancel() }
    }
    LaunchedEffect(visibility.foreground) { if (!visibility.foreground) confirm = false }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("加密备份与恢复", if (model.world == PrototypeWorld.SFW) "仅表世界" else "仅里世界", leave) }
        item { SettingsPanel {
            Text("书库与阅读记录", style = MaterialTheme.typography.titleMedium)
            Text("备份当前空间，恢复时保留已有记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsDisclosure("范围与恢复码") {
                Text("包含书目、书签、进度、分组、历史、统计和本地 CBZ 应用副本；同内容漫画合并。")
                Text("不含账号凭据、图源配置、设备密钥、全局设置、合成样本内部状态、离线下载与缓存。账号需重新配置 / 登录，离线章节需重新下载。")
                Text("恢复码只在本页内存中保留，请单独安全保存。备份文件加密，但文件大小与条目数量可见。")
                Text("只支持同空间恢复；恢复使用目标设备的密钥，不修复已损坏或丢失的原密钥，也不会开放真实里世界来源。")
            }
        } }
        if (state.busy) item {
            Text(if (state.cancelling) "正在停止并结束当前写入…" else state.stage, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = model::cancel, enabled = !state.cancelling) { Text("停止") }
        }
        state.message?.let { message -> item { RequestFeedback(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
        if (state.picking) item { Text("等待系统文件选择返回…") }
        state.summary?.let { summary -> item { SettingsPanel {
            Text("恢复预览", style = MaterialTheme.typography.titleMedium)
            Text("${DateFormat.getDateTimeInstance().format(Date(summary.createdAt))} · ${summary.books} 本 · ${summary.records} 条记录")
            Text("正文 ${android.text.format.Formatter.formatFileSize(app, summary.contentBytes)}")
            Text("同键记录以本机已有内容为准；统计的同一计时检查点取较长时长，不重复相加。")
            ReferenceButton(onClick = { confirm = true }, enabled = enabled) { Text("合并恢复") }
            TextButton(onClick = model::discard, enabled = enabled) { Text("放弃并清理暂存") }
        } } }
        if (state.summary == null) {
            item { SettingsPanel {
                Text("创建备份", style = MaterialTheme.typography.titleMedium)
                if (state.backupCode.isNotEmpty()) {
                    Text("请先保存恢复码")
                    SelectionContainer { Text(state.backupCode.chunked(8).joinToString(" ")) }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(savedCode, onCheckedChange = { savedCode = it }, enabled = enabled)
                        Text("我已在别处安全保存恢复码", Modifier.weight(1f))
                    }
                }
                if (state.selected && !state.restoring) {
                    ReferenceButton(onClick = model::export, enabled = enabled && savedCode) { Text("开始写入加密备份") }
                } else {
                    TextButton(onClick = model::generate, enabled = enabled && !state.selected) { Text(if (state.backupCode.isEmpty()) "生成恢复码" else "重新生成恢复码") }
                    ReferenceButton(onClick = { picker(model, false) }, enabled = enabled && !state.selected && savedCode && state.backupCode.isNotEmpty()) { Text("选择新备份位置") }
                }
            } }
            item { SettingsPanel { SettingsDisclosure("恢复备份", "选择备份文件并输入恢复码") {
                OutlinedTextField(state.restoreCode, model::code, Modifier.fillMaxWidth(), enabled = enabled,
                    label = { Text("恢复码") }, visualTransformation = PasswordVisualTransformation())
                if (state.selected && state.restoring) {
                    ReferenceButton(onClick = model::preview, enabled = enabled && state.restoreCode.isNotBlank()) { Text("读取备份并预览") }
                } else ReferenceButton(onClick = { picker(model, true) }, enabled = enabled && !state.selected) { Text("选择备份文件") }
            } } }
        }
        item {
            TextButton(onClick = model::discard, enabled = enabled) { Text(if (state.selected) "放弃所选文件" else "清理备份暂存") }
            SettingsDisclosure("中断与空间需求") {
                Text("备份读取期间请停留本页。离页、后台或空间切换会停止操作；文件提供方无响应时需要等待。导出失败留下的文件可能不完整，请另选新文件重试。")
                Text("恢复需要容纳密文备份及新增正文的空间；进程结束后不会自动重放。重新选择同一备份可再次合并，暂存密文可在此清理。原 CBZ 文件始终保留。")
            }
        }
    }
    if (confirm) ReferenceDialog(onDismissRequest = { confirm = false }, title = { Text("合并到当前空间？") },
        text = { Text("将加入缺失的记录和正文，同键记录保留本机内容。不会删除书目、原文件或修改原设备密钥。") },
        confirmButton = { ReferenceDialogConfirm("开始恢复", { confirm = false; model.restore() }, enabled = enabled) },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}
