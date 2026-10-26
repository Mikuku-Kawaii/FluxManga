package com.flux.manga.data

/** Search scope is presentation state, never persisted into a book or its archive. */
enum class LocalSearchField(val label: String, val placeholder: String) {
    ALL("全部字段", "搜索书名、作者、标签、卷号或简介"),
    TITLE("书名", "搜索显示书名或元数据书名"),
    AUTHOR("作者", "搜索作者"),
    VOLUME("卷号", "搜索本地卷号"),
    SUMMARY("简介", "搜索简介"),
}

/** Literal, case-insensitive substring matching; an empty query imposes no text condition. */
fun matchesLocalMetadata(title: String, metadata: ComicInfo?, query: String, field: LocalSearchField): Boolean {
    val term = query.trim()
    if (term.isEmpty()) return true
    fun contains(value: String?) = value?.contains(term, ignoreCase = true) == true
    return when (field) {
        LocalSearchField.ALL -> contains(title) || contains(metadata?.title) || contains(metadata?.author) ||
            contains(metadata?.volume) || contains(metadata?.summary)
        LocalSearchField.TITLE -> contains(title) || contains(metadata?.title)
        LocalSearchField.AUTHOR -> contains(metadata?.author)
        LocalSearchField.VOLUME -> contains(metadata?.volume)
        LocalSearchField.SUMMARY -> contains(metadata?.summary)
    }
}
