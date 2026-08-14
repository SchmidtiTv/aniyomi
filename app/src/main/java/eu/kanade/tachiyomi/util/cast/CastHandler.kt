package eu.kanade.tachiyomi.util.cast

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.core.net.toUri
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.database.models.anime.Episode
import eu.kanade.tachiyomi.ui.player.sync.ListenerType
import eu.kanade.tachiyomi.ui.player.sync.LoadedVideoEvent
import eu.kanade.tachiyomi.ui.player.sync.PlaybackCommand
import eu.kanade.tachiyomi.ui.player.sync.PlaybackCommandType
import eu.kanade.tachiyomi.ui.player.sync.PlaybackListener
import eu.kanade.tachiyomi.ui.player.sync.PlaybackSyncCoordinator
import eu.kanade.tachiyomi.ui.player.sync.PlaybackSyncState
import eu.kanade.tachiyomi.ui.player.sync.SyncOrigin
import eu.kanade.tachiyomi.ui.player.sync.VideoType
import eu.kanade.tachiyomi.util.cast.proxy.Server
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.domain.entries.anime.model.asAnimeCover
import java.util.UUID
import kotlin.math.abs

data class CurrentVideo(
    val videoId: String,
    val videoTitle: String,
    val videoUrl: String,
    val videoSubTitle: String,
    val animeCover: AnimeCover? = null,
    val headers: Map<String, String> = emptyMap(),
)

internal data class PlaybackPositionAnchor(
    val positionMs: Long,
    val capturedAtMs: Long,
    val isPlaying: Boolean,
    val playbackSpeed: Double,
) {
    fun positionAt(nowMs: Long): Long {
        val elapsedMs = (nowMs - capturedAtMs).coerceAtLeast(0L)
        val progressedMs = if (isPlaying) (elapsedMs * playbackSpeed).toLong() else 0L
        return (positionMs + progressedMs).coerceAtLeast(0L)
    }
}

private data class PendingPositionSync(
    val contentId: String,
    val anchor: PlaybackPositionAnchor,
)

private data class RemotePlaybackSnapshot(
    val contentId: String,
    val positionMs: Long,
    val capturedAtMs: Long,
    val isPlaying: Boolean,
    val playbackSpeed: Double,
) {
    fun expectedPositionAt(nowMs: Long): Long {
        val elapsedMs = (nowMs - capturedAtMs).coerceAtLeast(0L)
        return if (isPlaying) {
            positionMs + (elapsedMs * playbackSpeed).toLong()
        } else {
            positionMs
        }
    }
}

class CastHandler private constructor(context: Context) {
    private val applicationContext: Context = context.applicationContext
    private val castContext: CastContext = CastContext.getSharedInstance(applicationContext)

    private val playbackSyncListenerId = "CastHandler-${UUID.randomUUID()}"
    private val playbackCoordinator = PlaybackSyncCoordinator.getInstance()

    private var currentSession: CastSession? = null
    private var activeMedia: CurrentVideo? = null
    private var mediaLoadJob: Job? = null
    private var activeCastContentId: String? = null
    private var activePositionAnchor: PlaybackPositionAnchor? = null
    private var pendingPositionSync: PendingPositionSync? = null
    private var lastRemotePlaybackSnapshot: RemotePlaybackSnapshot? = null
    private var lastRemotePlayerState: Int? = null

    private var playingState = MutableStateFlow(false)
    private val _connectionState = MutableStateFlow(false)
    val connectionState = _connectionState.asStateFlow()

    val mediaRouter: MediaRouter = MediaRouter.getInstance(applicationContext)

