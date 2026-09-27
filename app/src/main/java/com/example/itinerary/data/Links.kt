package com.example.itinerary.data

import java.net.URI

// Web links kept as attachments. Only http and https addresses are accepted, so a link can never be something that
// opens another app's internals (intent:, file:, javascript: and so on).
object Links {
    const val MAX_URL_LENGTH = 2000
    const val MAX_NAME_LENGTH = 60

    // What is stored for a link's attachment record instead of a MIME type of a file.
    const val MIME_TYPE = "text/uri-list"

    // Turns typed text into an address to store, or null if it isn't a usable web address. "example.com" becomes
    // "https://example.com"; an address that already has http:// or https:// is kept as typed (trimmed).
    fun normalize(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.length > MAX_URL_LENGTH) return null
        val withScheme = if ("://" in text) text else "https://$text"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return withScheme
    }

    // The name shown for a link when the user doesn't give one: the site, without a leading "www.".
    fun defaultName(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url
}
