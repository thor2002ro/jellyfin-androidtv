package org.jellyfin.androidtv.ui.player.video

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentAction
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class VideoPlayerMediaSegmentTests : FunSpec({
	test("intro prompt is visible only while playback is inside an ask-to-skip segment") {
		val intro = segment(startSeconds = 5, endSeconds = 15)
		val action = { _: MediaSegmentDto -> MediaSegmentAction.ASK_TO_SKIP }

		findPromptSegment(listOf(intro), 4.seconds, action) shouldBe null
		findPromptSegment(listOf(intro), 5.seconds, action) shouldBe intro
		findPromptSegment(listOf(intro), 14.seconds, action) shouldBe intro
		findPromptSegment(listOf(intro), 15.seconds, action) shouldBe null
	}

	test("intro auto skip triggers once when natural playback crosses its start") {
		val intro = segment(startSeconds = 5, endSeconds = 15)
		val action = { _: MediaSegmentDto -> MediaSegmentAction.SKIP }

		findAutoSkipSegment(listOf(intro), 4.seconds, 5.seconds, emptySet(), action) shouldBe intro
		findAutoSkipSegment(listOf(intro), 5.seconds, 6.seconds, setOf(intro.stableKey), action) shouldBe null
	}

	test("seeking into an intro does not trigger automatic skipping") {
		val intro = segment(startSeconds = 5, endSeconds = 15)

		findAutoSkipSegment(
			segments = listOf(intro),
			previousPosition = 1.seconds,
			position = 10.seconds,
			handledKeys = emptySet(),
			actionFor = { MediaSegmentAction.SKIP },
			maximumNaturalAdvance = 2.seconds,
		) shouldBe null
	}
})

private fun segment(startSeconds: Long, endSeconds: Long) = MediaSegmentDto(
	id = UUID.randomUUID(),
	itemId = UUID.randomUUID(),
	type = MediaSegmentType.INTRO,
	startTicks = startSeconds * 10_000_000,
	endTicks = endSeconds * 10_000_000,
)
