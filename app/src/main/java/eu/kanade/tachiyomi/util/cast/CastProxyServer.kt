package eu.kanade.tachiyomi.util.cast

import android.content.Context
import android.net.Uri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.copyTo
import java.io.FileInputStream
import java.net.URLDecoder
import androidx.core.net.toUri

object CastProxyServer {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val httpClient = HttpClient(OkHttp) {
        followRedirects = true
    }

    fun start(context: Context, port: Int = 8080) {
        if (server != null) return

        server = embeddedServer(Netty, port = port) {
            routing {
                get("/proxy") {
                    val encodedUrl = call.parameters["url"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val originalUrl = URLDecoder.decode(encodedUrl, "UTF-8")

                    val videoHeaders: Map<String, String> = getHeadersForUrl(originalUrl)

                    if (originalUrl.startsWith("content://")) {
                        call.handleLocalFile(context, originalUrl.toUri())
                    } else {
                        call.handleRemoteStream(originalUrl, videoHeaders)
                    }
                }
            }
        }.start(wait = false)
    }

    private suspend fun ApplicationCall.handleRemoteStream(
        url: String,
        headers: Map<String, String>,
    ) {
        // 1. Get the Range header sent by the TV
        val rangeHeader = request.headers["Range"]

        val response = httpClient.get(url) {
            // 2. Inject the "Secret" headers (User-Agent, Referer)
            headers.forEach { (key, value) -> header(key, value) }
            // 3. Forward the Range to the real server
            rangeHeader?.let { header("Range", it) }
        }

        // 4. Pipe the real server's response back to the TV
        respondBytesWriter(
            contentType = response.contentType(),
            status = response.status,
        ) {
            response.bodyAsChannel().copyTo(this)
        }
    }

    private suspend fun ApplicationCall.handleLocalFile(
        context: Context,
        uri: Uri,
    ) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return
        val inputStream = FileInputStream(pfd.fileDescriptor)

        // Ktor's respondOutputStream handles Range requests for files automatically
        // if you provide the content length.
        respondOutputStream(
            contentType = ContentType.Video.Any,
            contentLength = pfd.statSize,
        ) {
            inputStream.use { it.copyTo(this) }
        }
    }

    private fun getHeadersForUrl(url: String): Map<String, String> {
        // TODO: Implement header lookup logic
        return emptyMap()
    }
}
