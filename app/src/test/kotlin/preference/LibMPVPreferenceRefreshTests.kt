package org.jellyfin.androidtv.preference

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.mpv.LibMPVBackend
import org.jellyfin.playback.mpv.LibMPVPlaybackOptions
import org.jellyfin.playback.mpv.LibMPVVideoDecoder

class LibMPVPreferenceRefreshTests : FunSpec({
	test("a reused MPV backend reads updated preferences before creating the next native player") {
		// Run the real load path up to the native-player boundary, without JNI.
		val backend = mockk<LibMPVBackend>(relaxed = true)
		val initial = LibMPVPlaybackOptions(audioChannels = "auto", audioSpdif = "ac3,eac3,dts,dts-hd,truehd")
		var selected = initial
		backend.setField("playbackOptions", initial)
		backend.setField("playbackOptionsProvider", { selected })
		backend.setField("videoDecoder", LibMPVVideoDecoder.AUTOMATIC)
		backend.setField("videoDecoderProvider", { LibMPVVideoDecoder.SOFTWARE })
		val sessionField = LibMPVBackend::class.java.getDeclaredField("doviRequestSession")
		backend.setField("doviRequestSession", mockkClass(sessionField.type.kotlin, relaxed = true))
		every { backend["cancelNvidiaFallbackResync"](false) } returns Unit
		every { backend["stopNativeSubtitleOverlay"]() } returns Unit
		every { backend["clearVideoGeometry"]() } returns Unit
		every { backend["ensureInstanceOptions"](any<Boolean>()) } throws NativeBoundaryReached()
		every { backend.replaceItem(any()) } answers { callOriginal() }
		val stream = mockk<PlayableMediaStream> {
			every { queueEntry } returns QueueEntry()
			every { errorOrigin } returns null
			every { onAccepted } returns null
		}
		every { backend["setMedia"](stream) } answers { callOriginal() }
		val item = QueueEntry().apply { mediaStream = stream }

		selected = initial.copy(audioChannels = "stereo", audioSpdif = "")
		shouldThrow<NativeBoundaryReached> { backend.replaceItem(item) }
		backend.getField("playbackOptions") shouldBe selected
		backend.getField("videoDecoder") shouldBe LibMPVVideoDecoder.SOFTWARE

		// Switching back must restore the enabled codecs, not latch PCM forever.
		selected = initial
		shouldThrow<NativeBoundaryReached> { backend.replaceItem(item) }
		backend.getField("playbackOptions") shouldBe initial

		// Callers without preference providers retain explicitly supplied options.
		backend.setField("playbackOptionsProvider", null)
		backend.setField("videoDecoderProvider", null)
		shouldThrow<NativeBoundaryReached> { backend.replaceItem(item) }
		backend.getField("playbackOptions") shouldBe initial
		backend.getField("videoDecoder") shouldBe LibMPVVideoDecoder.SOFTWARE
	}
})

private class NativeBoundaryReached : RuntimeException()

private fun LibMPVBackend.setField(name: String, value: Any?) {
	LibMPVBackend::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

private fun LibMPVBackend.getField(name: String): Any? =
	LibMPVBackend::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)
