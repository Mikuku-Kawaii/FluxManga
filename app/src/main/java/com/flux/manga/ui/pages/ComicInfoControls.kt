package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException

@Composable
private fun ComicInfoFields(info: ComicInfo, current: ComicInfo? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(Triple("书名", info.title, current?.title), Triple("作者", info.author, current?.author),
            Triple("卷号", info.volume, current?.volume)).forEach { (label, value, old) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value ?: "未提供", style = MaterialTheme.typography.bodyLarge)
                if (!old.isNullOrBlank()) Text(if (old == value) "与已有信息一致" else "保留已有：$old",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DetailDescription(info.summary ?: "未提供", "comic-info-preview:${info.summary}", title = "识别到的简介")
        current?.summary?.takeIf { it.isNotBlank() }?.let { old ->
            if (old == info.summary) Text("与已有简介一致", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else DetailDescription(old, "comic-info-current:$old", title = "保留已有简介")
        }
    }
}

@Composable
internal fun ComicInfoImportDialog(preview: CbzImportPreview, confirm: (Boolean) -> Unit, stop: () -> Unit) {
    val canAdopt = !preview.existing && preview.metadata.info?.hasValues == true
    ReferenceDialog(onDismissRequest = stop, title = { Text("导入元数据预览") },
        confirmButton = { ReferenceDialogConfirm(if (canAdopt) "采用并导入" else "继续导入",
            { confirm(canAdopt) }, Modifier.testTag("comic-info-import-confirm")) },
        dismissButton = { TextButton(onClick = stop) { Text("停止本批") } },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(preview.fileName, style = MaterialTheme.typography.titleMedium)
            Text(preview.metadata.message)
            preview.metadata.info?.let { ComicInfoFields(it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(if (preview.existing) "此漫画已导入。继续将检查应用副本，保留已有资料和进度。"
                else "采用识别出的书名、作者、卷号和简介；缺少书名时使用文件名，原 CBZ 保留。",
                style = MaterialTheme.typography.bodySmall)
            if (canAdopt) TextButton(onClick = { confirm(false) }, modifier = Modifier.testTag("comic-info-import-skip")) { Text("不采用元数据，按文件名导入") }
        } })
}

internal data class ComicInfoSupplementState(val current: ComicInfo = ComicInfo(), val result: ComicInfoResult? = null,
    val busy: Boolean = false, val error: String? = null, val completed: Boolean = false) {
    val canFill get() = result?.info?.let { next ->
        listOf(current.title to next.title, current.author to next.author, current.volume to next.volume,
            current.summary to next.summary).any { (old, value) -> old.isNullOrBlank() && !value.isNullOrBlank() }
    } == true
}

internal class ComicInfoSupplementModel(private val library: CbzLibrary, private val session: WorldSession,
    private val key: MangaKey) : ViewModel() {
    private val identity = session.identity.value
    private val mutable = MutableStateFlow(ComicInfoSupplementState())
    val state = mutable.asStateFlow()
    private fun active() = identity.world == PrototypeWorld.SFW && session.accepts(identity)
    fun load() {
        if (state.value.busy || !active()) return
        mutable.value = state.value.copy(busy = true, error = null, result = null)
        viewModelScope.launch {
            try {
                val detail = library.detail(key) ?: throw IOException("本地书目已不存在")
                val result = library.inspectComicInfo(key, ::active)
                ensureActive()
                if (active()) mutable.value = state.value.copy(current = detail.metadata, result = result, busy = false)
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) { if (active()) mutable.value = state.value.copy(busy = false, error = "读取失败：${e.message.orEmpty()}") }
        }
    }
    fun save() {
        val info = state.value.result?.info ?: return
        if (state.value.busy || !state.value.canFill || !active()) return
        mutable.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                library.supplementComicInfo(key, info, ::active)
                ensureActive()
                if (active()) mutable.value = state.value.copy(busy = false, completed = true)
            } catch (c: CancellationException) { throw c }
            catch (e: Exception) { if (active()) mutable.value = state.value.copy(busy = false, result = null,
                error = "保存结果待核对，请重新读取后再补充：${e.message.orEmpty()}") }
        }
    }
    fun acknowledge() { mutable.value = state.value.copy(completed = false) }
}

@Composable
internal fun ComicInfoSupplementDialog(session: WorldSession, manga: String, dismiss: () -> Unit, saved: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val identity by session.identity.collectAsStateWithLifecycle()
    val model: ComicInfoSupplementModel = viewModel(key = "comic-info:${identity.processSession}:${identity.world}:${identity.generation}:$manga",
        factory = viewModelFactory { initializer {
            ComicInfoSupplementModel(CbzLibrary(WorldDataStore(app)), session, MangaKey(PrototypeWorld.SFW, "cbz", manga))
        } })
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.load() }
    LaunchedEffect(state.completed) { if (state.completed) { model.acknowledge(); saved() } }
    ReferenceDialog(onDismissRequest = { if (!state.busy) dismiss() }, title = { Text("补充 ComicInfo 元数据") },
        confirmButton = { ReferenceDialogConfirm("补充空白信息", model::save, enabled = state.canFill && !state.busy,
            modifier = Modifier.testTag("comic-info-supplement")) },
        dismissButton = { TextButton(onClick = dismiss, enabled = !state.busy) { Text("取消") } },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) RequestLoading()
            state.error?.let { RequestFeedback(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            state.result?.let { result ->
                Text(result.message)
                result.info?.let { ComicInfoFields(it, state.current) }
                if (!state.canFill && result.info != null) Text("没有空白字段需要补充，已有信息保留。")
            }
            Text("仅补充空白资料，保留显示书名、自选封面和阅读进度。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = feedbackAttempt(model::load), enabled = !state.busy) { Text("重新读取") }
        } })
}
