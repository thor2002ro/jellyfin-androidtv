package org.jellyfin.androidtv.test

import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentAction
import org.jellyfin.androidtv.ui.player.video.findAutoSkipSegment
import org.jellyfin.androidtv.ui.player.video.findPromptSegment
import org.jellyfin.androidtv.ui.player.video.stableKey
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

object PlayerFlowLogicSuite {
	fun run(scenarioFilter: String?): List<PlaybackTestResult> = listOf(
		"intro-skip-prompt" to ::checkIntroPrompt,
		"intro-auto-skip" to ::checkIntroAutoSkip,
	).filter { (scenario) -> scenarioFilter == null || scenarioFilter == scenario }
		.map { (scenario, check) ->
			runCatching(check).fold(
				onSuccess = { PlaybackTestResult(PlaybackTestStatus.PASS, "player-flow", scenario = scenario, detail = it) },
				onFailure = { PlaybackTestResult(PlaybackTestStatus.FAIL, "player-flow", scenario = scenario, detail = it.message.orEmpty()) },
			)
		}

	private fun checkIntroPrompt(): String {
		val intro = intro()
		val action = { _: MediaSegmentDto -> MediaSegmentAction.ASK_TO_SKIP }
		check(findPromptSegment(listOf(intro), 4.seconds, action) == null)
		check(findPromptSegment(listOf(intro), 5.seconds, action) === intro)
		check(findPromptSegment(listOf(intro), 15.seconds, action) == null)
		return "prompt entered at 5s and cleared at 15s"
	}

	private fun checkIntroAutoSkip(): String {
		val intro = intro()
		val action = { _: MediaSegmentDto -> MediaSegmentAction.SKIP }
		check(findAutoSkipSegment(listOf(intro), 4.seconds, 5.seconds, emptySet(), action) === intro)
		check(findAutoSkipSegment(listOf(intro), 5.seconds, 6.seconds, setOf(intro.stableKey), action) == null)
		check(findAutoSkipSegment(listOf(intro), 1.seconds, 10.seconds, emptySet(), action) == null)
		return "natural boundary skipped once; manual seek was preserved"
	}

	private fun intro() = MediaSegmentDto(
		id = UUID.randomUUID(),
		itemId = UUID.randomUUID(),
		type = MediaSegmentType.INTRO,
		startTicks = 5 * 10_000_000L,
		endTicks = 15 * 10_000_000L,
	)
}
