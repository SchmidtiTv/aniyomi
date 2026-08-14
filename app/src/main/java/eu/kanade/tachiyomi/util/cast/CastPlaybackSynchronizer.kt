package eu.kanade.tachiyomi.util.cast

import android.os.SystemClock
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import eu.kanade.tachiyomi.ui.player.sync.PlaybackCommand
import eu.kanade.tachiyomi.ui.player.sync.PlaybackCommandType
import eu.kanade.tachiyomi.ui.player.sync.PlaybackSyncCoordinator
import eu.kanade.tachiyomi.ui.player.sync.SyncOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.system.logcat
import java.util.UUID
import kotlin.math.abs

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
        return if (isPlaying) positionMs + (elapsedMs * playbackSpeed).toLong() else positionMs
    }
}

internal class CastPlaybackSynchronizer(
    private val playbackCoordinator: PlaybackSyncCoordinator,
    private val remoteMediaClient: () -> RemoteMediaClient?,
    private val playingState: MutableStateFlow<Boolean>,
) {
    private var activeContentId: String? = null
    private var pendingPositionSync: PendingPositionSync? = null
    private var lastRemotePlaybackSnapshot: RemotePlaybackSnapshot? = null
    private var lastRemotePlayerState: Int? = null
    private var mediaLoadPending = false

    fun reset() {
        activeContentId = null
        pendingPositionSync = null
        lastRemotePlaybackSnapshot = null
        lastRemotePlayerState = null
        mediaLoadPending = false
    }

    fun onMediaLoadStarted() {
        activeContentId = null
        pendingPositionSync = null
        lastRemotePlaybackSnapshot = null
        mediaLoadPending = true
    }

    fun onMediaLoaded(contentId: String, anchor: PlaybackPositionAnchor) {
        activeContentId = contentId
        pendingPositionSync = PendingPositionSync(contentId, anchor)
        mediaLoadPending = false
    }

    fun clearPendingPosition() {
        pendingPositionSync = null
    }

    fun seekTo(anchor: PlaybackPositionAnchor) {
        val client = remoteMediaClient() ?: return
        val contentId = activeContentId ?: return
        pendingPositionSync = PendingPositionSync(contentId, anchor)
        client.seek(
            MediaSeekOptions.Builder()
                .setPosition(anchor.positionAt(System.currentTimeMillis()))
                .build(),
        )
    }

    fun onStatusUpdated(status: MediaStatus) {
        if (mediaLoadPending) return

        val positionWasLocallyRequested = correctPendingPosition(status)
        emitRemotePlaybackState(status)
        updateRemotePosition(status, positionWasLocallyRequested)
    }

    private fun correctPendingPosition(status: MediaStatus): Boolean {
        val pending = pendingPositionSync ?: return false
        if (status.mediaInfo?.contentId != pending.contentId) return false
        if (status.playerState !in PLAYING_OR_PAUSED_STATES) return true

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
        remoteMediaClient()?.seek(
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
                lastRemotePlayerState in PLAYING_OR_PAUSED_STATES
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
        emit(commandType)
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
            emit(PlaybackCommandType.SET_SPEED, status.playbackRate)
        }

        if (
            positionWasLocallyRequested ||
            previousSnapshot == null ||
            previousSnapshot.contentId != contentId
        ) {
            return
        }

        val expectedPositionMs = previousSnapshot.expectedPositionAt(nowMs)
        if (abs(status.streamPosition - expectedPositionMs) >= REMOTE_SEEK_THRESHOLD_MS) {
            emit(PlaybackCommandType.SEEK_TO, status.streamPosition)
        }
    }

    private fun emit(commandType: PlaybackCommandType, newValue: Any? = null) {
        playbackCoordinator.addEvent(
            PlaybackCommand(
                commandId = UUID.randomUUID().toString(),
                commandType = commandType,
                eventTime = System.currentTimeMillis(),
                origin = SyncOrigin.CAST,
                newValue = newValue,
            ),
        )
    }

    private companion object {
        const val POSITION_DRIFT_THRESHOLD_MS = 500L
        const val REMOTE_SEEK_THRESHOLD_MS = 1_500L
        const val PLAYBACK_RATE_CHANGE_THRESHOLD = 0.01
        val PLAYING_OR_PAUSED_STATES = setOf(
            MediaStatus.PLAYER_STATE_PLAYING,
            MediaStatus.PLAYER_STATE_PAUSED,
        )
    }
}
