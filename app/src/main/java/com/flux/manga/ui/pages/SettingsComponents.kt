package com.flux.manga.ui.pages

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface) {
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
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(24.dp).clearAndSetSemantics { }) {
        val a = if (back) 15f else 9f; val b = if (back) 9f else 15f
        val path = Path().apply { moveTo(size.width * a / 24, size.height * 5 / 24)
            lineTo(size.width * b / 24, size.height / 2); lineTo(size.width * a / 24, size.height * 19 / 24) }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
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

/** Small original line icons; no bitmap assets, icon runtime or sampled backdrop. */
@Composable
private fun SettingsGlyph(symbol: SettingsSymbol, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.clearAndSetSemantics { }) {
        val stroke = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round)
        fun point(x: Float, y: Float) = Offset(size.width * x / 24, size.height * y / 24)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, point(x,y), point(x2,y2), stroke.width, StrokeCap.Round)
        fun circle(x: Float, y: Float, r: Float) = drawCircle(color, size.width * r / 24, point(x,y), style = stroke)
        fun box(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(color, point(x,y), Size(size.width*w/24,size.height*h/24),
            androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = stroke)
        when (symbol) {
            SettingsSymbol.STARTUP -> {
                drawArc(color, -60f, 300f, false, point(3f,3f), Size(size.width*18/24,size.height*18/24), style=stroke)
                line(12f,2f,12f,12f)
            }
            SettingsSymbol.APPEARANCE -> { circle(9f,9f,7f); circle(15f,15f,7f) }
            SettingsSymbol.READING -> { box(2f,4f,20f,16f); line(12f,4f,12f,20f); line(5f,8f,9f,8f); line(15f,8f,19f,8f) }
            SettingsSymbol.SOURCES -> repeat(3) { i -> val y=2f+i*7; box(2f,y,20f,6f); circle(6f,y+3,0.6f) }
            SettingsSymbol.GROUPS -> { box(2f,7f,20f,14f); line(3f,7f,3f,3f); line(3f,3f,10f,3f); line(10f,3f,13f,7f) }
            SettingsSymbol.DOWNLOAD -> { line(12f,2f,12f,16f); line(7f,11f,12f,16f); line(12f,16f,17f,11f); line(3f,18f,3f,22f); line(3f,22f,21f,22f); line(21f,22f,21f,18f) }
            SettingsSymbol.PRIVACY -> { box(4f,10f,16f,12f); drawArc(color,180f,180f,false,point(7f,1f),Size(size.width*10/24,size.height*16/24),style=stroke); circle(12f,16f,1f) }
            SettingsSymbol.PROTECTION -> { val path=Path().apply { moveTo(size.width/2,0f); lineTo(size.width*0.9f,size.height*0.2f); lineTo(size.width*0.8f,size.height*0.7f); lineTo(size.width/2,size.height); lineTo(size.width*0.2f,size.height*0.7f); lineTo(size.width*0.1f,size.height*0.2f); close() }; drawPath(path,color,style=stroke); line(7f,12f,10f,15f); line(10f,15f,17f,8f) }
            SettingsSymbol.STORAGE -> { box(2f,3f,20f,18f); line(3f,15f,21f,15f); circle(17f,18f,0.7f) }
            SettingsSymbol.UPDATE -> { drawArc(color,40f,285f,false,point(2f,2f),Size(size.width*20/24,size.height*20/24),style=stroke); line(19f,2f,19f,8f); line(13f,8f,19f,8f) }
            SettingsSymbol.ABOUT -> { circle(12f,12f,10f); circle(12f,7f,0.7f); line(12f,11f,12f,18f) }
        }
    }
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
