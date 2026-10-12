package org.jellyfin.androidtv.ui.livetv

import org.jellyfin.androidtv.preference.SystemPreferences
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.java.KoinJavaComponent.inject

class GuideFilters {
	private val systemPreferences by inject<SystemPreferences>(SystemPreferences::class.java)

	var movies = false
		set(value) {
			field = value
			systemPreferences[SystemPreferences.liveTvGuideFilterMovies] = value
		}
	var news = false
		set(value) {
			field = value
			systemPreferences[SystemPreferences.liveTvGuideFilterNews] = value
		}
	var series = false
		set(value) {
			field = value
			systemPreferences[SystemPreferences.liveTvGuideFilterSeries] = value
		}
	var kids = false
		set(value) {
			field = value
			systemPreferences[SystemPreferences.liveTvGuideFilterKids] = value
		}
	var sports = false
		set(value) {
			field = value
			systemPreferences[SystemPreferences.liveTvGuideFilterSports] = value
		}
	var premiere = false
		set(value) {
			field = value
			systemPreferences[SystemPreferences.liveTvGuideFilterPremiere] = value
		}

	init {
		load()
	}

	fun load() {
		movies = systemPreferences[SystemPreferences.liveTvGuideFilterMovies]
		news = systemPreferences[SystemPreferences.liveTvGuideFilterNews]
		series = systemPreferences[SystemPreferences.liveTvGuideFilterSeries]
		kids = systemPreferences[SystemPreferences.liveTvGuideFilterKids]
		sports = systemPreferences[SystemPreferences.liveTvGuideFilterSports]
		premiere = systemPreferences[SystemPreferences.liveTvGuideFilterPremiere]
	}

	fun any() = movies || news || series || kids || sports || premiere

	fun passesFilter(program: BaseItemDto): Boolean {
		if (!any()) return true

		if (movies && Utils.isTrue(program.isMovie)) return !premiere || Utils.isTrue(program.isPremiere)
		if (news && Utils.isTrue(program.isNews)) {
			return !premiere || Utils.isTrue(program.isPremiere) || Utils.isTrue(program.isLive) || !Utils.isTrue(program.isRepeat)
		}
		if (series && Utils.isTrue(program.isSeries)) return !premiere || Utils.isTrue(program.isPremiere) || !Utils.isTrue(program.isRepeat)
		if (kids && Utils.isTrue(program.isKids)) return !premiere || Utils.isTrue(program.isPremiere)
		if (sports && Utils.isTrue(program.isSports)) return !premiere || Utils.isTrue(program.isPremiere) || Utils.isTrue(program.isLive)
		if (!movies && !news && !series && !kids && !sports) {
			return premiere && (
				Utils.isTrue(program.isPremiere) ||
					(Utils.isTrue(program.isSeries) && !Utils.isTrue(program.isRepeat)) ||
					(Utils.isTrue(program.isSports) && Utils.isTrue(program.isLive))
				)
		}

		return false
	}

	fun clear() {
		news = false
		series = false
		sports = false
		kids = false
		movies = false
		premiere = false
	}

	override fun toString() = if (any()) {
		"Content filtered. Showing channels with ${getFilterString()}"
	} else {
		"Showing all programs "
	}

	private fun getFilterString() = buildList {
		if (movies) add("movies")
		if (news) add("news")
		if (sports) add("sports")
		if (series) add("series")
		if (kids) add("kids")
		if (premiere) add("ONLY new")
	}.joinToString(", ")
}
