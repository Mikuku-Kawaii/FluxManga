package com.flux.manga.data

import com.flux.manga.ui.navigation.PrototypeWorld
import java.net.URI
import java.io.IOException
import org.json.JSONObject

/** UI accepts HTTPS only; transport's loopback exception remains for protocol fixtures. */
internal fun normalizeKomgaUrl(input: String): String {
    val uri = try { URI(input.trim()) } catch (_: Exception) { throw IllegalArgumentException("请输入有效的 HTTPS 服务地址") }
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65535)) {
        "请使用 HTTPS 地址，不包含账号、查询参数或片段"
    }
    val path = uri.rawPath.orEmpty()
    require(path.split('/').none { it == "." || it == ".." }) { "服务地址不能包含相对路径" }
    return uri.toASCIIString().trimEnd('/') + "/"
}

/** Existing version-1 API-key records remain readable; first verified save upgrades payload only. */
internal class KomgaSettingsStore(store: WorldDataStore) {
    internal val accounts = KomgaAccountStore(store.accountImportDatabase(), ::legacyKomgaConfiguration)
    suspend fun load(): KomgaConfiguration? = accounts.current { true }?.takeIf { it.hasCredential }
    suspend fun loadRecord(): KomgaConfiguration? = accounts.current { true }
    suspend fun save(configuration: KomgaConfiguration, allowed: () -> Boolean) = accounts.save(configuration, allowed)
}
internal fun legacyKomgaConfiguration(bytes: ByteArray): KomgaConfiguration {
    val json = JSONObject(bytes.toString(Charsets.UTF_8))
    check(json.getInt("version") == 1)
    val id = json.getString("sourceId"); val key = json.getString("apiKey")
    check(id.matches(Regex("komga-[0-9a-f-]{36}")) && key.isNotBlank())
    return KomgaConfiguration(id, normalizeKomgaUrl(json.getString("baseUrl")), key)
}
