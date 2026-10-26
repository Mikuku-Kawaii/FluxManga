package com.flux.manga.data

import com.flux.manga.core.*
import com.flux.manga.session.WorldSession
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*

internal class WorldCopyAccountNetwork(private val session: WorldSession) : CopyAccountNetwork {
    override suspend fun signIn(username: String, password: String): CopyAccount = withContext(Dispatchers.IO) {
        val salt = SecureRandom().nextInt(10000)
        val bytes = "$password-$salt".toByteArray()
        val encoded = try { Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
        WorldCopyMangaClient(session, "copymanga-account-${UUID.randomUUID()}", service = CopyAccountService.OFFICIAL).use { client ->
            val candidate = client.signIn(username, encoded, salt.toUInt())
            CopyAccount(candidate.userId, candidate.username, candidate.token, CopyAccountService.OFFICIAL)
        }
    }
    override suspend fun verify(c: CopyAccount): CopyAccountIdentity = withContext(Dispatchers.IO) {
        // Candidate credentials never replace the active pool until verification and persistence succeed.
        WorldCopyMangaClient(session, c.sourceId, c.token, c.service).use { it.accountInfo() }
    }
    override suspend fun collection(c: CopyAccount, offset: Int): CopyCollectionPage = withContext(Dispatchers.IO) {
        session.copyMangaSources.account(c).collection(offset)
    }
    override fun credentialsChanged() { session.copyMangaSources.closeAccount() }
}
