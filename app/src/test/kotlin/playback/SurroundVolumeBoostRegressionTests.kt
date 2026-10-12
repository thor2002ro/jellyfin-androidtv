package org.jellyfin.androidtv.playback

import android.os.Handler
import androidx.media3.common.Format
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.File
import org.jellyfin.playback.libvlc.LibVLCBackend
import org.jellyfin.playback.libvlc.LibVLCPlaybackOptions
import org.jellyfin.playback.media3.exoplayer.ExoPlayerBackend
import org.jellyfin.playback.mpv.LibMPVBackend
import org.jellyfin.playback.mpv.LibMPVPlaybackOptions
import org.jellyfin.playback.mpv.LibMPVVideoDecoder
import org.videolan.libvlc.MediaPlayer

class SurroundVolumeBoostRegressionTests : FunSpec({
	test("cancelling live start preserves queued audio output notifications") {
		val backend = mockk<ExoPlayerBackend>(relaxed = true)
		val liveQueue = mutableListOf<Runnable>()
		val audioQueue = mutableListOf<Runnable>()
		backend.setField("startHandler", queuedHandler(liveQueue))
		backend.setField("audioOutputHandler", queuedHandler(audioQueue))
		every { backend["onAudioOutputFormat"](any<Format>(), any<Long>()) } answers { callOriginal() }
		every { backend["clearPendingLiveStart"]() } answers { callOriginal() }
		every { backend["updateSurroundVolumeBoost"]() } returns Unit
		val pcm = Format.Builder().setSampleMimeType("audio/raw").setChannelCount(6).build()
		backend.callPrivate("onAudioOutputFormat", pcm, 0L)
		backend.callPrivate("clearPendingLiveStart")
		audioQueue.size shouldBe 1
		audioQueue.single().run()
		backend.getField("audioOutputFormat") shouldBe pcm
	}

	test("queued audio output from a replaced player cannot overwrite its successor") {
		val backend = mockk<ExoPlayerBackend>(relaxed = true)
		val queue = mutableListOf<Runnable>()
		backend.setField("audioOutputHandler", queuedHandler(queue))
		every { backend["onAudioOutputFormat"](any<Format>(), any<Long>()) } answers { callOriginal() }
		every { backend["updateSurroundVolumeBoost"]() } returns Unit
		backend.callPrivate("onAudioOutputFormat", Format.Builder().setSampleMimeType("audio/raw").build(), 0L)
		backend.setField("audioOutputGeneration", 1L)
		queue.single().run()
		backend.getField("audioOutputFormat") shouldBe null
	}

	test("VLC Off avoids native selected track reads") {
		val backend = mockk<LibVLCBackend>(relaxed = true)
		val native = mockk<MediaPlayer>(relaxed = true)
		backend.setField("player", native)
		backend.setField("playbackOptions", LibVLCPlaybackOptions(surroundVolumeBoost = 0))
		backend.setField("surroundBoostAllowed", true)
		every { backend["updateSurroundVolumeBoost"]() } answers { callOriginal() }
		every { backend["setSurroundBoostGain"](0) } returns Unit
		backend.callPrivate("updateSurroundVolumeBoost")
		verify(exactly = 0) { native.audioTrack }
	}

	test("MPV retries rejected filters only when the requested gain changes") {
		val backend = mockk<LibMPVBackend>(relaxed = true)
		var commands = 0
		every { backend["setSurroundBoostFilter"](any<String>()) } answers { callOriginal() }
		every { backend["runCommand"](anyVararg<String>()) } answers { commands++; false }
		repeat(3) { backend.callPrivate("setSurroundBoostFilter", "@jellyfin-surround-boost:lavfi=[volume=6dB]") }
		commands shouldBe 1
		backend.callPrivate("setSurroundBoostFilter", "@jellyfin-surround-boost:lavfi=[volume=9dB]")
		commands shouldBe 2
	}

	test("rewriting MPV native audio options refreshes gain after replacing the filter chain") {
		val backend = spyk(mockk<LibMPVBackend>(relaxed = true), recordPrivateCalls = true)
		backend.setField("playbackOptions", LibMPVPlaybackOptions())
		backend.setField("videoDecoder", LibMPVVideoDecoder.AUTOMATIC)
		backend.setField("presetDirectory", File("presets"))
		for (field in listOf("appliedNvidiaFallbackOptions", "appliedPresetOptions", "appliedCustomOptions")) {
			backend.setField(field, mutableSetOf<String>())
		}
		var audioFiltersWritten = false
		var gainUpdates = 0
		every { backend["currentPresetOptions"]() } returns emptyMap<String, String>()
		every { backend["currentCustomOptions"]() } returns emptyMap<String, String>()
		every { backend["setOption"](any<String>(), any<String>()) } answers {
			if (firstArg<String>() == "af") audioFiltersWritten = true
			true
		}
		every { backend["updateSurroundVolumeBoost"]() } answers {
			audioFiltersWritten shouldBe true
			gainUpdates++
		}
		// Invoke the real options path without crossing the JNI boundary.
		val method = LibMPVBackend::class.java.declaredMethods.single { it.name == "applyPlaybackOptions" }
		method.apply { isAccessible = true }.invoke(backend, null)
		gainUpdates shouldBe 1
	}

	test("VLC retries a rejected equalizer when gain changes rather than on identical notifications") {
		mockkStatic(MediaPlayer.Equalizer::class)
		try {
			val backend = mockk<LibVLCBackend>(relaxed = true)
			val native = mockk<MediaPlayer>()
			val equalizer = mockk<MediaPlayer.Equalizer>()
			backend.setField("player", native)
			every { MediaPlayer.Equalizer.create() } returns equalizer
			every { equalizer.setPreAmp(any()) } returns true
			every { native.setEqualizer(equalizer) } returns false
			every { backend["setSurroundBoostGain"](any<Int>()) } answers { callOriginal() }
			repeat(3) { backend.callPrivate("setSurroundBoostGain", 6) }
			verify(exactly = 1) { native.setEqualizer(equalizer) }
			backend.callPrivate("setSurroundBoostGain", 0)
			backend.callPrivate("setSurroundBoostGain", 6)
			verify(exactly = 2) { native.setEqualizer(equalizer) }
		} finally {
			unmockkStatic(MediaPlayer.Equalizer::class)
		}
	}
})

private fun queuedHandler(queue: MutableList<Runnable>) = mockk<Handler> {
	every { post(any()) } answers { queue += firstArg<Runnable>(); true }
	every { removeCallbacksAndMessages(null) } answers { queue.clear() }
}

private fun Any.setField(name: String, value: Any?) {
	backendClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

private val Any.backendClass get() = when (this) {
	is ExoPlayerBackend -> ExoPlayerBackend::class.java
	is LibMPVBackend -> LibMPVBackend::class.java
	else -> LibVLCBackend::class.java
}

private fun ExoPlayerBackend.getField(name: String): Any? =
	ExoPlayerBackend::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)

private fun Any.callPrivate(name: String, vararg arguments: Any?): Any? {
	return backendClass.declaredMethods.single { it.name == name && it.parameterCount == arguments.size }
		.apply { isAccessible = true }.invoke(this, *arguments)
}
