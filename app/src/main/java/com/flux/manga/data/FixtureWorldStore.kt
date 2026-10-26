package com.flux.manga.data

import android.content.Context
import com.flux.manga.ui.navigation.PrototypeWorld
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

typealias SampleKey = MangaKey
data class WorldPaths(val privateFiles: File, val cache: File, val databaseName: String)

/** Preparation opens the target business DB before WorldSession commits the new identity. */
class FixtureWorldStore(context: Context, private val awaitPreparation: suspend () -> Unit = {}) {
    private val data = WorldDataStore(context)

    fun paths(world: PrototypeWorld) = WorldPaths(
        data.directory(world),
        data.cacheDirectory(world),
        data.databaseFile(world).name,
    )
    suspend fun prepare(world: PrototypeWorld, fail: Boolean = false) {
        awaitPreparation()
        withContext(Dispatchers.IO) {
            if (fail) throw IOException("Fixed preparation failure")
            val paths = paths(world)
            for (directory in listOf(paths.privateFiles, paths.cache)) {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot prepare fixture directory")
            }
        }
        data.prepare(world)
    }
}

