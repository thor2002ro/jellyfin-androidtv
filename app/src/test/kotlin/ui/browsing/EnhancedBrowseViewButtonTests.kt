package org.jellyfin.androidtv.ui.browsing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.presentation.ActionButtonSize
import org.jellyfin.sdk.model.api.BaseItemKind

class EnhancedBrowseViewButtonTests : FunSpec({
	test("library view actions keep modern tile icons") {
		mapOf(
			EnhancedBrowseFragment.GRID to R.drawable.ic_grid,
			EnhancedBrowseFragment.BY_LETTER to R.drawable.ic_abc,
			EnhancedBrowseFragment.GENRES to R.drawable.ic_masks,
			EnhancedBrowseFragment.RANDOM to R.drawable.ic_shuffle,
			EnhancedBrowseFragment.SUGGESTED to R.drawable.ic_lightbulb,
			EnhancedBrowseFragment.ALBUMS to R.drawable.ic_album,
			EnhancedBrowseFragment.ALBUM_ARTISTS to R.drawable.ic_users,
			EnhancedBrowseFragment.ARTISTS to R.drawable.ic_artist,
			EnhancedBrowseFragment.SHUFFLE_SONGS to R.drawable.ic_shuffle,
			EnhancedBrowseFragment.SCHEDULE to R.drawable.ic_time,
			EnhancedBrowseFragment.SERIES to R.drawable.ic_tv_play,
		).forEach { (id, icon) ->
			EnhancedBrowseFragment.viewButtonIcon(id) shouldBe icon
		}
	}

	test("TV show view actions use the single-row size") {
		EnhancedBrowseFragment.viewButtonSize(BaseItemKind.SERIES) shouldBe ActionButtonSize.SINGLE
		EnhancedBrowseFragment.viewButtonSize(BaseItemKind.MOVIE) shouldBe ActionButtonSize.DOUBLE
	}
})
