package com.flux.manga.data

import java.io.*
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal fun isBaoziAccount(id: String) = id.matches(Regex("baozimh-account-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
internal fun isBaoziSource(id: String) = id == BaoziEndpoint.SOURCE_ID || isBaoziAccount(id)
internal fun baoziAccountId(username: String): String {
    require(username.isNotBlank() && username.length <= 256)
    return "baozimh-account-${UUID.nameUUIDFromBytes((BaoziEndpoint.BASE_URL + "\n" + username).toByteArray())}"
}

internal data class BaoziImportItem(val id: String, val title: String)
internal data class BaoziImportJob(val id: String, val items: List<BaoziImportItem>, val favorite: Boolean,
    val cursor: Int = 0, val added: Int = 0, val existing: Int = 0) {
    val remaining get() = items.size - cursor
}
/** Only the official account origin receives these cookies; never the anonymous reader/CDN. */
internal val BaoziAccountOrigins = setOf("https://cn.bzmgcn.com/", "https://cn.baozimh.com/", "https://www.baozimh.com/")
internal data class BaoziBrowserSession(val cookie: String, val userAgent: String,
    val origin: String = "https://cn.bzmgcn.com/") {
    override fun toString() = "BaoziBrowserSession(redacted)"
    fun validate(token: String) {
        require(origin in BaoziAccountOrigins)
        require(userAgent.length in 1..1024 && userAgent.none { it == '\r' || it == '\n' })
        require(cookie.length in 1..16384)
        val parts = cookie.split(';').map(String::trim)
        require(parts.count { it.startsWith("TSID=") } == 1 && parts.contains("TSID=$token"))
        require(parts.all { part ->
            val name = part.substringBefore('=')
            (name == "TSID" || name.matches(Regex("__Host-gk_[A-Za-z0-9_]+"))) &&
                part.contains('=') && part.substringAfter('=').isNotEmpty() &&
                part.all { it.code in 0x21..0x7e && it != ',' }
        })
    }
}
internal data class BaoziSavedAccount(val sourceId: String, val username: String, val token: String?, val job: BaoziImportJob? = null,
    val browserSession: BaoziBrowserSession? = null, val autoLogin: BaoziAutoLogin? = null) {
    override fun toString() = "BaoziSavedAccount(redacted)"
}

/** Only stored inside WorldDataStore.accountImportDatabase's AES-GCM / Keystore envelope. */
internal data class BaoziAutoLogin(val username: String, val password: String, val origin: String) {
    override fun toString() = "BaoziAutoLogin(redacted)"
}

/** Bounded versioned binary record, inside the existing authenticated Keystore envelope. */
internal object BaoziAccountCodec {
    fun encode(account: BaoziSavedAccount): ByteArray {
        validate(account)
        return ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(4); out.writeUTF(account.sourceId); out.writeUTF(account.username)
            out.writeBoolean(account.token != null); account.token?.let(out::writeUTF)
            out.writeBoolean(account.job != null)
            account.job?.let { job ->
                out.writeUTF(job.id); out.writeBoolean(job.favorite); out.writeInt(job.cursor)
                out.writeInt(job.added); out.writeInt(job.existing); out.writeInt(job.items.size)
                job.items.forEach { out.writeUTF(it.id); out.writeUTF(it.title) }
            }
            out.writeBoolean(account.browserSession != null)
            account.browserSession?.let { out.writeUTF(it.cookie); out.writeUTF(it.userAgent); out.writeUTF(it.origin) }
            out.writeBoolean(account.autoLogin != null)
            account.autoLogin?.let { out.writeUTF(it.username); out.writeUTF(it.password); out.writeUTF(it.origin) }
        } }.toByteArray()
    }
    fun decode(bytes: ByteArray): BaoziSavedAccount {
        require(bytes.size <= 4 * 1024 * 1024)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt(); require(version in 1..4)
            val source = input.readUTF(); val name = input.readUTF()
            val token = if (input.readBoolean()) input.readUTF() else null
            val job = if (input.readBoolean()) {
                val id = input.readUTF(); val favorite = input.readBoolean(); val cursor = input.readInt()
                val added = input.readInt(); val existing = input.readInt(); val size = input.readInt()
                require(size in 1..1000)
                BaoziImportJob(id, List(size) { BaoziImportItem(input.readUTF(), input.readUTF()) }, favorite, cursor, added, existing)
            } else null
            val browser = if (version >= 2 && input.readBoolean()) {
                val cookie = input.readUTF(); val agent = input.readUTF()
                BaoziBrowserSession(cookie, agent, if (version >= 3) input.readUTF() else "https://cn.bzmgcn.com/")
            } else null
            val login = if (version >= 4 && input.readBoolean())
                BaoziAutoLogin(input.readUTF(), input.readUTF(), input.readUTF()) else null
            require(input.available() == 0)
            BaoziSavedAccount(source, name, token, job, browser, login).also(::validate)
        }
    }
    private fun validate(account: BaoziSavedAccount) {
        require(account.sourceId == baoziAccountId(account.username))
        account.browserSession?.validate(requireNotNull(account.token))
        account.autoLogin?.let {
            require(it.username == account.username && it.origin in BaoziAccountOrigins)
            require(it.origin == account.browserSession?.origin)
            require(it.password.toByteArray().size in 1..4096 && '\u0000' !in it.password)
            require(account.token != null)
        }
        require(account.token == null || (account.token.length in 1..4096 && account.token.all {
            it.code == 0x21 || it.code in 0x23..0x2b || it.code in 0x2d..0x3a || it.code in 0x3c..0x5b || it.code in 0x5d..0x7e
        }))
        account.job?.let { job ->
            require(job.id.length in 1..64 && job.items.size in 1..1000 && job.items.distinctBy { it.id }.size == job.items.size)
            require(job.cursor in 0..job.items.size && job.added in 0..job.cursor && job.existing == job.cursor - job.added)
            require(job.items.all { it.id.matches(Regex("[A-Za-z0-9_-]{1,512}")) && it.title.isNotBlank() && it.title.length <= 1024 })
        }
    }
}

