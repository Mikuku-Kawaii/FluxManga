package com.flux.manga.ui.pages

import com.flux.manga.R

import com.flux.manga.ui.LucideIcon

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
import com.flux.manga.data.TagBlacklistRules
import com.flux.manga.data.TitleBlacklistRules
import com.flux.manga.data.normalizedBlockedTag
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val blacklistPresets = listOf("AI", "AI生成", "AI绘图", "NTR", "猎奇", "血腥", "恐怖")
private val titlePresets = blacklistPresets + listOf("合集", "总集篇", "総集編", "汉化版", "生肉", "无修正", "修正版")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagBlacklistPage(session: WorldSession, back: () -> Unit) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val identity = session.identity.value
    val store = remember(identity) { TagBlacklist(app, session) }
    val scope = rememberCoroutineScope()
    var tagRules by remember(identity) { mutableStateOf(TagBlacklistRules()) }
    val tags = tagRules.tags
    var input by remember(identity) { mutableStateOf("") }
    var titleRules by remember(identity) { mutableStateOf(TitleBlacklistRules()) }
    var titleInput by remember(identity) { mutableStateOf("") }
    var ready by remember(identity) { mutableStateOf(false) }
    var busy by remember(identity) { mutableStateOf(false) }
    var error by remember(identity) { mutableStateOf<String?>(null) }
    var reload by remember(identity) { mutableIntStateOf(0) }
    var showHelp by remember(identity) { mutableStateOf(false) }
    fun active() = session.accepts(identity) && session.state.value.foreground
    LaunchedEffect(store, reload) {
        error = null
        try {
            val savedTags = store.read()
            val savedTitles = store.readTitles()
            if (active()) { tagRules = savedTags; titleRules = savedTitles; ready = true }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "黑名单读取失败，请重试" }
    }
    fun save(values: List<String>, clearInput: Boolean = false, enabled: Boolean = tagRules.enabled) {
        if (!ready || busy || !active()) return
        busy = true; error = null
        scope.launch {
            try {
                val saved = store.save(TagBlacklistRules(enabled, values))
                if (active()) {
                    tagRules = saved
                    if (clearInput) input = ""
                    preloadedPages(app, session).sourceSelectionChanged()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (active()) error = "黑名单保存失败，请重试" }
            finally { busy = false }
        }
    }
    fun saveTitles(values: TitleBlacklistRules, clearInput: Boolean = false) {
        if (!ready || busy || !active()) return
        busy = true; error = null
        scope.launch {
            try {
                val saved = store.saveTitles(values)
                if (active()) {
                    titleRules = saved
                    if (clearInput) titleInput = ""
                    preloadedPages(app, session).sourceSelectionChanged()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (active()) error = "标题屏蔽设置保存失败，请重试" }
            finally { busy = false }
        }
    }
    val duplicate = tags.any { normalizedBlockedTag(it) == normalizedBlockedTag(input) }
    val canAdd = ready && !busy && input.isNotBlank() && !duplicate
    val add = { if (canAdd) save(tags + input.trim(), clearInput = true) }
    val duplicateTitle = titleRules.keywords.any { normalizedBlockedTag(it) == normalizedBlockedTag(titleInput) }
    val canAddTitle = ready && !busy && titleInput.isNotBlank() && !duplicateTitle
    val addTitle = { if (canAddTitle) saveTitles(titleRules.copy(keywords = titleRules.keywords + titleInput.trim()), clearInput = true) }
    var suggestionLimit by remember(identity, input) { mutableIntStateOf(12) }
    val suggestions = blacklistSuggestions(session, input, tags, blacklistPresets, suggestionLimit)
    var titleLimit by remember(identity, titleInput) { mutableIntStateOf(12) }
    val titleSuggestions = titleKeywordSuggestions(session, titleInput, titleRules.keywords, titlePresets, titleLimit)
    if (showHelp) ReferenceDialog(onDismissRequest = { showHelp = false },
        title = { Text("匹配规则") },
        text = { Text("标签按完整标签匹配；标签暂未读取的正常显示。可填写命名空间，如 female:标签。联想来自当前空间已读取的图源标签、书库资料和本地词库。\n\n开启标题关键词屏蔽后，标题包含任一关键词就隐藏，即使标签尚未读取也会生效。两种规则均不区分大小写与全半角，不做近义词或正则匹配。关闭标题开关不会删除关键词，也不影响标签规则。\n\n仅影响当前空间的推荐、更新、搜索与标签结果，已有书库与阅读进度保留。") },
        confirmButton = { TextButton({ showHelp = false }) { Text("知道了") } })
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SettingsHeading("黑名单", if (identity.world == PrototypeWorld.SFW) "表世界" else "里世界", back) }
        item("input") { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsSwitchRow("标签屏蔽", "隐藏命中标签的作品，关闭后保留已添加的标签", tagRules.enabled,
                { save(tags, enabled = it) }, enabled = ready && !busy)
            ReferenceSubmitSearchField(input, { input = it }, "输入标签名称", submit = add,
                inputEnabled = ready && !busy, submitEnabled = canAdd, actionLabel = "添加")
            if (ready) BlacklistSuggestionsRow("标签建议", suggestions, suggestionLimit, !busy,
                more = { suggestionLimit += 12 }, add = { save(tags + it, clearInput = true) })
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
                Text(if (ready) "已添加标签（${tags.size}）" else "已添加标签", Modifier.semantics { heading() },
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
        item("presets") {
            BlacklistPresets("常用标签预设", blacklistPresets, tags, ready && !busy) { save(it) }
        }
        item("title-keywords") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSwitchRow("标题关键词屏蔽", "标题包含任一关键词时隐藏；关闭后保留关键词", titleRules.enabled,
                { saveTitles(titleRules.copy(enabled = it)) }, enabled = ready && !busy)
            ReferenceSubmitSearchField(titleInput, { titleInput = it }, "输入标题关键词", submit = addTitle,
                inputEnabled = ready && !busy, submitEnabled = canAddTitle, actionLabel = "添加")
            if (ready) BlacklistSuggestionsRow("标题关键词建议", titleSuggestions, titleLimit, !busy,
                more = { titleLimit += 12 }, add = {
                    saveTitles(titleRules.copy(keywords = titleRules.keywords + it), clearInput = true)
                })
            if (duplicateTitle && titleInput.isNotBlank()) Text("该关键词已添加", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("已添加关键词（${titleRules.keywords.size}）", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            if (ready && titleRules.keywords.isEmpty()) Text("尚未添加标题关键词", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                titleRules.keywords.forEach { keyword -> key(normalizedBlockedTag(keyword)) {
                    BlacklistTagButton(keyword, selected = true, removable = true, enabled = !busy && ready, kind = "关键词") {
                        saveTitles(titleRules.copy(keywords = titleRules.keywords.filterNot { it == keyword }))
                    }
                } }
            }
        } }
        item("title-presets") {
            BlacklistPresets("常用标题关键词预设", titlePresets, titleRules.keywords, ready && !busy) {
                saveTitles(titleRules.copy(keywords = it))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlacklistSuggestionsRow(label: String, items: List<BlacklistSuggestion>, limit: Int,
    enabled: Boolean, more: () -> Unit, add: (String) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.take(limit).forEach { suggestion -> key(suggestion.value) {
                BlacklistTagButton(suggestion.label, false, false, enabled) { add(suggestion.value) }
            } }
        }
        if (items.size > limit) TextButton(more) { Text("更多建议") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlacklistPresets(label: String, presets: List<String>, values: List<String>, enabled: Boolean,
    save: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                val normalized = normalizedBlockedTag(preset)
                val selected = values.any { normalizedBlockedTag(it) == normalized }
                BlacklistTagButton(preset, selected, false, enabled) {
                    save(if (selected) values.filterNot { normalizedBlockedTag(it) == normalized } else values + preset)
                }
            }
        }
    }
}

/** Both groups share the tag page's shape, touch height and typography. */
@Composable
private fun BlacklistTagButton(label: String, selected: Boolean, removable: Boolean, enabled: Boolean, kind: String = "标签", click: () -> Unit) {
    val background = referenceStateColor(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
    val foreground = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    TextButton(click, enabled = enabled,
        modifier = Modifier.heightIn(min = referenceActionHeight()).semantics {
            contentDescription = if (removable) "移除$kind $label" else label
            if (!removable) this.selected = selected
        }, shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = background, contentColor = foreground,
            disabledContainerColor = background, disabledContentColor = foreground.copy(alpha = .38f))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge)
            if (removable || selected) LucideIcon(if (removable) R.drawable.lucide_x else R.drawable.lucide_check, Modifier.size(16.dp))
        }
    }
}
