package eu.kanade.tachiyomi.util.cast.proxy

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import tachiyomi.core.common.util.system.logcat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object Server {
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
                        call.handleRemoteStream(httpClient, originalUrl, videoHeaders)
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
