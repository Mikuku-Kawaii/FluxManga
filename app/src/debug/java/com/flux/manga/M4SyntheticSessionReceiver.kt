package com.flux.manga

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.flux.manga.data.SyntheticPrivateReaderRepository
import java.util.UUID

/** Selects only a NEW synthetic namespace. No navigation, real source or credential bypass. */
class M4SyntheticSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.flux.manga.M4_NEW_SYNTHETIC_SESSION") return
        val namespace = "m4-private-reader-${UUID.randomUUID()}"
        SyntheticPrivateReaderRepository.admissionNamespace = namespace
        resultData = namespace
        Log.i("FluxM4Stage", "SYNTHETIC_SESSION pid=${Process.myPid()} namespace=$namespace")
    }
}
