package eu.kanade.tachiyomi.util.cast

import android.content.Context
import android.widget.Toast
import androidx.core.net.toUri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.common.images.WebImage
import eu.kanade.tachiyomi.util.cast.proxy.Server
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.system.logcat

internal class CastMediaLoader(
    private val context: Context,
    private val currentSession: () -> CastSession?,
    private val playbackSynchronizer: CastPlaybackSynchronizer,
    private val playingState: MutableStateFlow<Boolean>,
) {
    private var mediaLoadJob: Job? = null
    private var activePositionAnchor: PlaybackPositionAnchor? = null
    private var loadGeneration = 0

    fun cancel() {
        loadGeneration++
        mediaLoadJob?.cancel()
        mediaLoadJob = null
        activePositionAnchor = null
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun load(video: CurrentVideo, positionAnchor: PlaybackPositionAnchor) {
        activePositionAnchor = positionAnchor
        val castSession = currentSession() ?: return
        mediaLoadJob?.cancel()
        val generation = ++loadGeneration
        playbackSynchronizer.onMediaLoadStarted()
        Server.cleanupHls()
        mediaLoadJob = launchUI {
            try {
                val preparationPositionAnchor = activePositionAnchor ?: positionAnchor
                val preparedMedia = CastMediaPreparer.prepare(
                    context = context,
                    originalUrl = video.videoUrl,
                    headers = video.headers,
                    requiredPositionMs = preparationPositionAnchor.positionAt(System.currentTimeMillis()),
                )
                if (currentSession() != castSession || generation != loadGeneration) return@launchUI
                loadPreparedMedia(
                    session = castSession,
                    media = preparedMedia,
                    video = video,
                    positionAnchor = activePositionAnchor ?: positionAnchor,
                    generation = generation,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logcat(LogPriority.ERROR, error) { "Could not prepare video for Cast" }
                context.toast(error.message ?: "Could not prepare video for Cast", Toast.LENGTH_LONG)
            }
        }
    }

    fun updatePositionAnchor(positionAnchor: PlaybackPositionAnchor) {
        activePositionAnchor = positionAnchor
    }

    private fun loadPreparedMedia(
        session: CastSession,
        media: PreparedCastMedia,
        video: CurrentVideo,
        positionAnchor: PlaybackPositionAnchor,
        generation: Int,
    ) {
        logcat { "Loading prepared Cast media: ${media.url} (${media.contentType})" }

        val coverUrl = video.animeCover?.url?.let { Server.hostImage(context, it) }
        val movieMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, video.videoTitle)
            putString(MediaMetadata.KEY_SUBTITLE, video.videoSubTitle)
            coverUrl?.let {
                addImage(WebImage(it.toUri()))
                addImage(WebImage(it.toUri()))
            }
        }
        val mediaInfo = MediaInfo.Builder(media.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(media.contentType)
            .setMetadata(movieMetadata)
            .build()
        submitLoad(
            session = session,
            mediaInfo = mediaInfo,
            contentId = media.url,
            positionAnchor = positionAnchor,
            generation = generation,
            attempt = 1,
        )
    }

    private fun submitLoad(
        session: CastSession,
        mediaInfo: MediaInfo,
        contentId: String,
        positionAnchor: PlaybackPositionAnchor,
        generation: Int,
        attempt: Int,
    ) {
        if (currentSession() != session || generation != loadGeneration) return

        val remoteMediaClient = session.remoteMediaClient
        if (remoteMediaClient == null) {
            logcat(LogPriority.WARN) { "Cast media client unavailable on load attempt $attempt" }
            retryLoad(session, mediaInfo, contentId, positionAnchor, generation, attempt)
            return
        }

        val requestData = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(positionAnchor.isPlaying)
            .setCurrentTime(positionAnchor.positionAt(System.currentTimeMillis()))
            .setPlaybackRate(positionAnchor.playbackSpeed)
            .build()

        playbackSynchronizer.onMediaLoaded(contentId, positionAnchor)
        playingState.update { positionAnchor.isPlaying }
        remoteMediaClient.load(requestData).setResultCallback { result ->
            if (result.status.isSuccess) return@setResultCallback

            val error = result.mediaError
            logcat(LogPriority.ERROR) {
                "Cast load attempt $attempt failed: status=${result.status.statusCode} " +
                    "detailedErrorCode=${error?.detailedErrorCode} reason=${error?.reason} " +
                    "customData=${error?.customData ?: result.customData}"
            }
            retryLoad(session, mediaInfo, contentId, positionAnchor, generation, attempt)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun retryLoad(
        session: CastSession,
        mediaInfo: MediaInfo,
        contentId: String,
        positionAnchor: PlaybackPositionAnchor,
        generation: Int,
        failedAttempt: Int,
    ) {
        if (
            failedAttempt >= MAX_LOAD_ATTEMPTS ||
            currentSession() != session ||
            generation != loadGeneration
        ) {
            return
        }
        mediaLoadJob = launchUI {
            delay(LOAD_RETRY_DELAY_MS)
            submitLoad(
                session = session,
                mediaInfo = mediaInfo,
                contentId = contentId,
                positionAnchor = activePositionAnchor ?: positionAnchor,
                generation = generation,
                attempt = failedAttempt + 1,
            )
        }
    }

    private companion object {
        const val MAX_LOAD_ATTEMPTS = 3
        const val LOAD_RETRY_DELAY_MS = 750L
    }
}