internal class BaoziAccountStore(private val database: AccountImportDatabase) {
    private val activeSlot = "baozi-active-account-v1"
    private fun slot(source: String) = "baozi-account-v1:$source"
    private fun AccountImportRows.account(source: String): BaoziSavedAccount? {
        require(isBaoziAccount(source))
        val bytes = read(slot(source)) ?: return null
        return try { BaoziAccountCodec.decode(bytes).also { require(it.sourceId == source) } }
        finally { bytes.fill(0) }
    }
    private fun AccountImportRows.active(): BaoziSavedAccount? {
        val bytes = read(activeSlot) ?: return null
        val source = try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
        return account(source) ?: throw IOException("账号记录不完整；未重置原配置")
    }
    private fun AccountImportRows.save(account: BaoziSavedAccount) {
        val bytes = BaoziAccountCodec.encode(account)
        try { write(slot(account.sourceId), bytes) } finally { bytes.fill(0) }
    }
    suspend fun current(allowed: () -> Boolean): BaoziSavedAccount? = database.transaction(allowed) { it.active() }
    suspend fun activate(source: String, name: String, token: String, allowed: () -> Boolean): BaoziSavedAccount =
        activate(source, name, token, null, allowed = allowed)
    suspend fun activate(source: String, name: String, token: String, browserSession: BaoziBrowserSession?,
        autoLogin: BaoziAutoLogin? = null, allowed: () -> Boolean): BaoziSavedAccount = database.transaction(allowed) { rows ->
        require(source == baoziAccountId(name))
        val old = rows.active()
        if (old != null && old.sourceId != source) rows.save(old.copy(token = null, browserSession = null, autoLogin = null))
        val previous = rows.account(source)
        val login = autoLogin ?: previous?.autoLogin?.takeIf { it.origin == browserSession?.origin }
        val account = BaoziSavedAccount(source, name, token, previous?.job, browserSession, login)
        rows.save(account); rows.write(activeSlot, source.toByteArray()); account
    }
    suspend fun logout(source: String, allowed: () -> Boolean): BaoziSavedAccount = database.transaction(allowed) { rows ->
        val account = rows.requireAccount(source)
        account.copy(token = null, browserSession = null, autoLogin = null).also { rows.save(it) }
    }
    private fun AccountImportRows.requireAccount(source: String): BaoziSavedAccount = active()?.takeIf { it.sourceId == source }
        ?: throw CancellationException("Account changed")

    suspend fun begin(source: String, items: List<BaoziImportItem>, favorite: Boolean, allowed: () -> Boolean): BaoziSavedAccount = database.transaction(allowed) { rows ->
        val account = rows.requireAccount(source)
        require(account.token != null && account.job?.remaining.let { it == null || it == 0 }) { "请先继续或结束上次导入任务" }
        account.copy(job = BaoziImportJob(UUID.randomUUID().toString(), items.distinctBy { it.id }, favorite)).also { rows.save(it) }
    }
    suspend fun batch(source: String, jobId: String, allowed: () -> Boolean): BaoziSavedAccount = database.transaction(allowed) { rows ->
        val account = rows.requireAccount(source)
        require(account.token != null)
        val job = requireNotNull(account.job); require(job.id == jobId)
        var added = job.added; var existing = job.existing
        val end = minOf(job.cursor + 25, job.items.size)
        for (item in job.items.subList(job.cursor, end)) {
            if (!allowed()) throw CancellationException("Expired import")
            val old = rows.book(source, item.id)
            val progress = rows.hasProgress(source, item.id)
            if (old != null || progress) existing++ else added++
            rows.write("baozi-import-ref:$source:${item.id}", ("my_bookshelf\n" + job.id).toByteArray())
            if (old == null) rows.putBook(OnlineBook(source, item.id, item.title,
                if (progress) false else job.favorite, System.currentTimeMillis()))
        }
        account.copy(job = job.copy(cursor = end, added = added, existing = existing)).also { rows.save(it) }
    }
    suspend fun finishTask(source: String, allowed: () -> Boolean): BaoziSavedAccount = database.transaction(allowed) { rows ->
        rows.requireAccount(source).copy(job = null).also { rows.save(it) }
    }
}
