package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LicenseLoad<T>(val value: T? = null, val failed: Boolean = false)

@Composable
private fun licenseReader(): LicenseCatalogReader {
    val assets = LocalContext.current.applicationContext.assets
    return remember(assets) { LicenseCatalogReader(assets::open) }
}

@Composable
private fun <T> licenseLoad(key: Any?, retry: Int, read: () -> T): LicenseLoad<T> {
    val currentRead by rememberUpdatedState(read)
    val state by produceState(LicenseLoad<T>(), key, retry) {
        value = LicenseLoad()
        value = try { LicenseLoad(withContext(Dispatchers.IO) { currentRead() }) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { LicenseLoad(failed = true) }
    }
    return state
}

@Composable
private fun LicenseLoading(failed: Boolean, retry: () -> Unit) {
    SettingsPanel {
        if (failed) {
            RequestFeedback("本地许可材料读取失败，请重试。", color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(feedbackAttempt(retry)) { Text("重试") }
        } else {
            Text("正在读取本地材料")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OpenSourceLicensesPage(back: () -> Unit, open: (String) -> Unit) {
    val reader = licenseReader()
    var retry by remember { mutableIntStateOf(0) }
    val loaded = licenseLoad(reader, retry, reader::catalog)
    var query by rememberSaveable { mutableStateOf("") }
    var groupKey by rememberSaveable { mutableStateOf<String?>(null) }
    val group = LicenseGroup.entries.firstOrNull { it.key == groupKey }
    val matches = remember(loaded.value, query, group) { loaded.value?.filter(query, group).orEmpty() }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("license-catalog"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("heading") { SettingsHeading("开源许可", "", back) }
        item("search") { ReferenceSearchField(query, { query = it }, "搜索组件或许可", Modifier.testTag("license-query"),
            trailingIcon = if (query.isNotEmpty()) ({ TextButton({ query = "" }) { Text("清空") } }) else null) }
        item("groups") { SettingsPanel { SettingsDisclosure("分类", group?.label ?: "全部组件") {
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(group == null, { groupKey = null }, { Text("全部") })
                LicenseGroup.entries.forEach { choice ->
                    FilterChip(group == choice, { groupKey = choice.key }, { Text(choice.label) })
                }
            }
        } } }
        item("scope") { SettingsPanel { SettingsDisclosure("目录范围与来源", "随应用提供的离线许可材料") {
            Text("包含项目、协议适配与依赖材料。Android 清单含元数据组件；Rust 清单含宿主、可选及其他平台依赖，不等于最终 APK 链接组成。正式发布源码取得方式仍待发布渠道落实。",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            loaded.value?.let { Text("快照：${it.snapshot}", style = MaterialTheme.typography.bodySmall) }
        } } }
        if (loaded.value == null) item("load") { LicenseLoading(loaded.failed) { retry++ } }
        else {
            item("count") { Text("${matches.size} 项", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (matches.isEmpty()) item("empty") {
                Text("没有匹配的组件，可修改搜索或分类。")
                if (query.isNotEmpty() || group != null) TextButton(onClick = { query = ""; groupKey = null }) { Text("清除筛选") }
            }
            items(matches, key = { it.id }, contentType = { "license" }) { entry -> Column {
                SettingsRow(entry.name, "${entry.group.label} · ${entry.license}", "license-entry-${entry.id}", null, { open(entry.id) })
                SettingsDivider()
            } }
        }
    }
}

@Composable
internal fun LicenseEntryPage(id: String, back: () -> Unit, openDocument: (String) -> Unit) {
    val reader = licenseReader()
    var retry by remember { mutableIntStateOf(0) }
    val loaded = licenseLoad(id, retry) { reader.catalog().entries.single { it.id == id } }
    val uri = LocalUriHandler.current
    var linkFailed by rememberSaveable(id) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("license-entry"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("heading") { SettingsHeading("组件许可", "", back) }
        val entry = loaded.value
        if (entry == null) item("load") { LicenseLoading(loaded.failed) { retry++ } }
        else {
            item("identity") { SettingsPanel {
                SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleLarge)
                    Text(entry.license)
                    Text(entry.group.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                if (entry.project.startsWith("https://") || entry.project.startsWith("http://")) {
                    TextButton({
                        try { uri.openUri(entry.project); linkFailed = false }
                        catch (_: IllegalArgumentException) { linkFailed = true }
                        catch (_: android.content.ActivityNotFoundException) { linkFailed = true }
                    }) { Text("打开项目网站（浏览器）") }
                }
                if (linkFailed) RequestFeedback("无法打开链接，请检查浏览器是否可用。", color = MaterialTheme.colorScheme.error)
            } }
            item("texts-heading") { Text("许可与声明原文", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }) }
            items(entry.documents, key = { it.name }) { doc -> SettingsGroup {
                SettingsRow(doc.name.substringAfterLast('/'), doc.name, "license-document-${doc.sha256}", null,
                    { openDocument(doc.sha256) })
            } }
        }
    }
}

@Composable
internal fun LicenseDocumentPage(entryId: String, hash: String, back: () -> Unit) {
    val reader = licenseReader()
    var retry by remember { mutableIntStateOf(0) }
    val loaded = licenseLoad(entryId to hash, retry) { reader.text(entryId, hash) }
    val paragraphs = remember(loaded.value) { loaded.value?.replace("\r\n", "\n")?.split("\n\n").orEmpty() }
    LazyColumn(Modifier.fillMaxSize().testTag("license-document"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("heading") { SettingsHeading("许可原文", "保留原始语言与版权声明", back) }
        if (loaded.value == null) item("load") { LicenseLoading(loaded.failed) { retry++ } }
        else items(paragraphs.size, key = { it }, contentType = { "paragraph" }) { index ->
            SelectionContainer { Text(paragraphs[index], style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
