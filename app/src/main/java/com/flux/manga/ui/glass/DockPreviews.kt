package com.flux.manga.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.flux.manga.ui.navigation.MainTab
import com.flux.manga.ui.navigation.PrototypeWorld

// IDE previews contain no repository, WorldSession, files or device settings.
// They are design aids, not evidence of device rendering or motion acceptance.
@Composable
private fun DockPreview(world: PrototypeWorld, lowEffects: Boolean = false) {
    var tab by remember { mutableStateOf(MainTab.LIBRARY) }
    MaterialTheme(colorScheme = WorldColors.scheme(world)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center) {
            LiquidBottomBar(world, tab, onSelect = { tab = it }, onSwitch = {},
                effects = DockEffects(lowEffects = lowEffects))
        }
    }
}

@Preview(name = "392dp white blue", widthDp = 392, heightDp = 100)
@Preview(name = "320dp white blue 200%", widthDp = 320, heightDp = 150, fontScale = 2f)
@Composable
private fun OuterDockPreview() = DockPreview(PrototypeWorld.SFW)

@Preview(name = "392dp charcoal orange", widthDp = 392, heightDp = 100)
@Preview(name = "320dp charcoal orange 200%", widthDp = 320, heightDp = 150, fontScale = 2f)
@Composable
private fun InnerDockPreview() = DockPreview(PrototypeWorld.NSFW)

@Preview(name = "392dp white blue low effects", widthDp = 392, heightDp = 100)
@Composable
private fun OuterLowDockPreview() = DockPreview(PrototypeWorld.SFW, lowEffects = true)

@Preview(name = "392dp charcoal orange low effects", widthDp = 392, heightDp = 100)
@Composable
private fun InnerLowDockPreview() = DockPreview(PrototypeWorld.NSFW, lowEffects = true)
