package org.jellyfin.androidtv.ui.playback

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

data class SeasonTransition(
	val episodesRemaining: Int,
	val nextSeasonNumber: Int,
)

internal fun List<BaseItemDto>.seasonTransitionAt(index: Int): SeasonTransition? {
	val currentItem = getOrNull(index)?.takeIf { it.type == BaseItemKind.EPISODE } ?: return null
	val seriesId = currentItem.seriesId ?: return null
	val currentSeason = currentItem.parentIndexNumber?.takeIf { it > 0 } ?: return null
	val nextSeasonIndex = ((index + 1)..lastIndex).firstOrNull { itemIndex ->
		val item = get(itemIndex)
		item.type != BaseItemKind.EPISODE ||
			item.seriesId != seriesId ||
			item.parentIndexNumber != currentSeason
	} ?: return null
	val nextSeasonItem = get(nextSeasonIndex)
	val nextSeason = nextSeasonItem.parentIndexNumber?.takeIf {
		nextSeasonItem.type == BaseItemKind.EPISODE &&
			nextSeasonItem.seriesId == seriesId &&
			it > currentSeason
	} ?: return null
	val episodesRemaining = nextSeasonIndex - index - 1

	return SeasonTransition(episodesRemaining, nextSeason)
		.takeIf { episodesRemaining < 4 }
}

@Composable
internal fun SeasonTransition.text(): String = if (episodesRemaining == 0) {
	stringResource(R.string.season_up_next, nextSeasonNumber)
} else {
	pluralStringResource(
		R.plurals.episodes_left_till_season,
		episodesRemaining,
		episodesRemaining,
		nextSeasonNumber,
	)
}
