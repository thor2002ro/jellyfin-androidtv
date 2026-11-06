package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.NextUpBehavior
import org.jellyfin.androidtv.ui.playback.nextup.NextUpState
import org.jellyfin.androidtv.ui.playback.nextup.NextUpViewModel
import org.jellyfin.androidtv.ui.playback.stillwatching.StillWatchingState
import org.jellyfin.androidtv.ui.playback.stillwatching.StillWatchingViewModel
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

class PostPlaybackPromptTests : FunSpec({
	test("both prompts display the requested queued item rather than the current item") {
		val queue = VideoQueueManager()
		val item = BaseItemDto(id = UUID.randomUUID(), name = "Next item", type = BaseItemKind.MOVIE)
		queue.setCurrentVideoQueue(listOf(item.copy(id = UUID.randomUUID(), name = "Current item"), item))
		val preferences = mockk<UserPreferences>()
		every { preferences[UserPreferences.nextUpBehavior] } returns NextUpBehavior.MINIMAL
		val nextUp = NextUpViewModel(mockk(), queue, preferences)
		val stillWatching = StillWatchingViewModel(mockk(), queue, preferences, mockk())

		nextUp.setItemId(item.id)
		stillWatching.setItemId(item.id)

		nextUp.item.value?.baseItem shouldBe item
		stillWatching.item.value?.baseItem shouldBe item
		nextUp.item.value?.title shouldBe "Next item"
		stillWatching.item.value?.title shouldBe "Next item"
		for (id in listOf(null, UUID.randomUUID())) {
			nextUp.setItemId(id)
			stillWatching.setItemId(id)
			nextUp.state.value shouldBe NextUpState.NO_DATA
			stillWatching.state.value shouldBe StillWatchingState.NO_DATA
			nextUp.item.value shouldBe null
			stillWatching.item.value shouldBe null
		}
	}
})
