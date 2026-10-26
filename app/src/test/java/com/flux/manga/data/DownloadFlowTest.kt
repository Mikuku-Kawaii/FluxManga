package com.flux.manga.data

import com.flux.manga.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

/** Transactional fake shares the production codec/store/controller; no Android/real account IO. */
internal class DownloadDb : AccountImportDatabase {
    val secrets = mutableMapOf<String, ByteArray>()
    val books = mutableMapOf<Pair<String, String>, OnlineBook>()
    val progress = mutableSetOf<Pair<String, String>>()
    private val lock = Mutex()
    var beforeCommit: (() -> Unit)? = null
    var afterCommit: (suspend () -> Unit)? = null
    override suspend fun <T> transaction(allowed: () -> Boolean, action: (AccountImportRows) -> T): T {
        val result = lock.withLock {
            currentCoroutineContext().ensureActive()
            if (!allowed()) throw CancellationException()
            val nextSecrets = secrets.mapValues { it.value.copyOf() }.toMutableMap()
            val nextBooks = books.toMutableMap()
            val result = action(object : AccountImportRows {
                override fun read(slot: String) = nextSecrets[slot]?.copyOf()
                override fun write(slot: String, value: ByteArray) { nextSecrets[slot] = value.copyOf() }
                override fun book(source: String, id: String) = nextBooks[source to id]
                override fun hasProgress(source: String, id: String) = source to id in progress
                override fun putBook(book: OnlineBook) { nextBooks[book.sourceId to book.mangaId] = book }
            })
            beforeCommit?.invoke()
            if (!allowed()) throw CancellationException()
            secrets.clear(); secrets.putAll(nextSecrets); books.clear(); books.putAll(nextBooks)
            result
        }
        afterCommit?.invoke()
        return result
    }
}

