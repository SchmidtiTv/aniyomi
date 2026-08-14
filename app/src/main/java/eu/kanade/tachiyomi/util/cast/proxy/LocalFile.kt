package eu.kanade.tachiyomi.util.cast.proxy

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import tachiyomi.core.common.util.system.logcat
import java.io.InputStream
import java.io.OutputStream

private const val BUFFER_SIZE = 8192
private val BYTE_RANGE_REGEX = Regex("bytes=(\\d+)-(.*)")

internal suspend fun ApplicationCall.handleLocalFile(
    context: Context,
    originalUrl: String,
    mimeType: String?,
) {
    val uri = originalUrl.toUri()
    logcat { "Handling local file URI: $uri" }

    val fileSize = context.resolveFileSize(uri)
    logcat { "Resolved file size: $fileSize bytes" }

    val range = request.headers["Range"].parseByteRange(fileSize)

    if (range != null) {
        respondLocalFileRange(context, uri, mimeType, fileSize, range)
        return
    }

    respondLocalFile(context, uri, mimeType, fileSize)
}

private fun Context.resolveFileSize(uri: Uri): Long {
    return getStatSize(uri).takeIf { it > -1L }
        ?: queryOpenableSize(uri)
}

private fun Context.getStatSize(uri: Uri): Long {
    try {
        contentResolver.openFileDescriptor(uri, "r")?.use { return it.statSize }
    } catch (e: Exception) {
        logcat { "statSize failed, falling back to query: ${e.message}" }
    }
    return -1L
}

private fun Context.queryOpenableSize(uri: Uri): Long {
    try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1) return cursor.getLong(sizeIndex)
            }
        }
    } catch (e: Exception) {
        logcat { "Size query failed: ${e.message}" }
    }
    return -1L
}

private suspend fun ApplicationCall.respondLocalFileRange(
    context: Context,
    uri: Uri,
    mimeType: String?,
    fileSize: Long,
    range: ByteRange,
) {
    response.header(HttpHeaders.ContentRange, range.toContentRangeHeader(fileSize))
    response.header(HttpHeaders.AcceptRanges, "bytes")

    respondOutputStream(
        contentType = mimeType?.let(ContentType::parse),
        status = HttpStatusCode.PartialContent,
        contentLength = range.length,
    ) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.copyRangeTo(this, range)
            } ?: logcat { "openInputStream returned null for $uri" }
        } catch (e: Exception) {
            logcat { "Error streaming range [${range.start}-${range.end}]: ${e.message}" }
        }
    }
}

private suspend fun ApplicationCall.respondLocalFile(
    context: Context,
    uri: Uri,
    mimeType: String?,
    fileSize: Long,
) {
    response.header(HttpHeaders.AcceptRanges, "bytes")
    respondOutputStream(
        contentType = mimeType?.let(ContentType::parse),
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

private fun String?.parseByteRange(fileSize: Long): ByteRange? {
    if (this == null || fileSize <= 0) return null

    val match = BYTE_RANGE_REGEX.find(this) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (fileSize - 1)

    return ByteRange(start, end).takeIf { it.isValidFor(fileSize) }
}

private fun InputStream.copyRangeTo(outputStream: OutputStream, range: ByteRange) {
    // openInputStream doesn't guarantee seekability, so drain instead of using skip().
    drainBytes(range.start)

    val buffer = ByteArray(BUFFER_SIZE)
    var bytesRemaining = range.length
    while (bytesRemaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), bytesRemaining).toInt())
        if (read == -1) break
        outputStream.write(buffer, 0, read)
        bytesRemaining -= read
    }
}

private fun InputStream.drainBytes(bytesToDrain: Long) {
    val buffer = ByteArray(BUFFER_SIZE)
    var bytesDrained = 0L
    while (bytesDrained < bytesToDrain) {
        val toRead = minOf(buffer.size.toLong(), bytesToDrain - bytesDrained).toInt()
        val read = read(buffer, 0, toRead)
        if (read == -1) return
        bytesDrained += read
    }
}

private data class ByteRange(
    val start: Long,
    val end: Long,
) {
    val length: Long = end - start + 1

    fun isValidFor(fileSize: Long): Boolean {
        return start in 0..end && end < fileSize
    }

    fun toContentRangeHeader(fileSize: Long): String {
        return "bytes $start-$end/$fileSize"
    }
}
