package com.modeltest.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlSecurityTest {
    @Test
    fun normalizeBaseUrlAddsLocalHttpScheme() {
        assertEquals("http://10.0.0.2:6006", normalizeBaseUrl(" 10.0.0.2:6006/ "))
    }

    @Test
    fun privateAndLocalHttpHostsAreAccepted() {
        val urls = listOf(
            "http://10.0.2.2:6006",
            "http://172.16.0.8:6006",
            "http://192.168.1.5:6006",
            "http://localhost:6006",
            "http://training-server:6006",
            "http://monitor.local:6006",
            "http://[fd00::1]:6006",
        )
        urls.forEach { url -> assertNull(url, serverUrlValidationError(url)) }
    }

    @Test
    fun publicHttpHostsAreRejected() {
        val urls = listOf(
            "http://example.com:6006",
            "http://8.8.8.8:6006",
            "http://172.32.0.1:6006",
        )
        urls.forEach { url ->
            assertTrue(url, serverUrlValidationError(url)?.contains("HTTPS") == true)
        }
    }

    @Test
    fun httpsHostsAreAccepted() {
        assertNull(serverUrlValidationError("https://monitor.example.com"))
        assertNull(serverUrlValidationError("https://8.8.8.8:6006"))
    }

    @Test
    fun unsupportedOrAmbiguousUrlsAreRejected() {
        assertTrue(serverUrlValidationError("ftp://example.com")?.contains("HTTP") == true)
        assertTrue(serverUrlValidationError("http://user:pass@192.168.1.5")?.contains("格式") == true)
        assertTrue(serverUrlValidationError("https://example.com?token=secret")?.contains("格式") == true)
    }
}
