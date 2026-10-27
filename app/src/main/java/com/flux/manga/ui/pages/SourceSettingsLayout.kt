package com.flux.manga.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class SourceSettingsSection(val label: String) {
    OVERVIEW(""), ACCOUNT("账号"), IMPORT("收藏导入"), CONNECTION("连接设置")
}

/** Keep forms/controllers alive while navigating within one source's settings entry. */
internal class SourceSettingsNavigation(
    private val history: MutableState<List<SourceSettingsSection>>,
    val scroll: LazyListState,
    private val leave: () -> Unit,
) {
    val section get() = history.value.last()
    fun open(section: SourceSettingsSection) {
        val existing = history.value.indexOf(section)
        history.value = if (existing >= 0) history.value.take(existing + 1) else history.value + section
    }
    fun back() { if (history.value.size == 1) leave() else history.value = history.value.dropLast(1) }
    fun title(source: String) = if (section == SourceSettingsSection.OVERVIEW) source else "$source · ${section.label}"
}

@Composable
internal fun rememberSourceSettingsNavigation(back: () -> Unit): SourceSettingsNavigation {
    val history = rememberSaveable { mutableStateOf(listOf(SourceSettingsSection.OVERVIEW)) }
    val overview = rememberLazyListState()
    val account = rememberLazyListState()
    val imports = rememberLazyListState()
    val connection = rememberLazyListState()
    val scroll = when (history.value.last()) {
        SourceSettingsSection.OVERVIEW -> overview
        SourceSettingsSection.ACCOUNT -> account
        SourceSettingsSection.IMPORT -> imports
        SourceSettingsSection.CONNECTION -> connection
    }
    val navigation = SourceSettingsNavigation(history, scroll, back)
    BackHandler(history.value.size > 1) { navigation.back() }
    return navigation
}

internal fun LazyListScope.sourceSettingsOverview(
    navigation: SourceSettingsNavigation, account: String, imports: () -> Unit,
    importSummary: String, connectionSummary: String? = null,
    accountAction: (() -> Unit)? = null,
    importEnabled: Boolean = true,
) {
    if (navigation.section != SourceSettingsSection.OVERVIEW) return
    item("source-overview") { SettingsGroup {
        SettingsRow("账号", account, "source-account", SettingsSymbol.PRIVACY,
            accountAction ?: { navigation.open(SourceSettingsSection.ACCOUNT) })
        SettingsDivider()
        SettingsRow("收藏导入", importSummary, "source-import", SettingsSymbol.GROUPS, imports, enabled = importEnabled)
        if (connectionSummary != null) {
            SettingsDivider()
            SettingsRow("连接设置", connectionSummary, "source-connection", SettingsSymbol.SOURCES,
                { navigation.open(SourceSettingsSection.CONNECTION) })
        }
    } }
}

@Composable
internal fun DownloadStorageSettingsPage(back: () -> Unit, downloads: () -> Unit, storage: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SettingsHeading("下载与存储", "当前空间", back) }
        item { SettingsGroup {
            SettingsRow("下载管理", "队列、暂停与续办", "storage-downloads", SettingsSymbol.DOWNLOAD, downloads)
            SettingsDivider()
            SettingsRow("存储与清理", "空间占用、缓存与记录", "storage-cleanup", SettingsSymbol.STORAGE, storage)
        } }
    }
}

@Composable
internal fun SourceInformationEntry(model: BookInformationModel, enabled: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsGroup {
        SettingsRow("更新已导入作品资料", "封面、标签与作品信息", "source-book-information", null,
            { expanded = true }, enabled = enabled)
    }
    if (expanded) ReferenceSheet(onDismissRequest = { expanded = false },
        title = { Text("作品资料") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { BookInformationPanel(model, enabled) } },
        confirmButton = { TextButton({ expanded = false }) { Text("关闭") } })
}
