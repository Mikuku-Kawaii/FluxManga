package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Source tags only; no local associations, genres or book-level tags. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KomgaDetailTags(sourceId: String, mangaId: String, tags: List<String>?, compact: Boolean = false, open: (String, String) -> Unit) {
    if (tags.isNullOrEmpty()) return
    var expanded by rememberSaveable(sourceId, mangaId) { mutableStateOf(false) }
    val previewCount = if (compact) 4 else 6
    Column(Modifier.testTag("komga-detail-tags"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.take(previewCount).forEach { tag ->
                OutlinedButton(onClick = { open(sourceId, tag) }, modifier = Modifier.defaultMinSize(minHeight = 32.dp),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text(tag) }
            }
        }
        if (tags.size > previewCount) TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("komga-all-tags")) {
            Text("查看全部 ${tags.size} 个标签")
        }
    }
    if (expanded) ReferenceDialog(onDismissRequest = { expanded = false },
        title = { Text("作品标签") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("komga-all-tags-list")) {
                items(tags, key = { it }) { tag ->
                    TextButton(onClick = { expanded = false; open(sourceId, tag) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tag) }
                }
            }
        }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("关闭") } })
}

/** Names are visible; request IDs stay attached to their original source. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkTags(tags: List<Pair<String, String>>?, canOpen: (String) -> Boolean = { true }, compact: Boolean = false, open: (String, String) -> Unit) {
    if (tags.isNullOrEmpty()) return
    var expanded by rememberSaveable(tags) { mutableStateOf(false) }
    val previewCount = if (compact) 4 else 6
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (if (expanded) tags else tags.take(previewCount)).forEach { (name, id) ->
                if (canOpen(id)) OutlinedButton(onClick = { open(id, name) }, modifier = Modifier.defaultMinSize(minHeight = 32.dp),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text(name) }
                else Text(name, Modifier.padding(8.dp))
            }
        }
        if (tags.size > previewCount) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起标签" else "查看全部标签") }
    }
}
