package com.flux.manga.ui.pages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Opaque backing keeps scrolled book covers from competing with batch actions. */
@Composable
internal fun SelectionActionBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val maximumHeight = (LocalConfiguration.current.screenHeightDp * .4f).dp
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background, tonalElevation = 2.dp) {
        Column(Modifier.heightIn(max = maximumHeight).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.selectionHeader(key: String, content: @Composable ColumnScope.() -> Unit) {
    stickyHeader(key = key) { SelectionActionBar(content = content) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImportSelectionActions(count: Int, enabled: Boolean, canImport: Boolean,
    selectAll: () -> Unit, clear: () -> Unit, confirm: () -> Unit) {
    FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("已选 $count 项", style = MaterialTheme.typography.titleSmall)
        TextButton(selectAll, enabled = enabled) { Text("全选已读取") }
        TextButton(clear, enabled = enabled && count > 0) { Text("清除选择") }
        ReferenceButton(confirm, enabled = canImport && count > 0) { Text("导入选中") }
    }
}
