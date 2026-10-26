package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flux.manga.update.*

@Composable
internal fun AppUpdatePage(model: AppUpdateModel, back: () -> Unit, install: () -> Unit, permission: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val context = LocalContext.current
    val allowed = remember(lifecycle) { context.packageManager.canRequestPackageInstalls() }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val primaryMessage = state.message?.takeIf {
        state.phase in setOf(UpdatePhase.ERROR, UpdatePhase.INCOMPATIBLE, UpdatePhase.PAUSED, UpdatePhase.READY, UpdatePhase.INSTALLING) ||
            it == "正在暂停并保存下载字节…"
    }
    LazyColumn(Modifier.fillMaxSize().testTag("app-update-page"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("应用更新", "", back) }
        item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.Start,
              verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.primary) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { ReferenceIcon(ReferenceSymbol.UPDATE, Modifier.size(24.dp)) }
            }
            Text("当前版本 ${model.currentVersion}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when(state.phase) {
                UpdatePhase.UNCONFIGURED -> "暂不支持在线更新"
                UpdatePhase.IDLE -> "等待检查"
                UpdatePhase.CHECKING -> "正在检查"
                UpdatePhase.CURRENT -> "已是最新版"
                UpdatePhase.INCOMPATIBLE -> "暂无兼容更新"
                UpdatePhase.AVAILABLE -> "有可用更新"
                UpdatePhase.DOWNLOADING -> "正在下载更新"
                UpdatePhase.PAUSED -> "下载已暂停"
                UpdatePhase.VERIFYING -> "正在校验"
                UpdatePhase.APPLYING -> "正在合成更新包"
                UpdatePhase.READY -> "等待安装"
                UpdatePhase.INSTALLING -> "等待系统安装结果"
                UpdatePhase.ERROR -> "更新未完成"
            }, style = MaterialTheme.typography.headlineSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
          }
            state.installationMessage?.let { RequestFeedback(it) }
            primaryMessage?.let { RequestFeedback(it, color = if(state.phase == UpdatePhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, failure = state.phase == UpdatePhase.ERROR) }
            if (state.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.VERIFYING)) RequestLoading()
            if (model.configured && (state.artifact == null || state.phase == UpdatePhase.ERROR)) ReferenceButton(onClick = feedbackAttempt(model::check), enabled = model.configured && !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("update-check")) { Text(if (state.phase == UpdatePhase.ERROR) "重新检查" else "检查更新") }
            state.artifact?.let { a ->
                Text("${a.name} (${a.version})")
                Text("下载大小 ${sizeLabel(state.patch?.payload?.size ?: a.size)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.phase == UpdatePhase.APPLYING) {
                    val fraction = transferFraction(state.assembledBytes, a.size)
                    Text(if (fraction != null) "正在合成更新包 · ${(fraction * 100).toInt()}%" else "正在合成更新包…",
                        style = MaterialTheme.typography.bodySmall)
                } else if (state.bytes > 0 || state.phase == UpdatePhase.DOWNLOADING) {
                    TransferProgress(state.bytes, state.transferSize, state.phase == UpdatePhase.DOWNLOADING,
                        "已接收 ${sizeLabel(state.bytes)} / ${sizeLabel(state.transferSize)}")
                }
                if (state.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.APPLYING, UpdatePhase.VERIFYING)) {
                    OutlinedButton(onClick = model::pause) { Text("暂停更新") }
                }
                if (!state.busy && state.phase != UpdatePhase.READY) {
                    ReferenceButton(onClick = model::download, modifier = Modifier.fillMaxWidth().testTag("update-download")) { Text(when {
                        state.phase == UpdatePhase.ERROR -> "重试下载"
                        state.bytes > 0 -> "继续下载"
                        else -> "下载更新"
                    }) }
                    Text("使用当前网络，可能产生流量费用。", style = MaterialTheme.typography.bodySmall)
                }
                if (state.phase == UpdatePhase.READY) {
                    if (!allowed) {
                        Text("请允许此来源安装，返回后点击立即安装。")
                        OutlinedButton(onClick = permission) { Text("允许此来源安装") }
                    }
                    ReferenceButton(onClick = install, enabled = allowed, modifier = Modifier.fillMaxWidth().testTag("update-install")) { Text("立即安装") }
                    OutlinedButton(onClick = back) { Text("稍后") }
                }
            }
        } }
        state.artifact?.takeIf { it.notes.isNotBlank() }?.let { artifact -> item("release-notes") {
            DetailDescription(artifact.notes, "update:${artifact.version}", title = "更新说明")
        } }
        item("details") { TransferDisclosure("更新设置与详情") {
            TransferPanel {
                SettingsSwitchRow("自动下载更新", "仅前台、非计费网络", state.automatic, model::automatic,
                    Modifier.testTag("update-automatic"), model.configured)
                Text("渠道：${model.channel}")
                if (primaryMessage == null) state.message?.let { Text(it) }
                Text("冷启动时自动检查，不打断阅读。自动下载离开前台或网络变化时暂停；安装由系统确认。")
                state.artifact?.let { a ->
                    Text("${a.abis.joinToString(" / ")} · Android API ${a.minSdk}+")
                    Text("完整包 · ${sizeLabel(a.size)}")
                    state.patch?.let { patch ->
                        Text("增量包 · ${sizeLabel(patch.payload.size)}，少下载 ${sizeLabel(a.size - patch.payload.size)}")
                        Text("增量包合成失败会改为下载完整包。", style = MaterialTheme.typography.bodySmall)
                        if (!state.busy && state.phase != UpdatePhase.READY)
                            TextButton(onClick = model::downloadFull) { Text("改用完整包") }
                    }
                    if (state.phase == UpdatePhase.APPLYING)
                        Text("已合成 ${sizeLabel(state.assembledBytes)} / ${sizeLabel(a.size)}")
                    if (state.phase != UpdatePhase.ERROR) TextButton(onClick = model::check, enabled = model.configured && !state.busy,
                        modifier = Modifier.testTag("update-check")) { Text("重新检查更新") }
                    if (!state.busy && (state.bytes > 0 || state.phase == UpdatePhase.READY))
                        TextButton(onClick = { confirmClear = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("取消并清理此更新包") }
                }
                Text("清理仅影响更新包，保留书库、章节下载、阅读位置和原文件。安装失败仍可使用当前版本。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
    }
    if (confirmClear) ReferenceDialog(onDismissRequest = { confirmClear = false }, title = { Text("清理此更新包？") },
        text = { Text("删除此版本的完整包、增量包和合成临时文件；已安装应用、漫画和阅读位置不变。") },
        confirmButton = { ReferenceDialogConfirm("清理更新包", onClick = { confirmClear = false; model.clearDownload() }, destructive = true) },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("保留") } })
}
private fun sizeLabel(bytes: Long) = String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576.0)
