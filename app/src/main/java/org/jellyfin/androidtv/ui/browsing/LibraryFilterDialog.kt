@file:Suppress("LongMethod", "MatchingDeclarationName")

package org.jellyfin.androidtv.ui.browsing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.FilterOptions
import org.jellyfin.androidtv.data.model.PlaybackFilter
import org.jellyfin.androidtv.data.model.QualityFilter
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.VideoType
import java.util.UUID

data class LibraryFilterDialogState(
	val applied: FilterOptions = FilterOptions(),
	val draft: FilterOptions = FilterOptions(),
	val choices: LibraryFilterChoices = LibraryFilterChoices(),
	val loading: Boolean = false,
	val videoFiltersAvailable: Boolean = true,
) {
	fun clear(): LibraryFilterDialogState = copy(draft = FilterOptions())

	fun dismiss(): LibraryFilterDialogState = copy(draft = applied)

	fun apply(): LibraryFilterDialogState = copy(applied = draft)

	fun selectPlayback(value: PlaybackFilter): LibraryFilterDialogState = copy(draft = draft.copy(playback = value))

	fun toggleFavorite(): LibraryFilterDialogState = copy(draft = draft.copy(favoriteOnly = !draft.favoriteOnly))

	fun selectQuality(value: QualityFilter): LibraryFilterDialogState = copy(draft = draft.copy(quality = value))

	fun toggleGenre(ids: Set<UUID>): LibraryFilterDialogState = copy(
		draft = draft.copy(
			genreIds = if (ids.any(draft.genreIds::contains)) draft.genreIds - ids else draft.genreIds + ids,
		),
	)

	fun selectedGenreCount(): Int = choices.genres.count { genre -> genre.ids.any(draft.genreIds::contains) }

	fun toggleYear(year: Int): LibraryFilterDialogState = copy(draft = draft.copy(years = draft.years.toggle(year)))

	fun toggleRating(ratings: Set<String>): LibraryFilterDialogState = copy(
		draft = draft.copy(
			officialRatings = if (ratings.any(draft.officialRatings::contains)) {
				draft.officialRatings - ratings
			} else {
				draft.officialRatings + ratings
			},
		),
	)

	fun selectedRatingCount(): Int = choices.ratings.count { rating ->
		rating.values.any(draft.officialRatings::contains)
	}

	fun toggleStudio(id: UUID): LibraryFilterDialogState = copy(draft = draft.copy(studioIds = draft.studioIds.toggle(id)))

	fun toggleVideoType(type: VideoType): LibraryFilterDialogState = copy(
		draft = draft.copy(videoTypes = draft.videoTypes.toggle(type)),
	)
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

private enum class LibraryFilterEditorSection {
	PLAYBACK,
	GENRES,
	YEARS,
	RATINGS,
	STUDIOS,
	QUALITY,
	VIDEO_TYPES,
}

@Composable
internal fun LibraryFilterDialog(
	state: LibraryFilterDialogState,
	onStateChange: (LibraryFilterDialogState) -> Unit,
	onApply: (FilterOptions) -> Unit,
	onClear: (FilterOptions) -> Unit,
) {
	var section by remember { mutableStateOf<LibraryFilterEditorSection?>(null) }
	BackHandler(enabled = section != null) { section = null }

	when (section) {
		null -> LibraryFilterRoot(
			state = state,
			onOpenSection = { section = it },
			onStateChange = onStateChange,
			onApply = onApply,
			onClear = onClear,
		)

		LibraryFilterEditorSection.PLAYBACK -> RadioSection(
			title = stringResource(R.string.library_filter_playback),
			values = PlaybackFilter.entries,
			selected = state.draft.playback,
			label = { playbackLabel(it) },
			onSelect = {
				onStateChange(state.selectPlayback(it))
				section = null
			},
		)

		LibraryFilterEditorSection.QUALITY -> RadioSection(
			title = stringResource(R.string.library_filter_quality),
			values = QualityFilter.entries,
			selected = state.draft.quality,
			label = { qualityLabel(it) },
			onSelect = {
				onStateChange(state.selectQuality(it))
				section = null
			},
		)

		LibraryFilterEditorSection.GENRES -> GenreChoiceSection(
			title = stringResource(R.string.lbl_genres),
			items = state.choices.genres,
			selectedIds = state.draft.genreIds,
			onToggle = { onStateChange(state.toggleGenre(it)) },
			onDone = { section = null },
		)

		LibraryFilterEditorSection.YEARS -> CheckSection(
			title = stringResource(R.string.library_filter_years),
			values = state.choices.years,
			selected = state.draft.years,
			label = { it.toString() },
			onToggle = { onStateChange(state.toggleYear(it)) },
			onDone = { section = null },
		)

		LibraryFilterEditorSection.RATINGS -> CheckSection(
			title = stringResource(R.string.library_filter_ratings),
			values = state.choices.ratings,
			selected = state.choices.ratings.filterTo(linkedSetOf()) { rating ->
				rating.values.any(state.draft.officialRatings::contains)
			},
			label = { it.name },
			onToggle = { onStateChange(state.toggleRating(it.values)) },
			onDone = { section = null },
		)

		LibraryFilterEditorSection.STUDIOS -> ItemChoiceSection(
			title = stringResource(R.string.library_filter_studios),
			items = state.choices.studios,
			selectedIds = state.draft.studioIds,
			onToggle = { onStateChange(state.toggleStudio(it)) },
			onDone = { section = null },
		)

		LibraryFilterEditorSection.VIDEO_TYPES -> CheckSection(
			title = stringResource(R.string.library_filter_video_type),
			values = VideoType.entries,
			selected = state.draft.videoTypes,
			label = { videoTypeLabel(it) },
			onToggle = { onStateChange(state.toggleVideoType(it)) },
			onDone = { section = null },
		)
	}
}

@Composable
private fun LibraryFilterRoot(
	state: LibraryFilterDialogState,
	onOpenSection: (LibraryFilterEditorSection) -> Unit,
	onStateChange: (LibraryFilterDialogState) -> Unit,
	onApply: (FilterOptions) -> Unit,
	onClear: (FilterOptions) -> Unit,
) = SettingsColumn {
	item {
		ListSection(headingContent = { Text(stringResource(R.string.filters)) })
	}
	if (state.loading) item {
		ListButton(
			headingContent = { Text(stringResource(R.string.loading)) },
			onClick = {},
			enabled = false,
		)
	}
	item {
		ListButton(
			headingContent = { Text(stringResource(R.string.library_filter_playback)) },
			captionContent = { Text(playbackLabel(state.draft.playback)) },
			onClick = { onOpenSection(LibraryFilterEditorSection.PLAYBACK) },
		)
	}
	item {
		ListButton(
			headingContent = { Text(stringResource(R.string.lbl_favorites)) },
			trailingContent = { Checkbox(checked = state.draft.favoriteOnly) },
			onClick = { onStateChange(state.toggleFavorite()) },
		)
	}
	val genreAvailability = state.choices.availability(LibraryFilterSection.GENRES)
	if (genreAvailability != LibraryFilterSectionAvailability.HIDDEN) item {
		FilterCategoryButton(R.string.lbl_genres, state.selectedGenreCount(), genreAvailability) {
			onOpenSection(LibraryFilterEditorSection.GENRES)
		}
	}
	val yearAvailability = state.choices.availability(LibraryFilterSection.YEARS)
	if (yearAvailability != LibraryFilterSectionAvailability.HIDDEN) item {
		FilterCategoryButton(R.string.library_filter_years, state.draft.years.size, yearAvailability) {
			onOpenSection(LibraryFilterEditorSection.YEARS)
		}
	}
	val ratingAvailability = state.choices.availability(LibraryFilterSection.RATINGS)
	if (ratingAvailability != LibraryFilterSectionAvailability.HIDDEN) item {
		FilterCategoryButton(R.string.library_filter_ratings, state.selectedRatingCount(), ratingAvailability) {
			onOpenSection(LibraryFilterEditorSection.RATINGS)
		}
	}
	val studioAvailability = state.choices.availability(LibraryFilterSection.STUDIOS)
	if (studioAvailability != LibraryFilterSectionAvailability.HIDDEN) item {
		FilterCategoryButton(R.string.library_filter_studios, state.draft.studioIds.size, studioAvailability) {
			onOpenSection(LibraryFilterEditorSection.STUDIOS)
		}
	}
	if (state.videoFiltersAvailable) {
		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.library_filter_quality)) },
				captionContent = { Text(qualityLabel(state.draft.quality)) },
				onClick = { onOpenSection(LibraryFilterEditorSection.QUALITY) },
			)
		}
		item {
			FilterCategoryButton(R.string.library_filter_video_type, state.draft.videoTypes.size) {
				onOpenSection(LibraryFilterEditorSection.VIDEO_TYPES)
			}
		}
	}
	item {
		ListButton(
			headingContent = { Text(stringResource(R.string.library_filter_clear)) },
			onClick = {
				val cleared = state.clear().apply()
				onStateChange(cleared)
				onClear(cleared.applied)
			},
		)
	}
	item {
		ListButton(
			headingContent = { Text(stringResource(R.string.library_filter_apply)) },
			onClick = {
				val applied = state.apply()
				onStateChange(applied)
				onApply(applied.applied)
			},
		)
	}
}

