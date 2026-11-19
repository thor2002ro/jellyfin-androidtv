package org.jellyfin.androidtv.ui.player.video

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.minutes

class LiveTvTimeshiftTests : StringSpec({
	"time-shift position is shown only within the current live program" {
		val start = LocalDateTime.of(2026, 11, 19, 12, 0)
		val timeline = LiveTvProgramTimeline(start, start.plusHours(1), 60.minutes, "Program")
		val origin = start.plusMinutes(10)
		liveTvTimeshiftPosition(timeline, origin, 20.minutes, start.plusMinutes(40)) shouldBe 30.minutes
		liveTvTimeshiftPosition(timeline, origin, (-11).minutes, start.plusMinutes(40)) shouldBe null
		liveTvTimeshiftPosition(timeline, origin, 20.minutes, start.plusMinutes(61)) shouldBe null
		liveTvTimeshiftPosition(timeline, origin, 20.minutes, start.plusMinutes(20)) shouldBe null
		liveTvTimeshiftPosition(timeline, origin, 20.minutes, start.plusHours(1)) shouldBe null
		liveTvTimeshiftPosition(timeline, null, 20.minutes, start.plusMinutes(40), 15.minutes) shouldBe 25.minutes
		liveTvTimeshiftPosition(timeline, null, 20.minutes, start.plusMinutes(40), 41.minutes) shouldBe null
		liveTvTimeshiftPosition(timeline, null, 20.minutes, start.plusMinutes(40)) shouldBe null
	}
	"program seeks map to the player window and cannot go beyond live" {
		liveTvTimeshiftSeekTarget(35.minutes, 30.minutes, 40.minutes, 20.minutes, 60.minutes) shouldBe 25.minutes
		liveTvTimeshiftSeekTarget(50.minutes, 30.minutes, 40.minutes, 20.minutes, 60.minutes) shouldBe 30.minutes
		liveTvTimeshiftSeekTarget(1.minutes, 30.minutes, 40.minutes, 20.minutes, 60.minutes) shouldBe 0.minutes
	}
})
