package eu.kanade.tachiyomi.ui.player.sync

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
    SEEK_TO,                    // Triggered when the user seeks to a new position. The newValue is the target position in milliseconds.
    LOAD_MEDIA,                 // Triggered when a new media item is loaded. The newValue is the mediaId of the loaded item.
    SET_SPEED,                  // Triggered when the playback speed is changed. The newValue is the new speed (e.g., 1.0 for normal speed).
    SET_TRACK_SELECTION,        // Triggered when the user changes audio or subtitle tracks. The newValue is a PlaybackTrackSelection object containing the selected track IDs.
    STOP,                       // Triggered when playback is stopped, e.g The player gets closed.
    REQUEST_FULL_STATE,         // Triggered by a late-joiner (like UI) asking for the current state.
    SYNC_STATE,                 // Triggered by the active player in response to REQUEST_FULL_STATE. The newValue is a PlaybackSessionState object.
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
    val subtitle: String
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
