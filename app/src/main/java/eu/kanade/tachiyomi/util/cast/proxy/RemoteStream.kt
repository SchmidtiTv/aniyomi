package eu.kanade.tachiyomi.util.cast.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.copyTo

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

    respondBytesWriter(
        contentType = response.contentType(),
        status = response.status,
    ) {
        response.bodyAsChannel().copyTo(this)
    }
}
