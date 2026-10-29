package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.flux.manga.data.TagBlacklist
import com.flux.manga.data.normalizedBlockedTag
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val blacklistPresets = listOf("AI", "AI生成", "AI绘图", "NTR", "猎奇", "血腥", "恐怖")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagBlacklistPage(session: WorldSession, back: () -> Unit) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val identity = session.identity.value
    val store = remember(identity) { TagBlacklist(app, session) }
    val scope = rememberCoroutineScope()
    var tags by remember(identity) { mutableStateOf<List<String>>(emptyList()) }
    var input by remember(identity) { mutableStateOf("") }
    var ready by remember(identity) { mutableStateOf(false) }
    var busy by remember(identity) { mutableStateOf(false) }
    var error by remember(identity) { mutableStateOf<String?>(null) }
    var reload by remember(identity) { mutableIntStateOf(0) }
    fun active() = session.accepts(identity) && session.state.value.foreground
    LaunchedEffect(store, reload) {
        error = null
        try { tags = store.read(); ready = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "黑名单读取失败，请重试" }
    }
    fun save(values: List<String>, clearInput: Boolean = false) {
        if (!ready || busy || !active()) return
        busy = true; error = null
        scope.launch {
            try {
                val saved = store.save(values)
                if (active()) {
                    tags = saved
                    if (clearInput) input = ""
                    preloadedPages(app, session).sourceSelectionChanged()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (active()) error = "黑名单保存失败，请重试" }
            finally { busy = false }
        }
    }
    val duplicate = tags.any { normalizedBlockedTag(it) == normalizedBlockedTag(input) }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("标签黑名单", if (identity.world == PrototypeWorld.SFW) "表世界" else "里世界", back) }
        item { Text("屏蔽含这些标签的推荐、最近更新和搜索结果；已有书库与阅读进度保留。",
            style = MaterialTheme.typography.bodyMedium) }
        item { SettingsPanel {
            OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), enabled = ready && !busy,
                singleLine = true, label = { Text("标签名称") })
            ReferenceButton({ save(tags + input.trim(), clearInput = true) }, enabled = ready && !busy && input.isNotBlank() && !duplicate) {
                Text(if (duplicate) "已在黑名单" else "添加标签")
            }
            Text("按完整标签匹配，不区分大小写；可填写命名空间，如 female:标签。", style = MaterialTheme.typography.bodySmall)
        } }
        error?.let { message -> item { RequestFeedback(message, failure = true)
            if (!ready) TextButton({ reload++ }) { Text("重试") }
        } }
        if (!ready && error == null || busy) item { RequestLoading() }
        if (ready && tags.isEmpty()) item { Text("尚未添加标签") }
        items(tags, key = { normalizedBlockedTag(it) }) { tag -> SettingsPanel {
            ReferenceActionRow(text = { Text(tag, it, style = MaterialTheme.typography.bodyLarge) }, actions = {
                TextButton({ save(tags.filterNot { it == tag }) }, enabled = !busy) { Text("移除") }
            })
        } }
        item("presets") { SettingsPanel {
            Text("常用预设", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                blacklistPresets.forEach { preset ->
                    val normalized = normalizedBlockedTag(preset)
                    val selected = tags.any { normalizedBlockedTag(it) == normalized }
                    FilterChip(selected = selected, onClick = {
                        save(if (selected) tags.filterNot { normalizedBlockedTag(it) == normalized } else tags + preset)
                    }, enabled = ready && !busy, label = { Text(preset) })
                }
            }
        } }
    }
}
