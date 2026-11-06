package org.jellyfin.androidtv.ui.player.video

import android.os.Bundle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.NextUpBehavior
import org.jellyfin.androidtv.preference.constant.StillWatchingBehavior
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.nextup.NextUpFragment
import org.jellyfin.androidtv.ui.playback.stillwatching.StillWatchingFragment
import org.jellyfin.androidtv.util.createBundle
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.liveStreamTargetOffset
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID
import kotlin.time.Duration

class VideoPlayerPromptTests : FunSpec({
	val preferences = mockk<UserPreferences>()
	val tracker = mockk<InteractionTrackerViewModel>()
	var nextUpBehavior = NextUpBehavior.EXTENDED
	var stillWatchingDue = false
	val nextItemId = UUID.randomUUID()
	beforeSpec {
		every { preferences[UserPreferences.nextUpBehavior] } answers { nextUpBehavior }
		every { tracker.getShowStillWatching() } answers { stillWatchingDue }
		every { tracker.onEpisodeWatched() } returns Unit
		every { tracker.notifyPlaybackState(any()) } returns Unit
		every { tracker.notifyStart(any()) } returns Unit
		every { preferences[UserPreferences.stillWatchingBehavior] } returns StillWatchingBehavior.SHORT
		startKoin { modules(module { single { preferences }; single { tracker } }) }
		mockkStatic("org.jellyfin.androidtv.util.BundleExtensionsKt")
		every { createBundle(any()) } answers {
			val values = mutableMapOf<String, String?>()
			val bundle = mockk<Bundle>(relaxed = true)
			every { bundle.putString(any(), any()) } answers { values[firstArg()] = secondArg() }
			every { bundle.getString(any()) } answers { values[firstArg()] }
			firstArg<(Bundle.() -> Unit)?>()?.invoke(bundle)
			bundle
		}
	}
	afterSpec {
		unmockkStatic("org.jellyfin.androidtv.util.BundleExtensionsKt")
		stopKoin()
	}

	test("prompt selection preserves priority, item IDs, disabled behavior and trailer exclusion") {
		for (behavior in NextUpBehavior.entries) {
			nextUpBehavior = behavior
			stillWatchingDue = true
			val destination = VideoPlayerFragment().selectPostPlaybackPrompt(BaseItemKind.EPISODE, nextItemId)
			destination?.fragment shouldBe StillWatchingFragment::class
			destination?.arguments?.getString(StillWatchingFragment.ARGUMENT_ITEM_ID) shouldBe nextItemId.toString()
		}

		nextUpBehavior = NextUpBehavior.MINIMAL
		stillWatchingDue = false
		val destination = VideoPlayerFragment().selectPostPlaybackPrompt(BaseItemKind.EPISODE, nextItemId)
		destination?.fragment shouldBe NextUpFragment::class
		destination?.arguments?.getString(NextUpFragment.ARGUMENT_ITEM_ID) shouldBe nextItemId.toString()
		nextUpBehavior = NextUpBehavior.DISABLED
		stillWatchingDue = false
		VideoPlayerFragment().selectPostPlaybackPrompt(BaseItemKind.EPISODE, nextItemId) shouldBe null
		nextUpBehavior = NextUpBehavior.EXTENDED
		stillWatchingDue = true
		VideoPlayerFragment().selectPostPlaybackPrompt(BaseItemKind.TRAILER, nextItemId) shouldBe null
		val liveItem = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.TV_CHANNEL)
		VideoPlayerFragment().onQueueEntryChanged(QueueEntry().apply {
			baseItem = liveItem
			liveStreamTargetOffset = Duration.ZERO
		})
		verify(exactly = 1) { tracker.notifyStart(liveItem) }
	}

	test("the old player continues when Next Up is disabled and Still watching is not due") {
		nextUpBehavior = NextUpBehavior.DISABLED
		stillWatchingDue = false
		val controller = mockk<PlaybackController>(relaxed = true)
		every { controller["itemComplete"]() } answers { callOriginal() }
		val item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE)
		every { controller.currentlyPlayingItem } returns item
		every { controller.nextItem } returns item.copy(id = nextItemId)
		for ((name, value) in listOf("interactionTracker" to tracker, "userPreferences" to lazyOf(preferences), "videoQueueManager" to lazyOf(VideoQueueManager()))) {
			PlaybackController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controller, value)
		}
		PlaybackController::class.java.getDeclaredMethod("itemComplete").apply { isAccessible = true }.invoke(controller)
		verify(exactly = 1) { controller.next() }
		every { controller.onIsPlayingChanged(any()) } answers { callOriginal() }
		controller.onIsPlayingChanged(false)
		verify(exactly = 1) { tracker.notifyPlaybackState(false) }
	}
})
