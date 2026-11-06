package org.jellyfin.androidtv.util.profile.codec

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class MediaCodecQueryTests : FunSpec({
	test("software video exclusion does not hide native software audio decoders") {
		val audio = mockk<MediaCodecInfo> {
			every { isEncoder } returns false
			every { name } returns "OMX.google.ac3.decoder"
			every { isSoftwareOnly } returns true
			every { supportedTypes } returns arrayOf("audio/ac3")
		}
		val video = mockk<MediaCodecInfo> {
			every { isEncoder } returns false
			every { name } returns "OMX.google.h264.decoder"
			every { isSoftwareOnly } returns true
			every { supportedTypes } returns arrayOf("video/avc")
		}
		val codecs = mockk<MediaCodecList> { every { codecInfos } returns arrayOf(audio, video) }
		val query = MediaCodecQuery(codecs, softwareCodecsEnabled = false)
		query.hasCodecForMime("audio/ac3") shouldBe true
		query.hasCodecForMime("video/avc") shouldBe false
	}
	test("pre Android 10 software video decoders are recognized by codec name") {
		listOf(
			"OMX.google.h264.decoder",
			"OMX.ffmpeg.video.decoder",
			"OMX.SEC.avc.sw.dec",
			"OMX.qcom.video.decoder.hevcswvdec",
			"c2.android.hevc.decoder",
			"c2.google.av1.decoder",
			"software.custom.decoder",
		).forEach { codecName ->
			isSoftwareVideoCodecName(codecName) shouldBe true
		}
	}

	test("pre Android 10 hardware video decoders remain available") {
		listOf(
			"OMX.MTK.VIDEO.DECODER.HEVC",
			"OMX.qcom.video.decoder.hevc",
			"c2.amlogic.hevc.decoder",
			"arc.video.decoder",
		).forEach { codecName ->
			isSoftwareVideoCodecName(codecName) shouldBe false
		}
	}
})
