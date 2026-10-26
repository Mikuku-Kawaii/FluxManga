package com.flux.manga.data

import com.flux.manga.core.*
import com.flux.manga.ui.pages.*
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OfflineCatalogTest {
    private fun chapter(source: String="a",id: String="original-7",ordinal: UInt=3u) = SourceChapter(SourceMangaKey(SourceWorld.SFW,source,"work"),id,"合成章节",ordinal,null)
    private fun job(source: String,id: String,status: DownloadStatus) = ChapterDownload(UUID.randomUUID().toString(),source,"work",id,"合成",status,
        if(status==DownloadStatus.COMPLETE) listOf(DownloadPage("page",8,"a".repeat(64))) else emptyList())
    @Test fun catalogRoundtripPreservesOriginalIdsOrderingAndUnknownPageCounts() {
        val value=OfflineCatalog("a","work",true,listOf(chapter(),chapter(id="original-1",ordinal=1u)))
        assertEquals(value,OfflineCatalogCodec.decode(OfflineCatalogCodec.encode(value)))
        try { OfflineCatalogCodec.encode(value.copy(chapters=listOf(chapter("b"))));fail() } catch(_: IllegalArgumentException) { }
        try { OfflineCatalogCodec.decode(OfflineCatalogCodec.encode(value)+byteArrayOf(0));fail() } catch(_: IllegalArgumentException) { }
    }
    @Test fun interruptedRefreshKeepsCompleteCatalogAndAccountNamespacesSeparate() = runBlocking {
        val store=OfflineCatalogStore(DownloadDb());val complete=OfflineCatalog("a","work",true,listOf(chapter(),chapter(id="last")))
        store.save(complete) { true };store.save(complete.copy(complete=false,chapters=listOf(chapter()))) { true }
        store.save(OfflineCatalog("b","work",false,listOf(chapter("b")))) { true }
        assertEquals(complete,store.load("a","work") { true });assertFalse(store.load("b","work") { true }!!.complete)
        val refreshed=complete.copy(chapters=listOf(chapter(id="new")))
        store.save(refreshed) { true };assertEquals(refreshed,store.load("a","work") { true })
    }
    @Test fun partialCatalogDoesNotHideDownloadedChapterOrMixAnotherAccount() {
        val catalog=OfflineCatalog("a","work",false,listOf(chapter()))
        val jobs=listOf(job("a","extra",DownloadStatus.COMPLETE),job("b","foreign",DownloadStatus.COMPLETE),job("a","partial",DownloadStatus.PAUSED))
        assertEquals(listOf("original-7","extra"),offlineChapters("a","work",catalog,jobs).map { it.id })
        assertEquals(listOf("extra"),offlineChapters("a","work",null,jobs).map { it.id })
    }
    @Test fun shelfDownloadFilterUsesCompleteSourceKeysWithoutChangingFavoriteOrProgress() {
        val books=listOf("a","b").map { ShelfBook(MangaKey(PrototypeWorld.SFW,it,"work"),"same",false,false,null,9,"old",15,true) }
        val result=withShelfDownloads(books,listOf(job("a","first",DownloadStatus.COMPLETE),job("b","first",DownloadStatus.FAILED)))
        assertEquals(1,result[0].downloadedChapters);assertEquals(0,result[1].downloadedChapters)
        assertEquals(listOf(result[0]),filterShelfDownloads(result,ShelfDownloadFilter.SAVED))
        assertEquals(listOf(result[1]),filterShelfDownloads(result,ShelfDownloadFilter.PENDING))
        assertTrue(result.all { !it.favorite && it.ordinal==9 && it.chapterId=="old" })
    }
    @Test fun networkPolicyDoesNotTreatAllConnectionsAsUnmetered() {
        assertNotNull(downloadNetworkBlock(DownloadNetworkPolicy.UNMETERED,true,false))
        assertNull(downloadNetworkBlock(DownloadNetworkPolicy.UNMETERED,true,true))
        assertNull(downloadNetworkBlock(DownloadNetworkPolicy.ANY,true,false))
        assertNotNull(downloadNetworkBlock(DownloadNetworkPolicy.ANY,false,true))
    }
}
