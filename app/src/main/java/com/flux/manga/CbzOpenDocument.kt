package com.flux.manga

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Retain undelivered selection across rotation without persisting a replayable batch in a Bundle. */
internal class CbzPickerSelection : ViewModel() {
    var pending by mutableStateOf<List<Uri>>(emptyList())
}

/** Use the system SAF picker when present; OEM file previews may not return a document. */
internal class CbzOpenDocument : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        val documentsUi = context.packageManager.queryIntentActivities(
            intent, PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY,
        ).firstOrNull { it.activityInfo.packageName in setOf("com.android.documentsui", "com.google.android.documentsui") }
        documentsUi?.activityInfo?.let { intent.component = ComponentName(it.packageName, it.name) }
        return intent // Other systems retain their registered ACTION_OPEN_DOCUMENT handler.
    }
}
