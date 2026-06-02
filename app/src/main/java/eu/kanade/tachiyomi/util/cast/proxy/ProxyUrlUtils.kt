package eu.kanade.tachiyomi.util.cast.proxy

import java.net.URLDecoder
import java.net.URLEncoder

fun Map<String, String>.encodeAsParam(): String =
    entries
        .joinToString("&") { (k, v) -> "${k.urlEncode()}=${v.urlEncode()}" }
        .urlEncode()

fun decodeHeadersParam(encoded: String): Map<String, String> {
    val raw = URLDecoder.decode(encoded, "UTF-8")
    if (raw.isBlank()) return emptyMap()
    return raw.split("&").associate { pair ->
        val (k, v) = pair.split("=", limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }
}

private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")
