package com.flux.manga.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A visible alternative to long press, distinct from the settings menu glyph. */
@Composable
internal fun ReferenceActionsButton(label: String, open: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    IconButton(onClick = open, enabled = enabled, interactionSource = interaction, modifier = modifier.size(48.dp).semantics { contentDescription = label }) {
        ReferenceIcon(ReferenceSymbol.MORE, Modifier.referencePressFeedback(interaction, enabled))
    }
}

@Composable
internal fun ReferenceMoreButton(label: String, tag: String, open: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    IconButton(onClick = open, interactionSource = interaction, modifier = Modifier.testTag(tag).semantics { contentDescription = label }) {
        val color = LocalContentColor.current
        androidx.compose.foundation.Canvas(Modifier.size(24.dp).referencePressFeedback(interaction).clearAndSetSemantics { }) {
            repeat(3) { index ->
                val y = (6 + index * 6).dp.toPx()
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(3.dp.toPx(), y),
                    end = androidx.compose.ui.geometry.Offset(21.dp.toPx(), y),
                    strokeWidth = 2.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
    }
}

/** Shared with the reference shelf: content surfaces are opaque, never blurred. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReferenceHeading(title: String, subtitle: String, back: () -> Unit,
                              modifier: Modifier = Modifier, backModifier: Modifier = Modifier) {
    SettingsHeading(title, subtitle, back, modifier, backModifier)
}

@Composable
internal fun ReferencePanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** A single title line anchors each destination; actions wrap below at large text sizes. */
@Composable
internal fun ReferencePageHeader(title: String, actions: @Composable RowScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        @Composable fun Title(modifier: Modifier = Modifier) {
            Text(title, modifier.testTag("root-title").semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
        }
        if (maxWidth < 290.dp || LocalDensity.current.fontScale >= 1.5f) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Title()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Title(Modifier.weight(1f))
            actions()
        }
    }
}

@Composable
internal fun ReferenceEmptyState(title: String, modifier: Modifier = Modifier, actions: @Composable ColumnScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

@Composable
internal fun referenceActionHeight() = with(LocalDensity.current) {
    maxOf(48.dp, MaterialTheme.typography.labelLarge.lineHeight.toDp() + 12.dp)
}

@Composable
internal fun ReferenceButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                             content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Button(onClick, modifier.heightIn(min = referenceActionHeight()), enabled = enabled, shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary)) {
        Row(Modifier.referencePressFeedback(interaction, enabled), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center, content = content)
    }
}

@Composable
internal fun ReferenceBookTitle(title: String, open: () -> Unit) {
    TextButton(open, Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = androidx.compose.ui.graphics.RectangleShape,
        contentPadding = PaddingValues(vertical = 8.dp)) {
        Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Counts/status and their actions share a row only while both have room at the current font size. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReferenceActionRow(text: @Composable (Modifier) -> Unit, actions: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                text(Modifier.fillMaxWidth())
                FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
            }
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            text(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

@Composable
internal fun ReferenceBookSummary(cover: @Composable () -> Unit, onClick: (() -> Unit)? = null,
                                  content: @Composable ColumnScope.() -> Unit) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val coverAction = if (onClick == null) Modifier else Modifier.clickable(onClickLabel = "查看详情", onClick = onClick)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints {
            if (maxWidth < 280.dp || largeText) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(126.dp).then(coverAction)) { cover() }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            } else Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(88.dp).then(coverAction)) { cover() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun ReferenceDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null, sheet: Boolean = false) {
    if (sheet) {
        ReferenceSheet(onDismissRequest, title, text, confirmButton, dismissButton)
        return
    }
    AlertDialog(onDismissRequest = onDismissRequest, modifier = Modifier.referenceToolEntrance(dialog = true),
        title = { Box(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
            ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
        } }, text = { ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() } },
        confirmButton = confirmButton, dismissButton = dismissButton,
        shape = RoundedCornerShape(28.dp), containerColor = MaterialTheme.colorScheme.surfaceVariant,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp)
}

/** Choice and management surfaces use the available width; short confirmations stay dialogs. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReferenceSheet(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null) {
    @Composable fun Contents() {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
            }
            Box(Modifier.weight(1f, fill = false)) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
            }
            FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
    if (LocalReferenceMotionEnabled.current) {
        ModalBottomSheet(onDismissRequest = onDismissRequest,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 0.dp) { Contents() }
    } else {
        // Material 3 1.4 does not expose its sheet motion scheme; keep reduced mode stationary.
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Spacer(Modifier.matchParentSize().clickable(onClick = onDismissRequest).clearAndSetSemantics { })
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight * .9f),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Box(Modifier.padding(top = 20.dp)) { Contents() }
                }
            }
        }
    }
}

/** A form gets a full page without changing the owner's draft, save or cancellation contract. */
@Composable
internal fun ReferenceEditor(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ReferenceActionRow(text = { modifier ->
                    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                        ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                    }
                }, actions = confirmButton)
                Box(Modifier.weight(1f)) { ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() } }
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Start) { dismissButton?.invoke() }
            }
        }
    }
}

/** Explicit commit action; cancellation and read-only close remain quiet TextButtons. */
@Composable
internal fun ReferenceDialogConfirm(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, destructive: Boolean = false) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            contentColor = if (destructive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onTertiary)) {
        Text(label)
    }
}

@Composable
internal fun ReaderToolPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Surface(modifier.padding(horizontal = 16.dp, vertical = 6.dp).referenceToolEntrance(), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column {
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            if (scroll.maxValue > 0) Text("工具区可上下滚动", Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
