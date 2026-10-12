package test

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.test.PlaybackHealthSample
import org.jellyfin.androidtv.test.PlaybackResourceSample
import org.jellyfin.androidtv.test.evaluatePlaybackHealth
import org.jellyfin.androidtv.test.evaluateResourceTrend

class PlaybackHealthTests : FunSpec({
	test("healthy playback requires decoded frame growth and an audio decoder") {
		val result = evaluatePlaybackHealth(
			before = PlaybackHealthSample(10, 0, null),
			after = PlaybackHealthSample(80, 2, "c2.android.aac.decoder"),
		)

		result.failures shouldBe emptyList()
	}

	test("health reports stalled video missing audio and excessive frame drops independently") {
		val result = evaluatePlaybackHealth(
			before = PlaybackHealthSample(10, 1, null),
			after = PlaybackHealthSample(10, 12, null),
		)

		result.failures shouldContain "decoded frames did not increase"
		result.failures shouldContain "audio decoder was not initialized"
		result.failures shouldContain "dropped 11 of 11 observed frames"
	}

	test("resource trend rejects repeated growth beyond the configured budgets") {
		val result = evaluateResourceTrend(
			listOf(
				PlaybackResourceSample(100, 20_000),
				PlaybackResourceSample(105, 25_000),
				PlaybackResourceSample(111, 33_000),
			),
			maxFileDescriptorGrowth = 8,
			maxMemoryGrowthKb = 10_000,
		)

		result.failures shouldContain "file descriptors grew by 11"
		result.failures shouldContain "PSS grew by 13000 KiB"
	}
})
