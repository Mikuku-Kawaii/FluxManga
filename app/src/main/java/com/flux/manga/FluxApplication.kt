package com.flux.manga

class FluxApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        com.flux.manga.data.SourceBypassNetwork.initialize(this)
    }
}
