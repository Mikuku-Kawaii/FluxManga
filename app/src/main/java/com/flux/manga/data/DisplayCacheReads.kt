package com.flux.manga.data

import java.io.IOException
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import com.flux.manga.storage.StorageAccessException
import com.flux.manga.storage.StorageProblem

internal fun Exception.isDisplayCacheFailure(): Boolean = when (this) {
    is StorageAccessException -> problem == StorageProblem.IO || problem == StorageProblem.PAYLOAD_INVALID
    is IOException, is JSONException, is AEADBadTagException -> true
    else -> false
}

/** Only disposable display cache reads belong here, never account/configuration/database reads. */
internal suspend fun <T> readDisplayCache(read: suspend () -> T): T? = try { read() }
catch (cancelled: CancellationException) { throw cancelled }
catch (failure: Exception) {
    if (!failure.isDisplayCacheFailure()) throw failure
    android.util.Log.w("FluxCache", "Display cache unavailable; fetching source again")
    null
}
