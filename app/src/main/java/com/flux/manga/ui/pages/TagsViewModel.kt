package com.flux.manga.ui.pages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flux.manga.data.*
import com.flux.manga.session.WorldSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TagsState(val tags: List<UserTag> = emptyList(), val links: List<TagBook> = emptyList(),
    val busy: Boolean = false, val ready: Boolean = false, val error: String? = null)

internal fun TagBook.matches(book: ShelfBook) = kind == (if (book.online) "online" else "local") &&
    sourceId == book.key.sourceId && mangaId == book.key.mangaId

internal fun taggedShelfBooks(books: List<ShelfBook>, links: List<TagBook>, tagId: String,
                             world: com.flux.manga.ui.navigation.PrototypeWorld): List<ShelfBook> =
    books.filter { book -> book.key.world == world && links.any { it.tagId == tagId && it.matches(book) } }

internal class TagsViewModel(app: Application, private val session: WorldSession) : AndroidViewModel(app) {
    private val identity = session.identity.value
    private fun active() = session.accepts(identity)
    private val repository = UserTags(WorldDataStore(app), identity.world, ::active)
    private val mutable = MutableStateFlow(TagsState())
    val state = mutable.asStateFlow()
    init { refresh() }
    private suspend fun reload(): Boolean = try {
        val snapshot = repository.snapshot()
        if (active()) mutable.value = state.value.copy(tags = snapshot.tags, links = snapshot.links, ready = true)
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        if (active()) mutable.value = state.value.copy(ready = false, error = "分组读取失败；保留已显示内容，请重试读取后再编辑")
        false
    }
    fun refresh() {
        if (state.value.busy || !active()) return
        mutable.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { reload() } finally { if (active()) mutable.value = state.value.copy(busy = false) }
        }
    }
    private fun write(action: suspend () -> Unit, done: () -> Unit = {}) {
        if (state.value.busy || !state.value.ready || !active()) return
        mutable.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                action()
                if (reload() && active()) done()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // Read before retry; creates keep their ID and associations use desired state.
                val known = reload()
                if (active() && known) {
                    val validation = generateSequence(failure as Throwable) { it.cause }
                        .filterIsInstance<IllegalArgumentException>().firstOrNull()?.message
                    mutable.value = state.value.copy(error = validation ?: "操作结果已重新读取；请核对当前分组后重试")
                }
            } finally { if (active()) mutable.value = state.value.copy(busy = false) }
        }
    }
    fun save(id: String, name: String, creating: Boolean, done: () -> Unit) =
        write({ repository.save(id, name, creating) }, done)
    fun delete(id: String, done: () -> Unit) = write({ repository.delete(id) }, done)
    fun associateMany(tag: String, books: List<ShelfBook>, selected: Boolean, done: () -> Unit = {}) =
        write({ repository.associateMany(tag, books.map { GroupBookKey(it.key, it.online) }, selected) }, done)
    fun associate(tag: String, book: ShelfBook, selected: Boolean) =
        write({ repository.associate(tag, book.key, book.online, selected) })
}

internal const val UNGROUPED = "__ungrouped__"
internal fun groupedShelfBooks(books: List<ShelfBook>, snapshot: TagSnapshot, group: String?, world: com.flux.manga.ui.navigation.PrototypeWorld): List<ShelfBook> {
    if (group == null) return books.filter { it.key.world == world }
    val validIds = snapshot.tags.map { it.id }.toSet()
    val keys = snapshot.links.asSequence().filter { it.tagId in validIds && (group == UNGROUPED || it.tagId == group) }
        .map { Triple(it.kind, it.sourceId, it.mangaId) }.toSet()
    return books.filter { book -> book.key.world == world &&
        (Triple(if (book.online) "online" else "local", book.key.sourceId, book.key.mangaId) in keys) != (group == UNGROUPED) }
}
