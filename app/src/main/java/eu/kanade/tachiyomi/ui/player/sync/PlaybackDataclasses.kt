package eu.kanade.tachiyomi.ui.player.sync

import eu.kanade.tachiyomi.data.database.models.anime.Episode
import tachiyomi.domain.entries.anime.model.Anime

data class PlaybackTrackSelection(
    val audioTrackId: Int? = null,
    val subtitleTrackId: Int? = null,
)

data class PlaybackCommand<T>(
    val commandId: String,
    val eventTime: Long,
    val commandType: PlaybackCommandType,
    val newValue: T,
    val origin: SyncOrigin,
)

enum class PlaybackCommandType {
    PLAY,                       // Triggered when playback starts or resumes.
    PAUSE,                      // Triggered when playback is paused.
    // Triggered when the user seeks. The newValue is the target position in milliseconds.
    SEEK_TO,
    // Triggered when a media item is loaded. The newValue is its mediaId.
    LOAD_MEDIA,
    // Triggered when playback speed changes. The newValue is the new speed.
    SET_SPEED,
    // Triggered when audio or subtitle tracks change. The newValue contains the selected track IDs.
    SET_TRACK_SELECTION,
    STOP,                       // Triggered when playback is stopped, e.g The player gets closed.
    REQUEST_FULL_STATE,         // Triggered by a late-joiner (like UI) asking for the current state.
    // Triggered by the active player in response to REQUEST_FULL_STATE. The newValue is a PlaybackSessionState.
    SYNC_STATE,
}

data class PlaybackSessionListener(
    val listenerId: String,
    val listenerType: ListenerType,
    val callback: (PlaybackCommand<*>) -> Unit,
)

data class PlaybackSessionState(
    val media: LoadedVideoEvent<*>,
    val playWhenReady: Boolean,
    val positionMs: Long,
    val durationMs: Long?,
    val playbackState: PlaybackState,
    val playbackSpeed: Float = 1f,
    val trackSelection: PlaybackTrackSelection? = null,
)

data class LoadedVideoEvent<T>(
    val videoType: VideoType,
    val video: T,
    val title: String,
    val subtitle: String,
    val episode: Episode? = null,
    val anime: Anime? = null,
    val headers: Map<String, String> = emptyMap(),
)

enum class VideoType {
    EPISODE,
    VIDEO
}

enum class PlaybackState { IDLE, BUFFERING, READY, ENDED }

enum class SyncOrigin {
    LOCAL,
    CAST,
    SYSTEM,
}

enum class ListenerType {
    LOCAL_PLAYER,
    CAST_SESSION,
    UI_OBSERVER,
    SYSTEM_SERVICE,
}

typealias PlaybackEvent<T> = PlaybackCommand<T>
typealias PlaybackEventType = PlaybackCommandType
typealias PlaybackListener = PlaybackSessionListener
typealias PlaybackSyncState = PlaybackSessionState
