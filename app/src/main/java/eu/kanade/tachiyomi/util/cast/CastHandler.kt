package eu.kanade.tachiyomi.util.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import eu.kanade.tachiyomi.ui.player.sync.ListenerType
import eu.kanade.tachiyomi.ui.player.sync.LoadedVideoEvent
import eu.kanade.tachiyomi.ui.player.sync.PlaybackCommand
import eu.kanade.tachiyomi.ui.player.sync.PlaybackCommandType
import eu.kanade.tachiyomi.ui.player.sync.PlaybackListener
import eu.kanade.tachiyomi.ui.player.sync.PlaybackSyncCoordinator
import eu.kanade.tachiyomi.ui.player.sync.PlaybackSyncState
import eu.kanade.tachiyomi.ui.player.sync.PlayingManager
import eu.kanade.tachiyomi.ui.player.sync.SyncOrigin
import eu.kanade.tachiyomi.util.cast.proxy.Server
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.model.AnimeCover
import java.util.UUID

class CastHandler private constructor(context: Context) {
    private val applicationContext: Context = context.applicationContext
    private val castContext: CastContext = CastContext.getSharedInstance(applicationContext)

    private val playbackSyncListenerId = "CastHandler-${UUID.randomUUID()}"
    private val playbackCoordinator = PlaybackSyncCoordinator.getInstance()
    private val playingManager = PlayingManager.getInstance()

    private var currentSession: CastSession? = null
    private var activeMedia: CurrentVideo? = null
    private var connectionValidationJob: Job? = null

