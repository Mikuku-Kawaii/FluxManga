package com.flux.manga.session

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.flux.manga.data.FixtureWorldStore
import com.flux.manga.ui.navigation.MainTab
import com.flux.manga.ui.navigation.PrototypeWorld
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SessionIdentity(val world: PrototypeWorld, val processSession: String, val generation: Long)
enum class SwitchPhase { Stable, Preparing, Revealing }
data class SessionState(
    val phase: SwitchPhase = SwitchPhase.Stable,
    val foreground: Boolean = false,
    val visibilityEpoch: Long = 0,
    val error: String? = null,
    val target: PrototypeWorld? = null,
)

/** Activity-retained, never serialized. A generation owns page stores and requests. */
class WorldSession : ViewModel() {
    private var pageCoverOwner: com.flux.manga.data.PageCoverCache? = null
    @Synchronized internal fun pageCovers(): com.flux.manga.data.PageCoverCache {
        val owner = identity.value
        return pageCoverOwner ?: com.flux.manga.data.PageCoverCache(viewModelScope,
            { accepts(owner) && state.value.foreground }).also { pageCoverOwner = it }
    }
    internal fun clearPageCovers() { pageCoverOwner?.clear() }
    private var downloadBackgroundOwner: com.flux.manga.data.DownloadBackgroundConnection? = null
    internal fun downloadBackground(context: android.content.Context): com.flux.manga.data.DownloadBackgroundConnection {
        val ownerIdentity = identity.value
        val owner = downloads(context)
        return downloadBackgroundOwner ?: com.flux.manga.data.DownloadBackgroundConnection(context, viewModelScope, owner,
            { accepts(ownerIdentity) && ownerIdentity.world == PrototypeWorld.SFW },
            { state.value.foreground }).also { downloadBackgroundOwner = it }
    }
    private var downloadNetworkOwner: com.flux.manga.data.DownloadNetworkMonitor? = null
    internal fun downloadNetwork(context: android.content.Context): com.flux.manga.data.DownloadNetworkMonitor {
        downloads(context)
        return requireNotNull(downloadNetworkOwner)
    }
    private var downloadOwner: com.flux.manga.data.DownloadController? = null
    internal fun downloads(context: android.content.Context): com.flux.manga.data.DownloadController {
        val ownerIdentity=identity.value
        require(ownerIdentity.world==PrototypeWorld.SFW)
        val app=context.applicationContext
        return downloadOwner ?: com.flux.manga.data.DownloadController(viewModelScope,
            { accepts(ownerIdentity) && (state.value.foreground || downloadBackgroundOwner?.running == true) },
            com.flux.manga.data.DownloadStore(com.flux.manga.data.WorldDataStore(app).accountImportDatabase()),
            com.flux.manga.data.downloadFiles(app), com.flux.manga.data.WorldDownloadNetwork(app,this)).also { downloadOwner=it; downloadNetworkOwner=com.flux.manga.data.DownloadNetworkMonitor(app, it) }
    }
    private var copyAccountOwner: com.flux.manga.data.CopyAccountController? = null
    internal fun copyAccount(context: android.content.Context): com.flux.manga.data.CopyAccountController {
        val ownerIdentity = identity.value
        require(ownerIdentity.world == PrototypeWorld.SFW)
        return copyAccountOwner ?: com.flux.manga.data.CopyAccountController(viewModelScope, { accepts(ownerIdentity) },
            com.flux.manga.data.CopyAccountStore(com.flux.manga.data.WorldDataStore(context).accountImportDatabase()),
            com.flux.manga.data.WorldCopyAccountNetwork(this)).also { copyAccountOwner = it }
    }
    private var komgaAccountOwner: com.flux.manga.data.KomgaAccountController? = null
    internal fun komgaAccount(context: android.content.Context): com.flux.manga.data.KomgaAccountController {
        val ownerIdentity = identity.value
        require(ownerIdentity.world == PrototypeWorld.SFW)
        return komgaAccountOwner ?: com.flux.manga.data.KomgaAccountController(viewModelScope, { accepts(ownerIdentity) },
            com.flux.manga.data.KomgaSettingsStore(com.flux.manga.data.WorldDataStore(context)).accounts,
            com.flux.manga.data.WorldKomgaAccountNetwork(this)).also { komgaAccountOwner = it }
    }
    internal fun invalidateKomgaAccount() { komgaAccountOwner?.configurationChanged() }
    private var baoziAccountOwner: com.flux.manga.data.BaoziAccountController? = null
    internal fun baoziAccount(context: android.content.Context): com.flux.manga.data.BaoziAccountController {
        val ownerIdentity = identity.value
        require(ownerIdentity.world == PrototypeWorld.SFW)
        return baoziAccountOwner ?: com.flux.manga.data.BaoziAccountController(viewModelScope,
            { accepts(ownerIdentity) },
            com.flux.manga.data.BaoziAccountStore(com.flux.manga.data.WorldDataStore(context).accountImportDatabase()),
            com.flux.manga.data.WorldBaoziAccountNetwork(this),
            credentialsChanged = { baoziSources.close() }).also { baoziAccountOwner = it }
    }
    internal val baoziSources = com.flux.manga.data.WorldBaoziSources(this)
    internal fun recoverBaoziAccount(context: android.content.Context, owner: SessionIdentity) {
        viewModelScope.launch {
            if (accepts(owner) && owner.world == PrototypeWorld.SFW)
                baoziAccount(context.applicationContext).recoverBrowserSession()
        }
    }
    internal val copyMangaSources = com.flux.manga.data.WorldCopyMangaSources(this)
    internal val komgaSources = com.flux.manga.data.WorldKomgaSources(this)
    private val mutableIdentity = MutableStateFlow(SessionIdentity(PrototypeWorld.SFW, UUID.randomUUID().toString(), 0))
    val identity = mutableIdentity.asStateFlow()
    private val mutableState = MutableStateFlow(SessionState())
    val state = mutableState.asStateFlow()
    lateinit var repository: com.flux.manga.data.SampleRepository
        private set
    fun configure(context: android.content.Context) {
        if (!::repository.isInitialized) repository = com.flux.manga.data.SampleRepository(com.flux.manga.data.WorldDataStore(context))
    }
    private val tabs = mutableMapOf<PrototypeWorld, MainTab>()
    var pageOwner = newPageOwner()
        private set
    private var transaction: Job? = null
    private var backgroundAt: Long? = null
    internal var failNextPreparation = false
    init { Log.i("FluxSession", "created ${identity.value.processSession} world=SFW generation=0") }
    // Navigation save keys may survive a public snapshot; request identities never do.
    var navigationEpoch = 0L
        private set
    fun restorePublicNavigation(epoch: Long) {
        if (navigationEpoch == 0L) navigationEpoch = epoch
    }
    fun accepts(request: SessionIdentity) = request == identity.value && state.value.phase != SwitchPhase.Preparing
    fun selectedTab() = tabs[identity.value.world] ?: MainTab.HOME
    fun selectTab(tab: MainTab) { tabs[state.value.target ?: identity.value.world] = tab }

