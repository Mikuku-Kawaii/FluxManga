package com.flux.manga.data

/** Author links use the existing query field and history, without a second search mode UI. */
internal fun authorSearchName(query: String): String? {
    val text = query.trim()
    val prefix = listOf("作者：", "作者:", "author:").firstOrNull { text.startsWith(it, ignoreCase = true) }
        ?: return null
    return text.substring(prefix.length).trim().takeIf(String::isNotEmpty)
}