    private val playingState = MutableStateFlow(false)
    private val playbackSynchronizer = CastPlaybackSynchronizer(
        playbackCoordinator = playbackCoordinator,
        remoteMediaClient = { currentSession?.remoteMediaClient },
        playingState = playingState,
    )
    private val mediaLoader = CastMediaLoader(
        context = applicationContext,
        currentSession = { currentSession },
        playbackSynchronizer = playbackSynchronizer,
        playingState = playingState,
    )
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
        connectionValidationJob?.cancel()
        currentSession = session
        _connectionState.update { false }
        activeMedia = playingManager.activePlayback.value?.media?.toCurrentVideo()
        playbackSynchronizer.reset()
        session.remoteMediaClient?.registerCallback(remoteMediaListener)
        validateConnection(session, attempt = 1)
    }

    private fun completeConnection(session: CastSession) {
        if (currentSession != session || _connectionState.value) return

        connectionValidationJob?.cancel()
        connectionValidationJob = null
        _connectionState.update { true }
        Server.start(applicationContext)
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

    private fun validateConnection(session: CastSession, attempt: Int) {
        if (currentSession != session) return
        val remoteMediaClient = session.remoteMediaClient
        if (remoteMediaClient == null) {
            retryConnectionValidation(session, attempt)
            return
        }

        remoteMediaClient.requestStatus().setResultCallback { result ->
            if (currentSession != session) return@setResultCallback
            if (result.status.isSuccess) {
                completeConnection(session)
            } else {
                logcat(LogPriority.WARN) {
                    "Cast receiver handshake attempt $attempt failed: ${result.status.statusCode}"
                }
                retryConnectionValidation(session, attempt)
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun retryConnectionValidation(session: CastSession, failedAttempt: Int) {
        if (currentSession != session) return
        if (failedAttempt >= MAX_CONNECTION_ATTEMPTS) {
            logcat(LogPriority.ERROR) { "Cast receiver did not become ready" }
            castContext.sessionManager.endCurrentSession(true)
            return
        }

        connectionValidationJob?.cancel()
        connectionValidationJob = launchUI {
            delay(CONNECTION_RETRY_DELAY_MS)
            validateConnection(session, failedAttempt + 1)
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onNewSession(session)

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onNewSession(session)

        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionStarting(session: CastSession) = Unit

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            connectionValidationJob?.cancel()
            currentSession = null
            _connectionState.update { false }
            activeMedia = null
            playingManager.clearCastPlayback()
        }

        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            connectionValidationJob?.cancel()
            currentSession = null
            _connectionState.update { false }
            activeMedia = null
            playingManager.clearCastPlayback()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            connectionValidationJob?.cancel()
            currentSession = null
            _connectionState.update { false }
            activeMedia = null
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            connectionValidationJob?.cancel()
            mediaLoader.cancel()
            currentSession = null
            _connectionState.update { false }
            activeMedia = null
            playingManager.clearCastPlayback()
            playbackSynchronizer.reset()
            Server.stop()
        }
    }

    private val remoteMediaListener = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val session = currentSession ?: return
            completeConnection(session)
            val status = session.remoteMediaClient?.mediaStatus ?: return
            playingManager.updateCastPlayback(
                positionMs = status.streamPosition,
                capturedAtMs = System.currentTimeMillis(),
                isPlaying = status.playerState == MediaStatus.PLAYER_STATE_PLAYING,
                playbackSpeed = status.playbackRate,
            )
            playbackSynchronizer.onStatusUpdated(status)
        }

        override fun onMediaError(error: MediaError) {
            logcat(LogPriority.ERROR) {
                "Cast media error: detailedErrorCode=${error.detailedErrorCode} " +
                    "reason=${error.reason} customData=${error.customData}"
            }
        }
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

    private fun handleOpenVideoCommand(
        media: LoadedVideoEvent<*>,
        newCurrentVideo: CurrentVideo,
        positionAnchor: PlaybackPositionAnchor,
        synchronizePosition: Boolean,
    ) {
        val isAlreadyLoaded = activeMedia?.videoId == newCurrentVideo.videoId &&
            activeMedia?.videoUrl == newCurrentVideo.videoUrl
        if (isAlreadyLoaded && !synchronizePosition) return

        mediaLoader.updatePositionAnchor(positionAnchor)
        playingManager.saveCastPlayback(
            media = media,
            animeCover = newCurrentVideo.animeCover,
            positionMs = positionAnchor.positionMs,
            capturedAtMs = positionAnchor.capturedAtMs,
            isPlaying = positionAnchor.isPlaying,
            playbackSpeed = positionAnchor.playbackSpeed,
        )
        logcat {
            "CastHandler: Processing video event for ${media.videoType} | Current Video $activeMedia | Build media: $newCurrentVideo "
        }

        if (isAlreadyLoaded) {
            playbackSynchronizer.seekTo(positionAnchor)
            return
        }

        activeMedia = newCurrentVideo
        mediaLoader.load(newCurrentVideo, positionAnchor)
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
                    val newCurrentVideo = media.toCurrentVideo() ?: return@launchUI
                    handleOpenVideoCommand(
                        media = media,
                        newCurrentVideo = newCurrentVideo,
                        positionAnchor = PlaybackPositionAnchor(
                            positionMs = 0L,
                            capturedAtMs = command.eventTime,
                            isPlaying = true,
                            playbackSpeed = 1.0,
                        ),
                        synchronizePosition = false,
                    )
                }

                PlaybackCommandType.SYNC_STATE -> {
                    val states = command.newValue as? PlaybackSyncState ?: return@launchUI
                    val newCurrentVideo = states.media.toCurrentVideo() ?: return@launchUI
                    handleOpenVideoCommand(
                        media = states.media,
                        newCurrentVideo = newCurrentVideo,
                        positionAnchor = PlaybackPositionAnchor(
                            positionMs = states.positionMs,
                            capturedAtMs = command.eventTime,
                            isPlaying = states.playWhenReady,
                            playbackSpeed = states.playbackSpeed.toDouble(),
                        ),
                        synchronizePosition = true,
                    )

                    playingState.update { states.playWhenReady }
                    if (!states.playWhenReady) {
                        remoteMediaClient.pause()
                    }
                }

                PlaybackCommandType.PAUSE -> {
                    playingState.update { false }
                    playbackSynchronizer.clearPendingPosition()
                    remoteMediaClient.pause()
                }

                PlaybackCommandType.PLAY -> {
                    playingState.update { true }
                    remoteMediaClient.play()
                }

                PlaybackCommandType.STOP -> {
                    playingState.update { false }
                    playbackSynchronizer.clearPendingPosition()
                    remoteMediaClient.stop()
                }

                PlaybackCommandType.SET_SPEED -> {
                    val speed = command.newValue as? Double ?: return@launchUI
                    remoteMediaClient.setPlaybackRate(speed)
                }

                PlaybackCommandType.SEEK_TO -> {
                    val positionMs = (command.newValue as? Number)?.toLong() ?: return@launchUI
                    val positionAnchor = PlaybackPositionAnchor(
                        positionMs = positionMs,
                        capturedAtMs = command.eventTime,
                        isPlaying = playingState.value,
                        playbackSpeed = remoteMediaClient.mediaStatus?.playbackRate ?: 1.0,
                    )
                    mediaLoader.updatePositionAnchor(positionAnchor)
                    playbackSynchronizer.seekTo(positionAnchor)
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
        mediaLoader.load(
            video = CurrentVideo(
                videoId = originalUrl,
                videoUrl = originalUrl,
                videoTitle = title,
                videoSubTitle = subtitle,
                animeCover = animeCover,
                headers = headers,
            ),
            positionAnchor = PlaybackPositionAnchor(
                positionMs = 0L,
                capturedAtMs = System.currentTimeMillis(),
                isPlaying = true,
                playbackSpeed = 1.0,
            ),
        )
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
        return _connectionState.value
    }

    companion object {
        private const val MAX_CONNECTION_ATTEMPTS = 4
        private const val CONNECTION_RETRY_DELAY_MS = 750L

        @Volatile
        private var instance: CastHandler? = null

        fun getInstance(context: Context): CastHandler {
            return instance ?: synchronized(this) {
                instance ?: CastHandler(context).also { instance = it }
            }
        }
    }
}
