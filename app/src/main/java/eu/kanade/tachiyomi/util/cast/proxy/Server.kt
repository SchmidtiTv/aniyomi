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
            b0 == 10 -> true  // 10.0.0.0/8
            b0 == 172 && b1 in 16..31 -> true  // 172.16.0.0/12
            b0 == 192 && b1 == 168 -> true  // 192.168.0.0/16
            else -> false // excludes 100.x (CGNAT/Tailscale), etc.
        }
    }
}
