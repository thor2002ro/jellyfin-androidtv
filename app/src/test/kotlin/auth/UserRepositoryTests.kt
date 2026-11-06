package org.jellyfin.androidtv.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.auth.repository.UserRepositoryImpl
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.sdk.model.api.UserDto
import java.util.UUID

class UserRepositoryTests : FunSpec({
	test("server and user changes clear transient queue choices but a user refresh retains them") {
		val queue = VideoQueueManager()
		val repository = UserRepositoryImpl(queue)
		val user = UserDto(id = UUID.randomUUID(), serverId = "one")
		try {
			repository.setCurrentUser(user)
			queue.setLastPlayedAudioLanguageIsoCode("jpn")
			repository.setCurrentUser(user.copy(name = "Updated"))
			queue.getLastPlayedAudioLanguageIsoCode() shouldBe "ja"
			repository.setCurrentUser(user.copy(serverId = "two"))
			queue.getLastPlayedAudioLanguageIsoCode() shouldBe null
			queue.setLastPlayedSubtitleLanguageIsoCode("")
			repository.setCurrentUser(user.copy(id = UUID.randomUUID(), serverId = "two"))
			queue.getLastPlayedSubtitleLanguageIsoCodes() shouldBe null
			queue.setLastPlayedAudioLanguageIsoCode("eng")
			repository.setCurrentUser(null)
			queue.getLastPlayedAudioLanguageIsoCode() shouldBe null
		} finally {
			repository.setCurrentUser(null)
		}
	}
})
