package com.flux.manga.ui.pages

import com.flux.manga.core.*
import com.flux.manga.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ReaderEnhancementsTest {
    private val key = SourceMangaKey(SourceWorld.SFW, "source-account-a", "work")
    private val context = SourceContext(SourceWorld.SFW, key.sourceId, "test", 7uL, null)
    private fun chapter(id: String, source: SourceMangaKey = key) = SourceChapter(source, id, "标题 $id", 1u, null)
    private fun page(id: String = "next", source: SourceMangaKey = key) = SourcePageResource(source, id, "page-$id", 1u,
        "synthetic", null, 600u, 900u, context)
    private fun batch(ids: List<String>, next: SourceContinuation = SourceContinuation.End) = SourceChapterPage(context, ids.map { chapter(it) }, next, null)
    private val start = ReaderDirectory(emptyList(), SourceContinuation.Next(SourcePageRequest.Start))
    private suspend fun rejects(type: Class<out Throwable>, block: suspend () -> Unit) {
        try { block(); fail("Expected ${type.simpleName}") } catch (e: Exception) { if (!type.isInstance(e)) throw e }
    }
    @Test fun volumeDefaultIsOffAndOnlyCapturedReleaseTurnsOnce() {
        assertFalse(ReaderOptions().volume)
        val turns = mutableListOf<Int>(); val input = ReaderVolumeDispatcher()
        assertFalse(input.dispatch(25, 0, 0, false))
        input.owner = { turns += it }
        assertFalse(input.dispatch(25, 1, 0, false))
        assertTrue(input.dispatch(25, 0, 0, false))
        repeat(4) { assertTrue(input.dispatch(25, 0, it + 1, false)) }
        assertTrue(turns.isEmpty())
        assertTrue(input.dispatch(25, 1, 0, false)); assertEquals(listOf(1), turns)
        assertFalse(input.dispatch(25, 1, 0, false))
        input.dispatch(24, 0, 0, false); input.dispatch(24, 1, 0, false)
        assertEquals(listOf(1, -1), turns)
    }
    @Test fun cancellationBackgroundAndOwnerReplacementDoNotLeakKeyUps() {
        var count = 0; val input = ReaderVolumeDispatcher().apply { owner = { count++ } }
        input.dispatch(25, 0, 0, false); input.dispatch(25, 1, 0, true)
        input.dispatch(25, 0, 0, false); input.reset(); input.dispatch(25, 1, 0, false)
        input.dispatch(25, 0, 0, false); input.owner = { count += 10 }; input.dispatch(25, 1, 0, false)
        assertEquals(0, count)
        input.owner = null; assertFalse(input.dispatch(24, 0, 0, false))
    }
    @Test fun unrelatedKeysAndRepeatWithoutDownAreNotCaptured() {
        val input = ReaderVolumeDispatcher().apply { owner = { fail("unexpected turn") } }
        assertFalse(input.dispatch(4, 0, 0, false)); assertFalse(input.dispatch(26, 1, 0, false))
        assertFalse(input.dispatch(25, 0, 3, false)); assertFalse(input.dispatch(25, 1, 0, false))
    }
    @Test fun wideSpreadDoesNotChangeReadingModeOrPortraitLayout() {
        for (mode in ReadingMode.entries) {
            assertEquals(mode == ReadingMode.LTR || mode == ReadingMode.RTL, readerSpreadEnabled(true, mode, 800f, 500f))
            assertFalse(readerSpreadEnabled(true, mode, 500f, 800f))
            assertFalse(readerSpreadEnabled(false, mode, 800f, 500f))
        }
        assertFalse(readerSpreadEnabled(true, ReadingMode.LTR, 599f, 400f))
    }
    @Test fun spreadPreservesSecondPageAnchorAndOddLastPageWithoutSkipping() {
        assertEquals(listOf(0, 1), spreadIndices(0, 5, true, false))
        assertEquals(listOf(1, 0), spreadIndices(0, 5, true, true))
        assertEquals(listOf(4), spreadIndices(2, 5, true, true))
        assertEquals(3, settledReaderAnchor(3, 1, true))
        assertEquals(4, settledReaderAnchor(3, 2, true))
        assertEquals(3, settledReaderAnchor(3, 3, false))
    }
    @Test fun zoomClampsToFittedImageInsteadOfPanningIntoLetterboxing() {
        assertEquals(ReaderTransform(2f, 0f, 100f), readerTransform(2f, 999f, 999f, 400f, 200f, 100f, 100f))
        assertEquals(ReaderTransform(), readerTransform(1f, 99f, 90f, 400f, 200f))
        assertEquals(3f, readerTransform(9f, 999f, -999f, 400f, 200f).scale)
        assertEquals(ReaderTransform(), readerTransform(Float.NaN, 0f, 0f, 400f, 200f))
    }
    @Test fun stripBoundaryRequiresExtraDragAndFiresOnlyOnceUntilRelease() {
        val drag = ReaderBoundaryDrag(64f)
        assertFalse(drag.drag(-40f, false)); assertFalse(drag.drag(-40f, true))
        assertTrue(drag.drag(-25f, true)); assertFalse(drag.drag(-100f, true))
        drag.reset(); assertFalse(drag.drag(-40f, true)); assertFalse(drag.drag(10f, true))
        assertFalse(drag.drag(-30f, true)); assertTrue(drag.drag(-35f, true))
    }
    @Test fun adjacencyUsesCatalogOrderNotTitleIdOrOrdinalSorting() = runBlocking {
        val initial = ReaderDirectory(listOf(chapter("10"), chapter("2"), chapter("bonus")), SourceContinuation.End)
        assertEquals("bonus", adjacentReaderChapter(key, "2", 1, initial, { error("no fetch") })?.id)
        assertEquals("10", adjacentReaderChapter(key, "2", -1, initial, { error("no fetch") })?.id)
        assertNull(adjacentReaderChapter(key, "bonus", 1, initial, { error("no fetch") }))
        assertNull(adjacentReaderChapter(key, "10", -1, initial, { error("no fetch") }))
    }
    @Test fun findsCurrentChapterAcrossPagesAndKeepsSuccessfulPagesForRetry() = runBlocking {
        var snapshot = start; var calls = 0
        rejects(IOException::class.java) {
            adjacentReaderChapter(key, "current", 1, snapshot, {
                calls++
                if (it == SourcePageRequest.Start) batch(listOf("before", "current"), SourceContinuation.Next(SourcePageRequest.Page(2u)))
                else throw IOException("offline")
            }, { snapshot = it })
        }
        assertEquals(2, calls); assertEquals(listOf("before", "current"), snapshot.chapters.map { it.id })
        assertEquals("next", adjacentReaderChapter(key, "current", 1, snapshot, {
            assertEquals(SourcePageRequest.Page(2u), it); batch(listOf("next"))
        })?.id)
    }
    @Test fun repeatedCursorAndNoProgressStopInsteadOfLooping() = runBlocking {
        var calls = 0
        rejects(IllegalArgumentException::class.java) {
            adjacentReaderChapter(key, "missing", 1, start, {
                calls++; batch(listOf("$calls"), SourceContinuation.Next(SourcePageRequest.Start))
            })
        }
        assertEquals(1, calls)
        rejects(IllegalArgumentException::class.java) {
            adjacentReaderChapter(key, "current", 1, ReaderDirectory(listOf(chapter("current")), SourceContinuation.Next(SourcePageRequest.Start)), {
                batch(listOf("current"), SourceContinuation.Next(SourcePageRequest.Page(2u)))
            })
        }
    }
    @Test fun boundedLookupDoesNotFetchAnUnboundedDirectory() = runBlocking {
        var calls = 0
        rejects(IllegalStateException::class.java) {
            adjacentReaderChapter(key, "not-found", 1, start, {
                calls++; batch(listOf("$calls"), SourceContinuation.Next(SourcePageRequest.Page((calls + 1).toUInt())))
            })
        }
        assertEquals(128, calls)
    }
    @Test fun foreignSourceAndUnknownCurrentAreNotTreatedAsChapterEnd() = runBlocking {
        rejects(IllegalArgumentException::class.java) {
            adjacentReaderChapter(key, "current", 1, start, {
                SourceChapterPage(context, listOf(chapter("current", key.copy(sourceId = "other-account"))), SourceContinuation.End, null)
            })
        }
        rejects(IllegalArgumentException::class.java) {
            adjacentReaderChapter(key, "missing", 1, ReaderDirectory(listOf(chapter("first")), SourceContinuation.End), { error("no fetch") })
        }
    }
    @Test fun cancelledDirectoryDoesNotPublishLateResults() = runBlocking {
        var active = true; var published = false
        rejects(CancellationException::class.java) {
            adjacentReaderChapter(key, "current", 1, start, { active = false; batch(listOf("current", "next")) },
                { published = true }, { if (!active) throw CancellationException() })
        }
        assertFalse(published)
    }
    @Test fun offlineAdjacencyRequiresCompleteSameSourceCatalog() {
        assertThrows(IOException::class.java) { completeReaderCatalog(null, key.sourceId, key.mangaId) }
        val catalog = OfflineCatalog(key.sourceId, key.mangaId, false, listOf(chapter("a")))
        assertThrows(IOException::class.java) { completeReaderCatalog(catalog, key.sourceId, key.mangaId) }
        assertThrows(IllegalArgumentException::class.java) { completeReaderCatalog(catalog.copy(complete = true), "other", key.mangaId) }
        assertEquals(catalog.chapters, completeReaderCatalog(catalog.copy(complete = true), key.sourceId, key.mangaId))
    }
    @Test fun chapterPreparationSavesBeforeFetchingAndOnlyReleasesAfterValidation() = runBlocking {
        val order = mutableListOf<String>()
        val result = prepareReaderChapter(key, "next", { order += "save"; true }, { order += "fetch"; listOf(page()) }, { order += "release" }, {})
        assertEquals(listOf("save", "fetch", "release"), order); assertEquals(listOf(page()), result)
    }
    @Test fun saveFailureNeverLoadsNewChapterOrReleasesOldImages() = runBlocking {
        assertNull(prepareReaderChapter(key, "next", { false }, { error("must not fetch") }, { error("must not release") }, {}))
    }
    @Test fun failedEmptyAndForeignChapterKeepExistingImagesAndAnchor() = runBlocking {
        var released = false
        rejects(IOException::class.java) { prepareReaderChapter(key, "next", { true }, { throw IOException() }, { released = true }, {}) }
        rejects(IllegalArgumentException::class.java) { prepareReaderChapter(key, "next", { true }, { emptyList() }, { released = true }, {}) }
        rejects(IllegalArgumentException::class.java) { prepareReaderChapter(key, "next", { true }, { listOf(page("other")) }, { released = true }, {}) }
        assertFalse(released)
    }
    @Test fun sessionInvalidationAfterLoadCannotCommitTargetOrReleaseOldImages() = runBlocking {
        var active = true; var released = false
        rejects(CancellationException::class.java) {
            prepareReaderChapter(key, "next", { true }, { active = false; listOf(page()) }, { released = true }, { if (!active) throw CancellationException() })
        }
        assertFalse(released)
    }
}
