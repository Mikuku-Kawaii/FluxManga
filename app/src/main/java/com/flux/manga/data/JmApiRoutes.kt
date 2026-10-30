package com.flux.manga.data

import kotlinx.coroutines.sync.Mutex

/** Generation-owned routing hint only; clients keep their own cookies and account snapshot. */
internal class JmApiRoutes {
    val selection = Mutex()
    // Accessed under selection. A manual preference change starts with that preference again.
    private var successful: Pair<String, String>? = null

    fun candidates(preferred: String, excluded: Set<String>): List<String> =
        (listOfNotNull(successful?.takeIf { it.first == preferred }?.second, preferred) + JM_APIS)
            .distinct().filterNot { it in excluded }

    fun remember(preferred: String, host: String) { successful = preferred to host }
    fun forget(hosts: Set<String>) {
        successful?.second?.let { if (it in hosts) successful = null }
    }
}
