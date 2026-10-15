package com.example.browser.util

import java.net.URLEncoder

/** Search engines supported by the address bar. Templates use one %s placeholder. */
enum class SearchEngine(
    val id: String,
    val displayName: String,
    val queryTemplate: String,
) {
    GOOGLE("google", "Google", "https://www.google.com/search?q=%s"),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    BING("bing", "Bing", "https://www.bing.com/search?q=%s");

    fun searchUrl(query: String): String =
        queryTemplate.format(URLEncoder.encode(query, Charsets.UTF_8.name()))

    companion object {
        fun fromId(id: String?): SearchEngine =
            entries.firstOrNull { it.id == id } ?: GOOGLE
    }
}

/**
 * Pure address-bar input handling (ARCHITECTURE.md §7):
 * valid URLs navigate directly, everything else becomes a search.
 */
object UrlUtils {

    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
    private val hostLikeRegex = Regex("^[^\\s/]+\\.[^\\s/.]+(:\\d+)?(/.*)?$")

    fun isHttpUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("http://", ignoreCase = true)

    /** True when [input] should be treated as a URL instead of a search query. */
    fun looksLikeUrl(input: String): Boolean {
        val text = input.trim()
        if (text.isEmpty() || text.contains(' ')) return false
        if (schemeRegex.matches(text)) return true
        if (text.startsWith("about:", ignoreCase = true)) return true
        val hostPart = text.substringBefore('/').substringBefore('?')
        return hostLikeRegex.matches(text) && !hostPart.startsWith(".")
    }

    /** Resolves raw address-bar text into the URL that should be loaded. */
    fun resolveInput(input: String, searchEngine: SearchEngine): String {
        val text = input.trim()
        if (text.isEmpty()) return text
        if (!looksLikeUrl(text)) return searchEngine.searchUrl(text)
        return when {
            schemeRegex.matches(text) -> text
            text.startsWith("about:", ignoreCase = true) -> text
            else -> "https://$text"
        }
    }
}
