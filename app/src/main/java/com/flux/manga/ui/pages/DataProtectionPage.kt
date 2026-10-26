package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.WorldDataStore
import com.flux.manga.data.WORLD_DATABASE_VERSION
import com.flux.manga.session.WorldSession
import com.flux.manga.storage.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.DateFormat
import java.util.Date

internal data class ProtectionUiState(
    val busy: Boolean = false,
    val report: ProtectionReport? = null,
    val error: String? = null,
    val diagnosticBusy: Boolean = false,
    val diagnosticStage: DiagnosticStage? = null,
    val cancelling: Boolean = false,
    val diagnostic: DatabaseDiagnosticReport? = null,
    val diagnosticMessage: String? = null,
    val cleanupRequired: Boolean = false,
)
internal class DataProtectionModel(app: Application, private val session: WorldSession) : AndroidViewModel(app) {
    private val owner = session.identity.value
    private val store = WorldDataStore(app)
    private val mutable = MutableStateFlow(ProtectionUiState())
    val state = mutable.asStateFlow()
    private var diagnosticJob: Job? = null
    private fun active() = session.accepts(owner) && session.state.value.foreground
    init {
        viewModelScope.launch {
            session.state.collect { if (!active()) cancelDiagnostic() }
        }
    }
    fun inspect() {
        if (!active() || state.value.busy || state.value.diagnosticBusy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val remaining = store.hasDiagnosticCopy(owner.world)
                if (active()) mutable.update { it.copy(cleanupRequired = remaining) }
                val report = store.inspectProtection(owner.world)
                if (active()) mutable.update { it.copy(report = report) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (active()) mutable.update { it.copy(error = storageMessage(failure)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun diagnose(migrateCopy: Boolean) {
        if (!active() || state.value.busy || state.value.diagnosticBusy) return
        mutable.update { it.copy(diagnosticBusy = true, diagnostic = null, diagnosticMessage = null,
            cancelling = false, diagnosticStage = DiagnosticStage.PREPARING) }
        diagnosticJob = viewModelScope.launch {
            try {
                val report = store.diagnoseProtection(owner.world, ::active,
                    onStage = { stage -> mutable.update { it.copy(diagnosticStage = stage) } },
                    onCleanup = { remaining -> mutable.update { it.copy(cleanupRequired = remaining) } },
                    migrateCopy = migrateCopy)
                if (active()) mutable.update { it.copy(diagnostic = report) }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(diagnosticMessage = "检查已取消，未产生新的检查结论。") }
                throw cancelled
            } finally {
                mutable.update { it.copy(diagnosticBusy = false, diagnosticStage = null, cancelling = false) }
                diagnosticJob = null
            }
        }
    }

    fun cancelDiagnostic() {
        if (!state.value.diagnosticBusy || state.value.cancelling) return
        mutable.update { it.copy(cancelling = true) }
        diagnosticJob?.cancel()
    }

    fun clearCopy() {
        if (!active() || state.value.busy || state.value.diagnosticBusy) return
        mutable.update { it.copy(diagnosticBusy = true, diagnosticStage = DiagnosticStage.CLEANING,
            diagnosticMessage = null, cancelling = false) }
        diagnosticJob = viewModelScope.launch {
            try {
                val cleared = store.clearDiagnosticCopy(owner.world)
                if (active()) mutable.update { it.copy(cleanupRequired = !cleared,
                    diagnosticMessage = if (cleared) "检查副本已清理；原数据库和密钥保持。" else "检查副本未能全部清理，请稍后重试。") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (active()) mutable.update { it.copy(cleanupRequired = true, diagnosticMessage = storageMessage(failure)) }
            } finally {
                mutable.update { it.copy(diagnosticBusy = false, diagnosticStage = null, cancelling = false) }
                diagnosticJob = null
            }
        }
    }
}

@Composable
internal fun DataProtectionPage(session: WorldSession, back: () -> Unit, backup: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as Application
    val model: DataProtectionModel = viewModel(factory = viewModelFactory { initializer { DataProtectionModel(app, session) } })
    val state by model.state.collectAsStateWithLifecycle()
    val identity by session.identity.collectAsStateWithLifecycle()
    val lifecycle by session.state.collectAsStateWithLifecycle()
    var requestedDiagnostic by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(lifecycle.foreground) {
        if (lifecycle.foreground) model.inspect() else requestedDiagnostic = null
    }
    DisposableEffect(model) { onDispose { model.cancelDiagnostic() } }
    LazyColumn(Modifier.fillMaxSize().testTag("data-protection-page"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("数据保护与恢复", "", back) }
        item { SettingsPanel {
            Text("文件与密钥检查", style = MaterialTheme.typography.titleMedium)
            Text(if (identity.world == PrototypeWorld.SFW) "表世界 · 只读检查" else "里世界样本空间 · 只读检查",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.busy) { RequestLoading(); Text("正在检查") }
            state.report?.let { Text(it.detail, modifier = Modifier.testTag("protection-result").semantics { liveRegion = LiveRegionMode.Polite }) }
            state.error?.let { RequestFeedback(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            OutlinedButton(onClick = model::inspect, enabled = !state.busy && !state.diagnosticBusy, modifier = Modifier.testTag("protection-retry")) { Text("重新检查") }
        } }
        item { SettingsPanel { SettingsDisclosure("数据库结构检查", if (state.diagnosticBusy) "正在检查副本" else if (state.cleanupRequired) "有待清理副本" else "高级工具 · 在临时副本上检查") {
            Text("在临时副本上检查，保留原库与密钥。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.diagnosticBusy) {
                Text(if (state.cancelling) "正在停止检查并清理副本…" else state.diagnosticStage?.label.orEmpty(),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                OutlinedButton(onClick = model::cancelDiagnostic, enabled = !state.cancelling,
                    modifier = Modifier.testTag("protection-diagnostic-cancel")) { Text("取消检查") }
            }
            state.diagnostic?.let { report ->
                Text("检查时间：${DateFormat.getDateTimeInstance().format(Date(report.checkedAt))}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (report.error != null) RequestFeedback(report.error, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                else {
                    Text("副本结构检查通过 · 数据库版本 ${report.version}", modifier = Modifier.testTag("protection-diagnostic-result")
                        .semantics { liveRegion = LiveRegionMode.Polite })
                    if (report.migrationChecked) Text("副本迁移通过：${report.originalVersion} → ${report.version}，原库版本保持。")
                    Text(when {
                        report.version == WORLD_DATABASE_VERSION -> "版本与当前应用一致；仍需验证实际升级、业务数据保留与密钥恢复。"
                        report.version != null && report.version in 1 until WORLD_DATABASE_VERSION -> "这是旧版数据库；本次未运行迁移，升级与数据保留仍待验收。"
                        report.version != null && report.version > WORLD_DATABASE_VERSION -> "数据库来自更新的应用版本；请使用兼容版本，不尝试降级或重建。"
                        else -> "数据库版本无法识别；结构可读不代表可以作为当前书库使用。"
                    })
                }
                SettingsDisclosure("检查详情") {
                    if (report.error == null) {
                        report.encryptedRecords?.let { Text("已验证 $it 条加密记录；没有显示或导出记录内容。") }
                        Text("副本大小：${android.text.format.Formatter.formatFileSize(app, report.snapshotBytes)}")
                    }
                    Text("结论只对应本次副本，不代表后续写入、密钥恢复或真实里世界准入已经通过。")
                }
            }
            state.diagnosticMessage?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (state.cleanupRequired) RequestFeedback("仍有检查副本待清理。可重试下方清理操作，不会删除原数据库。", color = MaterialTheme.colorScheme.error)
            if (!state.diagnosticBusy) {
                ReferenceButton(onClick = { requestedDiagnostic = false }, enabled = !state.busy,
                    modifier = Modifier.testTag("protection-diagnostic-start")) { Text("检查数据库副本") }
                if (state.cleanupRequired) OutlinedButton(onClick = model::clearCopy, enabled = !state.busy,
                    modifier = Modifier.testTag("protection-diagnostic-cleanup")) { Text("清理检查副本") }
            }
            SettingsDisclosure("更多检查与副本管理") {
                TextButton(onClick = { requestedDiagnostic = true }, enabled = !state.busy && !state.diagnosticBusy,
                    modifier = Modifier.testTag("protection-migration-start")) { Text("检查副本迁移") }
                if (!state.cleanupRequired) TextButton(onClick = model::clearCopy, enabled = !state.busy && !state.diagnosticBusy,
                    modifier = Modifier.testTag("protection-diagnostic-cleanup")) { Text("清理检查副本") }
                Text("检查副本的结构、关联与加密记录，也可核对迁移至当前版本是否可行。需要额外存储空间；原库不修复、不迁移。")
                Text("结束或取消后会尝试清理。若应用被强制结束，可能留下副本；可在此清理，下次检查也会先清理。里世界副本保持加密。")
            }
        } } }
        item { SettingsPanel { SettingsDisclosure("异常处理与恢复说明") {
            Text("暂时不可用：解锁手机、检查可用空间后重试。密钥缺失、永久失效或文件校验失败：保留现有安装、数据库与密钥文件，当前没有自动恢复旧密钥的功能。")
            Text("读取、迁移或损坏处理失败会停止打开，不删除原库、不重新生成密钥覆盖旧数据。文件与密钥检查不代替数据库结构检查；两项均不代表迁移与恢复已验收。")
            Text("文件与密钥检查不读取书名、账号或正文，不打开数据库，也不创建或修复文件。")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick = backup, enabled = !state.busy && !state.diagnosticBusy) { Text("加密备份与恢复") }
        SettingsDisclosure("备份与换机", "按空间备份，使用单独保存的恢复码") {
            Text("书库菜单或设置的书库管理中可导出当前空间的书目、书签和进度 JSON；这是明文记录导出，不包含正文与密钥，也不提供导入恢复。")
            Text("加密备份包含书库记录、分组、历史、统计及本地 CBZ 应用副本，支持合并恢复。账号凭据、设备密钥、全局设置和离线下载不迁移。平台自动备份保持关闭；卸载、清除应用数据不能用于排查存储问题。")
            Text("本地漫画原文件不由此页删除。已有阅读进度、分组、下载和密钥保持；缓存与记录清理仍在独立的存储与清理页。")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingsDisclosure("里世界来源", "已接入 JMComic / PicACG / E-Hentai / ExHentai") {
            Text("账号、书库记录、图片缓存与离线正文使用当前空间加密存储；EH / EX 账号与权限分别确认。本页诊断结果只对应所选副本。")
        } } }
    }
    requestedDiagnostic?.let { migration -> ReferenceDialog(
        onDismissRequest = { requestedDiagnostic = null },
        title = { Text(if (migration) "检查副本迁移？" else "检查当前空间数据库？") },
        text = { Text(if (migration)
            "只在临时副本上运行现有迁移并核对结构，不更新原库。需要更多空间；取消时会等待当前迁移步骤停止并清理副本，不会回写原库。离页或后台会请求取消。"
            else "会复制当前数据库及日志，核对结构、关联与加密记录，需要额外存储空间。原库与密钥保持；不修复数据、不导出备份。离开此页或切到后台会取消检查。",
            Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { ReferenceDialogConfirm("开始检查", onClick = { requestedDiagnostic = null; model.diagnose(migration) },
            enabled = !state.busy && !state.diagnosticBusy) },
        dismissButton = { TextButton(onClick = { requestedDiagnostic = null }) { Text("暂不检查") } },
    ) }
}
