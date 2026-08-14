package eu.kanade.tachiyomi.util.cast.proxy

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object HlsSessionManager {
    private const val READY_SEGMENT_COUNT = 3
    private const val PREPARATION_TIMEOUT_MS = 30_000L
    private const val PLAYLIST_NAME = "master.m3u8"
    private const val HLS_DIRECTORY = "cast_hls"

    private data class HlsSession(
        val directory: File,
        val ffmpegSession: FFmpegSession,
    )

    private val sessions = ConcurrentHashMap<String, HlsSession>()
    private var rootDirectory: File? = null

    suspend fun prepare(
        context: Context,
        input: String,
        headers: Map<String, String>,
        transcodeAudio: Boolean,
        requiredPositionMs: Long,
    ): String {
        val root = File(context.cacheDir, HLS_DIRECTORY).also {
            it.mkdirs()
            rootDirectory = it
        }
        val id = UUID.randomUUID().toString()
        val directory = File(root, id).also { check(it.mkdirs()) }
        val playlist = File(directory, PLAYLIST_NAME)
        val completed = CompletableDeferred<Result<Unit>>()

        val arguments = buildList {
            add("-v")
            add("warning")
            if ((input.startsWith("http://") || input.startsWith("https://")) && headers.isNotEmpty()) {
                add("-headers")
                add(headers.entries.joinToString(separator = "\r\n", postfix = "\r\n") { "${it.key}: ${it.value}" })
            }
            addAll(listOf("-i", input, "-map", "0:v:0", "-map", "0:a:0?", "-sn", "-c:v", "copy"))
            if (transcodeAudio) {
                addAll(listOf("-c:a", "aac", "-ac", "2", "-b:a", "192k"))
            } else {
                addAll(listOf("-c:a", "copy"))
            }
            addAll(
                listOf(
                    "-f",
                    "hls",
                    "-hls_time",
                    "6",
                    "-hls_playlist_type",
                    "event",
                    "-hls_flags",
                    "independent_segments",
                    "-hls_segment_filename",
                    File(directory, "seg_%05d.ts").absolutePath,
                    playlist.absolutePath,
                ),
            )
        }.toTypedArray()

        val ffmpegSession = FFmpegKit.executeWithArgumentsAsync(arguments) { session ->
            if (session.returnCode.isValueSuccess) {
                finalizePlaylist(playlist)
                completed.complete(Result.success(Unit))
            } else {
                completed.complete(
                    Result.failure(IllegalStateException("FFmpeg remux failed: ${session.output}")),
                )
            }
        }
        sessions[id] = HlsSession(directory, ffmpegSession)

        try {
            withTimeout(PREPARATION_TIMEOUT_MS) {
                while (true) {
                    val segmentCount = directory.listFiles { file ->
                        file.name.startsWith("seg_") && file.extension == "ts"
                    }?.size.orZero()
                    val availableDurationMs = playlist.availableDurationMs()
                    val requiredDurationMs = requiredPositionMs.coerceAtLeast(0L) + READY_AHEAD_MS
                    if (
                        playlist.isFile &&
                        segmentCount >= READY_SEGMENT_COUNT &&
                        availableDurationMs >= requiredDurationMs
                    ) {
                        break
                    }
                    if (completed.isCompleted) {
                        completed.await().getOrThrow()
                        if (playlist.isFile && segmentCount > 0 && availableDurationMs >= requiredPositionMs) break
                        error("FFmpeg produced no HLS segments")
                    }
                    delay(100)
                }
            }
        } catch (error: Throwable) {
            cleanup(id)
            throw error
        }

        logcat {
            "Cast HLS session $id is ready at ${directory.absolutePath}; " +
                "available=${playlist.availableDurationMs()}ms required=${requiredPositionMs}ms"
        }
        return id
    }

    fun resolve(sessionId: String, fileName: String): File? {
        if (!SAFE_FILE_NAME.matches(fileName)) return null
        return sessions[sessionId]
            ?.directory
            ?.resolve(fileName)
            ?.takeIf { it.isFile }
    }

    fun cleanupAll() {
        sessions.keys.toList().forEach(::cleanup)
        rootDirectory?.takeIf { it.exists() }?.deleteRecursively()
        rootDirectory = null
    }

    private fun cleanup(id: String) {
        sessions.remove(id)?.let { session ->
            session.ffmpegSession.cancel()
            session.directory.deleteRecursively()
        }
    }

    private fun finalizePlaylist(playlist: File) {
        if (!playlist.isFile) return
        val content = playlist.readText()
            .replace("#EXT-X-PLAYLIST-TYPE:EVENT", "#EXT-X-PLAYLIST-TYPE:VOD")
            .let { if ("#EXT-X-ENDLIST" in it) it else it.trimEnd() + "\n#EXT-X-ENDLIST\n" }
        playlist.writeText(content)
    }

    private fun Int?.orZero() = this ?: 0

    private fun File.availableDurationMs(): Long {
        if (!isFile) return 0L
        return runCatching {
            readLines()
                .asSequence()
                .filter { it.startsWith("#EXTINF:") }
                .mapNotNull { line ->
                    line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                }
                .sum()
                .times(1000.0)
                .toLong()
        }.getOrDefault(0L)
    }

    private val SAFE_FILE_NAME = Regex("[A-Za-z0-9_.-]+")

    private const val READY_AHEAD_MS = 12_000L
}
