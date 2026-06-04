package eu.kanade.presentation.updates.manga

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
import eu.kanade.presentation.entries.manga.components.ChapterDownloadAction
import eu.kanade.presentation.updates.manga.components.MangaUpdatesUiChapter
import eu.kanade.presentation.updates.manga.components.MangaUpdatesUiManga
import eu.kanade.presentation.updates.manga.model.MangaUpdatesUiModels
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.updates.manga.MangaUpdatesItem
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.time.LocalDate

internal fun LazyListScope.mangaUpdatesLastUpdatedItem(
    lastUpdated: Long,
) {
    item(key = "mangaUpdates-lastUpdated") {
        Box(
            modifier = Modifier
                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        ) {
            Text(
                text = stringResource(MR.strings.updates_last_update_info, relativeTimeSpanString(lastUpdated)),
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

internal fun LazyListScope.mangaUpdatesUiItems(
    uiModels: List<MangaUpdatesUiModel>,
    selectionMode: Boolean,
    openedMangas: Set<Pair<Long, LocalDate>>,
    onToggleManga: (Long, LocalDate) -> Unit,
    onUpdateSelected: (MangaUpdatesItem, Boolean, Boolean, Boolean) -> Unit,
    onClickCover: (MangaUpdatesItem) -> Unit,
    onClickUpdate: (MangaUpdatesItem) -> Unit,
    onDownloadChapter: (List<MangaUpdatesItem>, ChapterDownloadAction) -> Unit,
) {
    val result = mutableListOf<MutableList<MangaUpdatesUiModel>>()
    for (model in uiModels) {
        when (model) {
            is MangaUpdatesUiModel.Header -> result.add(mutableListOf(model))
            is MangaUpdatesUiModel.Item -> result.lastOrNull()?.add(model)
        }
    }

    items(
        items = result,
        key = { group ->
            when (val first = group.firstOrNull()) {
                is MangaUpdatesUiModel.Header -> "mangaUpdatesGroupHeader-${first.hashCode()}"
                is MangaUpdatesUiModel.Item -> "mangaUpdatesGroup-${first.item.update.mangaId}"
                null -> "mangaUpdatesGroup-empty-${group.hashCode()}"
            }
        },
        contentType = { "group" },
    ) { item ->
        val header = item.find { it is MangaUpdatesUiModel.Header } as? MangaUpdatesUiModel.Header
        val itemsList = item.filterIsInstance<MangaUpdatesUiModel.Item>()
        val groupedItems = itemsList.groupBy { it.item.update.mangaId }
        val mangas = groupedItems.map { entry ->
            val chapterAmount = entry.value.size
            val subText = pluralStringResource(
                AYMR.plurals.updated_amount_episodes,
                chapterAmount,
                chapterAmount
            )
            MangaUpdatesUiModels.Manga(
                mangaId = entry.key,
                mangaTitle = entry.value.first().item.update.mangaTitle,
                coverData = entry.value.first().item.update.coverData,
                subText = subText,
            )
        }

        ListGroupHeader(
            modifier = Modifier.animateItemFastScroll(),
            text = relativeDateText(header?.date ?: return@items),
        )
        for (manga in mangas) {
            val chapters = groupedItems[manga.mangaId]?.map { it.item } ?: emptyList()
            val indicatorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

            MangaUpdatesUiManga(
                modifier = Modifier.animateItemFastScroll(),
                manga = manga,
                selected = chapters.isNotEmpty() && chapters.fastAll { it.selected },
                onClick = { onToggleManga(manga.mangaId, header.date) },
                onLongClick = {
                    for (chapter in chapters) {
                        onUpdateSelected(chapter, true, true, true)
                    }
                },
                openManga = Pair(manga.mangaId, header.date) in openedMangas,
            )
            if (Pair(manga.mangaId, header.date) in openedMangas) {
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
                    for (chapter in chapters) {
                        MangaUpdatesUiChapter(
                            modifier = Modifier.animateItemFastScroll(),
                            update = chapter.update,
                            selected = chapter.selected,
                            readProgress = chapter.update.lastPageRead
                                .takeIf { !chapter.update.read && it > 0L }
                                ?.let { stringResource(MR.strings.chapter_progress, it + 1) },
                            onLongClick = { onUpdateSelected(chapter, !chapter.selected, true, true) },
                            onClick = {
                                when {
                                    selectionMode -> onUpdateSelected(chapter, !chapter.selected, true, false)
                                    else -> onClickUpdate(chapter)
                                }
                            },
                            onDownloadChapter = { action: ChapterDownloadAction ->
                                onDownloadChapter(listOf(chapter), action)
                            }.takeIf { !selectionMode },
                            downloadStateProvider = chapter.downloadStateProvider,
                            downloadProgressProvider = chapter.downloadProgressProvider,
                        )
                    }
                }
            }
        }
    }
}
