package com.flux.manga.ui.pages

import com.flux.manga.data.*
import org.junit.Assert.*
import org.junit.Test

class DownloadUiTest {
    private val jobs = DownloadStatus.entries.mapIndexed { i, status ->
        ChapterDownload("job-$i", "source-$i", "manga", "chapter", "Same Title", status)
    }
    @Test fun statusFiltersCoverEveryActualStateWithoutOverlap() {
        for (job in jobs) {
            assertEquals(1, DownloadQueueFilter.entries.filter { it != DownloadQueueFilter.ALL }.count { it.accepts(job.status) })
            assertTrue(DownloadQueueFilter.ALL.accepts(job.status))
        }
        assertEquals(listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING),
            filterDownloadJobs(jobs, DownloadQueueFilter.ACTIVE, "").map { it.status })
    }
    @Test fun sameTitlesAcrossSourcesRemainSeparateAndOrdered() {
        val result = filterDownloadJobs(jobs, DownloadQueueFilter.ALL, "  SAME ")
        assertEquals(jobs.map { it.id }, result.map { it.id })
        assertEquals(jobs.map { it.source }, result.map { it.source })
        assertEquals(jobs, result)
    }
    @Test fun titleAndStateConditionsCombineWithoutChangingInput() {
        assertEquals(listOf(jobs[3]), filterDownloadJobs(jobs, DownloadQueueFilter.FAILED, "title"))
        assertTrue(filterDownloadJobs(jobs, DownloadQueueFilter.FAILED, "missing").isEmpty())
        assertTrue(downloadTitleMatches("第十二章 回家", " 回家 "))
        assertTrue(downloadTitleMatches("第十二章", "  "))
        assertEquals(DownloadStatus.entries.toList(), jobs.map { it.status })
    }
    @Test fun appendSelectionPreservesHiddenIdsAndCapsAtTwoHundred() {
        val selected = (1..199).map { "hidden-$it" }
        val result = addDownloadSelection(selected, listOf("hidden-1", "visible-1", "visible-2"))
        assertEquals(selected + "visible-1", result)
        assertEquals(200, result.size)
        assertEquals(result, addDownloadSelection(result, listOf("visible-2")))
    }
    @Test fun duplicateChapterIdsDoNotConsumeSelectionCapacity() {
        assertEquals(listOf("a", "b", "c"), addDownloadSelection(listOf("a", "b"), listOf("b", "c", "c")))
        assertEquals(listOf("a"), addDownloadSelection(listOf("a"), emptyList()))
        assertEquals(200, addDownloadSelection(emptyList(), (1..500).map { "$it" }).size)
    }
    @Test fun hiddenCountsDescribeAllSelectedOperations() {
        assertEquals(2, hiddenDownloadSelection(listOf("a", "b", "c"), listOf("b")))
        assertEquals(0, hiddenDownloadSelection(listOf("a", "b"), listOf("a", "b", "c")))
        assertEquals(2, hiddenDownloadSelection(listOf("a", "b"), emptyList()))
    }
    @Test fun unknownInvalidOrEmptyTotalsNeverProducePercentages() {
        assertNull(transferFraction(3, null)); assertNull(transferFraction(0, 0))
        assertNull(transferFraction(-1, 10)); assertNull(transferFraction(11, 10))
        assertNull(transferFraction(0, -1))
    }
    @Test fun byteAndPageProgressHandlesLargeTotalsWithoutIntegerOverflow() {
        assertEquals(.5f, transferFraction(1, 2)!!, 0f)
        assertEquals(.5f, transferFraction(5_000_000_000, 10_000_000_000)!!, 0f)
        assertEquals(1f, transferFraction(Long.MAX_VALUE, Long.MAX_VALUE)!!, 0f)
        assertEquals(0f, transferFraction(0, 20)!!, 0f)
    }
}
