package org.jellyfin.playback.media3.exoplayer

import android.os.Handler
import android.os.Looper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic

class ExoPlayerStartupTests : FunSpec({
	test("stopping an unused backend leaves the player and native subtitles uninitialized") {
		mockkStatic(Looper::class)
		mockkConstructor(Handler::class)
		try {
			every { Looper.getMainLooper() } returns mockk()
			every { anyConstructed<Handler>().removeCallbacksAndMessages(any()) } just Runs
			val backend = ExoPlayerBackend(mockk(), ExoPlayerOptions(enableLibass = true, baseDataSourceFactory = mockk()))
			val player = ExoPlayerBackend::class.java.getDeclaredField("exoPlayerDelegate").apply { isAccessible = true }
			var creations = 0
			player.set(backend, lazy { creations++; error("stop must not create a player") })

			backend.stop()
			backend.stop()

			creations shouldBe 0
			(player.get(backend) as Lazy<*>).isInitialized() shouldBe false
		} finally {
			unmockkConstructor(Handler::class)
			unmockkStatic(Looper::class)
		}
	}
})
