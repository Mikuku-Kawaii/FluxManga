package com.flux.manga.ui.pages

import com.flux.manga.R
import com.flux.manga.ui.LucideIcon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flux.manga.ui.glass.WorldColors
import com.flux.manga.ui.navigation.PrototypeWorld

/** Compact left-aligned toolbar shared by child pages, with one accessible back action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsHeading(title: String, subtitle: String, back: () -> Unit,
    modifier: Modifier = Modifier, backModifier: Modifier = Modifier.testTag("settings-back"),
    actions: (@Composable () -> Unit)? = null) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    val backInteraction = remember { MutableInteractionSource() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { if (imeVisible) { keyboard?.hide(); focus.clearFocus() } else back() },
                interactionSource = backInteraction,
                modifier = Modifier.compactBackTarget().then(backModifier).size(48.dp).semantics { contentDescription = "返回" }) {
                SettingsChevron(back = true, modifier = Modifier.referencePressFeedback(backInteraction))
            }
            Text(title, Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleLarge)
            if (actions != null) actions()
        }
        if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
    }
}

/** Callers already supply a 16–20dp page gutter; the clickable button remains 48dp wide. */
internal fun Modifier.compactBackTarget() = layout { measurable, constraints ->
    val button = measurable.measure(constraints)
    val leading = 16.dp.roundToPx()
    layout(button.width - leading, button.height) { button.placeRelative(-leading, 0) }
}

@Composable
internal fun SettingsGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, Modifier.padding(horizontal = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth().readingCardShadow(), shape = ReadingSurfaceStyle.cardShape,
            color = ReadingSurfaceStyle.card, border = ReadingSurfaceStyle.border,
            contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(content = content)
        }
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun SettingsPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier) {
        SettingsGroup {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** Folds presentation only; editable drafts and operations remain owned by the caller. */
@Composable
internal fun SettingsDisclosure(title: String, summary: String? = null, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().referenceContentResize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = { expanded = !expanded })
            .heightIn(min = 56.dp).semantics { stateDescription = if (expanded) "已展开" else "已收起" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            ReferenceDisclosureChevron(expanded)
        }
        if (expanded) content()
    }
}

internal enum class SettingsSymbol { STARTUP, APPEARANCE, READING, SOURCES, GROUPS, DOWNLOAD, PRIVACY, PROTECTION, STORAGE, UPDATE, ABOUT }

@Composable
internal fun SettingsRow(title: String, summary: String, tag: String, symbol: SettingsSymbol?,
    open: () -> Unit, enabled: Boolean = true, prominent: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(tag)
        .clickable(enabled = enabled, role = Role.Button, onClick = open)
        .heightIn(min = if (prominent) 64.dp else 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart) {
        // Decorative leading symbols yield room to text on narrow windows / large fonts.
        val showSymbol = symbol != null && maxWidth >= 280.dp && LocalDensity.current.fontScale < 1.5f
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (showSymbol) SettingsGlyph(symbol!!, Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!enabled) Text("暂不可用", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (enabled) SettingsChevron()
        }
    }
}

@Composable
internal fun SettingsChevron(back: Boolean = false, modifier: Modifier = Modifier) {
    LucideIcon(if (back) R.drawable.lucide_chevron_left else R.drawable.lucide_chevron_right, modifier, MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The entire row owns one switch action, including in the stacked large-text layout. */
@Composable
internal fun SettingsSwitchRow(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change).padding(vertical = 10.dp),
        contentAlignment = Alignment.CenterStart) {
        @Composable fun Label(modifier: Modifier = Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (maxWidth < 220.dp && LocalDensity.current.fontScale >= 1.5f) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label()
            Switch(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.align(Alignment.End))
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(Modifier.weight(1f))
            Switch(checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
internal fun SettingsChoiceRow(title: String, selected: Boolean, select: () -> Unit,
    modifier: Modifier = Modifier, description: String? = null, enabled: Boolean = true) {
    val background = referenceStateColor(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = background,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface) {
        Row(modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = select)
            .padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            RadioButton(selected, onClick = null, enabled = enabled)
        }
    }
}

@Composable
private fun SettingsGlyph(symbol: SettingsSymbol, modifier: Modifier) {
    val glyph = when (symbol) {
        SettingsSymbol.STARTUP -> R.drawable.lucide_power
        SettingsSymbol.APPEARANCE -> R.drawable.lucide_palette
        SettingsSymbol.READING -> R.drawable.lucide_book_open
        SettingsSymbol.SOURCES -> R.drawable.lucide_server
        SettingsSymbol.GROUPS -> R.drawable.lucide_folder
        SettingsSymbol.DOWNLOAD -> R.drawable.lucide_download
        SettingsSymbol.PRIVACY -> R.drawable.lucide_lock_keyhole
        SettingsSymbol.PROTECTION -> R.drawable.lucide_shield_check
        SettingsSymbol.STORAGE -> R.drawable.lucide_hard_drive
        SettingsSymbol.UPDATE -> R.drawable.lucide_refresh_cw
        SettingsSymbol.ABOUT -> R.drawable.lucide_info
    }
    LucideIcon(glyph, modifier, MaterialTheme.colorScheme.primary)
}

@Preview(name = "Settings light", widthDp = 392, heightDp = 850)
@Preview(name = "Settings narrow large", widthDp = 320, heightDp = 850, fontScale = 2f)
@Composable
private fun SettingsLightPreview() { SettingsPreview(PrototypeWorld.SFW) }

@Preview(name = "Settings dark", widthDp = 392, heightDp = 850)
@Preview(name = "Settings dark large", widthDp = 320, heightDp = 850, fontScale = 2f)
@Composable
private fun SettingsDarkPreview() { SettingsPreview(PrototypeWorld.NSFW) }

@Composable
private fun SettingsPreview(world: PrototypeWorld) {
    MaterialTheme(colorScheme = WorldColors.scheme(world)) {
        Surface { SettingsPage({}, {}, {}, {}, {}, {}, {}, {}) }
    }
}