private fun page(source: String, manga: String, chapter: String, n: Int): SourcePageResource {
    val context=SourceContext(SourceWorld.SFW,source,"synthetic",1uL,null)
    return SourcePageResource(SourceMangaKey(SourceWorld.SFW,source,manga),chapter,"p$n",(n+1).toUInt(),"fixture:$n",null,null,null,context)
}
private class DownloadNet(private val directory: java.io.File) : DownloadNetwork {
    var fetched=mutableListOf<Int>(); var closed=0; var failAt: Int?=null
    var gate: CompletableDeferred<Unit>?=null; var changed=false
    override suspend fun pages(job: ChapterDownload)=List(3) { page(job.source,job.manga,job.chapter,it).let { p -> if(changed) p.copy(id="changed${p.id}") else p } }
    override suspend fun image(job: ChapterDownload,page: SourcePageResource,directory: java.io.File): DownloadImage {
        val n=page.ordinal.toInt()-1; fetched+=n; if(n==failAt) throw IOException("synthetic failure")
        gate?.await()
        val file=java.io.File(this.directory,"network-${UUID.randomUUID()}").apply { writeBytes("synthetic-$n".toByteArray()) }
        return DownloadImage(file) { closed++; file.delete() }
    }
}
class DownloadFlowTest {
    private suspend fun settled(c: DownloadController) { withTimeout(5000) { while(!c.state.value.loaded || c.state.value.controlling || c.state.value.jobs.any { it.status in listOf(DownloadStatus.QUEUED,DownloadStatus.RUNNING) }) delay(5) } }
    @Test fun queueCodecAndIdentityPreserveFullKeysAndDoNotPersistUrls() = runBlocking {
        val db=DownloadDb(); val store=DownloadStore(db)
        store.enqueue("sourceA","work",listOf("chapter" to "合成")) { true }
        store.enqueue("sourceA","work",listOf("chapter" to "changed")) { true }
        store.enqueue("sourceB","work",listOf("chapter" to "合成")) { true }
        val jobs=store.list { true }; assertEquals(2,jobs.size); assertEquals(jobs,DownloadCodec.decode(DownloadCodec.encode(jobs)))
        assertEquals("合成",jobs[0].title); assertNotEquals(jobs[0].key,jobs[1].key)
        try { DownloadCodec.decode(DownloadCodec.encode(jobs)+1.toByte()); fail() } catch(_: Exception) { }
        var allowed=true; db.beforeCommit={allowed=false}
        try { store.enqueue("sourceC","work",listOf("chapter" to "合成")) { allowed }; fail() } catch(_: CancellationException) { }
        db.beforeCommit=null; assertEquals(2,store.list { true }.size)
    }
    @Test fun filesStreamHashAndClearOnlyOwnPagesPreservingOriginalAndNeighbor() {
        val root=java.nio.file.Files.createTempDirectory("flux-download").toFile()
        try {
            val files=DownloadFiles(root); var job=ChapterDownload(UUID.randomUUID().toString(),"s","m","c","合成",pages=listOf(DownloadPage("p0")))
            val original=java.io.File(root,"original.cbz").apply { writeText("original") }
            val neighbor=java.io.File(root,"neighbor").apply { mkdirs() }; java.io.File(neighbor,"0.page").writeText("neighbor")
            val saved=files.save(job,0,original) { true }; job=job.copy(pages=listOf(saved))
            assertTrue(files.valid(job,0)); assertEquals(8,saved.bytes.toInt())
            files.file(job,0).writeText("corrupt!"); assertFalse(files.valid(job,0))
            files.clear(job); assertEquals("original",original.readText()); assertEquals("neighbor",java.io.File(neighbor,"0.page").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun insufficientSpaceAndCancellationLeaveNoPartOrOriginalChanges() {
        val root=java.nio.file.Files.createTempDirectory("flux-download").toFile()
        try {
            val original=java.io.File(root,"original.cbz").apply { writeText("original") }
            val job=ChapterDownload(UUID.randomUUID().toString(),"s","m","c","合成",pages=listOf(DownloadPage("p0")))
            val small=DownloadFiles(root) { 0 }
            try { small.save(job,0,original) { true }; fail() } catch(e: IOException) { assertTrue(e.message!!.contains("空间不足")) }
            val files=DownloadFiles(root)
            try { files.save(job,0,original) { false }; fail() } catch(_: CancellationException) { }
            assertFalse(java.io.File(files.directory(job),"0.part").exists()); assertFalse(files.file(job,0).exists()); assertEquals("original",original.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun failedPageResumesWithoutRedownloadingVerifiedPagesAndDeleteKeepsBooks() = runBlocking {
        val root=java.nio.file.Files.createTempDirectory("flux-download").toFile(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val db=DownloadDb(); db.books["s" to "m"]=OnlineBook("s","m","original",true,7); db.progress+="s" to "m"
            val store=DownloadStore(db); val files=DownloadFiles(root); val net=DownloadNet(root); net.failAt=1
            val c=DownloadController(scope,{true},store,files,net); c.start(); c.enqueue("s","m",listOf("c" to "chapter")); settled(c)
            val failed=c.state.value.jobs.single(); assertEquals(DownloadStatus.FAILED,failed.status); assertEquals(1,failed.completed)
            net.failAt=null; c.resume(failed.id); settled(c)
            val complete=c.state.value.jobs.single(); assertEquals(DownloadStatus.COMPLETE,complete.status); assertEquals(listOf(0,1,1,2),net.fetched)
            assertTrue(complete.pages.indices.all { files.valid(complete,it) }); assertEquals(3,net.closed)
            c.stop(complete.id,true); settled(c); assertTrue(c.state.value.jobs.isEmpty()); assertEquals("original",db.books["s" to "m"]!!.title); assertTrue(db.progress.contains("s" to "m"))
        } finally { scope.cancel(); root.deleteRecursively() }
    }
    @Test fun restartPausesPersistedQueueAndExplicitResumeChecksHashesAndPageOrder() = runBlocking {
        val root=java.nio.file.Files.createTempDirectory("flux-download").toFile(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val store=DownloadStore(DownloadDb()); val job=store.enqueue("s","m",listOf("c" to "chapter")) { true }.single()
            val net=DownloadNet(root); val files=DownloadFiles(root); val c=DownloadController(scope,{true},store,files,net)
            c.start(); assertEquals(DownloadStatus.PAUSED,c.state.value.jobs.single().status); assertTrue(net.fetched.isEmpty())
            net.failAt=1; c.resume(job.id); settled(c)
            val partial=c.state.value.jobs.single(); files.file(partial,0).writeText("corrupt")
            net.failAt=null; net.changed=true; c.resume(job.id); settled(c)
            assertEquals(DownloadStatus.FAILED,c.state.value.jobs.single().status); assertEquals(listOf(0,1),net.fetched)
            net.changed=false; c.resume(job.id); settled(c); assertEquals(listOf(0,1,0,1,2),net.fetched)
        } finally { scope.cancel(); root.deleteRecursively() }
    }
    @Test fun pauseCancelAndWorldInvalidationNeverPublishCompletedOrDeleteOriginal() = runBlocking {
        val root=java.nio.file.Files.createTempDirectory("flux-download").toFile(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val store=DownloadStore(DownloadDb()); val net=DownloadNet(root); net.gate=CompletableDeferred(); var active=true
            val c=DownloadController(scope,{active},store,DownloadFiles(root),net); c.start(); c.enqueue("s","m",listOf("c" to "chapter"))
            withTimeout(5000) { while(net.fetched.isEmpty()) delay(5) }
            val id=c.state.value.jobs.single().id
            c.stop(id); settled(c); assertEquals(DownloadStatus.PAUSED,c.state.value.jobs.single().status)
            c.stop(id,true); settled(c); assertEquals(DownloadStatus.CANCELLED,c.state.value.jobs.single().status)
            c.stop(id,true); settled(c); assertTrue(c.state.value.jobs.isEmpty())
            c.enqueue("s","m",listOf("c" to "chapter")); withTimeout(5000) { while(net.fetched.size<2) delay(5) }
            active=false; c.close(); net.gate!!.complete(Unit); delay(20)
            assertTrue(c.state.value.jobs.isEmpty()); assertNotEquals(DownloadStatus.COMPLETE,store.list { true }.single().status)
        } finally { scope.cancel(); root.deleteRecursively() }
    }
    @Test fun offlineResourcesKeepOriginalPageIdsOneBasedOrdinalsAndNewGeneration() {
        val job=ChapterDownload(UUID.randomUUID().toString(),"source","work","chapter","合成",DownloadStatus.COMPLETE,
            listOf(DownloadPage("original-page-a",7,"a".repeat(64)),DownloadPage("original-page-b",8,"b".repeat(64))))
        val context=SourceContext(SourceWorld.SFW,"source","new-process",9uL,null)
        val pages=downloadedResources(job,context)
        assertEquals(listOf("original-page-a","original-page-b"),pages.map { it.id }); assertEquals(listOf(1u,2u),pages.map { it.ordinal })
        assertTrue(pages.all { it.context==context && it.chapterId=="chapter" && !it.uri.startsWith("http") })
        try { downloadedResources(job,context.copy(sourceId="other")); fail() } catch(_: IllegalArgumentException) { }
        try { downloadedResources(job.copy(status=DownloadStatus.PAUSED),context); fail() } catch(_: IllegalArgumentException) { }
    }
    @Test fun providerInvalidationPausesInsteadOfLeavingAnUncontrollableRunningJob() = runBlocking {
        val root=java.nio.file.Files.createTempDirectory("flux-download").toFile(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val network=object: DownloadNetwork {
                override suspend fun pages(job: ChapterDownload): List<SourcePageResource> = throw CancellationException("source changed")
                override suspend fun image(job: ChapterDownload,page: SourcePageResource,directory: java.io.File): DownloadImage = error("unreachable")
            }
            val c=DownloadController(scope,{true},DownloadStore(DownloadDb()),DownloadFiles(root),network)
            c.start(); c.enqueue("s","m",listOf("c" to "chapter")); settled(c)
            assertEquals(DownloadStatus.PAUSED,c.state.value.jobs.single().status); assertNotNull(c.state.value.jobs.single().error)
        } finally { scope.cancel(); root.deleteRecursively() }
    }
    @Test fun cancellingAnotherQueuedChapterDoesNotLeaveTheActiveChapterPaused() = runBlocking {
        val root=java.nio.file.Files.createTempDirectory("flux-download").toFile(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val store=DownloadStore(DownloadDb()); val net=DownloadNet(root); net.gate=CompletableDeferred()
            val c=DownloadController(scope,{true},store,DownloadFiles(root),net)
            c.start(); c.enqueue("s","m",listOf("a" to "a","b" to "b"))
            withTimeout(5000) { while(net.fetched.isEmpty()) delay(5) }
            val b=c.state.value.jobs.first { it.chapter=="b" }; c.stop(b.id,true)
            withTimeout(5000) { while(c.state.value.controlling) delay(5) }
            assertEquals(DownloadStatus.CANCELLED,c.state.value.jobs.first { it.chapter=="b" }.status)
            net.gate!!.complete(Unit); settled(c)
            assertEquals(DownloadStatus.COMPLETE,c.state.value.jobs.first { it.chapter=="a" }.status)
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun foregroundRecoveryWaitsForCancelledFileWorkToReleaseItsDirectory() = runBlocking {
        val root=java.nio.file.Files.createTempDirectory("flux-download").toFile(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val release=CompletableDeferred<Unit>(); val entered=CompletableDeferred<Unit>()
        try {
            val store=DownloadStore(DownloadDb())
            val network=object: DownloadNetwork {
                override suspend fun pages(job: ChapterDownload)=listOf(page(job.source,job.manga,job.chapter,0))
                override suspend fun image(job: ChapterDownload,page: SourcePageResource,directory: java.io.File): DownloadImage {
                    entered.complete(Unit)
                    // Models the non-suspending portion of a native/file operation while cancellation unwinds.
                    withContext(NonCancellable) { release.await() }
                    currentCoroutineContext().ensureActive()
                    error("cancelled work must not publish")
                }
            }
            val c=DownloadController(scope,{true},store,DownloadFiles(root),network)
            c.start(); c.enqueue("s","m",listOf("c" to "chapter")); withTimeout(5000) { entered.await() }
            c.pauseAll(); c.start(); delay(20)
            assertFalse(c.state.value.loaded); assertTrue(c.state.value.controlling)
            release.complete(Unit); settled(c)
            assertEquals(DownloadStatus.PAUSED,c.state.value.jobs.single().status)
            assertEquals(0,c.state.value.jobs.single().completed)
        } finally { release.complete(Unit); scope.cancel(); root.deleteRecursively() }
    }

    @Test fun grantedBackgroundQueueContinuesAndRevocationKeepsExplicitRecovery() = runBlocking {
        val root=java.nio.file.Files.createTempDirectory("flux-download").toFile(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val store=DownloadStore(DownloadDb()); val net=DownloadNet(root); net.gate=CompletableDeferred()
            val lease=DownloadBackgroundLease(); var foreground=true; var sessionValid=true
            val c=DownloadController(scope,{sessionValid && (foreground || lease.running)},store,DownloadFiles(root),net)
            c.start(); c.enqueue("s","m",listOf("a" to "a"))
            withTimeout(5000) { while(net.fetched.isEmpty()) delay(5) }
            val ticket=lease.begin(); assertTrue(lease.activate(ticket,true)); foreground=false
            net.gate!!.complete(Unit); settled(c)
            assertEquals(DownloadStatus.COMPLETE,c.state.value.jobs.single().status)
            net.gate=CompletableDeferred(); c.enqueue("s","m",listOf("b" to "b"))
            withTimeout(5000) { while(net.fetched.size<4) delay(5) }
            lease.revoke(); sessionValid=false; c.pauseAll()
            net.gate!!.complete(Unit); delay(20)
            assertNotEquals(DownloadStatus.COMPLETE,store.list { true }.first { it.chapter=="b" }.status)
            foreground=true; sessionValid=true; c.start(); settled(c)
            assertEquals(DownloadStatus.PAUSED,c.state.value.jobs.first { it.chapter=="b" }.status)
            assertEquals(4,net.fetched.size)
            assertEquals(DownloadStatus.COMPLETE,c.state.value.jobs.first { it.chapter=="a" }.status)
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun networkWaitResumesOnlyQueuedWorkAndDoesNotResumeManualPause() = runBlocking {
        val root=java.nio.file.Files.createTempDirectory("flux-network").toFile(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val net=DownloadNet(root); val c=DownloadController(scope,{true},DownloadStore(DownloadDb()),DownloadFiles(root),net)
            c.networkChanged("等待非计费网络"); c.start(); c.enqueue("s","m",listOf("a" to "a","b" to "b")); delay(20)
            assertTrue(net.fetched.isEmpty()); assertEquals(2,c.state.value.jobs.size)
            val b=c.state.value.jobs.first { it.chapter=="b" }; c.batch(setOf(b.id),DownloadBatchAction.PAUSE)
            withTimeout(5000) { while(c.state.value.controlling) delay(5) }
            c.networkChanged(null); settled(c)
            assertEquals(DownloadStatus.COMPLETE,c.state.value.jobs.first { it.chapter=="a" }.status)
            assertEquals(DownloadStatus.PAUSED,c.state.value.jobs.first { it.chapter=="b" }.status)
            assertEquals(listOf(0,1,2),net.fetched)
        } finally { scope.cancel(); root.deleteRecursively() }
    }
    @Test fun networkBecomingMeteredCancelsInflightImageAndContinuesWithoutFalseFailure() = runBlocking {
        val root=java.nio.file.Files.createTempDirectory("flux-network").toFile(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val net=DownloadNet(root);net.gate=CompletableDeferred()
            val c=DownloadController(scope,{true},DownloadStore(DownloadDb()),DownloadFiles(root),net)
            c.start();c.enqueue("s","m",listOf("a" to "a"))
            withTimeout(5000) { while(net.fetched.isEmpty()) delay(5) }
            c.networkChanged("等待非计费网络")
            withTimeout(5000) { while(c.state.value.jobs.single().status!=DownloadStatus.QUEUED) delay(5) }
            assertEquals(0,c.state.value.jobs.single().completed)
            net.gate!!.complete(Unit);c.networkChanged(null);settled(c)
            assertEquals(DownloadStatus.COMPLETE,c.state.value.jobs.single().status)
            assertEquals(listOf(0,0,1,2),net.fetched)
        } finally { scope.cancel(); root.deleteRecursively() }
    }
    @Test fun batchRemovalCleansSelectedSourceOnlyAndRetainsBooksProgressAndNeighbors() = runBlocking {
        val root=java.nio.file.Files.createTempDirectory("flux-batch").toFile();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val db=DownloadDb();db.books["a" to "m"]=OnlineBook("a","m","kept",true,1);db.progress+="a" to "m"
            val net=DownloadNet(root);val files=DownloadFiles(root);val c=DownloadController(scope,{true},DownloadStore(db),files,net)
            c.start();c.enqueue("a","m",listOf("one" to "one","two" to "two"));settled(c)
            c.enqueue("b","m",listOf("one" to "other"));settled(c)
            val originals=java.io.File(root,"original.cbz").apply { writeText("original") }
            val removed=c.state.value.jobs.filter { it.source=="a" };val neighbor=c.state.value.jobs.first { it.source=="b" }
            c.batch(removed.map { it.id }.toSet(),DownloadBatchAction.REMOVE);settled(c)
            assertEquals(listOf(neighbor),c.state.value.jobs);assertTrue(files.valid(neighbor,0))
            assertTrue(removed.none { files.directory(it).exists() });assertEquals("original",originals.readText())
            assertEquals("kept",db.books["a" to "m"]!!.title);assertTrue(db.progress.contains("a" to "m"))
        } finally { scope.cancel();root.deleteRecursively() }
    }

}
