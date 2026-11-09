package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flux.manga.R
import com.flux.manga.ui.LucideIcon

internal enum class ReferenceSymbol(val drawable: Int) {
    BOOKMARK(R.drawable.lucide_bookmark), DOWNLOAD(R.drawable.lucide_download), PLAY(R.drawable.lucide_play),
    PREVIOUS(R.drawable.lucide_chevron_left), NEXT(R.drawable.lucide_chevron_right), BOOK(R.drawable.lucide_book_open),
    OPTIONS(R.drawable.lucide_sliders_horizontal), UPDATE(R.drawable.lucide_refresh_cw), MORE(R.drawable.lucide_ellipsis)
}

@Composable
internal fun ReferenceIcon(symbol: ReferenceSymbol, modifier: Modifier = Modifier, filled: Boolean = false) {
    val drawable = if (symbol == ReferenceSymbol.BOOKMARK && filled) R.drawable.lucide_bookmark_check else symbol.drawable
    LucideIcon(drawable, modifier.size(20.dp))
}
