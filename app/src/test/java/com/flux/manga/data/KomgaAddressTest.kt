package com.flux.manga.data

import org.junit.Assert.*
import org.junit.Test

class KomgaAddressTest {
    @Test fun preservesReverseProxyPathAndPort() {
        assertEquals("https://example.test:8443/komga/", normalizeKomgaUrl(" https://example.test:8443/komga "))
        assertEquals("https://example.test/", normalizeKomgaUrl("https://example.test"))
        assertEquals("https://example.test/komga/", normalizeKomgaUrl("https://example.test/komga/"))
    }
    @Test fun rejectsCleartextAndCredentialBearingAddressesWithoutEchoingInput() {
        for (input in listOf("http://127.0.0.1:8000/", "http://example.test/",
            "https://user:secret-token@example.test/", "https://example.test/?api_key=secret-token",
            "https://example.test/#secret-token", "https://", "not a URL secret-token",
            "https://example.test:99999/", "https://example.test/a/../")) {
            try { normalizeKomgaUrl(input); fail("Address should be rejected") }
            catch (error: IllegalArgumentException) { assertFalse(error.message.orEmpty().contains("secret-token")) }
        }
    }
}
