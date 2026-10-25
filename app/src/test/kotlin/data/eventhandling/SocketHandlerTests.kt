package org.jellyfin.androidtv.data.eventhandling

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.coroutineScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.PlaystateMessage
import org.jellyfin.sdk.model.api.PlaystateRequest
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class SocketHandlerTests : FunSpec({
	test("remote pause and unpause are explicit while play-pause toggles") {
		mockkStatic("androidx.lifecycle.LifecycleKt")
		mockkStatic(Dispatchers::class)
		try {
			val owner = mockk<Lifecycle>()
			val scope = mockk<LifecycleCoroutineScope>()
			every { scope.coroutineContext } returns Job().apply { cancel() }
			every { owner.coroutineScope } returns scope
			val main = object : MainCoroutineDispatcher() {
				override val immediate get() = this
				override fun isDispatchNeeded(context: CoroutineContext) = false
				override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
			}
			every { Dispatchers.Main } returns main
			var paused = false
			val controller = mockk<PlaybackController>(relaxed = true)
			every { controller.isPaused } answers { paused }
			every { controller.currentPosition } returns 12_345L
			every { controller.pause() } answers { paused = true }
			every { controller.play(any()) } answers { paused = false }
			every { controller.playPause() } answers { paused = !paused }
			val mediaManager = mockk<MediaManager>()
			every { mediaManager.hasAudioQueueItems() } returns false
			val container = PlaybackControllerContainer().apply { playbackController = controller }
			val handler = SocketHandler(mockk(), mockk(), mockk(), mediaManager, container, mockk(), mockk(), mockk(), owner)
			val receive = SocketHandler::class.java.getDeclaredMethod("onPlayStateMessage", PlaystateMessage::class.java, Continuation::class.java)
				.apply { isAccessible = true }
			suspend fun send(command: PlaystateCommand) = suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
				receive.invoke(handler, PlaystateMessage(data = PlaystateRequest(command = command), messageId = UUID.randomUUID()), continuation)
			}

			repeat(2) {
				send(PlaystateCommand.PAUSE)
				paused shouldBe true
			}
			repeat(2) {
				send(PlaystateCommand.UNPAUSE)
				paused shouldBe false
			}
			verify(exactly = 1) { controller.play(12_345L) }
			for (expectedPaused in listOf(true, false)) {
				send(PlaystateCommand.PLAY_PAUSE)
				paused shouldBe expectedPaused
			}
			verify(exactly = 2) { controller.playPause() }
		} finally {
			unmockkStatic(Dispatchers::class)
			unmockkStatic("androidx.lifecycle.LifecycleKt")
		}
	}

	test("stream badge invalidation batches changed items into lean Jellyfin requests") {
		val changedIds = List(101) { UUID.randomUUID() }.toSet()

		val requests = createSeriesStreamBadgeInvalidationRequests(changedIds)

		requests.size shouldBe 2
		requests[0].ids?.size shouldBe 100
		requests[1].ids?.size shouldBe 1
		requests.forEach { request ->
			request.fields shouldBe setOf(ItemFields.PARENT_ID)
			request.limit shouldBe request.ids?.size
			request.enableImages shouldBe false
			request.enableUserData shouldBe false
			request.enableTotalRecordCount shouldBe false
		}
	}
})
