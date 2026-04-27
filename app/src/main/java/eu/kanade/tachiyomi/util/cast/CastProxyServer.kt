package eu.kanade.tachiyomi.util.cast

import android.content.Context
import android.provider.OpenableColumns
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.copyTo
import tachiyomi.core.common.util.system.logcat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object CastProxyServer {
    private const val DEFAULT_PORT = 8080

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var activePort: Int = DEFAULT_PORT
    private val headerStore = ConcurrentHashMap<String, Map<String, String>>()
    private val httpClient = HttpClient(OkHttp) {
        followRedirects = true
    }

    fun start(context: Context, port: Int = DEFAULT_PORT) {
        if (server != null) return

        activePort = port
        server = embeddedServer(Netty, port = port) {
            routing {
                get("/proxy") {
                    val originalUrl = call.parameters["url"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                    val videoHeaders = getHeadersForUrl(originalUrl)

                    logcat { "Proxying request for URL: $originalUrl with headers: $videoHeaders" }

                    if (originalUrl.startsWith("content://")) {
                        call.handleLocalFile(context, originalUrl)
                    } else {
                        call.handleRemoteStream(originalUrl, videoHeaders)
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        headerStore.clear()
    }

    fun proxiedUrl(context: Context, originalUrl: String, headers: Map<String, String> = emptyMap()): String {
        start(context)
        if (headers.isNotEmpty()) {
            headerStore[originalUrl] = headers
        }
        val encodedUrl = URLEncoder.encode(originalUrl, "UTF-8")
        return "http://${getLocalIpAddress()}:$activePort/proxy?url=$encodedUrl"
    }

    private suspend fun ApplicationCall.handleRemoteStream(
        url: String,
        headers: Map<String, String>,
    ) {
        val rangeHeader = request.headers["Range"]

        val response = httpClient.get(url) {
            headers.forEach { (key, value) -> header(key, value) }
            rangeHeader?.let { header("Range", it) }
        }

        respondBytesWriter(
            contentType = response.contentType(),
            status = response.status,
        ) {
            response.bodyAsChannel().copyTo(this)
        }
    }

    private suspend fun ApplicationCall.handleLocalFile(
        context: Context,
        originalUrl: String,
    ) {
        val uri = originalUrl.toUri()
        logcat { "Handling local file URI: $uri" }

        // Resolve file size
        var fileSize = -1L
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                fileSize = pfd.statSize
            }
        } catch (e: Exception) {
            logcat { "statSize failed, falling back to query: ${e.message}" }
        }

        if (fileSize == -1L) {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                    }
                }
            } catch (e: Exception) {
                logcat { "Size query failed: ${e.message}" }
            }
        }

        logcat { "Resolved file size: $fileSize bytes" }

        val mimeType = getMimeType(originalUrl)
        val rangeHeader = request.headers["Range"]

        if (rangeHeader != null && fileSize > 0) {
            val match = Regex("bytes=(\\d+)-(.*)").find(rangeHeader)
            if (match != null) {
                val start = match.groupValues[1].toLong()
                val endStr = match.groupValues[2]
                val end = if (endStr.isNotEmpty()) endStr.toLong() else fileSize - 1
                val length = end - start + 1

                response.header(HttpHeaders.ContentRange, "bytes $start-$end/$fileSize")
                response.header(HttpHeaders.AcceptRanges, "bytes")

                respondOutputStream(
                    contentType = ContentType.parse(mimeType),
                    status = HttpStatusCode.PartialContent,
                    contentLength = length,
                ) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { inputStream ->
                            // openInputStream doesn't guarantee seekability — drain, don't skip()
                            var skipped = 0L
                            val skipBuf = ByteArray(8192)
                            while (skipped < start) {
                                val toRead = minOf(skipBuf.size.toLong(), start - skipped).toInt()
                                val read = inputStream.read(skipBuf, 0, toRead)
                                if (read == -1) return@use
                                skipped += read
                            }
                            val buffer = ByteArray(8192)
                            var bytesRemaining = length
                            while (bytesRemaining > 0) {
                                val read =
                                    inputStream.read(buffer, 0, minOf(buffer.size.toLong(), bytesRemaining).toInt())
                                if (read == -1) break
                                write(buffer, 0, read)
                                bytesRemaining -= read
                            }
                        } ?: logcat { "openInputStream returned null for $uri" }
                    } catch (e: Exception) {
                        logcat { "Error streaming range [$start-$end]: ${e.message}" }
                    }
                }
                return
            }
        }

        response.header(HttpHeaders.AcceptRanges, "bytes")
        respondOutputStream(
            contentType = ContentType.parse(mimeType),
            contentLength = fileSize.takeIf { it > 0L },
        ) {
            try {
                context.contentResolver.openInputStream(uri)?.use { it.copyTo(this) }
                    ?: logcat { "openInputStream returned null for $uri" }
            } catch (e: Exception) {
                logcat { "Error streaming full file: ${e.message}" }
            }
        }
    }

    private fun getMimeType(url: String): String {
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.endsWith(".mkv") -> "video/x-matroska"
            lowerUrl.endsWith(".webm") -> "video/webm"
            lowerUrl.endsWith(".m3u8") -> "application/x-mpegURL"
            else -> "video/mp4"
        }
    }

    private fun getHeadersForUrl(url: String): Map<String, String> {
        return headerStore[url].orEmpty()
    }

    private fun getLocalIpAddress(): String {
        NetworkInterface.getNetworkInterfaces().toList().forEach { networkInterface ->
            networkInterface.inetAddresses.toList().forEach { address ->
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    return address.hostAddress ?: "127.0.0.1"
                }
            }
        }
        return "127.0.0.1"
    }
}
