package org.jellyfin.androidtv.test

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class PlaybackWatchStateTests : FunSpec({
	val baseline = PlaybackWatchState(
		played = false,
		playCount = 2,
		positionTicks = 12_000,
		favorite = true,
		likes = null,
		rating = 8.0,
		lastPlayed = "2026-09-01T10:00:00Z",
	)

	test("identical watched state has no differences") {
		diffPlaybackWatchState(baseline, baseline.copy()) shouldBe emptyList()
	}

	test("all watched fields are guarded") {
		val changed = baseline.copy(
			played = true,
			playCount = 3,
			positionTicks = 99_000,
			favorite = false,
			likes = true,
			rating = 9.0,
			lastPlayed = "2026-09-09T10:00:00Z",
		)

		diffPlaybackWatchState(baseline, changed).map(PlaybackWatchDifference::field) shouldContainExactly listOf(
			"played", "playCount", "positionTicks", "favorite", "likes", "rating", "lastPlayed"
		)
	}
})