    private fun invalidate(world: PrototypeWorld) {
        pageCoverOwner?.clear(); pageCoverOwner = null
        navigationEpoch++
        downloadBackgroundOwner?.stop(null); downloadBackgroundOwner=null; downloadNetworkOwner?.close(); downloadNetworkOwner=null; downloadOwner?.close(); downloadOwner=null; copyAccountOwner?.close(); copyAccountOwner = null; komgaAccountOwner?.close(); komgaAccountOwner = null; baoziAccountOwner?.close(); baoziAccountOwner = null
        baoziSources.close(); copyMangaSources.close()
        komgaSources.close()
        repository.clearMemory()
        pageOwner.viewModelStore.clear()
        pageOwner = newPageOwner()
        mutableIdentity.value = identity.value.copy(world = world, generation = identity.value.generation + 1)
    }

    /** Caller installs the opaque cover and FLAG_SECURE before invoking this method. */
    fun switch(store: FixtureWorldStore) {
        if (state.value.phase != SwitchPhase.Stable || !state.value.foreground) return
        val target = if (identity.value.world == PrototypeWorld.SFW) PrototypeWorld.NSFW else PrototypeWorld.SFW
        val targetTab = selectedTab()
        val fail = failNextPreparation.also { failNextPreparation = false }
        mutableState.value = state.value.copy(phase = SwitchPhase.Preparing, error = null, target = target)
        invalidate(identity.value.world)

        transaction = viewModelScope.launch {
            try {
                store.prepare(target, fail)
                // Carry only the selected root tab; page state stays scoped to its world.
                tabs[target] = targetTab
                mutableIdentity.value = identity.value.copy(world = target)
                Log.i("FluxSession", "commit world=$target generation=${identity.value.generation}")
                // Visual progress is composition-owned; publishing the ready page needs no timer.
                mutableState.value = state.value.copy(phase = SwitchPhase.Stable, target = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: java.io.IOException) {
                invalidate(PrototypeWorld.SFW)
                mutableState.value = state.value.copy(phase = SwitchPhase.Stable, target = null, error = "空间准备失败，已返回表世界。" + com.flux.manga.storage.storageMessage(failure))
            }
        }
    }

    /** Authentication cancellation returns to public memory state; persistent books and keys stay intact. */
    internal fun returnToPublicForPrivacy() {
        transaction?.cancel()
        invalidate(PrototypeWorld.SFW)
        mutableState.value = state.value.copy(phase = SwitchPhase.Stable, target = null, error = null)
    }

    fun pause(now: Long = SystemClock.elapsedRealtime()) {
        clearPageCovers()
        // Resource cache bytes are shared across store instances; revoke them before a new foreground frame.
        if (::repository.isInitialized) repository.clearMemory()
        if (downloadBackgroundOwner?.running != true) downloadOwner?.pauseAll()
        backgroundAt = backgroundAt ?: now
        transaction?.cancel()
        if (state.value.phase == SwitchPhase.Preparing) invalidate(PrototypeWorld.SFW)
        mutableState.value = state.value.copy(foreground = false, phase = SwitchPhase.Stable, target = null)
    }

    fun resume(now: Long = SystemClock.elapsedRealtime()) {
        if (backgroundAt?.let { now - it > 30_000 } == true &&
            !(identity.value.world == PrototypeWorld.SFW && downloadBackgroundOwner?.running == true)) invalidate(PrototypeWorld.SFW)
        backgroundAt = null
        mutableState.value = state.value.copy(foreground = true, visibilityEpoch = state.value.visibilityEpoch + 1)
    }

    override fun onCleared() { clearPageCovers(); downloadBackgroundOwner?.stop(null); downloadBackgroundOwner=null; downloadNetworkOwner?.close(); downloadNetworkOwner=null; downloadOwner?.close(); downloadOwner=null; copyAccountOwner?.close(); copyAccountOwner = null; komgaAccountOwner?.close(); komgaAccountOwner = null; baoziAccountOwner?.close(); baoziSources.close(); copyMangaSources.close(); komgaSources.close(); pageOwner.viewModelStore.clear(); if (::repository.isInitialized) repository.clearMemory() }
    private fun newPageOwner() = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
}
