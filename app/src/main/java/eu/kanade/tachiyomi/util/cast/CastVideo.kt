package eu.kanade.tachiyomi.util.cast

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.database.models.anime.Episode
import eu.kanade.tachiyomi.ui.player.sync.LoadedVideoEvent
import eu.kanade.tachiyomi.ui.player.sync.VideoType
import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.domain.entries.anime.model.asAnimeCover

data class CurrentVideo(
    val videoId: String,
    val videoTitle: String,
    val videoUrl: String,
    val videoSubTitle: String,
    val animeCover: AnimeCover? = null,
    val headers: Map<String, String> = emptyMap(),
)

internal fun LoadedVideoEvent<*>.toCurrentVideo(): CurrentVideo? {
    return when (videoType) {
        VideoType.VIDEO -> {
            val sourceVideo = video as? Video ?: return null
            if (sourceVideo.videoUrl.isEmpty()) return null

            CurrentVideo(
                videoId = sourceVideo.videoUrl,
                videoTitle = title,
                videoUrl = sourceVideo.videoUrl,
                videoSubTitle = subtitle,
                animeCover = anime?.asAnimeCover(),
                headers = headers,
            )
        }

        VideoType.EPISODE -> {
            val episode = video as? Episode ?: return null
            CurrentVideo(
                videoId = episode.id.toString(),
                videoUrl = episode.url,
                videoTitle = title,
                videoSubTitle = subtitle,
                animeCover = anime?.asAnimeCover(),
            )
        }
    }
}
