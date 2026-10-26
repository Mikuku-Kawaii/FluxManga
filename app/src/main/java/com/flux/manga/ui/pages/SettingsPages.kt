package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flux.manga.ui.glass.DockEffects
import com.flux.manga.data.SourceKind
import com.flux.manga.data.SourceSelection
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.launch

@Composable
internal fun SettingsPage(back: () -> Unit, appearance: () -> Unit,
    sources: () -> Unit, about: () -> Unit, reading: () -> Unit,
    library: () -> Unit, privacyMaintenance: () -> Unit,
    session: com.flux.manga.session.WorldSession? = null, effects: DockEffects? = null) {
    val readingState = session?.let { preloadedPages(it).reading().state.collectAsStateWithLifecycle().value }
    val readingSummary = when {
        readingState == null -> ""
        readingState.loading -> "正在读取默认方式…"
        readingState.stored != null -> readingState.stored.label
        else -> "默认方式读取失败"
    }
    val appearanceSummary = effects?.let { current ->
        listOfNotNull(if (current.lowEffects) "低效果" else if (current.backdropEnabled) "玻璃材质" else "基础材质",
            "减少动画".takeIf { current.reduceMotion }).joinToString(" · ")
    }.orEmpty()
    LazyColumn(Modifier.fillMaxSize().testTag("settings-page"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("heading") { SettingsHeading("设置", "", back) }
        item("preferences") { SettingsGroup {
            SettingsRow("外观与动效", appearanceSummary, "settings-appearance", SettingsSymbol.APPEARANCE, appearance, prominent = true)
            SettingsDivider()
            SettingsRow("阅读方式", readingSummary, "settings-reading", SettingsSymbol.READING, reading, prominent = true)
            SettingsDivider()
            SettingsRow("图源管理", "图源开关、账号与书库导入", "settings-sources", SettingsSymbol.SOURCES, sources, prominent = true)
        } }
        item("management") { SettingsGroup {
            SettingsRow("书库与记录", "下载、统计与存储", "settings-library", SettingsSymbol.GROUPS, library)
            SettingsDivider()
            SettingsRow("隐私与维护", "隐私入口、数据保护与应用更新", "settings-maintenance", SettingsSymbol.PRIVACY, privacyMaintenance)
            SettingsDivider()
            SettingsRow("关于 FluxManga", "", "settings-about", SettingsSymbol.ABOUT, about)
        } }
    }
}

@Composable
internal fun LibrarySettingsPage(back: () -> Unit, downloads: () -> Unit,
    statistics: () -> Unit, exportRecords: () -> Unit, storage: () -> Unit,
    session: com.flux.manga.session.WorldSession? = null, localLibrary: (() -> Unit)? = null,
    onlineLibrary: (() -> Unit)? = null) {
    var showInformation by rememberSaveable { mutableStateOf(false) }
    val information = session?.let { rememberBookInformation(it) }
    if (showInformation && information != null) ReferenceSheet(onDismissRequest = { showInformation = false },
        title = { Text("书目信息") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { BookInformationPanel(information) } },
        confirmButton = { TextButton({ showInformation = false }) { Text("关闭") } })
    LazyColumn(Modifier.fillMaxSize().testTag("library-settings"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("书库与记录", "", back) }
        item { SettingsGroup("整理与阅读") {
            SettingsRow("下载队列", "", "settings-downloads", null, downloads)
            SettingsDivider()
            SettingsRow("阅读统计", "", "settings-statistics", null, statistics)
        } }
        if (localLibrary != null || onlineLibrary != null || information != null) item { SettingsGroup("书目管理") {
            localLibrary?.let { SettingsRow("本地书库", "", "settings-local-library", null, it) }
            if (localLibrary != null && onlineLibrary != null) SettingsDivider()
            onlineLibrary?.let { SettingsRow("在线书库", "", "settings-online-library", null, it) }
            if (information != null) {
                if (localLibrary != null || onlineLibrary != null) SettingsDivider()
                SettingsRow("补齐书目信息", "", "settings-book-information", null, { showInformation = true })
            }
        } }
        item { SettingsGroup("本地数据") {
            SettingsRow("导出本地记录", "书目、书签与阅读进度", "settings-record-export", null, exportRecords)
            SettingsDivider()
            SettingsRow("存储与清理", "", "settings-storage", null, storage)
        } }
    }
}

@Composable
internal fun PrivacyMaintenanceSettingsPage(back: () -> Unit, privacy: () -> Unit,
    protection: () -> Unit, updates: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("privacy-maintenance-settings"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("隐私与维护", "", back) }
        item { SettingsGroup {
            SettingsRow("隐私入口", "", "settings-privacy", null, privacy)
            SettingsDivider()
            SettingsRow("数据保护与恢复", "备份、恢复与保护状态", "settings-protection", null, protection)
            SettingsDivider()
            SettingsRow("应用更新", "", "settings-updates", null, updates)
        } }
    }
}

