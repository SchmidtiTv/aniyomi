package eu.kanade.tachiyomi.ui.player.sync

data class PlaybackEvent<T>(
    val commandId: String,
    val eventTime: Long,
    val eventType: PlaybackEventType,
    val newValue: T
)

enum class PlaybackEventType {
    PLAY,
    PAUSE,
    SEEK,
    MEDIA_CHANGED,
    ENDED,
    ERROR
}



data class PlaybackListener(
    val listenerId: String,           // Unique identifier (e.g., "Cast", "Localplayer")
    val listenerType: ListenerType,   // Categorizes the listener's role
    val callback: (PlaybackSyncState) -> Unit // Passes the state to the listener upon update
)

data class PlaybackSyncState(
    val mediaId: String,              // Episode/Video Identifier
    val playWhenReady: Boolean,       // spielt oder pausiert
    val positionMs: Long,             // aktuelle Position
    val durationMs: Long?,            // optional, oft spät bekannt
    val playbackState: PlaybackState, // BUFFERING/READY/ENDED/IDLE

    // Konfliktauflösung
    val origin: SyncOrigin,           // LOCAL oder CAST
    val revision: Long,               // monoton hochzählen
    val updatedAtMs: Long,            // timestamp in ms
    val lastCommandId: String? = null // echo-loop Schutz
)

enum class PlaybackState { IDLE, BUFFERING, READY, ENDED }
enum class SyncOrigin { LOCAL, CAST, SYSTEM }
enum class ListenerType {
    LOCAL_PLAYER,   // The primary internal player (e.g., ExoPlayer)
    CAST_SESSION,   // Remote playback sessions (Chromecast, AirPlay)
    UI_OBSERVER,    // Interface components like SeekBars or Notification managers
    SYSTEM_SERVICE  // Media sessions or background synchronization tasks
}
