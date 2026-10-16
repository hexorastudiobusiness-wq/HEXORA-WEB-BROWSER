package com.example.browser.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Best-effort favicon discovery for user-created Start Page shortcuts.
 *
 * Candidates are taken from the page's own <link rel="icon"> declarations
 * when readable, then the conventional /apple-touch-icon.png, /favicon.png
 * and /favicon.ico locations. Downloads are size-capped; SVG or otherwise
 * undecodable icons simply leave the letter placeholder in place.
 */
class FaviconFetcher {

    suspend fun fetch(pageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val pageUri = URI(pageUrl)
            val scheme = pageUri.scheme?.lowercase()
            if ((scheme != "http" && scheme != "https") || pageUri.host.isNullOrBlank()) {
                return@runCatching null
            }
            val candidates = linkedSetOf<String>()
            candidates += htmlIconCandidates(pageUri)
            candidates += pageUri.resolve("/apple-touch-icon.png").toString()
            candidates += pageUri.resolve("/favicon.png").toString()
            candidates += pageUri.resolve("/favicon.ico").toString()

            candidates.asSequence()
                .take(MAX_CANDIDATES)
                .mapNotNull { downloadBitmap(it) }
                .firstOrNull { it.width >= MIN_ICON_SIZE && it.height >= MIN_ICON_SIZE }
        }.getOrNull()
    }

    private fun htmlIconCandidates(pageUri: URI): List<String> {
        val html = downloadText(pageUri.toString(), MAX_HTML_BYTES) ?: return emptyList()
        return LINK_TAG_REGEX.findAll(html)
            .mapNotNull { match ->
                val tag = match.value
                val rel = attribute(tag, "rel")?.lowercase() ?: return@mapNotNull null
                if (!rel.contains("icon")) return@mapNotNull null
                val href = attribute(tag, "href")?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                runCatching { pageUri.resolve(href).toString() }.getOrNull()
            }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .take(3)
            .toList()
    }

    private fun attribute(tag: String, name: String): String? {
        val regex = Regex(
            """\b$name\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s>]+))""",
            RegexOption.IGNORE_CASE,
        )
        val match = regex.find(tag) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }

    private fun downloadBitmap(url: String): Bitmap? = runCatching {
        val bytes = downloadBytes(url, MAX_ICON_BYTES) ?: return@runCatching null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun downloadText(url: String, maxBytes: Int): String? = runCatching {
        val bytes = downloadBytes(url, maxBytes) ?: return@runCatching null
        String(bytes, Charsets.UTF_8)
    }.getOrNull()

    private fun downloadBytes(url: String, maxBytes: Int): ByteArray? {
        val connection = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "image/*,text/html;q=0.5,*/*;q=0.1")
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
        const val MAX_CANDIDATES = 6
        const val MIN_ICON_SIZE = 16
        const val MAX_HTML_BYTES = 192 * 1024
        const val MAX_ICON_BYTES = 1024 * 1024
        const val USER_AGENT = "HexoraBrowser/0.3 Android"
        val LINK_TAG_REGEX = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    }
}
