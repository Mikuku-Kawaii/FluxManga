package com.flux.manga.ui.pages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SpaceStorageState(val usage: SpaceUsage? = null, val busy: Boolean = false, val ready: Boolean = false,
    val error: String? = null, val result: String? = null)
internal class SpaceStorageModel(app: Application, private val session: WorldSession) : AndroidViewModel(app) {
    private val identity = session.identity.value
    private fun active() = session.accepts(identity)
    private val store = SpaceStorage(WorldDataStore(app), identity.world, ::active)
    private val mutable = MutableStateFlow(SpaceStorageState())
    val state = mutable.asStateFlow()
    init { refresh() }
    fun refresh() = run(null)
    fun clear(choice: CleanupChoice) { if (state.value.ready && choice.any) run(choice) }
    fun applyCoverPolicy(policy: CoverCachePolicy) { if (state.value.ready) run(null, policy) }
    private fun run(choice: CleanupChoice?, policy: CoverCachePolicy? = null) {
        if (!active() || state.value.busy) return
        mutable.value = state.value.copy(busy = true, ready = false, error = null, result = null)
        viewModelScope.launch {
            try {
                if (policy != null) {
                    val result = try { store.applyCoverPolicy(policy) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { "缓存设置保存结果待核对，请查看重新读取的当前设置；读取失败时请刷新后再操作。" }
                    if (active()) mutable.value = state.value.copy(result = result)
                }
                if (choice != null) {
                    val result = store.clear(choice)
                    session.clearPageCovers()
                    if (active()) mutable.value = state.value.copy(result = result)
                }
                val usage = store.usage()
                if (active()) mutable.value = state.value.copy(usage = usage, ready = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (active()) mutable.value = state.value.copy(error = com.flux.manga.storage.storageMessage(failure))
            }
            finally { if (active()) mutable.value = state.value.copy(busy = false) }
        }
    }
}

@Composable
internal fun SpaceStoragePage(session: WorldSession, back: () -> Unit, downloads: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val model = preloadedPages(session).storage()
    val state by model.state.collectAsStateWithLifecycle()
    val identity by session.identity.collectAsStateWithLifecycle()
    val public = identity.world == PrototypeWorld.SFW
    var covers by rememberSaveable { mutableStateOf(false) }
    var searches by rememberSaveable { mutableStateOf(false) }
    var visits by rememberSaveable { mutableStateOf(false) }
    var privateResources by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var draftPolicyName by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmPolicyName by rememberSaveable { mutableStateOf<String?>(null) }
    var catalogPages by rememberSaveable { mutableStateOf(false) }
    val choice = CleanupChoice(covers && public, searches, visits, privateResources && !public, catalogPages && public)
    val editable = state.ready && !state.busy
    ReferenceRefreshColumn(refreshing = state.busy, refresh = { model.refresh() }, refreshEnabled = true, modifier = Modifier.fillMaxSize().testTag("space-storage"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("存储与清理", "", back) }
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("仅清理所选缓存与记录，保留书目和阅读进度。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.busy) RequestLoading()
            state.error?.let { RequestFeedback(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            state.result?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        } }
        item { SettingsGroup("选择清理项目") {
            if (public) CleanupOption("书库封面缓存", state.usage?.covers?.let { "${it.files} 个文件 · ${android.text.format.Formatter.formatFileSize(app, it.bytes)}" } ?: "尚未读取",
                covers, editable) { covers = it }
            else CleanupOption("里世界资源缓存", state.usage?.privateResources?.let {
                "${it.files} 个文件 · ${android.text.format.Formatter.formatFileSize(app, it.bytes)} · 加密缓存"
            } ?: "尚未读取", privateResources, editable) { privateResources = it }
            if (public) {
                SettingsDivider()
                CleanupOption("页面缓存", state.usage?.catalogPages?.let {
                    "${it.files} 个文件 · ${android.text.format.Formatter.formatFileSize(app, it.bytes)}"
                } ?: "尚未读取", catalogPages, editable) { catalogPages = it }
            }
            SettingsDivider()
            CleanupOption("搜索记录", state.usage?.let { "${it.searches} 条 · 当前空间" } ?: "尚未读取", searches, editable) { searches = it }
            SettingsDivider()
            CleanupOption("最近观看记录", state.usage?.let { "${it.visits} 条 · 保留续读进度" } ?: "尚未读取", visits, editable) { visits = it }
        } }
        if (choice.any) item { ReferenceButton(onClick = { confirm = true }, enabled = editable && choice.any, modifier = Modifier.fillMaxWidth()) { Text("查看清理确认") } }
        if (public) item {
            SettingsPanel {
                val stored = state.usage?.coverPolicy
                val draft = CoverCachePolicy.entries.firstOrNull { it.name == draftPolicyName } ?: stored
                state.usage?.coverPolicyError?.let { RequestFeedback(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                val overLimit = stored?.bytes?.let { (state.usage?.localCoverBytes ?: 0L) > it } == true
                if (overLimit) Text("当前占用仍超过上限，可展开再次整理。")
                SettingsDisclosure("CBZ 本地封面缓存上限", stored?.let {
                    "当前：${it.label}" + if (draft != stored) " · 有未保存的选择" else ""
                } ?: "尚未读取设置") {
                    Text("达到上限后先清理较久未使用的封面；不保存磁盘缓存时，每次从 CBZ 副本生成。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (stored != null) {
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CoverCachePolicy.entries.forEach { policy ->
                                SettingsChoiceRow(policy.label, draft == policy, { draftPolicyName = policy.name }, enabled = editable)
                            }
                        }
                        if (draft != stored) Text("有未保存的选择")
                        ReferenceButton(onClick = { confirmPolicyName = draft?.name },
                            enabled = editable && draft != null && (draft != stored || overLimit)) {
                            Text(if (draft != stored) "保存并应用上限" else "再次整理至上限")
                        }
                    }
                }
            }
        }
        if (public) item { SettingsGroup {
            SettingsRow("下载副本", "查看队列与已下载内容", "storage-downloads", SettingsSymbol.DOWNLOAD,
                open = downloads, enabled = !state.busy)
        } }
        item { SettingsPanel { SettingsDisclosure("清理范围与说明") {
            Text("手动清理仅处理勾选项目；封面缓存上限单独保存。数量是本次读取的快照，执行后重新核对。")
            Text("书目、收藏、分组、阅读进度、账号、密钥和原文件保留。搜索记录与最近观看记录的清理不可撤销。")
            Text("下载副本单独管理，不随缓存清理删除。CBZ 应用副本保留；封面可从副本重建。在线书库缩略封面可重新获取，正文临时图片由原句柄释放。")
            if (!public) Text("只清理当前空间的加密图片缓存，保留书库、进度和密钥。离线副本在下载管理中单独清理。")
        }
        } }
    }
    val confirmedPolicy = CoverCachePolicy.entries.firstOrNull { it.name == confirmPolicyName }
    if (public && confirmedPolicy != null) ReferenceDialog(onDismissRequest = { confirmPolicyName = null },
        title = { Text("应用封面缓存设置") },
        text = { Text(buildString {
            append("将设置为：${confirmedPolicy.label}。")
            append(when (confirmedPolicy) {
                CoverCachePolicy.UNLIMITED -> "不会因此清理已有缓存。"
                CoverCachePolicy.MEMORY_ONLY -> "立即清理本地磁盘封面缓存，之后显示时重新生成。"
                else -> "立即按上限整理已有封面，之后写入缓存时继续执行。被清理的封面可重新生成。"
            })
            append("\n\n仅影响表世界本地封面；下载、CBZ 应用副本、原文件、书库和进度保留。")
        }, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { ReferenceDialogConfirm("确认应用", onClick = { confirmPolicyName = null; model.applyCoverPolicy(confirmedPolicy) }, enabled = editable) },
        dismissButton = { TextButton(onClick = { confirmPolicyName = null }) { Text("取消") } })
    if (confirm) ReferenceDialog(onDismissRequest = { confirm = false }, title = { Text("确认清理当前空间") },
        text = { Text(buildString {
            append(if (public) "表世界：" else "里世界：")
            if (choice.covers) append("\n本地与在线书库封面缓存 ${state.usage?.covers?.files ?: 0} 个文件")
            if (choice.catalogPages) append("\n页面缓存 ${state.usage?.catalogPages?.files ?: 0} 个文件")
            if (choice.searches) append("\n搜索记录 ${state.usage?.searches ?: 0} 条")
            if (choice.visits) append("\n最近观看记录 ${state.usage?.visits ?: 0} 条")
            if (choice.privateResources) append("\n里世界资源缓存 ${state.usage?.privateResources?.files ?: 0} 个文件")
            append("\n\n记录清理不可撤销；书目、阅读进度、分组、下载、凭据和原文件保持。")
        }, modifier = Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { ReferenceDialogConfirm("清理所选项目", onClick = { confirm = false; model.clear(choice) }, enabled = editable && choice.any, destructive = true) },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

@Composable
private fun CleanupOption(title: String, detail: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change)
        .padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(checked, null, enabled = enabled)
        }
    }
}
