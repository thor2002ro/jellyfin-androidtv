package org.jellyfin.playback.jellyfin.playsession

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.backend.PlayerTrack
import org.jellyfin.playback.core.backend.TrackType

class SelectedStreamIndexTests : FunSpec({
	test("an unmapped selected native track is unknown rather than off or the previous server index") {
		val tracks = listOf(PlayerTrack(42, TrackType.SUBTITLE, null, null, null, true))
		tracks.selectedJellyfinStreamIndex(-1) shouldBe null
		tracks.selectedJellyfinStreamIndex(2) shouldBe null
	}
	test("mapped selection and no selection retain server reporting semantics") {
		val track = PlayerTrack(42, TrackType.SUBTITLE, null, null, null, true, streamIndex = 7)
		listOf(track).selectedJellyfinStreamIndex(-1) shouldBe 7
		listOf(track.copy(isSelected = false)).selectedJellyfinStreamIndex(-1) shouldBe -1
		emptyList<PlayerTrack>().selectedJellyfinStreamIndex(2) shouldBe 2
	}
})
