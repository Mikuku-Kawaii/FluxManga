package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Reserve the final image bounds before a bitmap, placeholder or caption is composed. */
@Composable
internal fun FeaturedCardFrame(cover: @Composable BoxScope.() -> Unit,
    information: @Composable (Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val ratio = ReadingSurfaceStyle.bookCoverRatio
        val coverWidth = minOf((maxHeight - 8.dp) * ratio, maxWidth * .40f)
        val inset = (maxHeight - coverWidth / ratio) / 2
        Row(Modifier.fillMaxSize().padding(start = inset, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ReadingSurfaceStyle.sideBookTitleGap)) {
            Box(Modifier.width(coverWidth).fillMaxHeight().padding(vertical = inset)
                .clip(RoundedCornerShape(12.dp)), content = cover)
            BoxWithConstraints(Modifier.weight(1f).align(Alignment.Top)) { information(maxWidth) }
        }
    }
}
