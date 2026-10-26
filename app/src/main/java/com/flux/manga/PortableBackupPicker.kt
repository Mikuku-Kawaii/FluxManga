package com.flux.manga

import android.net.Uri
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import com.flux.manga.ui.pages.PortableBackupModel

/** Activity-owned result registration; never persist a URI, key or pending write across process death. */
internal class PortableBackupSelection : ViewModel() {
    private var pending: PortableBackupModel? = null
    fun request(model: PortableBackupModel, restore: Boolean): Boolean {
        if (pending != null || !model.preparePicker(restore)) return false
        pending = model; return true
    }
    fun result(uri: Uri?) { val recipient = pending; pending = null; recipient?.receive(uri) }
    fun failure() { val recipient = pending; pending = null; recipient?.pickerFailed() }
}

internal class PortableBackupOpenDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        val system = context.packageManager.queryIntentActivities(intent,
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY).firstOrNull {
                it.activityInfo.packageName in setOf("com.android.documentsui", "com.google.android.documentsui")
            }
        system?.activityInfo?.let { intent.component = ComponentName(it.packageName, it.name) }
        return intent
    }
}
