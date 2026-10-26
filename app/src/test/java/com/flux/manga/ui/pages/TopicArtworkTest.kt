package com.flux.manga.ui.pages

import org.junit.Assert.*
import org.junit.Test

class TopicArtworkTest {
    @Test fun sixExplicitTopicsHaveDistinctArtwork() {
        val names = listOf("奇幻", "日常", "冒险", "科幻", "悬疑", "青春")
        assertEquals(TopicArtwork.entries.filter { it != TopicArtwork.BOOKS }, names.map(::topicArtwork))
        assertEquals(6, names.map { topicArtwork(it).resource }.distinct().size)
    }
    @Test fun unknownNamesDoNotGetGuessedOrNormalizedIntoCategories() {
        listOf("科幻小说", "自定义题材", "SciFi", "scifi", "奇幻 ", "", "冒险/日常").forEach {
            assertEquals(TopicArtwork.BOOKS, topicArtwork(it))
        }
    }
    @Test fun decorationDoesNotAddMissingTopicsOrMergeNames() {
        val names = listOf("科幻小说", "科幻", "SciFi", "scifi", "奇幻 ")
        val chosen = featuredTags(names)
        val before = chosen.toList()
        chosen.map(::topicArtwork)
        assertEquals(before, chosen)
        assertEquals(names.toSet(), chosen.toSet())
        assertFalse(chosen.contains("日常"))
        assertTrue(featuredTags(emptyList()).map(::topicArtwork).isEmpty())
    }
}
