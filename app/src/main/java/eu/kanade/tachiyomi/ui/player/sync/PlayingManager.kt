package eu.kanade.tachiyomi.ui.player.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tachiyomi.domain.entries.anime.model.AnimeCover

data class ManagedPlayback(
    val media: LoadedVideoEvent<*>,
    val animeCover: AnimeCover?,
    val positionMs: Long,
    val capturedAtMs: Long,
    val isPlaying: Boolean,
    val playbackSpeed: Double,
    val origin: SyncOrigin,
) {
    fun positionAt(nowMs: Long): Long {
        val elapsedMs = (nowMs - capturedAtMs).coerceAtLeast(0L)
        val progressedMs = if (isPlaying) (elapsedMs * playbackSpeed).toLong() else 0L
        return (positionMs + progressedMs).coerceAtLeast(0L)
    }
}

class PlayingManager private constructor() {
    private val _activePlayback = MutableStateFlow<ManagedPlayback?>(null)
    val activePlayback = _activePlayback.asStateFlow()

    fun saveCastPlayback(
        media: LoadedVideoEvent<*>,
        animeCover: AnimeCover?,
        positionMs: Long,
        capturedAtMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Double,
    ) {
        _activePlayback.value = ManagedPlayback(
            media = media,
            animeCover = animeCover,
            positionMs = positionMs,
            capturedAtMs = capturedAtMs,
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed,
            origin = SyncOrigin.CAST,
        )
    }

    fun updateCastPlayback(
        positionMs: Long,
        capturedAtMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Double,
    ) {
        _activePlayback.update { playback ->
            playback
                ?.takeIf { it.origin == SyncOrigin.CAST }
                ?.copy(
                    positionMs = positionMs,
                    capturedAtMs = capturedAtMs,
                    isPlaying = isPlaying,
                    playbackSpeed = playbackSpeed,
                )
        }
    }

    fun hasActiveCastPlayback(): Boolean {
        return _activePlayback.value?.origin == SyncOrigin.CAST
    }

    fun clearCastPlayback() {
        _activePlayback.update { playback ->
            playback?.takeUnless { it.origin == SyncOrigin.CAST }
        }
    }

    companion object {
        @Volatile
        private var instance: PlayingManager? = null

        fun getInstance(): PlayingManager {
            return instance ?: synchronized(this) {
                instance ?: PlayingManager().also { instance = it }
            }
        }
    }
}
