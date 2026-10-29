package com.flux.manga.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Home recommendations and shelf continuation share one frame, including large text. */
@Composable
internal fun featuredCardHeight(): Dp = 144.dp.coerceAtLeast(80.dp * LocalDensity.current.fontScale) + 16.dp

internal fun featuredCoverWidth(availableWidth: Dp, height: Dp): Dp = (height * .7f).coerceAtMost(availableWidth * .4f)
