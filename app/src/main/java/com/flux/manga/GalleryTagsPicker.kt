package com.flux.manga

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.flux.manga.ui.pages.GalleryTagsModel

/** Activity-owned delivery survives configuration changes; process death drops the pending recipient. */
internal class GalleryTagsSelection : ViewModel() {
    private var pending: GalleryTagsModel? = null
    fun request(model: GalleryTagsModel): Boolean {
        if (pending != null || !model.preparePicker()) return false
        pending = model; return true
    }
    fun result(uri: Uri?) { val target = pending; pending = null; target?.receive(uri) }
    fun failure() { val target = pending; pending = null; target?.pickerFailed() }
}
