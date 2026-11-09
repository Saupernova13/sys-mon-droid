package com.raavivi.sysmon.core.tracker.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Strips dashes and spaces and upper-cases; null unless exactly 8 letters or digits. */
fun normalizeCode(raw: String?): String? {
    val code = raw.orEmpty().filter { it != '-' && !it.isWhitespace() }.uppercase()
    return code.takeIf { it.length == 8 && it.all { c -> c in 'A'..'Z' || c in '0'..'9' } }
}

/**
 * Reduces a server address to an origin `http(s)://host[:port]`. A missing scheme
 * defaults to http (LAN use). Null when it is not a usable URL.
 */
fun normalizeServer(raw: String?): String? {
    var text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    if (!text.contains("://")) text = "http://$text"
    val url = text.toHttpUrlOrNull() ?: return null
    val host = if (url.host.contains(':')) "[${url.host}]" else url.host
    val defaultPort = if (url.scheme == "https") 443 else 80
    val port = if (url.port == defaultPort) "" else ":${url.port}"
    return "${url.scheme}://$host$port"
}
