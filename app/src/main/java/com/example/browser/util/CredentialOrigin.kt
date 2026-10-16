package com.example.browser.util

import java.net.URI

/** Exact-origin helpers for saved-login matching. */
object CredentialOrigin {

    /** Returns scheme://host[:non-default-port] for http(s), otherwise null. */
    fun fromUrl(url: String): String? {
        if (!UrlUtils.isHttpUrl(url)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val defaultPort = if (scheme == "https") 443 else 80
        val authority = if (uri.port != -1 && uri.port != defaultPort) {
            "$host:${uri.port}"
        } else {
            host
        }
        return "$scheme://$authority"
    }
}
