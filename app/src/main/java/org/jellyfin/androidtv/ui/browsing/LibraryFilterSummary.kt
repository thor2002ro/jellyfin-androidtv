package org.jellyfin.androidtv.ui.browsing

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.FilterOptions
import org.jellyfin.androidtv.data.model.PlaybackFilter
import org.jellyfin.androidtv.data.model.QualityFilter

fun FilterOptions.summary(context: Context, choices: LibraryFilterChoices): String {
	val labels = buildList {
		if (playback != PlaybackFilter.ANY) add(context.getString(playbackLabelResource(playback)))
		if (favoriteOnly) add(context.getString(R.string.lbl_favorites))
		if (genreIds.isNotEmpty()) {
			val selectedGenres = choices.genres.filter { genre -> genre.ids.any(genreIds::contains) }
			addAll(selectedGenres.map { it.name })
			if (genreIds.any { id -> selectedGenres.none { id in it.ids } }) add(context.getString(R.string.lbl_genres))
		}
		addAll(years.sorted().map(Int::toString))
		val selectedRatings = choices.ratings.filter { rating -> rating.values.any(officialRatings::contains) }
		addAll(selectedRatings.map { it.name })
		addAll((officialRatings - selectedRatings.flatMap { it.values }.toSet()).sorted())
		if (studioIds.isNotEmpty()) {
			val selectedStudios = choices.studios.filter { it.id in studioIds }
			addAll(selectedStudios.mapNotNull { it.name })
			if (studioIds.any { id -> selectedStudios.none { it.id == id } }) add(context.getString(R.string.library_filter_studios))
		}
		if (quality != QualityFilter.ANY) add(context.getString(qualityLabelResource(quality)))
		addAll(videoTypes.sortedBy { it.ordinal }.map { context.getString(videoTypeLabelResource(it)) })
	}
	return when {
		labels.isEmpty() -> context.getString(R.string.lbl_all_items)
		labels.size <= 3 -> labels.joinToString(", ")
		else -> context.getString(R.string.library_filter_active_count, labels.size)
	}
}