@Composable
private fun FilterCategoryButton(
	labelRes: Int,
	count: Int,
	availability: LibraryFilterSectionAvailability = LibraryFilterSectionAvailability.AVAILABLE,
	onClick: () -> Unit,
) {
	ListButton(
		headingContent = { Text(stringResource(labelRes)) },
		captionContent = when {
			availability == LibraryFilterSectionAvailability.UNAVAILABLE -> {
				{ Text(stringResource(R.string.library_filter_unavailable)) }
			}
			count > 0 -> {
				{ Text(stringResource(R.string.library_filter_selected_count, count)) }
			}
			else -> null
		},
		onClick = onClick,
		enabled = availability == LibraryFilterSectionAvailability.AVAILABLE,
	)
}

@Composable
private fun <T> RadioSection(
	title: String,
	values: List<T>,
	selected: T,
	label: @Composable (T) -> String,
	onSelect: (T) -> Unit,
) = SettingsColumn {
	item { ListSection(headingContent = { Text(title) }) }
	items(values) { value ->
		ListButton(
			headingContent = { Text(label(value)) },
			trailingContent = { RadioButton(checked = selected == value) },
			onClick = { onSelect(value) },
		)
	}
}

@Composable
private fun <T> CheckSection(
	title: String,
	values: List<T>,
	selected: Set<T>,
	label: @Composable (T) -> String,
	onToggle: (T) -> Unit,
	onDone: () -> Unit,
) = SettingsColumn {
	item { ListSection(headingContent = { Text(title) }) }
	items(values) { value ->
		ListButton(
			headingContent = { Text(label(value)) },
			trailingContent = { Checkbox(checked = value in selected) },
			onClick = { onToggle(value) },
		)
	}
	item {
		ListButton(
			headingContent = { Text(stringResource(R.string.library_filter_done)) },
			onClick = onDone,
		)
	}
}

