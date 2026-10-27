package com.flux.manga.ui.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Both spaces use the same actions and geometry; their theme supplies the colors. */
@Composable
internal fun ShelfSelectionBar(
    count: Int, allSelected: Boolean, canEdit: Boolean, hasBooks: Boolean,
    primaryAction: ShelfBatchAction, public: Boolean, hasLocal: Boolean,
    busy: Boolean, processed: Int, total: Int, message: String?,
    exit: () -> Unit, selectAll: () -> Unit, clear: () -> Unit,
    action: (ShelfBatchAction) -> Unit, removeLocal: () -> Unit, stop: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    val canAct = canEdit && count > 0
    SelectionActionBar(Modifier.padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(exit, enabled = !busy, modifier = Modifier.testTag("shelf-batch-done")
                .semantics { contentDescription = "退出多选" }) {
                val color = LocalContentColor.current
                Canvas(Modifier.size(24.dp).clearAndSetSemantics { }) {
                    drawLine(color, Offset(size.width * .25f, size.height * .25f),
                        Offset(size.width * .75f, size.height * .75f), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(color, Offset(size.width * .75f, size.height * .25f),
                        Offset(size.width * .25f, size.height * .75f), 2.dp.toPx(), StrokeCap.Round)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("已选 $count 部", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (!busy && message != null) Text(message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            TextButton(selectAll, enabled = canEdit && hasBooks, modifier = Modifier.heightIn(min = 48.dp)
                .semantics { contentDescription = if (allSelected) "取消全选当前筛选结果" else "全选当前筛选结果" }) {
                Text(if (allSelected) "取消全选" else "全选")
            }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                Text("正在处理 $processed/$total 部", Modifier.weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                TextButton(stop, modifier = Modifier.heightIn(min = 48.dp).fillMaxHeight()) { Text("停止处理") }
            } else {
                FilledTonalButton({ action(primaryAction) }, enabled = canAct,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).fillMaxHeight(), shape = MaterialTheme.shapes.medium) {
                    Text(if (primaryAction == ShelfBatchAction.UNFAVORITE) "取消收藏" else "收藏")
                }
                Box(Modifier.fillMaxHeight()) {
                    TextButton({ more = true }, modifier = Modifier.heightIn(min = 48.dp).fillMaxHeight()
                        .semantics { contentDescription = "更多多选操作" }) {
                        ReferenceIcon(ReferenceSymbol.MORE)
                        Spacer(Modifier.width(6.dp))
                        Text("更多")
                    }
                    DropdownMenu(more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text("收藏") }, enabled = canAct,
                            onClick = { more = false; action(ShelfBatchAction.FAVORITE) })
                        DropdownMenuItem(text = { Text("取消收藏") }, enabled = canAct,
                            onClick = { more = false; action(ShelfBatchAction.UNFAVORITE) })
                        DropdownMenuItem(text = { Text("清空选择") }, enabled = canAct,
                            onClick = { more = false; clear() })
                        if (public) {
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("移除本地书目") }, enabled = canAct && hasLocal,
                                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error),
                                onClick = { more = false; removeLocal() })
                        }
                    }
                }
            }
        }
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
