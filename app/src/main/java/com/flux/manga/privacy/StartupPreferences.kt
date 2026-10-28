package com.flux.manga.privacy

import android.content.Context
import com.flux.manga.ui.navigation.MainTab
import com.flux.manga.ui.navigation.PrototypeWorld

/** Only startup choices are persisted here, never private routes or content. */
internal class StartupPreferences(context: Context) {
    private val values = context.getSharedPreferences("startup-preferences", Context.MODE_PRIVATE)
    var returnToPublic: Boolean
        get() = values.getBoolean("return-public", true)
        set(value) { values.edit().putBoolean("return-public", value).apply() }
    var backgroundSeconds: Int
        get() = values.getInt("background-seconds", 30)
        set(value) { values.edit().putInt("background-seconds", value).apply() }
    var startPage: String
        get() = values.getString("start-page", "HOME") ?: "HOME"
        set(value) { values.edit().putString("start-page", value).apply() }
    fun initialTab(world: PrototypeWorld): MainTab {
        val name = if (startPage == "LAST") values.getString("last-tab-${world.name}", "HOME") else startPage
        return MainTab.primary.firstOrNull { it.name == name } ?: MainTab.HOME
    }
    fun rememberTab(world: PrototypeWorld, tab: MainTab) {
        values.edit().putString("last-tab-${world.name}", tab.name).apply()
    }
    fun rememberWorld(world: PrototypeWorld) { values.edit().putBoolean("last-private", world == PrototypeWorld.NSFW).apply() }
    fun restorePrivate() = !returnToPublic && values.getBoolean("last-private", false)
}
