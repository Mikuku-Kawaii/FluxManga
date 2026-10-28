package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flux.manga.session.WorldSession

@Composable
internal fun PrivateCollectionImportPage(
    session: WorldSession, source: String?, name: String, signedIn: Boolean,
    books: List<ImportBook>, previewBusy: Boolean, message: String?, error: Boolean,
    importer: FavoritesImportModel?, information: BookInformationModel?,
    back: () -> Unit, settings: () -> Unit, open: (String) -> Unit, library: (() -> Unit)?,
    refresh: () -> Unit, stop: () -> Unit, importSelected: (Set<String>) -> Unit,
    extraOptions: @Composable ColumnScope.() -> Unit = {},
    scope: @Composable (Boolean) -> Unit = {}, pagination: @Composable (Boolean) -> Unit = {},
    cover: @Composable (ImportBook) -> Unit,
) {
    var selected by rememberSaveable(source) { mutableStateOf(emptyList<String>()) }
    var query by rememberSaveable(source) { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val state = importer?.state?.collectAsStateWithLifecycle()?.value ?: FavoritesImportState()
    val task = state.task
    LaunchedEffect(books.map { it.key }) { selected = selected.filter { id -> books.any { it.key == id } } }
    CollectionImportPage(session, source, name, "", books, signedIn, previewBusy || state.running,
        selected, { selected = it }, query, { query = it }, back, settings, open,
        primaryLabel = if (task != null && !task.done) "继续导入" else "导入账号全部收藏",
        canImport = !previewBusy && !state.running && state.loaded,
        importBooks = {
            if (task != null && !task.done) importer?.start()
            else if (selected.isNotEmpty()) confirm = true else importer?.start(rescan = task?.done == true)
        }, pause = { if (state.running) importer?.stop() else stop() }, refresh = refresh,
        error = if (state.error) state.message else message.takeIf { error },
        notice = state.message.takeUnless { state.error } ?: message.takeUnless { error },
        summary = task?.let { "${if (it.done) "已完成" else "导入进度"} · 新增 ${it.added} · 已存在 ${it.existing}" },
        information = information, scope = { scope(!previewBusy && !state.running) }, pagination = { pagination(!previewBusy && !state.running) },
        options = {
            extraOptions()
            Text("全部导入覆盖账号收藏，不受当前分类或搜索筛选限制。")
            task?.let { SettingsDisclosure("任务详情") {
                Text("已读取 ${it.pages} 页 · 新增 ${it.added} · 已存在 ${it.existing}")
                TextButton({ importer?.start(rescan = true) }, enabled = !previewBusy && !state.running) { Text("重新扫描") }
            } }
            if (!state.loaded) TextButton({ importer?.load() }, enabled = importer != null && !previewBusy) { Text("读取导入进度") }
            library?.let { TextButton(it) { Text("查看书库") } }
        }, footerExtra = { if (task?.done == true) library?.let { TextButton(it) { Text("查看书库") } } }, cover = cover)
    if (confirm) ReferenceDialog(onDismissRequest = { confirm = false }, title = { Text("导入选中书目") },
        text = { Text("导入 ${selected.size} 部，保留已有收藏和阅读进度。") },
        confirmButton = { ReferenceDialogConfirm("导入", onClick = { confirm = false; importSelected(selected.toSet()) }) },
        dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
}
