package eu.kanade.presentation.updates.anime

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.entries.anime.components.EpisodeDownloadAction
import eu.kanade.presentation.updates.anime.components.AnimeUpdatesUiAnime
import eu.kanade.presentation.updates.anime.components.AnimeUpdatesUiEpisode
import eu.kanade.presentation.updates.anime.model.AnimeUpdatesUiModels
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.updates.anime.AnimeUpdatesItem
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.time.LocalDate
import java.util.concurrent.TimeUnit

internal fun LazyListScope.animeUpdatesLastUpdatedItem(
    lastUpdated: Long,
) {
    item(key = "animeUpdates-lastUpdated") {
        Box(
            modifier = Modifier
                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                .padding(
                    horizontal = MaterialTheme.padding.medium,
                    vertical = MaterialTheme.padding.small,
                ),
        ) {
            Text(
                text = stringResource(MR.strings.updates_last_update_info, relativeTimeSpanString(lastUpdated)),
                fontStyle = FontStyle.Italic,
            )
        }
    }
}


internal fun LazyListScope.animeUpdatesUiItems(
    uiModels: List<AnimeUpdatesUiModel>,
    selectionMode: Boolean,
    openedAnimes: Set<Pair<Long, LocalDate>>,
    onToggleAnime: (Long, LocalDate) -> Unit,
    onUpdateSelected: (AnimeUpdatesItem, Boolean, Boolean, Boolean) -> Unit,
    onClickCover: (AnimeUpdatesItem) -> Unit,
    onClickUpdate: (AnimeUpdatesItem, altPlayer: Boolean) -> Unit,
    onDownloadEpisode: (List<AnimeUpdatesItem>, EpisodeDownloadAction) -> Unit,
) {
    val result = mutableListOf<MutableList<AnimeUpdatesUiModel>>()
    for (model in uiModels) {
        when (model) {
            is AnimeUpdatesUiModel.Header -> result.add(mutableListOf(model))
            is AnimeUpdatesUiModel.Item -> result.lastOrNull()?.add(model)
        }
    }

    items(
        items = result,
        key = { group ->
            when (val first = group.firstOrNull()) {
                is AnimeUpdatesUiModel.Header -> "animeUpdatesGroupHeader-${first.hashCode()}"
                is AnimeUpdatesUiModel.Item -> "animeUpdatesGroup-${first.item.update.animeId}"
                null -> "animeUpdatesGroup-empty-${group.hashCode()}"
            }
        },
        contentType = { "group" },
    ) { item ->
        val header = item.find { it is AnimeUpdatesUiModel.Header } as? AnimeUpdatesUiModel.Header
        val itemsList = item.filterIsInstance<AnimeUpdatesUiModel.Item>()
        val groupedItems = itemsList.groupBy { it.item.update.animeId }
        val animes = groupedItems.map { entry ->
            val episodeAmount = entry.value.size
            val subText = pluralStringResource(
                AYMR.plurals.updated_amount_episodes,
                episodeAmount,
                episodeAmount
            )
            AnimeUpdatesUiModels.Anime(
                animeId = entry.key,
                animeTitle = entry.value.first().item.update.animeTitle,
                coverData = entry.value.first().item.update.coverData,
                subText = subText,
            )
        }

        ListGroupHeader(
            modifier = Modifier.animateItemFastScroll(),
            text = relativeDateText(header?.date ?: return@items),
        )
        for (anime in animes) {
            val episodes = groupedItems[anime.animeId]?.map { it.item } ?: emptyList()
            val indicatorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

            AnimeUpdatesUiAnime(
                modifier = Modifier.animateItemFastScroll(),
                anime = anime,
                selected = episodes.isNotEmpty() && episodes.fastAll { it.selected },
                onClick = { onToggleAnime(anime.animeId, header.date) },
                onLongClick = {
                    for (episode in episodes) {
                        onUpdateSelected(episode, true, true, true)
                    }
                },
                openAnime = Pair(anime.animeId, header.date) in openedAnimes,
            )
            if (Pair(anime.animeId, header.date) in openedAnimes) {
                Column(
                    modifier = Modifier
                        .padding(start = MaterialTheme.padding.large)
                        .drawBehind {
                            drawLine(
                                color = indicatorColor,
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                        },
                ) {
                    for (episode in episodes) {
                        AnimeUpdatesUiEpisode(
                            modifier = Modifier.animateItemFastScroll(),
                            update = episode.update,
                            selected = episode.selected,
                            watchProgress = episode.update.lastSecondSeen
                                .takeIf { it > 0 }
                                ?.let {
                                    stringResource(
                                        AYMR.strings.episode_progress,
                                        formatProgress(it),
                                        formatProgress(episode.update.totalSeconds),
                                    )
                                },
                            onLongClick = { onUpdateSelected(episode, !episode.selected, true, true) },
                            onClick = {
                                when {
                                    selectionMode -> onUpdateSelected(episode, !episode.selected, true, false)
                                    else -> onClickUpdate(episode, false)
                                }
                            },
                            onClickCover = { onClickCover(episode) }.takeIf { !selectionMode },
                            onDownloadEpisode = { action: EpisodeDownloadAction ->
                                onDownloadEpisode(listOf(episode), action)
                            }.takeIf { !selectionMode },
                            downloadStateProvider = episode.downloadStateProvider,
                            downloadProgressProvider = episode.downloadProgressProvider,
                        )
                    }
                }
            }
        }
    }
}

private fun formatProgress(milliseconds: Long): String {
    return if (milliseconds > 3600000L) {
        String.format(
            "%d:%02d:%02d",
            TimeUnit.MILLISECONDS.toHours(milliseconds),
            TimeUnit.MILLISECONDS.toMinutes(milliseconds) -
                TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(milliseconds)),
            TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(milliseconds)),
        )
    } else {
        String.format(
            "%d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(milliseconds),
            TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(milliseconds)),
        )
    }
}
