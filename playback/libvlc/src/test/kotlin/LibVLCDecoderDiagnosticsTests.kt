package org.jellyfin.playback.libvlc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.IOException

class LibVLCDecoderDiagnosticsTests : StringSpec({
	"decoder evidence is ignored until the current playback marker arrives" {
		val parser = LibVLCDecoderLogParser()
		parser.beginSession("item-1")

		parser.accept("libvlc decoder: using video decoder module \"mediacodec\"")
		parser.snapshot() shouldBe LibVLCDecoderDiagnostics()

		parser.accept("I/JellyfinVLCDecoder: ${libVLCDecoderSessionMarker("item-1")}")
		parser.accept("libvlc decoder: using video decoder module \"mediacodec\"")
		parser.snapshot().video shouldBe LibVLCDecoderInfo("mediacodec", "hw")
	}

	"OMX component replaces the generic hardware module" {
		val parser = activeParser()

		parser.accept("libvlc decoder: using video decoder module \"mediacodec\"")
		parser.accept("I/ACodec: [OMX.MTK.VIDEO.DECODER.HEVC] configured")

		parser.snapshot().video shouldBe LibVLCDecoderInfo("OMX.MTK.VIDEO.DECODER.HEVC", "hw")
	}

	"OMX component is sufficient when LibVLC omits its decoder module log" {
		val parser = activeParser()

		parser.accept("I/MediaCodec: [OMX.MTK.VIDEO.DECODER.AVC] setting surface generation to 5643265")

		parser.snapshot().video shouldBe LibVLCDecoderInfo("OMX.MTK.VIDEO.DECODER.AVC", "hw")
	}

	"Codec2 component seen before module selection is retained" {
		val parser = activeParser()

		parser.accept("D/CCodec: allocate(c2.mtk.avc.decoder)")
		parser.accept("libvlc decoder: using video decoder module \"mediacodec\"")

		parser.snapshot().video shouldBe LibVLCDecoderInfo("c2.mtk.avc.decoder", "hw")
	}

	"software fallback replaces an earlier hardware component" {
		val parser = activeParser()
		parser.accept("libvlc decoder: using video decoder module \"mediacodec\"")
		parser.accept("I/ACodec: [OMX.MTK.VIDEO.DECODER.HEVC] configured")

		parser.accept("libvlc decoder: using video decoder module \"avcodec\"")

		parser.snapshot().video shouldBe LibVLCDecoderInfo("avcodec", "sw")
	}

	"audio decoder evidence remains separate from video" {
		val parser = activeParser()
		parser.accept("libvlc decoder: using video decoder module \"mediacodec\"")
		parser.accept("libvlc decoder: using audio decoder module \"avcodec\"")
		parser.accept("I/ACodec: [OMX.MTK.VIDEO.DECODER.AVC] configured")

		parser.snapshot() shouldBe LibVLCDecoderDiagnostics(
			video = LibVLCDecoderInfo("OMX.MTK.VIDEO.DECODER.AVC", "hw"),
			audio = LibVLCDecoderInfo("avcodec", "sw"),
		)
	}

	"a new playback marker clears prior decoder evidence" {
		val parser = activeParser()
		parser.accept("libvlc decoder: using video decoder module \"avcodec\"")

		parser.beginSession("item-2")
		parser.accept("I/JellyfinVLCDecoder: ${libVLCDecoderSessionMarker("item-2")}")

		parser.snapshot() shouldBe LibVLCDecoderDiagnostics()
	}

	"ending playback clears decoder evidence and ignores later lines" {
		val parser = activeParser()
		parser.accept("libvlc decoder: using video decoder module \"avcodec\"")

		parser.endSession()
		parser.accept("libvlc decoder: using video decoder module \"mediacodec\"")

		parser.snapshot() shouldBe LibVLCDecoderDiagnostics()
	}

	"encoder and unrelated component names are ignored" {
		val parser = activeParser()
		parser.accept("I/ACodec: [OMX.MTK.VIDEO.ENCODER.AVC] configured")
		parser.accept("D/CCodec: allocate(c2.android.aac.decoder)")
		parser.accept("libvlc decoder: using video decoder module \"mediacodec\"")

		parser.snapshot().video shouldBe LibVLCDecoderInfo("mediacodec", "hw")
	}

	"log reader follows only the current app process" {
		libVLCDecoderLogcatCommand(42) shouldBe listOf(
			"logcat",
			"-T",
			"1",
			"--pid=42",
			"-v",
			"brief",
		)
	}

	"log reader starts on demand and capture failure does not affect playback" {
		var starts = 0
		val reader = LibVLCDecoderLogReader(
			parser = LibVLCDecoderLogParser(),
			processId = 42,
			processStarter = {
				starts++
				throw IOException("logcat unavailable")
			},
			markerWriter = {},
		)
		starts shouldBe 0

		reader.beginSession("item-1")

		starts shouldBe 1
		reader.snapshot() shouldBe LibVLCDecoderDiagnostics()
		reader.close()
	}
})

private fun activeParser() = LibVLCDecoderLogParser().apply {
	beginSession("item-1")
	accept("I/JellyfinVLCDecoder: ${libVLCDecoderSessionMarker("item-1")}")
}
