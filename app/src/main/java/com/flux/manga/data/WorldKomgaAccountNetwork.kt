package com.flux.manga.data

import com.flux.manga.core.*
import com.flux.manga.session.WorldSession

internal class WorldKomgaAccountNetwork(private val session: WorldSession) : KomgaAccountNetwork {
    private fun client(c: KomgaConfiguration) = session.komgaSources.get(c.sourceId) ?: session.komgaSources.configure(c)
    override suspend fun verify(c: KomgaConfiguration) = client(c).accountId()
    override suspend fun lists(c: KomgaConfiguration, kind: KomgaListKind, page: Int) = client(c).lists(kind, page)
    override suspend fun entries(c: KomgaConfiguration, list: KomgaAuthorizedList, page: Int) = client(c).entries(list, page)
}
