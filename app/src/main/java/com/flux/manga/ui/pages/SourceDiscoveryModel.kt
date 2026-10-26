package com.flux.manga.ui.pages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flux.manga.data.KomgaSettingsStore
import com.flux.manga.data.WorldDataStore
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.PrototypeWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class DiscoveryStatus { LOADING, UNCONFIGURED, READY, FAILED, PRIVATE }
internal data class DiscoveryState(val status: DiscoveryStatus = DiscoveryStatus.LOADING, val sourceId: String? = null,
    val refreshing: Boolean = false)

/** Entry-owned configuration summary. Does not load books, tags, images or make network requests.
 * Credentials stay in the existing encrypted store/client boundary, never in UI state. */
internal class SourceDiscoveryModel(app: Application, private val session: WorldSession) : AndroidViewModel(app) {
    private val identity = session.identity.value
    private val mutable = MutableStateFlow(DiscoveryState(
        if (identity.world == PrototypeWorld.SFW) DiscoveryStatus.LOADING else DiscoveryStatus.PRIVATE))
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var attempted = identity.world != PrototypeWorld.SFW
    fun start() { if (!attempted) refresh() }

    fun refresh() {
        if (work?.isActive == true || !session.accepts(identity) || identity.world != PrototypeWorld.SFW) return
        attempted = true
        work = viewModelScope.launch {
            // Keep a mounted result entry (and its query/scroll) while rechecking configuration.
            mutable.value = state.value.copy(refreshing = true)
            try {
                if (!com.flux.manga.data.SourceSelection(getApplication(), identity.world).enabled(com.flux.manga.data.SourceKind.KOMGA)) {
                    mutable.value = DiscoveryState(DiscoveryStatus.UNCONFIGURED)
                    return@launch
                }
                val config = KomgaSettingsStore(WorldDataStore(getApplication())).load()
                ensureActive()
                if (session.accepts(identity)) mutable.value = if (config == null)
                    DiscoveryState(DiscoveryStatus.UNCONFIGURED)
                else DiscoveryState(DiscoveryStatus.READY, config.sourceId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                ensureActive()
                if (session.accepts(identity)) mutable.value = DiscoveryState(DiscoveryStatus.FAILED)
            }
        }
    }

    fun leave() {
        if (work?.isActive == true && state.value.status == DiscoveryStatus.LOADING) attempted = false
        work?.cancel(); work = null
        mutable.value = state.value.copy(refreshing = false)
    }
}
