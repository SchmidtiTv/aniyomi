package eu.kanade.tachiyomi.util.cast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastMediaPreparerTest {

    @Test
    fun `safe mp4 can play directly`() {
        val result = probe(format = "mov,mp4,m4a,3gp,3g2,mj2")

        assertTrue(result.isVideoSafe)
        assertTrue(result.isAudioSafe)
        assertTrue(result.isDirectPlaySafe)
    }

    @Test
    fun `safe matroska requires container remux`() {
        val result = probe(format = "matroska,webm")

        assertTrue(result.isVideoSafe)
        assertTrue(result.isAudioSafe)
        assertFalse(result.isDirectPlaySafe)
    }

    @Test
    fun `dts requires only audio transcode`() {
        val result = probe(format = "matroska,webm", audioCodec = "dts")

        assertTrue(result.isVideoSafe)
        assertFalse(result.isAudioSafe)
        assertFalse(result.isDirectPlaySafe)
    }

    @Test
    fun `hevc is rejected by universal compatibility target`() {
        val result = probe(format = "matroska,webm", videoCodec = "hevc")

        assertFalse(result.isVideoSafe)
    }

    @Test
    fun `h264 high 10 and levels above 4 1 are rejected`() {
        assertFalse(probe(format = "matroska,webm", videoProfile = "High 10").isVideoSafe)
        assertFalse(probe(format = "matroska,webm", videoLevel = 42).isVideoSafe)
    }

    @Test
    fun `ffprobe json selects first video and audio streams`() {
        val result = CastMediaPreparer.parseProbe(
            """
            {
              "streams": [
                {"codec_name":"h264","profile":"High","codec_type":"video","level":41},
                {"codec_name":"aac","codec_type":"audio"},
                {"codec_name":"ass","codec_type":"subtitle"}
              ],
              "format": {"format_name":"matroska,webm"}
            }
            """.trimIndent(),
        )

        assertEquals(setOf("matroska", "webm"), result.formatNames)
        assertEquals("h264", result.videoCodec)
        assertEquals("High", result.videoProfile)
        assertEquals(41, result.videoLevel)
        assertEquals("aac", result.audioCodec)
    }

    @Test
    fun `playing position anchor compensates elapsed startup time`() {
        val anchor = PlaybackPositionAnchor(
            positionMs = 10_000L,
            capturedAtMs = 1_000L,
            isPlaying = true,
            playbackSpeed = 1.25,
        )

        assertEquals(12_500L, anchor.positionAt(nowMs = 3_000L))
    }

    @Test
    fun `paused position anchor does not advance`() {
        val anchor = PlaybackPositionAnchor(
            positionMs = 10_000L,
            capturedAtMs = 1_000L,
            isPlaying = false,
            playbackSpeed = 1.0,
        )

        assertEquals(10_000L, anchor.positionAt(nowMs = 5_000L))
    }

    @Test
    fun `hls uses Cast compatible content type instead of Android audio mime type`() {
        val contentType = CastMediaPreparer.directMediaContentType(
            originalUrl = "https://example.com/master.m3u8?token=abc",
            resolvedMimeType = "audio/x-mpegurl",
        )

        assertEquals("application/vnd.apple.mpegurl", contentType)
    }

    private fun probe(
        format: String,
        videoCodec: String = "h264",
        videoProfile: String = "High",
        videoLevel: Int = 41,
        audioCodec: String = "aac",
    ) = ProbeResult(
        formatNames = format.split(',').toSet(),
        videoCodec = videoCodec,
        videoProfile = videoProfile,
        videoLevel = videoLevel,
        audioCodec = audioCodec,
    )
}
