package org.jellyfin.androidtv.ui.player.video

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.StillWatchingBehavior
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

class StillWatchingPlaybackTests : FunSpec({
	var now = 1_000L
	beforeSpec {
		mockkStatic(SystemClock::class)
		mockkStatic("androidx.lifecycle.ViewModelKt")
		every { SystemClock.elapsedRealtime() } answers { now }
		every { any<ViewModel>().viewModelScope } returns CoroutineScope(Job().apply { cancel() })
	}
	afterSpec {
		unmockkStatic(SystemClock::class)
		unmockkStatic("androidx.lifecycle.ViewModelKt")
	}

	test("inactivity counts actual playing time and respects pause, interaction and confirmation resets") {
		val preferences = mockk<UserPreferences>()
		every { preferences[UserPreferences.screensaverInAppEnabled] } returns false
		every { preferences[UserPreferences.stillWatchingBehavior] } returns StillWatchingBehavior.SHORT
		val manager = mockk<PlaybackManager>()
		every { manager.state.playState } returns MutableStateFlow(PlayState.STOPPED)
		val tracker = InteractionTrackerViewModel(preferences, manager)
		val episode = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE)

		tracker.notifyStart(episode)
		tracker.notifyPlaybackState(true)
		now += 59.minutes.inWholeMilliseconds
		tracker.onEpisodeWatched()
		tracker.getShowStillWatching() shouldBe false
		tracker.notifyStart(episode)
		tracker.notifyPlaybackState(false)
		now += 120.minutes.inWholeMilliseconds
		tracker.getShowStillWatching() shouldBe false
		tracker.notifyPlaybackState(true)
		now += 1.minutes.inWholeMilliseconds
		tracker.getShowStillWatching() shouldBe true

		tracker.notifyInteraction(canCancel = false, userInitiated = true)
		now += 1.minutes.inWholeMilliseconds
		tracker.onEpisodeWatched()
		tracker.getShowStillWatching() shouldBe false
		tracker.notifyStillWatching()
		repeat(2) {
			tracker.notifyStart(episode)
			tracker.notifyPlaybackState(true)
			now += 1.minutes.inWholeMilliseconds
			tracker.onEpisodeWatched()
		}
		tracker.getShowStillWatching() shouldBe true
		tracker.notifyStillWatching()
		tracker.notifyStart(episode.copy(type = BaseItemKind.MOVIE))
		tracker.notifyPlaybackState(true)
		now += 60.minutes.inWholeMilliseconds
		tracker.getShowStillWatching() shouldBe true
		tracker.notifyStillWatching()
		tracker.notifyStart(episode.copy(type = BaseItemKind.TV_CHANNEL))
		tracker.notifyPlaybackState(true)
		now += 120.minutes.inWholeMilliseconds
		tracker.getShowStillWatching() shouldBe false

		tracker.notifyStillWatching()
		tracker.notifyStart(episode)
		tracker.notifyInteraction(canCancel = false, userInitiated = true)
		tracker.notifyStart(episode.copy(name = "Same episode after resume"))
		tracker.onEpisodeWatched()
		tracker.notifyStart(episode.copy(id = UUID.randomUUID()))
		tracker.onEpisodeWatched()
		tracker.getShowStillWatching() shouldBe false

		tracker.notifyStillWatching()
		tracker.notifyStart(episode)
		tracker.notifyInteraction(canCancel = false, userInitiated = true)
		repeat(2) {
			tracker.notifyStart(episode.copy(id = UUID.randomUUID()))
			tracker.onEpisodeWatched()
		}
		tracker.getShowStillWatching() shouldBe true
	}
})
