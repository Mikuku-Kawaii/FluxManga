package com.flux.manga.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.flux.manga.data.CopyMangaEndpoint
import com.flux.manga.ui.navigation.DiscoverySection

/** Stable provider choice; the configured Komga instance ID is resolved separately. */
internal enum class DiscoverySource(val title: String) {
    COPY("拷贝漫画"), KOMGA("Komga"), BAOZI("包子漫画");
    fun id(state: DiscoveryState): String? = when {
        state.status == DiscoveryStatus.PRIVATE -> null
        this == COPY -> CopyMangaEndpoint.SOURCE_ID
        this == BAOZI -> com.flux.manga.data.BaoziEndpoint.SOURCE_ID
        state.status == DiscoveryStatus.READY -> state.sourceId
        else -> null
    }
    fun supports(section: DiscoverySection) = when (section) {
        DiscoverySection.RECOMMENDATIONS -> this == COPY || this == BAOZI
        DiscoverySection.UPDATES -> true
        DiscoverySection.VISITS -> false
    }
    companion object {
        fun defaultFor(section: DiscoverySection?) = if (section == DiscoverySection.RECOMMENDATIONS) COPY else KOMGA
    }
}
internal fun discoverySourceName(sourceId: String?) = when {
    sourceId?.startsWith("aggregate-v1:") == true -> "聚合搜索"
    sourceId?.startsWith("jmcomic-") == true -> "JMComic"
    sourceId?.startsWith("picacg-") == true -> "PicACG"
    sourceId?.startsWith("ehentai-") == true -> "E-Hentai"
    sourceId?.startsWith("exhentai-") == true -> "ExHentai"
    sourceId == com.flux.manga.data.BaoziEndpoint.SOURCE_ID -> "包子漫画"
    sourceId == CopyMangaEndpoint.SOURCE_ID -> "拷贝漫画"
    sourceId?.startsWith("komga") == true -> "Komga"
    else -> "图源"
}

