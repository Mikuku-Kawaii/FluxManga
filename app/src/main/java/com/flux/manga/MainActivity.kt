package com.flux.manga

import android.os.Bundle
import android.net.Uri
import android.content.ActivityNotFoundException
import android.widget.Toast
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.flux.manga.core.CoreInfo
import com.flux.manga.core.coreInfo
import com.flux.manga.data.FixtureWorldStore
import com.flux.manga.session.SwitchPhase
import com.flux.manga.session.WorldSession
import com.flux.manga.ui.navigation.GlassLab
import com.flux.manga.ui.navigation.NavigationPrototype
import com.flux.manga.ui.navigation.PrototypeNavigation
import com.flux.manga.ui.navigation.PrototypeWorld
import com.flux.manga.ui.navigation.rememberPrototypeNavigation
import androidx.compose.ui.graphics.toArgb
import com.flux.manga.ui.glass.DockEffects
import com.flux.manga.ui.glass.LiquidBottomBar
import com.flux.manga.ui.glass.WorldMotion
import com.flux.manga.ui.glass.CircularRevealBox
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.flux.manga.data.DisplayBitmapStore.trimMemory(level)
    }
    private val requestFeedback = com.flux.manga.ui.pages.RequestFeedbackState()
    private val privacy by lazy { com.flux.manga.privacy.PrivacyController(this,
        { worldSession.identity.value.toString() }, { worldSession.state.value.foreground },
        { worldSession.identity.value.world == PrototypeWorld.NSFW }, ::privacyReturnToPublic,
        ::onScreenshotPreferenceChanged) }
    private fun onScreenshotPreferenceChanged() {
        if (!privacy.allowPrivateScreenshots && worldSession.identity.value.world == PrototypeWorld.NSFW) protectWindow()
    }
    private fun privacyLocked() = worldSession.identity.value.world == PrototypeWorld.NSFW && privacy.biometric && !privacy.unlocked
    private fun privacyReturnToPublic() {
        coverWindow(); protectWindow()
        privacy.clearGrant()
        worldSession.returnToPublicForPrivacy()
        if (::liquidMotion.isInitialized) liquidMotion.interrupt(PrototypeWorld.SFW)
    }

    internal val readerVolumeKeys = com.flux.manga.ui.pages.ReaderVolumeDispatcher()
    private fun handleReaderKey(event: android.view.KeyEvent): Boolean =
        hasWindowFocus() && !privacyLocked() && worldSession.state.value.foreground && readerVolumeKeys.dispatch(
            event.keyCode, event.action, event.repeatCount, event.isCanceled)

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean =
        handleReaderKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        handleReaderKey(event) || super.onKeyUp(keyCode, event)
    private val appUpdates: com.flux.manga.update.AppUpdateModel by viewModels()
    private val updateInstaller = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        appUpdates.installationResult(it.resultCode)
    }
    private val updateAuthorization = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { }
    private fun installUpdate() {
        if (!::navigation.isInitialized || navigation.currentStack.last() != com.flux.manga.ui.navigation.AppUpdates) return
        appUpdates.prepareInstall { intent ->
            if (navigation.currentStack.last() == com.flux.manga.ui.navigation.AppUpdates && worldSession.state.value.foreground) {
                try { updateInstaller.launch(intent) } catch (_: Exception) { appUpdates.launchError() }
            } else appUpdates.launchError()
        }
    }
    private fun authorizeUpdate() {
        try { updateAuthorization.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:$packageName".toUri())) } catch (_: Exception) {
            requestFeedback.beginAttempt(); requestFeedback.show("无法打开安装授权设置")
        }
    }
    internal val worldSession: WorldSession by viewModels()
    internal lateinit var navigation: PrototypeNavigation
        private set
    internal lateinit var fixtureStore: FixtureWorldStore
    internal lateinit var liquidMotion: WorldMotion
        private set
    internal var lowEffects by mutableStateOf(false)
        private set
    internal var reduceMotion by mutableStateOf(false)
        private set
    private var privacyCover: View? = null
    internal val isCovered get() = privacyCover?.visibility == View.VISIBLE
    private val instance = UUID.randomUUID().toString()
    private var pendingDownloadQueue by mutableStateOf(false)
    private var waitingForCbz = false
    private val cbzSelection: CbzPickerSelection by viewModels()
    private var pendingCbzUris: List<Uri>
        get() = cbzSelection.pending
        set(value) { cbzSelection.pending = value }
    // Register on every Activity creation, independently of a navigation entry's lifetime.
    private val cbzPicker = registerForActivityResult(CbzOpenDocument()) { uris ->
        if (waitingForCbz && worldSession.identity.value.world == PrototypeWorld.SFW &&
            worldSession.state.value.phase == SwitchPhase.Stable) {
            val selected = uris.distinct()
            if (selected.size <= com.flux.manga.ui.pages.MAX_CBZ_BATCH) pendingCbzUris = selected
            else Toast.makeText(this, "每批最多 ${com.flux.manga.ui.pages.MAX_CBZ_BATCH} 个文件，请重新选择。", Toast.LENGTH_LONG).show()
        }
        waitingForCbz = false
    }

    private val recordExportSelection: RecordExportPickerSelection by viewModels()
    private val recordExportPicker = registerForActivityResult(RecordCreateDocument()) { uri ->
        recordExportSelection.result(uri)
    }
    private fun pickRecordExport(model: com.flux.manga.ui.pages.RecordExportModel) {
        if (!recordExportSelection.request(model)) { model.pickerUnavailable(); return }
        try { recordExportPicker.launch(model.filename()) }
        catch (_: Exception) { recordExportSelection.failure() }
    }

    private fun pickCbz() {
        if (waitingForCbz || pendingCbzUris.isNotEmpty() || worldSession.identity.value.world != PrototypeWorld.SFW ||
            !worldSession.state.value.foreground || worldSession.state.value.phase != SwitchPhase.Stable) return
        waitingForCbz = true
        try { cbzPicker.launch(arrayOf("*/*")) }
        catch (_: ActivityNotFoundException) {
            waitingForCbz = false
            requestFeedback.beginAttempt(); requestFeedback.show("未找到可用的文件选择器")
        }
    }

    private val galleryTagsSelection: GalleryTagsSelection by viewModels()
    private val galleryTagsInput = registerForActivityResult(PortableBackupOpenDocument()) { uri -> galleryTagsSelection.result(uri) }
    private fun pickGalleryTags(model: com.flux.manga.ui.pages.GalleryTagsModel) {
        if (!galleryTagsSelection.request(model)) { model.pickerUnavailable(); return }
        try { galleryTagsInput.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        catch (_: Exception) { galleryTagsSelection.failure() }
    }

    private val portableBackupSelection: PortableBackupSelection by viewModels()
    private val portableBackupOutput = registerForActivityResult(RecordCreateDocument("application/zip")) { uri ->
        portableBackupSelection.result(uri)
    }
    private val portableBackupInput = registerForActivityResult(PortableBackupOpenDocument()) { uri ->
        portableBackupSelection.result(uri)
    }
    private fun pickPortableBackup(model: com.flux.manga.ui.pages.PortableBackupModel, restore: Boolean) {
        if (!portableBackupSelection.request(model, restore)) { model.pickerUnavailable(); return }
        try {
            if (restore) portableBackupInput.launch(arrayOf("application/zip", "application/octet-stream"))
            else portableBackupOutput.launch("FluxManga-backup-${System.currentTimeMillis()}.zip")
        } catch (_: Exception) { portableBackupSelection.failure() }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Compose UI 1.9.5 eagerly recalculates semantics on deactivated lazy nodes,
        // crashing Material 3 1.4.0 during large-font shelf refresh on OPPO/API 37.
        // Keep the supported lazy semantics path until the dependency fix is verified.
        // No production fields currently use the new Semantic Autofill API.
        androidx.compose.ui.ComposeUiFlags.isSemanticAutofillEnabled = false
        val displayPreferences = getSharedPreferences("display-preferences", MODE_PRIVATE)
        lowEffects = displayPreferences.getBoolean("low-effects", false)
        reduceMotion = displayPreferences.getBoolean("reduce-motion", false)
        // Sensitive state is never fed to Compose/SavedStateHandle after a new host starts.
        super.onCreate(savedInstanceState?.takeUnless { it.getBoolean("private-world") })
        savedInstanceState?.takeUnless { it.getBoolean("private-world") }?.let {
            waitingForCbz = it.getBoolean("cbz-picker-waiting")
            // Only an outstanding system-picker request is restored. Consumed batches never replay.
        }
        if (savedInstanceState?.getBoolean("private-world") != true) {
            worldSession.restorePublicNavigation(savedInstanceState?.getLong("public-navigation-epoch") ?: 0L)
        }
        if (intent?.action == com.flux.manga.data.ChapterDownloadService.OPEN_QUEUE) { pendingDownloadQueue = true; intent.action = null }
        worldSession.configure(applicationContext)
        fixtureStore = FixtureWorldStore(applicationContext)
        Log.d("FluxNavActivity", "created $instance restored=${savedInstanceState != null}")
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        if (worldSession.identity.value.world == PrototypeWorld.NSFW) protectWindow()
        setContent {
            CompositionLocalProvider(com.flux.manga.privacy.LocalPrivacyController provides privacy,
                com.flux.manga.ui.pages.LocalRecordExportPicker provides ::pickRecordExport,
                com.flux.manga.ui.pages.LocalPortableBackupPicker provides ::pickPortableBackup,
                com.flux.manga.ui.pages.LocalGalleryTagsPicker provides ::pickGalleryTags) {
            val identity by worldSession.identity.collectAsState()
            val sessionState by worldSession.state.collectAsState()
            val locked = identity.world == PrototypeWorld.NSFW && privacy.biometric && !privacy.unlocked
            LaunchedEffect(identity, sessionState.foreground, sessionState.phase, locked) {
                // Exiting the private world revokes the grant before preparation; never prompt on that exit.
                if (sessionState.foreground && sessionState.phase == SwitchPhase.Stable && locked) {
                    privacy.enter({}, ::privacyReturnToPublic)
                }
            }
            LaunchedEffect(identity.world, sessionState.phase) {
                if (identity.world != PrototypeWorld.SFW || sessionState.phase == SwitchPhase.Preparing) {
                    waitingForCbz = false
                    pendingCbzUris = emptyList()
                }
            }
            val motionScope = rememberCoroutineScope()
            liquidMotion = remember { WorldMotion(motionScope, identity.world) }
            LaunchedEffect(identity, sessionState.phase) { liquidMotion.resolve(identity.world, sessionState.phase) }
            var sampling by rememberSaveable { mutableStateOf(true) }
            var blur by rememberSaveable { mutableStateOf(true) }
            var tint by rememberSaveable { mutableStateOf(true) }
            var fusion by rememberSaveable { mutableStateOf(false) }
            var highlight by rememberSaveable { mutableStateOf(true) }
            var refraction by rememberSaveable { mutableStateOf(true) }
            var saturation by rememberSaveable { mutableStateOf(true) }
            var enhancementFailure by remember { mutableStateOf<String?>(null) }
            var samplePages by rememberSaveable { mutableStateOf(false) }
            com.flux.manga.ui.pages.PreloadSessionPages(worldSession,
                enabled = sessionState.foreground && sessionState.phase == SwitchPhase.Stable && !locked && !samplePages)
            val effects = DockEffects(sampling, blur, tint, lowEffects, reduceMotion, fusion, highlight, refraction, saturation)
            MaterialTheme(colorScheme = com.flux.manga.ui.glass.WorldColors.scheme(identity.world),
                typography = com.flux.manga.ui.ReferenceTypography,
                shapes = com.flux.manga.ui.ReferenceShapes) {
                val updateState by appUpdates.state.collectAsState()
                val updateNotice = remember { androidx.compose.material3.SnackbarHostState() }
                var noticedVersion by rememberSaveable { mutableStateOf(0L) }
                val updateRootVisible = ::navigation.isInitialized && navigation.isPrimaryRoot &&
                    sessionState.foreground && sessionState.phase == SwitchPhase.Stable
                LaunchedEffect(updateState.artifact?.version, updateState.phase, updateRootVisible) {
                    val target = updateState.artifact
                    if (updateRootVisible && target != null && target.version != noticedVersion &&
                        updateState.phase in setOf(com.flux.manga.update.UpdatePhase.AVAILABLE, com.flux.manga.update.UpdatePhase.READY)) {
                        noticedVersion = target.version
                        val result = updateNotice.showSnackbar("发现新版本 ${target.name}", actionLabel = "查看", withDismissAction = true,
                            duration = androidx.compose.material3.SnackbarDuration.Long)
                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed &&
                            navigation.isPrimaryRoot && worldSession.state.value.foreground &&
                            worldSession.state.value.phase == SwitchPhase.Stable) {
                            navigation.openSettings(); navigation.openAppUpdates()
                        }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                var transitionSampleBounds by remember(identity) {
                    mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
                }
                // During handoff sample only the already-revealed, authorized scene below.
                // The dock is a sibling; no previous-world image survives identity/visibility changes.
                com.flux.manga.ui.glass.GlassBackdropHost(
                    modifier = Modifier.fillMaxSize(), sourceKey = identity,
                    enabled = liquidMotion.pending && effects.backdropEnabled && sessionState.foreground && !locked &&
                        sessionState.phase != SwitchPhase.Preparing,
                    blur = effects.blurEnabled, saturation = effects.saturationEnabled,
                    motionPadding = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() },
                    sampleBounds = transitionSampleBounds, background = MaterialTheme.colorScheme.background,
                    observeContentChanges = { liquidMotion.momentumProgress(); liquidMotion.revealProgress(); Unit },
                    content = {
                com.flux.manga.ui.glass.LiquidShockwaveScaffold(liquidMotion, identity.world, worldSession.selectedTab(),
                    Modifier.fillMaxSize()) { _, _ ->
                Surface(Modifier.fillMaxSize()) {
                    if (locked) {
                        Box(Modifier.fillMaxSize().background(com.flux.manga.ui.glass.WorldColors.DarkBackground))
                    } else if (sessionState.phase == SwitchPhase.Preparing) {
                        // The old page is already revoked. Only solid theme pixels may remain here.
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    } else {
                        val registry = LocalSaveableStateRegistry.current
                        CompositionLocalProvider(
                            LocalViewModelStoreOwner provides worldSession.pageOwner,
                            LocalSaveableStateRegistry provides if (identity.world == PrototypeWorld.NSFW) null else registry,
                        ) {
                            key(worldSession.navigationEpoch, identity.world) {
                                navigation = rememberPrototypeNavigation(identity.world, worldSession.selectedTab(), identity.processSession, worldSession::selectTab)
                                LaunchedEffect(pendingDownloadQueue, identity, sessionState.foreground) {
                                    if (pendingDownloadQueue && sessionState.foreground) {
                                        pendingDownloadQueue = false
                                        if (identity.world == PrototypeWorld.SFW) navigation.openDownloads()
                                        else Toast.makeText(this@MainActivity, "请返回表世界查看下载队列", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                LaunchedEffect(pendingCbzUris, identity, sessionState.foreground) {
                                    if (pendingCbzUris.isNotEmpty() && sessionState.foreground && identity.world == PrototypeWorld.SFW &&
                                        navigation.currentStack.last() != com.flux.manga.ui.navigation.LocalLibrary) {
                                        navigation.openLocalLibrary()
                                    }
                                }
                                NavigationPrototype(navigation, rememberCoreStatus(), worldSession, ::requestWorldSwitch, liquidMotion, effects,
                                    samplePages, { samplePages = it }, ::pickCbz, pendingCbzUris,
                                    { consumed -> if (pendingCbzUris == consumed) pendingCbzUris = emptyList() }, appUpdates, ::installUpdate, ::authorizeUpdate, enhancementFailure, { enhancementFailure = it }, feedbackState = requestFeedback) {
                                    sampling = it.sampling; blur = it.blur; tint = it.tint
                                    fusion = it.fusion; highlight = it.highlight; refraction = it.refraction; saturation = it.saturation
                                    lowEffects = it.lowEffects; reduceMotion = it.reduceMotion
                                    displayPreferences.edit().putBoolean("low-effects", lowEffects)
                                        .putBoolean("reduce-motion", reduceMotion).apply()
                                }
                            }
                        }
                    }
                }
                }
                }, overlay = { backgroundGraphicsLayer, filtered, origin, host ->
                // Mount the committed page immediately, while a theme-only veil fades away.
                // No previous-world page, image or saveable state is held by this overlay.
                if (liquidMotion.pending && sessionState.foreground && !locked) {
                    if (sessionState.phase == SwitchPhase.Preparing) {
                        Text("正在准备空间", Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                            .padding(20.dp).background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("switch-phase")
                            .semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (::navigation.isInitialized && navigation.isPrimaryRoot) {
                        LiquidBottomBar(identity.world, worldSession.selectedTab(), onSelect = {},
                            onSwitch = {}, switchEnabled = false,
                            modifier = Modifier.align(Alignment.BottomCenter)
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                                .padding(bottom = 12.dp),
                            effects = effects, motion = liquidMotion,
                            source = backgroundGraphicsLayer, filtered = filtered, filteredOrigin = origin, host = host,
                            observeContentChanges = { liquidMotion.momentumProgress(); liquidMotion.revealProgress(); Unit },
                            positioned = { local ->
                                if (host?.isAttached == true) transitionSampleBounds = androidx.compose.ui.geometry.Rect(
                                    host.localPositionOf(local, androidx.compose.ui.geometry.Offset.Zero),
                                    androidx.compose.ui.geometry.Size(local.size.width.toFloat(), local.size.height.toFloat()))
                            },
                            enhancementFailure = enhancementFailure, onEnhancementFailure = { enhancementFailure = it })
                    }
                }
                })
                if (updateRootVisible) androidx.compose.material3.SnackbarHost(updateNotice,
                    Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 16.dp, vertical = 8.dp))
                }
                LaunchedEffect(privacy.busy, privacy.message, locked) {
                    if (!privacy.busy && !locked) privacy.message?.let {
                        requestFeedback.show(it)
                        privacy.dismissMessage()
                    }
                }
                // Let the replacement/placeholder reach the render tree before removing native protection.
                LaunchedEffect(identity, sessionState, locked, privacy.allowPrivateScreenshots) {
                    if (sessionState.foreground && !locked) {
                        withFrameNanos { }
                        withFrameNanos { }
                        if (!worldSession.state.value.foreground || privacyLocked() || worldSession.identity.value != identity) return@LaunchedEffect
                        if (sessionState.phase == SwitchPhase.Stable &&
                            (identity.world == PrototypeWorld.SFW || privacy.allowPrivateScreenshots)) {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            protectWindow()
                        }
                        // The standalone glass study owns its own theme/system bars.
                        if (sessionState.phase != SwitchPhase.Stable || navigation.currentStack.last() != GlassLab) {
                            WindowCompat.getInsetsController(window, window.decorView).apply {
                                isAppearanceLightStatusBars = identity.world == PrototypeWorld.SFW
                                isAppearanceLightNavigationBars = identity.world == PrototypeWorld.SFW
                            }
                        }
                        findViewById<View>(android.R.id.content)?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        privacyCover?.visibility = View.GONE
                        liquidMotion.displayReady()
                        Log.i("FluxWindow", "expose world=${identity.world} phase=${sessionState.phase} secure=${window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0}")
                    }
                }
            }
        }
        }
        coverWindow()
    }

    internal fun requestWorldSwitch(fail: Boolean = false, origin: Offset? = null) {
        if (!worldSession.state.value.foreground || worldSession.state.value.phase != SwitchPhase.Stable || liquidMotion.pending || !navigation.isPrimaryRoot) return
        if (privacy.busy) return
        if (worldSession.identity.value.world == PrototypeWorld.SFW && privacy.biometric) {
            val owner = worldSession.identity.value
            privacy.enter({
                if (worldSession.identity.value == owner && worldSession.state.value.foreground &&
                    worldSession.state.value.phase == SwitchPhase.Stable && navigation.isPrimaryRoot && !liquidMotion.pending) {
                    performWorldSwitch(fail, origin)
                } else privacy.clearGrant()
            })
        } else performWorldSwitch(fail, origin)
    }

    private fun performWorldSwitch(fail: Boolean, origin: Offset?) {
        worldSession.selectTab(navigation.selectedTab)
        val world = worldSession.identity.value.world
        val owner = worldSession.identity.value
        liquidMotion.begin(world, if (world == PrototypeWorld.SFW) PrototypeWorld.NSFW else PrototypeWorld.SFW,
            origin, reduceMotion || lowEffects) { impactOffset ->
            if (worldSession.identity.value == owner && worldSession.state.value.foreground &&
                worldSession.state.value.phase == SwitchPhase.Stable && navigation.isPrimaryRoot) {
                commitWorldSwitch(fail, world)
            } else { liquidMotion.interrupt(worldSession.identity.value.world); privacy.clearGrant() }
        }
    }

    private fun commitWorldSwitch(fail: Boolean, world: PrototypeWorld) {
        // Match the safe transition veil; background/lock protection still uses opaque black.
        coverWindow((if (world == PrototypeWorld.SFW) com.flux.manga.ui.glass.GlassTokens.Light
            else com.flux.manga.ui.glass.GlassTokens.Dark).background.toArgb())
        protectWindow()
        if (world == PrototypeWorld.NSFW) privacy.clearGrant()
        worldSession.failNextPreparation = fail
        worldSession.switch(fixtureStore)
    }

    private fun protectWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Log.i("FluxWindow", "secure enabled before target preparation/display")
    }
    private fun coverWindow(color: Int = android.graphics.Color.rgb(24, 24, 24)) {
        findViewById<View>(android.R.id.content)?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val cover = privacyCover ?: View(this).also {
            it.isClickable = true
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            (window.decorView as ViewGroup).addView(it, ViewGroup.LayoutParams(-1, -1))
            privacyCover = it
        }
        cover.setBackgroundColor(color)
        cover.visibility = View.VISIBLE
        cover.bringToFront()
        Log.i("FluxWindow", "covered world=${worldSession.identity.value.world}")
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.action == com.flux.manga.data.ChapterDownloadService.OPEN_QUEUE) pendingDownloadQueue = true
    }

    override fun onPause() {
        if (worldSession.identity.value.world == PrototypeWorld.NSFW) protectWindow()
        coverWindow()
        privacy.pause()
        readerVolumeKeys.reset()
        worldSession.pause()
        if (::liquidMotion.isInitialized) liquidMotion.interrupt(worldSession.identity.value.world)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        appUpdates.foreground(true)
    }
    override fun onStop() {
        appUpdates.foreground(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        worldSession.resume()
        privacy.refresh()
        if (worldSession.takePrivateStartup() && !pendingDownloadQueue && worldSession.identity.value.world == PrototypeWorld.SFW) {
            protectWindow()
            coverWindow()
            privacy.enter({
                protectWindow()
                coverWindow()
                worldSession.switch(fixtureStore, com.flux.manga.privacy.StartupPreferences(this).initialTab(PrototypeWorld.NSFW))
            }, ::privacyReturnToPublic)
        }
        if (::liquidMotion.isInitialized) liquidMotion.interrupt(worldSession.identity.value.world)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("public-navigation-epoch", worldSession.navigationEpoch)
        outState.putBoolean("cbz-picker-waiting", waitingForCbz)
        if (worldSession.identity.value.world == PrototypeWorld.NSFW || worldSession.state.value.phase == SwitchPhase.Preparing) {
            outState.clear()
            outState.putBoolean("private-world", true)
        }
    }

    override fun onDestroy() {
        privacy.pause()
        Log.d("FluxNavActivity", "destroyed $instance changingConfiguration=$isChangingConfigurations")
        super.onDestroy()
    }
}
@Composable
private fun rememberCoreStatus(): String {
    var info by remember { mutableStateOf<CoreInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            info = withContext(Dispatchers.IO) { coreInfo() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: failure.javaClass.simpleName
        } catch (failure: LinkageError) {
            error = failure.message ?: failure.javaClass.simpleName
        }
    }
    val core = info
    return when {
        error != null -> stringResource(R.string.core_error, error.orEmpty())
        core != null -> stringResource(R.string.core_ready, core.version, core.architecture)
        else -> stringResource(R.string.core_loading)
    }
}
