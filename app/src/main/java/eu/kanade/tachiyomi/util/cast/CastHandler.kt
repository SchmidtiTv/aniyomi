package eu.kanade.tachiyomi.util.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManagerListener
import eu.kanade.tachiyomi.ui.player.sync.ListenerType
import eu.kanade.tachiyomi.ui.player.sync.PlaybackListener
import eu.kanade.tachiyomi.ui.player.sync.PlaybackSessionState
import eu.kanade.tachiyomi.ui.player.sync.PlaybackSyncCoordinator
import eu.kanade.tachiyomi.util.cast.proxy.Server
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.system.logcat
import java.util.UUID

class CastHandler private constructor(context: Context) {
    private val applicationContext: Context = context.applicationContext
    private val castContext: CastContext = CastContext.getSharedInstance(applicationContext)

    private val playbackSyncListenerId = "CastHandler-${UUID.randomUUID()}"
    private val playbackCoordinator = PlaybackSyncCoordinator.getInstance()

    private var currentSession: CastSession? = null
    private var currentLoadedMediaId: String? = null

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
    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            currentSession = session
            currentLoadedMediaId = null
            Server.start(applicationContext)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            currentSession = session
            currentLoadedMediaId = null
            Server.start(applicationContext)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            currentSession = null
            currentLoadedMediaId = null
            Server.stop()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            currentSession = null
            currentLoadedMediaId = null
        }
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            currentSession = null
            currentLoadedMediaId = null
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            currentSession = null
            currentLoadedMediaId = null
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
                ::handlePlaybackStateChange,
            ),
        )
    }

    fun handlePlaybackStateChange(state: PlaybackSessionState) {
        launchUI {
            if (!isConnected()) return@launchUI

            if (currentLoadedMediaId != state.mediaId) {
                logcat { "Handler trigger: ${state.mediaId}. Attempting to load the video!" }
                currentLoadedMediaId = state.mediaId
                loadVideo(
                    originalUrl = state.mediaId,
                )
            } else {
                // The video is already loaded!
                // Here we can sync playback state (play/pause/seek) without reloading the entire video.
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
