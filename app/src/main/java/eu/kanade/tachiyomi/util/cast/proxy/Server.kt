package eu.kanade.tachiyomi.util.cast.proxy

import android.content.Context
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.readBytes
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder

object Server {
    private const val DEFAULT_PORT = 8080

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var activePort: Int = DEFAULT_PORT
    private val httpClient = HttpClient(OkHttp) {
        followRedirects = true
    }

    fun start(context: Context, port: Int = DEFAULT_PORT) {
        if (server != null) return

        activePort = port
        server = embeddedServer(Netty, port = port) {
            intercept(ApplicationCallPipeline.Plugins) {
                // Cast receivers load media from a Web Receiver origin. Without CORS the
                // receiver can fetch an HLS playlist but is not allowed to consume it, so it
                // never proceeds to request the media segments.
                call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
                call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, HEAD, OPTIONS")
                call.response.header(HttpHeaders.AccessControlAllowHeaders, HttpHeaders.Range)
                call.response.header(
                    HttpHeaders.AccessControlExposeHeaders,
                    "Accept-Ranges, Content-Length, Content-Range",
                )
            }
            routing {
                get("/proxy") {
                    val originalUrl = call.parameters["url"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                    val videoHeaders = call.parameters["headers"]
                        ?.let(::decodeHeadersParam)
                        .orEmpty()

                    logcat { "Proxying request for URL: $originalUrl with headers: $videoHeaders" }

                    if (originalUrl.startsWith("content://")) {
                        call.handleLocalFile(context, originalUrl, call.parameters["mimeType"])
                    } else {
                        call.handleRemoteStream(httpClient, originalUrl, videoHeaders)
                    }
                }
                get("/image") {
                    val originalUrl = call.parameters["url"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                    logcat { "Hosting image for $originalUrl" }

                    val bytes = if (originalUrl.startsWith("content://")) {
                        // content:// URI — must use ContentResolver
                        val uri = originalUrl.toUri()
                        context.contentResolver.openInputStream(uri)?.readBytes()
                            ?: return@get call.respond(HttpStatusCode.NotFound)
                    } else if (originalUrl.startsWith("http://") || originalUrl.startsWith("https://")) {
                        HttpClient().use { it.get(originalUrl).readRawBytes() }
                    } else {
                        File(originalUrl).takeIf { it.exists() }?.readBytes()
                            ?: return@get call.respond(HttpStatusCode.NotFound)
                    }

                    call.respondBytes(bytes, ContentType.Image.JPEG)
                }
                get("/hls/{session}/{file}") {
                    val sessionId = call.parameters["session"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val fileName = call.parameters["file"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val file = HlsSessionManager.resolve(sessionId, fileName)
                        ?: return@get call.respond(HttpStatusCode.NotFound)

                    val contentType = when (file.extension.lowercase()) {
                        "m3u8" -> "application/vnd.apple.mpegurl"
                        "ts" -> "video/mp2t"
                        else -> return@get call.respond(HttpStatusCode.UnsupportedMediaType)
                    }
                    call.response.header(HttpHeaders.ContentType, contentType)
                    call.respondFile(file)
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        HlsSessionManager.cleanupAll()
        server?.stop(1000, 2000)
        server = null
    }

    suspend fun prepareHls(
        context: Context,
        input: String,
        headers: Map<String, String>,
        transcodeAudio: Boolean,
        requiredPositionMs: Long = 0L,
    ): String {
        start(context)
        val sessionId = HlsSessionManager.prepare(
            context = context,
            input = input,
            headers = headers,
            transcodeAudio = transcodeAudio,
            requiredPositionMs = requiredPositionMs,
        )
        return "http://${getLocalIpAddress()}:$activePort/hls/$sessionId/master.m3u8"
    }

    fun cleanupHls() {
        HlsSessionManager.cleanupAll()
    }

    fun proxiedUrl(
        context: Context,
        originalUrl: String,
        headers: Map<String, String> = emptyMap(),
        mimeType: String? = null,
    ): String {
        start(context)
        val encodedUrl = URLEncoder.encode(originalUrl, "UTF-8")
        val headersParam = if (headers.isNotEmpty()) "&headers=${headers.encodeAsParam()}" else ""
        val mimeTypeParam = mimeType?.let { "&mimeType=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        return "http://${getLocalIpAddress()}:$activePort/proxy?url=$encodedUrl$headersParam$mimeTypeParam"
    }

    fun hostImage(context: Context, originalUrl: String): String {
        start(context)
        val encodedUrl = URLEncoder.encode(originalUrl, "UTF-8")
        return "http://${getLocalIpAddress()}:$activePort/image?url=$encodedUrl"
    }

    private fun getLocalIpAddress(): String {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { address ->
                address is Inet4Address &&
                    !address.isLoopbackAddress &&
                    isLanAddress(address.address)
            }
            ?.let { return (it as Inet4Address).hostAddress ?: "127.0.0.1" }

        return "127.0.0.1"
    }

    private fun isLanAddress(ip: ByteArray): Boolean {
        val b0 = ip[0].toInt() and 0xFF
        val b1 = ip[1].toInt() and 0xFF
        return when {
            b0 == 10 -> true // 10.0.0.0/8
            b0 == 172 && b1 in 16..31 -> true // 172.16.0.0/12
            b0 == 192 && b1 == 168 -> true // 192.168.0.0/16
            else -> false // excludes 100.x (CGNAT/Tailscale), etc.
        }
    }
}