@Composable
private fun ItemChoiceSection(
	title: String,
	items: List<BaseItemDto>,
	selectedIds: Set<UUID>,
	onToggle: (UUID) -> Unit,
	onDone: () -> Unit,
) = CheckSection(
	title = title,
	values = items,
	selected = items.filterTo(linkedSetOf()) { it.id in selectedIds },
	label = { it.name.orEmpty() },
	onToggle = { onToggle(it.id) },
	onDone = onDone,
)

@Composable
private fun GenreChoiceSection(
	title: String,
	items: List<LibraryGenreChoice>,
	selectedIds: Set<UUID>,
	onToggle: (Set<UUID>) -> Unit,
	onDone: () -> Unit,
) = CheckSection(
	title = title,
	values = items,
	selected = items.filterTo(linkedSetOf()) { genre -> genre.ids.any(selectedIds::contains) },
	label = { it.name },
	onToggle = { onToggle(it.ids) },
	onDone = onDone,
)

@Composable
private fun playbackLabel(filter: PlaybackFilter): String = stringResource(
	when (filter) {
		PlaybackFilter.ANY -> R.string.library_filter_any
		PlaybackFilter.UNWATCHED -> R.string.lbl_unwatched
		PlaybackFilter.WATCHED -> R.string.lbl_watched
	}
)

@Composable
private fun qualityLabel(filter: QualityFilter): String = stringResource(
	when (filter) {
		QualityFilter.ANY -> R.string.library_filter_any
		QualityFilter.SD -> R.string.library_filter_quality_sd
		QualityFilter.HD -> R.string.library_filter_quality_hd
		QualityFilter.FOUR_K -> R.string.library_filter_quality_4k
	}
)

@Composable
private fun videoTypeLabel(type: VideoType): String = stringResource(
	when (type) {
		VideoType.VIDEO_FILE -> R.string.library_filter_video_file
		VideoType.ISO -> R.string.library_filter_video_iso
		VideoType.DVD -> R.string.library_filter_video_dvd
		VideoType.BLU_RAY -> R.string.library_filter_video_bluray
	}
)
