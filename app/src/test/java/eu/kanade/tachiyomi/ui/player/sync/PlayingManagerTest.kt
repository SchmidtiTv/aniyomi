package eu.kanade.tachiyomi.ui.player.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.entries.anime.model.AnimeCover

class PlayingManagerTest {
    private val manager = PlayingManager.getInstance()
    private val media = LoadedVideoEvent(
        videoType = VideoType.VIDEO,
        video = "video",
        title = "Title",
        subtitle = "Episode 1",
    )
    private val animeCover = AnimeCover(
        animeId = 1L,
        sourceId = 2L,
        isAnimeFavorite = true,
        url = "https://example.com/cover.jpg",
        lastModified = 3L,
    )

    @BeforeEach
    fun resetManager() {
        manager.clearCastPlayback()
    }

    @Test
    fun `cast playback remains active after being saved`() {
        manager.saveCastPlayback(
            media = media,
            animeCover = animeCover,
            positionMs = 1_000L,
            capturedAtMs = 2_000L,
            isPlaying = true,
            playbackSpeed = 1.0,
        )

        assertTrue(manager.hasActiveCastPlayback())
        assertEquals(media, manager.activePlayback.value?.media)
        assertEquals(animeCover, manager.activePlayback.value?.animeCover)
    }

    @Test
    fun `cast status updates retained playback`() {
        manager.saveCastPlayback(
            media = media,
            animeCover = null,
            positionMs = 1_000L,
            capturedAtMs = 2_000L,
            isPlaying = true,
            playbackSpeed = 1.0,
        )

        manager.updateCastPlayback(
            positionMs = 5_000L,
            capturedAtMs = 6_000L,
            isPlaying = false,
            playbackSpeed = 1.25,
        )

        val playback = manager.activePlayback.value
        assertEquals(5_000L, playback?.positionMs)
        assertEquals(6_000L, playback?.capturedAtMs)
        assertFalse(playback?.isPlaying ?: true)
        assertEquals(1.25, playback?.playbackSpeed)
    }

    @Test
    fun `playing cast position advances from its latest status anchor`() {
        val playback = ManagedPlayback(
            media = media,
            animeCover = null,
            positionMs = 5_000L,
            capturedAtMs = 10_000L,
            isPlaying = true,
            playbackSpeed = 1.25,
            origin = SyncOrigin.CAST,
        )

        assertEquals(7_500L, playback.positionAt(nowMs = 12_000L))
    }

    @Test
    fun `paused cast position remains at its latest status anchor`() {
        val playback = ManagedPlayback(
            media = media,
            animeCover = null,
            positionMs = 5_000L,
            capturedAtMs = 10_000L,
            isPlaying = false,
            playbackSpeed = 1.25,
            origin = SyncOrigin.CAST,
        )

        assertEquals(5_000L, playback.positionAt(nowMs = 12_000L))
    }

    @Test
    fun `ending cast clears retained playback`() {
        manager.saveCastPlayback(
            media = media,
            animeCover = null,
            positionMs = 1_000L,
            capturedAtMs = 2_000L,
            isPlaying = true,
            playbackSpeed = 1.0,
        )

        manager.clearCastPlayback()

        assertFalse(manager.hasActiveCastPlayback())
        assertEquals(null, manager.activePlayback.value)
    }
}
