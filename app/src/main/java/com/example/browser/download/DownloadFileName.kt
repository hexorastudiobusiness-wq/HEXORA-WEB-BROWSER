package com.example.browser.download

import java.net.URLDecoder

/** Pure helpers to pick a display/download file name. */
object DownloadFileName {

    private val rfc5987Regex = Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
    private val plainRegex = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)

    /** Extracts a file name from a Content-Disposition header value, if present. */
    fun fromContentDisposition(headerValue: String?): String? {
        if (headerValue.isNullOrBlank()) return null
        rfc5987Regex.find(headerValue)?.let { match ->
            val decoded = runCatching {
                URLDecoder.decode(match.groupValues[1].trim(), Charsets.UTF_8.name())
            }.getOrNull()
            if (!decoded.isNullOrBlank()) return sanitize(decoded)
        }
        plainRegex.find(headerValue)?.let { match ->
            val name = match.groupValues[1].trim()
            if (name.isNotBlank()) return sanitize(name)
        }
        return null
    }

    /** Falls back to the last path segment of [url], or a generic name. */
    fun fromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val lastSegment = path.substringAfterLast('/').trim()
        if (lastSegment.isNotBlank()) {
            val decoded = runCatching {
                URLDecoder.decode(lastSegment, Charsets.UTF_8.name())
            }.getOrDefault(lastSegment)
            return sanitize(decoded)
        }
        return "download"
    }

    /** Strips path separators / control characters so the name is safe to display and store. */
    fun sanitize(name: String): String {
        val cleaned = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
            .trim()
        return cleaned.ifBlank { "download" }
    }
}
