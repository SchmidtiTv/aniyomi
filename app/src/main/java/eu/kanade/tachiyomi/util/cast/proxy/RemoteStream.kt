package eu.kanade.tachiyomi.util.cast.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.copyTo
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val HLS_CONTENT_TYPES = setOf(
    "application/vnd.apple.mpegurl",
    "application/x-mpegurl",
    "audio/mpegurl",
)

private fun String.isHlsContentType() =
    HLS_CONTENT_TYPES.any { lowercase().startsWith(it) }

private fun String.isM3u8Url() =
    substringBefore("?").trimEnd('/').endsWith(".m3u8", ignoreCase = true)

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private fun resolveSegmentUrl(baseUrl: String, href: String): String =
    if (href.startsWith("http://") || href.startsWith("https://")) {
        href
    } else {
        URI(baseUrl).resolve(href).toString()
    }

private fun rewriteM3u8(
    content: String,
    originalUrl: String,
    proxyBaseUrl: String,
    headers: Map<String, String>,
): String {
    val headersParam = if (headers.isNotEmpty()) "&headers=${headers.encodeAsParam()}" else ""

    fun proxyUrl(href: String): String {
        val resolved = resolveSegmentUrl(originalUrl, href)
        return "$proxyBaseUrl?url=${resolved.urlEncode()}$headersParam"
    }

    return content.lines().joinToString("\n") { line ->
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> line

            trimmed.startsWith("#") ->
                if ("URI=\"" in trimmed) {
                    trimmed.replace(Regex("""URI="([^"]+)"""")) { match ->
                        """URI="${proxyUrl(match.groupValues[1])}""""
                    }
                } else {
                    line
                }

            // Bare URL line (segment or variant playlist)
            else -> proxyUrl(trimmed)
        }
    }
}

internal suspend fun ApplicationCall.handleRemoteStream(
    httpClient: HttpClient,
    url: String,
    headers: Map<String, String>,
) {
    val rangeHeader = request.headers["Range"]

    val response = httpClient.get(url) {
        headers.forEach { (key, value) -> header(key, value) }
        rangeHeader?.let { header("Range", it) }
    }

    val contentTypeStr = response.contentType()?.toString().orEmpty()
    val isHls = contentTypeStr.isHlsContentType() || url.isM3u8Url()

    if (isHls) {
        val proxyBaseUrl = "http://${request.host()}:${request.port()}/proxy"
        val rewritten = rewriteM3u8(
            content = response.bodyAsText(),
            originalUrl = url,
            proxyBaseUrl = proxyBaseUrl,
            headers = headers,
        )
        respondText(
            text = rewritten,
            contentType = ContentType.parse("application/vnd.apple.mpegurl"),
            status = response.status,
        )
    } else {
        respondBytesWriter(
            contentType = response.contentType(),
            status = response.status,
        ) {
            response.bodyAsChannel().copyTo(this)
        }
    }
}
