package org.jellyfin.playback.media3.exoplayer.subtitle

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleOutputBuffer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.jellyfin.playback.core.model.DEFAULT_SUBTITLE_TIMING_SPEED
import org.jellyfin.playback.core.model.MAX_SUBTITLE_TIMING_SPEED
import org.jellyfin.playback.core.model.MIN_SUBTITLE_TIMING_SPEED

@UnstableApi
class SubtitleTimingTests : FunSpec({
	test("speed scales subtitle timestamps while offset stays fixed") {
		val cue = Cue.Builder().setText("Test cue").build()
		val delegate = TestSubtitleOutputBuffer().apply {
			setContent(
				4_000_000L,
				TestSubtitle(eventTimeUs = 10_000_000L, endTimeUs = 12_000_000L, cue = cue),
				0L,
			)
		}
		val state = SubtitleTimingOffsetState(initialOffsetUs = 1_000_000L, initialSpeed = 1.25f)
		val output = OffsetSubtitleOutputBuffer(delegate, state)

		output.timeUs shouldBe 4_200_000L
		output.getEventTime(0) shouldBe 9_000_000L
		output.getNextEventTimeIndex(8_900_000L) shouldBe 0
		output.getNextEventTimeIndex(9_000_000L) shouldBe -1
		output.getCues(8_900_000L).shouldBeEmpty()
		output.getCues(9_400_000L) shouldBe listOf(cue)

		state.setTiming(state.offsetUs, 1f)
		output.getEventTime(0) shouldBe 11_000_000L
		state.setOffsetUs(2_000_000L)
		output.getEventTime(0) shouldBe 12_000_000L
	}

	test("speed is kept in a safe finite range") {
		val state = SubtitleTimingOffsetState()

		state.setTiming(state.offsetUs, 0.1f)
		state.speed shouldBe MIN_SUBTITLE_TIMING_SPEED
		state.setTiming(state.offsetUs, 3f)
		state.speed shouldBe MAX_SUBTITLE_TIMING_SPEED
		state.setTiming(state.offsetUs, Float.NaN)
		state.speed shouldBe DEFAULT_SUBTITLE_TIMING_SPEED
	}

	test("unchanged timing does not notify renderers again") {
		val state = SubtitleTimingOffsetState()
		var notifications = 0
		state.addTimingListener(listener = { notifications++ })

		state.setTiming(0L, DEFAULT_SUBTITLE_TIMING_SPEED)
		state.setTiming(0L, DEFAULT_SUBTITLE_TIMING_SPEED)
		notifications shouldBe 1

		state.setTiming(state.offsetUs, 1.01f)
		notifications shouldBe 2
	}

	test("concurrent offset adjustments do not lose updates") {
		val state = SubtitleTimingOffsetState()
		val start = CountDownLatch(1)
		val workers = List(4) {
			thread {
				start.await()
				repeat(1_000) { state.adjustOffsetUs(1L) }
			}
		}

		start.countDown()
		workers.forEach(Thread::join)

		state.offsetUs shouldBe 4_000L
	}

	test("listener delivery stays ordered with registration and removal") {
		val state = SubtitleTimingOffsetState()
		val initialEntered = CountDownLatch(1)
		val releaseInitial = CountDownLatch(1)
		val updateStarted = CountDownLatch(1)
		val updateDelivered = CountDownLatch(1)
		val firstDelivery = AtomicBoolean(true)
		val notifications = CopyOnWriteArrayList<SubtitleTiming>()
		val listener = SubtitleTimingOffsetState.TimingListener { timing ->
			if (firstDelivery.compareAndSet(true, false)) {
				initialEntered.countDown()
				releaseInitial.await()
			}
			notifications += timing
			if (timing.speed == 1.01f) updateDelivered.countDown()
		}
		val registration = thread { state.addTimingListener(listener) }
		val initialEnteredInTime = initialEntered.await(1, TimeUnit.SECONDS)
		if (!initialEnteredInTime) releaseInitial.countDown()
		initialEnteredInTime shouldBe true
		val update = thread {
			updateStarted.countDown()
			state.setTiming(0L, 1.01f)
		}

		val updateStartedInTime = updateStarted.await(1, TimeUnit.SECONDS)
		val updateOvertookInitialDelivery = updateStartedInTime && updateDelivered.await(1, TimeUnit.SECONDS)
		releaseInitial.countDown()
		registration.join()
		update.join()
		updateStartedInTime shouldBe true
		updateOvertookInitialDelivery shouldBe false
		notifications.map(SubtitleTiming::speed) shouldBe listOf(1f, 1.01f)

		state.removeTimingListener(listener)
		val callbackEntered = CountDownLatch(1)
		val releaseCallback = CountDownLatch(1)
		val removalStarted = CountDownLatch(1)
		val removalReturned = CountDownLatch(1)
		val sequence = AtomicInteger()
		var secondCallbackOrder = 0
		var removalOrder = 0
		state.addTimingListener(
			listener = {
				callbackEntered.countDown()
				releaseCallback.await()
			},
			notifyCurrent = false,
		)
		val removedListener = SubtitleTimingOffsetState.TimingListener {
			secondCallbackOrder = sequence.incrementAndGet()
		}
		state.addTimingListener(removedListener, notifyCurrent = false)
		val secondUpdate = thread { state.setTiming(0L, 1.02f) }
		val callbackEnteredInTime = callbackEntered.await(1, TimeUnit.SECONDS)
		if (!callbackEnteredInTime) releaseCallback.countDown()
		callbackEnteredInTime shouldBe true
		val removal = thread {
			removalStarted.countDown()
			state.removeTimingListener(removedListener)
			removalOrder = sequence.incrementAndGet()
			removalReturned.countDown()
		}

		val removalStartedInTime = removalStarted.await(1, TimeUnit.SECONDS)
		val removalOvertookCallback = removalStartedInTime && removalReturned.await(1, TimeUnit.SECONDS)
		releaseCallback.countDown()
		secondUpdate.join()
		removal.join()
		removalStartedInTime shouldBe true
		removalOvertookCallback shouldBe false
		secondCallbackOrder shouldBe 1
		removalOrder shouldBe 2
	}

	test("offset-only mutations preserve speed and Java construction") {
		val state = SubtitleTimingOffsetState(initialOffsetUs = 1_000L)

		state.setTiming(state.offsetUs, 1.01f)
		state.setOffsetUs(2_000L)

		state.offsetUs shouldBe 2_000L
		state.speed shouldBe 1.01f
		SubtitleTimingOffsetState::class.java.constructors.any { constructor ->
			constructor.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
		} shouldBe true
	}

	test("timing support excludes playback paths that bypass timing correction") {
		fun supported(
			mimeType: String = MimeTypes.APPLICATION_SUBRIP,
			isExternal: Boolean = false,
			isLiveTv: Boolean = false,
			parsedDuringExtraction: Boolean = false,
			usesLibassOverlay: Boolean = false,
		) = isSubtitleTimingAdjustmentSupported(
			format = Format.Builder().setSampleMimeType(mimeType).build(),
			isExternal = isExternal,
			isLiveTv = isLiveTv,
			subtitlesParsedDuringExtraction = parsedDuringExtraction,
			usesLibassOverlay = usesLibassOverlay,
		)

		supported() shouldBe true
		supported(isLiveTv = true) shouldBe false
		supported(parsedDuringExtraction = true) shouldBe false
		supported(isExternal = true, parsedDuringExtraction = true) shouldBe true
		supported(mimeType = MimeTypes.TEXT_SSA, usesLibassOverlay = true) shouldBe false
		supported(mimeType = MimeTypes.TEXT_SSA) shouldBe true
	}

	test("renderer refresh is skipped while timing is unsupported") {
		val state = SubtitleTimingOffsetState()
		var supported = false
		var playerRequests = 0
		SubtitleTimingRendererInvalidator(
			state = state,
			playerProvider = {
				playerRequests++
				null
			},
			refreshSupported = { supported },
		)

		state.setTiming(state.offsetUs, 1.01f)
		playerRequests shouldBe 0

		supported = true
		state.setTiming(state.offsetUs, 1.02f)
		playerRequests shouldBe 1
	}
})

private class TestSubtitleOutputBuffer : SubtitleOutputBuffer() {
	override fun release() = Unit
}

private class TestSubtitle(
	private val eventTimeUs: Long,
	private val endTimeUs: Long,
	private val cue: Cue,
) : Subtitle {
	override fun getEventTimeCount(): Int = 1

	override fun getEventTime(index: Int): Long = eventTimeUs

	override fun getNextEventTimeIndex(timeUs: Long): Int =
		if (timeUs < eventTimeUs) 0 else -1

	override fun getCues(timeUs: Long): List<Cue> =
		if (timeUs in eventTimeUs..<endTimeUs) listOf(cue) else emptyList()
}
