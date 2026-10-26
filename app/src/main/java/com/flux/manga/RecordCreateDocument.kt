package com.flux.manga

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import com.flux.manga.ui.pages.RecordExportModel

/** Memory only: rotation retains the recipient, process death never replays an export. */
internal class RecordExportPickerSelection : ViewModel() {
    private var pending: RecordExportModel? = null
    fun request(model: RecordExportModel): Boolean {
        if (pending != null || !model.preparePicker()) return false
        pending = model
        return true
    }
    fun result(uri: Uri?) { val model = pending; pending = null; model?.receive(uri) }
    fun failure() { val model = pending; pending = null; model?.pickerFailed() }
    override fun onCleared() { pending = null }
}

internal class RecordCreateDocument(mimeType: String = "application/json") : ActivityResultContracts.CreateDocument(mimeType) {
    override fun createIntent(context: Context, input: String): Intent {
        val intent = super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        val system = context.packageManager.queryIntentActivities(intent,
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY).firstOrNull {
                it.activityInfo.packageName in setOf("com.android.documentsui", "com.google.android.documentsui")
            }
        system?.activityInfo?.let { intent.component = ComponentName(it.packageName, it.name) }
        return intent
    }
}
