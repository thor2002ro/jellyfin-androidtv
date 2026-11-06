package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.androidtv.di.playbackModule
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class RewriteMediaManagerStartupTests : FunSpec({
	test("login queue cleanup does not construct an unused playback manager") {
		var creations = 0
		val player = lazy<PlaybackManager> { creations++; error("unused playback manager") }
		RewriteMediaManager(mockk(), player).clearAudioQueue()
		creations shouldBe 0
	}

	test("Home audio observers do not construct an unused playback manager") {
		var creations = 0
		val player = lazy<PlaybackManager> { creations++; error("unused playback manager") }
		val mediaManager = RewriteMediaManager(mockk(), player)

		mediaManager.addAudioEventListener(mockk(relaxed = true))

		mediaManager.hasAudioQueueItems() shouldBe false
		creations shouldBe 0
	}

	test("login cleanup and other playback callers share the same deferred player") {
		val manager = mockk<PlaybackManager>(relaxed = true)
		var creations = 0
		val application = koinApplication {
			modules(playbackModule, module {
				single<ApiClient> { mockk() }
				single<Lazy<PlaybackManager>> { lazy { creations++; manager } }
			})
		}
		try {
			val mediaManager = application.koin.get<MediaManager>()
			mediaManager.clearAudioQueue()
			creations shouldBe 0

			application.koin.get<PlaybackManager>() shouldBe manager
			mediaManager.clearAudioQueue()

			creations shouldBe 1
			verify(exactly = 1) { manager.state.stop() }
		} finally {
			application.close()
		}
	}
})
