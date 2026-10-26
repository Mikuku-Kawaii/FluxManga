package com.flux.manga.data

import com.flux.manga.session.SessionIdentity
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.delay
import java.io.IOException

enum class SampleScenario { CONTENT, EMPTY, ERROR }
data class SampleManga(val world: PrototypeWorld, val sourceId: String, val id: String, val title: String)
data class SampleChapter(val manga: SampleManga, val chapterId: String, val pages: Int)
class SampleLoadException : Exception("固定样本加载失败，请重试")

/** Fixed catalog comes from per-world Room databases; no network or real content. */
class SampleRepository(private val store: WorldDataStore) {
    fun clearMemory() = store.clearMemory()
    private suspend fun <T> storage(block: suspend () -> T): T = try { block() }
        catch (_: IOException) { throw SampleLoadException() }
    suspend fun history(session: SessionIdentity) = storage { store.history(session.world, "sample") }
    suspend fun remember(session: SessionIdentity, query: String, allowed: () -> Boolean) = storage { store.remember(session.world, "sample", query, allowed) }
    suspend fun clearHistory(session: SessionIdentity, allowed: () -> Boolean) = storage { store.clearHistory(session.world, "sample", allowed) }
    private suspend fun available(session: SessionIdentity, scenario: SampleScenario): Boolean {
        require(session.generation >= 0)
        delay(400) // Makes loading/replacement observable with a reproducible local sample.
        if (scenario == SampleScenario.ERROR) throw SampleLoadException()
        return scenario != SampleScenario.EMPTY
    }

    suspend fun list(session: SessionIdentity, query: String, scenario: SampleScenario): List<SampleManga>? {
        if (!available(session, scenario)) return null
        return storage { store.catalog(session.world) }
            .filter { query.isBlank() || "${it.title} orbit".contains(query.trim(), ignoreCase = true) }
            .takeIf { it.isNotEmpty() }
    }

    suspend fun detail(session: SessionIdentity, world: PrototypeWorld, source: String, id: String, scenario: SampleScenario): SampleManga? {
        if (!available(session, scenario) || world != session.world || source != "sample" || id.toIntOrNull() !in 1..60) return null
        return storage { store.catalog(world, source).firstOrNull { it.id == id } }
    }

    suspend fun chapter(session: SessionIdentity, world: PrototypeWorld, source: String, mangaId: String, chapterId: String, scenario: SampleScenario): SampleChapter? {
        val manga = detail(session, world, source, mangaId, scenario) ?: return null
        return if (chapterId == "chapter-1") SampleChapter(manga, chapterId, 3) else null
    }
}

