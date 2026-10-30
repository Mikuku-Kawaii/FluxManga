package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
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
    var showHelp by remember(identity) { mutableStateOf(false) }
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
    val canAdd = ready && !busy && input.isNotBlank() && !duplicate
    val add = { if (canAdd) save(tags + input.trim(), clearInput = true) }
    var suggestionLimit by remember(identity, input) { mutableIntStateOf(12) }
    val suggestions = blacklistSuggestions(session, input, tags, blacklistPresets, suggestionLimit)
    if (showHelp) ReferenceDialog(onDismissRequest = { showHelp = false },
        title = { Text("标签匹配规则") },
        text = { Text("只屏蔽已知标签命中黑名单的作品；标签暂未读取的正常显示，已有书库与阅读进度保留。\n\n按完整标签匹配，不区分大小写与全半角，不做近义词扩展；可填写命名空间，如 female:标签。联想来自当前空间已读取的图源标签、书库资料和本地词库。") },
        confirmButton = { TextButton({ showHelp = false }) { Text("知道了") } })
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SettingsHeading("标签黑名单", if (identity.world == PrototypeWorld.SFW) "表世界" else "里世界", back) }
        item("input") { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ReferenceSubmitSearchField(input, { input = it }, "输入标签名称", submit = add,
                inputEnabled = ready && !busy, submitEnabled = canAdd, actionLabel = "添加")
            if (ready && suggestions.isNotEmpty()) Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("标签建议", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    suggestions.take(suggestionLimit).forEach { suggestion -> key(suggestion.value) {
                        BlacklistTagButton(suggestion.label, selected = false, removable = false, enabled = !busy) {
                            save(tags + suggestion.value, clearInput = true)
                        }
                    } }
                }
                if (suggestions.size > suggestionLimit) TextButton({ suggestionLimit += 12 }) { Text("更多建议") }
            }
            if (duplicate && input.isNotBlank()) Text("该标签已在黑名单中", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReferenceActionRow(text = { modifier ->
                Text("仅影响当前空间，书库保留", modifier, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }, actions = {
                TextButton({ showHelp = true }) { Text("匹配规则") }
            })
        } }
        error?.let { message -> item("error") { RequestFeedback(message, failure = true)
            if (!ready) TextButton({ reload++ }) { Text("重试") }
        } }
        item("blocked") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (ready) "已屏蔽（${tags.size}）" else "已屏蔽", Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium)
                if (!ready && error == null || busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            if (ready && tags.isEmpty()) Text("尚未添加标签，可从下方预设选择", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag -> key(normalizedBlockedTag(tag)) {
                    BlacklistTagButton(tag, selected = true, removable = true, enabled = !busy) {
                        save(tags.filterNot { it == tag })
                    }
                } }
            }
        } }
        item("presets") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("常用预设", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                blacklistPresets.forEach { preset ->
                    val normalized = normalizedBlockedTag(preset)
                    val selected = tags.any { normalizedBlockedTag(it) == normalized }
                    BlacklistTagButton(preset, selected, removable = false, enabled = ready && !busy) {
                        save(if (selected) tags.filterNot { normalizedBlockedTag(it) == normalized } else tags + preset)
                    }
                }
            }
        } }
    }
}

/** Both groups share the tag page's shape, touch height and typography. */
@Composable
private fun BlacklistTagButton(label: String, selected: Boolean, removable: Boolean, enabled: Boolean, click: () -> Unit) {
    val background = referenceStateColor(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
    val foreground = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    TextButton(click, enabled = enabled,
        modifier = Modifier.heightIn(min = referenceActionHeight()).semantics {
            contentDescription = if (removable) "移除标签 $label" else label
            if (!removable) this.selected = selected
        }, shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = background, contentColor = foreground,
            disabledContainerColor = background, disabledContentColor = foreground.copy(alpha = .38f))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge)
            if (removable || selected) Text(if (removable) "×" else "✓", Modifier.clearAndSetSemantics { },
                style = MaterialTheme.typography.labelLarge)
        }
    }
}
