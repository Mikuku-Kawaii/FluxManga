package com.flux.manga.ui.pages

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ReadingSettingsState(val stored: ReadingMode? = null, val draft: ReadingMode? = null,
    val loading: Boolean = true, val saving: Boolean = false, val error: String? = null)

internal class ReadingSettingsModel(app: Application, private val session: WorldSession,
    private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val identity = session.identity.value
    val world = identity.world
    private val data = WorldDataStore(app)
    private val mutable = MutableStateFlow(ReadingSettingsState())
    val state = mutable.asStateFlow()
    init { load() }
    fun load() {
        if (state.value.saving) return
        viewModelScope.launch {
            mutable.value = state.value.copy(loading = true, error = null)
            try {
                val value = data.readingDefault(world)
                if (session.accepts(identity)) mutable.value = ReadingSettingsState(value,
                    decodeReadingMode(saved["reading-draft"]) ?: value, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (session.accepts(identity)) mutable.value = state.value.copy(loading = false, error = "阅读方式读取失败，请重试") }
        }
    }
    fun select(value: ReadingMode) {
        if (!session.accepts(identity) || state.value.loading || state.value.saving || state.value.stored == null) return
        saved["reading-draft"] = value.name
        mutable.value = state.value.copy(draft = value, error = null)
    }
    fun save() {
        val value = state.value.draft ?: return
        if (!session.accepts(identity) || state.value.loading || state.value.saving || state.value.stored == null) return
        mutable.value = state.value.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                data.saveReadingDefault(world, value) { session.accepts(identity) }
                if (session.accepts(identity)) {
                    saved.remove<String>("reading-draft")
                    mutable.value = state.value.copy(stored = value, saving = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (session.accepts(identity)) mutable.value = state.value.copy(saving = false, error = "默认方式未保存，已保留选择，请重试") }
        }
    }
}

@Composable
internal fun ReadingSettingsPage(session: WorldSession, back: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val model = preloadedPages(session).reading()
    val state by model.state.collectAsStateWithLifecycle()
    ReadingSettingsContent(model.world, state, model::select, model::save, model::load, back)
}

@Composable
internal fun ReadingSettingsContent(world: PrototypeWorld, state: ReadingSettingsState,
    select: (ReadingMode) -> Unit, save: () -> Unit, retry: () -> Unit, back: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("reading-settings"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("阅读方式", "", back,
            backModifier = Modifier.testTag("settings-back")) }
        item { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.stored?.let { Text("当前默认：${it.label}", style = MaterialTheme.typography.titleMedium) }
            Text("用于尚未选定阅读方式的作品。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (state.loading) item { CircularProgressIndicator(); Text("正在读取默认方式…") }
        else if (state.stored != null) item {
            SettingsPanel {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingMode.entries.forEach { mode ->
                        SettingsChoiceRow(mode.label, state.draft == mode, { select(mode) },
                            Modifier.testTag("reading-default-${mode.name}"), mode.description, !state.saving)
                    }
                }
            }
        }
        item {
            state.error?.let { RequestFeedback(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (!state.loading && state.stored == null) ReferenceButton(onClick = feedbackAttempt(retry)) { Text("重试读取") }
            if (state.stored != null) {
                if (state.saving || state.draft != state.stored) Text(if (state.saving) "正在保存…" else "有未保存的选择",
                    Modifier.testTag("reading-default-status").semantics { liveRegion = LiveRegionMode.Polite })
                if (state.draft != state.stored || state.saving) ReferenceButton(onClick = save, enabled = !state.loading && !state.saving && state.draft != state.stored,
                    modifier = Modifier.fillMaxWidth().testTag("reading-default-save")) { Text("保存默认方式") }
            }
        }
        item { ReaderOptionsSettings(world) }
        item { SettingsPanel { SettingsDisclosure("默认方式如何生效") {
            Text("用于尚未设置阅读方式的作品，也可在阅读器中单独调整。")
            Text("默认方式需点击保存，阅读操作开关即时生效。")
        } } }
    }
}
