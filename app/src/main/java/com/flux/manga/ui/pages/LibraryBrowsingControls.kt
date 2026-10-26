package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.flux.manga.data.LocalSearchField

internal val ReferenceSearchShape = RoundedCornerShape(20.dp)

/** One quiet search surface for local filtering and explicit online submissions. */
@Composable
internal fun ReferenceSearchField(value: String, change: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions: KeyboardActions? = null,
    trailingIcon: (@Composable () -> Unit)? = null) {
    val action: (@Composable () -> Unit)? = trailingIcon ?: if (value.isNotEmpty()) ({
        TextButton(onClick = { change("") }, enabled = enabled) { Text("清空") }
    }) else null
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val border = referenceStateColor(if (focused && enabled) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
    // Keep typed text and its action accessible instead of shrinking the input beside a large label.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.3f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (stacked) Text(placeholder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    BasicTextField(value, change, modifier.fillMaxWidth()
        .semantics { contentDescription = placeholder }, enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface
            .copy(alpha = if (enabled) 1f else 0.38f)),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interaction,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions ?: KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
        decorationBox = { input ->
            Surface(shape = ReferenceSearchShape, color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.border(1.dp, border, ReferenceSearchShape)) {
                // The text grows with font scale; actions keep their full 48dp touch area.
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!stacked) DiscoverySearchGlyph()
                    Box(Modifier.weight(1f).padding(vertical = 8.dp, horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                        if (!stacked && value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        input()
                    }
                    if (!stacked) action?.invoke()
                }
            }
        })
    if (stacked && action != null) Box(Modifier.align(androidx.compose.ui.Alignment.End)) { action() }
    }
    }
}

/** Filters the already loaded library; the keyboard action only finishes text entry. */
@Composable
internal fun LibraryQueryField(query: String, change: (String) -> Unit, tag: String,
    placeholder: String = "搜索书名", enabled: Boolean = true) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    ReferenceSearchField(query, { change(it.take(200)) }, placeholder, enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
        trailingIcon = if (query.isNotEmpty()) ({ TextButton(onClick = { change("") }, enabled = enabled) { Text("清空") } }) else null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalSearchFieldOptions(field: LocalSearchField, change: (LocalSearchField) -> Unit) {
    Text("搜索范围", style = MaterialTheme.typography.titleMedium)
    FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LocalSearchField.entries.forEach { value ->
            FilterChip(selected = field == value, onClick = { change(value) }, label = { Text(value.label) },
                modifier = Modifier.testTag("local-search-field-${value.name}"))
        }
    }
}

/** Explicit network search: button and IME share the same enabled state and callback. */
@Composable
internal fun ReferenceSubmitSearchField(
    value: String, change: (String) -> Unit, placeholder: String, submit: () -> Unit,
    modifier: Modifier = Modifier, inputEnabled: Boolean = true, submitEnabled: Boolean = true,
    actionLabel: String = "搜索", actionModifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val search = { if (inputEnabled && submitEnabled) { keyboard?.hide(); focus.clearFocus(); submit() } }
    ReferenceSearchField(value, change, placeholder, modifier, enabled = inputEnabled,
        keyboardActions = KeyboardActions(onSearch = { search() }),
        trailingIcon = { TextButton(feedbackAttempt(search), enabled = inputEnabled && submitEnabled, modifier = actionModifier) { Text(actionLabel) } })
}

/** Home opens the search page without pretending to be an editable text field. */
@Composable
internal fun ReferenceSearchEntry(label: String, open: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = ReferenceSearchShape, color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = open)) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DiscoverySearchGlyph()
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
