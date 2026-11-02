package com.example.browser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun `full urls are kept as-is`() {
        assertEquals(
            "https://example.com/page",
            UrlUtils.resolveInput("https://example.com/page", SearchEngine.GOOGLE),
        )
        assertEquals(
            "http://example.com",
            UrlUtils.resolveInput("http://example.com", SearchEngine.GOOGLE),
        )
    }

    @Test
    fun `bare domains get https prefix`() {
        assertEquals(
            "https://example.com",
            UrlUtils.resolveInput("example.com", SearchEngine.GOOGLE),
        )
        assertEquals(
            "https://sub.example.co.uk/path?q=1",
            UrlUtils.resolveInput("sub.example.co.uk/path?q=1", SearchEngine.GOOGLE),
        )
    }

    @Test
    fun `plain words become a search`() {
        assertEquals(
            "https://www.google.com/search?q=cats",
            UrlUtils.resolveInput("cats", SearchEngine.GOOGLE),
        )
        assertEquals(
            "https://duckduckgo.com/?q=hello+world",
            UrlUtils.resolveInput("hello world", SearchEngine.DUCKDUCKGO),
        )
    }

    @Test
    fun `looksLikeUrl detects hosts and schemes only`() {
        assertTrue(UrlUtils.looksLikeUrl("example.com"))
        assertTrue(UrlUtils.looksLikeUrl("https://example.com"))
        assertFalse(UrlUtils.looksLikeUrl("hello world"))
        assertFalse(UrlUtils.looksLikeUrl("localhost"))
        assertFalse(UrlUtils.looksLikeUrl(""))
    }

    @Test
    fun `non-web schemes are searched instead of navigated to`() {
        assertFalse(UrlUtils.looksLikeUrl("file:///etc/passwd"))
        assertFalse(UrlUtils.looksLikeUrl("intent://example.com/#Intent;scheme=https;end"))
        assertTrue(
            UrlUtils.resolveInput("file:///etc/passwd", SearchEngine.GOOGLE)
                .startsWith("https://www.google.com/search?q="),
        )
    }

    @Test
    fun `valid custom search template is used and invalid falls back`() {
        assertEquals(
            "https://search.example/?q=hello+world",
            UrlUtils.resolveInput(
                "hello world",
                SearchEngine.CUSTOM,
                "https://search.example/?q=%s",
            ),
        )
        assertEquals(
            "https://www.google.com/search?q=cats",
            UrlUtils.resolveInput(
                "cats",
                SearchEngine.CUSTOM,
                "search.example/?q=",
            ),
        )
    }

    @Test
    fun `isHttpUrl only accepts http and https`() {
        assertTrue(UrlUtils.isHttpUrl("https://example.com"))
        assertTrue(UrlUtils.isHttpUrl("http://example.com"))
        assertFalse(UrlUtils.isHttpUrl("about:blank"))
        assertFalse(UrlUtils.isHttpUrl("ftp://example.com"))
    }
}
