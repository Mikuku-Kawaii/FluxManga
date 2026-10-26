package com.flux.manga.ui.pages

import com.flux.manga.R

/** Decoration only: never rewrite tag names, source IDs or query targets. */
internal enum class TopicArtwork(val resource: Int) {
    FANTASY(R.drawable.topic_fantasy), DAILY(R.drawable.topic_daily), ADVENTURE(R.drawable.topic_adventure),
    SCIFI(R.drawable.topic_scifi), MYSTERY(R.drawable.topic_mystery), YOUTH(R.drawable.topic_youth), BOOKS(R.drawable.topic_books)
}

internal fun topicArtwork(name: String): TopicArtwork = when (name) {
    "奇幻" -> TopicArtwork.FANTASY
    "日常" -> TopicArtwork.DAILY
    "冒险" -> TopicArtwork.ADVENTURE
    "科幻" -> TopicArtwork.SCIFI
    "悬疑" -> TopicArtwork.MYSTERY
    "青春" -> TopicArtwork.YOUTH
    else -> TopicArtwork.BOOKS
}