@Composable
private fun SettingsLink(title: String, summary: String, tag: String, open: () -> Unit) {
    SettingsGroup {
        SettingsRow(title, summary, tag, null, open)
    }
}

@Composable
internal fun AppearanceSettingsPage(effects: DockEffects, change: (DockEffects) -> Unit, back: () -> Unit, enhancementFailure: String? = null) {
    LazyColumn(Modifier.fillMaxSize().testTag("appearance-settings"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("外观与动效", "即时生效", back,
            backModifier = Modifier.testTag("settings-back")) }
        item { ReferencePanel { DockEffectControls(effects, change, enhancementFailure) } }

    }
}

/** Shared by the product settings page and the retained developer effects dialog. */
@Composable
internal fun DockEffectControls(effects: DockEffects, change: (DockEffects) -> Unit, failure: String? = null) {
    val api = android.os.Build.VERSION.SDK_INT
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (failure != null) RequestFeedback(failure, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("dock-enhancement-failure").semantics { liveRegion = LiveRegionMode.Polite })
        @Composable fun Option(option: DockEffectOption) {
            val checked = option.checked(effects)
            val enabled = option.enabled(effects, api)
            SettingsSwitchRow(option.label, option.description(effects, api), checked,
                { change(option.changed(effects, it, api)) }, Modifier.testTag(option.tag), enabled)
        }
        val basic = listOf(DockEffectOption.LOW, DockEffectOption.MOTION, DockEffectOption.TINT)
        basic.forEach { Option(it) }
        SettingsDisclosure("玻璃效果细节", "采样、模糊与边缘光效") {
            DockEffectOption.entries.filterNot { it in basic }.forEach { Option(it) }
            Text("低效果与减少动画重启后保留；其余玻璃选项随当前任务恢复。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AboutSettingsPage(back: () -> Unit, licenses: () -> Unit,
    developerTools: @Composable ColumnScope.() -> Unit = {}) {
    var developerEnabled by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val version = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName ?: "版本未知"} (${info.longVersionCode})"
    }
    val uriHandler = LocalUriHandler.current
    var linkError by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("about-settings"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("关于", "", back, backModifier = Modifier.testTag("settings-back")) }
        item { SettingsPanel {
            Text("FluxManga · 流光漫匣", style = MaterialTheme.typography.titleLarge)
            Text("版本 $version", modifier = Modifier.testTag("app-version")
                .combinedClickable(onClick = {}, onLongClickLabel = "显示开发工具", onLongClick = { developerEnabled = !developerEnabled })
                .padding(vertical = 12.dp))
        } }
        if (developerEnabled) item("developer-tools") { SettingsPanel { SettingsDisclosure("开发工具") { developerTools() } } }
        item("offline-licenses") { SettingsGroup {
            SettingsRow("开源许可与声明", "离线搜索、组件信息与许可原文", "about-licenses", SettingsSymbol.ABOUT, licenses)
        } }
        if (linkError) item {
            RequestFeedback("无法打开链接，请检查浏览器是否可用。", color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        // License identifiers/URLs checked against the pinned packages' local POM/Cargo metadata.
        val components = listOf(
            Triple("液态材质核心 · Kyant Backdrop", "Apache 2.0 · 已保留来源与修改说明", "https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/LICENSE"),
            Triple("包子账号协议适配 · kototoro-parsers", "GPL 3.0 · 已保留来源与修改说明", "https://github.com/skepsun/kototoro-parsers/blob/a94b6704997081b0b57544d3404dfc6809c9ae4b/LICENSE"),
            Triple("包子漫画协议适配 · Keiyoushi", "Apache 2.0 · 已保留来源与修改说明", "https://github.com/keiyoushi/extensions-source/blob/ff521c17bb3e503c01e658e8fac00b60a95829f3/LICENSE"),
            Triple("jsoup 1.21.2", "MIT", "https://github.com/jhy/jsoup/blob/jsoup-1.21.2/LICENSE"),
            Triple("拷贝漫画协议适配 · fumiama/copymanga", "GPL 3.0 · 已保留来源与修改说明", "https://github.com/fumiama/copymanga/blob/0fdd3f4489c8ec02f41b4a570aba3b11acfb889b/LICENSE"),
            Triple("AndroidX Compose UI 1.9.5 / Room 2.8.4", "Apache 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt"),
            Triple("Kotlin 2.2.20", "Apache 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt"),
            Triple("UniFFI 0.29.4", "MPL 2.0", "https://github.com/mozilla/uniffi-rs"),
            Triple("SQLCipher Android 4.9.0", "BSD-3-Clause · 保留原库适配与修改声明", "https://github.com/sqlcipher/sqlcipher-android/blob/ffe6c588b06ab042d60264f1f0da79a7ac56db0f/LICENSE"),
            Triple("JNA 5.17.0", "Apache 2.0 / LGPL 2.1", "https://github.com/java-native-access/jna"),
        )
        item("component-links") { SettingsPanel { SettingsDisclosure("主要组件外部链接", "在浏览器中查看许可与来源") {
            components.forEach { (name, license, url) ->
                SettingsLink(name, license, "license-$name") {
                    try { uriHandler.openUri(url); linkError = false }
                    catch (_: IllegalArgumentException) { linkError = true }
                    catch (_: android.content.ActivityNotFoundException) { linkError = true }
                }
            }
        } } }
        item("description") { SettingsPanel { SettingsDisclosure("应用说明") {
            Text("支持本地 CBZ、Komga、拷贝漫画与包子漫画，提供书库管理、四种阅读方式与离线下载。各图源以实际支持能力和准入状态为准。")
        } } }
    }
}

@Composable
internal fun SourceManagementPage(
    available: Boolean, back: () -> Unit, komga: () -> Unit, baoziAccount: () -> Unit,
    copyAccount: () -> Unit, jm: () -> Unit = {}, pica: () -> Unit = {},
    eh: () -> Unit = {}, ex: () -> Unit = {},
    session: com.flux.manga.session.WorldSession? = null,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val world = if (available) PrototypeWorld.SFW else PrototypeWorld.NSFW
    val selection = remember(world) { SourceSelection(app, world) }
    var enabledSources by remember(selection) { mutableStateOf(selection.snapshot()) }
    var saving by remember(selection) { mutableStateOf(false) }
    var error by remember(selection) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val feedback = LocalRequestFeedback.current
    val owner = session?.identity?.value
    fun active() = session == null || (session.accepts(requireNotNull(owner)) && session.state.value.foreground)
    @Composable fun Source(kind: SourceKind, settingsTag: String, settings: () -> Unit) {
        SourceManagementGroup(kind.label, settingsTag, settings,
            enabled = enabledSources[kind] == true, saving = saving, toggle = { value ->
                if (!saving && active()) {
                    feedback?.beginAttempt(); error = null; saving = true
                    scope.launch {
                        try {
                            selection.set(kind, value, ::active)
                            if (active()) {
                                enabledSources = selection.snapshot()
                                session?.let { preloadedPages(app, it).sourceSelectionChanged() }
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: java.io.IOException) { if (active()) error = "图源开关保存失败，请重试" }
                        finally { saving = false }
                    }
                }
            })
    }
    error?.let { RequestFeedback(it) }
    LazyColumn(Modifier.fillMaxSize().testTag("source-management"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("heading") { SettingsHeading("图源管理", if (available) "表世界图源" else "里世界图源", back) }
        item("sources") { SettingsGroup {
            if (available) {
                Source(SourceKind.KOMGA, "manage-komga", komga)
                SettingsDivider()
                Source(SourceKind.COPY, "manage-copy-account", copyAccount)
                SettingsDivider()
                Source(SourceKind.BAOZI, "manage-baozi-account", baoziAccount)
            } else {
                Source(SourceKind.JM, "manage-jm-account", jm)
                SettingsDivider()
                Source(SourceKind.PICA, "manage-pica-account", pica)
                SettingsDivider()
                Source(SourceKind.EH, "manage-eh-account", eh)
                SettingsDivider()
                Source(SourceKind.EX, "manage-ex-account", ex)
            }
        } }
        item("scope") { AccountMoreActions("图源与导入说明") { _ ->
            Text("开关控制首页推荐、最近更新、在线搜索和标签发现，保存后立即生效。关闭不退出账号、不移除书库或进度；已入库作品仍可直接阅读，账号管理与手动导入保持可用。")
            Text("点击图源直接进入设置，书库导入位于同一页面。首次使用默认只开启表世界拷贝、里世界 JM，其余来源可手动开启。")
            Text("导入保存书目，不自动下载漫画正文，也不修改网站上的喜欢或收藏。相同来源与账号的已有书目保留本机收藏设置和阅读进度。")
            Text("同一空间的各来源统一展示在书库；不同空间、账号及 EH / EX 的作品身份保持独立。导入范围以各来源页面说明为准。")
        } }
    }
}

@Composable
private fun SourceManagementGroup(title: String, settingsTag: String, settings: () -> Unit,
    enabled: Boolean, saving: Boolean, toggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            SettingsRow(title, if (enabled) "已启用" else "已关闭", "$settingsTag-entry", SettingsSymbol.SOURCES, settings)
        }
        Switch(checked = enabled, onCheckedChange = toggle, enabled = !saving,
            modifier = Modifier.padding(end = 12.dp).testTag("$settingsTag-enabled")
                .semantics { contentDescription = "启用$title" })
    }
}

@Composable
internal fun DownloadsUnavailablePage(back: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("downloads-unavailable"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SettingsHeading("下载队列", "", back) }
        item { ReferencePanel {
            Text("当前入口不可用", style = MaterialTheme.typography.titleMedium)
            Text("请返回当前空间的下载队列，或从作品详情选择下载。")
        } }
    }
}
