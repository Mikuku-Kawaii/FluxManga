package com.flux.manga.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** The title and reading filters are fixed by the caller, above these three actions. */
@Composable
internal fun ShelfSelectionBar(
    canEdit: Boolean, hasBooks: Boolean, hasSelection: Boolean,
    selectAll: () -> Unit, clear: () -> Unit, unfavorite: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable fun Action(label: String, enabled: Boolean, action: () -> Unit) {
            FilledTonalButton(action, enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).fillMaxHeight(),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
                Text(label, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Action("全选", canEdit && hasBooks, selectAll)
        Action("清空", canEdit && hasSelection, clear)
        Action("取消收藏", canEdit && hasSelection, unfavorite)
    }
}

/** The tile owns the touch target and checkbox semantics; this badge is decorative. */
@Composable
internal fun ShelfSelectionBadge(checked: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier.size(26.dp).clearAndSetSemantics { }, shape = CircleShape,
        color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        border = BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
        if (checked) {
            val color = LocalContentColor.current
            Canvas(Modifier.padding(6.dp)) {
                drawLine(color, Offset(0f, size.height * .5f), Offset(size.width * .36f, size.height * .85f),
                    2.dp.toPx(), StrokeCap.Round)
                drawLine(color, Offset(size.width * .36f, size.height * .85f), Offset(size.width, size.height * .15f),
                    2.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}
