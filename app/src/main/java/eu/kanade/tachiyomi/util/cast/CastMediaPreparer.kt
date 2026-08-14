package eu.kanade.tachiyomi.util.cast

import android.content.Context
import androidx.core.net.toUri
import com.arthenica.ffmpegkit.FFprobeKit
import eu.kanade.tachiyomi.util.cast.proxy.Server
import eu.kanade.tachiyomi.util.storage.toFFmpegString
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class PreparedCastMedia(
    val url: String,
    val contentType: String?,
)

internal object CastMediaPreparer {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(
        context: Context,
        originalUrl: String,
        headers: Map<String, String>,
        requiredPositionMs: Long = 0L,
    ): PreparedCastMedia {
        // Remote HLS is already segmented and only needs its URLs proxied.
        if (originalUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
            return directMedia(context, originalUrl, headers)
        }

        val probe = probe(ffmpegInput(context, originalUrl), headers)

        if (probe.isDirectPlaySafe) {
            return directMedia(context, originalUrl, headers)
        }
        if (!probe.isVideoSafe) {
            throw CastPreparationException(
                "This video uses ${probe.videoDescription}, which this Cast compatibility mode cannot play. " +
                    "Video transcoding is not enabled.",
            )
        }

        val hlsUrl = Server.prepareHls(
            context = context,
            // FFmpegKit SAF parameters are single-use. FFprobe closes and unregisters its
            // descriptor, so the remux needs a newly registered parameter for the same URI.
            input = ffmpegInput(context, originalUrl),
            headers = headers,
            transcodeAudio = !probe.isAudioSafe,
            requiredPositionMs = requiredPositionMs,
        )
        return PreparedCastMedia(hlsUrl, HLS_CONTENT_TYPE)
    }

    private fun ffmpegInput(context: Context, originalUrl: String): String {
        return if (originalUrl.startsWith("content://")) {
            originalUrl.toUri().toFFmpegString(context, mode = "r")
        } else {
            originalUrl
        }
    }

    private fun directMedia(
        context: Context,
        originalUrl: String,
        headers: Map<String, String>,
    ): PreparedCastMedia {
        val mimeType = directMediaContentType(originalUrl, context.resolveMimeType(originalUrl))
        return PreparedCastMedia(
            url = Server.proxiedUrl(context, originalUrl, headers, mimeType),
            contentType = mimeType,
        )
    }

    internal fun directMediaContentType(originalUrl: String, resolvedMimeType: String?): String? {
        return if (originalUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
            HLS_CONTENT_TYPE
        } else {
            resolvedMimeType
        }
    }

    private suspend fun probe(input: String, headers: Map<String, String>): ProbeResult {
        val arguments = buildList {
            addAll(listOf("-v", "quiet"))
            addHttpHeaders(input, headers)
            addAll(
                listOf(
                    "-show_entries",
                    "stream=codec_type,codec_name,profile,level:format=format_name",
                    "-of",
                    "json",
                    input,
                ),
            )
        }.toTypedArray()

        val output = suspendCancellableCoroutine { continuation ->
            val session = FFprobeKit.executeWithArgumentsAsync(arguments) {
                if (continuation.isActive) {
                    if (it.returnCode.isValueSuccess) {
                        continuation.resume(it.output)
                    } else {
                        continuation.resumeWithException(
                            CastPreparationException("Could not inspect this video for Cast: ${it.output}"),
                        )
                    }
                }
            }
            continuation.invokeOnCancellation { session.cancel() }
        }
        return parseProbe(output)
    }

    internal fun parseProbe(output: String): ProbeResult {
        val root = json.parseToJsonElement(output).jsonObject
        val streams = root["streams"]?.jsonArray.orEmpty()
        val video = streams
            .map { it.jsonObject }
            .firstOrNull { it["codec_type"]?.jsonPrimitive?.contentOrNull == "video" }
            ?: throw CastPreparationException("No video stream was found.")
        val audio = streams
            .map { it.jsonObject }
            .firstOrNull { it["codec_type"]?.jsonPrimitive?.contentOrNull == "audio" }

        return ProbeResult(
            formatNames = root["format"]
                ?.jsonObject
                ?.get("format_name")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.split(',')
                ?.map { it.lowercase() }
                ?.toSet()
                .orEmpty(),
            videoCodec = video["codec_name"]?.jsonPrimitive?.contentOrNull,
            videoProfile = video["profile"]?.jsonPrimitive?.contentOrNull,
            videoLevel = video["level"]?.jsonPrimitive?.intOrNull,
            audioCodec = audio?.get("codec_name")?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun MutableList<String>.addHttpHeaders(input: String, headers: Map<String, String>) {
        if ((input.startsWith("http://") || input.startsWith("https://")) && headers.isNotEmpty()) {
            add("-headers")
            add(headers.entries.joinToString(separator = "\r\n", postfix = "\r\n") { "${it.key}: ${it.value}" })
        }
    }

    private const val HLS_CONTENT_TYPE = "application/vnd.apple.mpegurl"
}

internal data class ProbeResult(
    val formatNames: Set<String>,
    val videoCodec: String?,
    val videoProfile: String?,
    val videoLevel: Int?,
    val audioCodec: String?,
) {
    val isVideoSafe: Boolean
        get() = videoCodec == "h264" &&
            videoProfile?.lowercase() in SAFE_H264_PROFILES &&
            videoLevel != null &&
            videoLevel <= MAX_H264_LEVEL

    val isAudioSafe: Boolean
        get() = audioCodec == null || audioCodec == "aac"

    val isDirectPlaySafe: Boolean
        get() = "mp4" in formatNames && isVideoSafe && isAudioSafe

    val videoDescription: String
        get() = listOfNotNull(videoCodec, videoProfile, videoLevel?.let { "level $it" }).joinToString(" ")
            .ifEmpty { "an unsupported codec" }

    private companion object {
        const val MAX_H264_LEVEL = 41
        val SAFE_H264_PROFILES = setOf("baseline", "constrained baseline", "main", "high")
    }
}

internal class CastPreparationException(message: String) : Exception(message)
