package com.flux.manga.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Actions use the exact same compact text button as the shelf reading filters. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShelfSelectionBar(
    canEdit: Boolean, hasBooks: Boolean, hasSelection: Boolean,
    selectAll: () -> Unit, clear: () -> Unit, unfavorite: () -> Unit,
) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        ShelfTextButton("全选", selectAll, enabled = canEdit && hasBooks)
        ShelfTextButton("清空", clear, enabled = canEdit && hasSelection)
        ShelfTextButton("取消收藏", unfavorite, enabled = canEdit && hasSelection)
    }
}

@Composable
internal fun ShelfTextButton(label: String, action: () -> Unit, enabled: Boolean,
    modifier: Modifier = Modifier, selected: Boolean? = null) {
    val background = referenceStateColor(if (selected == true) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
    TextButton(action, enabled = enabled, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = background,
            contentColor = if (selected == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = modifier.then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier)) {
        Text(label)
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
