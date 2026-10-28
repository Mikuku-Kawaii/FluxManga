package com.flux.manga.ui.pages

import android.content.SharedPreferences
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flux.manga.MainActivity
import com.flux.manga.ui.navigation.PrototypeWorld

@Composable
internal fun rememberReaderOptions(world: PrototypeWorld): Pair<ReaderOptions, (ReaderOptions) -> Unit> {
    val app = LocalContext.current.applicationContext
    val prefs = remember(app) { app.getSharedPreferences("reader-controls", 0) }
    fun read() = ReaderOptions(prefs.getBoolean("${world.name}.volume", false), prefs.getBoolean("${world.name}.invert", false),
        prefs.getBoolean("${world.name}.double-tap", true), prefs.getBoolean("${world.name}.continuous", true), prefs.getBoolean("${world.name}.spread", false))
    var value by remember(world) { mutableStateOf(read()) }
    DisposableEffect(prefs, world) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> value = read() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value to { next ->
        prefs.edit {
            putBoolean("${world.name}.volume", next.volume); putBoolean("${world.name}.invert", next.invertVolume)
            putBoolean("${world.name}.double-tap", next.doubleTap); putBoolean("${world.name}.continuous", next.continuous)
            putBoolean("${world.name}.spread", next.spread)
        }
        value = next
    }
}

@Composable
internal fun ReaderOptionControls(options: ReaderOptions, change: (ReaderOptions) -> Unit, online: Boolean, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSwitchRow("音量键翻页", "隐藏工具后生效", options.volume,
            { change(options.copy(volume = it)) }, enabled = enabled)
        if (options.volume) SettingsSwitchRow("反向音量键", "开启后用音量加向后阅读", options.invertVolume,
            { change(options.copy(invertVolume = it)) }, enabled = enabled)
        SettingsSwitchRow("双击缩放", "双击切换 1× / 2×", options.doubleTap,
            { change(options.copy(doubleTap = it)) }, enabled = enabled)
        if (online) SettingsSwitchRow("跨章节连读", "缺章或加载失败时停留", options.continuous,
            { change(options.copy(continuous = it)) }, enabled = enabled)
        SettingsSwitchRow("宽屏双页", "仅横向宽屏的逐页模式可用", options.spread,
            { change(options.copy(spread = it)) }, enabled = enabled)
    }
    if (options.volume) Text("隐藏工具后，音量${if (options.invertVolume) "加" else "减"}键向后阅读；条漫滚动约一屏。", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun ReaderOptionsSettings(world: PrototypeWorld) {
    val (options, change) = rememberReaderOptions(world)
    SettingsPanel { SettingsDisclosure("阅读操作", "音量键、缩放、连读与双页 · 即时保存") {
        @Composable fun Option(title: String, detail: String, checked: Boolean, update: (Boolean) -> Unit) {
            SettingsSwitchRow(title, detail, checked, update)
        }
        Option("音量键翻页", "隐藏阅读工具后生效", options.volume) { change(options.copy(volume = it)) }
        Option("反向音量键", if (options.volume) "开启后用音量加向后阅读" else "开启音量键翻页后生效", options.invertVolume) { change(options.copy(invertVolume = it)) }
        Option("双击缩放", "双击切换 1× / 2×", options.doubleTap) { change(options.copy(doubleTap = it)) }
        Option("跨章节连读", "离线缺章时停止，不跨本地书目", options.continuous) { change(options.copy(continuous = it)) }
        Option("宽屏双页", "横向宽屏的左右翻页模式可用", options.spread) { change(options.copy(spread = it)) }
        SettingsDisclosure("操作说明") {
            Text("阅读操作设置在当前空间共用，即时保存。")
            Text("隐藏阅读工具后，音量减向后翻，音量加向前翻；可设置反向。")
            Text("捏合最多放大 3 倍，拖动查看。在线漫画读完后可继续翻到相邻章节。")
            Text("横向阅读区域足够宽时显示双页，竖屏和条漫保持单页。")
        }
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderVolumeKeys(enabled: Boolean, inverted: Boolean, move: (Int) -> Unit) {
    val activity = LocalActivity.current as? MainActivity
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val ime = WindowInsets.isImeVisible
    val latest by rememberUpdatedState(move)
    val canHandle = enabled && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !ime
    DisposableEffect(activity, canHandle, inverted) {
        val handler: (Int) -> Unit = { latest(if (inverted) -it else it) }
        if (canHandle) activity?.readerVolumeKeys?.owner = handler
        onDispose { if (activity?.readerVolumeKeys?.owner === handler) activity.readerVolumeKeys.owner = null }
    }
}
