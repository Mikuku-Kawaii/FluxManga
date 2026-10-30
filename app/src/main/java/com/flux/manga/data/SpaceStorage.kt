package com.flux.manga.data

import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SpaceUsage(val covers: CacheUsage, val searches: Int, val visits: Int, val privateResources: CacheUsage = CacheUsage(),
    val coverPolicy: CoverCachePolicy? = null, val coverPolicyError: String? = null, val localCoverBytes: Long = covers.bytes,
    val catalogPages: CacheUsage = CacheUsage(), val readerPages: CacheUsage = CacheUsage())
internal data class CleanupChoice(val covers: Boolean = false, val searches: Boolean = false, val visits: Boolean = false,
    val privateResources: Boolean = false, val catalogPages: Boolean = false, val readerPages: Boolean = false) {
    val any get() = covers || searches || visits || privateResources || catalogPages || readerPages
}
internal fun clearSpaceRecords(dao: WorldDao, choice: CleanupChoice): Pair<Int, Int> {
    val searches = if (choice.searches) dao.searchHistoryCount() else 0
    val visits = if (choice.visits) dao.visitCount() else 0
    if (choice.searches) dao.clearSearchHistory()
    if (choice.visits) dao.clearVisits()
    return searches to visits
}
internal class SpaceStorage(private val data: WorldDataStore, private val world: PrototypeWorld, private val allowed: () -> Boolean) {
    private val local = CbzLibrary(data)
    private fun active() { if (!allowed()) throw CancellationException("Expired storage session") }
    suspend fun usage(): SpaceUsage {
        active()
        val cache = if (world == PrototypeWorld.SFW) local.coverCacheSettings() else null
        val resources = if (world == PrototypeWorld.NSFW) data.privateResourceUsage() else CacheUsage()
        val onlineCovers = if (world == PrototypeWorld.SFW) data.libraryCoverUsage() else CacheUsage()
        val localCovers = cache?.usage ?: CacheUsage()
        val pages = if (world == PrototypeWorld.SFW) data.catalogPageUsage() else CacheUsage()
        val reader = if (world == PrototypeWorld.SFW) data.readerCacheUsage(world) else CacheUsage()
        val context = currentCoroutineContext()
        return data.worldData(world, allowed) { dao ->
            context.ensureActive(); active()
            SpaceUsage(CacheUsage(localCovers.files + onlineCovers.files, localCovers.bytes + onlineCovers.bytes), dao.searchHistoryCount(), dao.visitCount(), resources,
                cache?.policy, cache?.error, localCovers.bytes, pages, reader).also { active() }
        }
    }
    suspend fun applyCoverPolicy(policy: CoverCachePolicy): String {
        active()
        check(world == PrototypeWorld.SFW)
        return local.applyCoverCachePolicy(policy, allowed)
    }
    suspend fun clear(choice: CleanupChoice): String {
        require(choice.any)
        active()
        val context = currentCoroutineContext()
        val results = mutableListOf<String>()
        if (choice.privateResources && world == PrototypeWorld.NSFW) {
            try {
                val removed = data.clearPrivateResources(allowed)
                results += "里世界资源缓存：已清理 ${removed.files} 个文件（${removed.bytes} 字节）" +
                    if (removed.failed > 0) "，${removed.failed} 个未完成，请刷新核对" else "；书库与密钥保留"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { results += "里世界资源缓存清理未完成，请刷新核对" }
        }
        if (choice.covers && world == PrototypeWorld.SFW) {
            try {
                val localRemoved = local.clearCoverCache(allowed)
                val onlineRemoved = data.clearLibraryCovers(allowed)
                val removed = CacheCleared(localRemoved.files + onlineRemoved.files, localRemoved.bytes + onlineRemoved.bytes, localRemoved.failed + onlineRemoved.failed)
                results += "封面缓存：已清理 ${removed.files} 个文件（${removed.bytes} 字节）" + if (removed.failed > 0) "，${removed.failed} 个未完成，可刷新重试" else ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { results += "封面缓存清理未完成，请刷新核对剩余文件" }
        }
        if (choice.catalogPages && world == PrototypeWorld.SFW) {
            try {
                val removed = data.clearCatalogPages(allowed)
                results += "页面缓存：已清理 ${removed.files} 个文件（${removed.bytes} 字节）" +
                    if (removed.failed > 0) "，${removed.failed} 个未完成，请刷新核对" else ""
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { results += "页面缓存清理未完成，请刷新核对" }
        }
        if (choice.readerPages && world == PrototypeWorld.SFW) {
            val removed = data.clearReaderCache(world, allowed)
            results += "阅读临时缓存：已清理 ${removed.files} 个文件；正在阅读的章节与离线下载保留" +
                if (removed.failed > 0) "，${removed.failed} 个未完成，请刷新核对" else ""
        }
        if (choice.searches || choice.visits) {
            try {
                val removed = data.worldData(world, allowed) { dao ->
                    context.ensureActive(); active()
                    clearSpaceRecords(dao, choice).also { context.ensureActive(); active() }
                }
                if (choice.searches) results += "搜索记录：已清理 ${removed.first} 条"
                if (choice.visits) results += "最近观看：已清理 ${removed.second} 条（阅读进度保留）"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { results += "记录清理结果待核对，请刷新后重试" }
        }
        active()
        return results.joinToString("\n")
    }
}
