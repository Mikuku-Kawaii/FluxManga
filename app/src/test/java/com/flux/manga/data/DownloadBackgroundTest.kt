package com.flux.manga.data

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DownloadBackgroundTest {
    @Test fun staleOrDeniedPlatformHandoffCannotGrantExecution() {
        val lease = DownloadBackgroundLease()
        val old = lease.begin()
        assertFalse(lease.running)
        lease.revoke()
        assertFalse(lease.activate(old, true))
        val next = lease.begin()
        assertFalse(lease.activate(next, false))
        assertTrue(lease.activate(next, true))
        assertFalse(lease.accepts(old)); assertTrue(lease.accepts(next))
        lease.revoke()
        assertFalse(lease.running); assertFalse(lease.accepts(next))
    }
    private fun job(status: DownloadStatus, pages: List<DownloadPage> = emptyList()) =
        ChapterDownload(UUID.randomUUID().toString(), "secret-account", "private-work", "chapter", "private-title", status, pages, "raw-token-error")

    @Test fun noticeKeepsUnknownTotalsIndeterminateAndUsesOnlyCommittedPageCounts() {
        val unknown = downloadNotice(DownloadQueueState(true, listOf(job(DownloadStatus.RUNNING))))
        assertTrue(unknown.working); assertEquals(0, unknown.total); assertEquals(0, unknown.saved)
        val pages = listOf(DownloadPage("p1", 8, "a".repeat(64)), DownloadPage("p2"))
        val known = downloadNotice(DownloadQueueState(true, listOf(job(DownloadStatus.RUNNING, pages), job(DownloadStatus.QUEUED))))
        assertEquals(2, known.total); assertEquals(1, known.saved); assertTrue(known.text.contains("等待 1"))
        for (value in listOf(unknown.text, known.text)) {
            assertFalse(value.contains("private")); assertFalse(value.contains("secret")); assertFalse(value.contains("token"))
        }
    }
    @Test fun partialFailuresAndUnconfirmedQueueNeverAppearAsAllCompleted() {
        val summary = downloadNotice(DownloadQueueState(true, listOf(job(DownloadStatus.FAILED), job(DownloadStatus.PAUSED), job(DownloadStatus.CANCELLED))))
        assertFalse(summary.working); assertTrue(summary.text.contains("完成 0")); assertTrue(summary.text.contains("失败 1"))
        assertFalse(summary.text.contains("token"))
        val pending = downloadNotice(DownloadQueueState(false, listOf(job(DownloadStatus.RUNNING))))
        assertFalse(pending.working); assertTrue(pending.text.contains("核对"))
    }
    @Test fun queueControlDoesNotBrieflyStopForegroundServiceBetweenChapters() {
        assertTrue(downloadNotice(DownloadQueueState(true, listOf(job(DownloadStatus.QUEUED)))).working)
        assertTrue(downloadNotice(DownloadQueueState(true, listOf(job(DownloadStatus.PAUSED)), controlling = true)).working)
        assertFalse(downloadNotice(DownloadQueueState(true, emptyList())).working)
    }
}
