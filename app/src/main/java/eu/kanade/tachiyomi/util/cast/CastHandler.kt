package eu.kanade.tachiyomi.util.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManagerListener
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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.system.logcat
import java.util.UUID

data class CurrentVideo(
    val videoId: String,
    val videoTitle: String,
    val videoUrl: String,
)

class CastHandler private constructor(context: Context) {
    private val applicationContext: Context = context.applicationContext
    private val castContext: CastContext = CastContext.getSharedInstance(applicationContext)

    private val playbackSyncListenerId = "CastHandler-${UUID.randomUUID()}"
    private val playbackCoordinator = PlaybackSyncCoordinator.getInstance()

    private var currentSession: CastSession? = null
    private var activeMedia: CurrentVideo? = null

    private var playingState = MutableStateFlow(false)

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
        activeMedia = null
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

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onNewSession(session)

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onNewSession(session)

        override fun onSessionEnded(session: CastSession, error: Int) {
            currentSession = null
            activeMedia = null
            Server.stop()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            currentSession = null
            activeMedia = null
        }

        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            currentSession = null
            activeMedia = null
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            currentSession = null
            activeMedia = null
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

    private fun buildCurrentVideo(media: LoadedVideoEvent<*>): CurrentVideo? {
        return when (media.videoType) {
            VideoType.VIDEO -> {
                val video = media.video as? Video ?: return null
                if (video.videoUrl.isEmpty()) return null

                CurrentVideo(
                    videoId = video.videoUrl,
                    videoTitle = video.videoTitle,
                    videoUrl = video.videoUrl,
                )
            }

            VideoType.EPISODE -> {
                val video = media.video as? Episode ?: return null
                CurrentVideo(
                    video.id.toString(),
                    video.name,
                    video.url,
                )
            }
        }
    }

    private fun handleOpenVideoCommand(media: LoadedVideoEvent<*>, newCurrentVideo: CurrentVideo) {
        logcat { "CastHandler: Processing video event for ${media.videoType} | Current Video ${activeMedia.toString()} | Build media: $newCurrentVideo " }

        if (activeMedia?.videoId == newCurrentVideo.videoId && activeMedia?.videoUrl == newCurrentVideo.videoUrl)
            return

        activeMedia = newCurrentVideo

        loadVideo(
            originalUrl = newCurrentVideo.videoUrl,
            title = newCurrentVideo.videoTitle,
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
                    handleOpenVideoCommand(media, newCurrentVideo)
                }

                PlaybackCommandType.SYNC_STATE -> {
                    val states = command.newValue as? PlaybackSyncState ?: return@launchUI
                    val newCurrentVideo = buildCurrentVideo(states.media) ?: return@launchUI
                    handleOpenVideoCommand(states.media, newCurrentVideo)

                    playingState.update { states.playWhenReady }
                    if (!states.playWhenReady)
                        remoteMediaClient.pause()
                }

                PlaybackCommandType.PAUSE -> {
                    playingState.update { false }
                    remoteMediaClient.pause()
                }

                PlaybackCommandType.PLAY -> {
                    playingState.update { true }
                    remoteMediaClient.play()
                }

                PlaybackCommandType.STOP -> {
                    playingState.update { false }
                    remoteMediaClient.stop()
                }

                PlaybackCommandType.SET_SPEED -> {
                    val speed = command.newValue as? Double ?: return@launchUI
                    remoteMediaClient.setPlaybackRate(speed)
                }

                PlaybackCommandType.SEEK_TO -> {
                    val position = command.newValue as? Long ?: return@launchUI
                    MediaSeekOptions.Builder()
                        .setPosition(position)
                        .build()
                        .let { remoteMediaClient.seek(it) }
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
    ) {
        val session = currentSession ?: return
        val remoteMediaClient = session.remoteMediaClient ?: return

        val lowerUrl = originalUrl.lowercase()
        val mimeType = when {
            lowerUrl.endsWith(".mkv") -> "video/x-matroska"
            lowerUrl.endsWith(".webm") -> "video/webm"
            lowerUrl.endsWith(".m3u8") -> "application/x-mpegURL"
            else -> "video/mp4"
        }

        val proxiedUrl = Server.proxiedUrl(applicationContext, originalUrl, headers)

        val movieMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
        }

        val mediaInfo = MediaInfo.Builder(proxiedUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mimeType)
            .setMetadata(movieMetadata)
            .build()

        val requestData = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()

        remoteMediaClient.load(requestData)
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
        @Volatile
        private var instance: CastHandler? = null

        fun getInstance(context: Context): CastHandler {
            return instance ?: synchronized(this) {
                instance ?: CastHandler(context).also { instance = it }
            }
        }
    }
}
