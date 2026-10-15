package com.example.browser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialOriginTest {

    @Test
    fun `normalizes scheme and host without changing exact origin`() {
        assertEquals(
            "https://example.com",
            CredentialOrigin.fromUrl("HTTPS://Example.COM/login?next=/home"),
        )
        assertEquals(
            "http://example.com",
            CredentialOrigin.fromUrl("http://example.com/login"),
        )
    }

    @Test
    fun `keeps non default ports and drops default ports`() {
        assertEquals(
            "https://example.com:8443",
            CredentialOrigin.fromUrl("https://example.com:8443/login"),
        )
        assertEquals(
            "https://example.com",
            CredentialOrigin.fromUrl("https://example.com:443/login"),
        )
    }

    @Test
    fun `does not merge different schemes or subdomains`() {
        assertEquals(
            "https://login.example.com",
            CredentialOrigin.fromUrl("https://login.example.com/auth"),
        )
        assertNull(CredentialOrigin.fromUrl("about:blank"))
        assertNull(CredentialOrigin.fromUrl("ftp://example.com/login"))
    }
}
