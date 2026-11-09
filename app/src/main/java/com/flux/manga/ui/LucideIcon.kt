package com.flux.manga.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** The parent control owns the action label and hit area. */
@Composable
internal fun LucideIcon(@DrawableRes drawable: Int, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(drawable), contentDescription = null, modifier = modifier.size(24.dp), tint = tint)
}