    val castSelector: MediaRouteSelector =
        MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForRemotePlayback(
                    CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
                ),
            )
            .build()

    // --- Callbacks

    private fun onNewSession(session: CastSession) {
        currentSession = session
        _connectionState.update { true }
        activeMedia = null
        activeCastContentId = null
        activePositionAnchor = null
        pendingPositionSync = null
        lastRemotePlaybackSnapshot = null
        lastRemotePlayerState = null
        Server.start(applicationContext)
        session.remoteMediaClient?.registerCallback(remoteMediaListener)

        playbackCoordinator.addEvent(
            PlaybackCommand(
                commandId = UUID.randomUUID().toString(),
                commandType = PlaybackCommandType.REQUEST_FULL_STATE,
                eventTime = System.currentTimeMillis(),
                origin = SyncOrigin.CAST,
                newValue = null,
            ),
        )
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onNewSession(session)

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onNewSession(session)

        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionStarting(session: CastSession) = Unit

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            currentSession = null
            _connectionState.update { false }
            activeMedia = null
        }

        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            currentSession = null
            _connectionState.update { false }
            activeMedia = null
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            currentSession = null
            _connectionState.update { false }
            activeMedia = null
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            mediaLoadJob?.cancel()
            mediaLoadJob = null
            currentSession = null
            _connectionState.update { false }
            activeMedia = null
            activeCastContentId = null
            activePositionAnchor = null
            pendingPositionSync = null
            lastRemotePlaybackSnapshot = null
            lastRemotePlayerState = null
            Server.stop()
        }
    }

    private val remoteMediaListener = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val status = currentSession?.remoteMediaClient?.mediaStatus ?: return
            val positionWasLocallyRequested = correctPendingPosition(status)

            emitRemotePlaybackState(status)
            updateRemotePosition(status, positionWasLocallyRequested)
        }

        override fun onMediaError(error: MediaError) {
            logcat(LogPriority.ERROR) {
                "Cast media error: detailedErrorCode=${error.detailedErrorCode} " +
                    "reason=${error.reason} customData=${error.customData}"
            }
        }
    }

    private fun correctPendingPosition(status: MediaStatus): Boolean {
        val pending = pendingPositionSync ?: return false
        if (status.mediaInfo?.contentId != pending.contentId) return false
        if (
            status.playerState != MediaStatus.PLAYER_STATE_PLAYING &&
            status.playerState != MediaStatus.PLAYER_STATE_PAUSED
        ) {
            return true
        }

        val expectedPositionMs = pending.anchor.positionAt(System.currentTimeMillis())
        val driftMs = expectedPositionMs - status.streamPosition
        if (abs(driftMs) < POSITION_DRIFT_THRESHOLD_MS) {
            pendingPositionSync = null
            return true
        }

        logcat {
            "Correcting Cast startup drift by ${driftMs}ms: " +
                "receiver=${status.streamPosition} expected=$expectedPositionMs"
        }
        currentSession?.remoteMediaClient?.seek(
            MediaSeekOptions.Builder()
                .setPosition(expectedPositionMs)
                .build(),
        )
        return true
    }

    private fun emitRemotePlaybackState(status: MediaStatus) {
        val commandType = when (status.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> PlaybackCommandType.PLAY
            MediaStatus.PLAYER_STATE_PAUSED -> PlaybackCommandType.PAUSE
            MediaStatus.PLAYER_STATE_IDLE -> PlaybackCommandType.STOP.takeIf {
                lastRemotePlayerState == MediaStatus.PLAYER_STATE_PLAYING ||
                    lastRemotePlayerState == MediaStatus.PLAYER_STATE_PAUSED
            }
            else -> null
        }

        when (status.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> playingState.update { true }
            MediaStatus.PLAYER_STATE_PAUSED,
            MediaStatus.PLAYER_STATE_IDLE,
            -> playingState.update { false }
        }
        if (commandType == null || lastRemotePlayerState == status.playerState) return

        lastRemotePlayerState = status.playerState
        playbackCoordinator.addEvent(
            PlaybackCommand(
                commandId = UUID.randomUUID().toString(),
                commandType = commandType,
                eventTime = System.currentTimeMillis(),
                origin = SyncOrigin.CAST,
                newValue = null,
            ),
        )
    }

    private fun updateRemotePosition(status: MediaStatus, positionWasLocallyRequested: Boolean) {
        val contentId = status.mediaInfo?.contentId ?: return
        val nowMs = SystemClock.elapsedRealtime()
        val currentSnapshot = RemotePlaybackSnapshot(
            contentId = contentId,
            positionMs = status.streamPosition,
            capturedAtMs = nowMs,
            isPlaying = status.playerState == MediaStatus.PLAYER_STATE_PLAYING,
            playbackSpeed = status.playbackRate,
        )
        val previousSnapshot = lastRemotePlaybackSnapshot
        lastRemotePlaybackSnapshot = currentSnapshot

        if (
            previousSnapshot != null &&
            previousSnapshot.contentId == contentId &&
            abs(status.playbackRate - previousSnapshot.playbackSpeed) >= PLAYBACK_RATE_CHANGE_THRESHOLD
        ) {
            playbackCoordinator.addEvent(
                PlaybackCommand(
                    commandId = UUID.randomUUID().toString(),
                    commandType = PlaybackCommandType.SET_SPEED,
                    eventTime = System.currentTimeMillis(),
                    origin = SyncOrigin.CAST,
                    newValue = status.playbackRate,
                ),
            )
        }

        if (
            positionWasLocallyRequested ||
            previousSnapshot == null ||
            previousSnapshot.contentId != contentId
        ) {
            return
        }

        val expectedPositionMs = previousSnapshot.expectedPositionAt(nowMs)
        if (abs(status.streamPosition - expectedPositionMs) < REMOTE_SEEK_THRESHOLD_MS) return

        playbackCoordinator.addEvent(
            PlaybackCommand(
                commandId = UUID.randomUUID().toString(),
                commandType = PlaybackCommandType.SEEK_TO,
                eventTime = System.currentTimeMillis(),
                origin = SyncOrigin.CAST,
                newValue = status.streamPosition,
            ),
        )
    }

    init {
        castContext.sessionManager.addSessionManagerListener(
            sessionListener,
            CastSession::class.java,
        )

        playbackCoordinator.addListener(
            PlaybackListener(
                playbackSyncListenerId,
                ListenerType.CAST_SESSION,
                ::handleCommand,
            ),
        )
    }

    private fun buildCurrentVideo(media: LoadedVideoEvent<*>): CurrentVideo? {
        return when (media.videoType) {
            VideoType.VIDEO -> {
                val video = media.video as? Video ?: return null

                if (video.videoUrl.isEmpty()) return null

                CurrentVideo(
                    videoId = video.videoUrl,
                    videoTitle = media.title,
                    videoUrl = video.videoUrl,
                    videoSubTitle = media.subtitle,
                    animeCover = media.anime?.asAnimeCover(),
                    headers = media.headers,
                )
            }

            VideoType.EPISODE -> {
                val video = media.video as? Episode ?: return null
                CurrentVideo(
                    videoId = video.id.toString(),
                    videoUrl = video.url,
                    videoTitle = media.title,
                    videoSubTitle = media.subtitle,
                    animeCover = media.anime?.asAnimeCover(),
                )
            }
        }
    }

    private fun handleOpenVideoCommand(
        media: LoadedVideoEvent<*>,
        newCurrentVideo: CurrentVideo,
        positionAnchor: PlaybackPositionAnchor,
    ) {
        activePositionAnchor = positionAnchor
        logcat {
            "CastHandler: Processing video event for ${media.videoType} | Current Video $activeMedia | Build media: $newCurrentVideo "
        }

        if (activeMedia?.videoId == newCurrentVideo.videoId && activeMedia?.videoUrl == newCurrentVideo.videoUrl) {
            seekToAnchor(positionAnchor)
            return
        }

        activeMedia = newCurrentVideo

        loadVideoAtPosition(
            originalUrl = newCurrentVideo.videoUrl,
            headers = newCurrentVideo.headers,
            title = newCurrentVideo.videoTitle,
            subtitle = newCurrentVideo.videoSubTitle,
            animeCover = newCurrentVideo.animeCover,
            positionAnchor = positionAnchor,
        )
    }

    private fun seekToAnchor(anchor: PlaybackPositionAnchor) {
        val remoteMediaClient = currentSession?.remoteMediaClient ?: return
        val contentId = activeCastContentId ?: return
        pendingPositionSync = PendingPositionSync(contentId, anchor)
        remoteMediaClient.seek(
            MediaSeekOptions.Builder()
                .setPosition(anchor.positionAt(System.currentTimeMillis()))
                .build(),
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun handleCommand(command: PlaybackCommand<*>) {
        launchUI {
            if (!isConnected()) return@launchUI
            if (command.origin == SyncOrigin.CAST) return@launchUI

            val remoteMediaClient = currentSession?.remoteMediaClient ?: return@launchUI

            when (command.commandType) {
                PlaybackCommandType.LOAD_MEDIA -> {
                    val media = command.newValue as? LoadedVideoEvent<*> ?: return@launchUI
                    val newCurrentVideo = buildCurrentVideo(media) ?: return@launchUI
                    handleOpenVideoCommand(
                        media = media,
                        newCurrentVideo = newCurrentVideo,
                        positionAnchor = PlaybackPositionAnchor(
                            positionMs = 0L,
                            capturedAtMs = command.eventTime,
                            isPlaying = true,
                            playbackSpeed = 1.0,
                        ),
                    )
                }

                PlaybackCommandType.SYNC_STATE -> {
                    val states = command.newValue as? PlaybackSyncState ?: return@launchUI
                    val newCurrentVideo = buildCurrentVideo(states.media) ?: return@launchUI
                    handleOpenVideoCommand(
                        media = states.media,
                        newCurrentVideo = newCurrentVideo,
                        positionAnchor = PlaybackPositionAnchor(
                            positionMs = states.positionMs,
                            capturedAtMs = command.eventTime,
                            isPlaying = states.playWhenReady,
                            playbackSpeed = states.playbackSpeed.toDouble(),
                        ),
                    )

                    playingState.update { states.playWhenReady }
                    if (!states.playWhenReady) {
                        remoteMediaClient.pause()
                    }
                }

                PlaybackCommandType.PAUSE -> {
                    playingState.update { false }
                    pendingPositionSync = null
                    remoteMediaClient.pause()
                }

                PlaybackCommandType.PLAY -> {
                    playingState.update { true }
                    remoteMediaClient.play()
                }

                PlaybackCommandType.STOP -> {
                    playingState.update { false }
                    pendingPositionSync = null
                    remoteMediaClient.stop()
                }

                PlaybackCommandType.SET_SPEED -> {
                    val speed = command.newValue as? Double ?: return@launchUI
                    remoteMediaClient.setPlaybackRate(speed)
                }

                PlaybackCommandType.SEEK_TO -> {
                    val positionMs = (command.newValue as? Number)?.toLong() ?: return@launchUI
                    seekToAnchor(
                        PlaybackPositionAnchor(
                            positionMs = positionMs,
                            capturedAtMs = command.eventTime,
                            isPlaying = playingState.value,
                            playbackSpeed = remoteMediaClient.mediaStatus?.playbackRate ?: 1.0,
                        ),
                    )
                }

                else -> {
                    logcat.logcat(
                        "CastHandler/handleCommand",
                        LogPriority.WARN,
                    ) { "Received unhandled playback command: ${command.commandType} with value ${command.newValue}" }
                }
            }
        }
    }

    fun loadVideo(
        originalUrl: String,
        headers: Map<String, String> = emptyMap(),
        title: String = "Aniyomi Video",
        subtitle: String = "",
        animeCover: AnimeCover?,
    ) {
        loadVideoAtPosition(
            originalUrl = originalUrl,
            headers = headers,
            title = title,
            subtitle = subtitle,
            animeCover = animeCover,
            positionAnchor = PlaybackPositionAnchor(
                positionMs = 0L,
                capturedAtMs = System.currentTimeMillis(),
                isPlaying = true,
                playbackSpeed = 1.0,
            ),
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun loadVideoAtPosition(
        originalUrl: String,
        headers: Map<String, String> = emptyMap(),
        title: String = "Aniyomi Video",
        subtitle: String = "",
        animeCover: AnimeCover?,
        positionAnchor: PlaybackPositionAnchor,
    ) {
        activePositionAnchor = positionAnchor
        val castSession = currentSession ?: return
        mediaLoadJob?.cancel()
        Server.cleanupHls()
        mediaLoadJob = launchUI {
            try {
                val preparedMedia = CastMediaPreparer.prepare(
                    context = applicationContext,
                    originalUrl = originalUrl,
                    headers = headers,
                    requiredPositionMs = positionAnchor.positionAt(System.currentTimeMillis()),
                )
                if (currentSession != castSession) return@launchUI
                loadPreparedMedia(
                    session = castSession,
                    media = preparedMedia,
                    title = title,
                    subtitle = subtitle,
                    animeCover = animeCover,
                    positionAnchor = activePositionAnchor ?: positionAnchor,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logcat(LogPriority.ERROR, error) { "Could not prepare video for Cast" }
                applicationContext.toast(error.message ?: "Could not prepare video for Cast", Toast.LENGTH_LONG)
            }
        }
    }

    private fun loadPreparedMedia(
        session: CastSession,
        media: PreparedCastMedia,
        title: String,
        subtitle: String,
        animeCover: AnimeCover?,
        positionAnchor: PlaybackPositionAnchor,
    ) {
        val remoteMediaClient = session.remoteMediaClient ?: return
        logcat { "Loading prepared Cast media: ${media.url} (${media.contentType})" }

        val coverUrl = animeCover?.url?.let { Server.hostImage(applicationContext, it) }
        val movieMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            putString(MediaMetadata.KEY_SUBTITLE, subtitle)
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

        val requestData = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(positionAnchor.isPlaying)
            .setCurrentTime(positionAnchor.positionAt(System.currentTimeMillis()))
            .setPlaybackRate(positionAnchor.playbackSpeed)
            .build()

        activeCastContentId = media.url
        pendingPositionSync = PendingPositionSync(media.url, positionAnchor)
        playingState.update { positionAnchor.isPlaying }
        remoteMediaClient.load(requestData).setResultCallback { result ->
            if (!result.status.isSuccess) {
                val error = result.mediaError
                logcat(LogPriority.ERROR) {
                    "Cast load failed: status=${result.status.statusCode} " +
                        "detailedErrorCode=${error?.detailedErrorCode} reason=${error?.reason} " +
                        "customData=${error?.customData ?: result.customData}"
                }
            }
        }
    }

    fun registerCallback(callback: MediaRouter.Callback) {
        mediaRouter.addCallback(
            castSelector,
            callback,
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY,
        )
    }

    fun unregisterCallback(callback: MediaRouter.Callback) {
        mediaRouter.removeCallback(callback)
    }

    fun addSessionManagerListener(listener: SessionManagerListener<Session>) {
        castContext.sessionManager.addSessionManagerListener(listener)
    }

    fun removeSessionManagerListener(listener: SessionManagerListener<Session>) {
        castContext.sessionManager.removeSessionManagerListener(listener)
    }

    // --- Routes
    fun getCastRoutes(): List<MediaRouter.RouteInfo> {
        return mediaRouter.routes.filter { route ->
            route.matchesSelector(castSelector) &&
                route != mediaRouter.defaultRoute &&
                route != mediaRouter.bluetoothRoute &&
                route.isEnabled
        }
    }

    fun isCurrentRoute(route: MediaRouter.RouteInfo): Boolean {
        return mediaRouter.selectedRoute == route || mediaRouter.selectedRoute.id == route.id
    }

    fun getCurrentRoute(): MediaRouter.RouteInfo {
        return mediaRouter.selectedRoute
    }

    // --- Connection
    fun connect(route: MediaRouter.RouteInfo) {
        mediaRouter.selectRoute(route)
    }

    fun disconnect() {
        castContext.sessionManager.endCurrentSession(true)
    }

    fun isConnected(): Boolean {
        return currentSession?.isConnected == true
    }

    companion object {
        private const val POSITION_DRIFT_THRESHOLD_MS = 500L
        private const val REMOTE_SEEK_THRESHOLD_MS = 1_500L
        private const val PLAYBACK_RATE_CHANGE_THRESHOLD = 0.01

        @Volatile
        private var instance: CastHandler? = null

        fun getInstance(context: Context): CastHandler {
            return instance ?: synchronized(this) {
                instance ?: CastHandler(context).also { instance = it }
            }
        }
    }
}
