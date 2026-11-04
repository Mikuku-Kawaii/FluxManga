package com.flux.manga.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Home recommendations and shelf continuation share one frame, including large text. */
@Composable
internal fun featuredCardHeight(): Dp {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val title = with(density) { typography.titleMedium.lineHeight.toDp() } * 2
    val metadata = with(density) { typography.bodySmall.lineHeight.toDp() }
    // Reserve two title lines, bounded metadata and a complete action/summary at this font scale.
    val continuation = title + metadata * 2 + referenceActionHeight() + 27.dp
    val recommendation = title + metadata * 4 + with(density) { 16.sp.toDp() } + 38.dp
    return maxOf(160.dp, continuation, recommendation)
}

internal fun featuredCoverWidth(availableWidth: Dp, height: Dp): Dp = (height * .7f).coerceAtMost(availableWidth * .4f)
